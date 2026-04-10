package com.example.minseo2;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static volatile String cfgBaseUrl  = DsFileConfig.BASE_URL;
    private static volatile String cfgLanUrl   = DsFileConfig.LAN_URL;
    private static volatile String cfgUser     = DsFileConfig.USER;
    private static volatile String cfgPass     = DsFileConfig.PASS;
    private static volatile String cfgBasePath = DsFileConfig.BASE_PATH;
    private static volatile String cfgPosDir   = DsFileConfig.POS_DIR;

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

    /** NasCredentialStore 에서 읽은 인증 정보를 적용하는 편의 메서드. */
    public static void init(NasCredentialStore store) {
        init(store.getBaseUrl(), store.getLanUrl(), store.getUser(), store.getPass(),
                store.getBasePath(), store.getPosDir());
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
                // QuickConnect URL인 경우 실제 NAS 주소 먼저 해석
                if (resolvedBase == null) {
                    String resolved = resolveQuickConnect(cfgBaseUrl);
                    resolvedBase = (resolved != null) ? resolved : cfgBaseUrl;
                    Log.i(TAG, "resolvedBase=" + resolvedBase);
                }
                String url = resolvedBase + "/webapi/auth.cgi"
                        + "?api=SYNO.API.Auth&version=6&method=login"
                        + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                        + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                        + "&session=FileStation&format=sid";
                Log.d(TAG, "login → " + cfgBaseUrl);
                String body = httpGet(url);
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
            String base = resolvedBase != null ? resolvedBase : cfgBaseUrl;
            String url = base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.List&version=2&method=list"
                    + "&folder_path=" + URLEncoder.encode(folderPath, "UTF-8")
                    + "&sort_by=name&sort_direction=ASC"
                    + "&additional=%5B%22size%22%2C%22time%22%5D"
                    + "&limit=2000&offset=0"
                    + "&_sid=" + sid;
            Log.d(TAG, "listFolder → " + folderPath + (isRetry ? " [retry]" : ""));
            String body = httpGet(url);
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

    // ── URL 헬퍼 (동기, 네트워크 없음) ──────────────────────────────────────

    /** 스트림 URL (SID 포함) — libVLC 재생용 */
    public static String getStreamUrl(String filePath, String sid) {
        try {
            String base = resolvedBase != null ? resolvedBase : cfgBaseUrl;
            return base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.Download&version=2&method=download"
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8")
                    + "&mode=open&_sid=" + sid;
        } catch (Exception e) {
            return "";
        }
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

    /** Canonical URL + SID 붙이기 — 이어보기 다이얼로그용 */
    public static String canonicalToStream(String canonicalUrl, String sid) {
        return canonicalUrl + "&_sid=" + sid;
    }

    /** NAS 스트림 URL 여부 판단 */
    public static boolean isNasUrl(String url) {
        return url != null && url.contains("/webapi/entry.cgi");
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
                String body = httpGet(downloadUrl);

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

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    private static String httpGet(String urlStr) throws Exception {
        // 리다이렉트를 최대 5회까지 수동으로 따라감 (HTTPS→HTTPS 포함)
        String currentUrl = urlStr;
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection conn = openTrustedConnection(currentUrl);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false); // 수동 처리

            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP " + code + " ← " + currentUrl.replaceAll("passwd=[^&]+", "passwd=***"));

            // 리다이렉트 처리
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new Exception("Redirect without Location");
                // 상대 경로 처리
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
     * 0) 같은 LAN의 ARP 테이블에서 Synology MAC 장치를 탐지 (최우선)
     * 1) global → regional Serv.php POST 로 서버 정보 수집
     * 2) 후보 URL 목록(LAN IP, DDNS, 외부 IP) 순으로 probe 하여 최초 응답 반환
     */
    private static String resolveQuickConnect(String baseUrl) {
        try {
            if (!baseUrl.toLowerCase().contains("quickconnect.to")) return null;

            String host = new URL(baseUrl).getHost();
            String qcId = host.split("\\.")[0];
            Log.d(TAG, "QuickConnect ID: " + qcId);

            // ── Step 0: LAN_URL 설정된 경우 먼저 probe ──────────────────────
            String lanUrl = cfgLanUrl;
            if (lanUrl != null && !lanUrl.isEmpty()) {
                for (String candidate : new String[]{
                        lanUrl,
                        lanUrl + ":5001",
                        lanUrl.replace("https://", "http://") + ":5000"}) {
                    if (probeUrl(candidate)) {
                        Log.i(TAG, "LAN NAS 연결 성공: " + candidate);
                        return candidate;
                    }
                }
                Log.d(TAG, "LAN_URL probe 실패, QuickConnect 시도");
            }

            // Step 1: global GET → regional 서버 hostname 반환 (예: "usc.quickconnect.to")
            String servParams = "?id=" + qcId + "&port=5001&stopReason=0"
                    + "&compound=%5B%7B%22api%22%3A%22SYNO.API.Info%22%2C%22method%22%3A%22query%22%2C%22version%22%3A1%7D%5D";
            String resp1 = httpGet("https://global.quickconnect.to/Serv.php" + servParams);
            Log.d(TAG, "global: " + resp1.substring(0, Math.min(200, resp1.length())));

            List<String> candidates = new ArrayList<>();
            collectCandidates(resp1, candidates);

            // Step 2: regional 서버에 GET → 실제 NAS 서버 정보 JSON
            String regional = resp1.trim();
            if (!regional.startsWith("{") && !regional.isEmpty() && regional.length() < 100) {
                try {
                    String resp2 = httpGet("https://" + regional + "/Serv.php" + servParams);
                    Log.d(TAG, regional + ": " + resp2.substring(0, Math.min(300, resp2.length())));
                    collectCandidates(resp2, candidates);
                } catch (Exception ignored) {}
            }

            Log.d(TAG, "QuickConnect candidates: " + candidates);
            // 가장 먼저 응답하는 URL 사용 (3초 타임아웃)
            for (String url : candidates) {
                if (probeUrl(url)) {
                    Log.i(TAG, "QuickConnect resolved: " + url);
                    return url;
                }
            }
            Log.w(TAG, "QuickConnect: 모든 후보 실패");
            return null;

        } catch (Exception e) {
            Log.w(TAG, "QuickConnect resolve failed: " + e.getMessage());
            return null;
        }
    }

    /** Serv.php JSON에서 후보 URL 목록 수집 (LAN IP 우선) */
    private static void collectCandidates(String body, List<String> out) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject server = json.optJSONObject("server");
            if (server == null) return;

            // LAN 인터페이스 IP (같은 네트워크일 경우 가장 빠름)
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
            // DDNS
            String ddns = server.optString("ddns", "");
            if (!ddns.isEmpty() && !ddns.equals("NULL")) {
                out.add("https://" + ddns);
                out.add("https://" + ddns + ":5001");
            }
            // 외부 IP
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
        } catch (Exception e) {
            Log.d(TAG, "collectCandidates: " + e.getMessage());
        }
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

    /** JSON body POST (Content-Type: application/json) */
    private static String httpPost(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
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
        String base     = resolvedBase != null ? resolvedBase : cfgBaseUrl;
        // _sid를 URL 파라미터로도 전달 (일부 DSM 버전에서 form field만으로 인증 안 됨)
        String urlStr   = base + "/webapi/entry.cgi?_sid=" + URLEncoder.encode(sid, "UTF-8");
        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

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
