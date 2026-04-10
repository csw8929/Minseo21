package com.example.minseo2;

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
    private static final long SAVE_INTERVAL_MS = 5_000;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private ParcelFileDescriptor pfd;
    private boolean tracksLogged = false;
    private int videoW = 0, videoH = 0;

    private boolean rotationLocked = false;

    private String currentUriKey = null;
    /** Room DB / NAS 위치 파일 키. NAS 파일은 canonical URL, 로컬은 currentUriKey 와 동일. */
    private String currentDbKey  = null;
    private String currentTitle = null;
    private String currentBucketId = null; // 현재 폴더 ID
    private static final String PREFS_NAME    = "player_prefs";
    private static final String KEY_SCREEN_MODE = "screen_mode";
    private static final String KEY_LAST_STATE = "last_app_state";

    private NasSyncManager nasSyncManager;

    private long pendingSeekMs       = -1;
    private int  pendingSubtitleId   = Integer.MIN_VALUE;
    private int  pendingAudioId      = Integer.MIN_VALUE;
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
        topBar.setVisibility(View.GONE);
        centerControls.setVisibility(View.GONE);
        controlsOverlay.setVisibility(View.GONE);
        controlsVisible = false;
        // 컨트롤 숨김 시 화면잠금 자동 해제
        if (rotationLocked) {
            rotationLocked = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            btnRotationLock.setBackgroundResource(R.drawable.bg_blue_circle);
        }
    };

    private final Runnable savePositionTask = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
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

    /** NAS 파일이면 canonical URL, 로컬이면 URI 문자열 그대로 반환 */
    private String resolveDbKey(Uri uri) {
        if (PlaylistHolder.playlist != null && PlaylistHolder.currentIndex >= 0) {
            VideoItem vi = PlaylistHolder.playlist.get(PlaylistHolder.currentIndex);
            if (vi.canonicalUri != null) return vi.canonicalUri;
        }
        return uri.toString();
    }

    private void initPlayer(VLCVideoLayout videoLayout, Uri videoUri) {
        currentUriKey = videoUri.toString();
        currentDbKey  = resolveDbKey(videoUri);

        boolean isNetwork = "http".equals(videoUri.getScheme()) || "https".equals(videoUri.getScheme());

        ArrayList<String> options = new ArrayList<>();
        // HW 디코더: mediacodec_ndk(NDK) → mediacodec_jni(JNI) → 소프트웨어 순서로 시도
        options.add("--codec=mediacodec_ndk,mediacodec_jni,none");
        // 색심도: RV32 (32bit, 고색 재현). RV16보다 메모리 대역폭 2× 사용.
        // VLCVideoLayout은 TextureView 기반 → --vout=android-display 사용 불가 (충돌)
        options.add("--android-display-chroma=RV32");
        options.add("--deinterlace=0");               // 0(off): 인터레이스 감지 CPU 비용 제거. 필요 시 -1(auto)
        options.add("--aout=opensles");
        // 캐싱: 로컬 500ms / NAS 3000ms — 과도한 버퍼는 초기 지연만 늘린다
        options.add(isNetwork ? "--network-caching=3000" : "--file-caching=500");
        options.add("--live-caching=300");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        // NOTE: --no-drop-late-frames / --no-skip-frames 제거.
        // 위 두 옵션은 "프레임 절대 드롭 금지"로 지연 누적 시 회복 불가 → 끊김 주 원인
        options.add("--no-audio-time-stretch");
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

        Media media = openMedia(videoUri);
        if (media == null) {
            loadingBar.setVisibility(View.GONE);
            errorText.setVisibility(View.VISIBLE);
            errorText.setText("파일을 열 수 없습니다.");
            return;
        }
        
        // setMedia() must come before tryAddExternalSubtitles() so that
        // mediaPlayer.addSlave() has a media object to attach to
        mediaPlayer.setMedia(media);
        media.release();
        tryAddExternalSubtitles(videoUri);
        mediaPlayer.play();
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
        // NAS URI이면 NasSyncManager (NAS + Room DB 병렬, 더 최신 쪽 사용)
        if (DsFileApiClient.isNasUrl(uriKey)) {
            nasSyncManager.loadPosition(uriKey, pos -> {
                // 메인 스레드에서 호출됨 (두 번 호출될 수 있음: Room DB 먼저, NAS 나중)
                if (pos == null) return;
                if (pos.positionMs > 0) {
                    Log.d(TAG, "[NAS] resume at " + pos.positionMs + "ms");
                    if (tracksLogged && mediaPlayer != null) {
                        mediaPlayer.setTime(pos.positionMs);
                    } else {
                        pendingSeekMs = pos.positionMs;
                    }
                }
                pendingSubtitleId = pos.subtitleTrackId;
                pendingAudioId   = pos.audioTrackId;
                if (tracksLogged) applyPendingSettings();
            });
            return;
        }
        // 로컬 파일: Room DB만 사용
        dbExecutor.execute(() -> {
            PlaybackPosition pos = PlaybackDatabase.getInstance(this)
                    .playbackDao().getPosition(uriKey);
            runOnUiThread(() -> {
                if (pos == null) return;
                if (pos.positionMs > 0) {
                    Log.d(TAG, "[DB] resume at " + pos.positionMs + "ms");
                    if (tracksLogged && mediaPlayer != null) {
                        mediaPlayer.setTime(pos.positionMs);
                    } else {
                        pendingSeekMs = pos.positionMs;
                    }
                }
                pendingSubtitleId = pos.subtitleTrackId;
                pendingAudioId   = pos.audioTrackId;
                if (tracksLogged) applyPendingSettings();
            });
        });
    }

    private void saveCurrentPosition() {
        if (mediaPlayer == null || currentUriKey == null) return;
        if (currentDbKey == null) return; // canonical 키 없으면 null 행 저장 방지
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
        nasSyncManager.savePosition(pp, pp.uri);
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
                return null;
            }
        } else {
            media = new Media(libVLC, uri);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                media.addOption(":network-caching=5000");
            }
        }
        return media;
    }

    private void handleVlcEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                loadingBar.setVisibility(View.VISIBLE);
                break;
            case MediaPlayer.Event.Buffering:
                loadingBar.setVisibility(event.getBuffering() < 100f ? View.VISIBLE : View.GONE);
                break;
            case MediaPlayer.Event.Playing:
                loadingBar.setVisibility(View.GONE);
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
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
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                handler.removeCallbacks(savePositionTask);
                saveCurrentPosition();
                showControls();
                break;
            case MediaPlayer.Event.EndReached:
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
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

        currentUriKey = item.uri.toString();
        currentDbKey  = item.canonicalUri != null ? item.canonicalUri : currentUriKey;
        loadSavedPosition(currentDbKey);

        Media media = openMedia(item.uri);
        if (media != null) {
            mediaPlayer.setMedia(media);
            media.release();
            tryAddExternalSubtitles(item.uri);
            mediaPlayer.play();
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
        if (mediaPlayer != null) mediaPlayer.pause();
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
