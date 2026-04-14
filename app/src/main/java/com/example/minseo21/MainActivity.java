package com.example.minseo21;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements IVLCVout.Callback {

    private static final int CONTROLS_HIDE_DELAY_MS = 3000;
    private static final String TAG = "SACH";
    private static final long SAVE_INTERVAL_MS     = 5_000;
    private static final long NAS_FLUSH_INTERVAL_MS = 30_000;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private ParcelFileDescriptor pfd;
    private boolean tracksLogged = false;
    private int videoW = 0, videoH = 0;

    private boolean rotationLocked = false;

    private String currentUriKey = null;
    /** Room DB 키. NAS 파일은 canonical URL, 로컬은 content:// URI 문자열. */
    private String currentDbKey  = null;
    /** NAS 위치 캐시 키. "{폴더명}/{파일명}" 형태. */
    private String currentSyncKey = null;
    private String currentTitle = null;
    private String currentBucketId = null; // 현재 폴더 ID
    private static final String PREFS_NAME    = "player_prefs";
    private static final String KEY_SCREEN_MODE = "screen_mode";
    private static final String KEY_LAST_STATE = "last_app_state";
    private static final String KEY_PLAYBACK_SPEED = "playback_speed";

    private NasSyncManager nasSyncManager;
    private java.util.concurrent.Future<?> nasFlushing;

    private long pendingSeekMs       = -1;
    private int  pendingSubtitleId   = Integer.MIN_VALUE;
    private int  pendingAudioId      = Integer.MIN_VALUE;
    /** 버퍼링 시작 시각 (System.currentTimeMillis). 0 = 버퍼링 중 아님 */
    private long bufferingStartMs    = 0;
    private int  bufferingCount      = 0;
    private int  subtitleMargin      = 0;
    private int  currentSubtitleTrackId  = Integer.MIN_VALUE;
    private int  currentAudioTrackId     = Integer.MIN_VALUE;
    private int  currentScreenMode       = -1;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private VLCVideoLayout videoLayout;
    private ProgressBar loadingBar;
    private View topBar;
    private View centerControls;
    private View controlsOverlay;
    private TextView tvTitle;
    private ImageButton btnOptions;
    private ImageButton btnRotationLock;
    private TextView btnSpeed;
    private ImageButton btnPlayPause;
    private ImageButton btnRewind;
    private ImageButton btnFastForward;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private TextView errorText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isSeeking = false;
    private boolean controlsVisible = false;

    private ScaleGestureDetector scaleGestureDetector;
    private float zoomFactor = 1.0f;

    private final Runnable hideControls = () -> {
        Log.d(TAG, "[UI] 컨트롤 hide");
        topBar.setVisibility(View.GONE);
        centerControls.setVisibility(View.GONE);
        controlsOverlay.setVisibility(View.GONE);
        controlsVisible = false;
        // 회전 잠금 상태는 컨트롤 숨김과 무관하게 유지
    };

    private final Runnable savePositionTask = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private final Runnable nasFlushTask = new Runnable() {
        @Override
        public void run() {
            nasSyncManager.flushToNas();
            handler.postDelayed(this, NAS_FLUSH_INTERVAL_MS);
        }
    };

    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && !isSeeking) {
                long time   = mediaPlayer.getTime();
                long length = mediaPlayer.getLength();
                if (length > 0) {
                    seekBar.setMax((int) length);
                    seekBar.setProgress((int) time);
                    tvCurrentTime.setText(formatTime(time));
                    tvTotalTime.setText(formatTime(length));
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        nasSyncManager = new NasSyncManager(this, dbExecutor);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsCtrl =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsCtrl.hide(WindowInsetsCompat.Type.systemBars());
        insetsCtrl.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_main);

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_LAST_STATE, 1).apply();

        videoLayout      = findViewById(R.id.videoLayout);
        loadingBar       = findViewById(R.id.loadingBar);
        topBar           = findViewById(R.id.topBar);
        centerControls   = findViewById(R.id.centerControls);
        controlsOverlay  = findViewById(R.id.controlsOverlay);
        tvTitle          = findViewById(R.id.tvTitle);
        btnOptions       = findViewById(R.id.btnOptions);
        btnRotationLock  = findViewById(R.id.btnRotationLock);
        btnSpeed         = findViewById(R.id.btnSpeed);

        // 저장된 배속 불러와서 버튼 텍스트 초기화
        float savedSpeed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getFloat(KEY_PLAYBACK_SPEED, 1.0f);
        btnSpeed.setText(formatSpeed(savedSpeed));
        btnPlayPause     = findViewById(R.id.btnPlayPause);
        btnRewind        = findViewById(R.id.btnRewind);
        btnFastForward   = findViewById(R.id.btnFastForward);
        btnPrev          = findViewById(R.id.btnPrev);
        btnNext          = findViewById(R.id.btnNext);
        seekBar          = findViewById(R.id.seekBar);
        tvCurrentTime    = findViewById(R.id.tvCurrentTime);
        tvTotalTime      = findViewById(R.id.tvTotalTime);
        errorText        = findViewById(R.id.errorText);

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        Uri videoUri = getIntent().getData();
        if (videoUri == null) {
            loadingBar.setVisibility(View.GONE);
            errorText.setVisibility(View.VISIBLE);
            errorText.setText("재생할 동영상이 없습니다.");
            return;
        }

        currentTitle = getIntent().getStringExtra("title");
        // bucketId 정보 가져오기
        if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0) {
            VideoItem current = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
            if (currentTitle == null) currentTitle = current.name;
            currentBucketId = current.bucketId;
        }

        if (currentTitle == null) {
            String seg = videoUri.getLastPathSegment();
            currentTitle = seg != null ? seg : "";
        }
        tvTitle.setText(currentTitle);

        updateNavButtons();
        setupControls();
        initPlayer(videoLayout, videoUri);
        scheduleHide();
        handler.post(updateProgress);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_LAST_STATE, 0).apply();
                finish();
            }
        });
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            zoomFactor *= detector.getScaleFactor();
            zoomFactor = Math.max(0.5f, Math.min(zoomFactor, 3.0f));
            if (videoLayout != null) {
                videoLayout.setScaleX(zoomFactor);
                videoLayout.setScaleY(zoomFactor);
            }
            return true;
        }
    }

    /** NAS 파일이면 canonical URL, 로컬이면 URI 문자열 그대로 반환.
     *  Playlist에 canonicalUri가 없으면 NAS 스트림 URL에서 _sid를 제거한 canonical을 재구성.
     *  (세션간 _sid 누적 방지) */
    private String resolveDbKey(Uri uri) {
        if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0) {
            VideoItem vi = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
            if (vi.canonicalUri != null) return vi.canonicalUri;
        }
        // NAS 스트림 URL이면 canonical로 정규화 (_sid 누적 방지)
        return DsFileApiClient.toCanonicalUrl(uri.toString());
    }

    /**
     * NAS 위치 캐시 키 생성: "{폴더명}/{파일명}"
     * - NAS 파일: nasPath의 마지막 두 세그먼트 (/video/드라마/ep01.mkv → 드라마/ep01.mkv)
     * - 로컬 파일: bucketDisplayName + "/" + name
     * - fallback: 파일명만
     */
    /**
     * NAS syncKey = 파일명만 (폴더 무관).
     * A/B 단말이 같은 파일을 다른 폴더에서 재생할 수 있으므로, 크로스 디바이스 매칭은 파일명 기준.
     */
    private String deriveSyncKey() {
        if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0) {
            VideoItem item = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
            // NAS 파일: nasPath의 마지막 세그먼트 (파일명)
            if (item.nasPath != null && !item.nasPath.isEmpty()) {
                String[] parts = item.nasPath.split("/");
                String name = parts[parts.length - 1];
                if (!name.isEmpty()) return name;
            }
            // 로컬 파일: name
            if (item.name != null && !item.name.isEmpty()) return item.name;
        }
        // Intent로 직접 열린 경우 → 파일 URI의 마지막 세그먼트
        if (currentTitle != null && !currentTitle.isEmpty()) return currentTitle;
        return "";
    }

    private void initPlayer(VLCVideoLayout videoLayout, Uri videoUri) {
        currentUriKey  = videoUri.toString();
        currentDbKey   = resolveDbKey(videoUri);
        currentSyncKey = deriveSyncKey();
        // NAS flush 타이머 시작 (중복 제거 후 재등록)
        handler.removeCallbacks(nasFlushTask);
        handler.postDelayed(nasFlushTask, NAS_FLUSH_INTERVAL_MS);

        boolean isNetwork = "http".equals(videoUri.getScheme()) || "https".equals(videoUri.getScheme());

        ArrayList<String> options = new ArrayList<>();
        // HW 디코더: mediacodec_ndk(NDK) → mediacodec_jni(JNI) → 소프트웨어 순서로 시도
        options.add("--codec=mediacodec_ndk,mediacodec_jni,none");
        // 색심도: RV32 (32bit, 고색 재현). RV16보다 메모리 대역폭 2× 사용.
        // VLCVideoLayout은 TextureView 기반 → --vout=android-display 사용 불가 (충돌)
        options.add("--android-display-chroma=RV32");
        // -1(auto): 인터레이스 콘텐츠(구형 AVI 등) 자동 감지. 0(off)이면 인터레이스 AVI가 흐릿하게 보임
        options.add("--deinterlace=-1");
        options.add("--aout=opensles");
        // SW 디코더 스레드 수: Xvid/DivX 등 HW 가속 불가 코덱의 소프트웨어 폴백 성능 향상
        options.add("--avcodec-threads=4");
        // 릴레이 연결 여부: direct.quickconnect.to 또는 quickconnect.to 경유 시 레이턴시 높음
        boolean isRelay = isNetwork && DsFileApiClient.isRelayUrl(videoUri.toString());
        // 캐싱: 로컬 500ms / NAS 5000ms / 릴레이 45000ms
        // 릴레이(대만 서버 경유)는 RTT ~560ms + 대역폭 제한 → 45초 선버퍼
        options.add(isRelay ? "--network-caching=45000" : isNetwork ? "--network-caching=5000" : "--file-caching=500");
        options.add("--live-caching=300");
        if (isNetwork) {
            // NAS 스트리밍: 클럭 지터 허용 + 동기화 활성화
            // AVI 컨테이너는 타이밍 메타데이터가 부정확해 jitter=0/synchro=0이면
            // SurfaceTexture 버퍼 슬롯 고갈(BufferQueueProducer timeout) 발생
            // 릴레이: RTT ~560ms → jitter=2000 으로 클록 리셋 방지
            options.add(isRelay ? "--clock-jitter=2000" : "--clock-jitter=500");
            if (isRelay) {
                // 릴레이 연결 끊김 시 자동 재연결
                options.add("--http-reconnect");
            }
        } else {
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");
        }
        // NOTE: --no-drop-late-frames / --no-skip-frames 제거.
        // 위 두 옵션은 "프레임 절대 드롭 금지"로 지연 누적 시 회복 불가 → 끊김 주 원인
        // NOTE: --no-audio-time-stretch 제거 (네트워크용).
        // AVI 오디오 헤더가 불량(0Hz)일 때 time-stretch 비활성화 시 오디오 초기화 실패 →
        // A/V 동기 파이프라인 중단 → 프레임 스탈 유발
        if (!isNetwork) {
            options.add("--no-audio-time-stretch");
        }
        options.add("--input-fast-seek");
        if (subtitleMargin > 0) {
            options.add("--sub-margin=" + subtitleMargin);
        }

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, true);
        mediaPlayer.getVLCVout().addCallback(this);
        mediaPlayer.setEventListener(event -> runOnUiThread(() -> handleVlcEvent(event)));

        loadSavedPosition(currentDbKey);

        // TODO: 5G 환경에서 HLS 트랜스코딩(SYNO.VideoStation2.Streaming) 지원 예정.
        //       현재는 5G 포함 모든 환경에서 직접 스트리밍으로 재생.
        {
            // WiFi / 5G / 로컬 파일 → 직접 재생
            Media media = openMedia(videoUri);
            if (media == null) {
                loadingBar.setVisibility(View.GONE);
                errorText.setVisibility(View.VISIBLE);
                errorText.setText("파일을 열 수 없습니다.");
                return;
            }
            mediaPlayer.setMedia(media);
            media.release();
            tryAddExternalSubtitles(videoUri);
            mediaPlayer.play();
        }
    }

    /**
     * NAS 스트림/캐노니컬 URL 에서 파일 경로 추출.
     * 예: ...&path=%2Fvideo%2Ffoo.mkv&... → "/video/foo.mkv"
     * NAS URL 이 아니거나 path 파라미터 없으면 null.
     */
    private static String extractNasPathFromUri(Uri uri) {
        if (!DsFileApiClient.isNasUrl(uri.toString())) return null;
        String path = uri.getQueryParameter("path");
        return (path != null && !path.isEmpty()) ? path : null;
    }

    private void tryAddExternalSubtitles(Uri videoUri) {
        String scheme = videoUri.getScheme();

        // ── NAS 스트림: PlaylistHolder에서 nasPath 추출 후 비동기 자막 탐색 ──
        if ("http".equals(scheme) || "https".equals(scheme)) {
            tryAddNasSubtitles();
            return;
        }

        // ── 로컬 content:// ──
        if (!"content".equals(scheme)) return;

        // Fast single-row query on main thread to get metadata
        String videoPath = null;
        String videoName = null;
        boolean bucketIdFound = false;
        long bucketId = -1;
        String[] proj = {
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID
        };
        try (Cursor cursor = getContentResolver().query(videoUri, proj, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                videoPath = cursor.getString(0);
                videoName = cursor.getString(1);
                bucketId = cursor.getLong(2);
                bucketIdFound = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "[Sub] failed to get video path", e);
        }

        if (videoName == null) return;

        final String finalBaseName;
        int lastDot = videoName.lastIndexOf('.');
        finalBaseName = (lastDot > 0) ? videoName.substring(0, lastDot) : videoName;
        final String finalVideoPath = videoPath;
        final long finalBucketId = bucketId;
        final boolean finalBucketIdFound = bucketIdFound;

        // Bucket scan can be slow on large folders — run off the main thread
        dbExecutor.execute(() -> {
            java.util.Set<String> subExtSet = new java.util.HashSet<>(
                    java.util.Arrays.asList(".smi", ".srt", ".ass", ".ssa"));
            boolean found = false;

            // Android 10+ fix: File.exists() is unreliable on external storage.
            // Use ContentResolver to scan same MediaStore bucket instead.
            if (finalBucketIdFound) {
                android.net.Uri filesUri = android.provider.MediaStore.Files.getContentUri("external");
                String[] fileProj = {
                    android.provider.MediaStore.Files.FileColumns._ID,
                    android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME
                };
                String sel = android.provider.MediaStore.Files.FileColumns.BUCKET_ID + "=?";
                try (Cursor fc = getContentResolver().query(
                        filesUri, fileProj, sel, new String[]{String.valueOf(finalBucketId)}, null)) {
                    while (fc != null && fc.moveToNext()) {
                        String name = fc.getString(1);
                        if (name == null) continue;
                        int dot = name.lastIndexOf('.');
                        String fBase = (dot > 0) ? name.substring(0, dot) : name;
                        String fExt  = (dot > 0) ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
                        if (fBase.equalsIgnoreCase(finalBaseName) && subExtSet.contains(fExt)) {
                            long id = fc.getLong(0);
                            android.net.Uri subUri = android.net.Uri.withAppendedPath(filesUri, String.valueOf(id));
                            final String subName = name;
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed() || mediaPlayer == null) return;
                                boolean ok = mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, subUri, true);
                                Log.i(TAG, "[Sub] ContentResolver " + (ok ? "추가됨" : "실패") + ": " + subName);
                            });
                            found = true;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[Sub] ContentResolver 자막 탐색 실패", e);
                }
            }

            // Fallback: direct file path (Android 9 이하, 또는 내부 저장소)
            if (!found && finalVideoPath != null) {
                int lastSlash = finalVideoPath.lastIndexOf('/');
                if (lastSlash >= 0) {
                    String folderPath = finalVideoPath.substring(0, lastSlash + 1);
                    String[] subExts = {".smi", ".srt", ".SMI", ".SRT", ".ass", ".ssa"};
                    for (String ext : subExts) {
                        java.io.File subFile = new java.io.File(folderPath + finalBaseName + ext);
                        if (subFile.exists()) {
                            final Uri subUri = Uri.fromFile(subFile);
                            final String subName = subFile.getName();
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed() || mediaPlayer == null) return;
                                boolean ok = mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, subUri, true);
                                Log.i(TAG, "[Sub] File.exists " + (ok ? "추가됨" : "실패") + ": " + subName);
                            });
                            found = true;
                        }
                    }
                }
            }

            if (!found) Log.d(TAG, "[Sub] No matching external subtitles found for: " + finalBaseName);
        });
    }

    /**
     * NAS 재생 시 같은 폴더의 자막 파일 자동 탐색.
     * PlaylistHolder에서 현재 항목의 nasPath를 읽어 폴더를 listFolder.
     */
    private void tryAddNasSubtitles() {
        if (PlaylistHolder.playlist == null
                || PlaylistHolder.currentIndex < 0
                || PlaylistHolder.currentIndex >= PlaylistHolder.playlist.size()) return;

        VideoItem current = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
        String nasPath = current.nasPath;
        if (nasPath == null || nasPath.isEmpty()) return;

        String sid = DsFileApiClient.getCachedSid();
        if (sid == null) { Log.d(TAG, "[Sub] NAS SID 없음, 자막 탐색 스킵"); return; }

        String nasFolder = nasPath.contains("/")
                ? nasPath.substring(0, nasPath.lastIndexOf('/'))
                : nasPath;
        String videoName = current.name != null ? current.name : "";
        int lastDot = videoName.lastIndexOf('.');
        String baseName = (lastDot > 0) ? videoName.substring(0, lastDot) : videoName;
        String[] subExts = {".smi", ".srt", ".SMI", ".SRT", ".ass", ".ssa"};

        Log.d(TAG, "[Sub] NAS 자막 탐색: 폴더=" + nasFolder + ", 기준파일=" + baseName);
        DsFileApiClient.listFolder(nasFolder, sid, new DsFileApiClient.Callback<List<VideoItem>>() {
            @Override public void onResult(List<VideoItem> items) {
                if (isFinishing() || isDestroyed() || mediaPlayer == null) return;
                for (VideoItem item : items) {
                    if (item.type == VideoItem.TYPE_FOLDER || item.name == null) continue;
                    for (String ext : subExts) {
                        if (item.name.equalsIgnoreCase(baseName + ext) && item.nasPath != null) {
                            String subUrl = DsFileApiClient.getStreamUrl(item.nasPath, sid);
                            boolean ok = mediaPlayer.addSlave(
                                    IMedia.Slave.Type.Subtitle, Uri.parse(subUrl), true);
                            Log.i(TAG, "[Sub] NAS 자막 " + (ok ? "추가됨" : "실패") + ": " + item.name);
                        }
                    }
                }
            }
            @Override public void onError(String msg) {
                Log.d(TAG, "[Sub] NAS 자막 탐색 실패 (무시): " + msg);
            }
        });
    }

    private void loadSavedPosition(String uriKey) {
        // 모든 파일: NasSyncManager (Room DB + NAS 캐시 비교, 더 최신 쪽 사용)
        // 콜백은 두 번 호출될 수 있음 — Room DB 먼저, NAS 캐시가 더 최신이면 이후 한 번 더
        nasSyncManager.loadPosition(currentSyncKey, uriKey, pos -> {
            if (pos == null) return;
            if (pos.positionMs > 0) {
                Log.d(TAG, "[Sync] resume at " + pos.positionMs + "ms (key=" + currentSyncKey + ")");
                if (tracksLogged && mediaPlayer != null) {
                    mediaPlayer.setTime(pos.positionMs);
                } else {
                    pendingSeekMs = pos.positionMs;
                }
            }
            pendingSubtitleId = pos.subtitleTrackId;
            pendingAudioId    = pos.audioTrackId;
            if (tracksLogged) applyPendingSettings();
        });
    }

    private void saveCurrentPosition() {
        if (mediaPlayer == null || currentUriKey == null) return;
        if (currentDbKey == null) return;
        long pos = mediaPlayer.getTime();
        if (pos <= 0) return;
        PlaybackPosition pp = new PlaybackPosition();
        pp.uri        = currentDbKey;
        pp.name       = currentTitle;
        pp.bucketId   = currentBucketId;
        pp.positionMs = pos;
        pp.updatedAt  = System.currentTimeMillis();
        pp.subtitleTrackId = currentSubtitleTrackId;
        pp.audioTrackId    = currentAudioTrackId;
        pp.screenMode      = -1;
        // nasPath: NAS 파일이면 저장 (cross-device resume 시 B 단말이 스트림 URL 재생성)
        String nasPath = null;
        if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0
                && PlaylistHolder.currentIndex < PlaylistHolder.playlist.size()) {
            nasPath = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex).nasPath;
        }
        Log.d(TAG, "[Save] pos=" + pos + "ms  key=" + currentSyncKey);
        nasSyncManager.savePosition(pp, currentSyncKey, nasPath);
    }

    private void logTracks() {
        try {
            IMedia m = mediaPlayer.getMedia();
            if (m != null) {
                for (int i = 0; i < m.getTrackCount(); i++) {
                    IMedia.Track t = m.getTrack(i);
                    if (t.type == IMedia.Track.Type.Video) {
                        IMedia.VideoTrack vt = (IMedia.VideoTrack) t;
                        videoW = vt.width;
                        videoH = vt.height;
                        Log.i(TAG, "[VLC] Video Track: " + videoW + "x" + videoH);
                        Log.i(TAG, "[VLC] HW ACCEL: MediaCodec (qti/google) requested.");
                        break;
                    }
                }
                m.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "video track info failed", e);
        }

        MediaPlayer.TrackDescription[] spuTracks = mediaPlayer.getSpuTracks();
        if (spuTracks != null) {
            Log.i(TAG, "[VLC] ALL Subtitle tracks list:");
            for (MediaPlayer.TrackDescription td : spuTracks) {
                Log.i(TAG, "  - [SUB] ID: " + td.id + ", Name: " + td.name);
            }
        }

        MediaPlayer.TrackDescription[] audioTracks = mediaPlayer.getAudioTracks();
        if (audioTracks != null) {
            Log.i(TAG, "[VLC] ALL Audio tracks list:");
            for (MediaPlayer.TrackDescription td : audioTracks) {
                Log.i(TAG, "  - [AUDIO] ID: " + td.id + ", Name: " + td.name);
            }
        }
    }

    private Media openMedia(Uri uri) {
        String scheme = uri.getScheme();
        Media media;
        if ("content".equals(scheme)) {
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new Exception("openFileDescriptor returned null");
                media = new Media(libVLC, pfd.getFileDescriptor());
            } catch (Exception e) {
                Log.e(TAG, "content:// open failed", e);
                // PFD 누수 방지: new Media() 가 throw 해도 pfd 는 열려 있을 수 있음
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                    pfd = null;
                }
                return null;
            }
        } else {
            media = new Media(libVLC, uri);
            // 포털 모드(QuickConnect + _SSID 쿠키)이면 libVLC HTTP 요청에 쿠키 헤더 추가
            String cookieHeader = DsFileApiClient.getStreamCookieHeader();
            if (cookieHeader != null && !cookieHeader.isEmpty()
                    && ("http".equals(scheme) || "https".equals(scheme))) {
                media.addOption(":http-extra-headers=Cookie: " + cookieHeader + "\r\n");
                Log.d("NAS", "libVLC 쿠키 헤더 설정: " + cookieHeader.substring(0, Math.min(40, cookieHeader.length())));
            }
        }
        return media;
    }

    private void handleVlcEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                loadingBar.setVisibility(View.VISIBLE);
                break;
            case MediaPlayer.Event.Buffering: {
                float pct = event.getBuffering();
                long posMs = (mediaPlayer != null) ? mediaPlayer.getTime() : -1;
                if (pct < 100f) {
                    loadingBar.setVisibility(View.VISIBLE);
                    if (bufferingStartMs == 0) {
                        bufferingStartMs = System.currentTimeMillis();
                        bufferingCount++;
                        Log.w(TAG, "[버퍼링 시작 #" + bufferingCount + "] pos=" + posMs + "ms  pct=" + (int)pct + "%");
                    } else {
                        // 진행 중 — 10% 단위로만 로그 (스팸 방지)
                        if ((int)pct % 10 == 0) {
                            Log.d(TAG, "[버퍼링 중 #" + bufferingCount + "] " + (int)pct + "%  pos=" + posMs + "ms");
                        }
                    }
                } else {
                    loadingBar.setVisibility(View.GONE);
                    if (bufferingStartMs > 0) {
                        long stalledMs = System.currentTimeMillis() - bufferingStartMs;
                        Log.w(TAG, "[버퍼링 완료 #" + bufferingCount + "] " + stalledMs + "ms 멈춤  pos=" + posMs + "ms");
                        bufferingStartMs = 0;
                    }
                }
                break;
            }
            case MediaPlayer.Event.Playing:
                loadingBar.setVisibility(View.GONE);
                btnPlayPause.setImageResource(R.drawable.ic_pause);
                if (!tracksLogged) {
                    tracksLogged = true;
                    logTracks();
                    applyPendingSettings();
                    applyDefaultTracksIfNeeded();

                    currentSubtitleTrackId = mediaPlayer.getSpuTrack();
                    currentAudioTrackId = mediaPlayer.getAudioTrack();
                    Log.d(TAG, "[VLC] Initial active tracks: Sub=" + currentSubtitleTrackId + " Audio=" + currentAudioTrackId);

                    // VLC가 Playing 직후 onNewVideoLayout으로 scale을 리셋하므로, 그 이후에 재적용
                    if (currentScreenMode > 0) {
                        handler.postDelayed(() -> applyScreenMode(currentScreenMode), 200);
                    }
                }
                if (pendingSeekMs > 0) {
                    mediaPlayer.setTime(pendingSeekMs);
                    pendingSeekMs = -1;
                }
                handler.removeCallbacks(savePositionTask);
                handler.postDelayed(savePositionTask, SAVE_INTERVAL_MS);
                break;
            case MediaPlayer.Event.Paused:
            case MediaPlayer.Event.Stopped:
                Log.d(TAG, "[Player] " + (event.type == MediaPlayer.Event.Paused ? "일시정지" : "정지"));
                btnPlayPause.setImageResource(R.drawable.ic_play);
                handler.removeCallbacks(savePositionTask);
                saveCurrentPosition();
                showControls();
                break;
            case MediaPlayer.Event.EndReached:
                btnPlayPause.setImageResource(R.drawable.ic_play);
                handler.removeCallbacks(savePositionTask);
                if (currentUriKey != null) {
                    final String key = currentUriKey;
                    dbExecutor.execute(() ->
                            PlaybackDatabase.getInstance(this).playbackDao().clearPosition(key));
                }
                showControls();
                if (PlaylistHolder.playlist != null) {
                    int next = PlaylistHolder.currentIndex + 1;
                    if (next < PlaylistHolder.playlist.size()) {
                        handler.postDelayed(() -> playEpisode(next), 1000);
                    }
                }
                break;
            case MediaPlayer.Event.Vout:
                break;
            case MediaPlayer.Event.EncounteredError:
                if (DsFileApiClient.isNasUrl(currentUriKey)) {
                    // NAS 스트림 오류 → SID 재발급 후 재시도
                    Log.w(TAG, "[NAS] 스트림 오류, SID 재발급 시도");
                    loadingBar.setVisibility(View.VISIBLE);
                    errorText.setVisibility(View.GONE);
                    final long resumePos = mediaPlayer != null ? mediaPlayer.getTime() : 0;
                    final String dbKey = currentDbKey;
                    final String nasFilePath = (PlaylistHolder.playlist != null
                            && PlaylistHolder.currentIndex >= 0)
                            ? PlaylistHolder.playlist.get(PlaylistHolder.currentIndex).nasPath
                            : null;
                    DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
                        @Override public void onResult(String newSid) {
                            if (isFinishing() || isDestroyed()) return;
                            if (nasFilePath == null) {
                                showError("NAS 재연결 실패");
                                return;
                            }
                            String newStream = DsFileApiClient.getStreamUrl(nasFilePath, newSid);
                            currentUriKey = newStream;
                            if (resumePos > 0) pendingSeekMs = resumePos;
                            // 플레이리스트 항목도 갱신
                            if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0) {
                                VideoItem old = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
                                PlaylistHolder.playlist.set(PlaylistHolder.currentIndex,
                                        VideoItem.nasFileWithStream(old.name, old.nasPath, newStream, old.canonicalUri));
                            }
                            // 기존 libVLC/mediaPlayer 해제 후 재초기화 (메모리 누수 방지)
                            if (mediaPlayer != null) {
                                mediaPlayer.setEventListener(null);
                                mediaPlayer.detachViews();
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            if (libVLC != null) {
                                libVLC.release();
                                libVLC = null;
                            }
                            initPlayer(videoLayout, Uri.parse(newStream));
                        }
                        @Override public void onError(String msg) {
                            if (!isFinishing() && !isDestroyed()) showError("NAS 재연결 실패: " + msg);
                        }
                    });
                } else {
                    loadingBar.setVisibility(View.GONE);
                    errorText.setVisibility(View.VISIBLE);
                    errorText.setText("동영상을 재생할 수 없습니다.");
                }
                break;
        }
    }

    private void playEpisode(int newIndex) {
        if (PlaylistHolder.playlist == null) return;
        if (newIndex < 0 || newIndex >= PlaylistHolder.playlist.size()) return;

        saveCurrentPosition();
        resetZoom();

        PlaylistHolder.currentIndex = newIndex;
        VideoItem item = PlaylistHolder.playlist.get(newIndex);

        currentTitle = item.name;
        currentBucketId = item.bucketId; // 버킷 ID 갱신
        tvTitle.setText(currentTitle);
        tracksLogged = false;
        videoW = 0;
        videoH = 0;
        pendingSeekMs          = -1;
        pendingSubtitleId      = Integer.MIN_VALUE;
        pendingAudioId         = Integer.MIN_VALUE;
        currentSubtitleTrackId = Integer.MIN_VALUE;
        currentAudioTrackId    = Integer.MIN_VALUE;
        currentScreenMode      = -1;
        mediaPlayer.setAspectRatio(null);
        mediaPlayer.setScale(0f);
        updateNavButtons();

        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }

        currentDbKey   = item.canonicalUri != null ? item.canonicalUri : item.uri.toString();
        currentSyncKey = deriveSyncKey();
        loadSavedPosition(currentDbKey);

        // TODO: 5G 환경에서 HLS 트랜스코딩 지원 예정 — 현재는 직접 스트리밍.
        {
            // WiFi / 5G / 로컬 파일 → 직접 재생
            currentUriKey = item.uri.toString();
            Media media = openMedia(item.uri);
            if (media != null) {
                mediaPlayer.setMedia(media);
                media.release();
                tryAddExternalSubtitles(item.uri);
                mediaPlayer.play();
            }
        }
    }

    private void updateNavButtons() {
        if (PlaylistHolder.playlist == null || PlaylistHolder.playlist.size() <= 1) {
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
            return;
        }
        btnPrev.setVisibility(PlaylistHolder.currentIndex > 0 ? View.VISIBLE : View.INVISIBLE);
        btnNext.setVisibility(
                PlaylistHolder.currentIndex < PlaylistHolder.playlist.size() - 1
                        ? View.VISIBLE : View.INVISIBLE);
    }

    private void setupControls() {
        FrameLayout root = findViewById(R.id.root);
        root.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return false;
        });
        
        root.setOnClickListener(v -> toggleControls());
        topBar.setOnClickListener(v -> resetHideTimer());
        centerControls.setOnClickListener(v -> resetHideTimer());
        controlsOverlay.setOnClickListener(v -> resetHideTimer());

        btnPlayPause.setOnClickListener(v -> {
            if (mediaPlayer == null) return;
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            else mediaPlayer.play();
            resetHideTimer();
        });

        btnRewind.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                long t = Math.max(0, mediaPlayer.getTime() - 10_000);
                mediaPlayer.setTime(t);
            }
            resetHideTimer();
        });

        btnFastForward.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                long length = mediaPlayer.getLength();
                long t = mediaPlayer.getTime() + 10_000;
                if (length > 0) t = Math.min(t, length);
                mediaPlayer.setTime(t);
            }
            resetHideTimer();
        });

        btnPrev.setOnClickListener(v -> {
            playEpisode(PlaylistHolder.currentIndex - 1);
            resetHideTimer();
        });

        btnNext.setOnClickListener(v -> {
            playEpisode(PlaylistHolder.currentIndex + 1);
            resetHideTimer();
        });

        btnSpeed.setOnClickListener(v -> {
            handler.removeCallbacks(hideControls);
            showSpeedDialog();
        });

        btnRotationLock.setOnClickListener(v -> {
            rotationLocked = !rotationLocked;
            if (rotationLocked) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                btnRotationLock.setBackgroundResource(R.drawable.bg_blue_circle_locked);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                btnRotationLock.setBackgroundResource(R.drawable.bg_blue_circle);
            }
            resetHideTimer();
        });

        btnOptions.setOnClickListener(v -> {
            handler.removeCallbacks(hideControls);
            showOptionsMenu();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) tvCurrentTime.setText(formatTime(progress));
            }
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isSeeking = true;
                handler.removeCallbacks(hideControls);
            }
            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                isSeeking = false;
                if (mediaPlayer != null) mediaPlayer.setTime(bar.getProgress());
                resetHideTimer();
            }
        });
    }

    private void resetZoom() {
        zoomFactor = 1.0f;
        if (videoLayout != null) {
            videoLayout.setScaleX(1.0f);
            videoLayout.setScaleY(1.0f);
        }
    }

    private void toggleControls() {
        if (controlsVisible) {
            topBar.setVisibility(View.GONE);
            centerControls.setVisibility(View.GONE);
            controlsOverlay.setVisibility(View.GONE);
            controlsVisible = false;
        } else {
            showControls();
        }
    }

    private void showControls() {
        topBar.setVisibility(View.VISIBLE);
        centerControls.setVisibility(View.VISIBLE);
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        resetHideTimer();
    }

    private void scheduleHide() {
        handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
    }

    private void resetHideTimer() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
    }

    private static final float[] SPEED_VALUES = {0.75f, 1.0f, 1.25f, 1.5f};
    private static final String[] SPEED_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×"};

    private String formatSpeed(float speed) {
        if (speed == 0.75f) return "0.75×";
        if (speed == 1.25f) return "1.25×";
        if (speed == 1.5f)  return "1.5×";
        return "1.0×";
    }

    private void showSpeedDialog() {
        float current = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getFloat(KEY_PLAYBACK_SPEED, 1.0f);
        int checkedIdx = 2; // 기본 1.0×
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            if (Math.abs(SPEED_VALUES[i] - current) < 0.01f) { checkedIdx = i; break; }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("재생 속도")
                .setSingleChoiceItems(SPEED_LABELS, checkedIdx, (dialog, which) -> {
                    float chosen = SPEED_VALUES[which];
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putFloat(KEY_PLAYBACK_SPEED, chosen).apply();
                    btnSpeed.setText(SPEED_LABELS[which]);
                    if (mediaPlayer != null) mediaPlayer.setRate(chosen);
                    dialog.dismiss();
                    resetHideTimer();
                })
                .setNegativeButton("취소", (dialog, w) -> resetHideTimer())
                .show();
    }

    private void showOptionsMenu() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        String[] items = {"자막 선택", "오디오 선택", "화면 모드 설정", "초기화"};
        final boolean[] subDialogShown = {false};
        new AlertDialog.Builder(this)
                .setTitle("옵션")
                .setItems(items, (dialog, which) -> {
                    subDialogShown[0] = true;
                    switch (which) {
                        case 0: showSubtitleDialog();   break;
                        case 1: showAudioDialog();      break;
                        case 2: showScreenModeDialog(); break;
                        case 3: clearDatabase();        break;
                    }
                })
                .setOnDismissListener(d -> {
                    if (!subDialogShown[0]) resumePlay();
                })
                .show();
    }

    private void resumePlay() {
        if (mediaPlayer != null) mediaPlayer.play();
        showControls();
    }

    private void clearDatabase() {
        dbExecutor.execute(() -> {
            PlaybackDatabase.getInstance(this).playbackDao().clearAll();
            runOnUiThread(() ->
                    Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show());
        });
        resumePlay();
    }

    private void applyPendingSettings() {
        if (mediaPlayer == null) return;
        if (pendingSubtitleId != Integer.MIN_VALUE) {
            mediaPlayer.setSpuTrack(pendingSubtitleId);
            currentSubtitleTrackId = pendingSubtitleId;
            pendingSubtitleId = Integer.MIN_VALUE;
            Log.d(TAG, "[DB] restore subtitle id=" + currentSubtitleTrackId);
        }
        if (pendingAudioId != Integer.MIN_VALUE) {
            mediaPlayer.setAudioTrack(pendingAudioId);
            currentAudioTrackId = pendingAudioId;
            pendingAudioId = Integer.MIN_VALUE;
            Log.d(TAG, "[DB] restore audio id=" + currentAudioTrackId);
        }
        int savedMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_SCREEN_MODE, 0);
        applyScreenMode(savedMode);
        currentScreenMode = savedMode;

        // 저장된 배속 적용
        float savedSpeed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getFloat(KEY_PLAYBACK_SPEED, 1.0f);
        if (savedSpeed != 1.0f) {
            mediaPlayer.setRate(savedSpeed);
        }
    }

    private void showSubtitleDialog() {
        if (mediaPlayer == null) return;
        MediaPlayer.TrackDescription[] all = mediaPlayer.getSpuTracks();
        if (all == null || all.length == 0) {
            Toast.makeText(this, "자막 트랙이 없습니다.", Toast.LENGTH_SHORT).show();
            resumePlay();
            return;
        }

        List<MediaPlayer.TrackDescription> filtered = filterKorean(all);
        final MediaPlayer.TrackDescription[] displayList = filtered.isEmpty() ? all : filtered.toArray(new MediaPlayer.TrackDescription[0]);

        String[] names = new String[displayList.length];
        int currentSubIdx = -1;
        int activeId = mediaPlayer.getSpuTrack();

        for (int i = 0; i < displayList.length; i++) {
            names[i] = (displayList[i].name != null) ? displayList[i].name : "자막 트랙 " + displayList[i].id;
            if (displayList[i].id == activeId) currentSubIdx = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("자막 선택")
                .setSingleChoiceItems(names, currentSubIdx, (dlg, which) -> {
                    int selectedId = displayList[which].id;
                    mediaPlayer.setSpuTrack(selectedId);
                    currentSubtitleTrackId = selectedId;
                    Log.i(TAG, "[SPU] User selected track: " + names[which] + " (id=" + selectedId + ")");
                    saveCurrentPosition();
                    dlg.dismiss();
                })
                .setOnDismissListener(d -> resumePlay())
                .show();
    }

    private void showAudioDialog() {
        if (mediaPlayer == null) return;
        MediaPlayer.TrackDescription[] all = mediaPlayer.getAudioTracks();
        if (all == null || all.length == 0) {
            Toast.makeText(this, "오디오 트랙이 없습니다.", Toast.LENGTH_SHORT).show();
            resumePlay();
            return;
        }

        List<MediaPlayer.TrackDescription> filtered = filterKorean(all);
        final MediaPlayer.TrackDescription[] displayList = filtered.isEmpty() ? all : filtered.toArray(new MediaPlayer.TrackDescription[0]);

        String[] names = new String[displayList.length];
        int currentAudioIdx = -1;
        int activeId = mediaPlayer.getAudioTrack();

        for (int i = 0; i < displayList.length; i++) {
            names[i] = (displayList[i].name != null) ? displayList[i].name : "오디오 트랙 " + displayList[i].id;
            if (displayList[i].id == activeId) currentAudioIdx = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("오디오 선택")
                .setSingleChoiceItems(names, currentAudioIdx, (dlg, which) -> {
                    int selectedId = displayList[which].id;
                    mediaPlayer.setAudioTrack(selectedId);
                    currentAudioTrackId = selectedId;
                    Log.i(TAG, "[Audio] User selected track: " + names[which] + " (id=" + selectedId + ")");
                    saveCurrentPosition();
                    dlg.dismiss();
                })
                .setOnDismissListener(d -> resumePlay())
                .show();
    }

    private void showScreenModeDialog() {
        String[] modes = {"기본", "가로채움", "세로채움"};
        int savedMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_SCREEN_MODE, 0);

        new AlertDialog.Builder(this)
                .setTitle("화면 모드 설정")
                .setSingleChoiceItems(modes, savedMode, (dialog, which) -> {
                    int prevMode = currentScreenMode;
                    applyScreenMode(which);
                    currentScreenMode = which;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putInt(KEY_SCREEN_MODE, which).apply();
                    dialog.dismiss();
                    // 자막 마진이 달라지는 경우(가로↔다른 모드 전환) 미디어 재로드
                    if (prevMode != which && (which == 1 || prevMode == 1)) {
                        reloadCurrentMediaWithSubtitleMargin();
                    } else {
                        resumePlay();
                    }
                })
                .setOnDismissListener(d -> {
                    // 아무것도 선택 안 하고 닫은 경우에만 resumePlay
                    if (mediaPlayer != null && !mediaPlayer.isPlaying()) resumePlay();
                })
                .show();
    }

    private void applyScreenMode(int mode) {
        if (mediaPlayer == null) return;
        android.graphics.Point sz = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getSize(sz);
        int sw = sz.x, sh = sz.y;

        resetZoom();

        switch (mode) {
            case 0:
                mediaPlayer.setAspectRatio(null);
                mediaPlayer.setScale(0f);
                subtitleMargin = 0;
                break;
            case 1:
                mediaPlayer.setAspectRatio(null);
                if (videoW > 0) {
                    float scale = (float) sw / videoW;
                    mediaPlayer.setScale(scale);
                    // 가로 맞춤 시 세로가 화면 밖으로 넘치는 만큼 자막을 위로 올림
                    // overflow/2: 화면 경계까지, + 폰트 높이 추정값(표시 높이의 6%): 글자가 완전히 보이도록
                    float displayH = videoH * scale;
                    subtitleMargin = displayH > sh
                            ? (int) ((displayH - sh) / 2) + (int) (displayH * 0.06f)
                            : 0;
                } else {
                    mediaPlayer.setAspectRatio(sw + ":" + sh);
                    mediaPlayer.setScale(0f);
                    subtitleMargin = 0;
                }
                break;
            case 2:
                mediaPlayer.setAspectRatio(null);
                if (videoH > 0) {
                    mediaPlayer.setScale((float) sh / videoH);
                } else {
                    mediaPlayer.setScale(0f);
                }
                subtitleMargin = 0;
                break;
        }
    }

    private void reloadCurrentMediaWithSubtitleMargin() {
        if (currentUriKey == null) return;

        // 현재 위치와 트랙 정보를 DB에 저장 (loadSavedPosition이 이후 복원)
        saveCurrentPosition();

        // 상태 초기화
        tracksLogged           = false;
        currentSubtitleTrackId = Integer.MIN_VALUE;
        currentAudioTrackId    = Integer.MIN_VALUE;
        pendingSeekMs          = -1;
        pendingSubtitleId      = Integer.MIN_VALUE;
        pendingAudioId         = Integer.MIN_VALUE;

        // libVLC 재초기화 필요 (--sub-margin은 init 옵션이므로)
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener(null);
            mediaPlayer.detachViews();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
            pfd = null;
        }

        // dbExecutor는 단일 스레드이므로 saveCurrentPosition의 DB 쓰기 완료 후
        // initPlayer 내부의 loadSavedPosition이 올바른 위치/트랙을 읽어옴
        initPlayer(videoLayout, Uri.parse(currentUriKey));
    }

    private MediaPlayer.TrackDescription findDefaultTrack(MediaPlayer.TrackDescription[] all) {
        List<MediaPlayer.TrackDescription> pool = filterKorean(all);
        if (pool.isEmpty()) return null;
        for (MediaPlayer.TrackDescription t : pool) {
            if (t.name != null && t.name.toLowerCase().contains("track")) {
                return t;
            }
        }
        return pool.get(0);
    }

    private void applyDefaultTracksIfNeeded() {
        if (mediaPlayer == null) return;
        if (currentSubtitleTrackId == Integer.MIN_VALUE) {
            MediaPlayer.TrackDescription[] spuTracks = mediaPlayer.getSpuTracks();
            if (spuTracks != null && spuTracks.length > 0) {
                MediaPlayer.TrackDescription def = findDefaultTrack(spuTracks);
                if (def != null) {
                    mediaPlayer.setSpuTrack(def.id);
                    currentSubtitleTrackId = def.id;
                }
            }
        }
        if (currentAudioTrackId == Integer.MIN_VALUE) {
            MediaPlayer.TrackDescription[] audioTracks = mediaPlayer.getAudioTracks();
            if (audioTracks != null && audioTracks.length > 0) {
                MediaPlayer.TrackDescription def = findDefaultTrack(audioTracks);
                if (def != null) {
                    mediaPlayer.setAudioTrack(def.id);
                    currentAudioTrackId = def.id;
                }
            }
        }
    }

    private List<MediaPlayer.TrackDescription> filterKorean(MediaPlayer.TrackDescription[] tracks) {
        List<MediaPlayer.TrackDescription> result = new ArrayList<>();
        for (MediaPlayer.TrackDescription t : tracks) {
            if (t.name == null) continue;
            String lower = t.name.toLowerCase();
            if (lower.contains("korea") || t.name.contains("한국")) {
                result.add(t);
            }
        }
        return result;
    }

    private void showError(String msg) {
        loadingBar.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        errorText.setText(msg);
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s)
                     : String.format("%d:%02d", m, s);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (currentScreenMode > 0) {
            videoLayout.setAlpha(0f);
            handler.postDelayed(() -> {
                applyScreenMode(currentScreenMode);
                videoLayout.setAlpha(1f);
            }, 200);
        }
    }

    @Override
    public void onSurfacesCreated(IVLCVout vlcVout) {
        if (currentScreenMode >= 0 && videoW > 0) {
            applyScreenMode(currentScreenMode);
        }
    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vlcVout) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 화면잠금 해제 후 돌아왔을 때 rotation lock 상태 재적용
        if (rotationLocked) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentPosition();
        // onPause에서 flush 시작 → onStop에서 완료 대기 (앱 종료 전 NAS 저장 보장)
        nasFlushing = dbExecutor.submit(nasSyncManager::flushToNasBlocking);
        if (mediaPlayer != null) mediaPlayer.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // onPause에서 시작된 NAS flush가 완료될 때까지 최대 4초 대기 (앱 종료 전 보장)
        if (nasFlushing != null && !nasFlushing.isDone()) {
            try {
                nasFlushing.get(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
            nasFlushing = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveCurrentPosition();
        if (mediaPlayer != null) {
            mediaPlayer.getVLCVout().removeCallback(this);
            mediaPlayer.detachViews();
            mediaPlayer.release();
        }
        if (libVLC != null) libVLC.release();
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
        }
        dbExecutor.shutdown();
    }
}
