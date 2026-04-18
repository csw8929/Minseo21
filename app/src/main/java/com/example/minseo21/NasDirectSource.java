package com.example.minseo21;

import android.net.Uri;

import org.videolan.libvlc.Media;

import java.util.List;

final class NasDirectSource extends PlaybackSource {
    private final Uri uri;

    NasDirectSource(Uri uri, String canonicalKey, String syncKey, String nasPath) {
        super(canonicalKey, syncKey, nasPath);
        this.uri = uri;
    }

    @Override
    void addVlcOptions(List<String> options) {
        options.add("--network-caching=5000");
        options.add("--live-caching=300");
        // AVI 컨테이너 타이밍 부정확 → SurfaceTexture 슬롯 고갈 방지
        options.add("--clock-jitter=500");
    }

    @Override boolean isNetwork() { return true; }

    @Override
    void prepare(PlaybackHost host, MediaReadyCallback cb) {
        Media media = new Media(host.getLibVLC(), uri);
        cb.onReady(media, uri.toString());
    }

    @Override
    void loadSubtitles(PlaybackHost host) {
        if (nasPath != null && !nasPath.isEmpty()) {
            host.loadNasSubtitles(nasPath);
        }
    }
}
