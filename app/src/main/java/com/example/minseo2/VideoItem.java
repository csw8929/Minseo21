package com.example.minseo2;

import android.net.Uri;

/** 파일 목록의 항목 (폴더 또는 동영상) */
public class VideoItem {

    public static final int TYPE_FOLDER = 0;
    public static final int TYPE_VIDEO  = 1;

    public final int    type;
    public final String name;
    public final String bucketId;   // 폴더 진입용 (TYPE_FOLDER / TYPE_VIDEO 모두)
    public final Uri    uri;        // TYPE_VIDEO : content URI
    public final long   size;       // TYPE_VIDEO : 파일 크기

    private VideoItem(int type, String name, String bucketId, Uri uri, long size) {
        this.type     = type;
        this.name     = name;
        this.bucketId = bucketId;
        this.uri      = uri;
        this.size     = size;
    }

    public static VideoItem folder(String name, String bucketId) {
        return new VideoItem(TYPE_FOLDER, name, bucketId, null, 0);
    }

    public static VideoItem video(String name, String bucketId, Uri uri, long size) {
        return new VideoItem(TYPE_VIDEO, name, bucketId, uri, size);
    }
}
