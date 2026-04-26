package com.example.minseo21;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.example.minseo21.xr.XrConfig;
import com.example.minseo21.xr.XrSurfaceController;

import org.videolan.libvlc.MediaPlayer;

import java.util.List;

/**
 * Galaxy XR / Android XR 전용 SBS 3D 플레이어 Activity.
 *
 * MainActivity 를 그대로 상속받아 모든 재생 기능(이어보기 / 자막 / 오디오 트랙 / 스피드 /
 * 즐겨찾기 / Playlist prev-next)을 inherit 하고, protected hook 3개만 override 해서
 * SurfaceEntity 양안 렌더링 path 로 전환한다.
 *
 * **launch 경로:** FileListActivity 가 파일명 SBS keyword 검출 시
 * {@link com.example.minseo21.xr.XrFullSpaceLauncher#startActivity} 로 이 Activity 를 띄움.
 * 매니페스트 property {@code XR_ACTIVITY_START_MODE_Full_Space_Activity} + Bundle launch
 * 조합으로 Galaxy XR 의 DesktopTasksController 가 Full Space 전환을 발동시킨다.
 *
 * MainActivity 는 이 Activity 와 완전히 분리되어 일반 2D Home Space + system mainPanel
 * decoration 정상 동작을 유지한다 (옵션 B 핵심).
 */
public class SbsPlayerActivity extends MainActivity {

    private static final String TAG = "SACH_XR";

    private XrSurfaceController xr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // SurfaceEntity 가 보이려면 window 배경이 투명이어야 함 — setContentView 전에 적용.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // **xr 는 super.onCreate 호출 전에 초기화한다.**
        // MainActivity.onCreate 가 line 231 에서 initPlayer() 를 동기적으로 호출하고, 그 안에서
        // attemptStereoTakeover hook 이 발화된다. 그 시점에 this.xr 가 살아있어야 SBS path 로
        // 진입 — 이 순서가 깨지면 super.attemptStereoTakeover() = false 가 반환되어 일반
        // VLCVideoLayout 출력으로 fallback (2026-04-26 logcat 진단으로 확인).
        xr = new XrSurfaceController(this);
        xr.init();

        super.onCreate(savedInstanceState);

        // 영상 layer 가 SurfaceEntity Quad 로 빠지므로 root FrameLayout 의 검정 배경도 제거.
        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
    }

    /** GL surface(SurfaceEntity) + mediacodec NDK direct render 충돌 → JNI 강제 + DR 비활성. */
    @Override
    protected void onConfigureVlcOptions(List<String> options) {
        super.onConfigureVlcOptions(options);
        options.removeIf(opt -> opt != null && opt.startsWith("--codec="));
        options.add("--codec=mediacodec_jni,none");
        options.add("--no-mediacodec-dr");
    }

    /**
     * 파일명 SBS keyword 매치 시 SurfaceEntity 로 takeover.
     * 매치 안 되면 super (=false) 반환 → 호출자가 일반 attachViews 사용.
     * (이론상 SbsPlayerActivity 는 SBS keyword 영상으로만 launch 되지만 안전망 확보.)
     */
    @Override
    protected boolean attemptStereoTakeover(MediaPlayer mp, String sourceName) {
        if (xr == null) return super.attemptStereoTakeover(mp, sourceName);
        if (!XrConfig.sbsPatternMatch(sourceName)) {
            Log.i(TAG, "SBS pattern 미검출: '" + sourceName + "' → 일반 attachViews fallback");
            return super.attemptStereoTakeover(mp, sourceName);
        }
        if (!xr.setupStereoSurface(mp)) {
            Log.w(TAG, "setupStereoSurface 실패 → 일반 attachViews fallback");
            return super.attemptStereoTakeover(mp, sourceName);
        }
        // VLC 출력이 SurfaceEntity 로 갔으므로 일반 videoLayout 은 숨김.
        View videoLayout = findViewById(R.id.videoLayout);
        if (videoLayout != null) videoLayout.setVisibility(View.GONE);
        return true;
    }

    @Override
    protected void onVideoTrackInfo(MediaPlayer mp, int videoW, int videoH) {
        super.onVideoTrackInfo(mp, videoW, videoH);
        if (xr != null) xr.applyVideoAspect(videoW, videoH, /* isSbs */ true);
    }

    @Override
    protected void onDestroy() {
        if (xr != null) {
            xr.release();
            xr = null;
        }
        super.onDestroy();
    }
}
