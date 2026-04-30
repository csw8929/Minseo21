@file:OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
package com.example.minseo21.xr

import android.app.Activity
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.ResizableComponent
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import org.videolan.libvlc.MediaPlayer

/**
 * SBS / VR180 양안 렌더링 컨트롤러.
 *
 * **결정적 4가지 조합** (메모리 `project_xr_sbs_solution.md` 2026-04-25):
 * 1. Bundle launch (호출자가 [XrFullSpaceLauncher] 로 처리)
 * 2. 매니페스트 `XR_ACTIVITY_START_MODE_Full_Space_Activity` (SbsPlayerActivity 에 부착)
 * 3. `MediaBlendingMode.OPAQUE` 명시 — default(TRANSPARENT)면 비디오 투명 렌더 → 검은 화면
 * 4. `setSurfacePixelDimensions` 명시 — 누락 시 frame mismatch
 *
 * 2026-04-29 update — VR180 hemisphere immersion smoke test 통과 후 SpatialMode 분기 도입:
 * - SBS_PANEL: Quad + Movable/Resizable + applyVideoAspect (기존 동작)
 * - VR180_HEMISPHERE: Hemisphere + Resizable / aspect 적용 skip (모양 고정)
 *
 * 비-XR 단말에서는 모든 메서드 no-op.
 */
class XrSurfaceController(private val activity: Activity) {

    companion object {
        private const val TAG = "SACH_XR"
    }

    val isXrDevice: Boolean = XrConfig.isXrDevice(activity.packageManager).also { result ->
        Log.i(TAG, "[isXrDevice] $result")
    }

    private var session: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var currentResizable: ResizableComponent? = null
    private var currentMode: SpatialMode = SpatialMode.NONE

    // ── 초기화 / 해제 ─────────────────────────────────────────────────

    fun init() {
        if (!isXrDevice) return
        try {
            val result = Session.create(activity)
            if (result is SessionCreateSuccess) {
                session = result.session
                Log.i(TAG, "XR Session 생성 완료")
            } else {
                Log.w(TAG, "XR Session 생성 실패: $result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "XR Session 생성 예외: $e")
        }
    }

    fun release() {
        try { surfaceEntity?.dispose() } catch (_: Exception) {}
        surfaceEntity = null
        currentResizable = null
        currentMode = SpatialMode.NONE
        session = null
    }

    // ── libVLC → SurfaceEntity 연결 ──────────────────────────────────

    /**
     * libVLC 출력을 XR StereoSurface 로 교체.
     * 호출자는 mediaPlayer 생성 직후, attachViews() 호출 전에 실행해야 한다.
     *
     * @param mode SBS_PANEL = 평면 Quad / VR180_HEMISPHERE = hemisphere immersion. NONE 은 false 반환.
     * @param videoW / videoH 사전 probe 한 영상 frame 크기 (MediaMetadataRetriever).
     *                        0 이하면 mode 별 default 적용.
     * @return true = 연결 성공 → 호출자는 attachViews(videoLayout) 를 생략.
     *         false = 실패 또는 비-XR 또는 mode=NONE → 호출자가 기존 attachViews 그대로 사용.
     */
    fun setupStereoSurface(
        mediaPlayer: MediaPlayer,
        mode: SpatialMode,
        videoW: Int = 0,
        videoH: Int = 0,
    ): Boolean {
        if (mode == SpatialMode.NONE) return false
        val s = session ?: return false
        return try {
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) vout.detachViews()
            surfaceEntity?.dispose()
            currentResizable = null
            currentMode = mode
            surfaceEntity = SurfaceEntity.create(
                s,
                XrConfig.screenPose(mode),
                XrConfig.screenShape(mode),
                SurfaceEntity.StereoMode.SIDE_BY_SIDE,
            )
            // parent 를 activitySpace 로 명시 — 안 하면 default parent 가 mainPanelEntity 라
            // mainPanel size shrink(0.01×0.01) 시 SurfaceEntity 도 함께 scale down 되어 화면이
            // 극도로 작아진다(2026-04-26 실기기 검증). activitySpace 에 직접 붙이면 mainPanel
            // 영향 0.
            try {
                surfaceEntity?.parent = s.scene.activitySpace
            } catch (e: Exception) {
                Log.w(TAG, "SurfaceEntity parent=activitySpace 설정 실패: $e")
            }
            // MediaBlendingMode 를 OPAQUE 로 명시 — default(TRANSPARENT)면 비디오 투명 렌더.
            try {
                surfaceEntity?.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            } catch (e: Exception) {
                Log.w(TAG, "mediaBlendingMode 설정 실패: $e")
            }
            // Surface 픽셀 크기 — codec 출력 frame 과 정확히 일치해야 한다 (smoke test 2026-04-29 검증).
            // 더 크게 잡으면 codec 가 빈 영역 포함된 surface 에 native frame 만 좌상단에 쓰고
            // hemisphere/Quad 가 빈 영역까지 매핑 → 영상 작아지고 위치 어긋남.
            // videoW/H 사전 probe 값 우선, 0 이면 mode 별 default fallback.
            val pixDim = XrConfig.surfacePixelDim(mode, videoW, videoH)
            Log.i(TAG, "surfacePixelDim → ${pixDim.width}x${pixDim.height} (probe ${videoW}x${videoH}, mode=$mode)")
            try {
                surfaceEntity?.setSurfacePixelDimensions(pixDim)
            } catch (e: Exception) {
                Log.w(TAG, "setSurfacePixelDimensions 실패: $e")
            }
            val xrSurface = surfaceEntity?.getSurface()
                ?: run { Log.w(TAG, "SurfaceEntity.getSurface() null"); return false }

            vout.setVideoSurface(xrSurface, null)
            vout.attachViews()
            Log.i(TAG, "XR StereoSurface 연결 완료 (mode=$mode)")

            // VR180 hemisphere / VR360 sphere 는 모양 고정 — Movable/Resizable 안 붙임
            // (resize callback 이 Shape.Quad 로 덮어써 hemisphere/sphere 깨짐).
            if (mode == SpatialMode.SBS_PANEL) {
                attachInteraction(s)
            }

            // Full Space 명시 요청 — Bundle launch 가 trigger 이지만 SDK alpha13 가 spatial state
            // 를 fully Full Space 로 전환 안 했을 가능성에 대비한 안전망.
            try {
                s.scene.requestFullSpaceMode()
                Log.i(TAG, "requestFullSpaceMode() 안전망 호출")
            } catch (e: Exception) {
                Log.w(TAG, "requestFullSpaceMode 실패: $e")
            }
            // alpha = 1.0 명시 — alpha13 default 가 무효화될 수 있어 검은 화면 회귀 방지.
            try {
                surfaceEntity?.setAlpha(1.0f)
            } catch (e: Exception) {
                Log.w(TAG, "SurfaceEntity setAlpha 실패: $e")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "XR StereoSurface 연결 실패: $e")
            surfaceEntity = null
            currentMode = SpatialMode.NONE
            false
        }
    }

    // ── 사용자 인터랙션 ──────────────────────────────────────────────

    /**
     * SurfaceEntity(영상 Quad) 에 Movable + Resizable 부착.
     * 사용자가 헤드셋 컨트롤러로 영상 창을 잡아 옮기고 크기 변경 가능.
     * 비율 잠금은 [applyVideoAspect] 가 실제 영상 비율로 Quad 를 맞춘 다음 활성화한다.
     *
     * SBS_PANEL 전용 — VR180_HEMISPHERE 에선 호출하지 않는다.
     */
    private fun attachInteraction(s: Session) {
        val ent = surfaceEntity ?: return
        try {
            // scaleInZ=false: 클릭/이동 시 SDK 가 Z 거리에 따라 자동 스케일하지 않음.
            // true 면 헤드셋 포인터 클릭 시 미세 Z 변화로 Quad 가 폭발적으로 커지는 현상 발생.
            val movable = MovableComponent.createSystemMovable(s, /* scaleInZ = */ false)
            ent.addComponent(movable)
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceEntity Movable 실패: $e")
        }
        try {
            val resizable = ResizableComponent.create(s) { event: ResizeEvent ->
                val ns = event.newSize
                try {
                    ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(ns.width, ns.height))
                } catch (e: Exception) {
                    Log.w(TAG, "resize Shape 갱신 실패: $e")
                }
            }
            ent.addComponent(resizable)
            currentResizable = resizable
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceEntity Resizable 실패: $e")
        }
    }

    /**
     * 영상 트랙 정보 로딩 후 호출 — 실제 비율로 Quad shape 갱신 + SDK 비율 잠금 활성화.
     *
     * SBS 인코딩 두 가지를 자동 구분:
     * - Full-SBS (예 3840×1080, 프레임 비율 ≥ 3.0): 한 눈 = width/2 × height (= 16:9)
     * - Half-SBS (예 1920×1080 인데 SBS 로 식별, 프레임 비율 < 3.0): 한 눈 = width × height (= 16:9)
     *
     * Quad 는 height(default 3.6m) 유지, width 를 새 비율로 늘린다.
     * 호출 후 `isFixedAspectRatioEnabled` 활성화 — 사용자 리사이즈 시 비율 유지.
     *
     * VR180_HEMISPHERE / VR360_SPHERE / NONE / 비-XR 단말 / SurfaceEntity 미존재 시 no-op.
     */
    fun applyVideoAspect(videoW: Int, videoH: Int, isSbs: Boolean) {
        if (!isXrDevice) return
        if (currentMode != SpatialMode.SBS_PANEL) return
        if (videoW <= 0 || videoH <= 0) return
        val ent = surfaceEntity ?: return
        val frameAspect = videoW.toFloat() / videoH
        val fullSbs = isSbs && frameAspect >= 3.0f
        val perEyeAspect = if (fullSbs) frameAspect / 2f else frameAspect
        try {
            val current = ent.shape as? SurfaceEntity.Shape.Quad ?: return
            val h = current.extents.height
            val newW = h * perEyeAspect
            ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(newW, h))
            currentResizable?.let { rc ->
                try {
                    rc.isFixedAspectRatioEnabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "isFixedAspectRatioEnabled 설정 실패: $e")
                }
            }
            Log.i(
                TAG,
                "applyVideoAspect: ${videoW}x${videoH} sbs=$isSbs(full=$fullSbs) → quad ${newW}x${h} aspect=${perEyeAspect}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "applyVideoAspect 실패: $e")
        }
    }
}
