# DsFileApiClient 분리 리팩토링 (2026-04-18)

## 목적
1075 줄짜리 `DsFileApiClient` 를 책임별로 4개 클래스로 쪼개 유지보수성을 높인다. 외부 호출 시그니처는 한 줄도 바꾸지 않는다. 빌드 성공 + 기존 호출부 무수정.

## 변경 요약

**Before**
```
DsFileApiClient.java  1075 줄
```
- HTTP IO, 인증, FileStation, VideoStation 트랜스코딩, 재생 위치 동기화가 한 파일에 뒤섞임
- private static 필드(SID/resolvedBase/fileIdCache/executor)를 직접 참조하는 메서드가 다층으로 엮여 테스트/교체가 어려움

**After**
```
DsFileApiClient.java   142 줄   ← 파사드 (중첩 타입 + 정적 위임)
DsHttp.java            263 줄   ← HTTP 저수준 IO
DsAuth.java            179 줄   ← 자격증명 + SID + resolvedBase + 네트워크 모니터
DsFileStation.java     433 줄   ← 폴더 목록, file_id, HLS 세션, URL 헬퍼
DsPlayback.java        195 줄
────────────────────────────
총                    1212 줄   (+137 — 중복 import + javadoc 비용)
```

## 클래스 책임

### `DsHttp` — HTTP 저수준 IO
- `httpGet` — 수동 리다이렉트(최대 5회) + 쿠키 누적 + User-Agent 스푸핑
- `httpPost`, `httpPostForm` — JSON / form-encoded 요청
- `uploadFile` — multipart/form-data (FileStation Upload)
- `openTrustedConnection` — 자체 서명 TLS 허용
- `probeUrl` — LAN base URL 3초 타임아웃 probe
- **상태 없음(stateless).** Synology 의존성 없음 — 재사용 가능

### `DsAuth` — 세션 소유자
- 필드: `cachedSid`, `resolvedBase`, `cfgBaseUrl/LanUrl/User/Pass/BasePath/PosDir`
- `executor` (단일 스레드 ExecutorService) + `mainHandler` (메인 스레드 콜백용)
- `init(...)` — 자격 증명 교체 시 SID/resolvedBase/fileIdCache 모두 무효화
- `login()`, `reLoginSync()` — SYNO.API.Auth 로그인 (비동기 + 동기)
- `resolveBase()` — LAN_URL probe 성공 시 LAN, 실패 시 cfgBaseUrl (DDNS/공인 IP)
- `startNetworkMonitoring()` — WiFi↔5G 전환 감지해 캐시 무효화

### `DsFileStation` — FileStation + VideoStation
- `listFolder` — 폴더 목록 (105/106 에러 시 1회 재로그인 재시도)
- `findFileIdForSharePath` — sharepath → Video Station file_id 매핑 (Movie → TVShow → Episode 순 탐색, `fileIdCache` 로 반복 조회 회피)
- `openTranscodeStream` / `closeTranscodeStream` — HLS remux 세션 (ffmpeg 누수 방지 계약)
- `getStreamUrl` — resolvedBase 경유 SID 부착 URL
- `getCanonicalUrl` — cfgBaseUrl 기반 DB 키 (기기 간 일관성)
- `canonicalToStream` — 이어보기 다이얼로그용 (canonical + 현재 SID)
- `toCanonicalUrl` — 재생 중 URL 을 DB 키로 정규화 (_sid 제거)
- `isWifi` — 셀룰러가 하나라도 있으면 false (HLS 강제 폴백)

### `DsPlayback` — 재생 위치 JSON 동기화
- per-file: `savePositionToNas`, `loadPositionFromNas` — `pos_<hash>.json`
- user-bundle: `uploadUserPositions`, `downloadUserPositions` — `<user>_positions.json`
- Sync 버전: `uploadUserPositionsSync`, `downloadUserPositionsSync` — onPause 블로킹 flush

### `DsFileApiClient` — 파사드
- 모든 public static 메서드 유지 (위임)
- `Callback<T>` 중첩 인터페이스 유지 (호출부 `DsFileApiClient.Callback<...>` 참조 다수)
- `TranscodeSession` 중첩 클래스 유지

## 호출부 영향

**없다.** 다음 모든 파일에서 `DsFileApiClient.xxx` 호출 시그니처가 동일하게 작동한다:
- `FileListActivity.java` (29곳)
- `MainActivity.java` (6곳)
- `NasSyncManager.java` (6곳)
- `NasSetupActivity.java` (4곳)
- `NasHlsSource.java` (4곳)
- `PlaybackSource.java` (1곳)

## 의존 방향

```
DsFileApiClient (파사드)
    ├─→ DsAuth
    ├─→ DsFileStation ──→ DsAuth, DsHttp
    └─→ DsPlayback    ──→ DsAuth, DsFileStation, DsHttp
DsAuth ──→ DsHttp, DsFileStation.clearFileIdCache
```

순환 참조 1건: `DsAuth.init()` → `DsFileStation.clearFileIdCache()`. fileIdCache 는 credential 교체 시 무효화되어야 하므로 의도된 의존.

## 공유 상태 (package-private)

- `DsAuth.cachedSid` / `DsAuth.resolvedBase` — volatile, 크로스 클래스 읽기
- `DsAuth.cfgBaseUrl` / `cfgPosDir` / `cfgUser` — URL 조합용 읽기
- `DsAuth.executor` / `DsAuth.mainHandler` — 모든 비동기 작업 공유

이 필드들은 원본에서 `private static` 이었고, 분리 후 `package-private (static)` 로 열어 같은 패키지 내 접근만 허용. public 노출 아님.

## 잔여 과제

1. **PlaybackSource.onReconnect()** — 네트워크 전환 시 SID 재발급 로직(현재 `MainActivity` L654–680)을 `PlaybackSource` 추상 메서드로 이동 고려.
2. **테스트 커버리지** — `DsHttp` 는 상태가 없어 unit test 대상으로 적합. `DsAuth` 는 `DsHttp` mock 주입이 가능하도록 리팩토링 여지 있음.

## 검증

```
./gradlew assembleDebug
BUILD SUCCESSFUL in 2s
```

---

# 이슈 로그

세션 중 발견/수정된 버그. 최신 순.

## ISSUE-001 — 5G/외부망에서 NAS 접속 실패 (QuickConnect URL 잔존)

**발견**: 2026-04-18
**해결**: 2026-04-18
**상태**: 해결됨 (UI 재설정)

### 증상
- 5G 셀룰러 환경에서 NAS 탭 접근 시 즉시 에러:
  ```
  연결 실패: NAS 연결 오류: Value <!doctype of type java.lang.String cannot be converted to JSONObject
  ```
- WiFi→5G 전환 후에도 동일 증상 지속.

### 원인
`NasCredentialStore` 에 저장된 `baseUrl` 이 QuickConnect 포털 URL
(`https://gomji17.tw3.quickconnect.to`). logcat:
```
외부 연결: https://gomji17.tw3.quickconnect.to
HTTP 307 → http://gomji17.quickconnect.to/webapi/auth.cgi...    (HTTPS→HTTP 다운그레이드)
HTTP 200 ← <!doctype html...                                     (포털 HTML)
JSONException: Value <!doctype of type java.lang.String cannot be converted to JSONObject
```

메모리 기록대로 `gomji17.quickconnect.to` 는 포털 HTML 만 반환 —
릴레이 핸드셰이크 없이는 실제 DSM 에 도달 불가. 릴레이 코드는 이미 제거됨
(사용자 지시 "Relay 관련 코드는 모두 제거해줘"). 구 prefs 값이 잔존한 것.

### 해결
앱 메뉴 → **NAS 설정** → "기본 URL" 필드를 DDNS 로 교체:
```
https://gomji17.synology.me:5001
```
- SK 브로드밴드 포트포워딩 5000/5001 오픈 완료 (2026-04-17, 메모리 기록)
- 공인 IP 직결 또는 DDNS 어느 쪽이든 HTTPS 리스너로 JSON 응답 받음

### 후속
- **코드 가드 추가 여부**: `DsAuth.login()` 에서 `quickconnect.to` 감지 시
  명시 에러로 조기 실패 (미적용 — UI 재설정으로 우선 해결).
- **마이그레이션**: 기존 사용자도 같은 stale prefs 가능 → 앱 첫 실행 시
  `NasCredentialStore.getBaseUrl()` 이 quickconnect.to 포함이면 자동으로
  `DsFileConfig.BASE_URL` fallback 로 덮어쓰는 것도 고려.

## ISSUE-002 — 첫 재생 시 이어보기 시드 미반영 (NAS 캐시 레이스)

**발견**: 2026-04-18
**해결**: 2026-04-18
**상태**: 해결됨 (B안 — Application 싱글턴)

### 증상
즐겨찾기 화면에는 12분 위치가 표시되지만, **해당 파일을 처음 재생할 때**
`pos=0ms` 부터 시작. 같은 파일을 다시 재생하면 이어보기가 정상 동작.

### 원인 — MainActivity 의 NasSyncManager 생성 타이밍
`MainActivity.onCreate()` 가 **새** `NasSyncManager` 를 생성
(MainActivity.java:162). 생성자에서 `loadAllPositionsFromNas(null)` 를
백그라운드로 호출하지만, `beginPlayback()` 이 즉시 진행되어
`loadSavedPosition()` 이 캐시가 채워지기 전에 실행됨.

logcat (17:26:38 1회차 재생):
```
17:26:38.074  [버퍼링 시작 #1] pos=0ms              ← 재생 시작
17:26:38.225  HTTP 200 ← csw8929_positions.json    ← NAS 캐시 download 완료
17:26:38.226  downloadUserPositions: 35 항목       ← 150ms 늦음
```

`NasSyncManager.loadPosition()` 은 `positionsCache.length() == 0` 이면
NAS 비교를 건너뛰고 Room DB 만 사용 (NasSyncManager.java:354). 데이터 삭제
직후라 Room DB 도 비어있어 **null 위치 → 0ms 재생**.

두 번째 재생은 캐시가 이미 채워져 있어 772288ms (12:52) 로 정상 시크.

### 구조적 문제
- `NasSyncManager` 가 Activity 스코프 (`FileListActivity` / `MainActivity` 각자 보유)
- 두 액티비티 모두 생성자에서 중복 download 호출 (logcat 17:26:35 + 17:26:38)
- 캐시가 액티비티 전환 경계에서 공유되지 않음

### 해결 (B안 — Application 싱글턴)
- `NasSyncManager.getInstance(Context)` 싱글턴 + 내부 전용 `ExecutorService`
  (액티비티 생명주기와 독립).
- `FileListActivity.fetchNasAndShowResume()` 의 download 결과를
  `seedCache(positions)` 로 싱글턴에 주입 → `MainActivity` 는 재다운로드 없이
  캐시 즉시 사용.
- `NasSyncManager.loadPosition()` 에 lazy-load 백업 경로 추가: 캐시가 null 이고
  SID 가 있으면 `loadAllPositionsFromNas` 후 NAS 비교 재시도. 다이렉트 intent
  로 `MainActivity` 가 단독 실행될 때도 동작.
- 생성자 자동 download 제거: SID 없는 시점에 호출되면 빈 캐시가 고정되는 문제
  방지 (seedCache 또는 lazy-load 로 대체).
