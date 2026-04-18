package com.example.minseo21;

import android.net.Uri;
import android.util.Log;

import org.videolan.libvlc.Media;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 셀룰러/원격에서 NAS 파일을 HLS remux 로 재생.
 *
 * VLC 의 libvlc TLS 스택은 Synology self-signed/만료 체인 인증서를 거부한다.
 * 그리고 Synology 가 반환하는 m3u8 내 세그먼트 URL 은 무조건 HTTPS:5001 절대경로로
 * 하드코딩되어 있어 VLC 가 세그먼트 fetch 시 TLS 핸드셰이크 실패.
 *
 * 따라서 prepare() 는:
 *   1. openTranscodeStream 으로 stream_id + m3u8 URL 획득
 *   2. DsHttp.httpGet 으로 m3u8 직접 GET (self-signed 허용)
 *   3. 세그먼트 URL 을 HTTP:5000 으로 rewrite (포트 5000 은 포트포워딩 오픈됨)
 *   4. 앱 캐시 디렉토리에 저장 후 file:// URL 을 VLC 에 전달
 *
 * VLC 는 file:// 로컬 playlist 를 읽고 rewrite 된 HTTP 세그먼트만 fetch → TLS 이슈 회피.
 */
final class NasHlsSource extends PlaybackSource {
    private static final String TAG = "NAS";
    private String streamId;
    private String format;
    private File cachedPlaylist;
    private volatile boolean stopped = false;

    NasHlsSource(String canonicalKey, String syncKey, String nasPath) {
        super(canonicalKey, syncKey, nasPath);
    }

    @Override
    void addVlcOptions(List<String> options) {
        options.add("--network-caching=5000");
        options.add("--live-caching=300");
        options.add("--clock-jitter=500");
    }

    @Override boolean isNetwork() { return true; }

    @Override
    void prepare(PlaybackHost host, MediaReadyCallback cb) {
        if (nasPath == null || nasPath.isEmpty()) {
            cb.onError("NAS 경로 없음");
            return;
        }
        DsFileApiClient.findFileIdForSharePath(nasPath, new DsFileApiClient.Callback<Integer>() {
            @Override public void onResult(Integer fileId) {
                if (stopped) return;
                DsFileApiClient.openTranscodeStream(fileId, "hls_remux",
                        new DsFileApiClient.Callback<DsFileApiClient.TranscodeSession>() {
                    @Override public void onResult(DsFileApiClient.TranscodeSession s) {
                        if (stopped) {
                            DsFileApiClient.closeTranscodeStream(s.streamId, s.format);
                            return;
                        }
                        streamId = s.streamId;
                        format = s.format;
                        // m3u8 fetch + rewrite 는 I/O — 백그라운드 스레드에서
                        DsAuth.executor.execute(() -> {
                            try {
                                String body = DsHttp.httpGet(s.hlsUrl);
                                // 세그먼트 URL 내 https://<host>:5001/ → http://<host>:5000/ 일괄 치환
                                String rewritten = body.replaceAll(
                                        "https://([^/\\s]+):5001/",
                                        "http://$1:5000/");
                                File cacheDir = host.getContext().getCacheDir();
                                File out = new File(cacheDir, "hls_" + s.streamId + ".m3u8");
                                try (FileOutputStream fos = new FileOutputStream(out)) {
                                    fos.write(rewritten.getBytes("UTF-8"));
                                }
                                cachedPlaylist = out;
                                Log.i(TAG, "HLS playlist rewrite → " + out.getAbsolutePath()
                                        + " (" + rewritten.length() + " bytes)");
                                String fileUri = Uri.fromFile(out).toString();
                                DsAuth.mainHandler.post(() -> {
                                    if (stopped) return;
                                    Media media = new Media(host.getLibVLC(), Uri.parse(fileUri));
                                    cb.onReady(media, fileUri);
                                });
                            } catch (Exception e) {
                                Log.w(TAG, "m3u8 fetch/rewrite 실패", e);
                                DsAuth.mainHandler.post(() -> {
                                    if (!stopped) cb.onError("m3u8 준비 실패: " + e.getMessage());
                                });
                            }
                        });
                    }
                    @Override public void onError(String msg) {
                        if (stopped) return;
                        cb.onError("HLS 세션 실패: " + msg);
                    }
                });
            }
            @Override public void onError(String msg) {
                if (stopped) return;
                cb.onError("file_id 조회 실패: " + msg);
            }
        });
    }

    @Override
    void loadSubtitles(PlaybackHost host) {
        if (nasPath != null && !nasPath.isEmpty()) {
            host.loadNasSubtitles(nasPath);
        }
    }

    @Override
    void onStop() {
        stopped = true;
        if (streamId != null) {
            DsFileApiClient.closeTranscodeStream(streamId, format);
            streamId = null;
            format = null;
        }
        if (cachedPlaylist != null) {
            try { //noinspection ResultOfMethodCallIgnored
                cachedPlaylist.delete();
            } catch (Exception ignored) {}
            cachedPlaylist = null;
        }
    }
}
