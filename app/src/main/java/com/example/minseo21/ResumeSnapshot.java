package com.example.minseo21;

/**
 * 즐겨찾기 "빨간 별표" LAST / REMOTE 항목의 스냅샷.
 *
 * 의도:
 *   - local (LAST):   현 단말 Room DB의 "마지막 로컬 재생" 1개.
 *                     프로세스 시작 시 1회 캡처 → 세션 중 변하지 않음.
 *   - nas   (REMOTE): NAS positions.json에서 "이 단말이 아닌 다른 단말"이 올린 최신 항목 1개.
 *                     nasPath 여부 무관 — 다른 단말의 로컬 재생 기록도 포함 (파일명이 이 단말
 *                     MediaStore에 있으면 로컬 재생 fallback). 즐겨찾기 탭 진입마다
 *                     NasSyncManager.forceRefresh로 갱신.
 *   - onUpdate: 비동기 fetch 완료 시 UI 재렌더 훅 (메인 스레드).
 *
 * 필드명(local/nas)은 하위 호환 위해 유지. 향후 리팩토링에서 last/remote로 개명 예정.
 */
public class ResumeSnapshot {

    /** LAST 슬롯: 이 단말 Room DB의 로컬 파일(uri NOT LIKE 'http%') 최신 항목. null = 미캡처 or 없음. */
    public static volatile Favorite local;

    /**
     * REMOTE 슬롯: positions.json에서 타 단말이 올린 최신 항목 1개. null = 미캡처 or 해당 항목 없음.
     */
    public static volatile Favorite nas;

    /**
     * 비동기 캡처 완료 시 UI 갱신 훅.
     *   - 즐겨찾기 탭 진입 시 FileListActivity가 set
     *   - 탭 이탈/Activity 소멸 시 null
     *   - 항상 메인 스레드에서 호출된다
     */
    public static volatile Runnable onUpdate;
}
