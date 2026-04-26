@file:OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
package com.example.minseo21.xr

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.MovableComponent
import androidx.xr.scenecore.ResizableComponent
import androidx.xr.scenecore.ResizeEvent
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
        // 영상 Quad 의 시작 위치/크기 — 사용자는 MovableComponent/ResizableComponent 로 변경 가능.
        private val SCREEN_POSE  = Pose(Vector3(0f, 0.3f, -3.0f))
        private val SCREEN_SHAPE = SurfaceEntity.Shape.Quad(FloatSize2d(3.2f, 1.8f))
        // 시네마 룸 진입/퇴장 fade 길이 (ms). AccelerateDecelerateInterpolator 와 함께 자연스러운 명암 전환.
        private const val PASSTHROUGH_FADE_MS = 400L

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
    private var currentResizable: ResizableComponent? = null
    private var inCinemaRoom = false
    private val handler = Handler(Looper.getMainLooper())
    // passthrough opacity fade — 빠른 토글 시 cancel 후 현재 값에서 새 방향으로 재시작.
    private var passthroughAnimator: ValueAnimator? = null

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
            currentResizable = null
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
     * Resizable 은 [ResizableComponent.isFixedAspectRatioEnabled] = true 로 설정해
     * SDK 가 직접 Entity 의 현재 비율을 잠가 사용자 리사이즈 시 비율이 깨지지 않도록 한다.
     * Resize 이벤트 시 Quad 의 Shape 을 새 크기로 갱신 — 안 그러면 사용자 인지 크기와 실제 렌더 크기가 어긋남.
     * MainPanel 자체는 Movable 부착하지 않음 (컨트롤은 영상 Quad 내부에 자동숨김으로 동작).
     */
    private fun attachInteraction(s: Session) {
        val ent = surfaceEntity ?: return
        try {
            // scaleInZ=false: 클릭/이동 시 SDK 가 Z 거리에 따라 자동 스케일하지 않음.
            // true 로 두면 헤드셋 포인터 클릭이 미세 Z 변화로 해석돼 Quad 가 폭발적으로 커지는 현상 발생.
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
            // 비율 잠금은 [applyVideoAspect] 가 실제 영상 비율로 Quad 를 맞춘 다음 활성화한다 —
            // 트랙 정보 도착 전에 잠그면 SDK 가 기본 16:9 비율에 묶이고 toggle 으로도 갱신 안 될 수 있음.
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
     * Quad 는 **height(1.8m default) 를 유지**하고 width 를 새 비율로 늘린다.
     * 16:9 컨텐츠는 그대로 3.2×1.8, cinemascope 21:9 SBS 는 4.32×1.8 처럼 가로로 더 넓어진다.
     *
     * 이 호출 후 [ResizableComponent.isFixedAspectRatioEnabled] 를 활성화 — 그 다음부터
     * 사용자 리사이즈는 새 비율을 유지한다.
     *
     * 비-XR 단말이나 SurfaceEntity 미존재(2D 패널 경로) 시 no-op.
     */
    fun applyVideoAspect(videoW: Int, videoH: Int, isSbs: Boolean) {
        if (!isXrDevice) return
        if (videoW <= 0 || videoH <= 0) return
        val ent = surfaceEntity ?: return
        val frameAspect = videoW.toFloat() / videoH
        // Full-SBS 식별: 프레임 비율이 normal 16:9 (1.78) 의 2배 가까이 (≥3.0) 면 한 눈 = frame/2.
        val fullSbs = isSbs && frameAspect >= 3.0f
        val perEyeAspect = if (fullSbs) frameAspect / 2f else frameAspect
        try {
            val current = ent.shape as? SurfaceEntity.Shape.Quad ?: return
            // Height 유지, width 를 새 aspect 로 재계산.
            val h = current.extents.height
            val newW = h * perEyeAspect
            ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(newW, h))
            // 이제 비율 잠금 활성화 — SDK 가 새 Quad 비율을 캡처.
            currentResizable?.let { rc ->
                try {
                    rc.isFixedAspectRatioEnabled = true
                } catch (e: Exception) {
                    Log.w(TAG, "isFixedAspectRatioEnabled 설정 실패: $e")
                }
            }
            Log.i(TAG, "applyVideoAspect: ${videoW}x${videoH} sbs=$isSbs(full=$fullSbs) → quad ${newW}x${h} aspect=${perEyeAspect}")
        } catch (e: Exception) {
            Log.w(TAG, "applyVideoAspect 실패: $e")
        }
    }

    // ── 시네마 룸 모드 ───────────────────────────────────────────────────────

    /**
     * Full Space 전환 + 패스스루 fade-out → XR 시네마 모드 진입.
     * Playing 이벤트 수신 시 호출.
     *
     * 매니페스트의 PROPERTY_XR_ACTIVITY_START_MODE 로 MainActivity 가 Full Space 로
     * 시작되므로, 여기서는 requestFullSpaceMode() 안전망 호출 + passthrough opacity 를
     * 0.0 으로 [PASSTHROUGH_FADE_MS] 동안 fade 시킨다.
     */
    fun enterCinemaRoom() {
        val s = session ?: return
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
        startPassthroughFade(targetOpacity = 0f)
    }

    /**
     * Home Space 복귀 + 패스스루 fade-in 복원.
     * onPause / 재생 중단 / 비-SBS 전환 시 반드시 호출.
     */
    fun exitCinemaRoom() {
        if (!inCinemaRoom) return
        handler.removeCallbacksAndMessages(null)  // 예약된 SurfaceEntity 재생성 취소
        startPassthroughFade(targetOpacity = 1f, onEnd = {
            // fade 완료 후 system default 로 복귀시켜 다른 앱과의 정합 유지.
            val env = spatialEnvironment()
            if (env != null) {
                try {
                    env.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
                } catch (e: Exception) {
                    Log.w(TAG, "preferredPassthroughOpacity NO_PREFERENCE 실패: $e")
                }
            }
        })
        try {
            session?.scene?.requestHomeSpaceMode()
            Log.i(TAG, "시네마 룸 종료 요청 — requestHomeSpaceMode()")
        } catch (e: Exception) {
            Log.w(TAG, "requestHomeSpaceMode 실패: $e")
        }
        inCinemaRoom = false
    }

    /**
     * passthrough opacity 를 현재 값에서 [targetOpacity] 까지 fade.
     * 진행 중인 애니메이터가 있으면 cancel 후 현재 opacity 값에서 새 방향으로 재시작
     * — 사용자가 빠르게 재생/일시정지 토글해도 튀지 않음.
     *
     * SDK 호출은 약 30Hz(33ms tick)로 throttle. opacity 변화가 frame budget 을 다 쓸 가능성을 보수적으로 처리.
     */
    private fun startPassthroughFade(targetOpacity: Float, onEnd: (() -> Unit)? = null) {
        val env = spatialEnvironment() ?: run { onEnd?.invoke(); return }
        // 현재 값 — 진행 중 fade 가 있으면 그 시점 값, 없으면 SDK 의 현재 preferred.
        val from = try {
            val current = env.preferredPassthroughOpacity
            if (current.isNaN() || current == SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE) {
                // NO_PREFERENCE 상태에서 시작하면 시스템 default 를 1.0 으로 가정.
                if (targetOpacity < 0.5f) 1f else 0f
            } else {
                current.coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            if (targetOpacity < 0.5f) 1f else 0f
        }

        passthroughAnimator?.cancel()
        if (from == targetOpacity) {
            try { env.preferredPassthroughOpacity = targetOpacity } catch (_: Exception) {}
            onEnd?.invoke()
            return
        }
        val anim = ValueAnimator.ofFloat(from, targetOpacity).apply {
            duration = PASSTHROUGH_FADE_MS
            interpolator = AccelerateDecelerateInterpolator()
            // ~30Hz throttle: 마지막 적용 시각 추적해 33ms 간격으로만 SDK 호출.
            var lastApplyMs = 0L
            // cancel 됐을 때 onEnd 가 stale 한 종료 동작(예: NO_PREFERENCE 적용)을 수행해
            // 이어서 시작한 새 fade 를 clobber 하는 race 방지 — onAnimationCancel 에서 flag 셋 후 onEnd 스킵.
            var cancelled = false
            addUpdateListener { va ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastApplyMs < 33L && va.animatedFraction < 1f) return@addUpdateListener
                lastApplyMs = now
                try {
                    spatialEnvironment()?.preferredPassthroughOpacity = va.animatedValue as Float
                } catch (e: Exception) {
                    Log.w(TAG, "passthrough opacity 적용 실패: $e")
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (passthroughAnimator === animation) passthroughAnimator = null
                    if (!cancelled) onEnd?.invoke()
                }
            })
        }
        passthroughAnimator = anim
        anim.start()
        Log.i(TAG, "passthrough fade ${from} → ${targetOpacity} (${PASSTHROUGH_FADE_MS}ms)")
    }

    /** 즉시 passthrough 강제 복귀 — onPause / onDestroy 안전망. fade 진행 중이라도 cancel 후 1.0 즉시 적용. */
    private fun forcePassthroughRestore() {
        passthroughAnimator?.cancel()
        passthroughAnimator = null
        val env = spatialEnvironment() ?: return
        try {
            env.preferredPassthroughOpacity = 1f
            env.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
        } catch (e: Exception) {
            Log.w(TAG, "passthrough 강제 복귀 실패: $e")
        }
    }

    // ── 생명주기 ─────────────────────────────────────────────────────────────

    /**
     * onPause 에서 반드시 호출.
     * 헤드셋을 벗거나 앱이 백그라운드 전환 시 패스스루를 강제 복귀시켜야
     * 사용자가 현실을 볼 수 없는 안전 문제를 방지한다.
     * fade 진행 중이라도 cancel 후 즉시 1.0 적용 — 안전 의무.
     */
    fun onPause() {
        forcePassthroughRestore()
        if (inCinemaRoom) {
            try {
                session?.scene?.requestHomeSpaceMode()
            } catch (e: Exception) {
                Log.w(TAG, "onPause requestHomeSpaceMode 실패: $e")
            }
            inCinemaRoom = false
        }
    }

    /** onDestroy 에서 호출. SurfaceEntity 해제. Session 은 LifecycleOwner 가 관리. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        forcePassthroughRestore()
        try { surfaceEntity?.dispose() } catch (_: Exception) {}
        surfaceEntity = null
        currentResizable = null
        inCinemaRoom = false
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
