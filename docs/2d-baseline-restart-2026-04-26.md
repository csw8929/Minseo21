# 2D baseline 재출발 — 색강화 + Back 버튼 + WindowInsets (2026-04-26)

`feat/xr-2d-restart` 브랜치. **2D 영상이 main, SBS 3D 는 옵션** 인 라이브러리 정체성에 정합한 재설계.

## 배경

4/24~26 XR 시네마룸 작업(`feat/xr-cinema-room` PR #4 / #5)에서 SBS 3D 재생 / 시네마룸 passthrough fade / SurfaceEntity 양안 분리를 활성화하기 위해 `MainActivity` 의 `PROPERTY_XR_ACTIVITY_START_MODE` 를 `Full_Space_Activity` 로 박았다(Spatial Film 패턴).

그러나 **Full Space mode 의 trade-off** 로 system 이 mainPanel decoration(grab handle / resize affordance / 자동 panel 이동) 을 정책상 비활성. 결과적으로 일반 2D 영상의 panel grab/resize 가 SDK 한계(`MainPanelEntity` 에 `MovableComponent`/`ResizableComponent` 부착해도 system 이 무시 — 4가지 옵션 조합 시도 후 결론)로 깨짐.

`XR_ACTIVITY_START_MODE` 는 manifest property 라 runtime 변경 불가, dynamic Home↔Full Space 전환은 docs `xr-api-manifest-reference-2026-04-25.md` L97-141 에 "이미 막힌 path" 로 박혀 있다(`requestFullSpaceMode()` 만으로 DesktopTasksController trigger 안 됨, Bundle launch 가 진짜 trigger). 즉 **한 panel 에서 Full Space + Home Space 의 panel decoration 둘 다 못 가짐**.

## 결정

- `c94b528` (XR 작업 진입 직전, 4/19 시점) baseline 으로 되돌리고 **SBS 무관한 가벼운 변경만 다시 얹음**
- 4/26 작업 전부는 `wip/xr-2026-04-26-features` branch 에 보존(WIP commit 7ce278e)
- SBS path 는 **별 라운드(옵션 B): `SbsPlayerActivity` 신설로 Full Space + SurfaceEntity 분리**. MainActivity 는 일반 2D 전용 그대로. 영상 클릭 시 파일명 SBS 검출하여 conditional Activity launch

## 본 라운드 변경

### 1. video 색강화 (universal)

`MainActivity.initPlayer` 의 libVLC options 에 3줄 추가. **XR 분기 없이 모든 단말** 적용. OLED/OLEDoS 단말에서 명암비 활용:

```java
options.add("--video-filter=adjust");
options.add("--saturation=1.15");
options.add("--contrast=1.05");
```

값은 부담 없는 미세 강화 — 4/26 XR 라운드에서 saturation=2.0 / contrast=1.4 가 부담스러움 → 1.15/1.05 로 정착한 사용자 검증값.

### 2. Floating Back 버튼 — list + MainActivity, **universal**

XR 헤드셋의 시스템 back 제스처가 어색한 상황 + 일반 단말의 명시 path 보조. **XR 구분 없이** 모든 단말 표시.

#### 2.1 list (`activity_file_list.xml` + `FileListActivity`)
- 콘텐츠 `FrameLayout` child 로 `ImageButton#btnBack` 추가 (56dp, 우하단 24dp margin, `bg_red_circle` + `ic_back`)
- 클릭 → `getOnBackPressedDispatcher().onBackPressed()` 트리거 (NAS stack pop / 즐겨찾기→로컬 / 버킷 list / 종료 모두 시스템 back 과 동일)
- list (RecyclerView 3개 — local / NAS / 즐겨찾기) `paddingBottom` 8dp → **80dp** (back 버튼 size 56dp + margin 24dp). 마지막 항목 가림 방지

#### 2.2 영상 화면 (`activity_main.xml` + `MainActivity`)
- root FrameLayout child 로 `ImageButton#btnBack` 추가 (visibility="gone", marginBottom 120dp — bottom controls 위쪽)
- 클릭 → `getOnBackPressedDispatcher().onBackPressed()`
- **컨트롤 자동숨김 토글에 통합**: `hideControls` / `showControls` / `toggleControls` 에서 `btnBack` 도 같이 visibility 변경

### 3. WindowInsets 동적 처리

`fitsSystemWindows="true"` 만으로는 일부 단말(특히 Samsung One UI taskbar) 에서 inset 이 안 들어옴. `FileListActivity.onCreate` 에서 명시 listener 등록:

```java
ViewCompat.setOnApplyWindowInsetsListener(fileListRoot, (v, insets) -> {
    int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
    int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
    v.setPadding(0, top, 0, bottom);
    return insets;
});
```

결과: status bar / navigation bar / Samsung taskbar 영역에 list 침범 안 함, back 버튼이 taskbar 위에 잘림 없이 깨끗하게 표시.

## 검증

R34YA0007ZJ (Galaxy XR) + R54Y1003KXN (탭) 양 단말에서 검증:
- ✅ list 마지막 항목 — back 버튼 / system bar 영역에 안 들어감
- ✅ back 버튼 클릭 — list / MainActivity 양쪽 정상 back
- ✅ MainActivity back 버튼 — 컨트롤과 함께 자동숨김
- ✅ video 색강화 — saturation/contrast 시각 차이 (4/26 라운드에서 사용자 검증 반복)
- ✅ Galaxy XR 환경 — 일반 Android Activity 로 동작, system 자동 panel decoration 제공 (XR 작업 이전 깨끗한 상태)

## 옵션 B (별 라운드) — TODO

`SbsPlayerActivity` 신설 path 의 디자인은 별 office-hours 에서:
- `MainActivity` 그대로 유지(2D 전용 + system 자동 panel)
- `SbsPlayerActivity` 신설 — `XR_ACTIVITY_START_MODE_Full_Space_Activity` + `XrFullSpaceLauncher` Bundle launch + SurfaceEntity Quad + 시네마룸 fade
- `FileListActivity` 영상 클릭 시 파일명 SBS 검출(`isSbsByName`) 결과로 `MainActivity` vs `SbsPlayerActivity` conditional launch
- 4/26 wip branch 의 `XrPlayerManager` / `XrPlaybackController` / `XrConfig` / `XrFullSpaceLauncher` 코드를 그대로 `SbsPlayerActivity` 에 이전 — MainActivity 흐름 깨끗 유지

이 디자인이 4/26 작업의 본질적 trade-off(Full Space ↔ Home Space, 한 Activity 에서 둘 다 못 가짐) 를 path 자체를 둘로 쪼개서 푸는 방향. 별 라운드 office-hours 에서 본격 디자인 doc 부터 시작.

## 보존

- wip commit 7ce278e on `wip/xr-2026-04-26-features` — 4/26 작업 전부(XrConfig / XrPlayerManager / XrPlaybackController / XrFullSpaceLauncher / 시네마룸 fade / SBS Quad / mainPanel 시도 / audio 폐기 결정 + 시도 + design doc) 살아있음
- 옵션 B 진입 시 cherry-pick 으로 SBS path 코드 가져옴
