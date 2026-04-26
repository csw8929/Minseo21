package com.example.minseo21.xr;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.view.View;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.List;

/**
 * MainActivity 가 직접 XR API 를 다루지 않도록 모든 XR hook 을 통합한 컨트롤러.
 *
 * 사용 패턴:
 * <pre>
 *   xr = new XrPlaybackController(this);                  // this = Host (MainActivity)
 *   getLifecycle().addObserver(xr);                       // onPause/onDestroy 자동 처리
 *   ...
 *   xr.applyVlcOptions(options);                          // libVLC options 빌드 시
 *   boolean takeover = xr.attemptStereoTakeover(mp, name);
 *   if (!takeover) mediaPlayer.attachViews(videoLayout, ...);
 *   xr.retryByRatio(mp, videoW, videoH);                  // 트랙 정보 로딩 후 비율 폴백
 *   xr.onPlayingEvent(); xr.onPausedOrStopped();          // VLC 이벤트 전달
 *   xr.onWindowFocused(hasFocus);                         // 포커스 변경 시
 * </pre>
 *
 * 비-XR 단말에서는 모든 메서드가 no-op 또는 false 반환 — 메인 흐름이 그대로 동작.
 */
public class XrPlaybackController implements DefaultLifecycleObserver {

    /** MainActivity 가 컨트롤러에 노출하는 좁은 인터페이스 — view/handler 만. */
    public interface Host {
        Activity getActivity();
        /** activity_main.xml 의 root FrameLayout — XR 진입 시 배경 투명 처리 대상. */
        View getContentRoot();
        VLCVideoLayout getVideoLayout();
        Handler getMainHandler();
    }

    private final Host host;
    private final XrPlayerManager xrManager;
    private boolean stereoActive = false;
    private boolean aspectApplied = false;

    public XrPlaybackController(Host host) {
        this.host = host;
        this.xrManager = new XrPlayerManager(host.getActivity());
        this.xrManager.init();
        // XR 단말: SurfaceEntity 가 보이려면 Activity 창 배경이 투명이어야 함.
        // setContentView 전에 호출되어야 효과적이라 Controller 생성 시점에 적용한다.
        if (xrManager.isXrDevice()) {
            host.getActivity().getWindow().setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** libVLC options 빌드 시 호출. XR 단말이면 codec 을 jni 로 고정하고 DR 비활성 (SBS 검은 화면 방지).
     *  GL 기반 SurfaceEntity 와 mediacodec_ndk + 직접 렌더링 조합이 충돌하므로 mediacodec_jni 로 강제한다. */
    public void applyVlcOptions(List<String> options) {
        if (!xrManager.isXrDevice()) return;
        options.removeIf(opt -> opt != null && opt.startsWith("--codec="));
        options.add("--codec=mediacodec_jni,none");
        options.add("--no-mediacodec-dr");
    }

    /**
     * mediaPlayer 생성 직후 호출. 파일명으로 SBS 검출되면 SurfaceEntity 로 takeover.
     * @return true 면 호출자는 {@code mediaPlayer.attachViews(videoLayout, ...)} 를 생략해야 함.
     */
    public boolean attemptStereoTakeover(MediaPlayer mp, String sourceName) {
        stereoActive = false;
        aspectApplied = false;
        if (!xrManager.isXrDevice()) return false;
        if (!xrManager.isSbsByName(sourceName)) return false;
        if (!xrManager.setupStereoSurface(mp)) return false;
        stereoActive = true;
        applySpatialUi();
        xrManager.enterCinemaRoom();
        return true;
    }

    /**
     * 트랙 정보 로딩 후 호출 — 파일명 기반 검출 실패 시 비율로 폴백 + 영상 비율 잠금.
     * 이미 takeover + 비율 적용 완료된 상태면 no-op (반복 호출이 사용자 수동 리사이즈를 clobber 하지 않도록).
     */
    public void retryByRatio(MediaPlayer mp, int videoW, int videoH) {
        if (!xrManager.isXrDevice()) return;
        boolean sbs;
        boolean newlyActivated = false;
        if (stereoActive) {
            sbs = true;
        } else if (xrManager.isSbsByRatio(videoW, videoH)) {
            if (!xrManager.setupStereoSurface(mp)) return;
            stereoActive = true;
            applySpatialUi();
            xrManager.enterCinemaRoom();
            sbs = true;
            newlyActivated = true;
        } else {
            return;
        }
        // applyVideoAspect 는 신규 활성화된 경우에만 호출 — 이미 적용된 SurfaceEntity 에 또 적용하면
        // 사용자가 수동 리사이즈한 Quad 가 매번 source aspect 로 되돌아감.
        if (newlyActivated || !aspectApplied) {
            xrManager.applyVideoAspect(videoW, videoH, sbs);
            aspectApplied = true;
        }
    }

    /** VLC Playing 이벤트 — 시네마룸 재진입 안전망. */
    public void onPlayingEvent() {
        if (stereoActive && !xrManager.isInCinemaRoom()) xrManager.enterCinemaRoom();
    }

    /** VLC Paused/Stopped — passthrough 복귀. */
    public void onPausedOrStopped() {
        if (stereoActive) xrManager.exitCinemaRoom();
    }

    /** Activity onWindowFocusChanged — 포커스 확보 시 Full Space 재요청 안전망. */
    public void onWindowFocused(boolean hasFocus) {
        if (hasFocus && stereoActive && !xrManager.isInCinemaRoom()) xrManager.enterCinemaRoom();
    }

    // ── DefaultLifecycleObserver — onPause/onDestroy 자동 hook ───────────────

    @Override
    public void onPause(LifecycleOwner owner) {
        xrManager.onPause();
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        xrManager.release();
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    /** SBS takeover 성공 시 spatial UI 적용 — videoLayout 숨김 + root 투명. 컨트롤은 영상 Quad 내부 자동숨김으로 동작. */
    private void applySpatialUi() {
        host.getVideoLayout().setVisibility(View.GONE);
        View root = host.getContentRoot();
        if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
    }
}
