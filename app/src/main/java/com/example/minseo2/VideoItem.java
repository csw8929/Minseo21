package com.example.minseo2;

import android.net.Uri;

/** 파일 목록의 항목 (폴더 또는 동영상) */
public class VideoItem {

    public static final int TYPE_FOLDER = 0;
    public static final int TYPE_VIDEO  = 1;

    public final int    type;
    public final String name;
    public final String bucketId;    // 로컬: MediaStore bucket ID / NAS 폴더: NAS 경로
    public final Uri    uri;         // 로컬: content URI / NAS(스트림): HTTP URL / NAS(어댑터): null
    public final long   size;
    public final long   dateModified;
    public final String canonicalUri; // NAS 파일 전용: SID 없는 URL (Room DB 키). 로컬은 null.
    public final String nasPath;      // NAS 파일 전용: /video/폴더/파일.mkv (SID 재발급 시 재사용)

    private VideoItem(int type, String name, String bucketId, Uri uri,
                      long size, long dateModified, String canonicalUri, String nasPath) {
        this.type         = type;
        this.name         = name;
        this.bucketId     = bucketId;
        this.uri          = uri;
        this.size         = size;
        this.dateModified = dateModified;
        this.canonicalUri = canonicalUri;
        this.nasPath      = nasPath;
    }

    // ── 로컬 파일 팩토리 (기존 — 변경 없음) ────────────────────────────────────

    public static VideoItem folder(String name, String bucketId) {
        return new VideoItem(TYPE_FOLDER, name, bucketId, null, 0, 0, null, null);
    }

    public static VideoItem video(String name, String bucketId, Uri uri, long size, long dateModified) {
        return new VideoItem(TYPE_VIDEO, name, bucketId, uri, size, dateModified, null, null);
    }

    // ── NAS 팩토리 ──────────────────────────────────────────────────────────────

    /** NAS 폴더 항목 (어댑터 표시용). bucketId = NAS 경로. */
    public static VideoItem nasFolder(String name, String folderPath) {
        return new VideoItem(TYPE_FOLDER, name, folderPath, null, 0, 0, null, null);
    }

    /**
     * NAS 파일 항목 (어댑터 저장용). uri = null — 재생 시점에 스트림 URL 생성.
     * nasPath: /video/folder/file.mkv
     */
    public static VideoItem nasFile(String name, String nasPath,
                                    long size, long dateModified, String canonicalUrl) {
        return new VideoItem(TYPE_VIDEO, name, null, null, size, dateModified, canonicalUrl, nasPath);
    }

    /**
     * NAS 파일 플레이리스트 항목 (재생 직전 생성). uri = HTTP 스트림 URL (SID 포함).
     * nasPath 보존 → SID 만료 시 URL 재발급 가능.
     */
    public static VideoItem nasFileWithStream(String name, String nasPath,
                                              String streamUrl, String canonicalUrl) {
        return new VideoItem(TYPE_VIDEO, name, null, Uri.parse(streamUrl), 0, 0, canonicalUrl, nasPath);
    }
}
