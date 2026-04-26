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
 * SBS 양안 렌더링 전용 컨트롤러.
 *
 * **결정적 4가지 조합** (메모리 `project_xr_sbs_solution.md` 2026-04-25):
 * 1. Bundle launch (호출자가 [XrFullSpaceLauncher] 로 처리)
 * 2. 매니페스트 `XR_ACTIVITY_START_MODE_Full_Space_Activity` (SbsPlayerActivity 에 부착)
 * 3. `MediaBlendingMode.OPAQUE` 명시 — default(TRANSPARENT)면 비디오 투명 렌더 → 검은 화면
 * 4. `setSurfacePixelDimensions(1920, 1080)` 명시 — 누락 시 frame mismatch
 *
 * 이 클래스 책임: 위 4번 + Surface 생성/연결 + Movable/Resizable + 영상 비율 적용.
 *
 * 시네마룸 passthrough fade / mainPanel 작업 / Full↔Home 전환은 의도적으로 빠짐
 * (TODO-XR-2 보류 / SDK 한계 결론 — 2026-04-26 baseline restart 디자인 참조).
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
        session = null
    }

    // ── libVLC → SurfaceEntity 연결 ──────────────────────────────────

    /**
     * libVLC 출력을 XR StereoSurface 로 교체.
     * 호출자는 mediaPlayer 생성 직후, attachViews() 호출 전에 실행해야 한다.
     *
     * @return true = 연결 성공 → 호출자는 attachViews(videoLayout) 를 생략.
     *         false = 실패 또는 비-XR → 호출자가 기존 attachViews 그대로 사용.
     */
    fun setupStereoSurface(mediaPlayer: MediaPlayer): Boolean {
        val s = session ?: return false
        return try {
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) vout.detachViews()
            surfaceEntity?.dispose()
            currentResizable = null
            surfaceEntity = SurfaceEntity.create(
                s,
                XrConfig.SCREEN_POSE,
                XrConfig.SCREEN_SHAPE,
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
            // Surface 픽셀 크기를 libVLC 출력 frame 과 맞춤. 누락 시 검은 화면.
            try {
                surfaceEntity?.setSurfacePixelDimensions(XrConfig.SURFACE_PIXEL_DIM)
            } catch (e: Exception) {
                Log.w(TAG, "setSurfacePixelDimensions 실패: $e")
            }
            val xrSurface = surfaceEntity?.getSurface()
                ?: run { Log.w(TAG, "SurfaceEntity.getSurface() null"); return false }

            vout.setVideoSurface(xrSurface, null)
            vout.attachViews()
            Log.i(TAG, "XR StereoSurface 연결 완료")

            attachInteraction(s)

            // Full Space 명시 요청 — Bundle launch 가 trigger 이지만 SDK alpha13 가 spatial state
            // 를 fully Full Space 로 전환 안 했을 가능성에 대비한 안전망.
            // (WIP enterCinemaRoom() 핵심 — 시네마룸 fade 만 제외하고 spatial 안전망은 유지)
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
            false
        }
    }

    // ── 사용자 인터랙션 ──────────────────────────────────────────────

    /**
     * SurfaceEntity(영상 Quad) 에 Movable + Resizable 부착.
     * 사용자가 헤드셋 컨트롤러로 영상 창을 잡아 옮기고 크기 변경 가능.
     * 비율 잠금은 [applyVideoAspect] 가 실제 영상 비율로 Quad 를 맞춘 다음 활성화한다.
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
     *   (SBS surface 는 width/2 만 사용하지만 디스플레이가 가로 stretch 해 한 눈 비율은 frame 과 동일)
     *
     * Quad 는 height(default 3.6m) 유지, width 를 새 비율로 늘린다.
     * 호출 후 `isFixedAspectRatioEnabled` 활성화 — 사용자 리사이즈 시 비율 유지.
     *
     * 비-XR 단말이나 SurfaceEntity 미존재 시 no-op.
     */
    fun applyVideoAspect(videoW: Int, videoH: Int, isSbs: Boolean) {
        if (!isXrDevice) return
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
