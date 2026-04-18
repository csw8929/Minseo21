package com.example.minseo21;

import android.util.Log;

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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP 저수준 유틸리티. 쿠키 수집 + 수동 리다이렉트 추적 + 자체 서명 TLS 허용.
 * 상태 없음(stateless). DSM FileStation API 와 무관 — Synology 외 NAS도 재사용 가능.
 */
final class DsHttp {
    private static final String TAG = "NAS";
    static final int CONNECT_TIMEOUT = 10_000;
    static final int READ_TIMEOUT    = 15_000;

    static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private DsHttp() {}

    /** GET. 최대 5회 수동 리다이렉트, 쿠키 누적 전달. */
    static String httpGet(String urlStr) throws Exception {
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

            java.util.List<String> cookies = conn.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String cookie : cookies) {
                    String cookieName = cookie.split(";")[0].trim();
                    Log.d(TAG, "Set-Cookie: " + cookieName);
                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                    cookieHeader.append(cookieName);
                }
            }

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
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

    /** URL이 실제로 DSM API를 서비스하는지 빠르게 확인 (3초 타임아웃). JSON 응답 확인. */
    static boolean probeUrl(String baseUrl) {
        try {
            String probe = baseUrl + "/webapi/auth.cgi?api=SYNO.API.Info&version=1&method=query&query=SYNO.API.Auth";
            HttpURLConnection conn = openTrustedConnection(probe);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[64];
                int n = is.read(buf);
                conn.disconnect();
                if (n > 0) {
                    String start = new String(buf, 0, n, StandardCharsets.UTF_8);
                    return start.contains("{");
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }
        return false;
    }

    /** form-encoded POST — Synology 배열/객체 파라미터용. */
    static String httpPostForm(String urlStr, String formBody) throws Exception {
        HttpURLConnection conn = openTrustedConnection(urlStr);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setInstanceFollowRedirects(false);
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int code = conn.getResponseCode();
        Log.d(TAG, "HTTP POST-form " + code + " ← " + urlStr);
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

    static String httpPost(String urlStr, String jsonBody) throws Exception {
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

    /** SSL 인증서 검증 없이 연결 (NAS 자체 서명 인증서 대응). */
    static HttpURLConnection openTrustedConnection(String urlStr) throws Exception {
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

    /**
     * Multipart POST 로 파일 업로드. 서버 응답 body 반환.
     * @param urlStr 업로드 엔드포인트 (FileStation entry.cgi?_sid=...)
     * @param destFolder 업로드 대상 폴더
     * @param filename 저장 파일명
     * @param content 파일 내용
     * @param sid FileStation SID (form field 로도 전달)
     */
    static String uploadFile(String urlStr, String destFolder, String filename,
                             byte[] content, String sid) throws Exception {
        String boundary = "----MinseoNasBoundary" + System.currentTimeMillis();
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
}
