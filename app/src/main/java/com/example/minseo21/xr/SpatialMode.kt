package com.example.minseo21.xr

/**
 * SurfaceEntity 의 Shape / Pose / SurfacePixelDimensions 를 결정하는 모드.
 *
 * Smoke test (2026-04-29) 로 VR180 hemisphere immersion 확인 후 design doc Step 1+2 로 도입 —
 * SBS_PANEL (기존 평면 SBS) 회귀 방지 + VR180 분기 가능하도록. 2026-04-30 검출 강화 (PR #10),
 * 2026-04-30 VR360_SPHERE 추가 (PR #12).
 */
enum class SpatialMode {
    /** XR 비대상. 일반 attachViews path. */
    NONE,

    /** Quad + SIDE_BY_SIDE — 일반 평면 SBS (기존 동작). */
    SBS_PANEL,

    /** Hemisphere + SIDE_BY_SIDE — VR180 immersion (앞 180°). */
    VR180_HEMISPHERE,

    /**
     * Sphere + SIDE_BY_SIDE — VR360 전방위 immersion. equi.proj_bounds 가 모두 0 인 콘텐츠
     * (= 트림 없는 full equirectangular). MP4 sv3d/proj/equi 박스 파싱이 명시적으로
     * 매칭됐을 때만 진입 — 파일명 / dimension heuristic 만으론 VR180 과 구분 불가하므로
     * default 는 VR180_HEMISPHERE.
     */
    VR360_SPHERE,
}
