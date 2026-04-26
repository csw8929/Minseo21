# XR 시네마룸 구현 기록 (2026-04-26)

`feat/xr-cinema-room` 브랜치에서 진행된 작업 기록. PR #4 (`feat/xr-support`, 2026-04-25 merge) 위에 얹은 후속 작업.

## 1. 작업 묶음 요약

오늘 디자인 문서(`USER-dev-design-20260426-091215.md`, Status: APPROVED) 의 작업 항목 3개를 한 묶음으로 구현·실기기 검증.

| # | 항목 | 한 줄 요약 |
|---|---|---|
| **A** | 시네마 룸 | 영상 재생 시 자동으로 passthrough opacity 1→0 fade, 정지 시 0→1, onPause/onDestroy 시 즉시 1.0 강제 복귀 |
| **B** | floating 컨트롤 폐기 | PR #4에서 추가했던 MainPanel Movable + 영상↔패널 겹침 검출 + 자동숨김 분기 약 30줄 삭제. 컨트롤은 영상 Quad 내부에서 평소처럼 자동숨김 |
| **C** | 비율 고정 리사이즈 | `ResizableComponent.isFixedAspectRatioEnabled = true` (SDK 플래그) + `applyVideoAspect()` (실제 영상 비율로 Quad 갱신) |

추가로 실기기 테스트 중 잡은 버그 2개와 SDK 표면을 javap로 정확히 확인한 결과를 별도 섹션에 기록.

## 2. 출발 상태 (PR #4 직후, 4/26 작업 시작 시점)

PR #4 결과로 다음이 동작:
- SBS 3D 재생 (`SurfaceEntity` + `StereoMode.SIDE_BY_SIDE` + `MediaBlendingMode.OPAQUE` + `setSurfacePixelDimensions(1920, 1080)`)
- Bundle launch 패턴으로 Full Space 진입 (`XrFullSpaceLauncher`)
- `MovableComponent.createSystemMovable(s, scaleInZ = true)` — 영상 Quad와 MainPanel 양쪽 부착
- `ResizableComponent.create(s)` — ResizeEvent 콜백에서 `ent.shape` 직접 갱신 (manual snap)
- `enterCinemaRoom()` / `exitCinemaRoom()` — passthrough opacity 변경은 안 하고 `requestFullSpaceMode()` 와 `requestHomeSpaceMode()` 만 호출. 사실상 "시네마룸"의 시각 효과는 없는 상태였음
- 영상 Quad와 MainPanel의 xy AABB 겹침을 매 move 마다 계산해서, panel 이 영상 바깥에 있으면 컨트롤 자동숨김 비활성

## 3. POC 게이트 (javap 결과)

디자인 문서의 Open Question #1 ("alpha13 SpatialEnvironment API 정확한 시그니처") 을 닫기 위해 Jetpack XR alpha13 AAR 4개 (`scenecore`, `scenecore-runtime`, `scenecore-spatial-core`, `scenecore-spatial-rendering`) 를 풀어 javap 로 확인.

### 3-1. `androidx.xr.scenecore.SpatialEnvironment`

```kotlin
class SpatialEnvironment {
  companion object {
    const val NO_PASSTHROUGH_OPACITY_PREFERENCE: Float
  }

  // passthrough
  var preferredPassthroughOpacity: Float
  val currentPassthroughOpacity: Float
  fun addOnPassthroughOpacityChangedListener(...)

  // skybox + geometry (이번 작업 미사용)
  var preferredSpatialEnvironment: SpatialEnvironmentPreference?
  val isPreferredSpatialEnvironmentActive: Boolean
}
```

**확정 사항:**
- API 이름은 `setPreferredPassthroughOpacity(Float)` — **디자인 문서 초안의 `setPassthroughOpacityPreference()` 는 잘못된 이름이었음. 정정 후 사용**.
- `Float` 기반. enum 이 아님 → 디자인의 백업안(검정 ColorRect Quad alpha 보간) 폐기.
- `currentPassthroughOpacity` 는 read-only — 시스템이 실제 적용한 값 관찰용. preferred 와 다를 수 있음(사용자가 헤드셋 버튼으로 OFF 했을 때 등).
- 상수 `NO_PASSTHROUGH_OPACITY_PREFERENCE` 는 sentinel float — 설정하면 시스템 default 로 복귀.

### 3-2. `androidx.xr.scenecore.ResizableComponent`

```kotlin
class ResizableComponent {
  var isFixedAspectRatioEnabled: Boolean      // ← 핵심
  var isAutoHideContentWhileResizingEnabled: Boolean
  var affordanceSize: FloatSize3d
  var minimumEntitySize: FloatSize3d
  var maximumEntitySize: FloatSize3d
  // 그 외
}
```

**확정 사항:**
- `isFixedAspectRatioEnabled` 가 `Boolean` 플래그로 존재. 잠금 비율은 별도 인자로 받지 않고 **활성화 시점의 entity 비율을 SDK 가 캡처해서 잠금** (검증 완료 — 자세히는 4-3절).
- 따라서 디자인 문서의 manual ResizeEvent snap 는 불필요 — SDK 플래그로 단순화 가능.

### 3-3. `androidx.xr.scenecore.MovableComponent`

```kotlin
class MovableComponent {
  companion object {
    fun createSystemMovable(session, scaleInZ: Boolean): MovableComponent
    fun createSystemMovable(session): MovableComponent  // scaleInZ default false
  }
  private val scaleInZ: Boolean
  // ...
}
```

**확정 사항:**
- `createSystemMovable(session, scaleInZ)` 의 두 번째 인자는 **`scaleInZ`** (PR #4 코드에 주석으로 표기되어 있던 그대로).
- `scaleInZ = true` 의 의미는 javap 만으로는 정확히 안 잡혔으나 실기기 동작으로 확인됨 → 클릭/이동 시 SDK 가 Z 거리에 따라 entity 크기를 자동 조정. **헤드셋 포인터 클릭이 미세 Z 변화로 해석돼 영상 Quad 가 폭발적으로 커지는 부작용** (5-2절).

## 4. 구현 상세

### 4-1. 항목 B: floating 컨트롤 폐기

**삭제된 것:**

```kotlin
// xr/XrPlayerManager.kt — 모두 삭제
var onPanelOverlapChanged: ((Boolean) -> Unit)? = null
private var lastOverlap: Boolean? = null
fun enableMainPanelInteraction() { /* MainPanel Movable 부착 */ }
private val overlapTrackingListener = object : EntityMoveListener { /* ... */ }
private fun checkAndNotifyOverlap() { /* ... */ }
private fun computeOverlap(): Boolean { /* xy AABB 계산 */ }
```

```java
// xr/XrPlaybackController.java — Host 인터페이스에서 삭제
void setAutoHideAllowed(boolean allowed);
void requestShowControls();

// applySpatialUi() 단순화
// 삭제: xrManager.enableMainPanelInteraction();
// 삭제: xrManager.setOnPanelOverlapChanged(...);
```

```java
// MainActivity.java — 삭제
private boolean autoHideAllowed = true;  // 필드 삭제
// scheduleHide / resetHideTimer 의 if (!autoHideAllowed) return; 분기 삭제
// XrHost 의 setAutoHideAllowed / requestShowControls 메서드 override 삭제
```

`overlapTrackingListener` 가 사라지면서 `EntityMoveListener` import 도 제거됨. `Entity`, `Ray`, `kotlin.math.abs` import 도 동시 제거.

**효과:** 약 80줄 순감 (삭제 100줄 - 단순화한 자리에 약간 보강 20줄).

### 4-2. 항목 A: 시네마 룸 (passthrough fade)

**핵심 추가:**

```kotlin
// xr/XrPlayerManager.kt
private var passthroughAnimator: ValueAnimator? = null
private const val PASSTHROUGH_FADE_MS = 400L

private fun startPassthroughFade(targetOpacity: Float, onEnd: (() -> Unit)? = null) {
    val env = spatialEnvironment() ?: run { onEnd?.invoke(); return }
    val from = currentOpacityOrFallback(env, targetOpacity)
    passthroughAnimator?.cancel()
    if (from == targetOpacity) {
        env.preferredPassthroughOpacity = targetOpacity
        onEnd?.invoke(); return
    }
    val anim = ValueAnimator.ofFloat(from, targetOpacity).apply {
        duration = PASSTHROUGH_FADE_MS
        interpolator = AccelerateDecelerateInterpolator()
        var lastApplyMs = 0L
        addUpdateListener { va ->
            val now = SystemClock.uptimeMillis()
            // ~30Hz throttle: alpha13 SDK 의 호출 빈도 한계가 미상이라 보수치 채택
            if (now - lastApplyMs < 33L && va.animatedFraction < 1f) return@addUpdateListener
            lastApplyMs = now
            spatialEnvironment()?.preferredPassthroughOpacity = va.animatedValue as Float
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (passthroughAnimator === animation) passthroughAnimator = null
                onEnd?.invoke()
            }
        })
    }
    passthroughAnimator = anim
    anim.start()
}

private fun forcePassthroughRestore() {
    passthroughAnimator?.cancel()
    passthroughAnimator = null
    val env = spatialEnvironment() ?: return
    env.preferredPassthroughOpacity = 1f
    env.preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
}
```

**호출 지점:**
- `enterCinemaRoom()` 끝에 `startPassthroughFade(0f)` (1→0)
- `exitCinemaRoom()` 시작에 `startPassthroughFade(1f, onEnd = { /* NO_PREFERENCE 로 정상화 */ })`
- `onPause()` 와 `release()` 에 `forcePassthroughRestore()` (즉시 1.0 강제, 헤드셋 벗기 안전)

**설계 결정 정리:**

| 결정 | 값 | 이유 |
|---|---|---|
| Fade duration | 400ms | 갑작스럽지도 길지도 않음. 4/24 디자인의 권장값 |
| Interpolator | `AccelerateDecelerateInterpolator` | 양 끝이 부드러운 sigmoid, fade-out 시작과 끝이 자연스러움 |
| 호출 빈도 | ~30Hz throttle (33ms tick) | alpha13 SDK 가 호출당 frame budget 을 어떻게 쓰는지 미상. 매 vsync(16ms) 호출이 과도할 가능성 보수적으로 처리. 마지막 frame 은 throttle 무시하고 정확한 target 값 적용 |
| 빠른 토글 처리 | cancel 후 현재 opacity 에서 새 방향으로 재시작 | 사용자 빠른 재생/일시정지 시 애니메이션 점프 없이 자연스럽게 이어짐 |
| onPause 시 동작 | fade cancel + 즉시 1.0 강제 + NO_PREFERENCE 적용 | 안전 의무. 헤드셋 벗는 순간 현실 보여야 함 |

### 4-3. 항목 C: 비율 고정 리사이즈

**구현 변천:**

#### 1차 시도 (실기기 테스트 전)

`attachInteraction()` 안에서 ResizableComponent 생성 직후 즉시:

```kotlin
ent.addComponent(resizable)
resizable.isFixedAspectRatioEnabled = true   // ← 즉시 잠금
```

이 시점 entity Quad 는 기본값 `FloatSize2d(3.2f, 1.8f)` (16:9). 영상 트랙 정보는 아직 미수신. 이후 `applyVideoAspect()` 가 호출되면:

```kotlin
val newAspect = perEyeW / videoH
val w = current.extents.width    // width 보존
val newH = w / newAspect          // height 만 새 aspect 로
ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(w, newH))
resizable.isFixedAspectRatioEnabled = false
resizable.isFixedAspectRatioEnabled = true   // ← 잠금 갱신 의도
```

#### 1차의 문제 (실기기에서 잡힘 — "내가 원한 가로가 넓은 영상이 아니다")

- **시점 문제**: 잠금이 16:9 default 시점에 활성화돼 SDK 내부에 16:9 가 캡처됨. 이후 toggle false→true 가 entity 의 새 비율을 다시 읽지 않을 가능성.
- **HSBS vs full-SBS 구분 누락**: 코드가 `if (isSbs) videoW / 2f else videoW.toFloat()` 으로 일률 처리.
  - Full-SBS (3840×1080): 한 눈 1920×1080 → 16:9 ✓
  - **Half-SBS (1920×1080)**: 한 눈 960×1080 → 9:16 portrait ✗ (디스플레이는 가로 stretch 해서 한 눈 16:9 로 만들지만 코드는 모름)
- **width 보존 전략의 시각 효과**: cinemascope (3840×800) 같은 wide source 는 height 가 짜부라들기만 하고 width 가 늘지 않아 "wide" 로 보이지 않음.

#### 2차 (현재 코드)

```kotlin
private fun attachInteraction(s: Session) {
    // ...
    val resizable = ResizableComponent.create(s) { event: ResizeEvent ->
        val ns = event.newSize
        ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(ns.width, ns.height))
    }
    ent.addComponent(resizable)
    // 잠금은 applyVideoAspect 가 실제 영상 비율로 Quad 를 맞춘 다음 활성화한다.
    currentResizable = resizable
}

fun applyVideoAspect(videoW: Int, videoH: Int, isSbs: Boolean) {
    if (!isXrDevice) return
    if (videoW <= 0 || videoH <= 0) return
    val ent = surfaceEntity ?: return
    val frameAspect = videoW.toFloat() / videoH
    // Full-SBS 식별: 프레임 비율이 normal 16:9 (1.78) 의 2배 가까이 (≥3.0) 면 한 눈 = frame/2.
    val fullSbs = isSbs && frameAspect >= 3.0f
    val perEyeAspect = if (fullSbs) frameAspect / 2f else frameAspect
    val current = ent.shape as? SurfaceEntity.Shape.Quad ?: return
    // Height 유지, width 를 새 aspect 로 재계산 → wide source 는 가로로 더 넓어짐.
    val h = current.extents.height
    val newW = h * perEyeAspect
    ent.shape = SurfaceEntity.Shape.Quad(FloatSize2d(newW, h))
    // 이제 비율 잠금 활성화 — SDK 가 새 Quad 비율을 캡처.
    currentResizable?.isFixedAspectRatioEnabled = true
}
```

**케이스 별 결과:**

| 입력 | frameAspect | fullSbs | perEyeAspect | 결과 Quad |
|---|---|---|---|---|
| Full-SBS 4K (3840×1080) | 3.55 | true | 1.78 | 3.2 × 1.8 (기본 그대로) |
| Cinemascope SBS (3840×800) | 4.8 | true | 2.4 | **4.32 × 1.8** (가로로 더 넓음) |
| Half-SBS HD (1920×1080) | 1.78 | false | 1.78 | 3.2 × 1.8 (16:9 정상 — 9:16 portrait 버그 수정) |
| 2D 16:9 (1920×1080) | applyVideoAspect 자체 호출 안 됨 (`retryByRatio` 가 stereo 경로일 때만 진입) | — | — | — |

**호출 지점:** `XrPlaybackController.retryByRatio()` 에서 stereo 가 활성이거나 ratio 검출로 활성화된 직후 호출.

```java
public void retryByRatio(MediaPlayer mp, int videoW, int videoH) {
    if (!xrManager.isXrDevice()) return;
    boolean sbs;
    if (stereoActive) {
        sbs = true;  // 파일명으로 이미 takeover — SBS 로 가정
    } else if (xrManager.isSbsByRatio(videoW, videoH)) {
        if (!xrManager.setupStereoSurface(mp)) return;
        stereoActive = true;
        applySpatialUi();
        xrManager.enterCinemaRoom();
        sbs = true;
    } else {
        return;
    }
    xrManager.applyVideoAspect(videoW, videoH, sbs);
}
```

## 5. 실기기 테스트에서 잡은 버그

### 5-1. 비율 — 4-3절 1차 → 2차 변경 (이미 위에 기록)

### 5-2. `scaleInZ = true` 로 인한 Quad 폭발

**증상:** "처음 영상 재생 후 control 화면 보이고 그 상태에서 영상을 누르면 엄청 커져."

**원인 추정:** `MovableComponent.createSystemMovable(s, scaleInZ = true)` 의 `scaleInZ` 가 활성이면 SDK 가 entity 의 Z 좌표 변화에 따라 자동으로 크기를 조정. 헤드셋 포인터의 click(태깽) 이벤트가 SDK 내부적으로 미세한 Z 이동으로 해석돼, scaleInZ 보정이 누적·증폭된 결과 Quad 가 "엄청 커지는" 현상 발생한 것으로 추정.

**수정:**

```kotlin
// 변경 전
val movable = MovableComponent.createSystemMovable(s, /* scaleInZ = */ true)
// 변경 후
val movable = MovableComponent.createSystemMovable(s, /* scaleInZ = */ false)
```

검증 후 사용자 확인 — 해결됨.

## 6. 아키텍처 분리 점검

CLAUDE.md 의 XR 분리 원칙 (2026-04-25 추가) 기준으로 오늘 작업 후 상태 감사. 결과 — **준수.**

| 원칙 | 점검 결과 |
|---|---|
| 모든 XR 코드는 `com.example.minseo21.xr` 패키지에 | ✓ `androidx.xr.*` import 는 `xr/XrPlayerManager.kt`, `xr/XrFullSpaceLauncher.java` 단 2개 파일에만 존재 |
| 메인 클래스에 XR 분기/필드/직접 호출 직접 넣지 않기 | ✓ MainActivity 의 XR 흔적 약 14줄 (import 1 + 필드 1 + 컨트롤러 호출 ~6 + XrHost inner class). 오늘 작업으로 오히려 줄어듦 (autoHideAllowed 분기 제거) |
| Lifecycle / Host 콜백 패턴 | ✓ `XrPlaybackController` 가 `DefaultLifecycleObserver`. Host 인터페이스는 view/handler 어댑터만 (오늘 setAutoHideAllowed/requestShowControls 제거로 더 좁아짐) |
| 메인의 일반 동작은 비-XR 단말에서도 정상 | ✓ controller 의 모든 메서드가 `isXrDevice false` 면 no-op |

오늘 추가/변경된 코드는 100% `xr/` 패키지 내부. MainActivity 는 변경된 라인이 있지만 모두 `autoHideAllowed` 관련 삭제 (줄어드는 방향).

## 7. 보류된 항목 (TODO-XR-2)

원안: 정식 시네마룸 자산 — HDR EXR skybox + 진입/퇴장 사운드 큐 + silent 토글.

**보류 결정 (2026-04-26):** 사용자 관찰 — Galaxy XR(SM-I610) 실기기에서 `setPreferredPassthroughOpacity(0f)` 호출만으로 Samsung 시스템 기본 환경이 이미 시네마룸 분위기로 충분히 어둡고 분위기 있음. 단색/그라데이션 EXR skybox 로 덮으면 오히려 다운그레이드. 사운드 큐는 메디테이션·집중 사용 패턴과 충돌 (silent default 가 자연스러움) → 자체로 제외.

**다시 검토할 트리거:**
- Samsung 시스템 업데이트로 기본 환경이 변하여 cinema 분위기가 깨졌을 때
- 사용자가 "더 영화관답게"를 원하는 시점

**그땐 단색 skybox보단 새 항목 — TODO-XR-5: 가상 영화관 geometry (glTF mesh) — 방향 권장.** 좌석·스크린 프레임·벽이 있는 진짜 영화관 공간이 가치 큼. 자산 제작 비용도 큼.

## 8. 남은 follow-up (다음 세션 트리거 후보)

`~/.claude/projects/D--workspace-Minseo21/memory/project_xr_cinema_room_followups.md` 에 박혀 있음. 다음 XR 세션 진입 시 사용자에게 먼저 상기 의무.

- **TODO-XR-1: SDK 우선 마이그레이션** — alpha SDK 가 안정화되면 ValueAnimator 수동 fade 와 manual snap 을 SDK 내장 transition / property 로 교체. Effort M~L.
- ~~TODO-XR-2: HDR skybox + 사운드~~ — 보류.
- **TODO-XR-3: ResizeEvent 빈도 검증** — drag 중 ResizeEvent 가 매 프레임이면 jitter 가능. 실기기 로그로 빈도 확인 후 throttle 16~33ms 필요한지 결정.
- **TODO-XR-5: 가상 영화관 geometry (glTF)** — 무기한 보류.

## 9. 변경 파일 요약

| 파일 | 순 변경 |
|---|---|
| `app/src/main/java/com/example/minseo21/MainActivity.java` | 12 줄 (autoHideAllowed 필드 + 분기 + XrHost 메서드 삭제 — 줄어드는 방향) |
| `app/src/main/java/com/example/minseo21/xr/XrPlaybackController.java` | 42 줄 (Host 인터페이스 좁힘, applySpatialUi 단순화, retryByRatio 에 applyVideoAspect 호출 추가) |
| `app/src/main/java/com/example/minseo21/xr/XrPlayerManager.kt` | 237 줄 (overlap 검출/MainPanel Movable 삭제 + applyVideoAspect 추가 + passthrough fade 구현 + scaleInZ false 변경) |

총: 162 insertions / 129 deletions. 알짜 추가는 fade 와 applyVideoAspect; 알짜 삭제는 overlap/MainPanel-Movable 묶음.

## 10. 검증

| 항목 | 상태 |
|---|---|
| `./gradlew assembleDebug` | ✓ |
| `./gradlew test` (companion 객체 순수 함수 단위 테스트) | ✓ |
| Galaxy XR (R34YA0007ZJ) 실기기 install + 영상 재생 | ✓ |
| 와이드 비율 영상 (cinemascope SBS) 가로로 넓어지는지 육안 확인 | ✓ (사용자 검증) |
| 영상 클릭 시 Quad 폭발 현상 (scaleInZ 부작용) | ✓ 해결 |
| 비-XR 단말 (탭/폴드/플립/미니) 회귀 테스트 | 미실시 |
| onPause/onDestroy 시 passthrough 즉시 복귀 (안전 의무) | 코드 확인 ✓ / 실기기 시연 미실시 |
| 빠른 재생/일시정지 토글 시 fade 자연스러움 | 코드 확인 ✓ / 실기기 시연 미실시 |
