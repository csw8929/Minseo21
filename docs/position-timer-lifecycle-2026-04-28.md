# Position write timer lifecycle 정렬 — 2026-04-28

## 배경

`MainActivity` 는 재생 중 두 개의 주기 타이머로 position 을 저장한다.

| Task | 인터벌 | 책임 |
|---|---|---|
| `savePositionTask` | 5초 (`SAVE_INTERVAL_MS`) | `saveCurrentPosition()` 호출 → Room DB write + NasSyncManager 인메모리 캐시 update (`dirty=true`) |
| `nasFlushTask` | 30초 (`NAS_FLUSH_INTERVAL_MS`) | `nasSyncManager.flushToNas()` 호출 → dirty 면 NAS 업로드 |

문제: 두 타이머의 lifecycle 이 비대칭이었다.

- `savePositionTask` — Playing 시 시작 / Paused·Stopped·EndReached 시 정지 (정상)
- `nasFlushTask` — `initPlayer` 시 시작 / **정지 지점 없음** (onDestroy 의 `removeCallbacksAndMessages(null)` 만이 회수)

→ 일시정지 중에도 30초마다 `flushToNas()` 호출. 내부 `dirty.compareAndSet(true,false)` 로 빨리 빠져나오긴 하지만 사용자 의도("멈추어 있을 때는 NAS 에 write 할 필요 없고 timer 도 멈춰라") 와 어긋남.

## 수정 (MainActivity.java)

`nasFlushTask` 의 lifecycle 을 `savePositionTask` 와 동일 패턴으로 통일.

### 1. `initPlayer` — 잔여 task 회수만, 시작 제거

```diff
- // NAS flush 타이머 시작 (중복 제거 후 재등록)
- handler.removeCallbacks(nasFlushTask);
- handler.postDelayed(nasFlushTask, NAS_FLUSH_INTERVAL_MS);
+ // 잔여 nasFlushTask 정리. 시작은 Playing 이벤트에서 (savePositionTask 와 동일 lifecycle)
+ handler.removeCallbacks(nasFlushTask);
```

### 2. Playing 이벤트 — 시작 추가

```diff
  handler.removeCallbacks(savePositionTask);
  handler.postDelayed(savePositionTask, SAVE_INTERVAL_MS);
+ handler.removeCallbacks(nasFlushTask);
+ handler.postDelayed(nasFlushTask, NAS_FLUSH_INTERVAL_MS);
```

### 3. Paused / Stopped 이벤트 — 정지 추가

```diff
  handler.removeCallbacks(savePositionTask);
+ handler.removeCallbacks(nasFlushTask);
  saveCurrentPosition();
```

### 4. EndReached 이벤트 — 정지 추가

```diff
  handler.removeCallbacks(savePositionTask);
+ handler.removeCallbacks(nasFlushTask);
```

## hide (앱 background) 처리는 별도 코드 불필요

홈/네비게이션 버튼으로 백그라운드 진입 시:

1. `Activity.onPause` → `mediaPlayer.pause()` (`MainActivity.java:1285`)
2. VLC 가 `Paused` 이벤트 발화
3. 위 §3 의 `removeCallbacks(nasFlushTask)` 가 자동 실행

→ 별도 onPause 핸들링 추가 안 함. 한 곳(이벤트 핸들러)에서만 lifecycle 관리.

`onPause` 의 `flushToNasBlocking` (`MainActivity.java:1284`) 은 그대로. 앱 종료 직전 마지막 write 보장 역할이라 timer lifecycle 과 무관.

## Trade-off

⏸️ 로 일시정지 후 앱을 그대로 두면 (onPause 안 옴) 다음 Playing 또는 onPause 까지 NAS 가 갱신되지 않는다. 다중 단말 cross-device resume 시 "다른 단말에서 ⏸️ 직후 내 단말에서 켜기" 시나리오는 최대 30초 지연.

- 일시정지 시점에 `saveCurrentPosition()` 1회는 호출됨 (`MainActivity.java:670`) → 로컬 DB + NAS 캐시 dirty 세팅은 보장.
- NAS 업로드 시점만 다음 trigger 까지 지연됨.
- onPause 시 `flushToNasBlocking` 으로 동기 업로드 → 백그라운드 진입 시점엔 안전.

사용자 confirm: A 안 (timer 만 멈춤, B 안의 즉시 1회 flush 추가는 불채택).

## 검증

```bash
./gradlew assembleDebug
# BUILD SUCCESSFUL
```

런타임 검증 (수기):
1. 영상 재생 시작 → logcat `[Save]` 5초 간격 + `NAS flush 성공` 30초 간격 로그
2. ⏸️ 일시정지 → 두 로그 모두 멈춤 확인
3. ▶️ 재개 → 두 로그 다시 시작 확인
4. 재생 중 홈 버튼 → onPause → `flushToNasBlocking 성공` 1회 + 두 timer 정지

## 영향 범위

`MainActivity.java` 내 4 hunk (lifecycle 라인 6줄 추가, 2줄 제거). 다른 파일·Activity·NasSyncManager 무수정. 비-XR / XR 양쪽 동일 동작.
