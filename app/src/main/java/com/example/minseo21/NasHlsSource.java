package com.example.minseo21;

import android.net.Uri;

import org.videolan.libvlc.Media;

import java.util.List;

/**
 * 셀룰러/원격에서 NAS 파일을 HLS remux 로 재생.
 * prepare() 는 비동기 — findFileId → openTranscodeStream → Media 생성.
 * onStop() 으로 세션 정리; 이후 들어오는 콜백은 stopped 플래그로 무시되고
 * 레이스 세션이 열렸다면 즉시 닫는다.
 */
final class NasHlsSource extends PlaybackSource {
    private String streamId;
    private String format;
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
                            // 이미 중단됨 → 방금 연 세션 즉시 닫기 (누수 방지)
                            DsFileApiClient.closeTranscodeStream(s.streamId, s.format);
                            return;
                        }
                        streamId = s.streamId;
                        format = s.format;
                        Media media = new Media(host.getLibVLC(), Uri.parse(s.hlsUrl));
                        cb.onReady(media, s.hlsUrl);
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
    }
}
