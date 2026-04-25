@file:OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
package com.example.minseo21.xr

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.EntityMoveListener
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.ResizableComponent
import androidx.xr.scenecore.ResizeEvent
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs

/**
 * Galaxy XR / Android XR 전용 플레이어 관리자.
 *
 * 비-XR 단말에서는 [isXrDevice] == false → 모든 메서드가 no-op.
 * XR 단말에서는 SBS 영상을 SurfaceEntity 양안 렌더링으로 출력하고,
 * 재생 시 패스스루를 끄고 가상 시네마 룸 모드로 전환한다.
 *
 * 주의: androidx.xr.scenecore는 alpha API — 버전(1.0.0-alpha13)이 변경되면
 * 메서드 시그니처가 달라질 수 있다.
 */
class XrPlayerManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SACH_XR"
        // 영상 Quad 의 시작 위치/크기 — 사용자는 MovableComponent/ResizableComponent 로 변경 가능.
        private val SCREEN_POSE  = Pose(Vector3(0f, 0.3f, -3.0f))
        private val SCREEN_SHAPE = SurfaceEntity.Shape.Quad(FloatSize2d(3.2f, 1.8f))

        // ── 순수 함수 (Android 의존 없음 — JUnit 직접 테스트 가능) ─────────────
        internal fun sbsPatternMatch(name: String): Boolean {
            val lower = name.lowercase()
            return lower.contains("_sbs") ||
                   lower.contains("_3d")  ||
                   lower.contains(".sbs.") ||
                   lower.contains("[sbs]") ||
                   lower.contains("[3d]")
        }

        internal fun sbsRatioMatch(videoW: Int, videoH: Int): Boolean {
            if (videoH <= 0) return false
            return videoW.toFloat() / videoH >= 3.5f
        }
    }

    // Galaxy XR(SM-I610)은 android.hardware.type.xr 대신
    // android.software.xr.api.spatial 또는 android.hardware.xr.input.controller 를 사용
    val isXrDevice: Boolean = with(activity.packageManager) {
        hasSystemFeature("android.hardware.type.xr") ||
        hasSystemFeature("android.software.xr.api.spatial") ||
        hasSystemFeature("android.hardware.xr.input.controller")
    }.also { result ->
        Log.i(TAG, "[isXrDevice] " + result +
            " (type.xr=" + activity.packageManager.hasSystemFeature("android.hardware.type.xr") +
            " xr.api.spatial=" + activity.packageManager.hasSystemFeature("android.software.xr.api.spatial") +
            " xr.input.controller=" + activity.packageManager.hasSystemFeature("android.hardware.xr.input.controller") + ")")
    }

    private var session: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var inCinemaRoom = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Panel 과 영상 Quad 의 겹침 상태가 변할 때만 호출되는 콜백.
     * `true` = 겹침(컨트롤이 영상 가림 → 자동숨김 필요),
     * `false` = panel 이 영상 바깥(가리지 않음 → 자동숨김 불필요).
     * 메인 스레드에서 호출됨 (XR Move 콜백이 메인 thread).
     */
    var onPanelOverlapChanged: ((Boolean) -> Unit)? = null
    private var lastOverlap: Boolean? = null

    // ── 초기화 ──────────────────────────────────────────────────────────────

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

    fun isInCinemaRoom(): Boolean = inCinemaRoom

    // ── SBS 콘텐츠 판별 ──────────────────────────────────────────────────────

    /**
     * 파일명/경로 문자열로 SBS 3D 여부 판별.
     * 영상 크기를 알기 전에 호출 가능 — [initPlayer] 시점에 사용.
     * [name]에는 파일명 또는 syncKey("{폴더}/{파일명}") 전달.
     */
    fun isSbsByName(name: String): Boolean {
        if (!isXrDevice) return false
        return sbsPatternMatch(name)
    }

    /**
     * 해상도 비율로 SBS 여부 추가 확인 (가로 ÷ 세로 ≥ 3.5).
     * 트랙 정보 로딩 후([logTracks] 이후) 보조 수단으로 사용.
     */
    fun isSbsByRatio(videoW: Int, videoH: Int): Boolean {
        if (!isXrDevice) return false
        return sbsRatioMatch(videoW, videoH)
    }

    // ── libVLC → SurfaceEntity 연결 ──────────────────────────────────────────

    /**
     * libVLC 출력을 XR StereoSurface 로 교체.
     * [initPlayer] 에서 mediaPlayer 생성 직후, attachViews() 호출 전에 실행.
     *
     * @return true = XR Surface 연결 성공 → 호출자는 attachViews(videoLayout) 를 생략.
     *         false = 실패 또는 비-XR → 호출자가 기존 attachViews(videoLayout) 를 그대로 사용.
     */
    fun setupStereoSurface(mediaPlayer: MediaPlayer): Boolean {
        val s = session ?: return false
        return try {
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) vout.detachViews()
            surfaceEntity?.dispose()
            surfaceEntity = SurfaceEntity.create(
                s,
                SCREEN_POSE,
                SCREEN_SHAPE,
                SurfaceEntity.StereoMode.SIDE_BY_SIDE,
            )
            // MediaBlendingMode 를 OPAQUE 로 명시 — default(TRANSPARENT)면 비디오 투명 렌더됨.
            try {
                surfaceEntity?.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
            } catch (e: Exception) {
                Log.w(TAG, "mediaBlendingMode 설정 실패: $e")
            }
            // Surface 의 픽셀 크기를 libVLC 비디오 크기와 맞춤. 누락 시 frame mismatch 로 검은 화면 발생.
            try {
                @Suppress("OPT_IN_USAGE")
                surfaceEntity?.setSurfacePixelDimensions(
                    androidx.xr.runtime.math.IntSize2d(1920, 1080)
                )
            } catch (e: Exception) {
                Log.w(TAG, "setSurfacePixelDimensions 실패: $e")
            }
            val xrSurface = surfaceEntity?.getSurface()
                ?: run { Log.w(TAG, "SurfaceEntity.getSurface() null"); return false }

            vout.setVideoSurface(xrSurface, null)
            vout.attachViews()
            Log.i(TAG, "XR StereoSurface 연결 완료")

            attachInteraction(s)
            true
        } catch (e: Exception) {
            Log.e(TAG, "XR StereoSurface 연결 실패: $e")
            surfaceEntity = null
            false
        }
    }

    // ── 사용자 인터랙션 (Movable / Resizable) ──────────────────────────────

    /**
     * SurfaceEntity(영상 Quad) 에 시스템 Movable + Resizable 컴포넌트 부착.
     * 사용자가 헤드셋 컨트롤러로 영상 창을 잡아 옮기고 크기 변경 가능.
     * Resize 이벤트 시 Quad 의 Shape 을 새 크기로 갱신 — 안 그러면 사용자 인지 크기와 실제 렌더 크기가 어긋남.
     */
    private fun attachInteraction(s: Session) {
        val ent = surfaceEntity ?: return
        try {
            val movable = MovableComponent.createSystemMovable(s, /* scaleInZ = */ true)
            movable.addMoveListener(overlapTrackingListener)
            ent.addComponent(movable)
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceEntity Movable 실패: $e")
        }
        try {
            val resizable = ResizableComponent.create(s) { event: ResizeEvent ->
                val ns = event.newSize
                try {
                    ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(ns.width, ns.height))
                    checkAndNotifyOverlap()
                } catch (e: Exception) {
                    Log.w(TAG, "resize Shape 갱신 실패: $e")
                }
            }
            ent.addComponent(resizable)
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceEntity Resizable 실패: $e")
        }
    }

    /**
     * Activity 의 MainPanelEntity(컨트롤 호스트) 에 Movable 부착.
     * 사용자가 panel(컨트롤이 그려지는 표면) 자체를 잡아 옮길 수 있게 한다.
     * MainActivity.onCreate 에서 XR SBS 모드 진입 후 호출.
     */
    fun enableMainPanelInteraction() {
        val s = session ?: return
        try {
            val movable = MovableComponent.createSystemMovable(s)
            movable.addMoveListener(overlapTrackingListener)
            s.scene.mainPanelEntity.addComponent(movable)
        } catch (e: Exception) {
            Log.w(TAG, "MainPanel Movable 실패: $e")
        }
    }

    /** Panel/Surface 둘 다 같은 리스너 사용 — 어느 쪽이 움직여도 겹침 재계산. */
    private val overlapTrackingListener = object : EntityMoveListener {
        override fun onMoveUpdate(
            entity: Entity,
            currentInputRay: androidx.xr.runtime.math.Ray,
            currentPose: androidx.xr.runtime.math.Pose,
            currentScale: Float,
        ) {
            checkAndNotifyOverlap()
        }
        override fun onMoveEnd(
            entity: Entity,
            finalInputRay: androidx.xr.runtime.math.Ray,
            finalPose: androidx.xr.runtime.math.Pose,
            finalScale: Float,
            updatedParent: Entity?,
        ) {
            checkAndNotifyOverlap()
        }
    }

    /**
     * Panel(=컨트롤 호스트) 와 Surface Quad(=영상) 의 xy 평면 AABB 가 겹치는지 재계산.
     * 상태가 바뀐 경우에만 [onPanelOverlapChanged] 콜백 호출 — 매 frame spam 방지.
     */
    private fun checkAndNotifyOverlap() {
        val overlap = computeOverlap()
        if (overlap != lastOverlap) {
            lastOverlap = overlap
            onPanelOverlapChanged?.invoke(overlap)
        }
    }

    /** 겹침 판정. 정보 부족 시 안전하게 true(=겹침으로 가정) 반환 → 자동숨김 동작 유지. */
    private fun computeOverlap(): Boolean {
        val s = session ?: return true
        val ent = surfaceEntity ?: return true
        return try {
            val panel = s.scene.mainPanelEntity
            val panelPose = panel.getPose()
            val panelSize = panel.size
            val surfPose = ent.getPose()
            val quad = ent.shape as? SurfaceEntity.Shape.Quad ?: return true
            val surfExtents = quad.extents

            val dx = abs(panelPose.translation.x - surfPose.translation.x)
            val dy = abs(panelPose.translation.y - surfPose.translation.y)
            val sumHalfX = (panelSize.width + surfExtents.width) / 2f
            val sumHalfY = (panelSize.height + surfExtents.height) / 2f
            !(dx > sumHalfX || dy > sumHalfY)
        } catch (e: Exception) {
            Log.w(TAG, "[Overlap] 계산 실패: $e")
            true
        }
    }

    // ── 시네마 룸 모드 ───────────────────────────────────────────────────────

    /**
     * Full Space 전환 + 패스스루 OFF → XR 시네마 모드 진입.
     * Playing 이벤트 수신 시 호출.
     *
     * 매니페스트의 PROPERTY_XR_ACTIVITY_START_MODE=FULL_SPACE_MANAGED 로
     * MainActivity가 Full Space 로 시작되므로, 여기서는 requestFullSpaceMode() 만 호출.
     * 이전에는 HOME→FULL 전환 후 SurfaceEntity 재생성이 필요했으나,
     * 재생성 경로가 systemui(splitEngine)의 createExternalTextureSurface 크래시를
     * 유발하는 것이 확인되어 제거했다 (null producer 경쟁 상태).
     */
    fun enterCinemaRoom() {
        val s = session ?: return
        // 참고: Spatial Film 도 passthrough 유지하며 spatial panel 로 재생함.
        // 따라서 passthrough 를 OFF 하거나 환경을 강제하지 않음.
        try {
            s.scene.requestFullSpaceMode()
        } catch (e: Exception) {
            Log.w(TAG, "requestFullSpaceMode 실패: $e")
        }
        // alpha/parent 안전망 — alpha13 default 가 무효화될 수 있어 명시 설정. SBS 검은 화면 회귀 방지.
        val ent = surfaceEntity
        if (ent != null) {
            try {
                ent.setAlpha(1.0f)
                if (ent.parent == null) ent.parent = s.scene.activitySpace
            } catch (e: Exception) {
                Log.w(TAG, "SurfaceEntity alpha/parent 설정 실패: $e")
            }
        }
        inCinemaRoom = true
    }

    /**
     * Home Space 복귀 + 패스스루 복원.
     * onPause / 재생 중단 / 비-SBS 전환 시 반드시 호출.
     */
    fun exitCinemaRoom() {
        if (!inCinemaRoom) return
        handler.removeCallbacksAndMessages(null)  // 예약된 SurfaceEntity 재생성 취소
        try {
            session?.scene?.requestHomeSpaceMode()
            Log.i(TAG, "시네마 룸 종료 — requestHomeSpaceMode()")
        } catch (e: Exception) {
            Log.w(TAG, "requestHomeSpaceMode 실패: $e")
        }
        val env = spatialEnvironment()
        if (env != null) {
            env.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
        }
        inCinemaRoom = false
        Log.i(TAG, "시네마 룸 종료 — 패스스루 복귀")
    }

    // ── 생명주기 ─────────────────────────────────────────────────────────────

    /**
     * onPause 에서 반드시 호출.
     * 헤드셋을 벗거나 앱이 백그라운드 전환 시 패스스루를 강제 복귀시켜야
     * 사용자가 현실을 볼 수 없는 안전 문제를 방지한다.
     */
    fun onPause() {
        exitCinemaRoom()
    }

    /** onDestroy 에서 호출. SurfaceEntity 해제. Session 은 LifecycleOwner 가 관리. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        exitCinemaRoom()
        try { surfaceEntity?.dispose() } catch (_: Exception) {}
        surfaceEntity = null
        session = null
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private fun spatialEnvironment(): SpatialEnvironment? {
        return try {
            session?.scene?.spatialEnvironment
        } catch (e: Exception) {
            Log.w(TAG, "SpatialEnvironment 접근 실패: $e")
            null
        }
    }
}
