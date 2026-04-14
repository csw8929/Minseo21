package com.example.minseo21;

import java.util.List;

/**
 * Activity 간 플레이리스트 전달용 static 홀더.
 * 필드 자체는 기존 코드와의 호환을 위해 public 유지.
 * compound 접근(size check + get)은 아래 원자 메서드를 사용해야 OOB 경합을 피할 수 있다.
 */
public class PlaylistHolder {
    public static volatile List<VideoItem> playlist = null;
    public static volatile int currentIndex = -1;

    /** 원자적으로 현재 항목 스냅샷을 반환. 플레이리스트가 null/비었거나 인덱스 OOB면 null. */
    public static synchronized VideoItem currentItem() {
        List<VideoItem> p = playlist;
        int i = currentIndex;
        if (p == null) return null;
        if (i < 0 || i >= p.size()) return null;
        return p.get(i);
    }

    /** 원자적으로 지정 인덱스 항목 반환. OOB면 null. */
    public static synchronized VideoItem itemAt(int i) {
        List<VideoItem> p = playlist;
        if (p == null) return null;
        if (i < 0 || i >= p.size()) return null;
        return p.get(i);
    }

    public static synchronized int size() {
        List<VideoItem> p = playlist;
        return p == null ? 0 : p.size();
    }

    /** 플레이리스트 + 인덱스 원자 세팅. */
    public static synchronized void set(List<VideoItem> list, int index) {
        playlist = list;
        currentIndex = index;
    }
}
