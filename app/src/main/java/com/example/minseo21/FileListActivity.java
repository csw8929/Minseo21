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

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileListActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "player_prefs";
    private static final String KEY_LAST_STATE = "last_app_state";
    private static boolean hasCheckedResumeThisSession = false;

    // ── 로컬 ──────────────────────────────────────────────────────────────────
    private FileItemAdapter localAdapter;
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

    // ── 탭 ──────────────────────────────────────────────────────────────────
    private TabLayout tabLayout;
    private static final int TAB_LOCAL = 0;
    private static final int TAB_NAS   = 1;
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

    private final ActivityResultLauncher<Intent> portalLoginLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // 포털 쿠키 획득 완료 — NAS 재연결 시도
                    nasSid = null;
                    if (currentTab == TAB_NAS) connectNas();
                } else {
                    showNasError("포털 인증이 취소되었습니다.");
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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        // NAS 경로 스택 복원 (onSaveInstanceState에서 저장됨)
        if (savedInstanceState != null) {
            ArrayList<String> savedStack = savedInstanceState.getStringArrayList("nasPathStack");
            if (savedStack != null && !savedStack.isEmpty()) {
                nasPathStack.addAll(savedStack);
            }
        }

        tvPath       = findViewById(R.id.tvPath);
        tvEmpty      = findViewById(R.id.tvEmpty);
        nasLoadingView = findViewById(R.id.nasLoadingView);
        nasErrorView   = findViewById(R.id.nasErrorView);
        tvNasError     = findViewById(R.id.tvNasError);
        rvNas          = findViewById(R.id.rvNas);
        tabLayout      = findViewById(R.id.tabLayout);
        btnNasSettings = findViewById(R.id.btnNasSettings);

        // NAS 인증 정보 초기화 (저장된 값 우선, 없으면 DsFileConfig fallback)
        nasCredStore = new NasCredentialStore(this);
        DsFileApiClient.init(nasCredStore);

        btnNasSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, NasSetupActivity.class);
            intent.putExtra(NasSetupActivity.EXTRA_EDIT_MODE, true);
            nasSetupLauncher.launch(intent);
        });

        // 로컬 RecyclerView
        RecyclerView rv = findViewById(R.id.recyclerView);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
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
        rv.setAdapter(localAdapter);

        // NAS RecyclerView
        rvNas.setLayoutManager(new LinearLayoutManager(this));
        rvNas.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        nasAdapter = new NasFileAdapter(this::onNasItemClick);

        // 재시도 버튼
        Button btnRetry = findViewById(R.id.btnNasRetry);
        btnRetry.setOnClickListener(v -> connectNas());

        // 탭 설정
        tabLayout.addTab(tabLayout.newTab().setText("로컬"));
        tabLayout.addTab(tabLayout.newTab().setText("NAS"));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                if (currentTab == TAB_LOCAL) showLocalTab();
                else showNasTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 뒤로 가기
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTab == TAB_NAS) {
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
    }

    private void checkPermissionAndLoad() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            loadBucketList();
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
        if (!hasLocalState && !hasNasCreds) return;

        dbExecutor.execute(() -> {
            PlaybackPosition localLast = hasLocalState
                    ? PlaybackDatabase.getInstance(this).playbackDao().getLastPosition()
                    : null;
            if (localLast != null && localLast.uri == null) localLast = null;

            if (!hasNasCreds) {
                // NAS 인증 정보 없음 → 로컬 이력만 표시
                final PlaybackPosition ll = localLast;
                if (ll != null) runOnUiThread(() -> showResumeDialog(ll, null));
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
                } else if (localLast != null) {
                    // 로컬이 최신 or NAS 항목 없음 → 기존 이어보기
                    boolean isNas = DsFileApiClient.isNasUrl(localLast.uri);
                    String nasUrl = isNas
                            ? DsFileApiClient.canonicalToStream(localLast.uri, sid)
                            : null;
                    runOnUiThread(() -> showResumeDialog(localLast, nasUrl));
                }
                // 둘 다 없으면 다이얼로그 없이 파일 리스트
            }
            @Override public void onError(String msg) {
                // NAS 다운로드 실패 → 로컬 이력만
                if (localLast != null)
                    runOnUiThread(() -> showResumeDialog(localLast, null));
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
            }
            // 둘 다 없으면 → 파일 리스트 (아무것도 안 함)
        });
    }

    /**
     * Cross-device resume 다이얼로그.
     * 로컬에서 같은 파일을 찾은 경우에만 호출됨 → 항상 로컬 재생.
     */
    private void showCrossDeviceResumeDialog(String fileName, Uri localUri) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("이어서 볼까요?")
                .setMessage("다른 단말에서 재생 중이던 영상입니다.\n\n" + fileName)
                .setPositiveButton("예", (dialog, which) -> playVideo(localUri, fileName))
                .setNegativeButton("아니오", null)
                .show();
    }

    /** 이어보기 다이얼로그 (이 단말에서 마지막으로 재생하던 영상). nasStreamUrl이 null이면 로컬 파일로 취급. */
    private void showResumeDialog(PlaybackPosition last, String nasStreamUrl) {
        boolean isNas = nasStreamUrl != null;
        String playUri = isNas ? nasStreamUrl : last.uri;
        final String finalUri = playUri;

        new AlertDialog.Builder(this)
                .setTitle("재생할까요?")
                .setMessage("마지막으로 재생하던 영상을 이어서 봅니다.\n\n"
                        + (last.name != null ? last.name : "알 수 없는 파일"))
                .setPositiveButton("예", (dialog, which) -> {
                    if (!isNas && last.bucketId != null) {
                        List<VideoItem> videos = queryVideosInBucket(last.bucketId, "");
                        if (!videos.isEmpty()) {
                            PlaylistHolder.playlist = videos;
                            int idx = -1;
                            for (int i = 0; i < videos.size(); i++) {
                                if (videos.get(i).uri.toString().equals(last.uri)) { idx = i; break; }
                            }
                            PlaylistHolder.currentIndex = idx >= 0 ? idx : 0;
                        }
                    }
                    if (isNas) {
                        tabLayout.selectTab(tabLayout.getTabAt(TAB_NAS));
                    }
                    playVideo(Uri.parse(finalUri), last.name);
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
                if (msg.startsWith("PORTAL_AUTH_REQUIRED:")) {
                    String url = msg.substring("PORTAL_AUTH_REQUIRED:".length());
                    showNasLoading("포털 인증 화면 열기 중…");
                    Intent intent = new Intent(FileListActivity.this, PortalLoginActivity.class);
                    intent.putExtra(PortalLoginActivity.EXTRA_PORTAL_URL, url);
                    portalLoginLauncher.launch(intent);
                } else {
                    showNasError(msg);
                }
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

            // TODO: 5G 환경에서 HLS 트랜스코딩 지원 예정 — 현재는 직접 스트리밍.
            playVideo(Uri.parse(directUrl), item.name);
        }
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
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dbExecutor.shutdown();
    }
}
