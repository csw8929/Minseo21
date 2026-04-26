package com.example.minseo21.xr

import android.content.pm.PackageManager
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.IntSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SurfaceEntity

/**
 * XR 관련 magic value · 단말 검출 logic 통합.
 *
 * **여기에만 미세조정·실험을 한다 — 코드 군데군데 흩어진 상수 금지.**
 *
 * 2026-04-26 도입: SCREEN_POSE/SHAPE, fade 시간, SBS 검출 임계, surface 픽셀 buffer,
 * 색강화 값을 한 곳에 모음. 향후 사용자 prefs UI(TODO-XR-8) 의 시작점.
 *
 * Kotlin object — Java 에서도 `XrConfig.FOO` 형태로 직접 접근 (`@JvmField` / `const`).
 */
object XrConfig {

    // ── XR 단말 검출 ──────────────────────────────────────────────────
    /**
     * Galaxy XR(SM-I610)은 type.xr 대신 spatial / input.controller 노출.
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
     * SBS surface 픽셀 buffer 크기. 디코더 출력 frame 크기.
     * panel native(3552×3840 / eye)까지 끌어올림은 별 라운드(TODO-XR-7).
     */
    @JvmField
    val SURFACE_PIXEL_DIM: IntSize2d = IntSize2d(1920, 1080)

    // ── 일반 2D path: Activity mainPanelEntity 시작 크기 ────────────
    /**
     * 일반 2D 영상이 그려지는 Activity main panel 의 시작 크기.
     * Galaxy XR system default(약 0.89×0.56m) 의 약 1.5배 — 자연스러우면서 시야 충분.
     * SBS 의 [SCREEN_SHAPE](6.4×3.6m, 별 SurfaceEntity Quad)와 다른 spatial 인지 거리 때문에
     * 같은 값을 쓰면 mainPanel 은 너무 크게 느껴짐.
     * mainPanelEntity 에 Movable/Resizable 부착돼 사용자가 손으로 변경 가능 — system 이 size 자체 처리.
     */
    @JvmField
    val PANEL_INITIAL_SIZE: FloatSize2d = FloatSize2d(1.75f, 1.0625f)

    // ── SBS 검출 임계 ─────────────────────────────────────────────────
    /** 가로 ÷ 세로 ≥ 이 값이면 비율 기반 SBS 로 판정. */
    const val SBS_RATIO_THRESHOLD: Float = 3.5f

    /** 파일명 SBS 키워드 — 소문자 기준 contains 검사. */
    @JvmField
    val SBS_FILENAME_KEYWORDS: Array<String> = arrayOf(
        "_sbs", "_3d", ".sbs.", "[sbs]", "[3d]",
    )

    // ── 시네마룸 (passthrough fade) ───────────────────────────────────
    /** passthrough opacity fade 지속 시간 (ms). */
    const val PASSTHROUGH_FADE_MS: Long = 400L

    // ── 색강화 (libVLC --video-filter=adjust) ────────────────────────
    /**
     * libVLC `--video-filter=adjust` 모듈에 전달할 saturation/contrast 값.
     * 1.0 = 원본. OLEDoS DCI-P3 95% + 명암비 무한대 활용용 미세 강화.
     *
     * 2026-04-26 실기기 검증: 2.0/1.4 는 부담스러움 → 1.15/1.05 로 정착.
     * String 으로 두는 이유: Locale 의존성(`,` vs `.`) 회피 + libVLC option 직결.
     */
    const val COLOR_SATURATION: String = "1.15"
    const val COLOR_CONTRAST: String = "1.05"
}
