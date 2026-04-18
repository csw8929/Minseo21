package com.example.minseo21;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;

/**
 * Synology FileStation HTTP API 파사드.
 * 실제 구현은 4개 클래스에 분리되어 있다:
 * <ul>
 *   <li>{@link DsHttp}        — HTTP 저수준 IO (쿠키, 리다이렉트, TLS, multipart)</li>
 *   <li>{@link DsAuth}        — 자격 증명 + SID + resolvedBase + 네트워크 모니터링</li>
 *   <li>{@link DsFileStation} — 폴더 목록, file_id 조회, HLS 트랜스코딩, URL 헬퍼</li>
 *   <li>{@link DsPlayback}    — 재생 위치 NAS 저장/로드 (per-file + 사용자 번들)</li>
 * </ul>
 * 호출부 호환을 위해 정적 메서드 시그니처는 분리 전과 동일하게 유지한다.
 * {@link Callback} 과 {@link TranscodeSession} 중첩 타입도 여기 유지.
 */
public class DsFileApiClient {

    private DsFileApiClient() {}

    /** 비동기 콜백 — NAS 관련 모든 API 공통. 결과는 메인 스레드로 전달된다. */
    public interface Callback<T> {
        void onResult(T result);
        void onError(String msg);
    }

    /** HLS 트랜스코딩 세션 핸들. close 호출에 필요한 정보를 보관. */
    public static class TranscodeSession {
        public final String streamId;
        public final String hlsUrl;
        public final int fileId;
        public final String format;
        TranscodeSession(String streamId, String hlsUrl, int fileId, String format) {
            this.streamId = streamId;
            this.hlsUrl = hlsUrl;
            this.fileId = fileId;
            this.format = format;
        }
    }

    // ── 초기화/설정 ──────────────────────────────────────────────────────────

    public static void init(String baseUrl, String lanUrl, String user, String pass,
                            String basePath, String posDir) {
        DsAuth.init(baseUrl, lanUrl, user, pass, basePath, posDir);
    }

    public static void init(NasCredentialStore store) {
        String user = store.getUser();
        String pass = store.getPass();
        if (user.isEmpty()) user = DsFileConfig.USER;
        if (pass.isEmpty()) pass = DsFileConfig.PASS;
        DsAuth.init(store.getBaseUrl(), store.getLanUrl(), user, pass,
                store.getBasePath(), store.getPosDir());
    }

    public static String getBasePath()  { return DsAuth.cfgBasePath; }
    public static String getCachedSid() { return DsAuth.cachedSid; }

    public static void startNetworkMonitoring(Context ctx) {
        DsAuth.startNetworkMonitoring(ctx);
    }

    // ── 인증 ────────────────────────────────────────────────────────────────

    public static void login(Callback<String> cb) { DsAuth.login(cb); }

    // ── FileStation ─────────────────────────────────────────────────────────

    public static void listFolder(String folderPath, String sid, Callback<List<VideoItem>> cb) {
        DsFileStation.listFolder(folderPath, sid, cb);
    }

    public static void findFileIdForSharePath(String sharePath, Callback<Integer> cb) {
        DsFileStation.findFileIdForSharePath(sharePath, cb);
    }

    public static void openTranscodeStream(int fileId, String format, Callback<TranscodeSession> cb) {
        DsFileStation.openTranscodeStream(fileId, format, cb);
    }

    public static void closeTranscodeStream(String streamId, String format) {
        DsFileStation.closeTranscodeStream(streamId, format);
    }

    // ── URL 헬퍼 (동기) ─────────────────────────────────────────────────────

    public static String getStreamUrl(String filePath, String sid) {
        return DsFileStation.getStreamUrl(filePath, sid);
    }

    public static String getCanonicalUrl(String filePath) {
        return DsFileStation.getCanonicalUrl(filePath);
    }

    public static String canonicalToStream(String canonicalUrl, String sid) {
        return DsFileStation.canonicalToStream(canonicalUrl, sid);
    }

    public static String toCanonicalUrl(String streamUrl) {
        return DsFileStation.toCanonicalUrl(streamUrl);
    }

    public static boolean isNasUrl(String url) {
        return DsFileStation.isNasUrl(url);
    }

    public static boolean isWifi(Context ctx) {
        return DsFileStation.isWifi(ctx);
    }

    // ── 재생 위치 동기화 ─────────────────────────────────────────────────────

    public static void savePositionToNas(String canonicalUrl, PlaybackPosition pos, String sid) {
        DsPlayback.savePositionToNas(canonicalUrl, pos, sid);
    }

    public static void loadPositionFromNas(String canonicalUrl, String sid,
                                           Callback<PlaybackPosition> cb) {
        DsPlayback.loadPositionFromNas(canonicalUrl, sid, cb);
    }

    public static void uploadUserPositions(JSONObject positions, Callback<Boolean> cb) {
        DsPlayback.uploadUserPositions(positions, cb);
    }

    public static void downloadUserPositions(Callback<JSONObject> cb) {
        DsPlayback.downloadUserPositions(cb);
    }

    public static boolean uploadUserPositionsSync(JSONObject positions) {
        return DsPlayback.uploadUserPositionsSync(positions);
    }

    public static JSONObject downloadUserPositionsSync() {
        return DsPlayback.downloadUserPositionsSync();
    }
}
