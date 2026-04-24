package com.example.minseo21

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import org.videolan.libvlc.MediaPlayer

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
        // 화면 전면 2m 거리, 2.4m 너비 (16:9 비율 기준 — 크게 만들어 놓치기 어렵게)
        private val SCREEN_POSE  = Pose(Vector3(0f, 0f, -2.0f))
        private val SCREEN_SHAPE = SurfaceEntity.Shape.Quad(FloatSize2d(2.4f, 1.35f))
        // requestFullSpaceMode() 후 XR 컴포지터 전환 안정 대기 시간
        private const val RECREATE_DELAY_MS = 300L

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
    private var lastMediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

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
        lastMediaPlayer = mediaPlayer
        val s = session ?: return false
        return try {
            // 기존 VLC 출력 분리 후 SurfaceEntity 재생성
            val vout = mediaPlayer.vlcVout
            if (vout.areViewsAttached()) {
                vout.detachViews()
                Log.i(TAG, "StereoSurface — 기존 VLC 출력 분리")
            }
            surfaceEntity?.dispose()
            surfaceEntity = SurfaceEntity.create(
                s,
                SCREEN_POSE,
                SCREEN_SHAPE,
                SurfaceEntity.StereoMode.SIDE_BY_SIDE,
            )
            val xrSurface = surfaceEntity?.getSurface()
                ?: run { Log.w(TAG, "SurfaceEntity.getSurface() null"); return false }

            vout.setVideoSurface(xrSurface, null)
            vout.attachViews()
            Log.i(TAG, "XR StereoSurface 연결 완료 — 크기=${SCREEN_SHAPE} 거리=2m")
            true
        } catch (e: Exception) {
            Log.e(TAG, "XR StereoSurface 연결 실패: $e")
            surfaceEntity = null
            false
        }
    }

    // ── 시네마 룸 모드 ───────────────────────────────────────────────────────

    /**
     * Full Space 전환 + 패스스루 OFF → XR 시네마 모드 진입.
     * Playing 이벤트 수신 시 호출.
     * HOME_SPACE(2D 패널)에서는 SurfaceEntity가 렌더되지 않으므로
     * requestFullSpaceMode() 로 openXrRendering=true 전환이 필수.
     */
    fun enterCinemaRoom() {
        val s = session ?: return
        try {
            s.scene.requestFullSpaceMode()
            Log.i(TAG, "시네마 룸 진입 — requestFullSpaceMode() 호출")
        } catch (e: Exception) {
            Log.w(TAG, "requestFullSpaceMode 실패: $e")
        }
        val env = spatialEnvironment()
        if (env != null) {
            env.preferredPassthroughOpacity = 0.0f
            Log.i(TAG, "시네마 룸 진입 — 패스스루 OFF")
        }
        inCinemaRoom = true

        // FULL_SPACE 전환 안정 대기 후 SurfaceEntity 재생성
        // HOME_SPACE에서 생성된 SurfaceEntity는 FULL_SPACE에서 무효할 수 있음
        val mp = lastMediaPlayer ?: return
        handler.postDelayed({
            if (!inCinemaRoom) return@postDelayed
            Log.i(TAG, "시네마 룸 — ${RECREATE_DELAY_MS}ms 후 SurfaceEntity 재생성 시작")
            val ok = setupStereoSurface(mp)
            Log.i(TAG, "시네마 룸 — SurfaceEntity 재생성 결과=$ok")
        }, RECREATE_DELAY_MS)
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
        lastMediaPlayer = null
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
