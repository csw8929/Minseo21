package com.example.minseo21;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 재생 위치 이중 저장/읽기.
 *
 * 저장 방식:
 *   - Room DB: 5초마다 (기존 동작 유지)
 *   - NAS: 인메모리 캐시 유지 → 30초마다 + onPause 시 {user}_positions.json 업로드
 *
 * 파일 구조 (NAS):
 *   {cfgPosDir}/{user}_positions.json
 *   { "version": 1, "positions": { "폴더/파일.mkv": { positionMs, updatedAt, ... } } }
 *
 * 키 포맷: "{parentFolderName}/{filename}"
 *   - 로컬: VideoItem.bucketDisplayName + "/" + VideoItem.name
 *   - NAS:  nasPath의 마지막 두 세그먼트 (/video/폴더/파일.mkv → 폴더/파일.mkv)
 */
public class NasSyncManager {

    private static final String TAG = "NasSync";

    private final Context context;
    private final ExecutorService dbExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 전체 사용자 위치 인메모리 캐시. null = 아직 NAS에서 로드 안 됨. */
    private volatile JSONObject positionsCache = null;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean loading = new AtomicBoolean(false);

    /** 마지막으로 성공한 positions.json fetch 시각 (ms). 0 = 미 로드. */
    private volatile long lastFetchAt = 0;

    /** onResume refresh TTL. 이 시간 이내면 재fetch skip. */
    private static final long RESUME_REFRESH_TTL_MS = 5L * 60 * 1000;  // 5분

    /** startup fetch 실패 시 백그라운드 재시도 간격. */
    private static final long STARTUP_RETRY_MS = 30L * 1000;

    public interface PositionCallback {
        void onPosition(PlaybackPosition pos);
    }

    /**
     * NAS 위치 캐시의 단일 항목 — cross-device resume 다이얼로그용.
     */
    public static class NasResumeEntry {
        public final String syncKey;    // "{폴더}/{파일명}"
        public final String nasPath;    // "/video/폴더/파일.mkv" — null이면 로컬 파일
        public final long   positionMs;
        public final long   updatedAt;
        public final String deviceId;   // 저장한 단말의 ANDROID_ID. null이면 구버전 항목.

        NasResumeEntry(String syncKey, String nasPath, long positionMs, long updatedAt, String deviceId) {
            this.syncKey    = syncKey;
            this.nasPath    = nasPath;
            this.positionMs = positionMs;
            this.updatedAt  = updatedAt;
            this.deviceId   = deviceId;
        }

        /** syncKey의 마지막 세그먼트 = 파일명 */
        public String fileName() {
            int i = syncKey.lastIndexOf('/');
            return i >= 0 ? syncKey.substring(i + 1) : syncKey;
        }
    }

    /**
     * positions JSON에서 updatedAt이 가장 큰 항목을 반환.
     * downloadUserPositions 결과를 직접 받아 쓰므로 static.
     */
    public static NasResumeEntry findMostRecentEntry(JSONObject positions) {
        if (positions == null || positions.length() == 0) return null;
        try {
            String     bestKey   = null;
            long       bestTime  = 0;
            JSONObject bestEntry = null;
            Iterator<String> it = positions.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject e = positions.optJSONObject(key);
                if (e == null) continue;
                long t = e.optLong("updatedAt", 0);
                if (t > bestTime) { bestTime = t; bestKey = key; bestEntry = e; }
            }
            if (bestKey == null) return null;
            String np = bestEntry.optString("nasPath", null);
            if (np != null && np.isEmpty()) np = null;
            String did = bestEntry.optString("deviceId", null);
            if (did != null && did.isEmpty()) did = null;
            return new NasResumeEntry(bestKey, np,
                    bestEntry.optLong("positionMs", 0), bestTime, did);
        } catch (Exception e) {
            Log.d(TAG, "findMostRecentEntry 오류: " + e.getMessage());
            return null;
        }
    }

    /**
     * positions.json 전체를 updatedAt DESC 순으로 NasResumeEntry 리스트로 반환.
     *
     * 즐겨찾기 "빨간 별표 NAS" 캡처 시 이 리스트를 순서대로 돌면서
     * 이 단말에서 실제로 재생 가능한 첫 항목을 채택한다 (FileListActivity에서 사용).
     * "재생 가능"의 판정은 호출자가 결정 — Room DB / MediaStore / nasPath 모두 고려하기 위해
     * 여기선 필터를 걸지 않고 원본 순서대로 돌려준다.
     */
    public static java.util.List<NasResumeEntry> listEntriesDescending(JSONObject positions) {
        java.util.List<NasResumeEntry> out = new java.util.ArrayList<>();
        if (positions == null || positions.length() == 0) return out;
        try {
            Iterator<String> it = positions.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject e = positions.optJSONObject(key);
                if (e == null) continue;
                String np = e.optString("nasPath", null);
                if (np != null && np.isEmpty()) np = null;
                String did = e.optString("deviceId", null);
                if (did != null && did.isEmpty()) did = null;
                out.add(new NasResumeEntry(key, np,
                        e.optLong("positionMs", 0),
                        e.optLong("updatedAt", 0),
                        did));
            }
            java.util.Collections.sort(out,
                    (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        } catch (Exception ex) {
            Log.d(TAG, "listEntriesDescending 오류: " + ex.getMessage());
        }
        return out;
    }

    private static volatile NasSyncManager INSTANCE;

    /**
     * Application-scope 싱글턴. FileListActivity / MainActivity 가 공유.
     * 캐시가 액티비티 경계에서 보존되어 MainActivity 첫 재생 시 레이스 방지 (ISSUE-002).
     */
    public static NasSyncManager getInstance(Context ctx) {
        NasSyncManager local = INSTANCE;
        if (local == null) {
            synchronized (NasSyncManager.class) {
                local = INSTANCE;
                if (local == null) {
                    INSTANCE = local = new NasSyncManager(ctx.getApplicationContext());
                }
            }
        }
        return local;
    }

    private NasSyncManager(Context appContext) {
        this.context    = appContext;
        // 싱글턴 전용 내부 executor — 액티비티 생명주기와 독립.
        this.dbExecutor = Executors.newSingleThreadExecutor();
        // 자동 다운로드 안 함: SID 없는 시점에 호출되면 빈 캐시가 고정되어 이후 compare 스킵됨.
        // 로그인 직후 seedCache() 또는 필요 시 loadPosition() 이 lazy-load 를 트리거.
    }

    /**
     * FileListActivity 가 이미 다운로드한 positions JSON 을 싱글턴 캐시에 주입.
     * MainActivity 의 중복 네트워크 호출을 제거하고 첫 재생 레이스도 해결.
     */
    public void seedCache(JSONObject positions) {
        if (positions == null) return;
        positionsCache = positions;
        lastFetchAt = System.currentTimeMillis();
        Log.d(TAG, "NAS 캐시 seed: " + positions.length() + " 항목");
    }

    /**
     * NAS에서 전체 위치 JSON 로드 → 캐시 저장.
     * SID 없으면 빈 캐시로 초기화 (로컬만 동작).
     */
    public void loadAllPositionsFromNas(Runnable onDone) {
        // 이미 로딩 중이면 onDone 드롭 방지: 캐시가 채워져 있으면 즉시 실행, 아니면 100ms 재시도
        if (!loading.compareAndSet(false, true)) {
            if (onDone != null) {
                if (positionsCache != null) mainHandler.post(onDone);
                else mainHandler.postDelayed(() -> loadAllPositionsFromNas(onDone), 100);
            }
            return;
        }
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean(false);
        DsFileApiClient.downloadUserPositions(new DsFileApiClient.Callback<JSONObject>() {
            @Override public void onResult(JSONObject positions) {
                if (!fired.compareAndSet(false, true)) return; // 이중 콜백 방어
                positionsCache = positions;
                lastFetchAt = System.currentTimeMillis();
                loading.set(false);
                Log.d(TAG, "NAS 캐시 로드 완료: " + positions.length() + " 항목");
                if (onDone != null) onDone.run();
            }
            @Override public void onError(String msg) {
                if (!fired.compareAndSet(false, true)) return;
                if (positionsCache == null) positionsCache = new JSONObject();
                loading.set(false);
                Log.d(TAG, "NAS 캐시 로드 실패 (로컬 모드): " + msg);
                // 아직 한 번도 성공 못했으면 백그라운드 재시도 예약
                if (lastFetchAt == 0) {
                    mainHandler.postDelayed(() -> {
                        if (lastFetchAt == 0 && DsFileApiClient.getCachedSid() != null) {
                            loadAllPositionsFromNas(null);
                        }
                    }, STARTUP_RETRY_MS);
                }
                if (onDone != null) onDone.run();
            }
        });
    }

    /**
     * 포그라운드 복귀(onResume) 시 호출. 마지막 성공 fetch로부터 TTL 경과한 경우에만 재로드.
     * 재로드 결과는 현재 positionsCache와 mergePositions로 합쳐지므로
     * 세션 중 dirty 쓰기는 유실되지 않음 (updatedAt 기준 승자 결정).
     */
    public void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastFetchAt < RESUME_REFRESH_TTL_MS) return;
        if (DsFileApiClient.getCachedSid() == null) return;
        if (loading.get()) return;
        if (!loading.compareAndSet(false, true)) return;

        DsFileApiClient.downloadUserPositions(new DsFileApiClient.Callback<JSONObject>() {
            @Override public void onResult(JSONObject remote) {
                // 현재 캐시(세션 dirty 포함)를 remote 위에 병합 — 로컬이 더 최신이면 로컬 유지
                positionsCache = mergePositions(remote, positionsCache);
                lastFetchAt = System.currentTimeMillis();
                loading.set(false);
                Log.d(TAG, "NAS 캐시 refresh 완료: " + positionsCache.length() + " 항목");
            }
            @Override public void onError(String msg) {
                loading.set(false);
                Log.d(TAG, "NAS 캐시 refresh 실패 (기존 캐시 유지): " + msg);
            }
        });
    }

    /**
     * 위치 저장.
     *   syncKey: "{폴더}/{파일명}" — NAS 캐시 키
     *   pos.uri:  Room DB 키 (canonical URL / content:// URI)
     *   nasPath:  NAS 파일 경로 (/video/폴더/파일.mkv) — 로컬 파일이면 null
     *             cross-device resume 시 B 단말이 스트림 URL을 재생성하는 데 사용.
     */
    public void savePosition(PlaybackPosition pos, String syncKey, String nasPath) {
        // 1. Room DB
        dbExecutor.execute(() ->
                PlaybackDatabase.getInstance(context).playbackDao().savePosition(pos));

        // 2. 인메모리 캐시 업데이트
        if (syncKey == null || syncKey.isEmpty()) return;
        try {
            if (positionsCache == null) positionsCache = new JSONObject();
            JSONObject entry = new JSONObject();
            entry.put("positionMs",      pos.positionMs);
            entry.put("audioTrackId",    pos.audioTrackId);
            entry.put("subtitleTrackId", pos.subtitleTrackId);
            entry.put("screenMode",      pos.screenMode);
            entry.put("updatedAt",       pos.updatedAt);
            entry.put("deviceId",        getDeviceId());
            if (nasPath != null && !nasPath.isEmpty()) entry.put("nasPath", nasPath);
            positionsCache.put(syncKey, entry);
            dirty.set(true);
        } catch (Exception e) {
            Log.w(TAG, "캐시 업데이트 오류: " + e.getMessage());
        }
    }

    /**
     * NAS에 캐시 플러시. dirty 상태일 때만 업로드.
     * 30초 타이머 또는 onPause 에서 호출.
     *
     * Cache-first: positionsCache를 세션 내 진실로 간주, 다운로드 없이 바로 업로드.
     * 다중 단말 동시 쓰기 시 늦게 쓴 쪽이 이김 (last-writer-wins). 드문 케이스로 허용.
     */
    public void flushToNas() {
        if (!dirty.compareAndSet(true, false)) return; // 변경 없으면 skip
        JSONObject snapshot = positionsCache;
        if (snapshot == null) return;
        String sid = DsFileApiClient.getCachedSid();
        if (sid == null) { dirty.set(true); Log.w(TAG, "flushToNas skip: SID 없음"); return; }

        DsFileApiClient.uploadUserPositions(snapshot, new DsFileApiClient.Callback<Boolean>() {
            @Override public void onResult(Boolean ok) {
                Log.d(TAG, "NAS flush 성공: " + snapshot.length() + " 항목");
            }
            @Override public void onError(String msg) {
                dirty.set(true); // 실패 시 다음 flush에서 재시도
                Log.w(TAG, "NAS flush 실패: " + msg);
            }
        });
    }

    /**
     * 두 positions JSON을 병합. 같은 키라면 updatedAt이 더 큰 항목을 채택.
     * base의 모든 항목 + local의 모든 항목, 충돌 시 더 최신 항목 유지.
     */
    private JSONObject mergePositions(JSONObject base, JSONObject local) {
        JSONObject result = new JSONObject();
        try {
            // base 항목 먼저 복사
            if (base != null) {
                Iterator<String> it = base.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    result.put(k, base.get(k));
                }
            }
            // local 항목으로 덮어쓰기 (더 최신이면)
            if (local != null) {
                Iterator<String> it = local.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject le = local.optJSONObject(k);
                    if (le == null) continue;
                    long localTime = le.optLong("updatedAt", 0);
                    JSONObject re = result.optJSONObject(k);
                    long remoteTime = re != null ? re.optLong("updatedAt", 0) : 0;
                    if (localTime >= remoteTime) {
                        result.put(k, le);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "mergePositions 오류: " + e.getMessage());
        }
        return result;
    }

    /**
     * onPause 전용 블로킹 flush. 백그라운드 스레드에서 호출.
     * Cache-first: 다운로드 없이 업로드만 동기 수행.
     * dirty 아니면 skip. SID 없으면 skip.
     */
    public void flushToNasBlocking() {
        if (!dirty.compareAndSet(true, false)) return;
        JSONObject snapshot = positionsCache;
        if (snapshot == null) { dirty.set(true); return; }
        if (DsFileApiClient.getCachedSid() == null) { dirty.set(true); return; }
        try {
            boolean ok = DsFileApiClient.uploadUserPositionsSync(snapshot);
            if (!ok) {
                dirty.set(true);
                Log.w(TAG, "flushToNasBlocking 업로드 실패 → dirty 유지");
            } else {
                Log.d(TAG, "flushToNasBlocking 성공: " + snapshot.length() + " 항목");
            }
        } catch (Exception e) {
            dirty.set(true);
            Log.w(TAG, "flushToNasBlocking 오류: " + e.getMessage());
        }
    }

    /**
     * 위치 로드. Room DB(빠름) 먼저 콜백 → NAS 캐시가 더 최신이면 두 번째 콜백.
     *
     * Cache-first: positionsCache를 소스 오브 트루스로 간주. 비어있으면 Room DB만 사용.
     * startup fetch가 실패한 경우 30초 간격 백그라운드 재시도가 cache를 채우면 다음 재생부터 반영.
     *
     * @param syncKey  NAS 캐시 키 ("{폴더}/{파일명}")
     * @param roomDbKey Room DB 키 (canonical URL / content:// URI)
     */
    public void loadPosition(String syncKey, String roomDbKey, PositionCallback cb) {
        dbExecutor.execute(() -> {
            // 1. Room DB 먼저 콜백
            PlaybackPosition dbPos = PlaybackDatabase.getInstance(context)
                    .playbackDao().getPosition(roomDbKey);
            mainHandler.post(() -> cb.onPosition(dbPos));

            if (syncKey == null || syncKey.isEmpty()) return;

            // 2. 캐시 미준비 시 lazy-load 후 NAS 비교 (ISSUE-002: 첫 재생 레이스 방지)
            if (positionsCache == null) {
                if (DsFileApiClient.getCachedSid() == null) return; // SID 없으면 로컬만
                loadAllPositionsFromNas(() -> compareAndCallbackNas(syncKey, roomDbKey, dbPos, cb));
                return;
            }
            if (positionsCache.length() == 0) return;

            // 3. 캐시 준비됨 → 즉시 NAS 비교
            compareAndCallbackNas(syncKey, roomDbKey, dbPos, cb);
        });
    }

    /** NAS 캐시에서 syncKey 항목을 찾아 dbPos보다 최신이면 콜백. 메인 스레드에서 호출 가능. */
    private void compareAndCallbackNas(String syncKey, String roomDbKey,
                                       PlaybackPosition dbPos, PositionCallback cb) {
        if (positionsCache == null) return;
        try {
            JSONObject entry = positionsCache.optJSONObject(syncKey);

            // syncKey = 파일명 기준. 정확한 매치 실패 시 구버전 데이터(폴더/파일명 형태) fallback 검색
            if (entry == null) {
                // syncKey 자체가 파일명이므로, 캐시 키가 "폴더/파일명" 형태인 구버전 항목에서 파일명 부분만 비교
                Iterator<String> it = positionsCache.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String kFile = k.contains("/") ? k.substring(k.lastIndexOf('/') + 1) : k;
                    if (kFile.equals(syncKey)) {
                        entry = positionsCache.optJSONObject(k);
                        Log.d(TAG, "syncKey 구버전 fallback: [" + syncKey + "] ← [" + k + "]");
                        break;
                    }
                }
            }

            if (entry == null) return;
            long nasUpdatedAt = entry.optLong("updatedAt", 0);
            if (dbPos != null && dbPos.updatedAt >= nasUpdatedAt) return; // 로컬이 더 최신

            // 같은 단말이 저장한 항목: 모든 설정 복원
            // 다른 단말이 저장한 항목: 위치만 복원, screenMode/audioTrackId/subtitleTrackId 무시
            //   (단말마다 화면 설정/자막 취향이 다를 수 있음)
            String savedDeviceId = entry.optString("deviceId", null);
            boolean isSameDevice = savedDeviceId != null && savedDeviceId.equals(getDeviceId());

            PlaybackPosition nasPos = new PlaybackPosition();
            nasPos.uri             = roomDbKey;
            nasPos.positionMs      = entry.optLong("positionMs", 0);
            nasPos.audioTrackId    = isSameDevice ? entry.optInt("audioTrackId",    Integer.MIN_VALUE) : Integer.MIN_VALUE;
            nasPos.subtitleTrackId = isSameDevice ? entry.optInt("subtitleTrackId", Integer.MIN_VALUE) : Integer.MIN_VALUE;
            nasPos.screenMode      = isSameDevice ? entry.optInt("screenMode", -1) : -1;
            nasPos.updatedAt       = nasUpdatedAt;
            Log.d(TAG, "NAS 캐시가 더 최신: " + nasPos.positionMs + "ms (key=" + syncKey
                    + ", sameDevice=" + isSameDevice + ")");
            mainHandler.post(() -> cb.onPosition(nasPos));
        } catch (Exception e) {
            Log.d(TAG, "NAS 캐시 읽기 오류: " + e.getMessage());
        }
    }

    /** 이 단말의 고유 ID (ANDROID_ID). */
    public String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }
}
