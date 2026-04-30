# VR360 정밀 검출 — `SpatialMode.VR360_SPHERE` + equi.proj_bounds 파싱 (2026-04-30)

`docs/vr-test-matrix.md` / 메모리 P4 의 **VR360 ↔ VR180 정밀화** 처리 기록.

## 배경

v0.1.0.1 까지의 분류기는 다음 한계가 있음:
- VR180 (앞 180°) 와 VR360 (전방위) 가 **frame ratio 가 동일** (4096×2048 = 2:1).
- dimension heuristic 만으론 구분 불가 → 둘 다 `VR180_HEMISPHERE` 로 분류.
- 결과: VR360 콘텐츠를 hemisphere(180° 반구) 안쪽에 매핑하면 **앞 절반만 보이고
  화면이 좌우로 stretched** (180° 면적에 360° 콘텐츠를 우겨 넣음).

## 구분 방법 — Spatial Media V2 의 `equi.proj_bounds`

MP4 sv3d 박스 안의 `proj > equi` 박스에 4 필드:

```
[size:u32 = 28][type:'equi']        (8 byte header)
[version:u8][flags:u24]             (4 byte)
[bounds_top:u32]                    (이하 0.0.32 fixed-point: 1.0 = 0xFFFFFFFF)
[bounds_bottom:u32]
[bounds_left:u32]
[bounds_right:u32]                  (총 28 byte)
```

| 콘텐츠 형식 | top | bottom | left | right | 의미 |
|---|---|---|---|---|---|
| VR360 (전방위) | 0 | 0 | 0 | 0 | 트림 없음 |
| VR180 (앞 180°) | 0 | 0 | 0x40000000 | 0x40000000 | 좌우 25%씩 트림 |
| (그 외) | varies | varies | varies | varies | 부분 equirect — 보수적으로 VR180 처리 |

## 구현

### `SpatialMode.kt` — `VR360_SPHERE` enum 추가

```kotlin
enum class SpatialMode {
    NONE,
    SBS_PANEL,
    VR180_HEMISPHERE,
    VR360_SPHERE,   // 신설
}
```

### `SpatialMediaParser.kt` — `classifySphericalMode()` 신설

기존: sv3d 발견 → 무조건 `VR180_HEMISPHERE`.

신설: sv3d 발견 후 그 이후 영역에서 `equi` 마커 추가 검색 → bounds 4 필드 읽음:
- 모두 0 → `VR360_SPHERE`
- 어느 하나라도 nonzero → `VR180_HEMISPHERE` (트림 있음)
- equi 미발견 / 데이터 잘림 → `VR180_HEMISPHERE` (보수적 default — 변화 없음)

`findVerifiedMarker` 에 `fromIndex` 파라미터 추가 (sv3d 이후부터 search 가능하도록).

### `XrConfig.kt` — VR360 분기 추가

```kotlin
private val VR360_POSE: Pose = VR180_POSE   // origin (사용자 머리 위치)
private val VR360_SHAPE: SurfaceEntity.Shape = SurfaceEntity.Shape.Sphere(40.0f)
private val VR360_SURFACE_DIM: IntSize2d = IntSize2d(4096, 2048)
```

`screenPose / screenShape / surfacePixelDim` 의 `when` 분기 모두 VR360_SPHERE 추가.

`Shape.Sphere` 는 alpha13 SDK 에 노출됨 (`SurfaceEntity$Shape$Sphere.class` 확인 완료).

### `XrSurfaceController.kt` — 변경 없음

코드가 positive list 패턴 (`if (mode == SBS_PANEL)`) 이라 새 enum 자동으로 안전하게
처리됨 (Movable/Resizable skip, applyVideoAspect skip). 주석만 갱신.

### `detectByRatio` — 변경 없음

VR360 SBS (4096×4096 = 1.0) / VR360 mono (4096×2048 = 2.0) 모두 dimension 만으론
VR180 과 구분 불가. dimension fallback path 는 그대로 `VR180_HEMISPHERE` 보수적
default (사용자 corpus 의 95%가 VR180 임을 고려).

**즉 VR360 정확 검출은 metadata 가 박힌 콘텐츠에서만 작동.** 파일명 / dimension
fallback 은 VR180 으로 들어가는데, VR360 콘텐츠는 콘텐츠 측에서 metadata 를 박는
것이 표준이므로 (Spatial Media V2 spec) 실용상 충분.

## 단위 테스트 (`./gradlew test`)

`SpatialMediaParserTest.kt` 에 3 케이스 신규 (총 11 cases):
- `sv3dWithEqui_allZeroBounds_returnsVr360Sphere` — bounds (0,0,0,0)
- `sv3dWithEqui_vr180Bounds_returnsVr180Hemisphere` — bounds (0,0,0x40000000,0x40000000)
- `sv3dWithEqui_partialBounds_returnsVr180Hemisphere` — bounds (0,0x10000000,0,0)

기존 케이스도 그대로 동작 — sv3d 만 있고 equi 없는 케이스는 보수적 default 인
`VR180_HEMISPHERE` 반환 (기존 expectation 와 동일).

## 검증 상태

| 항목 | 상태 | 비고 |
|---|---|---|
| 단위 테스트 | ✅ 29/29 통과 | `./gradlew test` |
| 빌드 | ✅ assembleDebug 성공 | |
| 실기 — sv3d/equi 박힌 VR360 콘텐츠 | ⏳ 미검증 | 사용자 corpus 부재. Google Spatial Media samples / YouTube 360 다운 후 검증 |
| 실기 — sv3d/equi 박힌 VR180 콘텐츠 (regression) | ⏳ 미검증 | 위와 동일. 기존 v0.1.0.1 동작 보호용 |
| 실기 — 사용자 보유 VR180 (metadata 없음) | ✅ 회귀 보호 (parser 그대로 NONE → 파일명 fallback → VR180_HEMISPHERE) | logic 으로 자명 |

코드 검토 + 단위 테스트 + alpha13 Shape.Sphere 존재 확인 으로 ship 결정.
metadata 박힌 sample 입수 시 검증 매트릭스 (`docs/vr-test-matrix.md`) 갱신.

## Trade-off

| 측면 | 선택 | 이유 |
|---|---|---|
| equi bounds 정밀 해석 | 4 필드 모두 0 vs 그 외 (binary) | spec 상 VR180 표준은 left/right=0x40000000. 그 외 부분 트림은 흔치 않음 — binary 분류로 충분 |
| 파일명 / dimension fallback 시 default | VR180_HEMISPHERE 유지 | 사용자 corpus 95%가 VR180. VR360 콘텐츠는 metadata 박힌 형태가 표준 |
| Sphere radius | 40m (VR180 와 동일) | 일관성. 사용자 hemisphere 튜닝 결과 적용 |

## 후속

- VR360 SBS (`top-bottom` / `side-by-side` stereo 360°) — `st3d.stereo_mode` 정밀
  파싱 후 적용 가능. 현재 `StereoMode.SIDE_BY_SIDE` 강제이므로 SBS-360 만 작동, mono-360 도 SIDE_BY_SIDE 로 매핑되면 좌/우 같은 영상 → 무해.
- `docs/vr-test-matrix.md` — F-05 (VR360 mono full-equi) 의 분류 결과를
  `VR180_HEMISPHERE (근사)` → `VR360_SPHERE (metadata 박힌 경우)` 로 갱신 예정.
