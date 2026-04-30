package com.example.minseo21.xr

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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
     * 파일명만으로 SpatialMode 결정 (legacy / fallback path).
     *
     * 우선순위:
     * 1. VR180 keyword (vr180 / [vr] / [180] / _180_ 등) → VR180_HEMISPHERE
     *    — SBS keyword 없어도 매핑됨 (사용자 요청 2026-04-29).
     * 2. SBS keyword (_sbs / _3d / [sbs] 등) → SBS_PANEL
     * 3. 둘 다 없음 → NONE (XR 분기 진입 X, MainActivity 일반 path)
     *
     * Uri 가 있으면 [detectSpatialMode] (Context, Uri, String) overload 를 우선 사용 — MP4 sv3d /
     * st3d 박스 metadata 파싱이 파일명 heuristic 보다 정밀하다. 이 시그니처는 파일명만 알 때 호출.
     */
    @JvmStatic
    fun detectSpatialMode(name: String?): SpatialMode {
        if (name.isNullOrEmpty()) return SpatialMode.NONE
        if (vr180PatternMatch(name)) return SpatialMode.VR180_HEMISPHERE
        if (sbsPatternMatch(name)) return SpatialMode.SBS_PANEL
        return SpatialMode.NONE
    }

    /**
     * Uri 기반 SpatialMode 결정 — 3-layer 우선순위.
     *
     * 1. **MP4 metadata 파싱** ([SpatialMediaParser.parse]) — content:// / file:// 의 moov
     *    영역에서 sv3d / st3d 마커 검색. sv3d → VR180_HEMISPHERE, st3d → SBS_PANEL.
     * 2. **파일명 heuristic** ([detectSpatialMode] (String)) — `[vr]` / `_180_` / `_sbs` 등.
     * 3. **해상도 + 비율 heuristic** ([detectByDimension]) — FHD 이상 + 비율 분석.
     *    - 2:1 (±5%) → VR180_HEMISPHERE (4K VR180 SBS 의 2048×2048 per eye 형태)
     *    - 1:1 (±5%) → VR180_HEMISPHERE (mono full-equirect / VR360 근사)
     *    - 3:1 이상 → SBS_PANEL (Full-SBS 평면 3D)
     *    - 그 외 (16:9 등) → NONE (일반 영상)
     *
     * Layer 3 도입 이유 — 파일명·metadata 가 모두 비어 있는 사용자 콘텐츠도 해상도 형태가
     * VR 콘텐츠의 그것과 일치하면 자동 매핑 (사용자 요청 2026-04-30). 일반 4K 영화
     * (3840×2160 = 16:9) 는 NONE 으로 안전하게 회피.
     *
     * UI 스레드에서 호출 가능. 로컬 파일 기준 metadata 1MB read + dimension probe 합쳐
     * 수십~100ms 수준.
     */
    @JvmStatic
    fun detectSpatialMode(context: Context, uri: Uri?, name: String?): SpatialMode {
        val byMetadata = SpatialMediaParser.parse(context, uri)
        if (byMetadata != SpatialMode.NONE) {
            Log.i(
                "SACH_XR",
                "detectSpatialMode: metadata 우선 → $byMetadata (uri=$uri name=$name)"
            )
            return byMetadata
        }
        val byName = detectSpatialMode(name)
        if (byName != SpatialMode.NONE) {
            Log.i("SACH_XR", "detectSpatialMode: 파일명 → $byName (name=$name)")
            return byName
        }
        val byDim = detectByDimension(context, uri)
        if (byDim != SpatialMode.NONE) {
            Log.i("SACH_XR", "detectSpatialMode: 해상도/비율 → $byDim (name=$name)")
        }
        return byDim
    }

    // ── 해상도/비율 heuristic ─────────────────────────────────────────

    /** dimension heuristic 활성 최소 해상도 (FullHD). 미만은 일반 영상으로 간주. */
    private const val MIN_VR_PIXEL_WIDTH: Int = 1920
    private const val MIN_VR_PIXEL_HEIGHT: Int = 1080

    /**
     * 비율 매칭 허용 오차 — ±0.5%. 정확한 2:1 / 1:1 콘텐츠만 통과.
     *
     * VR180 SBS / VR360 콘텐츠는 codec 출력이 표준 정수 해상도 (4096×2048, 5120×2560,
     * 4096×4096 등) 라 ratio 가 정확히 2.0 / 1.0 으로 떨어진다. 일반 영화는 letterbox
     * 크롭으로 ratio 가 1.85 / 2.006 / 2.39 등 비정수 — 정확한 2.0 매치는 거의 없다.
     *
     * 2026-04-30 — 초기 0.05 (±5%) 는 letterbox 크롭된 4K 영화 (3836×1912 = 2.006) 가
     * 잘못 매치되어 VR180_HEMISPHERE 로 오분류 → 좁힘.
     */
    private const val RATIO_TOLERANCE: Float = 0.005f

    /**
     * 영상 해상도 + 비율로 SpatialMode 추정 (metadata / 파일명 모두 매칭 안 될 때 fallback).
     *
     * content:// / file:// scheme 만 처리. http(s) 는 즉시 NONE (NAS 부하 회피).
     * MediaMetadataRetriever probe ~50ms 수준. 분류 자체는 [detectByRatio] 가 담당.
     */
    private fun detectByDimension(context: Context, uri: Uri?): SpatialMode {
        if (uri == null) return SpatialMode.NONE
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return SpatialMode.NONE

        val (w, h) = probeVideoDimensions(context, uri) ?: return SpatialMode.NONE
        val mode = detectByRatio(w, h)
        Log.d("SACH_XR", "detectByDimension: ${w}x${h} → $mode")
        return mode
    }

    /**
     * 영상 frame 의 해상도/비율로 SpatialMode 추정 — pure function (단위 테스트 대상).
     *
     * - 정확한 2:1 (4096×2048 등, ±0.5%) → VR180_HEMISPHERE
     * - 정확한 1:1 (4096×4096 등, ±0.5%) → VR180_HEMISPHERE (VR360 mono 근사)
     * - 3:1 이상 (3840×1080 등) → SBS_PANEL
     * - FHD 미만 / 16:9 / 시네마스코프 / 그 외 → NONE
     */
    @JvmStatic
    fun detectByRatio(videoW: Int, videoH: Int): SpatialMode {
        if (videoW < MIN_VR_PIXEL_WIDTH || videoH < MIN_VR_PIXEL_HEIGHT) return SpatialMode.NONE
        val ratio = videoW.toFloat() / videoH
        return when {
            kotlin.math.abs(ratio - 2.0f) <= RATIO_TOLERANCE -> SpatialMode.VR180_HEMISPHERE
            kotlin.math.abs(ratio - 1.0f) <= RATIO_TOLERANCE -> SpatialMode.VR180_HEMISPHERE
            ratio >= 3.0f -> SpatialMode.SBS_PANEL
            else -> SpatialMode.NONE
        }
    }

    /** MediaMetadataRetriever 로 video frame width/height probe. 실패 시 null. */
    private fun probeVideoDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) null else w to h
        } catch (e: Exception) {
            Log.d("SACH_XR", "probeVideoDimensions 실패: $e")
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
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

    // VR360 은 origin pose / Sphere(40m) — VR180 hemisphere 와 같은 거리감으로 통일.
    // VR360 의 표준 frame 은 mono 4K 4096×2048 / SBS 4K 4096×4096 — codec 출력에 동적 매칭.
    private val VR360_POSE: Pose = VR180_POSE
    private val VR360_SHAPE: SurfaceEntity.Shape = SurfaceEntity.Shape.Sphere(40.0f)
    private val VR360_SURFACE_DIM: IntSize2d = IntSize2d(4096, 2048)

    @JvmStatic
    fun screenPose(mode: SpatialMode): Pose = when (mode) {
        SpatialMode.VR180_HEMISPHERE -> VR180_POSE
        SpatialMode.VR360_SPHERE -> VR360_POSE
        SpatialMode.SBS_PANEL, SpatialMode.NONE -> SBS_PANEL_POSE
    }

    @JvmStatic
    fun screenShape(mode: SpatialMode): SurfaceEntity.Shape = when (mode) {
        SpatialMode.VR180_HEMISPHERE -> VR180_SHAPE
        SpatialMode.VR360_SPHERE -> VR360_SHAPE
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
            SpatialMode.VR360_SPHERE -> VR360_SURFACE_DIM
            SpatialMode.SBS_PANEL, SpatialMode.NONE -> SBS_PANEL_SURFACE_DIM
        }
    }
}
