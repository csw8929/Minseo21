# Galaxy XR SBS 3D 재생 지원 — 개발 기록

**브랜치:** `feat/xr-support`  
**대상 단말:** Samsung Galaxy XR (SM-I610)  
**SDK:** `androidx.xr.scenecore:scenecore:1.0.0-alpha13` (alpha — API 변동 주의)

---

## 개요

삿치(Minseo21) libVLC 기반 영상 플레이어에 Galaxy XR 단말의 SBS(Side-by-Side) 3D 영상
양안 렌더링을 추가한다. XR 기기에서는 `SurfaceEntity`에 VLC 출력을 연결하고
Full Space 모드로 전환해 가상 시네마 룸 환경을 제공한다.

---

## 아키텍처

```
initPlayer()
  │
  ├─ isXrDevice? (3개 feature flag 체크)
  │    YES → --no-mediacodec-dr + --codec=mediacodec_jni,none
  │    NO  → --codec=mediacodec_ndk,mediacodec_jni,none (일반)
  │
  ├─ isSbsByName(파일명)?
  │    YES → setupStereoSurface() → SurfaceEntity[HOME_SPACE] + vlcVout.attachViews()
  │    NO  → mediaPlayer.attachViews(videoLayout) (일반 Surface)
  │
  └─ (파일명 감지 실패 시) logTracks() 에서 비율 폴백
         isSbsByRatio(videoW, videoH)? → setupStereoSurface()

Playing Event
  │
  └─ xrSbsMode=true → enterCinemaRoom()
           ├─ requestFullSpaceMode()         ← 2D 패널 → Full Space 전환
           ├─ preferredPassthroughOpacity=0  ← 패스스루 OFF (검은 배경)
           └─ [300ms 후] setupStereoSurface() ← FULL_SPACE에서 SurfaceEntity 재생성

Paused/Stopped/onPause
  └─ exitCinemaRoom()
           ├─ requestHomeSpaceMode()
           └─ preferredPassthroughOpacity=NO_PREFERENCE  ← 패스스루 복귀
```

---

## 핵심 파일

| 파일 | 역할 |
|---|---|
| `XrPlayerManager.kt` | XR 전용 로직 캡슐화. 비-XR 단말에서는 no-op |
| `MainActivity.java` | XR 상태 관리, VLC 이벤트 연동 |
| `AndroidManifest.xml` | `uses-feature` + `configChanges` |
| `XrPlayerManagerTest.kt` | 순수 함수 단위 테스트 (13개) |

---

## Galaxy XR 기기 감지

SM-I610은 `android.hardware.type.xr`을 **미지원**. 아래 3개 flag 중 하나로 판단:

```kotlin
val isXrDevice = hasSystemFeature("android.hardware.type.xr") ||      // 표준 XR
                 hasSystemFeature("android.software.xr.api.spatial") || // Galaxy XR
                 hasSystemFeature("android.hardware.xr.input.controller") // Galaxy XR
```

로그 예시:
```
SACH_XR: [isXrDevice] true (type.xr=false xr.api.spatial=true xr.input.controller=true)
```

---

## SBS 감지 방법

### 1. 파일명 패턴 (우선)
`isSbsByName(name)` — 대소문자 무관:

| 패턴 | 예 |
|---|---|
| `_sbs` | `movie_SBS.mkv` |
| `_3d` | `clip_3D.mp4` |
| `.sbs.` | `film.SBS.mkv` |
| `[sbs]` | `video [SBS].mkv` |
| `[3d]` | `show [3D].mp4` |

### 2. 해상도 비율 폴백 (logTracks 이후)
`isSbsByRatio(videoW, videoH)` — 가로/세로 ≥ 3.5

예: 3840×1080 = 3.55 → SBS 4K

---

## 디코더 설정

XR SurfaceEntity는 GL 기반. MediaCodec 직접 렌더링(DR)이 GL surface에 쓸 수 없어
**검은 화면**이 된다. XR 기기이면 DR을 항상 비활성화:

```java
// XR 기기 → DR 비활성 (SBS 여부와 무관하게 항상)
options.add("--codec=mediacodec_jni,none");
options.add("--no-mediacodec-dr");
```

> **주의:** 비율 감지 경로도 포함. 파일명 감지 후 비율 감지 경로로 전환해도 동일하게 DR 비활성.

---

## SurfaceEntity 재생성 흐름

HOME_SPACE에서 생성된 SurfaceEntity는 FULL_SPACE 전환 후 무효화될 수 있다.
`enterCinemaRoom()` 내 300ms 지연 후 재생성:

```kotlin
fun enterCinemaRoom() {
    s.scene.requestFullSpaceMode()
    // ... 패스스루 OFF ...
    handler.postDelayed({
        if (!inCinemaRoom) return@postDelayed
        setupStereoSurface(lastMediaPlayer!!)  // FULL_SPACE에서 새로 생성
    }, RECREATE_DELAY_MS)  // = 300ms
}
```

재생성 시 `vout.detachViews()` → 새 SurfaceEntity 생성 → `vout.attachViews()` 순서.

---

## XR 모드 컨트롤 UI

- **컨트롤 자동숨김 비활성**: 헤드셋에서 화면 탭이 어려우므로 `xrSbsMode=true`이면
  `showControls()`가 `resetHideTimer()`를 호출하지 않음.
- **Back 버튼**: 화면 우측 하단 빨간 버튼 (`btnBack`) → `finish()`.
- **Playing 이벤트**: `enterCinemaRoom()` 직후 `showControls()` 호출.
- **Paused/Stopped**: `exitCinemaRoom()` 호출 → 패스스루 복귀.

---

## 생명주기 안전 처리

| 이벤트 | 처리 |
|---|---|
| `onPause()` | `xrManager.onPause()` → `exitCinemaRoom()` (안전 패스스루 복귀) |
| `onDestroy()` | `xrManager.release()` → handler 취소 + SurfaceEntity dispose |
| Activity 재생성 | `configChanges`에 `uiMode` 추가 → `requestFullSpaceMode()` 시 재생성 방지 |

---

## 로그 태그 및 진단

모든 XR 관련 로그는 `SACH_XR` 태그:

```bash
adb -s R34YA0007ZJ logcat | grep SACH_XR
```

주요 로그 포인트:

| 메시지 | 의미 |
|---|---|
| `[isXrDevice] true` | XR 기기 감지 성공 |
| `[initPlayer] XR 기기 → --no-mediacodec-dr 항상 적용` | DR 비활성 확인 |
| `[initPlayer] isSbsByName='...' → true` | 파일명 SBS 감지 |
| `[logTracks] isSbsByRatio(3840,1080) → true` | 비율 SBS 감지 |
| `setupStereoSurface 결과=true` | SurfaceEntity 연결 성공 |
| `시네마 룸 진입 — requestFullSpaceMode() 호출` | Full Space 전환 요청 |
| `시네마 룸 — 300ms 후 SurfaceEntity 재생성 시작` | 재생성 타이머 발동 |
| `[configChanged] uiMode=...` | XR config 변경 발생 여부 (진단용) |

---

## 알려진 제약사항 / 미해결 이슈

| 항목 | 상태 |
|---|---|
| `isHeadMounted=false` 시 XR 컴포지터 비활성 | 테스트 시 헤드셋 반드시 착용 필요 |
| `RECREATE_DELAY_MS = 300ms` 근거 | API 콜백 없음 → 경험적 값, 시스템 부하 시 불충분 가능 |
| XR 컨트롤러/핸드트래킹 입력 | 미구현 (Full Space에서 2D 패널 탭 방식 유지) |
| Top-Bottom SBS 포맷 | 미지원 (SurfaceEntity.StereoMode.SIDE_BY_SIDE만 사용) |
| alpha API 불안정성 | scenecore:1.0.0-alpha13 — 버전 업그레이드 시 API 확인 필수 |

---

## 커밋 히스토리

| 커밋 | 내용 |
|---|---|
| `8ac767e` | 초기 XR 지원 추가 (XrPlayerManager.kt, 빌드 설정) |
| `c664d3a` | 비율 감지 연결, 상세 로그, Back 버튼 |
| `888c04b` | Galaxy XR feature flag 3종 감지 |
| `bc93b0a` | --no-mediacodec-dr DR 비활성 |
| `b05183e` | requestFullSpaceMode() 추가 |
| `7e3b311` | FULL_SPACE 후 SurfaceEntity 재생성 + 컨트롤 자동숨김 비활성 |
| `5ca0df1` | **plan-eng-review 반영**: DR 통합 + configChanges + 단위 테스트 |
