package com.example.minseo21;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 재생 위치 이중 저장/읽기.
 *   쓰기: Room DB (항상) + NAS JSON (fire-and-forget)
 *   읽기: NAS 와 Room DB 를 동시에, 더 최신 타임스탬프 쪽 사용
 */
public class NasSyncManager {

    private static final String TAG = "NasSync";

    private final Context context;
    private final ExecutorService dbExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PositionCallback {
        void onPosition(PlaybackPosition pos);
    }

    public NasSyncManager(Context context, ExecutorService dbExecutor) {
        this.context    = context.getApplicationContext();
        this.dbExecutor = dbExecutor;
    }

    /**
     * 위치 저장 — Room DB (동기, dbExecutor) + NAS (비동기, 무시 가능).
     *
     * @param canonicalUrl Room DB 키 / NAS 파일명 기준 URL
     */
    public void savePosition(PlaybackPosition pos, String canonicalUrl) {
        // 1. Room DB 저장
        dbExecutor.execute(() ->
                PlaybackDatabase.getInstance(context).playbackDao().savePosition(pos));

        // 2. NAS 저장 (SID 있을 때만)
        String sid = DsFileApiClient.getCachedSid();
        if (sid != null) {
            DsFileApiClient.savePositionToNas(canonicalUrl, pos, sid);
        }
    }

    /**
     * 위치 로드 — NAS 와 Room DB 를 병렬 조회, 더 최신 쪽 반환.
     * NAS 가 느리면 Room DB 먼저 사용 가능하도록 Room DB 결과를 먼저 콜백.
     * NAS 가 더 최신이면 두 번째로 콜백 (MainActivity 가 seek 업데이트).
     */
    public void loadPosition(String canonicalUrl, PositionCallback cb) {
        // Room DB (빠름) 먼저
        dbExecutor.execute(() -> {
            PlaybackPosition dbPos = PlaybackDatabase.getInstance(context)
                    .playbackDao().getPosition(canonicalUrl);
            mainHandler.post(() -> cb.onPosition(dbPos)); // null 일 수도 있음

            // NAS (느림) 이후
            String sid = DsFileApiClient.getCachedSid();
            if (sid == null) return;
            DsFileApiClient.loadPositionFromNas(canonicalUrl, sid, new DsFileApiClient.Callback<PlaybackPosition>() {
                @Override public void onResult(PlaybackPosition nasPos) {
                    if (nasPos == null) return;
                    if (dbPos == null || nasPos.updatedAt > dbPos.updatedAt) {
                        Log.d(TAG, "NAS 위치가 더 최신: " + nasPos.positionMs + "ms");
                        cb.onPosition(nasPos); // 두 번째 콜백 — seek 업데이트
                    }
                }
                @Override public void onError(String msg) {
                    Log.d(TAG, "NAS 위치 로드 실패 (무시): " + msg);
                }
            });
        });
    }
}
