package com.example.minseo21.xr

/**
 * SurfaceEntity 의 Shape / Pose / SurfacePixelDimensions 를 결정하는 모드.
 *
 * Smoke test (2026-04-29) 결과 VR180 hemisphere immersion 확인 후 design doc Step 1+2 로 도입 —
 * SBS_PANEL (기존 평면 SBS) 회귀 방지 + VR180 분기 가능하도록.
 *
 * 향후 확장 자리:
 * - MONO_360_SPHERE — 360° 모노
 * - TOP_BOTTOM_360_SPHERE — 360° top-bottom 스테레오
 */
enum class SpatialMode {
    /** XR 비대상. 일반 attachViews path. */
    NONE,

    /** Quad + SIDE_BY_SIDE — 일반 평면 SBS (기존 동작). */
    SBS_PANEL,

    /** Hemisphere + SIDE_BY_SIDE — VR180 immersion. */
    VR180_HEMISPHERE,
}
