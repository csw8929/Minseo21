package com.example.minseo21;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.videolan.libvlc.Media;

import java.util.List;

final class LocalFileSource extends PlaybackSource {
    private static final String TAG = "LocalFileSource";
    private final Uri uri;
    private ParcelFileDescriptor pfd;

    LocalFileSource(Uri uri, String canonicalKey, String syncKey) {
        super(canonicalKey, syncKey, null);
        this.uri = uri;
    }

    @Override
    void addVlcOptions(List<String> options) {
        options.add("--file-caching=500");
        options.add("--live-caching=300");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        // AVI time-stretch: 헤더 불량(0Hz)일 때 동기 파이프라인 스탈 방지
        options.add("--no-audio-time-stretch");
    }

    @Override boolean isNetwork() { return false; }

    @Override
    void prepare(PlaybackHost host, MediaReadyCallback cb) {
        Media media;
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            try {
                closePfd();
                ContentResolver cr = host.getContext().getContentResolver();
                pfd = cr.openFileDescriptor(uri, "r");
                if (pfd == null) { cb.onError("파일을 열 수 없습니다."); return; }
                media = new Media(host.getLibVLC(), pfd.getFileDescriptor());
            } catch (Exception e) {
                Log.e(TAG, "content:// open failed", e);
                closePfd();
                cb.onError("파일을 열 수 없습니다.");
                return;
            }
        } else {
            media = new Media(host.getLibVLC(), uri);
        }
        cb.onReady(media, uri.toString());
    }

    @Override
    void loadSubtitles(PlaybackHost host) {
        if ("content".equals(uri.getScheme())) {
            host.scanLocalSubtitles(uri);
        }
    }

    @Override
    void onStop() {
        closePfd();
    }

    private void closePfd() {
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }
    }
}
