package com.example.minseo21

import android.app.Activity
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
        // 화면 전면 2m 거리, 1.9m 너비 (16:9 비율 기준)
        private val SCREEN_POSE  = Pose(Vector3(0f, 0f, -2.0f))
        private val SCREEN_SHAPE = SurfaceEntity.Shape.Quad(FloatSize2d(1.9f, 1.07f))
    }

    val isXrDevice: Boolean = activity.packageManager
        .hasSystemFeature("android.hardware.type.xr")

    private var session: Session? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var inCinemaRoom = false

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

    // ── SBS 콘텐츠 판별 ──────────────────────────────────────────────────────

    /**
     * 파일명/경로 문자열로 SBS 3D 여부 판별.
     * 영상 크기를 알기 전에 호출 가능 — [initPlayer] 시점에 사용.
     * [name]에는 파일명 또는 syncKey("{폴더}/{파일명}") 전달.
     */
    fun isSbsByName(name: String): Boolean {
        if (!isXrDevice) return false
        val lower = name.lowercase()
        return lower.contains("_sbs") ||
               lower.contains("_3d")  ||
               lower.contains(".sbs.") ||
               lower.contains("[sbs]") ||
               lower.contains("[3d]")
    }

    /**
     * 해상도 비율로 SBS 여부 추가 확인 (가로 ÷ 세로 ≥ 3.5).
     * 트랙 정보 로딩 후([logTracks] 이후) 보조 수단으로 사용.
     */
    fun isSbsByRatio(videoW: Int, videoH: Int): Boolean {
        if (!isXrDevice || videoH <= 0) return false
        return videoW.toFloat() / videoH >= 3.5f
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
            surfaceEntity?.dispose()
            surfaceEntity = SurfaceEntity.create(
                s,
                SCREEN_POSE,
                SCREEN_SHAPE,
                SurfaceEntity.StereoMode.SIDE_BY_SIDE,
            )
            val xrSurface = surfaceEntity?.getSurface()
                ?: run { Log.w(TAG, "SurfaceEntity.getSurface() null"); return false }

            val vout = mediaPlayer.vlcVout
            vout.setVideoSurface(xrSurface, null)
            vout.attachViews()
            Log.i(TAG, "XR StereoSurface 연결 완료")
            true
        } catch (e: Exception) {
            Log.e(TAG, "XR StereoSurface 연결 실패: $e")
            surfaceEntity = null
            false
        }
    }

    // ── 시네마 룸 모드 ───────────────────────────────────────────────────────

    /** 패스스루 OFF → 가상 방으로 전환. Playing 이벤트 수신 시 호출. */
    fun enterCinemaRoom() {
        val env = spatialEnvironment() ?: return
        env.preferredPassthroughOpacity = 0.0f
        inCinemaRoom = true
        Log.i(TAG, "시네마 룸 진입 (패스스루 OFF)")
    }

    /**
     * 패스스루 복귀 → 현실 세계로 돌아옴.
     * onPause / 재생 중단 / 비-SBS 전환 시 반드시 호출.
     */
    fun exitCinemaRoom() {
        if (!inCinemaRoom) return
        val env = spatialEnvironment() ?: run { inCinemaRoom = false; return }
        env.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
        inCinemaRoom = false
        Log.i(TAG, "시네마 룸 종료 (패스스루 복귀)")
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
