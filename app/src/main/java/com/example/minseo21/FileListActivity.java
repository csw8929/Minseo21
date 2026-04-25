package com.example.minseo21;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import android.app.Activity;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.minseo21.xr.XrFullSpaceLauncher;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileListActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "player_prefs";
    private static final String KEY_LAST_STATE = "last_app_state";
    private static boolean hasCheckedResumeThisSession = false;

    // XR Full Space launcher — non-XR 단말에선 일반 startActivity 와 동일 동작.
    private XrFullSpaceLauncher xrLauncher;

    // ── 로컬 ──────────────────────────────────────────────────────────────────
    private FileItemAdapter localAdapter;
    private RecyclerView rvLocal;
    private TextView tvPath;
    private TextView tvEmpty;
    private String currentBucketId   = null;
    private String currentBucketName = null;
    private List<VideoItem> currentVideoList = new ArrayList<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // ── NAS ──────────────────────────────────────────────────────────────────
    private NasFileAdapter nasAdapter;
    private View nasLoadingView;
    private View nasErrorView;
    private TextView tvNasError;
    private RecyclerView rvNas;
    private ImageButton btnNasSettings;
    private String nasSid = null;
    private final Deque<String> nasPathStack = new ArrayDeque<>();
    private NasCredentialStore nasCredStore;
    private ProgressBar pbResumeCheck;
    private final Handler resumeTimeoutHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean resumeCheckCompleted = new AtomicBoolean(false);

    // ── 즐겨찾기 ───────────────────────────────────────────────────────────
    private FavoriteAdapter favAdapter;
    private RecyclerView rvFavorites;
    private TextView tvFavEmpty;

    // ── 탭 ──────────────────────────────────────────────────────────────────
    private TabLayout tabLayout;
    private static final int TAB_LOCAL = 0;
    private static final int TAB_NAS   = 1;
    private static final int TAB_FAV   = 2;
    private int currentTab = TAB_LOCAL;

    private final ActivityResultLauncher<String> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) { loadBucketList(); checkLastPlayback(); }
                else Toast.makeText(this, "저장소 접근 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<Intent> nasSetupLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // 설정 저장 완료 — 새 인증 정보로 NAS 재연결
                    nasSid = null;
                    nasPathStack.clear();
                    if (currentTab == TAB_NAS) connectNas();
                }
            });

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // NAS 경로 스택 저장: Activity가 백그라운드에서 소멸 후 복원될 때
        // nasPathStack이 비면 Back 핸들러가 로컬 탭으로 이동하는 버그 방지
        if (!nasPathStack.isEmpty()) {
            outState.putStringArrayList("nasPathStack", new ArrayList<>(nasPathStack));
        }
        // 로컬 폴더 상태 저장: 재생 중 Activity가 파괴되어도 복원 시 해당 폴더로 복귀
        if (currentBucketId != null) {
            outState.putString("currentBucketId", currentBucketId);
            outState.putString("currentBucketName", currentBucketName);
        }
        outState.putInt("currentTab", currentTab);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        xrLauncher = new XrFullSpaceLauncher(this);

        // NAS 경로 스택 복원 (onSaveInstanceState에서 저장됨)
        if (savedInstanceState != null) {
            ArrayList<String> savedStack = savedInstanceState.getStringArrayList("nasPathStack");
            if (savedStack != null && !savedStack.isEmpty()) {
                nasPathStack.addAll(savedStack);
            }
            // 로컬 폴더 상태 복원 — checkPermissionAndLoad()에서 이 값이 있으면 해당 폴더로 복귀
            currentBucketId   = savedInstanceState.getString("currentBucketId");
            currentBucketName = savedInstanceState.getString("currentBucketName");
            currentTab        = savedInstanceState.getInt("currentTab", TAB_LOCAL);
        }

        tvPath       = findViewById(R.id.tvPath);
        tvEmpty      = findViewById(R.id.tvEmpty);
        rvFavorites  = findViewById(R.id.rvFavorites);
        tvFavEmpty   = findViewById(R.id.tvFavEmpty);
        nasLoadingView = findViewById(R.id.nasLoadingView);
        nasErrorView   = findViewById(R.id.nasErrorView);
        tvNasError     = findViewById(R.id.tvNasError);
        rvNas          = findViewById(R.id.rvNas);
        tabLayout      = findViewById(R.id.tabLayout);
        btnNasSettings  = findViewById(R.id.btnNasSettings);
        pbResumeCheck   = findViewById(R.id.pbResumeCheck);

        // NAS 인증 정보 초기화 (저장된 값 우선, 없으면 DsFileConfig fallback)
        nasCredStore = new NasCredentialStore(this);
        DsFileApiClient.init(nasCredStore);
        DsFileApiClient.startNetworkMonitoring(this);

        // NAS 인증 정보 있으면 즉시 스피너 표시 (첫 프레임 렌더 전에 설정)
        if (nasCredStore.hasCredentials()) {
            pbResumeCheck.setVisibility(View.VISIBLE);
        }

        btnNasSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, NasSetupActivity.class);
            intent.putExtra(NasSetupActivity.EXTRA_EDIT_MODE, true);
            nasSetupLauncher.launch(intent);
        });

        // 로컬 RecyclerView
        rvLocal = findViewById(R.id.recyclerView);
        rvLocal.setLayoutManager(new LinearLayoutManager(this));
        rvLocal.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        localAdapter = new FileItemAdapter(item -> {
            if (item.type == VideoItem.TYPE_FOLDER) {
                loadVideosInBucket(item.bucketId, item.name);
            } else {
                int idx = currentVideoList.indexOf(item);
                PlaylistHolder.playlist = new ArrayList<>(currentVideoList);
                PlaylistHolder.currentIndex = idx >= 0 ? idx : 0;
                playVideo(item.uri, item.name);
            }
        });
        rvLocal.setAdapter(localAdapter);

        // NAS RecyclerView
        rvNas.setLayoutManager(new LinearLayoutManager(this));
        rvNas.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        nasAdapter = new NasFileAdapter(this::onNasItemClick);

        // 즐겨찾기 RecyclerView
        rvFavorites.setLayoutManager(new LinearLayoutManager(this));
        rvFavorites.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        favAdapter = new FavoriteAdapter(new FavoriteAdapter.Listener() {
            @Override public void onClick(Favorite fav) { playFavorite(fav); }
            @Override public void onLongClick(Favorite fav) { confirmDeleteFavorite(fav); }
        });
        rvFavorites.setAdapter(favAdapter);

        // 재시도 버튼
        Button btnRetry = findViewById(R.id.btnNasRetry);
        btnRetry.setOnClickListener(v -> connectNas());

        // 탭 설정
        tabLayout.addTab(tabLayout.newTab().setText("로컬"));
        tabLayout.addTab(tabLayout.newTab().setText("NAS"));
        tabLayout.addTab(tabLayout.newTab().setText("즐겨찾기"));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                if (currentTab == TAB_LOCAL) showLocalTab();
                else if (currentTab == TAB_NAS) showNasTab();
                else showFavTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {
                // 즐겨찾기 탭에서 벗어날 때 ResumeSnapshot 업데이트 훅 해제
                if (tab.getPosition() == TAB_FAV) ResumeSnapshot.onUpdate = null;
            }
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 뒤로 가기
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTab == TAB_FAV) {
                    // 즐겨찾기 탭 → 로컬 탭으로
                    tabLayout.selectTab(tabLayout.getTabAt(TAB_LOCAL));
                } else if (currentTab == TAB_NAS) {
                    if (nasPathStack.size() > 1) {
                        nasPathStack.pop();
                        loadNasFolder(nasPathStack.peek());
                    } else {
                        // NAS 탭 루트 → 로컬 탭으로
                        tabLayout.selectTab(tabLayout.getTabAt(TAB_LOCAL));
                    }
                } else {
                    if (currentBucketId != null) {
                        loadBucketList();
                    } else {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        });

        checkPermissionAndLoad();

        // 복원된 탭이 로컬이 아니면 그 탭을 선택 (onTabSelected 리스너가 해당 뷰를 표시)
        if (currentTab != TAB_LOCAL) {
            tabLayout.selectTab(tabLayout.getTabAt(currentTab));
        }
    }

    // ── 로컬 탭 ──────────────────────────────────────────────────────────────

    private void showLocalTab() {
        btnNasSettings.setVisibility(View.GONE);
        tvPath.setText(currentBucketId == null ? "내장저장공간"
                : "내장저장공간 / " + currentBucketName);
        findViewById(R.id.recyclerView).setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(currentVideoList.isEmpty() && currentBucketId != null
                ? View.VISIBLE : View.GONE);
        rvNas.setVisibility(View.GONE);
        nasLoadingView.setVisibility(View.GONE);
        nasErrorView.setVisibility(View.GONE);
        rvFavorites.setVisibility(View.GONE);
        tvFavEmpty.setVisibility(View.GONE);
    }

    private void checkPermissionAndLoad() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            // Activity 재생성으로 복원된 폴더가 있으면 그 폴더로, 아니면 bucket 목록으로
            if (currentBucketId != null) {
                loadVideosInBucket(currentBucketId,
                        currentBucketName != null ? currentBucketName : "");
            } else {
                loadBucketList();
            }
            checkLastPlayback();
        } else {
            permLauncher.launch(perm);
        }
    }

    /**
     * 앱 시작 시 이어보기 다이얼로그.
     * 로컬 last_app_state 뿐 아니라 NAS 위치 캐시도 확인해서
     * 다른 단말에서 재생 중이던 영상도 resume 제안.
     */
    private void checkLastPlayback() {
        if (hasCheckedResumeThisSession) return;
        hasCheckedResumeThisSession = true;

        int lastState = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_LAST_STATE, 0);
        boolean hasLocalState = (lastState == 1);
        boolean hasNasCreds   = nasCredStore.hasCredentials();

        // 로컬 이력도 없고 NAS 인증도 없으면 패스
        if (!hasLocalState && !hasNasCreds) {
            hideResumeSpinner();
            return;
        }

        if (hasNasCreds) {
            // 5초 타임아웃: NAS 응답 없으면 로컬 이력으로 폴백
            resumeTimeoutHandler.postDelayed(() -> {
                if (!resumeCheckCompleted.getAndSet(true)) {
                    hideResumeSpinner();
                    dbExecutor.execute(() -> {
                        PlaybackPosition local = PlaybackDatabase.getInstance(this)
                                .playbackDao().getLastPosition();
                        if (local != null && local.uri != null)
                            runOnUiThread(() -> showResumeDialog(local, null));
                    });
                }
            }, 5000);
        }

        dbExecutor.execute(() -> {
            // ResumeSnapshot LOCAL 캡처 — 프로세스 시작 시 1회만 (hasLocalState 무관하게 시도)
            if (ResumeSnapshot.local == null) {
                PlaybackPosition localOnly = PlaybackDatabase.getInstance(this)
                        .playbackDao().getLastLocalPosition();
                if (localOnly != null && localOnly.uri != null) {
                    ResumeSnapshot.local = recentFrom(localOnly, false);
                    Runnable cb = ResumeSnapshot.onUpdate;
                    if (cb != null) runOnUiThread(cb);
                }
            }

            PlaybackPosition localLast = hasLocalState
                    ? PlaybackDatabase.getInstance(this).playbackDao().getLastPosition()
                    : null;
            if (localLast != null && localLast.uri == null) localLast = null;

            if (!hasNasCreds) {
                // NAS 인증 정보 없음 → 로컬 이력만 표시
                final PlaybackPosition ll = localLast;
                if (ll != null) runOnUiThread(() -> showResumeDialog(ll, null));
                else runOnUiThread(this::hideResumeSpinner);
                return;
            }

            // NAS 인증 정보 있음 → SID 확보 후 positions 다운로드
            final PlaybackPosition finalLocal = localLast;
            String sid = nasSid != null ? nasSid : DsFileApiClient.getCachedSid();
            if (sid != null) {
                fetchNasAndShowResume(finalLocal, sid);
            } else {
                DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
                    @Override public void onResult(String newSid) {
                        if (isFinishing() || isDestroyed()) return;
                        nasSid = newSid;
                        fetchNasAndShowResume(finalLocal, newSid);
                    }
                    @Override public void onError(String msg) {
                        // 로그인 실패 → 로컬 이력만
                        if (finalLocal != null)
                            runOnUiThread(() -> showResumeDialog(finalLocal, null));
                        else
                            runOnUiThread(FileListActivity.this::hideResumeSpinner);
                    }
                });
            }
        });
    }

    /**
     * NAS positions 다운로드 후 로컬 이력과 비교해서 더 최신인 쪽으로 다이얼로그 표시.
     * NAS가 더 최신 → 로컬에서 같은 파일 검색 후 resume 결정
     * 로컬이 최신 or NAS 없음 → 기존 이어보기 다이얼로그
     */
    private void fetchNasAndShowResume(PlaybackPosition localLast, String sid) {
        DsFileApiClient.downloadUserPositions(new DsFileApiClient.Callback<JSONObject>() {
            @Override public void onResult(JSONObject positions) {
                // 싱글턴 캐시 주입 — MainActivity 가 같은 JSON 재사용해 중복 다운로드 제거 (ISSUE-002)
                NasSyncManager.getInstance(FileListActivity.this).seedCache(positions);

                // ResumeSnapshot REMOTE 캡처 — resume 분기와 독립. 5초 타임아웃 후에도 동작.
                captureNasSnapshot(positions);

                NasSyncManager.NasResumeEntry nasEntry =
                        NasSyncManager.findMostRecentEntry(positions);

                long localTime = (localLast != null) ? localLast.updatedAt : 0;
                long nasTime   = (nasEntry  != null) ? nasEntry.updatedAt  : 0;

                // 같은 단말이 저장한 항목이면 cross-device 분기 건너뜀
                String thisDeviceId = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                boolean isSameDevice = nasEntry != null
                        && nasEntry.deviceId != null
                        && nasEntry.deviceId.equals(thisDeviceId);

                if (nasTime > localTime && !isSameDevice) {
                    // NAS가 더 최신 + 다른 단말 → 로컬에 같은 파일 있는지 먼저 확인
                    findLocalFileAndResume(nasEntry, localLast);
                } else if (nasTime > localTime && isSameDevice) {
                    // NAS가 더 최신 + 같은 단말 (앱 재설치 등으로 Room DB 초기화된 경우)
                    findLocalFileAndResume(nasEntry, localLast);
                } else if (localLast != null) {
                    // 로컬이 최신 or NAS 항목 없음 → 기존 이어보기
                    boolean isNas = DsFileApiClient.isNasUrl(localLast.uri);
                    String nasUrl = isNas
                            ? DsFileApiClient.canonicalToStream(localLast.uri, sid)
                            : null;
                    runOnUiThread(() -> showResumeDialog(localLast, nasUrl));
                } else {
                    // 둘 다 없으면 다이얼로그 없이 파일 리스트
                    runOnUiThread(FileListActivity.this::hideResumeSpinner);
                }
            }
            @Override public void onError(String msg) {
                // NAS 다운로드 실패 → 로컬 이력만
                if (localLast != null)
                    runOnUiThread(() -> showResumeDialog(localLast, null));
                else
                    runOnUiThread(FileListActivity.this::hideResumeSpinner);
            }
        });
    }

    /**
     * NAS 최신 항목 기준으로 로컬에서 같은 파일명을 검색하고 결과에 따라 분기.
     *   - 로컬에 파일 있음 → cross-device resume 다이얼로그 → 로컬 재생
     *   - 없고 localLast 있음 → 이전 로컬 마지막 이어보기 다이얼로그
     *   - 둘 다 없음 → 다이얼로그 없이 파일 리스트
     * downloadUserPositions 콜백은 메인 스레드이므로 MediaStore 검색은 dbExecutor로 위임.
     */
    private void findLocalFileAndResume(NasSyncManager.NasResumeEntry nasEntry,
                                        PlaybackPosition localLast) {
        dbExecutor.execute(() -> {
            String fileName = nasEntry.fileName();
            Uri localUri = null;
            try (Cursor c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{ MediaStore.Video.Media._ID },
                    MediaStore.Video.Media.DISPLAY_NAME + " = ?",
                    new String[]{ fileName }, null)) {
                if (c != null && c.moveToFirst()) {
                    localUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                }
            } catch (Exception e) {
                Log.w("FileList", "로컬 파일 검색 오류: " + e.getMessage());
            }

            if (localUri != null) {
                // 로컬에 파일 있음 → cross-device resume 다이얼로그
                final Uri finalUri = localUri;
                runOnUiThread(() -> showCrossDeviceResumeDialog(nasEntry.fileName(), finalUri));
            } else if (localLast != null) {
                // 로컬에 파일 없음 + 이전 로컬 이력 있음 → 이전 이어보기로 폴백
                runOnUiThread(() -> showResumeDialog(localLast, null));
            } else {
                // 둘 다 없으면 → 파일 리스트
                runOnUiThread(FileListActivity.this::hideResumeSpinner);
            }
        });
    }

    /**
     * Cross-device resume 다이얼로그.
     * 로컬에서 같은 파일을 찾은 경우에만 호출됨 → 항상 로컬 재생.
     */
    private void showCrossDeviceResumeDialog(String fileName, Uri localUri) {
        if (isFinishing() || isDestroyed()) return;
        hideResumeSpinner();
        new AlertDialog.Builder(this)
                .setTitle("이어서 볼까요?")
                .setMessage("다른 단말에서 재생 중이던 영상입니다.\n[로컬 파일로 재생]\n\n" + fileName)
                .setPositiveButton("예", (dialog, which) ->
                        startLocalWithFolderView(localUri, fileName, null, null))
                .setNegativeButton("아니오", null)
                .show();
    }

    /** 이어보기 다이얼로그 (이 단말에서 마지막으로 재생하던 영상). nasStreamUrl이 null이면 로컬 파일로 취급. */
    private void showResumeDialog(PlaybackPosition last, String nasStreamUrl) {
        hideResumeSpinner();
        boolean isNas = nasStreamUrl != null;
        String playUri = isNas ? nasStreamUrl : last.uri;
        final String finalUri = playUri;

        new AlertDialog.Builder(this)
                .setTitle("재생할까요?")
                .setMessage("마지막으로 재생하던 영상을 이어서 봅니다.\n"
                        + (isNas ? "[NAS에서 스트리밍]" : "[로컬 파일로 재생]")
                        + "\n\n"
                        + (last.name != null ? last.name : "알 수 없는 파일"))
                .setPositiveButton("예", (dialog, which) -> {
                    if (isNas) {
                        String nasPath = Uri.parse(finalUri).getQueryParameter("path");
                        String sid = nasSid != null ? nasSid : DsFileApiClient.getCachedSid();
                        if (nasPath != null && sid != null) {
                            // 1회 recovery: 부모 폴더 목록 조회로 플레이리스트 복원, 실패 시 단일 항목 폴백
                            startNasWithFolderRecovery(nasPath, last.uri, last.name, sid);
                        } else {
                            if (nasPath != null) {
                                VideoItem vi = VideoItem.nasFileWithStream(
                                        last.name != null ? last.name : "",
                                        nasPath, finalUri, last.uri);
                                List<VideoItem> pl = new ArrayList<>();
                                pl.add(vi);
                                PlaylistHolder.playlist = pl;
                                PlaylistHolder.currentIndex = 0;
                            }
                            tabLayout.selectTab(tabLayout.getTabAt(TAB_NAS));
                            boolean useTranscode = !DsFileApiClient.isWifi(FileListActivity.this);
                            playNasVideo(Uri.parse(finalUri), last.name, useTranscode);
                        }
                    } else {
                        // 로컬도 즐겨찾기와 동일하게 폴더 복원 후 재생
                        startLocalWithFolderView(Uri.parse(finalUri), last.name, last.bucketId, null);
                    }
                })
                .setNegativeButton("아니오", (d, w) ->
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putInt(KEY_LAST_STATE, 0).apply())
                .show();
    }

    private void loadBucketList() {
        currentBucketId = null; currentBucketName = null; currentVideoList.clear();
        tvPath.setText("내장저장공간");
        String[] proj = { MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME };
        Map<String, String> buckets = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " ASC")) {
            if (c != null) {
                int colId = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                while (c.moveToNext()) {
                    String id = c.getString(colId), name = c.getString(colName);
                    if (id != null && !buckets.containsKey(id))
                        buckets.put(id, name != null ? name : id);
                }
            }
        }
        List<VideoItem> items = new ArrayList<>();
        for (Map.Entry<String, String> e : buckets.entrySet())
            items.add(VideoItem.folder(e.getValue(), e.getKey()));
        showLocalItems(items, items.isEmpty() ? "동영상 폴더가 없습니다." : null);
    }

    private List<VideoItem> queryVideosInBucket(String bucketId, String bucketDisplayName) {
        String[] proj = { MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.DATE_MODIFIED };
        List<VideoItem> items = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Video.Media.BUCKET_ID + " = ?", new String[]{bucketId}, null)) {
            if (c != null) {
                int colId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int colSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int colDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
                while (c.moveToNext()) {
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(colId));
                    items.add(VideoItem.video(c.getString(colName), bucketId, bucketDisplayName,
                            uri, c.getLong(colSize), c.getLong(colDate)));
                }
            }
        }
        items.sort((a, b) -> {
            int ea = extractEpisodeNumber(a.name), eb = extractEpisodeNumber(b.name);
            return ea != eb ? Integer.compare(ea, eb) : a.name.compareTo(b.name);
        });
        return items;
    }

    /** URI → {bucketId, bucketDisplayName} 복구. 실패 시 null. */
    private String[] lookupBucketFromUri(Uri uri) {
        String[] proj = { MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME };
        try (Cursor c = getContentResolver().query(uri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String id = c.getString(0);
                String display = c.getString(1);
                if (id != null) return new String[]{id, display};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void loadVideosInBucket(String bucketId, String bucketName) {
        currentBucketId = bucketId; currentBucketName = bucketName;
        tvPath.setText("내장저장공간 / " + bucketName);
        currentVideoList = queryVideosInBucket(bucketId, bucketName);
        showLocalItems(currentVideoList, currentVideoList.isEmpty() ? "동영상 파일이 없습니다." : null);
    }

    private void showLocalItems(List<VideoItem> items, String emptyMsg) {
        localAdapter.setItems(items);
        tvEmpty.setText(emptyMsg != null ? emptyMsg : "");
        tvEmpty.setVisibility(emptyMsg != null ? View.VISIBLE : View.GONE);
    }

    // ── NAS 탭 ──────────────────────────────────────────────────────────────

    private void showNasTab() {
        btnNasSettings.setVisibility(View.VISIBLE);
        findViewById(R.id.recyclerView).setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);
        rvFavorites.setVisibility(View.GONE);
        tvFavEmpty.setVisibility(View.GONE);

        // 인증 정보가 없으면 설정 화면 먼저
        if (!nasCredStore.hasCredentials()) {
            Intent intent = new Intent(this, NasSetupActivity.class);
            nasSetupLauncher.launch(intent);
            return;
        }

        if (nasSid == null) {
            nasSid = DsFileApiClient.getCachedSid(); // 화면 회전 후 복구
        }
        if (nasSid == null) {
            connectNas();
        } else if (!nasPathStack.isEmpty()) {
            if (nasAdapter.getItemCount() == 0) {
                // 스택은 복원됐지만 어댑터가 비어 있음 (Activity 재생성 후 SID 유효)
                // → 현재 폴더 내용 재로드
                loadNasFolder(nasPathStack.peek());
            } else {
                // 이미 탐색 중 — 현재 경로 그대로 표시
                rvNas.setVisibility(View.VISIBLE);
                nasLoadingView.setVisibility(View.GONE);
                nasErrorView.setVisibility(View.GONE);
                updateNasPath();
            }
        } else {
            connectNas();
        }
    }

    private void connectNas() {
        // 복원된 스택이 있으면 로그인 후 해당 폴더로 이동 (Activity 재생성 시 경로 복원)
        final List<String> restoredStack = nasPathStack.isEmpty()
                ? null : new ArrayList<>(nasPathStack);
        showNasLoading("NAS 연결 중…");
        DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
            @Override public void onResult(String sid) {
                if (isFinishing() || isDestroyed()) return;
                nasSid = sid;
                nasPathStack.clear();
                if (restoredStack != null && !restoredStack.isEmpty()) {
                    // 복원된 스택 그대로 사용 → 이전에 있던 폴더로 직접 이동
                    nasPathStack.addAll(restoredStack);
                } else {
                    nasPathStack.push(DsFileApiClient.getBasePath());
                }
                loadNasFolder(nasPathStack.peek());
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                nasSid = null;
                showNasError(msg);
            }
        });
    }

    private void loadNasFolder(String path) {
        showNasLoading("불러오는 중…");
        DsFileApiClient.listFolder(path, nasSid, new DsFileApiClient.Callback<List<VideoItem>>() {
            @Override public void onResult(List<VideoItem> items) {
                if (isFinishing() || isDestroyed()) return;
                nasAdapter.setItems(items);
                rvNas.setAdapter(nasAdapter);
                rvNas.setVisibility(View.VISIBLE);
                nasLoadingView.setVisibility(View.GONE);
                nasErrorView.setVisibility(View.GONE);
                updateNasPath();
                if (items.isEmpty()) {
                    tvNasError.setText("파일 없음");
                    nasErrorView.setVisibility(View.VISIBLE);
                    findViewById(R.id.btnNasRetry).setVisibility(View.GONE);
                }
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                // listFolder 내부에서 SID 105/106 재시도를 이미 수행함
                // 여기까지 오면 재시도도 실패한 것 → 에러 표시만 (재귀 방지)
                nasSid = null;
                showNasError(msg);
            }
        });
    }

    private void onNasItemClick(VideoItem item) {
        if (item.type == VideoItem.TYPE_FOLDER) {
            nasPathStack.push(item.bucketId); // bucketId = NAS 경로
            loadNasFolder(item.bucketId);
        } else {
            // 플레이리스트: 직접 스트림 URL 로 구성 (playlist navigation 용)
            List<VideoItem> allFiles = nasAdapter.getAllFiles();
            List<VideoItem> playlist = new ArrayList<>();
            int targetIdx = 0;
            for (int i = 0; i < allFiles.size(); i++) {
                VideoItem f = allFiles.get(i);
                String streamUrl = DsFileApiClient.getStreamUrl(f.nasPath, nasSid);
                playlist.add(VideoItem.nasFileWithStream(f.name, f.nasPath, streamUrl, f.canonicalUri));
                if (f.nasPath != null && f.nasPath.equals(item.nasPath)) targetIdx = i;
            }
            PlaylistHolder.playlist = playlist;
            PlaylistHolder.currentIndex = targetIdx;

            final int finalIdx = targetIdx;
            final String directUrl = DsFileApiClient.getStreamUrl(item.nasPath, nasSid);

            // 셀룰러(5G/LTE) 환경에서는 HLS 트랜스코딩 경로 사용
            boolean useTranscode = !DsFileApiClient.isWifi(this);
            playNasVideo(Uri.parse(directUrl), item.name, useTranscode);
        }
    }

    private void playNasVideo(Uri uri, String title, boolean useTranscode) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.putExtra("title", title);
        intent.putExtra("useTranscode", useTranscode);
        xrLauncher.startActivity(this, intent);
    }

    /** 파일 목록 뷰 다시 표시 (HLS 로딩 완료/실패 후 호출) */
    private void showNasList() {
        nasLoadingView.setVisibility(View.GONE);
        nasErrorView.setVisibility(View.GONE);
        rvNas.setVisibility(View.VISIBLE);
        updateNasPath();
    }

    private void showNasLoading(String msg) {
        rvNas.setVisibility(View.GONE);
        nasErrorView.setVisibility(View.GONE);
        ((TextView) nasLoadingView.findViewById(R.id.tvNasLoading)).setText(msg);
        nasLoadingView.setVisibility(View.VISIBLE);
        tvPath.setText("NAS");
    }

    private void showNasError(String msg) {
        rvNas.setVisibility(View.GONE);
        nasLoadingView.setVisibility(View.GONE);
        tvNasError.setText("연결 실패: " + msg);
        nasErrorView.setVisibility(View.VISIBLE);
        findViewById(R.id.btnNasRetry).setVisibility(View.VISIBLE);
        tvPath.setText("NAS — 오프라인");
    }

    private void updateNasPath() {
        String path = nasPathStack.isEmpty() ? "NAS" : "NAS / " + nasPathStack.peek()
                .replace(DsFileApiClient.getBasePath(), "")
                .replace("/", " / ")
                .replaceAll("^ / ", "");
        tvPath.setText(path.isEmpty() ? "NAS" : "NAS" + path);
    }

    private void hideResumeSpinner() {
        resumeCheckCompleted.set(true);
        resumeTimeoutHandler.removeCallbacksAndMessages(null);
        if (pbResumeCheck != null) pbResumeCheck.setVisibility(View.GONE);
    }

    // ── 즐겨찾기 탭 ──────────────────────────────────────────────────────────

    private void showFavTab() {
        btnNasSettings.setVisibility(View.GONE);
        findViewById(R.id.recyclerView).setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);
        rvNas.setVisibility(View.GONE);
        nasLoadingView.setVisibility(View.GONE);
        nasErrorView.setVisibility(View.GONE);
        tvPath.setText("즐겨찾기");
        rvFavorites.setVisibility(View.VISIBLE);
        // NAS 콜백 늦게 도착 시 자동 갱신 훅
        ResumeSnapshot.onUpdate = this::loadFavorites;
        loadFavorites();

        // REMOTE 슬롯은 탭 진입마다 NAS에서 강제 재 fetch → 최신 항목 반영
        NasSyncManager nas = NasSyncManager.getInstance(this);
        nas.forceRefresh(() -> {
            JSONObject snapshot = nas.getPositionsSnapshot();
            if (snapshot != null) captureNasSnapshot(snapshot);
        });
    }

    private void loadFavorites() {
        dbExecutor.execute(() -> {
            List<Favorite> favs = PlaybackDatabase.getInstance(this).favoriteDao().getAll();

            List<Favorite> combined = new ArrayList<>();
            Favorite snapLocal = ResumeSnapshot.local;
            Favorite snapNas   = ResumeSnapshot.nas;
            if (snapLocal != null) combined.add(snapLocal);  // [LAST]   빨간 별표
            if (snapNas   != null) combined.add(snapNas);    // [REMOTE] 빨간 별표 (중복 허용)
            combined.addAll(favs);

            runOnUiThread(() -> {
                favAdapter.setItems(combined);
                tvFavEmpty.setVisibility(combined.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /**
     * NAS positions.json 결과로 REMOTE 슬롯 스냅샷을 갱신.
     *
     * REMOTE 의미: positions.json에서 "이 단말이 아닌 다른 단말"이 올린 항목 중 updatedAt이 가장 큰 1개.
     *   - nasPath 있음  → NAS 스트리밍 Favorite
     *   - nasPath 없음  → 다른 단말의 로컬 재생 기록. 이 단말 MediaStore에 동일 이름 파일이 있으면
     *                     로컬로 재생 가능 (isNas=false). 없으면 skip → 다음 candidate.
     * 이 단말의 entry는 LAST 슬롯이 이미 커버하므로 제외한다 (deviceId 일치 시 skip).
     * deviceId가 null인 legacy entry는 타 단말 가정으로 포함 (보수적 노출).
     *
     * 전부 skip되면 REMOTE 슬롯을 비운다. 매 호출마다 덮어쓴다.
     */
    private void captureNasSnapshot(JSONObject positions) {
        java.util.List<NasSyncManager.NasResumeEntry> candidates =
                NasSyncManager.listEntriesDescending(positions);
        if (candidates.isEmpty()) {
            runOnUiThread(() -> setNasSnapshot(null));
            return;
        }
        final String myDeviceId = NasSyncManager.getInstance(this).getDeviceId();
        // dbExecutor가 아직 살아있으면 사용 — 과거 경로 호환 위해 유지.
        // tryCaptureOne이 MediaStore를 조회할 수 있으므로 백그라운드 유지.
        if (dbExecutor.isShutdown()) return;
        try {
            dbExecutor.execute(() -> {
                Favorite picked = null;
                for (NasSyncManager.NasResumeEntry entry : candidates) {
                    if (entry.deviceId != null && entry.deviceId.equals(myDeviceId)) continue;
                    Favorite f = tryCaptureOne(entry);
                    if (f != null) { picked = f; break; }
                }
                final Favorite result = picked;
                runOnUiThread(() -> setNasSnapshot(result));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity가 이미 destroy된 경우 — 이 세션의 스냅샷은 건드리지 않음
        }
    }

    /**
     * 타 단말 positions.json entry → REMOTE 슬롯 Favorite.
     *   - nasPath 있음: NAS 스트리밍으로 합성 (isNas=true)
     *   - nasPath 없음: 이 단말 MediaStore에서 동일 이름 찾으면 로컬 합성 (isNas=false), 없으면 null
     * 두 경우 모두 isRemoteSlot=true로 마킹 → UI는 [REMOTE] 프리픽스를 보여준다.
     */
    private Favorite tryCaptureOne(NasSyncManager.NasResumeEntry entry) {
        if (entry.nasPath != null) {
            Favorite f = synthNasFavorite(entry);
            f.isRemoteSlot = true;
            return f;
        }
        Uri localUri = lookupLocalUriByName(entry.fileName());
        if (localUri == null) return null;
        Favorite f = synthLocalFavorite(entry, localUri);
        f.isRemoteSlot = true;
        return f;
    }

    /** MediaStore에서 DISPLAY_NAME 일치하는 첫 비디오 URI. */
    private Uri lookupLocalUriByName(String fileName) {
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Video.Media._ID },
                MediaStore.Video.Media.DISPLAY_NAME + " = ?",
                new String[]{ fileName }, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception e) {
            Log.w("FileList", "lookupLocalUriByName 오류: " + e.getMessage());
        }
        return null;
    }

    /** NAS 스냅샷 세팅 + 즐겨찾기 탭 열려있으면 재갱신. 메인 스레드에서 호출. */
    private void setNasSnapshot(Favorite f) {
        ResumeSnapshot.nas = f;
        Runnable cb = ResumeSnapshot.onUpdate;
        if (cb != null) cb.run();
    }

    /** NasResumeEntry → 로컬 Favorite (이 단말 MediaStore에 파일이 있는 경우). */
    private Favorite synthLocalFavorite(NasSyncManager.NasResumeEntry entry, Uri localUri) {
        Favorite f = new Favorite();
        f.uri        = localUri.toString();
        f.name       = entry.fileName();
        f.isNas      = false;
        f.positionMs = entry.positionMs;
        f.addedAt    = entry.updatedAt;
        f.isRecent   = true;
        // bucketId는 모르므로 null — playFavoriteLocal → startLocalWithFolderView에서 MediaStore 재조회로 복구
        return f;
    }

    /** NasResumeEntry → NAS 스트리밍 Favorite (nasPath 필수). */
    private Favorite synthNasFavorite(NasSyncManager.NasResumeEntry entry) {
        Favorite f = new Favorite();
        f.uri        = DsFileApiClient.getCanonicalUrl(entry.nasPath);
        f.name       = entry.fileName();
        f.isNas      = true;
        f.nasPath    = entry.nasPath;
        f.positionMs = entry.positionMs;
        f.addedAt    = entry.updatedAt;
        f.isRecent   = true;
        return f;
    }

    /** playback_position → 합성 Favorite (빨간 별표 "마지막 재생" 항목). */
    private Favorite recentFrom(PlaybackPosition pp, boolean isNas) {
        Favorite f = new Favorite();
        f.uri        = pp.uri;
        f.name       = pp.name;
        f.isNas      = isNas;
        f.bucketId   = pp.bucketId;
        f.positionMs = pp.positionMs;
        f.addedAt    = pp.updatedAt;
        f.isRecent   = true;
        if (isNas) {
            // canonical URL에서 nasPath 추출 (재생 시 스트림 URL 재발급용)
            try { f.nasPath = Uri.parse(pp.uri).getQueryParameter("path"); }
            catch (Exception ignored) {}
        }
        return f;
    }

    private void playFavorite(Favorite fav) {
        // 재생 전에 해당 파일 위치를 playback_position에 써서 기존 이어가기 로직이 그대로 사용되도록 함
        PlaybackPosition pp = new PlaybackPosition();
        pp.uri        = fav.uri;
        pp.name       = fav.name;
        pp.bucketId   = fav.bucketId;
        pp.positionMs = fav.positionMs;
        pp.updatedAt  = System.currentTimeMillis();

        dbExecutor.execute(() -> {
            PlaybackDatabase.getInstance(this).playbackDao().savePosition(pp);
            runOnUiThread(() -> {
                if (fav.isNas) playFavoriteNas(fav);
                else playFavoriteLocal(fav);
            });
        });
    }

    private void playFavoriteLocal(Favorite fav) {
        startLocalWithFolderView(Uri.parse(fav.uri), fav.name, fav.bucketId, fav.bucketDisplayName);
    }

    /**
     * 로컬 파일 재생 헬퍼 — 즐겨찾기/이어보기 공통 진입점.
     * 해당 파일의 폴더를 쿼리해 플레이리스트 복원 + 로컬 탭 상태를 그 폴더로 이동 → Back 시 폴더 목록으로 복귀.
     */
    private void startLocalWithFolderView(Uri uri, String name, String bucketId, String bucketDisplayName) {
        // bucketId가 null이면 URI로 MediaStore를 1회 조회해 복구 (NAS의 listFolder recovery 대응)
        if (bucketId == null && uri != null) {
            String[] recovered = lookupBucketFromUri(uri);
            if (recovered != null) {
                bucketId = recovered[0];
                if (bucketDisplayName == null) bucketDisplayName = recovered[1];
            }
        }
        if (bucketId != null) {
            List<VideoItem> videos = queryVideosInBucket(bucketId, bucketDisplayName != null ? bucketDisplayName : "");
            if (!videos.isEmpty()) {
                PlaylistHolder.playlist = videos;
                int idx = -1;
                String target = uri.toString();
                for (int i = 0; i < videos.size(); i++) {
                    if (videos.get(i).uri.toString().equals(target)) { idx = i; break; }
                }
                PlaylistHolder.currentIndex = idx >= 0 ? idx : 0;
                currentBucketId   = bucketId;
                currentBucketName = bucketDisplayName != null ? bucketDisplayName : "";
                currentVideoList  = videos;
                showLocalItems(videos, null);
                tabLayout.selectTab(tabLayout.getTabAt(TAB_LOCAL));
            }
        }
        playVideo(uri, name);
    }

    private void playFavoriteNas(Favorite fav) {
        // SID 확보 후 스트림 URL 재생성 (nasPath 기반)
        if (nasSid == null) nasSid = DsFileApiClient.getCachedSid();
        if (nasSid != null && fav.nasPath != null) {
            launchNasFavorite(fav, nasSid);
            return;
        }
        // 로그인 필요
        DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
            @Override public void onResult(String sid) {
                if (isFinishing() || isDestroyed()) return;
                nasSid = sid;
                if (fav.nasPath != null) launchNasFavorite(fav, sid);
                else Toast.makeText(FileListActivity.this, "NAS 경로 정보 없음", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(FileListActivity.this, "NAS 연결 실패: " + msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void launchNasFavorite(Favorite fav, String sid) {
        startNasWithFolderRecovery(fav.nasPath, fav.uri, fav.name, sid);
    }

    /**
     * NAS 파일 재생 헬퍼 — 부모 폴더 내용을 1회 listFolder로 조회해 플레이리스트 복원.
     * 실패(네트워크/권한 등) 시 단일 항목으로 폴백. Back 시 해당 폴더로 돌아가도록 스택도 구성.
     */
    private void startNasWithFolderRecovery(String nasPath, String canonicalUri,
                                            String name, String sid) {
        if (nasPath == null || nasPath.isEmpty()) {
            playSingleNas(nasPath, canonicalUri, name, sid);
            return;
        }
        int lastSlash = nasPath.lastIndexOf('/');
        String parentPath = lastSlash > 0 ? nasPath.substring(0, lastSlash) : nasPath;
        final String targetNasPath = nasPath;

        DsFileApiClient.listFolder(parentPath, sid, new DsFileApiClient.Callback<List<VideoItem>>() {
            @Override public void onResult(List<VideoItem> items) {
                if (isFinishing() || isDestroyed()) return;
                List<VideoItem> playlist = new ArrayList<>();
                int targetIdx = 0;
                for (VideoItem f : items) {
                    if (f.type != VideoItem.TYPE_VIDEO) continue;
                    String url = DsFileApiClient.getStreamUrl(f.nasPath, sid);
                    playlist.add(VideoItem.nasFileWithStream(f.name, f.nasPath, url, f.canonicalUri));
                    if (f.nasPath != null && f.nasPath.equals(targetNasPath)) {
                        targetIdx = playlist.size() - 1;
                    }
                }
                if (playlist.isEmpty()) {
                    playSingleNas(targetNasPath, canonicalUri, name, sid);
                    return;
                }
                PlaylistHolder.playlist = playlist;
                PlaylistHolder.currentIndex = targetIdx;

                nasPathStack.clear();
                nasPathStack.push(DsFileApiClient.getBasePath());
                if (!parentPath.equals(DsFileApiClient.getBasePath())) {
                    nasPathStack.push(parentPath);
                }
                tabLayout.selectTab(tabLayout.getTabAt(TAB_NAS));

                String streamUrl = playlist.get(targetIdx).uri.toString();
                boolean useTranscode = !DsFileApiClient.isWifi(FileListActivity.this);
                playNasVideo(Uri.parse(streamUrl), name, useTranscode);
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                playSingleNas(targetNasPath, canonicalUri, name, sid);
            }
        });
    }

    /** 폴더 복원 실패/불가 시의 단일 항목 폴백. */
    private void playSingleNas(String nasPath, String canonicalUri, String name, String sid) {
        String url = DsFileApiClient.getStreamUrl(nasPath, sid);
        VideoItem vi = VideoItem.nasFileWithStream(
                name != null ? name : "", nasPath, url, canonicalUri);
        List<VideoItem> pl = new ArrayList<>();
        pl.add(vi);
        PlaylistHolder.playlist = pl;
        PlaylistHolder.currentIndex = 0;
        tabLayout.selectTab(tabLayout.getTabAt(TAB_NAS));
        boolean useTranscode = !DsFileApiClient.isWifi(FileListActivity.this);
        playNasVideo(Uri.parse(url), name, useTranscode);
    }

    private void confirmDeleteFavorite(Favorite fav) {
        // "마지막 재생" 항목은 합성된 것이라 삭제 불가
        if (fav.isRecent) return;
        new AlertDialog.Builder(this)
                .setTitle("즐겨찾기 삭제")
                .setMessage("삭제하시겠습니까?\n\n" + (fav.name != null ? fav.name : ""))
                .setPositiveButton("삭제", (d, w) -> {
                    dbExecutor.execute(() -> {
                        PlaybackDatabase.getInstance(this).favoriteDao().deleteById(fav.id);
                        runOnUiThread(this::loadFavorites);
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    private int extractEpisodeNumber(String name) {
        Matcher m = Pattern.compile("[Ee](\\d+)").matcher(name);
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        return Integer.MAX_VALUE;
    }

    private void playVideo(Uri uri, String title) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.putExtra("title", title);
        xrLauncher.startActivity(this, intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHighlight();
    }

    /** MainActivity에서 돌아왔을 때 마지막 재생 영상 항목을 파란색으로 표시. */
    private void refreshHighlight() {
        dbExecutor.execute(() -> {
            PlaybackDatabase db = PlaybackDatabase.getInstance(this);
            PlaybackPosition local = db.playbackDao().getLastLocalPosition();
            PlaybackPosition nas   = db.playbackDao().getLastNasPosition();
            String localUri = local != null ? local.uri : null;
            String nasPath = null;
            if (nas != null && nas.uri != null) {
                try { nasPath = Uri.parse(nas.uri).getQueryParameter("path"); }
                catch (Exception ignored) {}
            }
            final String finalLocalUri = localUri;
            final String finalNasPath  = nasPath;
            runOnUiThread(() -> {
                if (localAdapter != null) {
                    int pos = localAdapter.setHighlightUri(finalLocalUri);
                    if (pos >= 0 && rvLocal != null) scrollIntoView(rvLocal, pos);
                }
                if (nasAdapter != null) {
                    int pos = nasAdapter.setHighlightNasPath(finalNasPath);
                    if (pos >= 0 && rvNas != null) scrollIntoView(rvNas, pos);
                }
            });
        });
    }

    /** position이 RecyclerView 뷰포트 중앙 근처에 오도록 스크롤. */
    private void scrollIntoView(RecyclerView rv, int position) {
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            int first = ((LinearLayoutManager) lm).findFirstCompletelyVisibleItemPosition();
            int last  = ((LinearLayoutManager) lm).findLastCompletelyVisibleItemPosition();
            if (position >= first && position <= last && first != RecyclerView.NO_POSITION) return;
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(position, rv.getHeight() / 3);
        } else {
            rv.scrollToPosition(position);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 이 Activity 인스턴스를 참조하는 onUpdate 람다가 남아있으면 NAS 늦은 콜백에서 dead Activity를 호출할 수 있음
        ResumeSnapshot.onUpdate = null;
        dbExecutor.shutdown();
    }
}
