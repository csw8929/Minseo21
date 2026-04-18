package com.example.minseo21;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;

/** PlaybackSource 가 재생 준비/자막 로드 시 호출하는 Activity 측 엔트리. */
interface PlaybackHost {
    Context getContext();
    LibVLC getLibVLC();
    MediaPlayer getMediaPlayer();
    /** NAS 자막 자동 탐색 (PlaylistHolder 기반). */
    void loadNasSubtitles(String nasPath);
    /** 로컬 content:// 자막 자동 탐색 (MediaStore bucket scan). */
    void scanLocalSubtitles(Uri videoUri);
}
