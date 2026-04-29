package com.example.minseo21.xr

import android.content.pm.PackageManager
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SurfaceEntity

/**
 * XR 관련 magic value · 단말 검출 / SBS 검출 / SpatialMode 별 surface 파라미터 통합.
 *
 * **여기에만 미세조정·실험을 한다 — 코드 군데군데 흩어진 상수 금지.**
 *
 * 2026-04-29 update — design doc Step 1+2 (SpatialMode enum 도입 + Shape/Pose 분기):
 * 단일 SCREEN_POSE/SHAPE/PIXEL_DIM 대신 mode 별 helper 로 교체. SBS_PANEL 은 기존 동작 그대로,
 * VR180_HEMISPHERE 는 smoke test 검증치 (Hemisphere 30m radius / origin pose / 4K SBS dim).
 *
 * Kotlin object — Java 에서 `XrConfig.foo()` 로 직접 접근 가능 (`@JvmStatic`).
 */
object XrConfig {

    // ── XR 단말 검출 ──────────────────────────────────────────────────
    /**
     * Galaxy XR(SM-I610)은 type.xr 미지원 — spatial / input.controller 로 식별.
     * 셋 중 하나라도 매치되면 XR 단말로 판정.
     */
    @JvmField
    val DEVICE_FEATURES: Array<String> = arrayOf(
        "android.hardware.type.xr",
        "android.software.xr.api.spatial",
        "android.hardware.xr.input.controller",
    )

    @JvmStatic
    fun isXrDevice(pm: PackageManager): Boolean =
        DEVICE_FEATURES.any { pm.hasSystemFeature(it) }

    // ── SBS / VR180 파일명 검출 ─────────────────────────────────────
    /** 파일명 SBS 키워드 — 소문자 기준 contains 검사. */
    @JvmField
    val SBS_FILENAME_KEYWORDS: Array<String> = arrayOf(
        "_sbs", "_3d", ".sbs.", "[sbs]", "[3d]",
    )

    /**
     * VR180 파일명 키워드. SBS 매치된 후 추가로 이 중 하나라도 매치되면 VR180_HEMISPHERE.
     * TODO: 파일명 heuristic 외에 MP4 sv3d/equi box parsing 으로 정밀 검출.
     */
    @JvmField
    val VR180_FILENAME_KEYWORDS: Array<String> = arrayOf(
        "vr180", "vr_180", "vr-180", "[vr]", "[180]", "_180_",
    )

    /** 가로 ÷ 세로 ≥ 이 값이면 비율 기반 SBS 판정 (향후 폴백용 plumbing). */
    const val SBS_RATIO_THRESHOLD: Float = 3.5f

    /** 파일명/syncKey 로 SBS 판정 — 영상 트랙 정보 도착 전에 호출 가능. */
    @JvmStatic
    fun sbsPatternMatch(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return SBS_FILENAME_KEYWORDS.any { lower.contains(it) }
    }

    /** 가로/세로 비율 기반 SBS 보조 판정. */
    @JvmStatic
    fun sbsRatioMatch(videoW: Int, videoH: Int): Boolean {
        if (videoH <= 0) return false
        return videoW.toFloat() / videoH >= SBS_RATIO_THRESHOLD
    }

    /** 파일명에 VR180 키워드 포함 여부. */
    @JvmStatic
    fun vr180PatternMatch(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return VR180_FILENAME_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * 파일명 기반 SpatialMode 결정. FileListActivity 라우팅 + SbsPlayerActivity 진입 양쪽에서 사용.
     *
     * 우선순위:
     * 1. VR180 keyword (vr180 / [vr] / [180] / _180_ 등) → VR180_HEMISPHERE
     *    — SBS keyword 없어도 매핑됨 (사용자 요청 2026-04-29).
     * 2. SBS keyword (_sbs / _3d / [sbs] 등) → SBS_PANEL
     * 3. 둘 다 없음 → NONE (XR 분기 진입 X, MainActivity 일반 path)
     */
    @JvmStatic
    fun detectSpatialMode(name: String?): SpatialMode {
        if (name.isNullOrEmpty()) return SpatialMode.NONE
        if (vr180PatternMatch(name)) return SpatialMode.VR180_HEMISPHERE
        if (sbsPatternMatch(name)) return SpatialMode.SBS_PANEL
        return SpatialMode.NONE
    }

    // ── SpatialMode 별 SurfaceEntity 파라미터 ────────────────────────
    //
    // SBS_PANEL: 사용자 앞 3m, 살짝 위(y=0.3m), 6.4×3.6m Quad (≈100인치 TV 급).
    //            Surface 1920×1080 — 저해상도 SBS test 파일 기준.
    // VR180_HEMISPHERE: origin (사용자 머리 위치), Hemisphere 30m radius 안쪽 면.
    //                   Surface 4096×2048 — 4K SBS 콘텐츠 frame 과 매치.
    //
    // TODO: SURFACE_PIXEL_DIM 을 영상 실제 해상도에 동적 매칭 — 현재는 모드별 고정.
    //       4K SBS_PANEL 콘텐츠 또는 1080p VR180 콘텐츠 시 mismatch 가능.

    private val SBS_PANEL_POSE: Pose = Pose(Vector3(0f, 0.3f, -3.0f))
    private val SBS_PANEL_SHAPE: SurfaceEntity.Shape =
        SurfaceEntity.Shape.Quad(FloatSize2d(6.4f, 3.6f))
    private val SBS_PANEL_SURFACE_DIM: IntSize2d = IntSize2d(1920, 1080)

    private val VR180_POSE: Pose = Pose(Vector3(0f, 0f, 0f))
    private val VR180_SHAPE: SurfaceEntity.Shape = SurfaceEntity.Shape.Hemisphere(40.0f)
    private val VR180_SURFACE_DIM: IntSize2d = IntSize2d(4096, 2048)

    @JvmStatic
    fun screenPose(mode: SpatialMode): Pose = when (mode) {
        SpatialMode.VR180_HEMISPHERE -> VR180_POSE
        SpatialMode.SBS_PANEL, SpatialMode.NONE -> SBS_PANEL_POSE
    }

    @JvmStatic
    fun screenShape(mode: SpatialMode): SurfaceEntity.Shape = when (mode) {
        SpatialMode.VR180_HEMISPHERE -> VR180_SHAPE
        SpatialMode.SBS_PANEL, SpatialMode.NONE -> SBS_PANEL_SHAPE
    }

    /**
     * Surface pixel dim — codec 출력 frame 과 일치해야 한다 (smoke test 2026-04-29 검증).
     *
     * @param videoW / videoH 영상 실제 frame 크기. 양수면 그대로 사용 (정확).
     *                        0 이하면 mode 별 default (probe 실패시 fallback).
     */
    @JvmStatic
    fun surfacePixelDim(mode: SpatialMode, videoW: Int, videoH: Int): IntSize2d {
        if (videoW > 0 && videoH > 0) return IntSize2d(videoW, videoH)
        return when (mode) {
            SpatialMode.VR180_HEMISPHERE -> VR180_SURFACE_DIM
            SpatialMode.SBS_PANEL, SpatialMode.NONE -> SBS_PANEL_SURFACE_DIM
        }
    }
}
