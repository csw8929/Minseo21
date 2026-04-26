# SBS 디버깅 history — race condition 발견까지 (2026-04-26)

`feat/xr-2d-restart` 브랜치. 옵션 B 구현(`sbs-player-activity-2026-04-26.md`) 완료 후 Galaxy XR(R34YA0007ZJ) 실기기 검증에서 발견된 5라운드 디버깅 기록. **모든 SDK alpha13 가설은 헛다리였고, 진짜 원인은 우리 Java 코드의 객체 초기화 순서 race condition** 이었다는 시간순 학습.

---

## 배경

옵션 B 구현(MainActivity 무수정 + SbsPlayerActivity extends + protected hook 3개) 빌드 성공 후 Galaxy XR 단말 검증 시작.

- 단말: R34YA0007ZJ (Galaxy XR / SM-I610)
- 영상: `Men In Black 3 (2012) 3D H_SBS 1080p BluRay H264 DolbyD 5.1 + nickarad.mp4` (1920×1040, Half-SBS, `_3d` + `_sbs` 양 keyword 매치)

---

## Round 1 — 첫 검증

**상태:** 옵션 B 디자인 doc 그대로 구현. mainPanel 처리 0줄, 결정적 4가지 조합(Bundle launch + Samsung property + OPAQUE + setSurfacePixelDimensions) 만 충족.

**관찰:** 사용자 — "잘되는 것 같애. sbs는 두 개의 좌/우 영상이 보이고.. 이동이나 그런 control 안 되고."

**해석(잘못):** "좌/우 영상이 보임" = SurfaceEntity 양안 렌더링 성공. control 만 안 됨 → Movable/Resizable component 가 SurfaceEntity 입력을 못 받음.

**가설 1: mainPanel 가림.** WIP 메모리(`project_xr_cinema_room_followups.md` TODO-XR-3) 의 검증 사실:
> SBS 진입 시 mainPanel 을 사용자 시야에서 사실상 제거 — size 0.01×0.01m. size 가 작아 사용자 ray 가 거의 hit 안 함. SurfaceEntity 가 SBS 영상의 grab 처리 담당.

→ 본 라운드는 mainPanel 작업을 모두 버림 → mainPanel default size(약 0.89×0.56m) 가 SurfaceEntity Quad 의 ray 를 가린다고 판단.

---

## Round 2 — mainPanel shrink 추가

**변경:** `XrSurfaceController.init()` 안에 `mainPanel.size = 0.01×0.01` 추가.

```kotlin
fun init() {
    if (!isXrDevice) return
    val result = Session.create(activity)
    if (result is SessionCreateSuccess) {
        session = result.session
        shrinkMainPanelToInvisible()  // ← 추가
    }
}
```

**관찰:** 사용자 — "화면이 아주 작아져서 좌/우 영상으로 나와... 정말 작아져서..."

**가설 2: SurfaceEntity 의 default parent 가 mainPanelEntity.** mainPanel size 0.01 → child SurfaceEntity 도 같이 scale down. WIP `enterCinemaRoom()` 안에 `if (ent.parent == null) ent.parent = s.scene.activitySpace` 가 있던 걸 빠뜨렸음 (시네마룸 fade 와 같이 버림).

---

## Round 3 — parent=activitySpace 명시 추가

**변경:** `setupStereoSurface()` 안 SurfaceEntity 생성 직후 parent 명시.

```kotlin
surfaceEntity = SurfaceEntity.create(s, ...)
try {
    surfaceEntity?.parent = s.scene.activitySpace  // ← 추가
} catch (e: Exception) { ... }
```

**관찰:** 사용자 — "작아진 상태 그대로."

**가설 3: 호출 순서 문제.** mainPanel 이 이미 0.01 인 상태에서 SurfaceEntity.create 를 부르면 SDK alpha13 가 작은 mainPanel 의 spatial reference 로 SurfaceEntity 를 spawn 한다는 추측. WIP 코드의 호출 순서:
1. `setupStereoSurface` (SurfaceEntity 생성)
2. `shrinkMainPanelForSbs` (mainPanel 0.01)

본 라운드는 init 시점(SurfaceEntity 생성 전)에 shrink. 순서 reverse.

---

## Round 4 — 호출 순서 변경

**변경:** `init()` 의 shrink 호출 제거 → `setupStereoSurface` 마지막에 호출.

```kotlin
fun setupStereoSurface(mediaPlayer: MediaPlayer): Boolean {
    // ... SurfaceEntity 생성 ...
    attachInteraction(s)
    shrinkMainPanelToInvisible()  // ← 위치 변경: 마지막에
    return true
}
```

**관찰:** 사용자 — "변화 없어.... 이전에 3D 재생될 때 control이 분리되는 느낌있어고, control window를 투명하게 만들고 난 다음 play화면이 나온 것으로 보였는데"

**핵심 단서를 놓침:** 사용자가 "이전에는 control 이 분리되어 보였고 나중에 play 화면이 나왔다" 고 visual 차이를 묘사. WIP 의 visual 과 본 라운드의 visual 이 다르다는 강한 hint. 그러나 이걸 SDK 가설로만 해석.

**가설 4: SurfaceEntity 의 spatial state 가 mainPanel reference 에 종속.** mainPanel shrink 자체가 SurfaceEntity 를 줄인다고 판단 → shrink 자체를 빼고 다른 안전망으로 대체.

---

## Round 5 — mainPanel shrink 제거 + Full Space 안전망 + 매니페스트 누락 보완

**변경 (코드):** mainPanel shrink 호출 완전 제거. setupStereoSurface 마지막에 WIP `enterCinemaRoom()` 핵심 추가:

```kotlin
attachInteraction(s)
try {
    s.scene.requestFullSpaceMode()  // 안전망
    Log.i(TAG, "requestFullSpaceMode() 안전망 호출")
} catch (e: Exception) { ... }
try {
    surfaceEntity?.setAlpha(1.0f)  // 검은 화면 회귀 방지
} catch (e: Exception) { ... }
```

**변경 (매니페스트) — 사용자가 직관으로 짚음:** "원래 xr하기 위해서 xml에 뭔가를 수정했엇지?"

WIP 매니페스트에 있던 3개 property 가 본 라운드에 빠져있었음:
1. application-level `PROPERTY_ACTIVITY_XR_FLAGS` (`system_app_integration_supported|turn_screen_on`)
2. application-level `PROPERTY_XR_BOUNDARY_TYPE_RECOMMENDED` (`XR_BOUNDARY_TYPE_NO_RECOMMENDATION`)
3. FileListActivity 의 `PROPERTY_XR_ACTIVITY_START_MODE = Home_Activity`

design doc 의 Open Question 1 에서 "application-level XR property 2개 — 첫 라운드 빼고 검증" 으로 의도 미뤘었음. 검증 결과 필요한 게 맞다고 판단해 모두 추가.

**관찰:** 사용자 — "진짜 변화가 없네.... 확실한거 찾기 전까지 얘기하지마.. 계속 체크해봐"

**Path 전환:** 이 한 마디로 SDK 가설 시도가 4번이나 누적된 게 명확. "확실한 증거 없이 추측 fix 그만" 이라는 지시. → logcat 진단으로 path 전환.

---

## logcat 진단 — race condition 발견

**파일명 명시 SBS** (`Men In Black 3 (2012) 3D H_SBS ...`) → `XrConfig.sbsPatternMatch` 가 `_3d` 와 `_sbs` 둘 다 매치 → SBS path 진입해야 함. 그런데도 시각 동작이 wrong → SBS path 진입 자체를 의심하기 시작.

`adb logcat -d | grep -i SACH` 로 직전 재생 세션 dump:

```
04-26 19:28:11.694  I SACH_XR : [isXrDevice] true
04-26 19:28:11.737  I SACH_XR : XR Session 생성 완료
04-26 19:28:11.998  I SACH    : [VLC] Video Track: 1920x1040
```

**누락된 로그 (있어야 하는데 없는 것):**
- ❌ `XR StereoSurface 연결 완료` (setupStereoSurface 안 핵심 로그)
- ❌ `requestFullSpaceMode() 안전망 호출`
- ❌ `applyVideoAspect: ...`

→ **`setupStereoSurface` 가 단 한 번도 호출되지 않았다는 결정적 증거.**

만약 매니페스트 누락이거나 SDK 한계였다면 `setupStereoSurface` 진입 후 실패 로그가 떴을 것. "init 까지는 OK 인데 setupStereoSurface 가 안 들어옴" 이 패턴 자체가 race condition 의 fingerprint.

---

## 진짜 원인: Java 필드 초기화 순서 vs Android `onCreate` 동기 흐름의 race

### 세 가지 사실의 충돌

**사실 1.** `MainActivity.onCreate` (line 231) 이 **동기적으로** `initPlayer(videoLayout, videoUri)` 를 호출. 비동기(`handler.post`) 가 아니라 일반 method call.

**사실 2.** `initPlayer` 안에서 우리가 추가한 `attemptStereoTakeover` hook 발화:
```java
mediaPlayer = new MediaPlayer(libVLC);
boolean takenOver = attemptStereoTakeover(mediaPlayer, sourceName);
if (!takenOver) {
    mediaPlayer.attachViews(videoLayout, ...);
}
```

**사실 3.** SbsPlayerActivity 의 `onCreate` 가 자연스러운 패턴(super → setContentView 후 findViewById → 본인 필드 init) 으로 작성됨:
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    getWindow().setBackgroundDrawable(...);
    super.onCreate(savedInstanceState);    // 1
    findViewById(R.id.root)...;            // 2 (setContentView 후라야 가능)
    xr = new XrSurfaceController(this);    // 3 ← 여기
    xr.init();                              // 4
}
```

### 시간순 충돌

```
T0: getWindow().setBackgroundDrawable(TRANSPARENT)
T1: super.onCreate() 진입
  T1.1: ... MainActivity setup ...
  T1.2: initPlayer() [동기]
    T1.2.1: VLC options 빌드
    T1.2.2: onConfigureVlcOptions(options)
            → SbsPlayerActivity override → JNI codec, no DR  ✅
    T1.2.3: mediaPlayer = new MediaPlayer(libVLC)
    T1.2.4: attemptStereoTakeover(mp, name) hook 발화
            ↓ SbsPlayerActivity override 진입
            ↓ if (xr == null) return super.attemptStereoTakeover(...)
            ↓                        ↑ 이 시점 xr 는 still null
            ↓ super = false
            ↓ return false
    T1.2.5: takenOver==false → mediaPlayer.attachViews(videoLayout, ...) 실행 ❌
T2: findViewById(R.id.root).setBackgroundColor(TRANSPARENT)
T3: xr = new XrSurfaceController(this)  ← 너무 늦음
T4: xr.init()                            ← Session 만 만들어짐, SurfaceEntity 안 만들어짐
```

`xr.init()` 의 `XR Session 생성 완료` 로그(T4) 는 떴지만, 정작 SBS path 진입에 필요한 `setupStereoSurface` 는 T1.2.4 시점에 xr == null 이라 호출조차 안 됐다.

### 사용자가 본 시각 동작의 정체

| 인지 | 실제 |
|---|---|
| "두 개의 좌/우 영상" | SurfaceEntity 양안 분리 ❌. 일반 VLCVideoLayout(Android TextureView) 에 SBS 원본(좌우 동일 장면) 통째로 stretching 된 것 |
| "정말 작아짐" | mainPanel default size(0.89×0.56m) 안에 영상 그려짐 |
| control 안 됨 | SurfaceEntity 자체가 만들어지지 않아 Movable/Resizable 부착 대상 없음 |

Round 2~4 에서 mainPanel shrink 가 visual 을 더 작게 만들었던 것도 같은 메커니즘 — VLCVideoLayout 이 mainPanel 안에 그려지는데 mainPanel 자체가 0.01 로 줄었으니.

---

## 해결

**수정 (1라인 수정 + 2라인 이동):**

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

    // **xr 는 super.onCreate 호출 전에 초기화한다.**
    // MainActivity.onCreate 가 line 231 에서 initPlayer() 를 동기적으로 호출하고, 그 안에서
    // attemptStereoTakeover hook 이 발화된다. 그 시점에 this.xr 가 살아있어야 SBS path 로
    // 진입 — 이 순서가 깨지면 super.attemptStereoTakeover() = false 가 반환되어 일반
    // VLCVideoLayout 출력으로 fallback (2026-04-26 logcat 진단으로 확인).
    xr = new XrSurfaceController(this);
    xr.init();

    super.onCreate(savedInstanceState);

    View root = findViewById(R.id.root);
    if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
}
```

**왜 안전한가:** `XrSurfaceController.init()` 자체는 `setContentView` / `findViewById` 같은 view 조작에 의존하지 않음. `Session.create(activity)` 만 호출 — Activity context 만 있으면 OK. 그래서 super 전에 부르는 게 안전.

---

## 검증

`adb logcat` 정상 동작 (2026-04-26 20:15:42):

```
04-26 20:15:42.173  I SACH_XR : [isXrDevice] true
04-26 20:15:42.219  I SACH_XR : XR Session 생성 완료
04-26 20:15:42.404  I SACH_XR : XR StereoSurface 연결 완료           ← 새로 뜸
04-26 20:15:42.409  I SACH_XR : requestFullSpaceMode() 안전망 호출    ← 새로 뜸
04-26 20:15:42.715  I SACH_XR : applyVideoAspect: 1920x1040 sbs=true(full=false) → quad 6.646154x3.6 aspect=1.8461539
                                                                       ← 새로 뜸
```

`applyVideoAspect` 로그의 `full=false` 가 정확 — 1920×1040 은 frameAspect 1.85 < 3.0 이므로 Half-SBS 로 인식. Quad 가 6.65×3.6m 로 spawn (height 3.6 유지, width 1.85 비율 적용). 사용자 — "어 이제 되네."

---

## 왜 4번이나 헛다리

5라운드 중 4라운드(R2~R5의 코드 부분)가 **모두 SDK alpha13 의 spatial behavior 가설**이었음. 실제 원인(Java 객체 초기화 순서) 과는 layer 가 다른 곳을 의심.

| Round | 가설 layer | 실제 |
|---|---|---|
| R2 | SDK 동작 (mainPanel ray 차단) | 잘못 — mainPanel 무관 |
| R3 | SDK 동작 (SurfaceEntity parent inheritance) | 잘못 — parent 무관 |
| R4 | SDK 동작 (호출 순서) | 잘못 — 순서 무관 |
| R5 | SDK 동작 + 매니페스트 (Full Space transition / property 누락) | 잘못 — 둘 다 무관 |

logcat 안 봤으면 무한히 SDK 가설을 시도했을 것. 사용자의 "확실한거 찾기 전까지 얘기하지마" 한 마디가 path 를 logcat 진단으로 전환시킨 결정적 트리거.

---

## 교훈

### 1. 진단 로그 먼저, fix 가설 나중에

`docs/refactor-dsfile-split-2026-04-18.md` ISSUE-003 (DsHttp readLine 줄바꿈 소실) 의 교훈과 정확히 동일한 패턴:

> "확실한 솔루션 고르기 전에 진단 로그부터 심어라."

ISSUE-003 에서 VLC 의 `Unknown error 1094995529` 를 access 모듈 문제로 단정해 FD/로컬 HTTP 서버 같은 큰 변경을 꺼낼 뻔했음 — 실제 근본 원인은 상위 레이어의 10줄짜리 readLine 루프. 한 곳 고치니 file:// access 는 정상 동작.

본 라운드도 동일 — visual 이상을 SDK alpha13 동작으로 해석해 4번 SDK 가설 시도. 실제로는 SbsPlayerActivity onCreate 의 3줄 순서 문제.

**규칙화: 시각 동작 이상이 일관되게 wrong 인데 가설 시도가 효과 없으면, 단계 layer 를 한 단계 위로 올려 진단 로그부터 본다.**

### 2. Activity 상속 + protected hook 패턴의 함정

일반화 가능한 패턴:

> **부모 Activity 의 `onCreate` 가 동기로 hook 을 발화하면, 자식 Activity 의 hook 의존 필드는 `super.onCreate` 호출 이전에 초기화해야 한다.**

이건 Java 의 일반 패턴(subclass field init **after** super) 과 Android `onCreate` 의 동기 hook 발화가 만나는 race. 한 번 겪었으니 다음번 hook 패턴 도입할 때는 의식하게 됨.

**대안 패턴들:**
- (a) 부모가 동기 hook 발화하지 않고 `onPostCreate` 또는 `onResume` 으로 미룸 — 자식이 super.onCreate 후 초기화 가능
- (b) hook 호출 전에 명시적 `boolean isInitialized` 게이트 — null check 가 의미 있는 fallback
- (c) 본 라운드처럼 자식이 super 전 초기화 — Activity context 만 필요한 헬퍼라 안전

본 라운드는 (c) 채택. (a) 는 MainActivity 재생 흐름 변경이라 옵션 B 정신과 충돌.

### 3. 사용자 visual 묘사 의 우선순위

R4 에서 사용자가 "이전에 3D 재생될 때 control이 분리되는 느낌있어고, control window를 투명하게 만들고 난 다음 play화면이 나온 것으로 보였는데" 라고 visual 차이를 명시했지만, SDK 가설로만 해석하고 가벼이 처리. 사실은 "WIP visual 과 본 라운드 visual 이 다르다 → 같은 SBS path 가 아닐 수 있다" 는 직접 hint 였음.

**규칙화: 사용자가 같은 동작의 visual 차이를 묘사하면, 그건 동일 path 진입 여부의 단서. 무시 금지.**

---

## 결과

| 항목 | 값 |
|---|---|
| 5라운드 디버깅 시간 | ~2시간 (R1~R5 + logcat 진단 + race fix) |
| 헛다리 가설 | 4건 (mainPanel shrink / parent / 호출 순서 / 매니페스트) |
| 진짜 fix | SbsPlayerActivity.onCreate 의 3줄 위치 변경 |
| 추가된 매니페스트 property | 3개 (실제 race 원인은 아니었지만 spatial 안전망으로 유지) |
| 추가된 코드 (안전망) | requestFullSpaceMode() + setAlpha(1.0f) (race 가 아니어도 spatial 안전망 가치) |
| 최종 정상 동작 | 화면 6.65×3.6m + 양안 분리 + Movable/Resizable + 비율 lock |

**SbsPlayerActivity.java 최종 형태:**

```java
public class SbsPlayerActivity extends MainActivity {
    private XrSurfaceController xr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // xr 는 super.onCreate 호출 전에 초기화 — race 회피.
        xr = new XrSurfaceController(this);
        xr.init();

        super.onCreate(savedInstanceState);

        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onConfigureVlcOptions(List<String> options) {
        super.onConfigureVlcOptions(options);
        options.removeIf(opt -> opt != null && opt.startsWith("--codec="));
        options.add("--codec=mediacodec_jni,none");
        options.add("--no-mediacodec-dr");
    }

    @Override
    protected boolean attemptStereoTakeover(MediaPlayer mp, String sourceName) {
        if (xr == null) return super.attemptStereoTakeover(mp, sourceName);
        if (!XrConfig.sbsPatternMatch(sourceName)) return super.attemptStereoTakeover(mp, sourceName);
        if (!xr.setupStereoSurface(mp)) return super.attemptStereoTakeover(mp, sourceName);
        View videoLayout = findViewById(R.id.videoLayout);
        if (videoLayout != null) videoLayout.setVisibility(View.GONE);
        return true;
    }

    @Override
    protected void onVideoTrackInfo(MediaPlayer mp, int videoW, int videoH) {
        super.onVideoTrackInfo(mp, videoW, videoH);
        if (xr != null) xr.applyVideoAspect(videoW, videoH, true);
    }

    @Override
    protected void onDestroy() {
        if (xr != null) { xr.release(); xr = null; }
        super.onDestroy();
    }
}
```

---

## Open Questions / 후속

1. **SbsPlayerActivity onPause** — Bundle launch 후 system 자동 Home Space 복귀 여부 검증 미완료. 실제 사용자 테스트에서 back 후 FileListActivity panel 위치 정상인지 확인 필요.
2. **비율 폴백** — 첫 라운드 파일명 keyword 만. 사용자가 `_sbs` 표기 의무. 향후 `MediaMetadataRetriever` pre-launch probe 또는 launch 후 비율 검출 + UX.
3. **매니페스트 application-level property 2개** — 본 라운드 race 원인은 아니었지만 spatial 안전망으로 유지. 실제 효과 검증 미완료 (가져도 무해).
4. **다른 단말 회귀 검증** — R54Y1003KXN(탭) / R3CT70FY0ZP(폴드) / R3CX705W62D(플립) — XR 분기 가드(`isXrDevice`) 동작 확인.
