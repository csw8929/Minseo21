package com.example.minseo21;

/**
 * 즐겨찾기 "빨간 별표" LOCAL / NAS 항목의 프로세스 스코프 스냅샷.
 *
 * 의도:
 *   - 앱(프로세스) 시작 시점의 "최신 재생 이력"을 고정 보관
 *   - 세션 중 재생해도 여기는 변하지 않음 ("시작 당시" 정보 유지)
 *   - 프로세스 재생성 시 static 초기화 → 새로 캡처됨
 *
 * Activity 재생성(회전, 메모리 압박)에는 영향 없음.
 */
public class ResumeSnapshot {

    /** 이 단말 Room DB의 로컬 파일(uri NOT LIKE 'http%') 최신 항목. null = 미캡처 or 없음. */
    public static volatile Favorite local;

    /**
     * NAS positions.json 기반 "이 단말에서 재생 가능한 최신 항목".
     *   - 이 단말 deviceId 항목 (LOCAL/NAS 무관)
     *   - 다른 단말 deviceId이지만 nasPath 존재 (스트리밍 가능)
     * 다른 단말의 로컬 파일은 제외. null = 미캡처 or 없음.
     */
    public static volatile Favorite nas;

    /**
     * NAS 비동기 캡처 완료 시 UI 갱신 훅.
     *   - 즐겨찾기 탭 진입 시 FileListActivity가 set
     *   - 탭 이탈/Activity 소멸 시 null
     *   - 항상 메인 스레드에서 호출된다
     */
    public static volatile Runnable onUpdate;
}
