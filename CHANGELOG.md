# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0.2] - 2026-04-30

### Added
- **VR360 (전방위) 정밀 검출** — `SpatialMode.VR360_SPHERE` 추가. MP4 sv3d 박스 발견 후 그 안의 `equi` 박스 `proj_bounds_top/bottom/left/right` 4 필드를 정밀 파싱 — 모두 0 이면 트림 없는 전방위 → `VR360_SPHERE`, 어느 하나라도 nonzero 면 트림 있는 → `VR180_HEMISPHERE`.
- **SurfaceEntity.Shape.Sphere(40m) 매핑** — VR360 콘텐츠를 사용자 둘레 360° sphere 안쪽 면에 매핑 (alpha13 SDK 의 Sphere shape 사용).
- 단위 테스트 3 신규 (`sv3dWithEqui_allZeroBounds` / `vr180Bounds` / `partialBounds`) — 총 29 cases.

### Changed
- `SpatialMediaParser.classifySphericalMode` 신설 — sv3d 발견 시 equi 박스 정밀 파싱으로 분기. equi 미발견 / bounds 잘림 → 보수적 default `VR180_HEMISPHERE`.
- `XrConfig.screenPose / screenShape / surfacePixelDim` — `VR360_SPHERE` 분기 추가 (origin pose, Sphere(40m), dynamic surface dim).

### Notes
- VR360 ↔ VR180 ratio 가 동일하므로 dimension heuristic 만으론 구분 불가. **MP4 sv3d/equi 박스가 박힌 콘텐츠에서만 정확한 분기**. 파일명 / dimension fallback 은 그대로 `VR180_HEMISPHERE` 보수적 default.

## [0.1.0.1] - 2026-04-30

### Added
- **MP4 sv3d / st3d 박스 메타데이터 파싱** — `xr/SpatialMediaParser.kt`. moov 영역에서 sv3d / st3d 4-byte 마커를 byte-grep + mdat 경계 + size sanity check 로 검색해 SpatialMode 추정. 파일명에 키워드 없는 콘텐츠도 metadata 가 박혀 있으면 정확한 모드로 검출.
- **해상도/비율 휴리스틱** — metadata · 파일명 모두 매칭 안 되면 영상 frame ratio 기반 fallback. 정확 2:1 / 1:1 (±0.5%) → VR180_HEMISPHERE, ≥ 3:1 → SBS_PANEL, FHD 미만 / 16:9 / 시네마스코프 → NONE. 일반 4K 영화 (3836×1912 = ratio 2.006 등 letterbox 변형) 도 정확히 일반 path 로 라우팅.
- `docs/vr180-metadata-parsing-2026-04-30.md` — TODO-VR180-3 (P3) 처리 + 사용자 요청 dimension heuristic 통합 기록.

### Changed
- `XrConfig.detectSpatialMode` — Uri overload 추가, 3-layer 우선순위 (metadata → 파일명 → 해상도/비율). 기존 `detectSpatialMode(name)` 시그니처는 유지.
- `FileListActivity.launchPlayer` / `SbsPlayerActivity.attemptStereoTakeover` — 새 Uri overload 호출로 변경. 회귀 0 (기존 키워드 콘텐츠 동작 그대로).

## [0.1.0.0] - 2026-04-29

### Added
- **VR180 hemisphere immersion (Galaxy XR 전용)** — VR180 영상을 Galaxy XR 에서 머리 주변을 둘러싸는 hemispherical 매핑으로 재생. equirectangular SBS 콘텐츠가 시야 전체를 덮음 (기존엔 평면 panel 만 가능).
- **자동 모드 분기** — 파일명 키워드로 SBS_PANEL (평면 양안) / VR180_HEMISPHERE (immersion) 자동 선택. VR180 키워드 (`[vr]`, `vr180`, `[180]` 등) 단독으로도 hemisphere 모드 진입.
- **4K H.264 HW 디코드** — Galaxy XR 의 c2.qti.avc.decoder 로 4K SBS 콘텐츠 끊김 없이 재생. `--no-mediacodec-dr` 제거가 c2 codec configure 활성화의 핵심.
- **영상 해상도 자동 매칭** — `MediaMetadataRetriever` 사전 probe 로 SurfaceEntity 의 픽셀 buffer 를 codec 출력 frame 과 정확히 일치 (4K, 1080p 콘텐츠 모두 자동).
- `app/src/main/java/com/example/minseo21/xr/SpatialMode.kt` — NONE / SBS_PANEL / VR180_HEMISPHERE enum
- `docs/vr180-smoke-test-2026-04-29.md` — smoke test 결과 + Step 1+2+3-lite + VR180-2 구현 기록

### Changed
- `XrConfig` — 단일 SCREEN_POSE/SHAPE/PIXEL_DIM 상수에서 mode 별 helper (`screenPose(mode)`, `screenShape(mode)`, `surfacePixelDim(mode, w, h)`) 로 리팩토링.
- `XrSurfaceController.setupStereoSurface` — mode 인자 + 영상 해상도 인자 추가. VR180 모드에서 Movable/Resizable 부착 skip (resize callback 이 hemisphere 를 quad 로 덮어쓰는 것 방지).
- `FileListActivity.launchPlayer` — SBS keyword 단독 검사 → `detectSpatialMode != NONE` 으로 변경. VR180 only 파일도 SbsPlayerActivity 라우팅 진입.
- `.gitignore` — `.scratch/`, `*.mp4`, `*.mkv`, `*.mov`, `*.MP4` 추가 (smoke test 임시 캡처 / 사용자 콘텐츠 commit 방지).

## [0.0.0.2] - 2026-04-28

### Changed
- 영상 일시정지/정지/종료 시 NAS 동기화 타이머도 함께 멈추도록 정리 (이전: 30초 주기로 불필요한 flush 호출 지속). 재생 재개 시 자동 재시작.

### Added
- `docs/position-timer-lifecycle-2026-04-28.md` — position write 타이머 lifecycle 정렬 작업 기록
- `CLAUDE.md` 에 Documentation 정책 명시 — 코드 수정 시 `docs/<주제>-YYYY-MM-DD.md` 작업 기록 의무화

## [0.0.0.1] - 2026-04-26

### Changed
- 프로젝트 문서 파일 규칙 일괄 정비: 모든 작업 문서를 `docs/` 폴더로 이동하고 파일명 끝에 작성일 suffix(`-YYYY-MM-DD`) 추가
- `TODOS.md` → `docs/todos-2026-04-10.md` (루트에서 docs/로 이동)
- `.gstack/qa-reports/qa-report-minseo2-2026-04-10.md` → `docs/qa-report-minseo2-2026-04-10.md` (docs/로 이동)
- 문서 간 교차 참조 링크 2건 갱신 (`qa-report`, `xr-api-manifest-reference`)
