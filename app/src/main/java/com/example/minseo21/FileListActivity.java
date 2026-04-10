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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

    private void checkLastPlayback() {
        if (hasCheckedResumeThisSession) return;
        hasCheckedResumeThisSession = true;
        int lastState = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_LAST_STATE, 0);
        if (lastState != 1) return;

        dbExecutor.execute(() -> {
            PlaybackPosition last = PlaybackDatabase.getInstance(this)
                    .playbackDao().getLastPosition();
            if (last == null || last.uri == null) return;

            boolean isNas = DsFileApiClient.isNasUrl(last.uri);
            runOnUiThread(() -> {
                if (isNas) {
                    // 인증 정보 없으면 스킵 (최초 설치 직후 등)
                    if (!nasCredStore.hasCredentials()) return;
                    if (nasSid != null) {
                        // SID 이미 있음 (NAS 탭을 먼저 열었던 경우)
                        showResumeDialog(last, DsFileApiClient.canonicalToStream(last.uri, nasSid));
                    } else {
                        // SID 없음 → 백그라운드 로그인 후 다이얼로그 표시
                        DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
                            @Override public void onResult(String sid) {
                                if (isFinishing() || isDestroyed()) return;
                                nasSid = sid;
                                showResumeDialog(last, DsFileApiClient.canonicalToStream(last.uri, sid));
                            }
                            @Override public void onError(String msg) {
                                // 로그인 실패 → 이어보기 조용히 스킵
                            }
                        });
                    }
                } else {
                    showResumeDialog(last, null);
                }
            });
        });
    }

    /** 이어보기 다이얼로그. nasStreamUrl이 null이면 로컬 파일로 취급. */
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
                        List<VideoItem> videos = queryVideosInBucket(last.bucketId);
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
                        // NAS 탭으로 자동 전환
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

    private List<VideoItem> queryVideosInBucket(String bucketId) {
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
                    items.add(VideoItem.video(c.getString(colName), bucketId, uri,
                            c.getLong(colSize), c.getLong(colDate)));
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
        currentVideoList = queryVideosInBucket(bucketId);
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
            // 파일 탭 → 플레이리스트 구성 후 재생
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
            String streamUrl = DsFileApiClient.getStreamUrl(item.nasPath, nasSid);
            playVideo(Uri.parse(streamUrl), item.name);
        }
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
