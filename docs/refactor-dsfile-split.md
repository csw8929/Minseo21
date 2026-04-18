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
