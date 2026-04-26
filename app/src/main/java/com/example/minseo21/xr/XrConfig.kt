package com.example.minseo21.xr

import android.content.pm.PackageManager
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SurfaceEntity

/**
 * XR 관련 magic value · 단말 검출 / SBS 검출 logic 통합.
 *
 * **여기에만 미세조정·실험을 한다 — 코드 군데군데 흩어진 상수 금지.**
 *
 * 2026-04-26 baseline restart 정합 slim 버전:
 * 시네마룸 fade / mainPanel size / 색강화 값 등은 SbsPlayerActivity 분리로 불필요해져 제거.
 * 색강화는 MainActivity 가 universal 로 직접 처리(saturation=1.15 / contrast=1.05).
 *
 * Kotlin object — Java 에서 `XrConfig.FOO` 로 직접 접근 가능 (`@JvmField` / `const`).
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

    // ── SBS 검출 ─────────────────────────────────────────────────────
    /** 파일명 SBS 키워드 — 소문자 기준 contains 검사. */
    @JvmField
    val SBS_FILENAME_KEYWORDS: Array<String> = arrayOf(
        "_sbs", "_3d", ".sbs.", "[sbs]", "[3d]",
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

    // ── SBS path: SurfaceEntity Quad 시작 위치/크기 ──────────────────
    /** 화면 시작 위치 — 사용자 앞 3m, 살짝 위(y=0.3m). */
    @JvmField
    val SCREEN_POSE: Pose = Pose(Vector3(0f, 0.3f, -3.0f))

    /**
     * 화면 시작 크기. 6.4 × 3.6m = 16:9, 면적 약 100인치 TV 급.
     * 사용자는 ResizableComponent 로 손으로 변경 가능.
     */
    @JvmField
    val SCREEN_SHAPE: SurfaceEntity.Shape =
        SurfaceEntity.Shape.Quad(FloatSize2d(6.4f, 3.6f))

    /**
     * SBS surface 픽셀 buffer 크기 — 디코더 출력 frame 크기.
     * 누락 시 frame mismatch 로 검은 화면 발생(메모리: 결정적 4가지 조합 중 하나).
     */
    @JvmField
    val SURFACE_PIXEL_DIM: IntSize2d = IntSize2d(1920, 1080)
}
