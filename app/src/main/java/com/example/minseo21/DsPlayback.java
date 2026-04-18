package com.example.minseo21;

import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 재생 위치 JSON 을 NAS 에 저장/로드.
 * 단일 파일 (per-canonical) 및 사용자 전체 positions 번들 두 가지 경로 지원.
 * fire-and-forget 이 기본 — 실패해도 Room DB 에 이미 저장되어 있어 치명적이지 않음.
 */
final class DsPlayback {
    private static final String TAG = "NAS";

    private DsPlayback() {}

    private static String posFilename(String canonicalUrl) {
        return "pos_" + Integer.toHexString(Math.abs(canonicalUrl.hashCode())) + ".json";
    }

    private static String userPositionsFilename() {
        return DsAuth.cfgUser.replaceAll("[^a-zA-Z0-9_\\-]", "_") + "_positions.json";
    }

    /** uploadFile 에 전달할 엔드포인트 URL (SID 는 form field + URL param 양쪽). */
    private static String uploadEndpoint(String sid) throws Exception {
        return DsAuth.apiBase() + "/webapi/entry.cgi?_sid=" + URLEncoder.encode(sid, "UTF-8");
    }

    // ── 개별 파일 위치 저장/로드 ────────────────────────────────────────────

    /** 재생 위치를 NAS JSON 파일로 저장 (fire-and-forget). */
    static void savePositionToNas(String canonicalUrl, PlaybackPosition pos, String sid) {
        DsAuth.executor.execute(() -> {
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
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                String uploadResult = DsHttp.uploadFile(uploadEndpoint(sid),
                        DsAuth.cfgPosDir, filename, data, sid);
                Log.d(TAG, "NAS 위치 저장: " + filename + " → " + uploadResult);
                if (uploadResult.contains("\"code\":119")) {
                    Log.w(TAG, "SID 119 오류, 재로그인 후 재시도");
                    String newSid = DsAuth.reLoginSync();
                    if (newSid != null) {
                        String retryResult = DsHttp.uploadFile(uploadEndpoint(newSid),
                                DsAuth.cfgPosDir, filename, data, newSid);
                        Log.d(TAG, "NAS 위치 저장 재시도: " + filename + " → " + retryResult);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "NAS 위치 저장 실패 (무시): " + e.getMessage());
            }
        });
    }

    /** NAS 에서 재생 위치 로드. 없으면 null. */
    static void loadPositionFromNas(String canonicalUrl, String sid,
                                    DsFileApiClient.Callback<PlaybackPosition> cb) {
        DsAuth.executor.execute(() -> {
            try {
                String filename = posFilename(canonicalUrl);
                String filePath = DsAuth.cfgPosDir + "/" + filename;
                String downloadUrl = DsFileStation.getStreamUrl(filePath, sid);
                String body = DsHttp.httpGet(downloadUrl);

                JSONObject json = new JSONObject(body);
                PlaybackPosition pos = new PlaybackPosition();
                pos.uri             = canonicalUrl;
                pos.positionMs      = json.optLong("positionMs", 0);
                pos.audioTrackId    = json.optInt("audioTrackId",    Integer.MIN_VALUE);
                pos.subtitleTrackId = json.optInt("subtitleTrackId", Integer.MIN_VALUE);
                pos.screenMode      = json.optInt("screenMode",      -1);
                pos.updatedAt       = json.optLong("updatedAt",      0);
                Log.d(TAG, "NAS 위치 로드: " + pos.positionMs + "ms");
                DsAuth.mainHandler.post(() -> cb.onResult(pos));
            } catch (Exception e) {
                Log.d(TAG, "NAS 위치 없음: " + e.getMessage());
                DsAuth.mainHandler.post(() -> cb.onResult(null));
            }
        });
    }

    // ── 사용자 전체 positions 번들 (NasSyncManager) ──────────────────────────

    static void uploadUserPositions(JSONObject positions, DsFileApiClient.Callback<Boolean> cb) {
        DsAuth.executor.execute(() -> {
            try {
                String sid = DsAuth.cachedSid;
                if (sid == null) { DsAuth.mainHandler.post(() -> cb.onError("SID 없음")); return; }
                JSONObject wrapper = new JSONObject();
                wrapper.put("version", 1);
                wrapper.put("positions", positions);
                String filename = userPositionsFilename();
                byte[] data = wrapper.toString().getBytes(StandardCharsets.UTF_8);
                String result = DsHttp.uploadFile(uploadEndpoint(sid),
                        DsAuth.cfgPosDir, filename, data, sid);
                if (result.contains("\"code\":119")) {
                    String newSid = DsAuth.reLoginSync();
                    if (newSid != null) {
                        result = DsHttp.uploadFile(uploadEndpoint(newSid),
                                DsAuth.cfgPosDir, filename, data, newSid);
                    }
                }
                final boolean ok = result.contains("\"success\":true");
                final String finalResult = result;
                Log.d(TAG, "uploadUserPositions: " + filename + " → " + (ok ? "성공" : result));
                DsAuth.mainHandler.post(() -> { if (ok) cb.onResult(true); else cb.onError(finalResult); });
            } catch (Exception e) {
                Log.w(TAG, "uploadUserPositions 오류: " + e.getMessage());
                DsAuth.mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    static void downloadUserPositions(DsFileApiClient.Callback<JSONObject> cb) {
        DsAuth.executor.execute(() -> {
            try {
                String sid = DsAuth.cachedSid;
                if (sid == null) { DsAuth.mainHandler.post(() -> cb.onResult(new JSONObject())); return; }
                String filename = userPositionsFilename();
                String filePath = DsAuth.cfgPosDir + "/" + filename;
                String downloadUrl = DsFileStation.getStreamUrl(filePath, sid);
                String body = DsHttp.httpGet(downloadUrl);
                JSONObject wrapper = new JSONObject(body);
                JSONObject positions = wrapper.optJSONObject("positions");
                if (positions == null) positions = new JSONObject();
                final JSONObject result = positions;
                Log.d(TAG, "downloadUserPositions: " + result.length() + " 항목");
                DsAuth.mainHandler.post(() -> cb.onResult(result));
            } catch (Exception e) {
                Log.d(TAG, "downloadUserPositions 없음: " + e.getMessage());
                DsAuth.mainHandler.post(() -> cb.onResult(new JSONObject()));
            }
        });
    }

    /** onPause 블로킹 flush 전용 — 호출 스레드에서 직접 실행. */
    static boolean uploadUserPositionsSync(JSONObject positions) {
        try {
            String sid = DsAuth.cachedSid;
            if (sid == null) return false;
            JSONObject wrapper = new JSONObject();
            wrapper.put("version", 1);
            wrapper.put("positions", positions);
            String filename = userPositionsFilename();
            byte[] data = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            String result = DsHttp.uploadFile(uploadEndpoint(sid),
                    DsAuth.cfgPosDir, filename, data, sid);
            if (result.contains("\"code\":119")) {
                String newSid = DsAuth.reLoginSync();
                if (newSid != null) {
                    result = DsHttp.uploadFile(uploadEndpoint(newSid),
                            DsAuth.cfgPosDir, filename, data, newSid);
                }
            }
            boolean ok = result.contains("\"success\":true");
            Log.d(TAG, "uploadUserPositionsSync: " + filename + " → " + (ok ? "성공" : result));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "uploadUserPositionsSync 오류: " + e.getMessage());
            return false;
        }
    }

    /** onPause 블로킹 flush 전용. */
    static JSONObject downloadUserPositionsSync() {
        try {
            String sid = DsAuth.cachedSid;
            if (sid == null) return new JSONObject();
            String filename = userPositionsFilename();
            String filePath = DsAuth.cfgPosDir + "/" + filename;
            String downloadUrl = DsFileStation.getStreamUrl(filePath, sid);
            String body = DsHttp.httpGet(downloadUrl);
            JSONObject wrapper = new JSONObject(body);
            JSONObject positions = wrapper.optJSONObject("positions");
            if (positions == null) positions = new JSONObject();
            Log.d(TAG, "downloadUserPositionsSync: " + positions.length() + " 항목");
            return positions;
        } catch (Exception e) {
            Log.d(TAG, "downloadUserPositionsSync 없음: " + e.getMessage());
            return new JSONObject();
        }
    }
}
