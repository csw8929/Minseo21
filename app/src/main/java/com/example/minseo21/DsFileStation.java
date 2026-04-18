package com.example.minseo21;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileStation 목록/URL + Video Station file_id 매핑 + HLS 트랜스코딩 세션.
 * DsAuth(SID/resolvedBase)와 DsHttp(저수준 IO)에 의존.
 */
final class DsFileStation {
    private static final String TAG = "NAS";

    /** sharepath(/video/...) → Video Station file_id 캐시. DsAuth.init() 호출 시 클리어. */
    private static final Map<String, Integer> fileIdCache = new ConcurrentHashMap<>();

    static void clearFileIdCache() { fileIdCache.clear(); }

    private DsFileStation() {}

    // ── 폴더 목록 ────────────────────────────────────────────────────────────

    /**
     * 지정 경로의 파일/폴더 목록. 폴더 먼저(이름순), 파일은 에피소드 번호([Ee]\d+) 순.
     * SID 만료(105/106) 시 한 번만 재로그인 후 재시도.
     */
    static void listFolder(String folderPath, String sid, DsFileApiClient.Callback<List<VideoItem>> cb) {
        DsAuth.executor.execute(() -> listFolderSync(folderPath, sid, cb, false));
    }

    private static void listFolderSync(String folderPath, String sid,
                                       DsFileApiClient.Callback<List<VideoItem>> cb, boolean isRetry) {
        try {
            String base = DsAuth.apiBase();
            String url = base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.List&version=2&method=list"
                    + "&folder_path=" + URLEncoder.encode(folderPath, "UTF-8")
                    + "&sort_by=name&sort_direction=ASC"
                    + "&additional=%5B%22size%22%2C%22time%22%5D"
                    + "&limit=2000&offset=0"
                    + "&_sid=" + sid;
            Log.d(TAG, "listFolder → " + folderPath + (isRetry ? " [retry]" : ""));
            String body = DsHttp.httpGet(url);
            JSONObject json = new JSONObject(body);

            if (!json.optBoolean("success", false)) {
                int code = json.optJSONObject("error") != null
                        ? json.getJSONObject("error").optInt("code", -1) : -1;
                if ((code == 105 || code == 106) && !isRetry) {
                    Log.w(TAG, "SID 만료, 재로그인 후 listFolder 재시도");
                    String newSid = DsAuth.reLoginSync();
                    if (newSid != null) {
                        listFolderSync(folderPath, newSid, cb, true);
                    } else {
                        DsAuth.mainHandler.post(() -> cb.onError("세션 갱신 실패"));
                    }
                    return;
                }
                DsAuth.mainHandler.post(() -> cb.onError("목록 로딩 실패 (code=" + code + ")"));
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

            videos.sort((a, b) -> {
                int ea = extractEpisode(a.name);
                int eb = extractEpisode(b.name);
                if (ea != eb) return Integer.compare(ea, eb);
                return a.name.compareTo(b.name);
            });

            List<VideoItem> result = new ArrayList<>(folders);
            result.addAll(videos);
            Log.i(TAG, "listFolder OK: " + folders.size() + " 폴더, " + videos.size() + " 파일");
            DsAuth.mainHandler.post(() -> cb.onResult(result));

        } catch (Exception e) {
            Log.e(TAG, "listFolder exception", e);
            DsAuth.mainHandler.post(() -> cb.onError("목록 로딩 오류: " + e.getMessage()));
        }
    }

    // ── Video Station file_id 조회 ───────────────────────────────────────────

    /**
     * NAS 공유 경로(/video/…)에 해당하는 Video Station file_id 조회.
     * Movie.list 페이징 → TVShow/Episode.list 폴백. 성공 시 fileIdCache 저장.
     */
    static void findFileIdForSharePath(String sharePath, DsFileApiClient.Callback<Integer> cb) {
        if (sharePath == null || sharePath.isEmpty()) {
            DsAuth.mainHandler.post(() -> cb.onError("sharePath 없음"));
            return;
        }
        Integer cached = fileIdCache.get(sharePath);
        if (cached != null) {
            DsAuth.mainHandler.post(() -> cb.onResult(cached));
            return;
        }
        DsAuth.executor.execute(() -> {
            try {
                Integer fid = findFileIdSync(sharePath, false);
                if (fid != null && fid > 0) {
                    fileIdCache.put(sharePath, fid);
                    DsAuth.mainHandler.post(() -> cb.onResult(fid));
                } else {
                    DsAuth.mainHandler.post(() -> cb.onError("file_id 미발견: " + sharePath));
                }
            } catch (Exception e) {
                Log.w(TAG, "findFileId exception", e);
                DsAuth.mainHandler.post(() -> cb.onError("file_id 조회 오류: " + e.getMessage()));
            }
        });
    }

    private static Integer findFileIdSync(String sharePath, boolean isRetry) throws Exception {
        String sid = DsAuth.cachedSid;
        if (sid == null) {
            sid = DsAuth.reLoginSync();
            if (sid == null) return null;
        }
        String base = DsAuth.apiBase();
        String addFile = URLEncoder.encode("[\"file\"]", "UTF-8");

        // 1. Movie.list 페이지네이션
        int offset = 0;
        final int limit = 500;
        while (true) {
            String url = base + "/webapi/entry.cgi"
                    + "?api=SYNO.VideoStation2.Movie&method=list&version=1"
                    + "&library_id=0&additional=" + addFile
                    + "&limit=" + limit + "&offset=" + offset
                    + "&_sid=" + sid;
            String body = DsHttp.httpGet(url);
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("success", false)) {
                int code = json.optJSONObject("error") != null
                        ? json.getJSONObject("error").optInt("code", -1) : -1;
                if ((code == 105 || code == 106) && !isRetry) {
                    String newSid = DsAuth.reLoginSync();
                    if (newSid != null) return findFileIdSync(sharePath, true);
                }
                Log.w(TAG, "Movie.list 실패 code=" + code + " → TVShow 폴백");
                break;
            }
            JSONObject data = json.getJSONObject("data");
            int total = data.optInt("total", 0);
            JSONArray arr = data.optJSONArray("movie");
            if (arr == null) break;
            Integer hit = matchSharepath(arr, sharePath);
            if (hit != null) return hit;
            if (arr.length() == 0) break;
            offset += arr.length();
            if (offset >= total) break;
        }

        // 2. TVShow → Episode.list 폴백
        String tvUrl = base + "/webapi/entry.cgi"
                + "?api=SYNO.VideoStation2.TVShow&method=list&version=1"
                + "&library_id=0&limit=500&_sid=" + sid;
        String tvBody = DsHttp.httpGet(tvUrl);
        JSONObject tvJson = new JSONObject(tvBody);
        if (!tvJson.optBoolean("success", false)) return null;
        JSONArray shows = tvJson.getJSONObject("data").optJSONArray("tvshow");
        if (shows == null) return null;
        for (int i = 0; i < shows.length(); i++) {
            int showId = shows.getJSONObject(i).optInt("id", -1);
            if (showId < 0) continue;
            int epOffset = 0;
            while (true) {
                String epUrl = base + "/webapi/entry.cgi"
                        + "?api=SYNO.VideoStation2.TVShowEpisode&method=list&version=1"
                        + "&tvshow_id=" + showId
                        + "&additional=" + addFile
                        + "&limit=" + limit + "&offset=" + epOffset
                        + "&_sid=" + sid;
                String epBody = DsHttp.httpGet(epUrl);
                JSONObject epJson = new JSONObject(epBody);
                if (!epJson.optBoolean("success", false)) break;
                JSONObject epData = epJson.getJSONObject("data");
                int epTotal = epData.optInt("total", 0);
                JSONArray eps = epData.optJSONArray("episode");
                if (eps == null || eps.length() == 0) break;
                Integer hit = matchSharepath(eps, sharePath);
                if (hit != null) return hit;
                epOffset += eps.length();
                if (epOffset >= epTotal) break;
            }
        }
        return null;
    }

    private static Integer matchSharepath(JSONArray arr, String target) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            JSONObject add = m.optJSONObject("additional");
            if (add == null) continue;
            JSONArray files = add.optJSONArray("file");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject f = files.optJSONObject(j);
                if (f == null) continue;
                String sp = f.optString("sharepath", "");
                if (!sp.isEmpty() && sp.equals(target)) {
                    int id = f.optInt("id", -1);
                    if (id > 0) return id;
                }
            }
        }
        return null;
    }

    // ── HLS 트랜스코딩 세션 ──────────────────────────────────────────────────

    /**
     * HLS 트랜스코딩 세션 오픈. 성공 시 TranscodeSession(streamId+hlsUrl).
     * 호출 측은 재생 종료/전환 시 closeTranscodeStream 반드시 호출 (NAS ffmpeg 누수 방지).
     */
    static void openTranscodeStream(int fileId, String format,
                                    DsFileApiClient.Callback<DsFileApiClient.TranscodeSession> cb) {
        final String fmt = (format == null || format.isEmpty()) ? "hls" : format;
        DsAuth.executor.execute(() -> {
            String sid = DsAuth.cachedSid;
            if (sid == null) {
                sid = DsAuth.reLoginSync();
                if (sid == null) {
                    DsAuth.mainHandler.post(() -> cb.onError("세션 없음"));
                    return;
                }
            }
            try {
                String base = DsAuth.apiBase();
                String acceptFormat;
                try {
                    acceptFormat = URLEncoder.encode("hls_remux,hls,raw", "UTF-8");
                } catch (Exception e) {
                    acceptFormat = "hls_remux%2Chls%2Craw";
                }
                String openUrl = base + "/webapi/VideoStation/vtestreaming.cgi"
                        + "?api=SYNO.VideoStation.Streaming&version=2&method=open"
                        + "&id=" + fileId
                        + "&format=" + fmt
                        + "&audio_track_id=0&subtitle_track_id=-1"
                        + "&accept_format=" + acceptFormat
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(openUrl);
                JSONObject json = new JSONObject(body);
                if (!json.optBoolean("success", false)) {
                    int code = json.optJSONObject("error") != null
                            ? json.getJSONObject("error").optInt("code", -1) : -1;
                    DsAuth.mainHandler.post(() -> cb.onError("트랜스코딩 open 실패 (code=" + code + ")"));
                    return;
                }
                String streamId = json.getJSONObject("data").getString("stream_id");
                String hlsUrl = base + "/webapi/VideoStation/vtestreaming.cgi"
                        + "?api=SYNO.VideoStation.Streaming&version=2&method=stream"
                        + "&id=" + URLEncoder.encode(streamId, "UTF-8")
                        + "&format=" + fmt
                        + "&_sid=" + sid;
                DsFileApiClient.TranscodeSession s =
                        new DsFileApiClient.TranscodeSession(streamId, hlsUrl, fileId, fmt);
                Log.i(TAG, "openTranscodeStream OK streamId=" + streamId + " fileId=" + fileId);
                DsAuth.mainHandler.post(() -> cb.onResult(s));
            } catch (Exception e) {
                Log.w(TAG, "openTranscodeStream exception", e);
                DsAuth.mainHandler.post(() -> cb.onError("open 오류: " + e.getMessage()));
            }
        });
    }

    /** 트랜스코딩 세션 종료 (fire-and-forget). 실패해도 로깅만. */
    static void closeTranscodeStream(String streamId, String format) {
        if (streamId == null || streamId.isEmpty()) return;
        final String fmt = (format == null || format.isEmpty()) ? "hls" : format;
        DsAuth.executor.execute(() -> {
            String sid = DsAuth.cachedSid;
            if (sid == null) return;
            try {
                String base = DsAuth.apiBase();
                String url = base + "/webapi/VideoStation/vtestreaming.cgi"
                        + "?api=SYNO.VideoStation.Streaming&version=2&method=close"
                        + "&id=" + URLEncoder.encode(streamId, "UTF-8")
                        + "&format=" + fmt
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(url);
                String head = body != null && body.length() > 120 ? body.substring(0, 120) + "…" : body;
                Log.i(TAG, "closeTranscodeStream streamId=" + streamId + " ← " + head);
            } catch (Exception e) {
                Log.w(TAG, "closeTranscodeStream 실패: " + e.getMessage());
            }
        });
    }

    // ── URL 헬퍼 (동기, 네트워크 없음) ──────────────────────────────────────

    /** 스트림 URL (SID 포함) — libVLC 재생용. resolvedBase 경유. */
    static String getStreamUrl(String filePath, String sid) {
        try {
            String base = DsAuth.apiBase();
            return base + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.Download&version=2&method=download"
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8")
                    + "&mode=open&_sid=" + sid;
        } catch (Exception e) {
            return "";
        }
    }

    /** Canonical URL (SID 없음) — Room DB 키. 항상 cfgBaseUrl 기반 (기기 간 일관성). */
    static String getCanonicalUrl(String filePath) {
        try {
            return DsAuth.cfgBaseUrl + "/webapi/entry.cgi"
                    + "?api=SYNO.FileStation.Download&version=2&method=download"
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8")
                    + "&mode=open";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Canonical URL + SID 붙이기 — 이어보기 다이얼로그용.
     * 기존 _sid 제거 후 새 SID 부착. resolvedBase 가 cfgBaseUrl 과 다르면 base 교체.
     */
    static String canonicalToStream(String canonicalUrl, String sid) {
        String clean = toCanonicalUrl(canonicalUrl);
        String base = DsAuth.apiBase();
        String rewritten = clean;
        if (!base.equals(DsAuth.cfgBaseUrl) && clean.startsWith(DsAuth.cfgBaseUrl)) {
            rewritten = base + clean.substring(DsAuth.cfgBaseUrl.length());
        }
        return rewritten + "&_sid=" + sid;
    }

    /**
     * NAS 스트림 URL → canonical 정규화. _sid 제거, cfgBaseUrl 기반.
     * NAS URL 이 아니면 원본 반환.
     */
    static String toCanonicalUrl(String streamUrl) {
        if (!isNasUrl(streamUrl)) return streamUrl;
        try {
            android.net.Uri u = android.net.Uri.parse(streamUrl);
            String path = u.getQueryParameter("path");
            if (path != null && !path.isEmpty()) {
                return getCanonicalUrl(path);
            }
        } catch (Exception ignored) {}
        return streamUrl;
    }

    static boolean isNasUrl(String url) {
        return url != null && url.contains("/webapi/entry.cgi");
    }

    /**
     * WiFi 전용 연결 여부.
     * 셀룰러가 하나라도 있으면 false → HLS 트랜스코딩.
     * 판단 불가 시 false (안전한 기본값).
     */
    static boolean isWifi(android.content.Context ctx) {
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
            return hasWifi && !hasCellular;
        } catch (Exception e) {
            Log.w(TAG, "isWifi 감지 실패: " + e.getMessage());
            return false;
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

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
