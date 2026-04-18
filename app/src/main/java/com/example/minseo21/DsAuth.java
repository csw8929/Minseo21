package com.example.minseo21;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NAS 자격 증명 + 세션 + base URL 해석 소유.
 * 단일 스레드 ExecutorService 는 DsFileStation/DsPlayback 등 NAS 관련 모든 작업이 공유한다
 * (FileStation API 는 세션당 1개 커넥션만 안정).
 */
final class DsAuth {
    private static final String TAG = "NAS";

    static volatile String cachedSid     = null;
    /** 현재 접속 중인 base URL 캐시 — login 시 LAN probe 결과 또는 cfgBaseUrl */
    static volatile String resolvedBase  = null;

    private static final AtomicBoolean networkMonitorStarted = new AtomicBoolean(false);
    private static volatile Network lastKnownNetwork = null;

    static final ExecutorService executor = Executors.newSingleThreadExecutor();
    static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 런타임 인증 정보 (NasCredentialStore 에서 초기화) ─────────────────────
    static volatile String cfgBaseUrl     = DsFileConfig.BASE_URL;
    static volatile String cfgLanUrl      = DsFileConfig.LAN_URL;
    static volatile String cfgUser        = DsFileConfig.USER;
    static volatile String cfgPass        = DsFileConfig.PASS;
    static volatile String cfgBasePath    = DsFileConfig.BASE_PATH;
    static volatile String cfgPosDir      = DsFileConfig.POS_DIR;

    private DsAuth() {}

    /**
     * NAS 인증 정보 적용. cachedSid/resolvedBase/file_id 캐시 모두 무효화.
     * baseUrl 은 DDNS 또는 공인 IP 직결 URL (trailing slash 없이).
     */
    static void init(String baseUrl, String lanUrl, String user, String pass,
                     String basePath, String posDir) {
        cfgBaseUrl  = baseUrl;
        cfgLanUrl   = lanUrl;
        cfgUser     = user;
        cfgPass     = pass;
        cfgBasePath = basePath;
        cfgPosDir   = posDir;
        cachedSid    = null;
        resolvedBase = null;
        DsFileStation.clearFileIdCache();
        Log.i(TAG, "NAS 인증 정보 적용 완료 (baseUrl=" + cfgBaseUrl + ")");
    }

    /**
     * 네트워크 전환(5G↔WiFi) 감지하여 resolvedBase / SID 캐시 무효화.
     * LAN 대 외부망 구분이 바뀌므로 다음 login 에서 재 probe 필요.
     */
    static void startNetworkMonitoring(Context ctx) {
        if (!networkMonitorStarted.compareAndSet(false, true)) return;
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager null — 네트워크 모니터링 비활성");
            return;
        }
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (lastKnownNetwork != null && !lastKnownNetwork.equals(network)) {
                        Log.i(TAG, "네트워크 전환 감지 → resolvedBase/SID 캐시 초기화");
                        resolvedBase = null;
                        cachedSid = null;
                    }
                    lastKnownNetwork = network;
                }
                @Override public void onLost(Network network) {
                    Log.i(TAG, "네트워크 끊김 → 캐시 초기화");
                    resolvedBase = null;
                    cachedSid = null;
                    lastKnownNetwork = null;
                }
            });
            Log.i(TAG, "startNetworkMonitoring OK");
        } catch (Exception e) {
            networkMonitorStarted.set(false);
            Log.w(TAG, "startNetworkMonitoring 실패: " + e.getMessage());
        }
    }

    /**
     * 로그인 (비동기). 첫 호출 시 LAN_URL probe → 실패 시 cfgBaseUrl(DDNS/공인IP).
     * 성공 시 SID + resolvedBase 캐시 저장 후 콜백.
     */
    static void login(DsFileApiClient.Callback<String> cb) {
        executor.execute(() -> {
            try {
                if (resolvedBase == null) {
                    resolvedBase = resolveBase();
                    Log.i(TAG, "resolvedBase=" + resolvedBase);
                }
                String url = resolvedBase + "/webapi/auth.cgi"
                        + "?api=SYNO.API.Auth&version=6&method=login"
                        + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                        + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                        + "&session=FileStation&format=sid";
                Log.d(TAG, "login → " + resolvedBase);
                String body = DsHttp.httpGet(url);
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    String sid = json.getJSONObject("data").getString("sid");
                    cachedSid = sid;
                    Log.i(TAG, "login OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "…");
                    mainHandler.post(() -> cb.onResult(sid));
                } else {
                    int code = json.optJSONObject("error") != null
                            ? json.getJSONObject("error").optInt("code", -1) : -1;
                    String msg = "로그인 실패 (code=" + code + ")";
                    Log.e(TAG, msg);
                    mainHandler.post(() -> cb.onError(msg));
                }
            } catch (Exception e) {
                Log.e(TAG, "login exception", e);
                mainHandler.post(() -> cb.onError("NAS 연결 오류: " + e.getMessage()));
            }
        });
    }

    /** API 호출에 사용할 base URL — LAN 성공 시 LAN_URL, 아니면 cfgBaseUrl. */
    static String apiBase() {
        return resolvedBase != null ? resolvedBase : cfgBaseUrl;
    }

    /**
     * base URL 결정 — LAN_URL probe 성공 시 LAN, 아니면 cfgBaseUrl.
     * executor 스레드에서만 호출 (probeUrl 이 네트워크 호출).
     */
    private static String resolveBase() {
        if (cfgLanUrl != null && !cfgLanUrl.isEmpty() && DsHttp.probeUrl(cfgLanUrl)) {
            Log.i(TAG, "LAN NAS 연결: " + cfgLanUrl);
            return cfgLanUrl;
        }
        Log.i(TAG, "외부 연결: " + cfgBaseUrl);
        return cfgBaseUrl;
    }

    /** 동기 재로그인 — executor 내부에서만 호출. 성공 시 새 SID 반환, 실패 시 null (cachedSid 도 클리어). */
    static String reLoginSync() {
        try {
            String base = resolvedBase != null ? resolvedBase : cfgBaseUrl;
            String loginUrl = base + "/webapi/auth.cgi"
                    + "?api=SYNO.API.Auth&version=6&method=login"
                    + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                    + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                    + "&session=FileStation&format=sid";
            String body = DsHttp.httpGet(loginUrl);
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("success", false)) {
                String sid = json.getJSONObject("data").getString("sid");
                cachedSid = sid;
                Log.i(TAG, "reLoginSync OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "…");
                return sid;
            }
        } catch (Exception e) {
            Log.w(TAG, "reLoginSync 실패: " + e.getMessage());
        }
        // 만료된 SID 를 들고 있으면 후속 호출도 모두 105/106 으로 터지므로 클리어
        cachedSid = null;
        return null;
    }
}
