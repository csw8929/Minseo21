package com.example.minseo21;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.example.minseo21.xr.SpatialMode;
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

    /**
     * libVLC codec 설정.
     *
     * 2026-04-29 smoke test 결과 — `--no-mediacodec-dr` 가 c2 (modern Android codec stack) 의
     * configure 를 거부시키는 것 확인 (4K H.264 High@L5.2 입력에서). DR 활성으로 두고
     * mediacodec_jni → avcodec(SW) 폴백 순서. JNI+DR 은 NDK+DR 와 달리 SurfaceEntity 와 충돌 없음.
     */
    @Override
    protected void onConfigureVlcOptions(List<String> options) {
        super.onConfigureVlcOptions(options);
        options.removeIf(opt -> opt != null && opt.startsWith("--codec="));
        options.add("--codec=mediacodec_jni,avcodec");
    }

    /**
     * SpatialMode 결정(MP4 metadata 우선, 파일명 fallback) + 영상 frame 크기 사전 probe →
     * SurfaceEntity takeover.
     *
     * Uri overload {@link XrConfig#detectSpatialMode(android.content.Context, android.net.Uri, String)}
     * — 파일명에 키워드가 없어도 sv3d / st3d 박스가 있으면 정확히 검출 (2026-04-30 도입).
     *
     * pre-probe (MediaMetadataRetriever) 로 frame 크기를 미리 알아 SurfaceEntity 의
     * setSurfacePixelDimensions 가 codec 출력과 정확히 일치하도록 함 — mismatch 시
     * 영상 작아지고 위치 어긋나는 현상 방지 (smoke test 2026-04-29 확인).
     *
     * probe 실패 시 (~5ms 오버헤드, 드물게 실패) mode 별 default 로 fallback.
     */
    @Override
    protected boolean attemptStereoTakeover(MediaPlayer mp, String sourceName) {
        if (xr == null) return super.attemptStereoTakeover(mp, sourceName);
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        SpatialMode mode = XrConfig.detectSpatialMode(this, uri, sourceName);
        if (mode == SpatialMode.NONE) {
            Log.i(TAG, "SpatialMode=NONE: '" + sourceName + "' → 일반 attachViews fallback");
            return super.attemptStereoTakeover(mp, sourceName);
        }
        int[] dims = probeVideoDimensions();
        Log.i(TAG, "SpatialMode=" + mode + ": '" + sourceName + "' probe=" + dims[0] + "x" + dims[1]);
        if (!xr.setupStereoSurface(mp, mode, dims[0], dims[1])) {
            Log.w(TAG, "setupStereoSurface 실패 → 일반 attachViews fallback");
            return super.attemptStereoTakeover(mp, sourceName);
        }
        // VLC 출력이 SurfaceEntity 로 갔으므로 일반 videoLayout 은 숨김.
        View videoLayout = findViewById(R.id.videoLayout);
        if (videoLayout != null) videoLayout.setVisibility(View.GONE);
        return true;
    }

    /** 현재 Intent 의 URI 로 frame 크기 사전 probe. 실패 / null URI 시 [0, 0] 반환 (fallback). */
    private int[] probeVideoDimensions() {
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) return new int[] { 0, 0 };
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int videoW = w != null ? Integer.parseInt(w) : 0;
            int videoH = h != null ? Integer.parseInt(h) : 0;
            return new int[] { videoW, videoH };
        } catch (Exception e) {
            Log.w(TAG, "MediaMetadataRetriever probe 실패: " + e);
            return new int[] { 0, 0 };
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
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
