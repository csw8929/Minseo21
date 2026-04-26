# SBS 최소 이전 + SbsPlayerActivity 신설 (2026-04-26)

`feat/xr-2d-restart` 브랜치. **옵션 B 실행 라운드** — wip 브랜치(`wip/xr-2026-04-26-features`, commit `7ce278e`) 의 SBS 코드를 핵심 4가지 조합만 추출해 별 Activity 로 분리.

## 결정 (office-hours 디자인 doc — `~/.gstack/projects/csw8929-Minseo21/USER-feat-xr-2d-restart-design-20260426-183523.md`)

- MainActivity 는 **2D 전용 + Home Space** 그대로 유지 — 매니페스트 property 무수정. system mainPanel decoration(grab/resize) 정상 동작.
- `SbsPlayerActivity extends MainActivity` + protected hook 3개 — 모든 재생 기능(이어보기 / 자막 / 오디오 / 스피드 / 즐겨찾기 / Playlist) 자동 inherit.
- FileListActivity 가 파일명 SBS keyword 검출 시 SbsPlayerActivity 로 routing + Bundle launch.

## 가져온 / 버린 분류

### 가져옴 (essential SBS — 결정적 4가지 조합 + plumbing)

1. **Bundle launch** — `XrFullSpaceLauncher.java` (62줄, wip 그대로). `LaunchUtils.createBundleForFullSpaceModeLaunch()` 호출.
2. **Samsung property 값** — `SbsPlayerActivity` 매니페스트 `XR_ACTIVITY_START_MODE_Full_Space_Activity` (Google FULL_SPACE_MANAGED 는 Galaxy XR 에서 작동 X).
3. **MediaBlendingMode.OPAQUE 명시** — `XrSurfaceController.setupStereoSurface` 안 (default TRANSPARENT 면 검은 화면).
4. **setSurfacePixelDimensions(1920, 1080)** — 동상. (frame mismatch 방지)
5. SBS 검출 (파일명 keyword) + Quad shape / 위치 / pixel dim — `XrConfig.kt` 50줄.
6. Movable + Resizable + 영상 비율 적용 (Full-SBS / Half-SBS 자동 구분) — `XrSurfaceController.kt` 150줄.

### 버림 (필요없음)

1. **시네마룸 fade** (`enterCinemaRoom` / `exitCinemaRoom` / `passthroughAnimator` / `startPassthroughFade` / `forcePassthroughRestore` 등 ~150줄) — TODO-XR-2 보류 결정. Samsung 기본 환경으로 충분.
2. **mainPanel 작업** (`setupMainPanelSpatial` / `shrinkMainPanelForSbs` / `restoreMainPanelSize` / `release` 의 panel reset) — SDK alpha13 한계 결론 (4가지 옵션 조합 시도 후 system 무시). SbsPlayerActivity 분리로 자체 mainPanel 안 건드림.
3. **`XrPlaybackController.java`** (175줄) — Lifecycle observer + state machine. SbsPlayerActivity 가 직접 init/release 하면 충분.
4. **300ms surface 재생성** — Bundle launch 가 즉시 Full Space 진입시켜 재생성 자체 불필요. SIGSEGV(systemui SpaceFlinger) 위험도 회피.
5. **`requestFullSpaceMode()` / `requestHomeSpaceMode()` runtime 호출** — Bundle launch + onDestroy 자연 흐름으로 충분.
6. **audio 가상 surround** — 이미 폐기 (메모리 `project_xr_audio_decommissioned.md`).
7. **색강화 옵션 XR 분기** — 현재 brunch 가 universal 로 이미 적용 (saturation=1.15 / contrast=1.05).
8. **layout 회귀** (btnXrBack 으로 분리 / paddingBottom 8dp) — 현재 brunch 의 universal Back + WindowInsets 처리 유지.

## 변경 요약

```
변경: 5 files, +79 -3
신규: 4 files

app/build.gradle.kts                                      |  8 ++
app/src/main/AndroidManifest.xml                          | 19 +
app/src/main/java/com/example/minseo21/FileListActivity.java | 24 +
app/src/main/java/com/example/minseo21/MainActivity.java     | 29 +
gradle/libs.versions.toml                                    |  2 +

app/src/main/java/com/example/minseo21/SbsPlayerActivity.java               (신규, 89줄)
app/src/main/java/com/example/minseo21/xr/XrConfig.kt                        (신규, 84줄)
app/src/main/java/com/example/minseo21/xr/XrFullSpaceLauncher.java          (신규, 60줄)
app/src/main/java/com/example/minseo21/xr/XrSurfaceController.kt            (신규, 178줄)
```

총 ~440줄 (wip 브랜치 ~1050줄 대비 ~58% 감축).

## 핵심 파일

### `XrConfig.kt` (slim)

`isXrDevice(pm)` + `sbsPatternMatch(name)` + `sbsRatioMatch(w,h)` + `SCREEN_POSE` / `SCREEN_SHAPE` / `SURFACE_PIXEL_DIM`.

색강화 / 시네마룸 timing / mainPanel size 등은 제거.

### `XrFullSpaceLauncher.java`

`LaunchUtils.createBundleForFullSpaceModeLaunch(session, bundle)` 로 Bundle 생성 후 `startActivity(intent, opts)`. 비-XR 단말 fallback. wip 코드 그대로.

### `XrSurfaceController.kt`

XrPlayerManager.kt(492줄) 에서 SBS 핵심만 추출:

- `init()` — Session.create
- `setupStereoSurface(mp)` — SurfaceEntity 생성 + OPAQUE + setSurfacePixelDimensions + vlcVout.setVideoSurface + attachInteraction
- `attachInteraction(s)` — Movable + Resizable
- `applyVideoAspect(w, h, isSbs)` — Full-SBS / Half-SBS 자동 구분
- `release()` — surfaceEntity.dispose

### `MainActivity.java` 변경

protected hook 3개 추가 (기본 no-op / false):

```java
protected void onConfigureVlcOptions(List<String> options) { }
protected boolean attemptStereoTakeover(MediaPlayer mp, String sourceName) { return false; }
protected void onVideoTrackInfo(MediaPlayer mp, int videoW, int videoH) { }
```

`initPlayer` 에서 호출 지점 삽입 — 옵션 빌드 마무리 / mediaPlayer 생성 직후 / videoW·videoH 확정 직후.

### `SbsPlayerActivity.java`

```java
public class SbsPlayerActivity extends MainActivity {
    private XrSurfaceController xr;

    @Override protected void onCreate(...) {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(...);
        findViewById(R.id.root).setBackgroundColor(Color.TRANSPARENT);
        xr = new XrSurfaceController(this); xr.init();
    }
    @Override protected void onConfigureVlcOptions(List<String> options) {
        options.removeIf(...); options.add("--codec=mediacodec_jni,none"); options.add("--no-mediacodec-dr");
    }
    @Override protected boolean attemptStereoTakeover(MediaPlayer mp, String name) {
        if (!XrConfig.sbsPatternMatch(name)) return false;
        if (!xr.setupStereoSurface(mp)) return false;
        findViewById(R.id.videoLayout).setVisibility(View.GONE);
        return true;
    }
    @Override protected void onVideoTrackInfo(MediaPlayer mp, int w, int h) {
        xr.applyVideoAspect(w, h, true);
    }
    @Override protected void onDestroy() { xr.release(); super.onDestroy(); }
}
```

### `FileListActivity.java` 라우팅

```java
private void launchPlayer(Intent intent, String name) {
    if (XrConfig.isXrDevice(getPackageManager()) && XrConfig.sbsPatternMatch(name)) {
        intent.setClass(this, SbsPlayerActivity.class);
        xrLauncher.startActivity(this, intent);   // Bundle launch
    } else {
        startActivity(intent);                    // 기존 그대로
    }
}
```

`playNasVideo` / `playVideo` 두 곳 모두 `startActivity(intent)` → `launchPlayer(intent, title)` 로 교체.

### Manifest 변경

```xml
<uses-feature android:name="android.hardware.type.xr" android:required="false" />
<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />
<uses-feature android:name="android.software.xr.immersive" android:required="false" />

<activity android:name=".SbsPlayerActivity"
          android:exported="false"
          android:resizeableActivity="true"
          android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|keyboard|navigation">
    <property android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
              android:value="XR_ACTIVITY_START_MODE_Full_Space_Activity" />
</activity>
```

MainActivity 매니페스트 entry 는 무수정 (현재 brunch 의 깨끗한 상태 유지).

## 검증

### 빌드

```
./gradlew assembleDebug
BUILD SUCCESSFUL in 16s
```

APK: `app/build/outputs/apk/debug/Minseo21.apk` (272 MB).

### 실기기

- [x] **R34YA0007ZJ (Galaxy XR)** — `_sbs` 영상 → SbsPlayerActivity → Full Space + SurfaceEntity 양안 분리 렌더링 ✅ (2026-04-26 검증)
- [ ] **R34YA0007ZJ** — 일반 2D 영상 → MainActivity → Home Space + system mainPanel decoration 정상
- [ ] **R34YA0007ZJ** — SBS 영상 종료(back) 시 FileListActivity 가 깨끗한 panel 위치로 복귀
- [ ] **R34YA0007ZJ** — SbsPlayerActivity 의 onPause 시 자동 Home Space 복귀 여부 (필요 시 명시 호출 추가)
- [ ] **R54Y1003KXN (탭)** — 모든 영상 MainActivity 로 routing, 회귀 0
- [ ] **R3CT70FY0ZP (폴드) / R3CX705W62D (플립)** — XR 분기 가드(`isXrDevice`) 동작 확인

> **검증 과정 5라운드 디버깅 history:** [`sbs-debugging-history-2026-04-26.md`](sbs-debugging-history-2026-04-26.md) — Java 객체 초기화 순서 vs Android `onCreate` 동기 흐름의 race condition 발견 + 4번의 SDK 가설 헛다리.

## Open Questions / 후속

1. ~~**application-level XR property 2개**~~ — 5라운드 디버깅 중 추가됨(`sbs-debugging-history-2026-04-26.md` Round 5). race condition 의 진짜 원인은 아니었지만 spatial 안전망으로 유지.
2. **비율 폴백** — 첫 라운드 파일명 keyword 만. 사용자가 `_sbs` 표기 의무. 향후 `MediaMetadataRetriever` pre-launch probe 또는 launch 후 비율 검출 + UX.
3. **SbsPlayerActivity onPause** — Bundle launch 후 system 자동 Home Space 복귀 여부 검증. 안 하면 명시 `requestHomeSpaceMode()` 추가.
4. **ResizableComponent 의 `isFixedAspectRatioEnabled` 활성 시점** — `applyVideoAspect` 가 Quad shape 갱신 후 활성화. 트랙 정보 도착 전 사용자가 리사이즈 시 비율 깨짐 (드문 시나리오).

## 보존

wip commit `7ce278e` on `wip/xr-2026-04-26-features` — 4/26 작업 전부 살아있음. 향후 시네마룸 / mainPanel 등 재시도 시 cherry-pick 으로 가져옴.
