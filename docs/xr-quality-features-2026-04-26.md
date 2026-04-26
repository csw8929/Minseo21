# XR 화질 + 부가 기능 + config 통합 (2026-04-26)

`feat/xr-cinema-room` 브랜치, 시네마룸 구현(`xr-cinema-room-implementation-2026-04-26.md`) 위에 얹은 후속 작업. office-hours design doc(`USER-feat-xr-cinema-room-design-20260426-140642.md`, APPROVED) 의 video 트랙 + 같은 세션 흐름에서 나온 추가 기능들 통합 기록.

## 1. 작업 묶음 요약

| # | 항목 | 한 줄 요약 |
|---|---|---|
| **A** | video 색강화 | `XrPlaybackController.applyVlcOptions` 에 libVLC `--video-filter=adjust` + `--saturation=1.15` + `--contrast=1.05` 추가. XR 분기 한정. OLEDoS DCI-P3 95% + 명암비 무한대 활용 |
| **B** | audio 트랙 폐기 | "stereo 가상 surround = 임의 합성 = 왜곡" 판단으로 영구 비채택. 코드 흔적 X, 주석으로 사유만 박음 |
| **C** | FileListActivity XR back 버튼 | 헤드셋 시스템 back 제스처 어색해 list 화면 우하단에 동그란 빨간 floating back 버튼 추가. XR 단말만 visible |
| **D** | SBS 화면 시작 크기 4배 (면적) | `SCREEN_SHAPE` 3.2×1.8m → 6.4×3.6m (각 변 2배 = 면적 4배, 약 100인치 TV 급) |
| **E** | XrConfig.kt 신설 | 흩어진 XR magic value 모두 한 object 로 통합. 미세조정 진입점 단일화 |

## 2. A. Video 색강화

### 변경
`xr/XrPlaybackController.java::applyVlcOptions(List<String>)` 에 3줄 추가:
```java
options.add("--video-filter=adjust");
options.add("--saturation=" + XrConfig.COLOR_SATURATION);  // "1.15"
options.add("--contrast=" + XrConfig.COLOR_CONTRAST);      // "1.05"
```

### 검증
- Phase 0-A: `libvlc-all-3.6.0.aar` 의 `arm64-v8a/libvlc.so` 에서 `adjust_sat_hue` 함수 + `saturation=` 키워드 + `--enable-swscale` 매치 → adjust 모듈 컴파일 확정
- Phase 1 실기기(R34YA0007ZJ): saturation=2.0/contrast=1.4 강도로 첫 시도 → 사용자 시각 차이 명확 → 옵션 정상 적용 입증 → 1.15/1.05 production 값으로 정착(자연스러운 펀치)
- 비-XR 단말 영향 0 (XR 분기 안에서만)

## 3. B. Audio 트랙 폐기

### 결정
가상 surround / HRTF binaural / Dolby Headphone / spatialaudio filter 등 **임의 합성하는 모든 path 영구 비채택**.

### 사유
1. **임의 합성 = 왜곡**: stereo 2채널 → 5.1 6채널 가상 surround 는 원본에 없는 정보를 알고리즘이 추측 합성. 영화/음악 원작자 mix 의도와 정합 안 됨. video 색강화(픽셀 1:1 함수 매핑 — 원본 보존) 와 본질적 차이
2. **HRTF generic 두상 기준** → 사용자 개인 이도 형상과 안 맞으면 음색 변형. 외두화 효과는 얻지만 비용 큼
3. **Head-tracking 정합은 system 자동 처리**: Galaxy XR system Spatializer 가 AudioAttributes 명시 없이도 panel-fixed audio 자동 활성. 우리 audio chain 건드릴 이유 0
4. **5.1/Atmos passthrough ROI 0**: 라이브러리 stereo source 위주
5. **실험 결과**: `--audio-filter=headphone --headphone-dolby=1` → ANR (binder freeze 실패). 원인 추정: boolean syntax 위반 + headphone HRTF 와 mediacodec_jni audio output 호환 race

### 흔적
- `XrPlaybackController.applyVlcOptions` 주석에 폐기 사유 3줄 박음 (향후 코드 보는 사람 빠른 컨텍스트)
- design doc "Audio 트랙 결과" 섹션 + 메모리 `project_xr_audio_decommissioned.md` 에 결정 + 재검토 트리거 명시

## 4. C. FileListActivity XR back 버튼

### 배경
Galaxy XR 헤드셋의 시스템 back 제스처가 어색해 list 화면에서 뒤로 가기가 사실상 불가. MainActivity 영상 화면에는 이미 동그란 빨간 floating back(`btnBack` + `bg_red_circle` + `ic_back`) 있어, 같은 패턴을 list 에도 적용.

### 변경
- `res/layout/activity_file_list.xml` — 콘텐츠 `FrameLayout` child 마지막에 `ImageButton btnXrBack` 추가 (56dp 빨간 원, 우하단 24dp margin, `visibility="gone"`)
- `FileListActivity.java::onCreate` — XR feature 검출(`XrConfig.isXrDevice(pm)`) 시 `setVisibility(VISIBLE)` + `onClick → getOnBackPressedDispatcher().onBackPressed()` (이미 등록된 `OnBackPressedCallback` 그대로 트리거)

### 검증 결과
- XR 단말(R34YA0007ZJ): 동그란 back 버튼 우하단 표시, 클릭 시 NAS stack pop / 즐겨찾기→로컬 / 버킷 list / 종료 모두 시스템 back 과 동일
- 비-XR 단말(R54Y1003KXN 탭): feature 매치 0 → `visibility="gone"` 유지, 기존 시스템 back 그대로 사용

## 5. D. SBS 화면 시작 크기 키움

### 변경
`XrConfig.SCREEN_SHAPE` Quad(3.2 × 1.8m) → Quad(**6.4 × 3.6m**). 면적 4배(각 변 2배), 16:9 비율 유지, 약 100인치 TV 급. 3m 거리 default `SCREEN_POSE` 와 결합해 시야 충분히 채움.

### 적용 범위 — **SBS path 한정**
중요한 발견: 일반 2D 영상은 `VLCVideoLayout`(TextureView) path 라 `SurfaceEntity` / `SCREEN_SHAPE` 자체가 사용 안 됨. 일반 2D panel 크기는 Galaxy XR system default(약 0.89×0.56m)가 결정. **본 변경은 SBS 영상에서만 효과**.

### 미해결 — 일반 2D 영상 panel 크기
사용자가 일반 2D 영상에서도 panel 크기를 키우길 희망. 후보 path:
1. `AndroidManifest.xml` Activity property 로 preferred panel size 지정
2. `XrExtensions` API 로 `mainWindowSize` 직접 설정
3. Activity bounds / window 옵션 조정

별 라운드(TODO-XR-9)로 분리.

## 6. E. XrConfig.kt 신설 (refactor)

### 동기
XR magic value 가 코드 군데군데 흩어져 있음 — 빌더 직관에 거슬림. 미세조정 어디 가야 하는지 매번 grep. 향후 사용자 prefs UI(TODO-XR-8) 도입 시 진입점도 흩어짐.

### 통합 대상
| 항목 | 이전 위치 | 신규 위치 |
|---|---|---|
| `SCREEN_POSE` (시작 위치) | `XrPlayerManager.kt::companion` | `XrConfig.SCREEN_POSE` |
| `SCREEN_SHAPE` (시작 크기) | 〃 | `XrConfig.SCREEN_SHAPE` |
| `SURFACE_PIXEL_DIM` (1920×1080) | `XrPlayerManager.kt::setupStereoSurface` 인라인 | `XrConfig.SURFACE_PIXEL_DIM` |
| `PASSTHROUGH_FADE_MS` (400L) | `XrPlayerManager.kt::companion` | `XrConfig.PASSTHROUGH_FADE_MS` |
| `SBS_RATIO_THRESHOLD` (3.5f) | `XrPlayerManager.kt::sbsRatioMatch` 인라인 | `XrConfig.SBS_RATIO_THRESHOLD` |
| `SBS_FILENAME_KEYWORDS` (5개) | `XrPlayerManager.kt::sbsPatternMatch` 인라인 | `XrConfig.SBS_FILENAME_KEYWORDS` |
| `DEVICE_FEATURES` (3개 flag) | `XrPlayerManager.isXrDevice` + `FileListActivity` 중복 | `XrConfig.DEVICE_FEATURES` + `XrConfig.isXrDevice(pm)` helper |
| `COLOR_SATURATION` ("1.15") | `XrPlaybackController.applyVlcOptions` 인라인 | `XrConfig.COLOR_SATURATION` |
| `COLOR_CONTRAST` ("1.05") | 〃 | `XrConfig.COLOR_CONTRAST` |

### 설계
- Kotlin `object` — Java 에서도 `XrConfig.FOO` 직접 접근(`@JvmField` / `const val` / `@JvmStatic`)
- 색강화 값은 Locale 의존성(`,` vs `.`) 회피 위해 `String` 으로 보관 (libVLC option 직결)
- `XrConfig.isXrDevice(pm)` helper 로 검출 logic 도 단일화 — `FileListActivity` 의 인라인 중복 제거

### 동작 동일성
값은 모두 그대로 유지(SCREEN_SHAPE 만 6.4×3.6 으로 키운 D 항목 반영). Refactor 후 빌드 통과, 실기기 install 진행.

## 7. 검증 방법

```bash
# 빌드
./gradlew assembleDebug

# 설치 (XR 단말)
adb -s R34YA0007ZJ install -r app/build/outputs/apk/debug/Minseo21.apk

# 회귀 테스트 — 비-XR 단말 영향 0
adb -s R54Y1003KXN install -r app/build/outputs/apk/debug/Minseo21.apk
```

체크리스트:
- [x] XR 단말: 일반 2D 영상 재생 → 색이 OLEDoS 명암비로 약간 펀치 살아남(saturation 1.15, contrast 1.05)
- [x] XR 단말: SBS 3D 영상 재생 → 화면 시작 크기 6.4×3.6m, ResizableComponent 동작
- [x] XR 단말: list 화면 우하단 동그란 back 버튼, 클릭 시 정상 back
- [x] 비-XR 단말: back 버튼 안 보이고, 시스템 back 그대로
- [x] 비-XR 단말: 색·해상도 동작 무변경

## 8. 후속 TODO

- **TODO-XR-7**: SBS surface 픽셀 buffer 1920×1080 → panel native(3552×3840 / eye) 끌어올림. measurement gate(GPU 메모리 / 발열) 필요. design doc Phase 2 의 본격 작업
- **TODO-XR-8**: 사용자 prefs UI 로 색강화 saturation/contrast 미세조정. `XrConfig` 가 진입점
- **TODO-XR-9** (신설): 일반 2D 영상 panel 크기 키우기. system default panel(0.89×0.56m) 너머로. AndroidManifest property / XrExtensions API 조사 필요
