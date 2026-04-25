# Galaxy XR — API / 매니페스트 레퍼런스 (Minseo21)

**작성일:** 2026-04-25
**브랜치:** `feat/xr-support`
**대상 단말:** Samsung Galaxy XR (SM-I610) — adb serial `R34YA0007ZJ`
**OS:** Android 14, build `xrvst2ue UML2.260301.001`
**SDK:** Jetpack XR `androidx.xr.scenecore:scenecore:1.0.0-alpha13` (+ runtime, runtime-interfaces, scenecore-runtime, scenecore-spatial-core, scenecore-spatial-rendering)
**플레이어:** libVLC `org.videolan.android:libvlc-all:3.6.0`

이 문서는 Galaxy XR 에서 libVLC 기반 SBS 3D 재생을 동작시키기까지 검증된 매니페스트
선언, API 호출, 실패한 접근, 디버깅 절차를 한곳에 모은 레퍼런스이다.
시점 기록은 [`xr-sbs-3d-playback.md`](./xr-sbs-3d-playback.md), 코드 위치는
`com.example.minseo21.xr` 패키지를 참고.

---

## 1. AndroidManifest.xml — 전수 항목 분석

파일: [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)

### 1.1 `uses-feature`

```xml
<uses-feature android:name="android.hardware.type.xr" android:required="false" />
<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />
<uses-feature android:name="android.software.xr.immersive" android:required="false" />
```

| feature | Galaxy XR(SM-I610) | 역할 |
|---|---|---|
| `android.hardware.type.xr` | **미지원** | 일반 XR 단말 식별 — Galaxy XR 은 보고하지 않음 |
| `android.software.xr.api.spatial` | **지원** | spatial API 사용 가능 표시 — 핵심 |
| `android.software.xr.immersive` | **지원** | immersive 모드 가능 — Spatial Film 매니페스트 참고해 추가 |

`required="false"` 로 두면 비-XR 단말에서도 정상 설치된다. 단말 검출은
`PackageManager.hasSystemFeature` 로 세 feature 중 어느 하나라도 true 면 XR 로 판정.
`XrPlayerManager.kt:60-64` 참조.

### 1.2 application 전역 property

```xml
<property
    android:name="android.window.PROPERTY_ACTIVITY_XR_FLAGS"
    android:value="system_app_integration_supported|turn_screen_on" />
<property
    android:name="android.window.PROPERTY_XR_BOUNDARY_TYPE_RECOMMENDED"
    android:value="XR_BOUNDARY_TYPE_NO_RECOMMENDATION" />
```

Spatial Film(`com.hughhou.spatialfilm`) APK 의 매니페스트 덤프에서 발견. 두 항목 모두
공식 문서에 명시되지 않은 Samsung 전용. 없어도 빌드는 되지만 Galaxy XR 환경
통합(시스템 앱 흐름, 화면 깨우기 동작)이 어색해진다.

### 1.3 Activity 별 `PROPERTY_XR_ACTIVITY_START_MODE`

```xml
<activity android:name=".FileListActivity" ...>
    <property
        android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
        android:value="XR_ACTIVITY_START_MODE_Home_Activity" />
</activity>

<activity android:name=".MainActivity"
    android:resizeableActivity="true"
    android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|keyboard|navigation">
    <property
        android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
        android:value="XR_ACTIVITY_START_MODE_Full_Space_Activity" />
    ...
</activity>
```

**주의 — Google docs 의 값은 Galaxy XR 에서 작동하지 않음.**

| 출처 | 권장 값 (Full Space) | Galaxy XR 동작 |
|---|---|---|
| Google 공식 문서 | `FULL_SPACE_MANAGED` | **거부됨** — `SpatialApi(requestEnabled=false)` 로 SBS 렌더 안 됨 |
| Spatial Film 매니페스트 | `XR_ACTIVITY_START_MODE_Full_Space_Activity` | **동작** |

값 발견 경로: `aapt dump xmltree spatialfilm_base.apk AndroidManifest.xml` 로 매니페스트
덤프 후 `PROPERTY_XR_ACTIVITY_START_MODE` 검색.

추가 속성:
- `resizeableActivity="true"` — XR Managed 모드 요구사항.
- `configChanges` 에 `keyboard|navigation` 포함 — 외부 입력 변경 시 Activity 재생성으로
  XR Session 무효화되는 회귀 방지 (alpha13 현상 관측).
- `screenOrientation` 제거 — Full Space 는 orientation 개념이 없음.

### 1.4 intent-filter

`FileListActivity` 는 `LAUNCHER`, `MainActivity` 는 `VIEW` (video/* MIME, http/https 스트리밍,
HLS, 그리고 mp4·mkv·avi·mov·wmv·ts·m4v·3gp·webm·flv 확장자 매칭). XR 추가로 변경된 부분
없음.

---

## 2. Full Space 진입 — Bundle launch 가 진짜 trigger

매니페스트 property `PROPERTY_XR_ACTIVITY_START_MODE = Full_Space_Activity` 만으로는
**Full Space 진입이 발동되지 않는다.** Galaxy XR 의 SystemUI(DesktopTasksController) 가
Full Space 전환을 처리하려면 launching Activity 가 ActivityOptions Bundle 을
명시 전달해야 한다.

### 2.1 메커니즘

```
FileListActivity.startActivity(intent, opts)
   │
   └─→ SystemUI.DesktopTasksController
          └─→ WindowContainerTransaction
                 └─→ addXrWindowOperation(mXrDesktopMode = 2)   // 2 = FULL_SPACE
```

`mXrDesktopMode=2` 가 logcat 에 찍히면 정상 진입.
- 0 = HOME_SPACE
- 2 = FULL_SPACE

### 2.2 코드

[`xr/XrFullSpaceLauncher.java`](../app/src/main/java/com/example/minseo21/xr/XrFullSpaceLauncher.java)
가 캡슐화. 핵심:

```java
Bundle opts = androidx.xr.scenecore.LaunchUtils
        .createBundleForFullSpaceModeLaunch(xrSession, new Bundle());
activity.startActivity(intent, opts);
```

- `LaunchUtils` 는 top-level Kotlin 함수 모음. Java 에서는 `LaunchUtils.createBundleForFullSpaceModeLaunch(...)` 정적 호출.
- `xrSession` 은 `androidx.xr.runtime.Session.Companion.create(activity)` 로 생성 (FileListActivity onCreate 시).
- 비-XR 단말이면 launcher 가 일반 `activity.startActivity(intent)` 로 폴백.

### 2.3 실패한 접근 (참고)

| 시도 | 결과 |
|---|---|
| 매니페스트 property `FULL_SPACE_MANAGED` 만 선언 | `requestEnabled=false` 거부, SBS 렌더 X |
| `FullSpaceModeMode.FULL_SPACE` 같은 SDK enum 검색 | alpha13 에 해당 enum 없음 |
| `requestFullSpaceMode()` 만 호출 (Bundle 없이 startActivity) | DesktopTasksController 가 trigger 안 함 |
| Activity 의 `onResume` 에서 Bundle 재구성 | 너무 늦음 — 진입 결정은 launching activity 의 startActivity 시점 |

---

## 3. SurfaceEntity — libVLC SBS 양안 렌더링

**3D 영상 렌더의 핵심.** libVLC 가 그리는 단일 좌우 합성 프레임을 `SurfaceEntity` 가
양안 시점으로 분리해 헤드셋에 표시한다.

### 3.1 생성

```kotlin
val entity = SurfaceEntity.create(
    session,
    SCREEN_POSE,                       // Pose(Vector3(0f, 0.3f, -3.0f))
    SCREEN_SHAPE,                      // Shape.Quad(FloatSize2d(3.2f, 1.8f))
    SurfaceEntity.StereoMode.SIDE_BY_SIDE,
)
```

`StereoMode.SIDE_BY_SIDE` 가 좌우 분할 SBS, `TOP_BOTTOM` 은 상하 분할. Galaxy XR 은
SBS 만 검증.

### 3.2 검증된 4점 솔루션

`XrPlayerManager.setupStereoSurface` 가 모두 적용. 하나라도 빠지면 검은 화면.

#### (1) `MediaBlendingMode.OPAQUE`

```kotlin
entity.mediaBlendingMode = SurfaceEntity.MediaBlendingMode.OPAQUE
```

- 기본값 `TRANSPARENT`. 비디오 alpha 채널을 그대로 사용 → libVLC 가 0 alpha 로
  덮어쓰면 비디오가 투명 렌더되어 panel/배경이 비쳐 보임.
- 사용자 관측: "가끔 색이 약간 바뀌고 대부분 검정" — 영상이 그려지지만 거의 알파 0.

#### (2) `setSurfacePixelDimensions(1920, 1080)`

```kotlin
@Suppress("OPT_IN_USAGE")
entity.setSurfacePixelDimensions(IntSize2d(1920, 1080))
```

- libVLC 의 비디오 프레임 크기와 SurfaceEntity 의 EGLSurface 버퍼 크기를 일치시킴.
- 누락 시 frame 크기 mismatch 로 GPU 블릿이 부분 실패 → 간헐적 검은 화면.
- API 가 `@ExperimentalSurfaceEntityPixelDimensionsApi` 라 파일 상단에 OptIn 필요:
  ```kotlin
  @file:OptIn(androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi::class)
  ```

#### (3) Bundle launch (이미 §2 에서 다룸)

매니페스트 property 보다 Bundle launch 가 결정적.

#### (4) Samsung 전용 매니페스트 property 값 (이미 §1.3)

`Full_Space_Activity` / `Home_Activity`.

### 3.3 alpha / parent 안전망

```kotlin
entity.setAlpha(1.0f)
if (entity.parent == null) entity.parent = session.scene.activitySpace
```

- alpha13 에서 default alpha 가 0 으로 보고된 사례 관측 → 명시 1.0.
- parent 가 null 인 채로 떠 있는 경우도 관측 → activitySpace 에 명시 attach.
- 검증된 동작에서는 둘 다 default 일 수도 있으나, 회귀 방지 안전망으로 유지.

`enterCinemaRoom` 에서 적용. `XrPlayerManager.kt:293-313`.

### 3.4 libVLC 출력 연결

```kotlin
val xrSurface: Surface = entity.getSurface()
val vout = mediaPlayer.vlcVout
if (vout.areViewsAttached()) vout.detachViews()
vout.setVideoSurface(xrSurface, null)
vout.attachViews()
```

`attachViews()` 호출 후 libVLC 가 `Vout` 이벤트를 발화하면 정상.

### 3.5 libVLC 옵션

XR 단말일 때 `XrPlaybackController.applyVlcOptions` 가 강제:

```
--codec=mediacodec_jni,none      // mediacodec_ndk 제거 후 jni 만
--no-mediacodec-dr               // Direct Rendering 비활성
```

- `mediacodec_ndk` 는 GL EGLImage 기반 출력과 충돌해 SBS 검은 화면 유발.
- Direct Rendering 이 켜지면 디코더가 자체 surface 에 직접 그려서 우리 SurfaceEntity 로
  안 옴 → 이 옵션 없으면 영상 안 보임.

---

## 4. 사용자 인터랙션 — Movable / Resizable

### 4.1 영상 Quad

```kotlin
val movable = MovableComponent.createSystemMovable(session, /* scaleInZ = */ true)
movable.addMoveListener(overlapTrackingListener)
surfaceEntity.addComponent(movable)

val resizable = ResizableComponent.create(session) { event: ResizeEvent ->
    surfaceEntity.shape = SurfaceEntity.Shape.Quad(
        FloatSize2d(event.newSize.width, event.newSize.height))
    checkAndNotifyOverlap()
}
surfaceEntity.addComponent(resizable)
```

- `createSystemMovable(scaleInZ=true)` — 사용자가 Z 축으로 끌면 비율 스케일도 같이 변함.
- `ResizeEvent.newSize` 는 `FloatSize3d`. Quad 는 width/height 만 사용.
- **Resize 이후 Quad shape 갱신 필수** — 안 하면 사용자 인지 크기와 실제 렌더 크기가 어긋남.

### 4.2 Spatial Panel (Activity main panel)

```kotlin
val movable = MovableComponent.createSystemMovable(session)
movable.addMoveListener(overlapTrackingListener)
session.scene.mainPanelEntity.addComponent(movable)
```

- `Scene.mainPanelEntity` 는 `MainPanelEntity` (PanelEntity 상속). Activity 의
  contentView 가 그려지는 표면.
- Resizable 은 부착 안 함 (현재) — 컨트롤 UI 가 layout 으로 고정 크기 가정.

### 4.3 EntityMoveListener

```kotlin
override fun onMoveUpdate(entity, currentInputRay, currentPose, currentScale) {
    checkAndNotifyOverlap()
}
override fun onMoveEnd(entity, finalInputRay, finalPose, finalScale, updatedParent) {
    checkAndNotifyOverlap()
}
```

`onMoveStart` 도 있으나 사용 안 함. `currentPose` / `finalPose` 의 `translation.x/y/z`
가 실제 이동 좌표.

### 4.4 겹침 판정 (xy AABB)

```kotlin
val panel = session.scene.mainPanelEntity
val panelPose = panel.getPose(); val panelSize = panel.size            // FloatSize2d
val surfPose = surfaceEntity.getPose()
val quad = surfaceEntity.shape as SurfaceEntity.Shape.Quad
val surfExtents = quad.extents                                          // FloatSize2d

val dx = abs(panelPose.translation.x - surfPose.translation.x)
val dy = abs(panelPose.translation.y - surfPose.translation.y)
val sumHalfX = (panelSize.width  + surfExtents.width)  / 2f
val sumHalfY = (panelSize.height + surfExtents.height) / 2f
val overlap = !(dx > sumHalfX || dy > sumHalfY)
```

- z 평면 차이 무시 — Galaxy XR 사용 환경에서 panel/Quad 가 거의 같은 깊이로 떠 있음.
- 결과는 `lastOverlap` 와 비교해 변경 시에만 콜백 → 매 frame spam 방지.
- 콜백 → `MainActivity` 의 `setAutoHideAllowed(overlap)` →
  `autoHideAllowed=false` 면 컨트롤 자동숨김 영구 비활성, true 면 5초 hide 재시작.

---

## 5. 시네마룸 진입 / 종료

### 5.1 진입

```kotlin
session.scene.requestFullSpaceMode()
```

- Bundle launch 로 시작 즉시 진입되지만, 거부됐을 때 안전망으로 호출.
- Spatial Film 도 passthrough 를 끄지 않으므로 본 앱도 그대로 유지 (검정 환경 강제 X).
- alpha/parent 안전망 (§3.3) 도 이때 적용.

### 5.2 종료

```kotlin
session.scene.requestHomeSpaceMode()
session.scene.spatialEnvironment.preferredPassthroughOpacity =
    SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
```

`onPause` 에서 반드시 호출. 헤드셋을 벗거나 백그라운드 전환 시 passthrough 가 꺼진
상태로 남으면 사용자가 현실을 볼 수 없는 안전 문제 발생.

### 5.3 호출 지점 (high-level controller)

[`xr/XrPlaybackController.java`](../app/src/main/java/com/example/minseo21/xr/XrPlaybackController.java)
가 메인 코드 대신 wrap. MainActivity 는 다음 한 줄들만:

```java
xr.attemptStereoTakeover(mediaPlayer, sourceName);  // initPlayer
xr.retryByRatio(mediaPlayer, videoW, videoH);        // logTracks
xr.onPlayingEvent();                                 // VLC Playing
xr.onPausedOrStopped();                              // VLC Paused/Stopped
xr.onWindowFocused(hasFocus);                        // Window focus
```

`DefaultLifecycleObserver` 가 onPause/onDestroy 를 자동 hook 해서 정리.

---

## 6. SBS 콘텐츠 검출

### 6.1 파일명 패턴 (initPlayer 시점, 빠른 경로)

```kotlin
fun sbsPatternMatch(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains("_sbs")  ||
           lower.contains("_3d")   ||
           lower.contains(".sbs.") ||
           lower.contains("[sbs]") ||
           lower.contains("[3d]")
}
```

`syncKey` (폴더/파일) 또는 title 로 호출. 맞으면 즉시 `setupStereoSurface` 시도.

### 6.2 비율 폴백 (logTracks 후)

```kotlin
fun sbsRatioMatch(videoW: Int, videoH: Int): Boolean {
    if (videoH <= 0) return false
    return videoW.toFloat() / videoH >= 3.5f
}
```

가로 ÷ 세로 ≥ 3.5 면 SBS 로 추정. 일반 21:9 시네마는 2.39, SBS 1080p 는 3840×1080 = 3.55.

검증은 [`XrPlayerManagerTest.kt`](../app/src/test/java/com/example/minseo21/xr/XrPlayerManagerTest.kt)
의 JUnit 케이스. Android 의존 없이 companion object 순수 함수로 추출되어 있음.

---

## 7. 패널 가림 해소 (root view 투명화)

[`activity_main.xml`](../app/src/main/res/layout/activity_main.xml) 의 root FrameLayout
은 `android:background="#000000"`. 비-XR 단말에선 일반 영상 배경이지만, XR 단말에서는
Spatial Panel 이 통째로 검정 사각형으로 영상 Quad 앞에 떠 있게 됨.

Window 배경은 `getWindow().setBackgroundDrawable(ColorDrawable(TRANSPARENT))` 로
처리하지만 root view 의 `android:background` 가 별도 레이어이므로 따로 덮어써야 함.

```java
getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // controller 생성자
findViewById(R.id.root).setBackgroundColor(Color.TRANSPARENT);              // attemptStereoTakeover 성공 시
videoLayout.setVisibility(View.GONE);                                       // 잔상 방지
```

`videoLayout.setVisibility(GONE)` 누락 시: detach 된 TextureView 가 마지막 프레임
(로딩 스피너 등)으로 고정되어 panel 앞에 보임.

---

## 8. 컨트롤 자동숨김 — 영구 정지 버그 fix

세 가지 경로가 hide 타이머를 cancel 하고 재시작 안 해서 컨트롤이 영구 visible 되는 버그.

| 경로 | 문제 | fix |
|---|---|---|
| `btnSpeed.onClick` | `removeCallbacks(hideControls)` 만 호출, 다이얼로그 dismiss 후 재예약 X | `resetHideTimer()` 호출로 변경 |
| `btnOptions.onClick` | 동일 | 동일 |
| `showSpeedDialog` | 외부 탭 dismiss 시 `setOnDismissListener` 누락 | dismiss listener 추가 |
| `Playing` 이벤트 | 매번 `showControls()` 호출 → buffering↔Playing 반복 시 매번 reset | XR cinema room 진입만 하고 `showControls()` 제거 |

`MainActivity` 의 `resetHideTimer` / `scheduleHide` 는 `autoHideAllowed=false` 면 schedule 안 함:

```java
private void resetHideTimer() {
    handler.removeCallbacks(hideControls);
    if (!autoHideAllowed) return;   // panel 이 영상 바깥일 때 영구 표시
    handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS);
}
```

---

## 9. 코드 분리 (xr 패키지)

원칙은 [`Minseo21/CLAUDE.md`](../CLAUDE.md) "XR 확장 — 분리 원칙" 섹션 참조.

```
com.example.minseo21.xr/
├── XrPlayerManager.kt          // low-level: SurfaceEntity 직접 다룸
├── XrFullSpaceLauncher.java    // Bundle launch 캡슐화 (FileListActivity 용)
└── XrPlaybackController.java   // high-level lifecycle/이벤트 hook 통합
```

`XrPlaybackController` 의 `Host` 인터페이스가 메인의 view/handler/컨트롤 토글만 노출:

```java
public interface Host {
    Activity getActivity();
    View getContentRoot();
    VLCVideoLayout getVideoLayout();
    Handler getMainHandler();
    void setAutoHideAllowed(boolean allowed);
    void requestShowControls();
}
```

`MainActivity` 끝에 `private final class XrHost implements Host` 로 어댑터. 메인 본문은
일반 안드로이드 동작에만 집중.

---

## 10. 시도했다가 빠진 것들

### 10.1 300ms postDelayed SurfaceEntity 재생성

처음 HOME_SPACE → FULL_SPACE 전환 후 SurfaceEntity 가 안 보여 재생성을 시도:

```kotlin
handler.postDelayed({
    setupStereoSurface(mediaPlayer)
}, 300)
```

→ **systemui SIGSEGV**. 스택 트레이스의 정점:
```
SplitEngineSharedMemoryBridgeService::createExternalTextureSurface()
  → null producer 경쟁 상태
```

alpha13 splitEngine 의 race condition. 단말 자체가 reboot 됨. **금지**.

해결: HOME_SPACE 에서 SurfaceEntity 를 만들고 Bundle launch 로 즉시 FULL_SPACE 로
띄우면 재생성 자체가 불필요.

### 10.2 `FULL_SPACE_MANAGED` 매니페스트 값

Google docs 기준의 권장값. Galaxy XR 은 `SpatialApi(requestEnabled=false)` 로 거부.
Samsung 전용 `Full_Space_Activity` 로 교체.

### 10.3 passthrough 강제 OFF

처음엔 시네마 환경 흉내 위해 `preferredPassthroughOpacity = 0` 으로 강제.
Spatial Film 도 passthrough 를 유지하므로 동일 정책으로 변경 (사용자 요청 반영).

### 10.4 `setSpatialModeChangedListener` 진단

```kotlin
session.scene.setSpatialModeChangedListener { ev -> Log.i(TAG, "[SpatialModeChange] $ev") }
```

진단 목적. 정상 동작 확인 후 제거.

---

## 11. 디버깅 도구 / 명령어

### 11.1 logcat 필터

```bash
adb -s R34YA0007ZJ logcat -c
adb -s R34YA0007ZJ logcat | grep -E "SACH_XR|SurfaceEntity|SpatialApi|DesktopTasksController|systemui"
```

`SACH_XR` 태그가 본 앱의 진단 로그 prefix.

### 11.2 다른 앱 매니페스트 분석 (Spatial Film 등)

```bash
adb -s R34YA0007ZJ shell pm path com.hughhou.spatialfilm
adb -s R34YA0007ZJ pull <path-from-above> spatialfilm_base.apk
aapt dump xmltree spatialfilm_base.apk AndroidManifest.xml | less
# property / activity / uses-feature 검색
```

본 프로젝트의 모든 Samsung 전용 property 값이 이 경로로 발견됨.

### 11.3 화면 캡처

```bash
# screencap: surfaceflinger 만 캡처. PNG 가 손상 없이 나옴.
adb -s R34YA0007ZJ exec-out screencap -p > shot.png
# screenrecord: 영상 (10초 예시)
adb -s R34YA0007ZJ shell screenrecord --time-limit 10 /sdcard/rec.mp4
MSYS_NO_PATHCONV=1 adb -s R34YA0007ZJ pull /sdcard/rec.mp4 .
```

- `adb shell screencap -p > file.png` (Git Bash on Windows) 는 CRLF 변환으로 PNG 손상.
  반드시 `exec-out` 사용.
- `adb pull /sdcard/...` 는 Git Bash 가 경로를 `C:/Program Files/Git/sdcard/...` 로
  변환. `MSYS_NO_PATHCONV=1` 로 회피.

### 11.4 jar 클래스 시그니처 확인

API 시그니처가 의심스러울 때:

```bash
# AAR → classes.jar 추출
mkdir -p /tmp/scenecore && cd /tmp/scenecore
unzip -o ~/.gradle/caches/modules-2/files-2.1/androidx.xr.scenecore/scenecore/1.0.0-alpha13/*/scenecore-1.0.0-alpha13.aar
unzip -o classes.jar -d classes
# 클래스 검색
find classes -name "*.class" | xargs basename -a -s .class | grep -i panel | sort -u
# public API 시그니처
javap -public classes/androidx/xr/scenecore/SurfaceEntity.class
javap -public classes/androidx/xr/scenecore/MovableComponent.class
javap -public classes/androidx/xr/scenecore/MainPanelEntity.class
javap -public classes/androidx/xr/scenecore/EntityMoveListener.class
```

본 작업의 모든 API (Movable/Resizable/MainPanelEntity/Quad.extents 등)가 이 경로로 검증됨.

---

## 12. 알려진 이슈 / 주의사항

| 항목 | 상태 |
|---|---|
| alpha API (`alpha13`) | 시그니처가 다음 alpha 에서 깨질 수 있음. 업데이트 시 javap 으로 재확인. |
| `setSurfacePixelDimensions` `@Experimental` | 정식 API 아님. 사라지면 별도 경로 필요. |
| `SpatialEnvironment` passthrough API | 미세 변경 가능. 안전망 try/catch 유지. |
| 영상 / panel 위치 옮기기 | 헤드셋 컨트롤러로만 가능 (시선/제스처 입력 없음) |
| `setSpatialModeChangedListener` 의존 코드 | 제거됨. 추가 진단 필요 시 다시 도입 가능. |
| 비-XR 단말 회귀 | controller 의 모든 메서드가 no-op. `usingVideoLayout=true` 분기로 attachViews 정상 호출. |

---

## 13. 참고 자료

| 자료 | 용도 |
|---|---|
| Spatial Film APK (`com.hughhou.spatialfilm`) 매니페스트 덤프 | Samsung 전용 property 값 발견 |
| Spatial Film logcat (재생 시) | DesktopTasksController 의 mXrDesktopMode=2 trigger 흐름 확인 |
| [`xr-sbs-3d-playback.md`](./xr-sbs-3d-playback.md) | 어제까지 시점 기록 (디버깅 일지) |
| Jetpack XR alpha13 AAR (gradle cache) | 모든 API 시그니처 확인 source-of-truth |
| [`Minseo21/CLAUDE.md`](../CLAUDE.md) "XR 확장 — 분리 원칙" | 메인/XR 분리 규칙 |

---

## 14. 핵심 파일 빠른 인덱스

| 위치 | 역할 |
|---|---|
| `app/src/main/AndroidManifest.xml` | uses-feature, property, Activity 속성 |
| `app/src/main/java/com/example/minseo21/xr/XrPlayerManager.kt` | SurfaceEntity 생성/연결, 인터랙션, 시네마룸 |
| `app/src/main/java/com/example/minseo21/xr/XrFullSpaceLauncher.java` | Bundle launch |
| `app/src/main/java/com/example/minseo21/xr/XrPlaybackController.java` | lifecycle/이벤트 hook 통합, Host 인터페이스 |
| `app/src/main/java/com/example/minseo21/MainActivity.java` | 메인 (XR 흔적 ~10라인 + XrHost inner class) |
| `app/src/main/java/com/example/minseo21/FileListActivity.java` | XrFullSpaceLauncher 사용 |
| `app/src/main/res/layout/activity_main.xml` | root FrameLayout (XR 시 배경 투명화 대상) |
| `app/src/test/java/com/example/minseo21/xr/XrPlayerManagerTest.kt` | sbsPatternMatch / sbsRatioMatch 단위 테스트 |
| `scripts/apk.sh` | 단말 매핑 (xr = R34YA0007ZJ) |
