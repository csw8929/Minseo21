package com.example.minseo21;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Synology FileStation HTTP API 클라이언트.
 * 단일 스레드 ExecutorService 사용 (Room DB executor 와 별개).
 * 결과는 메인 스레드로 콜백.
 */
public class DsFileApiClient {

    private static final String TAG = "NAS";
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT    = 15_000;

    private static volatile String cachedSid     = null;
    private static volatile String resolvedBase  = null; // QuickConnect 해석 결과 캐시
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 런타임 인증 정보 (NasCredentialStore 에서 초기화) ─────────────────────
    // DsFileConfig 상수를 fallback 으로 사용; 앱 시작 시 init() 로 덮어씀.
    private static volatile String cfgBaseUrl     = DsFileConfig.BASE_URL;
    private static volatile String cfgLanUrl      = DsFileConfig.LAN_URL;
    private static volatile String cfgUser        = DsFileConfig.USER;
    private static volatile String cfgPass        = DsFileConfig.PASS;
    private static volatile String cfgBasePath    = DsFileConfig.BASE_PATH;
    private static volatile String cfgPosDir      = DsFileConfig.POS_DIR;
    private static volatile String cfgPortalCookie = ""; // QuickConnect 포털 _SSID 쿠키


    /**
     * NAS 인증 정보를 직접 지정한다. 연결 테스트 또는 저장 후 호출.
     * 인증 정보가 바뀌면 SID/resolvedBase 캐시를 무효화한다.
     */
    public static void init(String baseUrl, String lanUrl, String user, String pass,
                            String basePath, String posDir) {
        cfgBaseUrl  = baseUrl;
        cfgLanUrl   = lanUrl;
        cfgUser     = user;
        cfgPass     = pass;
        cfgBasePath = basePath;
        cfgPosDir   = posDir;
        cachedSid    = null;
        resolvedBase = null;
        Log.i(TAG, "NAS 인증 정보 적용 완료 (baseUrl=" + cfgBaseUrl + ")");
    }

    /** WebView 포털 로그인 후 획득한 쿠키(_SSID 포함)를 설정. */
    public static void setPortalCookie(String cookie) {
        cfgPortalCookie = (cookie != null) ? cookie : "";
        if (!cfgPortalCookie.isEmpty()) {
            Log.i(TAG, "포털 쿠키 설정됨: " + cfgPortalCookie.substring(0, Math.min(40, cfgPortalCookie.length())));
        }
    }

    /** NasCredentialStore 에서 읽은 인증 정보를 적용하는 편의 메서드.
     *  저장된 값이 비어 있으면 DsFileConfig 상수를 폴백으로 사용한다. */
    public static void init(NasCredentialStore store) {
        String user = store.getUser();
        String pass = store.getPass();
        // 저장된 계정 정보가 없으면 DsFileConfig 하드코딩 값 사용
        if (user.isEmpty()) user = DsFileConfig.USER;
        if (pass.isEmpty()) pass = DsFileConfig.PASS;
        init(store.getBaseUrl(), store.getLanUrl(), user, pass,
                store.getBasePath(), store.getPosDir());
        // 저장된 포털 쿠키도 함께 로드
        cfgPortalCookie = store.getPortalCookie();
    }

    public static String getBasePath() { return cfgBasePath; }


    public interface Callback<T> {
        void onResult(T result);
        void onError(String msg);
    }

    public static String getCachedSid() { return cachedSid; }

    // ── 인증 ─────────────────────────────────────────────────────────────────

    /**
     * NAS 로그인. QuickConnect URL이면 먼저 실제 NAS 주소를 해석 후 로그인.
     * 성공 시 SID 캐시 저장 후 콜백.
     */
    public static void login(Callback<String> cb) {
        executor.execute(() -> {
            try {
                // Step 1: QuickConnect 해석 — 릴레이 URL 확정 (포털 로그인 시에도 필요)
                if (resolvedBase == null) {
                    String resolved = resolveQuickConnect(cfgBaseUrl);
                    resolvedBase = (resolved != null) ? resolved : cfgBaseUrl;
                    Log.i(TAG, "resolvedBase=" + resolvedBase);
                }

                // Step 2: 포털 쿠키가 있으면 cfgBaseUrl 로 POST 로그인 (SID 획득)
                // resolvedBase 는 릴레이 URL 그대로 유지 — 이후 API 호출은 릴레이 경유
                if (!cfgPortalCookie.isEmpty() && cfgBaseUrl.contains("quickconnect.to")) {
                    if (tryPortalLoginInternal(cb)) return;
                }
                // QuickConnect relay 사용 시: 포털 쿠키 먼저 획득 후 로그인 시도
                String relayCookie = null;
                if (resolvedBase.contains("quickconnect.to")) {
                    relayCookie = getRelayCookie(resolvedBase);
                    if (relayCookie != null) {
                        Log.d(TAG, "relay cookie 획득: " + relayCookie.substring(0, Math.min(40, relayCookie.length())));
                    }
                }

                String url = resolvedBase + "/webapi/auth.cgi"
                        + "?api=SYNO.API.Auth&version=6&method=login"
                        + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                        + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                        + "&session=FileStation&format=sid";
                Log.d(TAG, "login → " + cfgBaseUrl + (relayCookie != null ? " [with relay cookie]" : ""));
                String body = httpGetWithCookie(url, relayCookie);
                if (body.startsWith("<")) {
                    // QuickConnect 릴레이가 포털 HTML 반환 — path prefix 없이 접속한 경우
                    Log.w(TAG, "QuickConnect relay HTML 응답 (resolvedBase=" + resolvedBase + "): "
                            + body.substring(0, Math.min(300, body.length())).replaceAll("\\s+", " "));
                    java.util.regex.Matcher tm = Pattern.compile("<title>([^<]{0,80})</title>").matcher(body);
                    if (tm.find()) Log.w(TAG, "relay HTML title: " + tm.group(1));
                    // /https_first path prefix 를 붙여 재시도
                    if (!resolvedBase.endsWith("/https_first") && resolvedBase.contains("quickconnect.to")) {
                        String withPrefix = resolvedBase.replaceAll("/$", "") + "/https_first";
                        Log.d(TAG, "relay /https_first prefix 재시도: " + withPrefix);
                        String probe = withPrefix + "/webapi/auth.cgi"
                                + "?api=SYNO.API.Auth&version=6&method=login"
                                + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                                + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                                + "&session=FileStation&format=sid";
                        String body2 = httpGet(probe);
                        if (!body2.startsWith("<")) {
                            Log.d(TAG, "relay /https_first 응답: " + body2.substring(0, Math.min(100, body2.length())));
                            resolvedBase = withPrefix;
                            body = body2; // 아래 JSON 파싱으로 계속
                        } else {
                            throw new Exception("QuickConnect 외부 접속 실패 (HTML응답).\n"
                                    + "DSM > 제어판 > QuickConnect 에서 연결 상태를 확인해주세요.");
                        }
                    } else {
                        throw new Exception("QuickConnect 외부 접속 실패 (HTML응답).\n"
                                + "DSM > 제어판 > QuickConnect 에서 연결 상태를 확인해주세요.");
                    }
                }
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    String sid = json.getJSONObject("data").getString("sid");
                    cachedSid = sid;
                    Log.i(TAG, "login OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "…");
                    mainHandler.post(() -> cb.onResult(sid));
                } else {
                    int code = json.optJSONObject("error") != null
                            ? json.getJSONObject("error").optInt("code", -1) : -1;
                    // code=400/407: DSM Secure SignIn이 릴레이 IP 차단 → 포털 쿠키 인증으로 우회
                    // (400: Invalid parameter / 407: Blocked IP source — 릴레이 경유 시 둘 다 발생)
                    if ((code == 400 || code == 407) && cfgBaseUrl.contains("quickconnect.to")
                            && resolvedBase != null && resolvedBase.contains("direct.quickconnect.to")) {
                        Log.w(TAG, "릴레이 login code=" + code + " → 포털 쿠키 인증 필요");
                        final String portalUrl = cfgBaseUrl;
                        mainHandler.post(() -> cb.onError("PORTAL_AUTH_REQUIRED:" + portalUrl));
                        return;
                    }
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

    /**
     * 저장된 포털 쿠키(_SSID)를 사용해 cfgBaseUrl 포털에 직접 POST 로그인.
     * executor 스레드에서만 호출.
     * @return true: 처리 완료 (cb.onResult 또는 cb.onError 호출됨). false: 재시도 필요.
     */
    private static boolean tryPortalLoginInternal(Callback<String> cb) {
        try {
            Log.d(TAG, "포털 쿠키 로그인 시도 → " + cfgBaseUrl);
            String postBody = "api=SYNO.API.Auth&version=7&method=login"
                    + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                    + "&passwd="  + URLEncoder.encode(cfgPass, "UTF-8")
                    + "&session=FileStation&format=sid";
            String result = httpPostWithCookie(cfgBaseUrl + "/webapi/entry.cgi",
                    cfgPortalCookie, postBody);
            if (result.startsWith("<")) {
                // 포털이 HTML 반환 — 쿠키 만료
                Log.w(TAG, "포털 로그인: HTML 응답 → 쿠키 만료, 재인증 필요");
                cfgPortalCookie = "";
                final String portalUrl = cfgBaseUrl;
                mainHandler.post(() -> cb.onError("PORTAL_AUTH_REQUIRED:" + portalUrl));
                return true;
            }
            JSONObject json = new JSONObject(result);
            if (json.optBoolean("success", false)) {
                String sid = json.getJSONObject("data").getString("sid");
                cachedSid = sid;
                // resolvedBase 는 릴레이 URL 유지 — API 호출은 릴레이 경유
                Log.i(TAG, "포털 로그인 OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "… resolvedBase=" + resolvedBase);
                mainHandler.post(() -> cb.onResult(sid));
                return true;
            }
            int errCode = json.optJSONObject("error") != null
                    ? json.getJSONObject("error").optInt("code", -1) : -1;
            Log.w(TAG, "포털 로그인 실패 code=" + errCode + " → 쿠키 무효, 재인증 필요");
            cfgPortalCookie = "";
            final String portalUrl = cfgBaseUrl;
            mainHandler.post(() -> cb.onError("PORTAL_AUTH_REQUIRED:" + portalUrl));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "포털 로그인 exception (릴레이 시도로 fall-through): " + e.getMessage());
            return false; // 네트워크 오류 → 기존 릴레이 방식으로 재시도
        }
    }

    /**
     * POST 요청 with Cookie 헤더.
     * Content-Type: application/x-www-form-urlencoded
     */
    private static String httpPostWithCookie(String urlStr, String cookieStr,
                                              String postBody) throws Exception {
        java.net.URL u = new java.net.URL(urlStr);
        String origin = u.getProtocol() + "://" + u.getHost()
                + (u.getPort() > 0 ? ":" + u.getPort() : "");

        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Origin", origin);
        conn.setRequestProperty("Referer", origin + "/");
        if (cookieStr != null && !cookieStr.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieStr);
        }
        byte[] bodyBytes = postBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int code = conn.getResponseCode();
        Log.d(TAG, "HTTP POST " + code + " ← " + urlStr.replaceAll("passwd=[^&]+", "passwd=***"));
        InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " — empty body");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return sb.toString();
    }

    // ── API 공통 헬퍼 ────────────────────────────────────────────────────────

    /**
     * 포털 쿠키 모드 여부.
     * _SSID 쿠키가 있고 QuickConnect URL 이면 true — 모든 API 호출이 포털 경유.
     */
    private static boolean isPortalMode() {
        return !cfgPortalCookie.isEmpty() && cfgBaseUrl.contains("quickconnect.to");
    }

    /**
     * API 호출에 사용할 base URL.
     * 포털 모드 → cfgBaseUrl (포털 도메인, 쿠키와 함께 사용)
     * 일반 모드 → resolvedBase (릴레이/LAN/직접접속)
     */
    private static String apiBase() {
        if (isPortalMode()) return cfgBaseUrl;
        return resolvedBase != null ? resolvedBase : cfgBaseUrl;
    }

    /**
     * GET 요청 — 포털 모드이면 cfgPortalCookie 를 Cookie 헤더로 포함.
     */
    private static String apiGet(String url) throws Exception {
        if (isPortalMode()) return httpGetWithCookie(url, cfgPortalCookie);
        return httpGet(url);
    }

    // ── 폴더 목록 ────────────────────────────────────────────────────────────

    /**
     * 지정 경로의 파일/폴더 목록 반환.
     * 폴더 먼저(이름순), 파일은 에피소드 번호([Ee]\d+) 순.
     * SID 만료(105/106) 시 한 번만 재로그인 후 재시도.
     */
    public static void listFolder(String folderPath, String sid, Callback<List<VideoItem>> cb) {
        executor.execute(() -> listFolderSync(folderPath, sid, cb, false));
    }

    /** executor 스레드에서 직접 실행 — isRetry=true 면 105/106 재시도 없이 에러 반환 */
    private static void listFolderSync(String folderPath, String sid,
                                       Callback<List<VideoItem>> cb, boolean isRetry) {
        try {
            String base = apiBase();
            String url = base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.List&version=2&method=list"
                    + "&folder_path=" + URLEncoder.encode(folderPath, "UTF-8")
                    + "&sort_by=name&sort_direction=ASC"
                    + "&additional=%5B%22size%22%2C%22time%22%5D"
                    + "&limit=2000&offset=0"
                    + "&_sid=" + sid;
            Log.d(TAG, "listFolder → " + folderPath + (isPortalMode() ? " [포털모드]" : "") + (isRetry ? " [retry]" : ""));
            String body = apiGet(url);
            JSONObject json = new JSONObject(body);

            if (!json.optBoolean("success", false)) {
                int code = json.optJSONObject("error") != null
                        ? json.getJSONObject("error").optInt("code", -1) : -1;
                // 에러 코드 105/106 = 세션 만료 → 재로그인 후 1회만 재시도
                if ((code == 105 || code == 106) && !isRetry) {
                    Log.w(TAG, "SID 만료, 재로그인 후 listFolder 재시도");
                    String newSid = reLoginSync();
                    if (newSid != null) {
                        listFolderSync(folderPath, newSid, cb, true);
                    } else {
                        mainHandler.post(() -> cb.onError("세션 갱신 실패"));
                    }
                    return;
                }
                mainHandler.post(() -> cb.onError("목록 로딩 실패 (code=" + code + ")"));
                return;
            }

            JSONArray files = json.getJSONObject("data").getJSONArray("files");
            List<VideoItem> folders = new ArrayList<>();
            List<VideoItem> videos  = new ArrayList<>();

            for (int i = 0; i < files.length(); i++) {
                JSONObject f    = files.getJSONObject(i);
                boolean  isDir  = f.optBoolean("isdir", false);
                String   name   = f.optString("name", "");
                String   path   = f.optString("path", "");

                if (isDir) {
                    folders.add(VideoItem.nasFolder(name, path));
                } else {
                    // 동영상 확장자만 포함
                    if (!isVideoFile(name)) continue;
                    JSONObject add  = f.optJSONObject("additional");
                    long size       = add != null ? add.optLong("size", 0) : 0;
                    long mtime      = 0;
                    if (add != null && add.has("time")) {
                        mtime = add.getJSONObject("time").optLong("mtime", 0);
                    }
                    String canonical = getCanonicalUrl(path);
                    videos.add(VideoItem.nasFile(name, path, size, mtime, canonical));
                }
            }

            // 폴더: 이름순 (API가 이미 정렬), 파일: 에피소드 번호순
            videos.sort((a, b) -> {
                int ea = extractEpisode(a.name);
                int eb = extractEpisode(b.name);
                if (ea != eb) return Integer.compare(ea, eb);
                return a.name.compareTo(b.name);
            });

            List<VideoItem> result = new ArrayList<>(folders);
            result.addAll(videos);
            Log.i(TAG, "listFolder OK: " + folders.size() + " 폴더, " + videos.size() + " 파일");
            mainHandler.post(() -> cb.onResult(result));

        } catch (Exception e) {
            Log.e(TAG, "listFolder exception", e);
            mainHandler.post(() -> cb.onError("목록 로딩 오류: " + e.getMessage()));
        }
    }

    /** 동기 재로그인 — executor 내부에서만 호출. 성공 시 새 SID 반환, 실패 시 null */
    private static String reLoginSync() {
        try {
            // 포털 모드이면 포털 POST 로그인 재시도
            if (isPortalMode()) {
                String postBody = "api=SYNO.API.Auth&version=7&method=login"
                        + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                        + "&passwd="  + URLEncoder.encode(cfgPass, "UTF-8")
                        + "&session=FileStation&format=sid";
                String result = httpPostWithCookie(cfgBaseUrl + "/webapi/entry.cgi",
                        cfgPortalCookie, postBody);
                JSONObject json = new JSONObject(result);
                if (json.optBoolean("success", false)) {
                    String sid = json.getJSONObject("data").getString("sid");
                    cachedSid = sid;
                    Log.i(TAG, "reLoginSync(portal) OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "…");
                    return sid;
                }
                Log.w(TAG, "reLoginSync(portal) 실패: " + result.substring(0, Math.min(100, result.length())));
                return null;
            }
            String base = resolvedBase != null ? resolvedBase : cfgBaseUrl;
            String loginUrl = base + "/webapi/auth.cgi"
                    + "?api=SYNO.API.Auth&version=6&method=login"
                    + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                    + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                    + "&session=FileStation&format=sid";
            String body = httpGet(loginUrl);
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
        return null;
    }

    // TODO: HLS 트랜스코딩 (SYNO.VideoStation2.Streaming) — 추후 구현 예정.
    // QuickConnect 릴레이 환경에서 Streaming API error 120이 반환되어 현재 미지원.
    // 구현 시 참고: VideoStation2.Streaming.open, stream_id → m3u8 URL 구성.

    // ── URL 헬퍼 (동기, 네트워크 없음) ──────────────────────────────────────

    /** 스트림 URL (SID 포함) — libVLC 재생용.
     *  포털 모드라도 릴레이(resolvedBase)가 확인됐으면 릴레이 경유.
     *  릴레이는 레이어-4 TCP 터널이라 포털 HTTP 프록시 오버헤드가 없어서 처리량이 높다.
     *  인증은 _sid URL 파라미터만으로 충분 (쿠키 불필요). */
    public static String getStreamUrl(String filePath, String sid) {
        try {
            String base = streamBase();
            return base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.Download&version=2&method=download"
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8")
                    + "&mode=open&_sid=" + sid;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 비디오 스트리밍에 사용할 base URL.
     * 포털 모드 + 릴레이 확인 → resolvedBase (TCP 터널, 프록시 오버헤드 없음)
     * 그 외                   → apiBase() (포털 or 직접접속)
     */
    private static String streamBase() {
        if (isPortalMode() && resolvedBase != null && !resolvedBase.equals(cfgBaseUrl)) {
            return resolvedBase;
        }
        return apiBase();
    }

    /** Canonical URL (SID 없음) — Room DB 키 */
    public static String getCanonicalUrl(String filePath) {
        try {
            // canonical은 항상 cfgBaseUrl 기반 (기기 간 일관성)
            return cfgBaseUrl + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.Download&version=2&method=download"
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8")
                    + "&mode=open";
        } catch (Exception e) {
            return "";
        }
    }

    /** Canonical URL + SID 붙이기 — 이어보기 다이얼로그용.
     *  기존 _sid 파라미터를 제거한 뒤 새 SID를 붙임 (세션간 누적 방지).
     *  포털 모드 + 릴레이 확인 시 base를 릴레이로 교체 (HTTP 프록시 우회). */
    public static String canonicalToStream(String canonicalUrl, String sid) {
        // _sid 누적 방지: 먼저 toCanonicalUrl로 정규화 (_sid 제거, cfgBaseUrl 기반)
        String clean = toCanonicalUrl(canonicalUrl);
        // 릴레이 사용 시 base 교체
        String base = streamBase();
        String rewritten = clean;
        if (!base.equals(cfgBaseUrl) && clean.startsWith(cfgBaseUrl)) {
            rewritten = base + clean.substring(cfgBaseUrl.length());
        }
        return rewritten + "&_sid=" + sid;
    }

    /**
     * NAS 스트림 URL을 canonical URL로 정규화.
     * _sid 파라미터 제거, 릴레이 base를 cfgBaseUrl로 교체.
     * Room DB 키 및 세션 간 재사용 목적. NAS URL이 아니면 원본 반환.
     */
    public static String toCanonicalUrl(String streamUrl) {
        if (!isNasUrl(streamUrl)) return streamUrl;
        try {
            android.net.Uri u = android.net.Uri.parse(streamUrl);
            String path = u.getQueryParameter("path");
            if (path != null && !path.isEmpty()) {
                return getCanonicalUrl(path); // _sid 없음, cfgBaseUrl 기반
            }
        } catch (Exception ignored) {}
        return streamUrl;
    }

    /** NAS 스트림 URL 여부 판단 */
    public static boolean isNasUrl(String url) {
        return url != null && url.contains("/webapi/entry.cgi");
    }

    /** 릴레이 경유 URL 여부 — direct.quickconnect.to 또는 quickconnect.to 포함 시 true */
    public static boolean isRelayUrl(String url) {
        return url != null && url.contains("quickconnect.to");
    }

    /**
     * 현재 WiFi 전용 연결 여부.
     * WiFi만 있고 셀룰러가 없을 때만 true → 직접/최고화질 스트리밍.
     * 셀룰러(5G/LTE)가 하나라도 있으면 false → HLS 트랜스코딩.
     * 판단 불가 시 false (안전한 기본값 — HLS 쪽으로 폴백).
     */
    public static boolean isWifi(android.content.Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            if (nc == null) return false;
            boolean hasWifi     = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
            boolean hasCellular = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
            Log.d(TAG, "네트워크: WiFi=" + hasWifi + " Cellular=" + hasCellular);
            // 셀룰러가 조금이라도 있으면 모바일로 판단 (Samsung WiFi콜링 오감지 방지)
            return hasWifi && !hasCellular;
        } catch (Exception e) {
            Log.w(TAG, "isWifi 감지 실패: " + e.getMessage());
            return false;
        }
    }

    /**
     * libVLC HTTP 스트리밍에 설정해야 하는 Cookie 헤더 값.
     * 포털 모드 + 릴레이 경유 스트리밍이면 null (TCP 터널, _sid 인증만 필요).
     * 포털 모드 + 릴레이 미확인이면 _SSID 포함 쿠키 반환.
     * 그 외에는 null.
     */
    public static String getStreamCookieHeader() {
        if (!isPortalMode()) return null;
        // 릴레이가 확인됐으면 쿠키 불필요 — streamBase()가 릴레이 URL 반환
        if (resolvedBase != null && !resolvedBase.equals(cfgBaseUrl)) return null;
        return cfgPortalCookie;
    }

    // ── 위치 동기화 (NAS JSON 파일) ──────────────────────────────────────────

    /**
     * 재생 위치를 NAS JSON 파일로 저장 (fire-and-forget).
     * 실패해도 Room DB 에 이미 저장됐으므로 무시.
     */
    public static void savePositionToNas(String canonicalUrl, PlaybackPosition pos, String sid) {
        executor.execute(() -> {
            try {
                String filename = posFilename(canonicalUrl);
                String json = new JSONObject()
                        .put("uri",             canonicalUrl)
                        .put("positionMs",      pos.positionMs)
                        .put("audioTrackId",    pos.audioTrackId)
                        .put("subtitleTrackId", pos.subtitleTrackId)
                        .put("screenMode",      pos.screenMode)
                        .put("updatedAt",       pos.updatedAt)
                        .toString();
                String uploadResult = uploadFile(cfgPosDir, filename, json.getBytes(StandardCharsets.UTF_8), sid);
                Log.d(TAG, "NAS 위치 저장: " + filename + " → " + uploadResult);
                // 119 = SID 문제 → 재로그인 후 1회 재시도
                if (uploadResult.contains("\"code\":119")) {
                    Log.w(TAG, "SID 119 오류, 재로그인 후 재시도");
                    String newSid = reLoginSync();
                    if (newSid != null) {
                        String retryResult = uploadFile(cfgPosDir, filename, json.getBytes(StandardCharsets.UTF_8), newSid);
                        Log.d(TAG, "NAS 위치 저장 재시도: " + filename + " → " + retryResult);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "NAS 위치 저장 실패 (무시): " + e.getMessage());
            }
        });
    }

    /**
     * NAS 에서 재생 위치 로드. 없으면 null 반환.
     */
    public static void loadPositionFromNas(String canonicalUrl, String sid, Callback<PlaybackPosition> cb) {
        executor.execute(() -> {
            try {
                String filename = posFilename(canonicalUrl);
                String filePath = cfgPosDir + "/" + filename;
                String downloadUrl = getStreamUrl(filePath, sid);
                String body = apiGet(downloadUrl);

                // 404 / 에러 응답은 JSON 파싱 실패로 처리
                JSONObject json = new JSONObject(body);
                PlaybackPosition pos = new PlaybackPosition();
                pos.uri             = canonicalUrl;
                pos.positionMs      = json.optLong("positionMs", 0);
                pos.audioTrackId    = json.optInt("audioTrackId",    Integer.MIN_VALUE);
                pos.subtitleTrackId = json.optInt("subtitleTrackId", Integer.MIN_VALUE);
                pos.screenMode      = json.optInt("screenMode",      -1);
                pos.updatedAt       = json.optLong("updatedAt",      0);
                Log.d(TAG, "NAS 위치 로드: " + pos.positionMs + "ms");
                mainHandler.post(() -> cb.onResult(pos));
            } catch (Exception e) {
                // 파일 없음 또는 파싱 오류 → null
                Log.d(TAG, "NAS 위치 없음: " + e.getMessage());
                mainHandler.post(() -> cb.onResult(null));
            }
        });
    }

    /**
     * 사용자 전체 재생 위치 JSON을 NAS에 업로드.
     * 파일명: {cfgUser}_positions.json, 저장 경로: cfgPosDir
     */
    public static void uploadUserPositions(JSONObject positions, Callback<Boolean> cb) {
        executor.execute(() -> {
            try {
                String sid = cachedSid;
                if (sid == null) { mainHandler.post(() -> cb.onError("SID 없음")); return; }
                JSONObject wrapper = new JSONObject();
                wrapper.put("version", 1);
                wrapper.put("positions", positions);
                String filename = cfgUser.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_positions.json";
                byte[] data = wrapper.toString().getBytes(StandardCharsets.UTF_8);
                String result = uploadFile(cfgPosDir, filename, data, sid);
                if (result.contains("\"code\":119")) {
                    String newSid = reLoginSync();
                    if (newSid != null) {
                        result = uploadFile(cfgPosDir, filename, data, newSid);
                    }
                }
                final boolean ok = result.contains("\"success\":true");
                final String finalResult = result;
                Log.d(TAG, "uploadUserPositions: " + filename + " → " + (ok ? "성공" : result));
                mainHandler.post(() -> { if (ok) cb.onResult(true); else cb.onError(finalResult); });
            } catch (Exception e) {
                Log.w(TAG, "uploadUserPositions 오류: " + e.getMessage());
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * NAS에서 사용자 재생 위치 JSON 다운로드.
     * 파일 없으면 빈 JSONObject 반환.
     */
    public static void downloadUserPositions(Callback<JSONObject> cb) {
        executor.execute(() -> {
            try {
                String sid = cachedSid;
                if (sid == null) { mainHandler.post(() -> cb.onResult(new JSONObject())); return; }
                String filename = cfgUser.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_positions.json";
                String filePath = cfgPosDir + "/" + filename;
                String downloadUrl = getStreamUrl(filePath, sid);
                String body = apiGet(downloadUrl);
                JSONObject wrapper = new JSONObject(body);
                JSONObject positions = wrapper.optJSONObject("positions");
                if (positions == null) positions = new JSONObject();
                final JSONObject result = positions;
                Log.d(TAG, "downloadUserPositions: " + result.length() + " 항목");
                mainHandler.post(() -> cb.onResult(result));
            } catch (Exception e) {
                // 파일 없거나 파싱 오류 → 빈 객체 반환
                Log.d(TAG, "downloadUserPositions 없음: " + e.getMessage());
                mainHandler.post(() -> cb.onResult(new JSONObject()));
            }
        });
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    /**
     * QuickConnect relay 포털 페이지에서 세션 쿠키를 가져옴.
     * 포털이 쿠키 기반으로 relay 세션을 인증하는 경우 이 쿠키가 필요.
     * 반환값: "name=value; name2=value2" 형태 또는 null
     */
    private static String getRelayCookie(String relayBase) {
        try {
            HttpURLConnection conn = openTrustedConnection(relayBase + "/");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Referer", "https://quickconnect.to/");
            int code = conn.getResponseCode();
            java.util.List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
            conn.disconnect();
            if (setCookies == null || setCookies.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String c : setCookies) {
                String nameVal = c.split(";")[0].trim();
                if (sb.length() > 0) sb.append("; ");
                sb.append(nameVal);
                Log.d(TAG, "relay portal cookie: " + nameVal);
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            Log.d(TAG, "getRelayCookie 실패: " + e.getMessage());
            return null;
        }
    }

    /**
     * GET 요청 — 초기 쿠키를 지정 가능 (relay 세션 쿠키 전달용).
     * null 이면 일반 httpGet 과 동일.
     */
    private static String httpGetWithCookie(String urlStr, String initialCookie) throws Exception {
        if (initialCookie == null || initialCookie.isEmpty()) return httpGet(urlStr);
        String currentUrl = urlStr;
        StringBuilder cookieHeader = new StringBuilder(initialCookie);
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection conn = openTrustedConnection(currentUrl);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Cookie", cookieHeader.toString());
            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP(cookie) " + code + " ← " + currentUrl.replaceAll("passwd=[^&]+", "passwd=***"));
            java.util.List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String c : cookies) {
                    String n = c.split(";")[0].trim();
                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                    cookieHeader.append(n);
                }
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new Exception("Redirect without Location");
                if (!location.startsWith("http")) {
                    URL base = new URL(currentUrl);
                    location = base.getProtocol() + "://" + base.getHost()
                            + (base.getPort() > 0 ? ":" + base.getPort() : "") + location;
                }
                currentUrl = location;
                continue;
            }
            InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) { conn.disconnect(); throw new Exception("HTTP " + code + " — empty body"); }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String body = sb.toString();
            if (body.isEmpty()) throw new Exception("HTTP " + code + " — empty body");
            return body;
        }
        throw new Exception("Too many redirects: " + urlStr);
    }

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static String httpGet(String urlStr) throws Exception {
        // 리다이렉트를 최대 5회까지 수동으로 따라감. 쿠키를 수집해 다음 요청으로 전달.
        String currentUrl = urlStr;
        StringBuilder cookieHeader = new StringBuilder();
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection conn = openTrustedConnection(currentUrl);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (cookieHeader.length() > 0) {
                conn.setRequestProperty("Cookie", cookieHeader.toString());
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP " + code + " ← " + currentUrl.replaceAll("passwd=[^&]+", "passwd=***"));

            // Set-Cookie 수집 (쿠키를 다음 리다이렉트 요청으로 전달)
            java.util.List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String cookie : cookies) {
                    String cookieName = cookie.split(";")[0].trim();
                    Log.d(TAG, "Set-Cookie: " + cookieName);
                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                    cookieHeader.append(cookieName);
                }
            }

            // 리다이렉트 처리
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                // 특이 헤더 로깅 (relay 토큰 등)
                for (String hk : conn.getHeaderFields().keySet()) {
                    if (hk != null && (hk.startsWith("X-") || hk.startsWith("x-"))) {
                        Log.d(TAG, "Header " + hk + ": " + conn.getHeaderField(hk));
                    }
                }
                conn.disconnect();
                if (location == null) throw new Exception("Redirect without Location");
                if (!location.startsWith("http")) {
                    URL base = new URL(currentUrl);
                    location = base.getProtocol() + "://" + base.getHost()
                            + (base.getPort() > 0 ? ":" + base.getPort() : "")
                            + location;
                }
                Log.d(TAG, "Redirect → " + location);
                currentUrl = location;
                continue;
            }

            InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                conn.disconnect();
                throw new Exception("HTTP " + code + " — empty body");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String body = sb.toString();
            if (body.isEmpty()) throw new Exception("HTTP " + code + " — empty body");
            return body;
        }
        throw new Exception("Too many redirects: " + urlStr);
    }

    /**
     * Synology QuickConnect URL에서 실제 NAS 접속 주소를 해석.
     * 0) LAN_URL 설정된 경우 먼저 probe
     * 1) global/regional Serv.php POST 로 서버 정보 수집 → 외부 IP/DDNS/relay 후보 병렬 probe
     * 2) 모두 실패 시 baseUrl 자체를 직접 probe (QuickConnect relay 경로)
     */
    private static String resolveQuickConnect(String baseUrl) {
        try {
            if (!baseUrl.toLowerCase().contains("quickconnect.to")) return null;

            String host = new URL(baseUrl).getHost();
            String qcId = host.split("\\.")[0];
            Log.d(TAG, "QuickConnect ID: " + qcId);

            // Step 0: LAN_URL 단일 probe
            if (cfgLanUrl != null && !cfgLanUrl.isEmpty()) {
                if (probeUrl(cfgLanUrl)) {
                    Log.i(TAG, "LAN NAS 연결 성공: " + cfgLanUrl);
                    return cfgLanUrl;
                }
                Log.d(TAG, "LAN_URL probe 실패, QuickConnect 시도");
            }

            // Step 0a: DDNS / 외부 IP 직접 probe — 릴레이보다 먼저 시도 (포트포워딩 환경에서 릴레이 우회)
            // Synology DDNS (xxx.synology.me) 와 공인 IP 를 사용하면 릴레이 없이 직접 접속 가능
            // → 릴레이 대역폭 제한(~1 Mbps) 없이 NAS 업로드 속도 전체 사용
            {
                List<String> directCandidates = new ArrayList<>();
                // DsFileConfig 에 설정된 DDNS/외부 IP — DSM 기본 포트 + 표준 웹 포트
                String[] nasHosts;
                if (!DsFileConfig.DDNS_HOST.isEmpty() && !DsFileConfig.EXTERNAL_IP.isEmpty()) {
                    nasHosts = new String[]{ DsFileConfig.DDNS_HOST, DsFileConfig.EXTERNAL_IP,
                            qcId + ".synology.me" };
                } else if (!DsFileConfig.DDNS_HOST.isEmpty()) {
                    nasHosts = new String[]{ DsFileConfig.DDNS_HOST, qcId + ".synology.me" };
                } else if (!DsFileConfig.EXTERNAL_IP.isEmpty()) {
                    nasHosts = new String[]{ DsFileConfig.EXTERNAL_IP, qcId + ".synology.me" };
                } else {
                    nasHosts = new String[]{ qcId + ".synology.me" };
                }
                for (String h : nasHosts) {
                    directCandidates.add("https://" + h + ":5001");
                    directCandidates.add("http://"  + h + ":5000");
                    directCandidates.add("https://" + h + ":443");
                    directCandidates.add("http://"  + h + ":80");
                }

                // DNS 해석이 실패한 경우 DoH(DNS over HTTPS) 로 gomji17.synology.me 의 IP 를 직접 조회
                if (!DsFileConfig.DDNS_HOST.isEmpty()) {
                    String dohIp = resolveDoh(DsFileConfig.DDNS_HOST);
                    if (dohIp != null) {
                        Log.d(TAG, "DoH resolved " + DsFileConfig.DDNS_HOST + " → " + dohIp);
                        directCandidates.add(0, "https://" + dohIp + ":5001");
                        directCandidates.add(1, "http://"  + dohIp + ":5000");
                        directCandidates.add(2, "https://" + dohIp + ":443");
                    }
                }

                if (!directCandidates.isEmpty()) {
                    Log.d(TAG, "DDNS/외부 IP 직접 probe 시작: " + directCandidates);
                    String winner = probeBestUrl(directCandidates);
                    if (winner != null) {
                        Log.i(TAG, "DDNS/외부 IP 직접 연결 성공 (릴레이 우회): " + winner);
                        return winner;
                    }
                    Log.d(TAG, "DDNS/외부 IP probe 실패, 릴레이 시도");
                }
            }

            // Step 0b: Synology direct.quickconnect.to 릴레이 (DDNS 실패 시 폴백)
            // 형식: http://synr-{region}.{QCID-upper}.direct.quickconnect.to:{port}
            // cfgBaseUrl 호스트에서 리전 추출 (예: gomji17.tw3.quickconnect.to → tw3)
            {
                String qcIdUpper = qcId.toUpperCase();
                // URL 에서 리전 추출
                String[] hp = host.split("\\.");
                // hp = ["gomji17", "tw3", "quickconnect", "to"] → region = hp[1] if length>=4
                String region = (hp.length >= 4) ? hp[1] : null;

                // 시도할 릴레이 포트 (DS Video 실측: 28095 HTTP, 16811 HTTPS 등)
                int[] relayPorts = {28095, 16811, 16812, 6690, 443, 80};

                List<String> directCandidates = new ArrayList<>();
                if (region != null) {
                    for (int rp : relayPorts) {
                        directCandidates.add("http://synr-" + region + "." + qcIdUpper + ".direct.quickconnect.to:" + rp);
                        directCandidates.add("https://synr-" + region + "." + qcIdUpper + ".direct.quickconnect.to:" + rp);
                    }
                }
                // 리전 없이도 시도
                for (int rp : relayPorts) {
                    directCandidates.add("http://synr." + qcIdUpper + ".direct.quickconnect.to:" + rp);
                    directCandidates.add("http://" + qcIdUpper + ".direct.quickconnect.to:" + rp);
                }
                Log.d(TAG, "direct.quickconnect.to relay probe 시작 (region=" + region + "): "
                        + directCandidates.subList(0, Math.min(4, directCandidates.size())));
                String winner = probeBestUrl(directCandidates);
                if (winner != null) {
                    Log.i(TAG, "direct.quickconnect.to relay 연결 성공: " + winner);
                    return winner;
                }
                Log.d(TAG, "direct.quickconnect.to relay probe 실패, 다음 단계 시도");
            }

            String payload = new JSONObject()
                    .put("version", 1)
                    .put("command", "get_server_info")
                    .put("stop_when_error", false)
                    .put("stop_when_connected", false)
                    .put("id", qcId)
                    .put("serverID", qcId)
                    .put("is_force_redirect", true)  // relay 강제 요청
                    .toString();

            List<String> candidates = new ArrayList<>();

            // Step 1: global GET → 리전 서버 이름 획득 (plain text 응답)
            // e.g. "usc.quickconnect.to"
            String regional = null;
            try {
                String globalResp = httpGet("https://global.quickconnect.to/Serv.php?id=" + qcId);
                Log.d(TAG, "global GET: [" + globalResp.trim() + "]");
                // plain text 리전 이름이면 바로 사용, JSON이면 candidates 파싱
                if (globalResp.trim().startsWith("{")) {
                    collectCandidates(globalResp, candidates);
                } else if (!globalResp.trim().isEmpty()) {
                    regional = globalResp.trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "global GET 실패: " + e.getMessage());
            }

            // baseUrl 호스트명에서 리전 서버 추출 (e.g. gomji17.tw3.quickconnect.to → tw3.quickconnect.to)
            // global GET이 지리적 위치 기반이라 NAS 등록 리전과 다를 수 있음
            String urlRegional = null;
            String[] hostParts = host.split("\\.");
            // host = "gomji17.tw3.quickconnect.to" → parts = ["gomji17","tw3","quickconnect","to"]
            if (hostParts.length >= 4) {
                urlRegional = hostParts[1] + ".quickconnect.to"; // "tw3.quickconnect.to"
            }
            if (urlRegional != null && urlRegional.equals(regional)) urlRegional = null; // 중복 제거

            // Step 2: 리전 서버에 POST → 실제 NAS 서버 정보 JSON
            if (candidates.isEmpty() && regional != null && !regional.isEmpty()) {
                try {
                    String resp = httpPost("https://" + regional + "/Serv.php", payload);
                    Log.d(TAG, regional + " POST: " + resp.substring(0, Math.min(400, resp.length())));
                    collectCandidates(resp, candidates);
                } catch (Exception e) {
                    Log.w(TAG, regional + " POST 실패: " + e.getMessage());
                }
            }

            // Step 2b: global 서버에 직접 POST (리전 서버가 errno:4인 경우 global이 라우팅해줄 수 있음)
            if (candidates.isEmpty()) {
                try {
                    String resp = httpPost("https://global.quickconnect.to/Serv.php", payload);
                    Log.d(TAG, "global POST: " + resp.substring(0, Math.min(400, resp.length())));
                    collectCandidates(resp, candidates);
                } catch (Exception e) {
                    Log.w(TAG, "global POST 실패: " + e.getMessage());
                }
            }

            // Step 2c: URL 기반 리전 서버 POST (global이 다른 지역 서버를 반환한 경우 보완)
            if (candidates.isEmpty() && urlRegional != null) {
                try {
                    String resp = httpPost("https://" + urlRegional + "/Serv.php", payload);
                    Log.d(TAG, urlRegional + " POST: " + resp.substring(0, Math.min(400, resp.length())));
                    collectCandidates(resp, candidates);
                } catch (Exception e) {
                    Log.w(TAG, urlRegional + " POST 실패: " + e.getMessage());
                }
            }

            // Step 3: check-item-permission — is_force_redirect:true/false 모두 시도
            if (candidates.isEmpty()) {
                // is_force_redirect:true = relay 강제 사용 요청 (relay_ip 정보 반환 기대)
                // is_force_redirect:false = 직접 접속 우선 (기존 값)
                String checkPayload = new JSONObject()
                        .put("version", 1)
                        .put("command", "check-item-permission")
                        .put("id", qcId)
                        .put("serverID", qcId)
                        .put("is_force_redirect", true)  // relay 강제 요청으로 변경
                        .toString();
                // 3a: global 서버
                try {
                    String checkResp = httpPost("https://global.quickconnect.to/Serv.php", checkPayload);
                    Log.d(TAG, "check-item-permission ← global: "
                            + checkResp.substring(0, Math.min(400, checkResp.length())));
                    collectCandidates(checkResp, candidates);
                } catch (Exception e) {
                    Log.w(TAG, "check-item-permission(global) 실패: " + e.getMessage());
                }
                // 3b: regional 서버
                if (candidates.isEmpty() && regional != null && !regional.isEmpty()) {
                    try {
                        String checkResp = httpPost("https://" + regional + "/Serv.php", checkPayload);
                        Log.d(TAG, "check-item-permission ← " + regional + ": "
                                + checkResp.substring(0, Math.min(400, checkResp.length())));
                        collectCandidates(checkResp, candidates);
                    } catch (Exception e) {
                        Log.w(TAG, "check-item-permission(" + regional + ") 실패: " + e.getMessage());
                    }
                }
            }

            // Step 3-v7: check-item-permission-v7 (DSM 7+ 용 업데이트된 명령)
            if (candidates.isEmpty()) {
                String v7Payload = new JSONObject()
                        .put("version", 1)
                        .put("command", "check-item-permission-v7")
                        .put("id", qcId)
                        .put("serverID", qcId)
                        .put("is_force_redirect", true)  // relay 강제 요청
                        .toString();
                for (String srv : new String[]{"global.quickconnect.to",
                        regional != null ? regional : "", urlRegional != null ? urlRegional : ""}) {
                    if (srv.isEmpty()) continue;
                    try {
                        String resp = httpPost("https://" + srv + "/Serv.php", v7Payload);
                        Log.d(TAG, "check-item-permission-v7 ← " + srv + ": "
                                + resp.substring(0, Math.min(400, resp.length())));
                        collectCandidates(resp, candidates);
                        if (!candidates.isEmpty()) break;
                    } catch (Exception e) {
                        Log.w(TAG, "check-item-permission-v7(" + srv + ") 실패: " + e.getMessage());
                    }
                }
            }

            // Step 3c: 모든 알려진 Synology 리전 서버 병렬 조회
            if (candidates.isEmpty()) {
                String[] allRegionals = {"eu.quickconnect.to", "sgp.quickconnect.to",
                        "apac.quickconnect.to", "usw.quickconnect.to", "usec.quickconnect.to",
                        "tw.quickconnect.to", "cn.quickconnect.to"};
                CountDownLatch latch = new CountDownLatch(allRegionals.length);
                List<String> syncCandidates = java.util.Collections.synchronizedList(new ArrayList<>());
                for (String srv : allRegionals) {
                    if (srv.equals(regional)) { latch.countDown(); continue; }
                    String srvFinal = srv;
                    Thread t = new Thread(() -> {
                        try {
                            String resp = httpPost("https://" + srvFinal + "/Serv.php", payload);
                            if (resp.contains("\"errno\":0") || resp.contains("external")) {
                                Log.d(TAG, srvFinal + " POST 성공: " + resp.substring(0, Math.min(300, resp.length())));
                                collectCandidates(resp, syncCandidates);
                            } else {
                                Log.d(TAG, srvFinal + " POST: " + resp.substring(0, Math.min(100, resp.length())));
                            }
                        } catch (Exception e) {
                            Log.d(TAG, srvFinal + " POST 실패: " + e.getMessage());
                        } finally { latch.countDown(); }
                    });
                    t.setDaemon(true);
                    t.start();
                }
                try { latch.await(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                candidates.addAll(syncCandidates);
            }

            Log.d(TAG, "QuickConnect candidates after get_server_info: " + candidates);
            if (!candidates.isEmpty()) {
                String winner = probeBestUrl(candidates);
                if (winner != null) {
                    Log.i(TAG, "QuickConnect resolved: " + winner);
                    return winner;
                }
                Log.w(TAG, "후보 있으나 모두 probe 실패");
                candidates.clear();
            }

            // Step 3c-pre: 포털 HTML 에서 controlHost 및 relay 설정 추출
            // 포털이 global 이 아닌 다른 서버(예: tw3)를 controlHost 로 사용할 수 있음
            String portalControlHost = null;
            try {
                String portalHtml = httpGet("http://" + qcId + ".quickconnect.to/");
                Log.d(TAG, "portal HTML length=" + portalHtml.length() + " first512: "
                        + portalHtml.substring(0, Math.min(512, portalHtml.length())).replaceAll("\\s+", " "));
                // 포털 HTML 에서 controlHost 추출 (여러 패턴 시도)
                String[] ctrlPatterns = {
                    "controlHost[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                    "control_host[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                    "\"site\"\\s*:\\s*[\"']([^\"']+quickconnect\\.to[^\"']*)[\"']",
                    "Serv\\.php[^\"']*[\"']([^\"']+quickconnect\\.to)[\"']",
                };
                for (String pat : ctrlPatterns) {
                    java.util.regex.Matcher m = Pattern.compile(pat).matcher(portalHtml);
                    if (m.find()) {
                        portalControlHost = m.group(1).trim();
                        Log.d(TAG, "포털 controlHost(" + pat.substring(0,20) + "): " + portalControlHost);
                        break;
                    }
                }
                // 포털 HTML 에서 JSON 블록 검색 (embedded config)
                java.util.regex.Matcher mJson = Pattern.compile(
                        "\\{[^{}]{0,2000}(?:relay_ip|external|ddns|serverID)[^{}]{0,2000}\\}"
                ).matcher(portalHtml);
                int jsonCount = 0;
                while (mJson.find() && jsonCount < 3) {
                    Log.d(TAG, "portal JSON block[" + jsonCount + "]: "
                            + mJson.group(0).substring(0, Math.min(200, mJson.group(0).length())));
                    jsonCount++;
                }
                // 스크립트 태그 안의 변수 할당 패턴 로그
                java.util.regex.Matcher mVar = Pattern.compile(
                        "(?:window\\.\\w+|var \\w+)\\s*=\\s*(\\{[^;]{10,300}\\})"
                ).matcher(portalHtml);
                int varCount = 0;
                while (mVar.find() && varCount < 5) {
                    Log.d(TAG, "portal var[" + varCount + "]: "
                            + mVar.group(0).substring(0, Math.min(200, mVar.group(0).length())));
                    varCount++;
                }
            } catch (Exception e) {
                Log.d(TAG, "portal HTML 취득 실패: " + e.getMessage());
            }

            // Step 3d: request_tunnel — 포털 JS가 실제로 사용하는 relay 터널 요청 방식
            // get_server_info 와 달리 JSON 배열로 전송, id:"mainapp_http" (논리 서비스 이름)
            // 이 명령이 errno:0 을 반환하면 relay_ip/relay_dn 에서 실제 접속 가능한 relay 주소를 얻을 수 있음
            {
                String tunnelPayload = new JSONArray()
                        .put(new JSONObject()
                                .put("version", 1)
                                .put("command", "request_tunnel")
                                .put("stop_when_error", false)
                                .put("stop_when_success", true)
                                .put("id", "mainapp_http")
                                .put("serverID", qcId)
                                .put("is_gofile", false)
                                .put("path", ""))
                        .toString();
                Log.d(TAG, "request_tunnel payload: " + tunnelPayload);

                // global + portalControlHost + regional + urlRegional 모두 시도 (순서대로)
                List<String> tunnelServers = new ArrayList<>();
                tunnelServers.add("global.quickconnect.to");
                if (portalControlHost != null && !portalControlHost.isEmpty()
                        && !portalControlHost.equals("global.quickconnect.to"))
                    tunnelServers.add(0, portalControlHost); // 포털 controlHost 를 첫 번째로
                if (regional != null && !regional.isEmpty()) tunnelServers.add(regional);
                if (urlRegional != null) tunnelServers.add(urlRegional);

                for (String srv : tunnelServers) {
                    try {
                        String resp = httpPost("https://" + srv + "/Serv.php", tunnelPayload);
                        Log.d(TAG, "request_tunnel ← " + srv + ": "
                                + resp.substring(0, Math.min(500, resp.length())));
                        collectTunnelCandidates(resp, qcId, candidates);
                        if (!candidates.isEmpty()) {
                            Log.d(TAG, "request_tunnel 후보: " + candidates);
                            break;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "request_tunnel(" + srv + ") 실패: " + e.getMessage());
                    }
                }

                if (!candidates.isEmpty()) {
                    String winner = probeBestUrl(candidates);
                    if (winner != null) {
                        Log.i(TAG, "request_tunnel relay 연결 성공: " + winner);
                        return winner;
                    }
                    Log.w(TAG, "request_tunnel 후보 있으나 probe 실패");
                    candidates.clear();
                }
            }

            // Step 3e: cfgBaseUrl 의 host(예: gomji17.tw3.quickconnect.to) 를 relay 로 직접 시도
            // 307 리다이렉트를 따라가 실제 NAS 주소를 발견할 수 있음
            {
                String qcHost = new URL(cfgBaseUrl).getHost(); // e.g. "gomji17.tw3.quickconnect.to"
                String[] directRelayVariants = {
                        "https://" + qcHost + "/https_first",
                        "https://" + qcHost,
                        "http://"  + qcHost + "/https_first",
                        "http://"  + qcHost,
                        "https://" + qcId + ".quickconnect.to/https_first",
                        "https://" + qcId + ".quickconnect.to",
                        "http://"  + qcId + ".quickconnect.to/https_first",
                };
                Log.d(TAG, "직접 relay probe 시작 (cfgBaseUrl host: " + qcHost + ")");
                for (String rv : directRelayVariants) {
                    if (probeUrl(rv)) {
                        Log.i(TAG, "직접 relay 연결 성공: " + rv);
                        return rv;
                    }
                    // probeUrl 이 실패한 경우 307 리다이렉트를 따라가 NAS 주소 발견 시도
                    String redirectBase = resolveViaRedirect(rv + "/webapi/auth.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth");
                    if (redirectBase != null) {
                        Log.i(TAG, "리다이렉트 추적으로 NAS 주소 발견: " + redirectBase);
                        return redirectBase;
                    }
                }
            }

            // Step 3f: mobile.quickconnect.to — DS File 과 동일한 모바일 앱 전용 엔드포인트
            // get_server_info 와 달리 POST 불필요, GET 단순 조회
            if (candidates.isEmpty()) {
                String[] mobileUrls = {
                    "https://mobile.quickconnect.to/get?id=" + qcId,
                    "https://mobile.quickconnect.to/Serv.php?id=" + qcId,
                };
                for (String mu : mobileUrls) {
                    try {
                        String mr = httpGet(mu);
                        Log.d(TAG, "mobile ← " + mu + ": " + mr.substring(0, Math.min(400, mr.length())));
                        collectCandidates(mr, candidates);
                        if (!candidates.isEmpty()) break;
                        // mobile API 고유 필드 추가 파싱
                        try {
                            JSONObject mj = new JSONObject(mr);
                            for (String field : new String[]{"service", "env", "server", "relay"}) {
                                JSONObject fo = mj.optJSONObject(field);
                                if (fo == null) continue;
                                String ri = fo.optString("relay_ip", "");
                                String rd = fo.optString("relay_dn", "");
                                int rp = fo.optInt("relay_port", 443);
                                if (!ri.isEmpty() && !ri.equals("NULL")) candidates.add("https://" + ri + ":" + rp);
                                if (!rd.isEmpty() && !rd.equals("NULL")) candidates.add("https://" + rd + ":" + rp);
                            }
                        } catch (Exception ignored) {}
                        if (!candidates.isEmpty()) break;
                    } catch (Exception e) {
                        Log.w(TAG, "mobile " + mu + " 실패: " + e.getMessage());
                    }
                }
                if (!candidates.isEmpty()) {
                    String winner = probeBestUrl(candidates);
                    if (winner != null) {
                        Log.i(TAG, "mobile API relay 연결 성공: " + winner);
                        return winner;
                    }
                    candidates.clear();
                }
            }

            // Step 4: https://quickconnect.to/{id} 포털 302 리다이렉트 추적
            String portalResolved = resolvePortalRedirect(qcId);
            if (portalResolved != null) {
                Log.i(TAG, "포털 리다이렉트 → NAS 주소: " + portalResolved);
                return portalResolved;
            }

            // Step 5: 최후 수단 — relay base를 강제 반환 (login 함수에서 HTML 여부 재확인)
            // HTTPS relay URL 직접 시도 (relay가 실제로 작동 중이면 JSON 응답 기대)
            String relayFallback = getRelayBase(qcId);
            if (relayFallback != null) {
                Log.w(TAG, "relay fallback 강제 사용: " + relayFallback);
                return relayFallback;
            }

            Log.w(TAG, "QuickConnect: 모든 후보 실패");
            return null;

        } catch (Exception e) {
            Log.w(TAG, "QuickConnect resolve failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * DNS over HTTPS (Cloudflare 1.1.1.1 → Google 8.8.8.8 fallback) 로 호스트명 해석.
     * 통신사 DNS가 synology.me 를 차단한 경우에도 DoH 는 포트 443 HTTPS 를 사용해 우회 가능.
     * 성공 시 IP 문자열 반환, 실패 시 null.
     */
    private static String resolveDoh(String hostname) {
        String[][] providers = {
                { "https://1.1.1.1/dns-query?name=" + hostname + "&type=A",
                  "application/dns-json" },
                { "https://8.8.8.8/dns-query?name=" + hostname + "&type=A",
                  "application/dns-json" },
        };
        for (String[] prov : providers) {
            try {
                HttpURLConnection conn = openTrustedConnection(prov[0]);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", prov[1]);
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); continue; }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                String body = sb.toString();
                Log.d(TAG, "DoH " + prov[0].substring(0, 30) + " → " + body.substring(0, Math.min(200, body.length())));
                JSONObject json = new JSONObject(body);
                JSONArray answers = json.optJSONArray("Answer");
                if (answers != null) {
                    for (int i = 0; i < answers.length(); i++) {
                        JSONObject ans = answers.getJSONObject(i);
                        int type = ans.optInt("type", 0);
                        if (type == 1) { // A record
                            String ip = ans.optString("data", "");
                            if (!ip.isEmpty() && !ip.contains(":")) { // IPv4
                                return ip;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "DoH 실패(" + prov[0].substring(0, 25) + "): " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Serv.php POST JSON 응답에서 후보 URL 목록 수집.
     * 외부 IP/DDNS를 LAN IP보다 앞에 추가 (외부망 우선).
     * relay 서버 정보도 포함.
     */
    private static void collectCandidates(String body, List<String> out) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject server = json.optJSONObject("server");
            JSONObject env    = json.optJSONObject("env");

            if (server != null) {
                // 외부 IP (포트포워딩 직접 접속) — 외부망에서 가장 신뢰할 수 있는 경로
                JSONObject ext = server.optJSONObject("external");
                if (ext != null) {
                    String ip       = ext.optString("ip", "");
                    int httpsPort   = ext.optInt("https_port", 5001);
                    int httpPort    = ext.optInt("http_port",  5000);
                    if (!ip.isEmpty() && !ip.equals("NULL")) {
                        out.add("https://" + ip + ":" + httpsPort);
                        out.add("http://"  + ip + ":" + httpPort);
                    }
                }
                // DDNS
                String ddns = server.optString("ddns", "");
                if (!ddns.isEmpty() && !ddns.equals("NULL")) {
                    out.add("https://" + ddns);
                    out.add("https://" + ddns + ":5001");
                }
                // LAN IP (외부망에서는 타임아웃이지만 병렬 probe라 문제 없음)
                JSONArray ifaces = server.optJSONArray("interface");
                if (ifaces != null) {
                    for (int i = 0; i < ifaces.length(); i++) {
                        JSONObject iface = ifaces.getJSONObject(i);
                        String ip = iface.optString("ip", "");
                        if (!ip.isEmpty() && !ip.equals("NULL")) {
                            out.add("http://"  + ip + ":5000");
                            out.add("https://" + ip + ":5001");
                        }
                    }
                }
            }

            // QuickConnect relay 서버 — HTTP 먼저 (DS Video 동작 방식, SSL 인증서 문제 없음)
            if (env != null) {
                String relayIp = env.optString("relay_ip", "");
                String relayDn = env.optString("relay_dn", "");
                int    relayPort = env.optInt("relay_port", 443);
                if (!relayIp.isEmpty() && !relayIp.equals("NULL")) {
                    out.add("http://"  + relayIp + ":" + relayPort);
                    out.add("https://" + relayIp + ":" + relayPort);
                }
                if (!relayDn.isEmpty() && !relayDn.equals("NULL")) {
                    out.add("http://"  + relayDn + ":" + relayPort);
                    out.add("https://" + relayDn + ":" + relayPort);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "collectCandidates: " + e.getMessage());
        }
    }

    /**
     * request_tunnel 응답에서 relay 후보 URL 추출.
     * 응답이 JSON 배열인 경우도 처리하고, service/env/server 필드에서 relay 정보 수집.
     */
    private static void collectTunnelCandidates(String body, String qcId, List<String> out) {
        try {
            JSONObject json;
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                if (arr.length() == 0) return;
                json = arr.getJSONObject(0);
            } else if (trimmed.startsWith("{")) {
                json = new JSONObject(trimmed);
            } else {
                Log.d(TAG, "request_tunnel 응답이 JSON 아님: "
                        + trimmed.substring(0, Math.min(120, trimmed.length())));
                return;
            }

            int errno = json.optInt("errno", -1);
            Log.d(TAG, "request_tunnel errno=" + errno);
            if (errno != 0) return;

            // service / env / server 필드 모두 검색
            for (String field : new String[]{"service", "env", "server"}) {
                JSONObject obj = json.optJSONObject(field);
                if (obj == null) continue;
                String relayIp   = obj.optString("relay_ip",   "");
                String relayDn   = obj.optString("relay_dn",   "");
                int    relayPort = obj.optInt("relay_port", 443);
                String relayPath = obj.optString("relay_path", "");
                if (!relayIp.isEmpty() && !relayIp.equals("NULL")) {
                    String baseHttp  = "http://"  + relayIp + ":" + relayPort;
                    String baseHttps = "https://" + relayIp + ":" + relayPort;
                    if (!relayPath.isEmpty()) { baseHttp += relayPath; baseHttps += relayPath; }
                    Log.d(TAG, "tunnel candidate relay_ip(" + field + "): " + baseHttp);
                    out.add(baseHttp);
                    out.add(baseHttps);
                }
                if (!relayDn.isEmpty() && !relayDn.equals("NULL")) {
                    String baseHttp  = "http://"  + relayDn + ":" + relayPort;
                    String baseHttps = "https://" + relayDn + ":" + relayPort;
                    if (!relayPath.isEmpty()) { baseHttp += relayPath; baseHttps += relayPath; }
                    Log.d(TAG, "tunnel candidate relay_dn(" + field + "): " + baseHttp);
                    out.add(baseHttp);
                    out.add(baseHttps);
                }
            }
            // 표준 server/external 경로도 시도 (get_server_info 와 동일 형식일 수 있음)
            collectCandidates(body, out);
        } catch (Exception e) {
            Log.d(TAG, "collectTunnelCandidates: " + e.getMessage());
        }
    }

    /** resolvePortalRedirect 에서 발견한 HTTP 200 relay base (마지막 수단용) */
    private static volatile String lastRelayBase = null;

    private static String getRelayBase(String qcId) {
        if (lastRelayBase != null) return lastRelayBase;
        // HTTPS relay URL — relay가 활성 상태면 /webapi/ 요청을 NAS로 프록시해줌
        return "https://" + qcId + ".quickconnect.to";
    }

    /**
     * https://quickconnect.to/{id} 포털의 302 리다이렉트를 따라가 실제 NAS 주소 추출.
     * JSON API 응답이 오면 즉시 반환. HTTP 200 HTML이면 lastRelayBase에 저장.
     */
    private static String resolvePortalRedirect(String qcId) {
        lastRelayBase = null;
        try {
            String currentUrl = "https://quickconnect.to/" + qcId
                    + "/webapi/auth.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth";
            for (int i = 0; i < 6; i++) {
                HttpURLConnection conn = openTrustedConnection(currentUrl);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                Log.d(TAG, "portal[" + i + "] HTTP " + code + " → "
                        + (location != null ? location.substring(0, Math.min(100, location.length())) : "null"));

                if (code >= 300 && code < 400 && location != null) {
                    URL loc = new URL(location);
                    String locHost = loc.getHost();

                    // LAN IP가 아닌 외부 주소 발견 → 실제 NAS
                    if (!locHost.contains("quickconnect.to")
                            && !locHost.startsWith("192.168.")
                            && !locHost.startsWith("10.")
                            && !locHost.startsWith("172.")) {
                        int port = loc.getPort();
                        String extBase = loc.getProtocol() + "://" + locHost + (port > 0 ? ":" + port : "");
                        Log.d(TAG, "portal → 외부 NAS 주소 발견: " + extBase);
                        if (probeUrl(extBase)) return extBase;
                        return extBase; // probe 실패해도 반환
                    }

                    // quickconnect.to 계열 → /webapi/ 앞까지 path prefix 포함해서 base 추출
                    int webApiIdx = location.indexOf("/webapi/");
                    String newBase;
                    if (webApiIdx > 0) {
                        newBase = location.substring(0, webApiIdx);
                    } else {
                        int port = loc.getPort();
                        newBase = loc.getProtocol() + "://" + locHost + (port > 0 ? ":" + port : "");
                    }
                    Log.d(TAG, "portal relay base: " + newBase);

                    if (probeUrl(newBase)) {
                        Log.d(TAG, "portal relay probe JSON 성공: " + newBase);
                        return newBase;
                    }
                    // HTTP 200 이지만 HTML — relay 후보로 저장, 계속 탐색
                    lastRelayBase = newBase;
                    currentUrl = newBase
                            + "/webapi/auth.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth";
                    continue;
                }
                if (code == 200) {
                    String base = extractBase(currentUrl);
                    if (probeUrl(base)) return base;
                    lastRelayBase = base;
                }
                break;
            }
        } catch (Exception e) {
            Log.w(TAG, "portal redirect 추적 실패: " + e.getMessage());
        }
        return null;
    }

    /** URL에서 scheme://host:port 부분만 추출 */
    private static String extractBase(String urlStr) {
        try {
            URL u = new URL(urlStr);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return urlStr;
        }
    }

    /**
     * 리다이렉트(307 등)를 따라가 최종적으로 DSM API JSON 을 반환하는 base URL 을 반환.
     * tw3.quickconnect.to 같이 307 으로 실제 NAS 주소를 알려주는 서버용.
     * LAN IP 나 quickconnect.to 내부로의 리다이렉트는 무시하고 외부 NAS 주소만 반환.
     */
    private static String resolveViaRedirect(String probeUrl) {
        try {
            String current = probeUrl;
            for (int i = 0; i < 6; i++) {
                HttpURLConnection conn = openTrustedConnection(current);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                Log.d(TAG, "resolveViaRedirect[" + i + "] HTTP " + code + " ← " + current
                        + (location != null ? " → " + location.substring(0, Math.min(100, location.length())) : ""));

                if (code >= 300 && code < 400 && location != null) {
                    if (!location.startsWith("http")) {
                        URL base = new URL(current);
                        location = base.getProtocol() + "://" + base.getHost()
                                + (base.getPort() > 0 ? ":" + base.getPort() : "") + location;
                    }
                    URL loc = new URL(location);
                    String locHost = loc.getHost();
                    // 외부 NAS 주소(LAN·quickconnect 제외)로 리다이렉트된 경우
                    if (!locHost.contains("quickconnect.to")
                            && !locHost.startsWith("192.168.")
                            && !locHost.startsWith("10.")
                            && !locHost.startsWith("172.")) {
                        int port = loc.getPort();
                        String extBase = loc.getProtocol() + "://" + locHost + (port > 0 ? ":" + port : "");
                        Log.d(TAG, "resolveViaRedirect → 외부 NAS 주소: " + extBase);
                        if (probeUrl(extBase)) return extBase;
                        return extBase; // probe 실패해도 외부 주소이면 반환
                    }
                    current = location;
                    continue;
                }
                if (code == 200) {
                    // /https_first 등 relay path prefix 포함해서 base 추출
                    // e.g. http://gomji17.quickconnect.to/https_first/webapi/... → http://gomji17.quickconnect.to/https_first
                    int webApiIdx = current.indexOf("/webapi/");
                    String base = webApiIdx > 0 ? current.substring(0, webApiIdx) : extractBase(current);
                    Log.d(TAG, "resolveViaRedirect HTTP200 base=" + base);
                    if (probeUrl(base)) return base;
                    // path prefix 가 있는 경우 probe 실패해도 relay URL 로 반환 (login 에서 재검증)
                    if (webApiIdx > 0) {
                        Log.d(TAG, "resolveViaRedirect relay path prefix URL 반환: " + base);
                        return base;
                    }
                }
                break;
            }
        } catch (Exception e) {
            Log.d(TAG, "resolveViaRedirect fail: " + e.getMessage());
        }
        return null;
    }

    /** URL이 실제로 DSM API를 서비스하는지 빠르게 확인 (3초 타임아웃) */
    private static boolean probeUrl(String baseUrl) {
        try {
            String probe = baseUrl + "/webapi/auth.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth";
            HttpURLConnection conn = openTrustedConnection(probe);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            Log.d(TAG, "probe " + baseUrl + " → HTTP " + code);
            if (code == 200) {
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[64];
                int n = is.read(buf);
                conn.disconnect();
                if (n > 0) {
                    String start = new String(buf, 0, n, StandardCharsets.UTF_8);
                    return start.contains("{"); // JSON 응답 확인
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.d(TAG, "probe fail " + baseUrl + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * 후보 URL 목록을 병렬로 probe — 가장 먼저 응답하는 URL 반환.
     * LAN/외부 혼합 상황에서 LAN IP 타임아웃을 기다리지 않고 외부 URL이 먼저 응답하면 바로 사용.
     * 최대 대기 시간 = probe 타임아웃(3초) + 여유 1초.
     */
    private static String probeBestUrl(List<String> candidates) {
        if (candidates.isEmpty()) return null;

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> winner = new AtomicReference<>(null);
        CountDownLatch allFinished = new CountDownLatch(candidates.size());

        for (String url : candidates) {
            Thread t = new Thread(() -> {
                try {
                    if (winner.get() == null && probeUrl(url)) {
                        if (winner.compareAndSet(null, url)) {
                            done.countDown(); // 첫 번째 성공 → 즉시 반환 트리거
                        }
                    }
                } finally {
                    allFinished.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        try {
            // 첫 성공 또는 전체 완료까지 최대 4초 대기
            done.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return winner.get();
    }

    /** JSON body POST (Content-Type: application/json) */
    private static String httpPost(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setInstanceFollowRedirects(false);

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        int code = conn.getResponseCode();
        Log.d(TAG, "HTTP POST " + code + " ← " + urlStr);

        InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) { conn.disconnect(); return ""; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return sb.toString();
    }

    /** SSL 인증서 검증 없이 연결 (개인 NAS의 자체 서명 인증서 대응) */
    private static HttpURLConnection openTrustedConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        if (!url.getProtocol().equals("https")) {
            return (HttpURLConnection) url.openConnection();
        }
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new SecureRandom());
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(sc.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> true);
        return conn;
    }

    /** Multipart POST 로 파일 업로드. 서버 응답 body 반환 */
    private static String uploadFile(String destFolder, String filename, byte[] content, String sid) throws Exception {
        String boundary = "----MinseoNasBoundary" + System.currentTimeMillis();
        String base     = apiBase();
        // _sid를 URL 파라미터로도 전달 (일부 DSM 버전에서 form field만으로 인증 안 됨)
        String urlStr   = base + "/webapi/entry.cgi?_sid=" + URLEncoder.encode(sid, "UTF-8");
        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        // 포털 모드이면 쿠키 헤더 추가
        if (isPortalMode() && !cfgPortalCookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cfgPortalCookie);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        appendField(baos, boundary, "api",            "SYNO.FileStation.Upload");
        appendField(baos, boundary, "version",        "2");
        appendField(baos, boundary, "method",         "upload");
        appendField(baos, boundary, "path",           destFolder);
        appendField(baos, boundary, "create_parents", "true");
        appendField(baos, boundary, "overwrite",      "true");
        appendField(baos, boundary, "_sid",           sid);
        // 파일 파트
        String filePart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        baos.write(filePart.getBytes(StandardCharsets.UTF_8));
        baos.write(content);
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(baos.toByteArray());
        }
        int resp = conn.getResponseCode();
        InputStream ris = (resp < 400) ? conn.getInputStream() : conn.getErrorStream();
        String respBody = "";
        if (ris != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ris, StandardCharsets.UTF_8))) {
                String l; while ((l = br.readLine()) != null) sb.append(l);
            }
            respBody = sb.toString();
        }
        conn.disconnect();
        if (resp >= 400) throw new Exception("upload HTTP " + resp + ": " + respBody);
        return respBody;
    }

    private static void appendField(ByteArrayOutputStream baos, String boundary,
                                    String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        baos.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static String posFilename(String canonicalUrl) {
        return "pos_" + Integer.toHexString(Math.abs(canonicalUrl.hashCode())) + ".json";
    }

    private static final Pattern EP_PATTERN = Pattern.compile("[Ee](\\d+)");
    private static int extractEpisode(String name) {
        Matcher m = EP_PATTERN.matcher(name);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return Integer.MAX_VALUE;
    }

    private static final String[] VIDEO_EXTS = {
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".ts",
            ".m4v", ".3gp", ".webm", ".flv", ".m3u8"
    };
    private static boolean isVideoFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}
