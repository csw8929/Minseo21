package com.example.minseo2;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileListActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "player_prefs";
    private static final String KEY_LAST_STATE = "last_app_state"; // 0: LIST, 1: PLAYER
    private static boolean hasCheckedResumeThisSession = false; // 프로세스당 1회 체크용

    private FileItemAdapter adapter;
    private TextView tvPath;
    private TextView tvEmpty;

    private String currentBucketId   = null;
    private String currentBucketName = null;
    private List<VideoItem> currentVideoList = new ArrayList<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadBucketList();
                    checkLastPlayback();
                }
                else Toast.makeText(this, "저장소 접근 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        tvPath  = findViewById(R.id.tvPath);
        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.recyclerView);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        adapter = new FileItemAdapter(item -> {
            if (item.type == VideoItem.TYPE_FOLDER) {
                loadVideosInBucket(item.bucketId, item.name);
            } else {
                int idx = currentVideoList.indexOf(item);
                PlaylistHolder.playlist = new ArrayList<>(currentVideoList);
                PlaylistHolder.currentIndex = idx >= 0 ? idx : 0;
                playVideo(item.uri, item.name);
            }
        });
        rv.setAdapter(adapter);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentBucketId != null) {
                    loadBucketList();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        checkPermissionAndLoad();
    }

    private void checkLastPlayback() {
        // 1. 이미 체크했거나, 런처 실행이 아닌 경우 제외
        if (hasCheckedResumeThisSession) return;
        hasCheckedResumeThisSession = true;

        // 2. 마지막 종료 상태 확인 (Player 화면에서 종료되었는지)
        int lastState = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_LAST_STATE, 0);
        if (lastState != 1) return;

        dbExecutor.execute(() -> {
            PlaybackPosition last = PlaybackDatabase.getInstance(this)
                    .playbackDao().getLastPosition();
            
            if (last != null && last.uri != null) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("재생할까요?")
                            .setMessage("마지막으로 재생하던 영상을 이어서 봅니다.\n\n" + (last.name != null ? last.name : "알 수 없는 파일"))
                            .setPositiveButton("예", (dialog, which) -> {
                                if (last.bucketId != null) {
                                    List<VideoItem> videos = queryVideosInBucket(last.bucketId);
                                    if (!videos.isEmpty()) {
                                        PlaylistHolder.playlist = videos;
                                        int idx = -1;
                                        for (int i = 0; i < videos.size(); i++) {
                                            if (videos.get(i).uri.toString().equals(last.uri)) {
                                                idx = i;
                                                break;
                                            }
                                        }
                                        PlaylistHolder.currentIndex = idx >= 0 ? idx : 0;
                                    }
                                }
                                playVideo(Uri.parse(last.uri), last.name);
                            })
                            .setNegativeButton("아니오", (dialog, which) -> {
                                // 아니오를 누르면 상태 초기화
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_LAST_STATE, 0).apply();
                            })
                            .show();
                });
            }
        });
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

    private void loadBucketList() {
        currentBucketId   = null;
        currentBucketName = null;
        currentVideoList.clear();
        tvPath.setText("내장저장공간");

        String[] proj = {
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        };
        Map<String, String> buckets = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " ASC")) {
            if (c != null) {
                int colId   = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                while (c.moveToNext()) {
                    String id   = c.getString(colId);
                    String name = c.getString(colName);
                    if (id != null && !buckets.containsKey(id)) {
                        buckets.put(id, name != null ? name : id);
                    }
                }
            }
        }

        List<VideoItem> items = new ArrayList<>();
        for (Map.Entry<String, String> e : buckets.entrySet()) {
            items.add(VideoItem.folder(e.getValue(), e.getKey()));
        }
        showItems(items, items.isEmpty() ? "동영상 폴더가 없습니다." : null);
    }

    private List<VideoItem> queryVideosInBucket(String bucketId) {
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_ID,
        };
        String sel    = MediaStore.Video.Media.BUCKET_ID + " = ?";
        String[] args = {bucketId};

        List<VideoItem> items = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, sel, args, null)) {
            if (c != null) {
                int colId   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int colSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                while (c.moveToNext()) {
                    long id   = c.getLong(colId);
                    String nm = c.getString(colName);
                    long sz   = c.getLong(colSize);
                    Uri uri   = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    items.add(VideoItem.video(nm, bucketId, uri, sz));
                }
            }
        }

        items.sort((a, b) -> {
            int ea = extractEpisodeNumber(a.name);
            int eb = extractEpisodeNumber(b.name);
            if (ea != eb) return Integer.compare(ea, eb);
            return a.name.compareTo(b.name);
        });

        return items;
    }

    private void loadVideosInBucket(String bucketId, String bucketName) {
        currentBucketId   = bucketId;
        currentBucketName = bucketName;
        tvPath.setText("내장저장공간 / " + bucketName);

        List<VideoItem> items = queryVideosInBucket(bucketId);

        currentVideoList = items;
        showItems(items, items.isEmpty() ? "동영상 파일이 없습니다." : null);
    }

    private int extractEpisodeNumber(String name) {
        Matcher m = Pattern.compile("[Ee](\\d+)").matcher(name);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return Integer.MAX_VALUE;
    }

    private void showItems(List<VideoItem> items, String emptyMsg) {
        adapter.setItems(items);
        if (emptyMsg != null) {
            tvEmpty.setText(emptyMsg);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
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
