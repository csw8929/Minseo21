package com.example.minseo21;

import android.net.Uri;

import org.videolan.libvlc.Media;

import java.util.List;

/**
 * 재생 소스 추상화. 3가지 구현:
 * - {@link LocalFileSource} : content:// 또는 file:// 로컬 파일
 * - {@link NasDirectSource} : NAS HTTPS 직결 스트림
 * - {@link NasHlsSource}    : NAS HLS remux 트랜스코드 세션
 *
 * MainActivity 는 소스 종류를 몰라도 되고, 분기 로직 대신 가상 호출만 한다.
 */
abstract class PlaybackSource {
    /** DB 키 (canonical URL, stable across sessions) */
    final String canonicalKey;
    /** 크로스 디바이스 이어보기 키 (보통 파일명) */
    final String syncKey;
    /** NAS 파일 경로 (/video/foo.mkv). 로컬이면 null. */
    final String nasPath;

    protected PlaybackSource(String canonicalKey, String syncKey, String nasPath) {
        this.canonicalKey = canonicalKey;
        this.syncKey = syncKey;
        this.nasPath = nasPath;
    }

    /** libVLC 옵션을 추가한다. 공통 옵션은 호출부에서 먼저 추가됨. */
    abstract void addVlcOptions(List<String> options);

    /** 네트워크 재생이면 true (NAS 직결/HLS). */
    abstract boolean isNetwork();

    /**
     * Media 준비 후 콜백 호출. 동기(Local/NasDirect) 또는 비동기(NasHls) 가능.
     * onReady 의 playingUri 는 libVLC 가 실제로 재생할 URL — currentUriKey 업데이트용.
     */
    abstract void prepare(PlaybackHost host, MediaReadyCallback cb);

    /** 자막 자동 로드. 기본 no-op. */
    void loadSubtitles(PlaybackHost host) {}

    /** 중단 시 호출 (HLS 세션 close, pfd close 등). 재호출 안전. */
    void onStop() {}

    interface MediaReadyCallback {
        void onReady(Media media, String playingUri);
        void onError(String msg);
    }

    /** Intent/VideoItem/셀룰러 상태 보고 적절한 구현을 선택. */
    static PlaybackSource from(Uri videoUri, VideoItem item, String title, boolean useTranscode) {
        String canonicalKey = (item != null && item.canonicalUri != null)
                ? item.canonicalUri
                : DsFileApiClient.toCanonicalUrl(videoUri.toString());
        String nasPath = (item != null) ? item.nasPath : null;
        String syncKey = deriveSyncKey(item, title, videoUri);

        if (useTranscode && nasPath != null && !nasPath.isEmpty()) {
            return new NasHlsSource(canonicalKey, syncKey, nasPath);
        }
        String scheme = videoUri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return new NasDirectSource(videoUri, canonicalKey, syncKey, nasPath);
        }
        return new LocalFileSource(videoUri, canonicalKey, syncKey);
    }

    private static String deriveSyncKey(VideoItem item, String title, Uri uri) {
        if (item != null) {
            if (item.nasPath != null && !item.nasPath.isEmpty()) {
                String[] parts = item.nasPath.split("/");
                String name = parts[parts.length - 1];
                if (!name.isEmpty()) return name;
            }
            if (item.name != null && !item.name.isEmpty()) return item.name;
        }
        if (title != null && !title.isEmpty()) return title;
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "";
    }
}
