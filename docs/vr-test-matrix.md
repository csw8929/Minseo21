# VR 콘텐츠 형식 × SpatialMode 매트릭스 (2026-04-30~)

`XrConfig.detectSpatialMode` 가 다양한 VR 콘텐츠 형식에 대해 의도한 SpatialMode 로
분류하는지 추적하는 라이브 매트릭스. 새 형식·sample 만날 때마다 줄 추가하고
실측 결과 기록.

분류기 출력은 3-state — `VR180_HEMISPHERE` / `SBS_PANEL` / `NONE`. 자세한 설계는
`docs/vr180-metadata-parsing-2026-04-30.md` 참고.

---

## 형식 매트릭스

| ID | 형식 | 표준 해상도 | ratio | 기대 분류 | 분류 layer | 참고 |
|---|---|---|---|---|---|---|
| F-01 | VR180 SBS 4K | 4096×2048 | 2.000 | VR180_HEMISPHERE | metadata / 파일명 / dimension | mainstream |
| F-02 | VR180 SBS 5.7K | 5760×2880 | 2.000 | VR180_HEMISPHERE | dimension | Insta360 / Vuze |
| F-03 | VR180 SBS 8K | 7680×3840 | 2.000 | VR180_HEMISPHERE | dimension | Galaxy XR 디스플레이 매칭 |
| F-04 | VR180 TaB 정사각 | 4096×4096 | 1.000 | VR180_HEMISPHERE | dimension | 위/아래 분할 |
| F-05 | VR360 mono full-equi | 4096×2048 | 2.000 | VR180_HEMISPHERE (근사) | dimension | hemisphere 매핑이 부정확 — 앞 절반만 보임. P4 정밀화 후 분리 필요 |
| F-06 | VR360 SBS | 4096×4096 / 8192×8192 | 1.000 | VR180_HEMISPHERE (근사) | dimension | 위와 동일한 한계 |
| F-07 | Full-SBS 3D 영화 4K | 7680×2160 | 3.555 | SBS_PANEL | dimension | |
| F-08 | Full-SBS 3D 영화 1080p | 3840×1080 | 3.555 | SBS_PANEL | dimension | |
| F-09 | Half-SBS 3D 영화 1080p | 1920×1040 / 1920×1080 | ~1.78 | SBS_PANEL (파일명 키워드 필요) | 파일명 | dimension 만으론 NONE → 키워드 의존 |
| F-10 | MV-HEVC (Apple Spatial) | 16:9 + 두 트랙 | 1.778 | (현재 미지원) | metadata 미파싱 | 분류기에서 NONE → 일반 path |
| F-11 | EAC (Equi-Angular Cubemap) | 다양 | 다양 | (현재 미지원) | - | YouTube VR 등 |
| F-12 | 일반 4K 영화 16:9 | 3840×2160 | 1.778 | NONE | dimension | 정상적으로 일반 path |
| F-13 | 일반 영화 letterbox 18:9 | 3836×1912 | 2.006 | NONE | dimension | ±0.5% 밖 → 안전하게 reject |
| F-14 | 일반 영화 cinemascope | 2560×1072 | 2.388 | NONE | dimension | |
| F-15 | 일반 1080p / 720p | 1920×1080 / 1280×720 | 1.778 | NONE | dimension (FHD 미만) / 파일명 | |

---

## 검증 매트릭스 (실기 — Galaxy XR R34YA0007ZJ)

각 케이스: 파일명 / 해상도 / 분류 layer / 기대 / 실측 / 검증일

| 케이스 | 형식 ID | 파일 / 해상도 | 분류 layer | 기대 | 실측 | 검증일 |
|---|---|---|---|---|---|---|
| C-01 | F-01 | `DSVR-995 ... [VR] A._sbs.mp4` (4096×2048) | 파일명 (`[VR]` `_sbs`) | VR180_HEMISPHERE | ✅ VR180_HEMISPHERE | 2026-04-30 |
| C-02 | F-12 | `Five Nights ... 2160p 4K.mkv` (3836×1912) | dimension (ratio 2.006 → out of ±0.5%) | NONE | ✅ NONE → 일반 path | 2026-04-30 |
| C-03 | F-09 | `Men In Black 3 H_SBS 1080p.mp4` (1920×1040) | 파일명 (`H_SBS`) | SBS_PANEL | ✅ SBS_PANEL | 2026-04-30 |
| C-04 | F-12 | (사용자 보유 16:9 4K) | dimension | NONE → 일반 path | ✅ | 2026-04-30 |
| C-05 | F-01 (metadata 박힌) | (sample 미확보) | metadata | VR180_HEMISPHERE | ⏳ 미검증 | - |
| C-06 | F-04 | (sample 미확보) | dimension | VR180_HEMISPHERE | ⏳ 미검증 | - |
| C-07 | F-08 | (sample 미확보) | dimension | SBS_PANEL | ⏳ 미검증 | - |

---

## 단위 테스트 매트릭스 (JVM, `./gradlew test`)

`app/src/test/java/com/example/minseo21/xr/`:

### `SpatialMediaParserTest.kt` (8 cases)
- `emptyInput` / `tooShortInput` → NONE
- `sv3dInMoov` → VR180_HEMISPHERE
- `st3dWithoutSv3d` → SBS_PANEL
- `sv3dWinsOverSt3d` → VR180 (sv3d 우선)
- `mdatBeforeSv3d_truncatesSearch` → NONE (non-fast-start MP4 시뮬)
- `sv3dWithBogusSize_rejected` → NONE (size sanity check 작동)
- `noSpatialMarker` → NONE

### `DimensionDetectionTest.kt` (18 cases)
- VR180 매치: 4096×2048 / 5760×2880 / 7680×3840 / 4096×4096
- SBS_PANEL 매치: 7680×2160 / 3840×1080
- NONE: 일반 4K 16:9 / letterbox 2.006 / cinemascope / Half-SBS / FHD 미만 / square small
- tolerance 경계: 정확 2.0 / 2.011 / 1.005 / 0 / 음수

---

## 새 형식 추가 워크플로우

1. **샘플 확보** — `.scratch/` (gitignored) 에 sample 1개 두고 `ffprobe` / `mp4dump`
   로 박스 트리 사전 검사:
   ```bash
   ffprobe -show_streams -show_entries side_data sample.mp4
   mp4dump sample.mp4 | grep -E 'sv3d|st3d|proj|equi|tkhd'
   ```
2. **실기 테스트** — `adb push` 후 앱 실행, `adb logcat -s SACH_XR` 로 분류 확인.
3. **결과 기록** — 위 "검증 매트릭스" 표에 행 추가.
4. **분류기 갭 발견** — `XrConfig.detectByRatio` / `SpatialMediaParser.parseStream`
   조정 + 단위 테스트에 케이스 추가.
5. **회귀 보호** — `./gradlew test` 통과 확인 후 PR.

---

## 샘플 콘텐츠 출처

| 출처 | 무엇 | 메모 |
|---|---|---|
| [Google `spatial-media`](https://github.com/google/spatial-media) | 공식 sv3d/st3d/proj 박스 박힌 sample + 도구 (CLI 로 metadata 주입 가능) | metadata layer positive 검증용 |
| YouTube VR180 trailer (yt-dlp) | 실제 metadata 박힌 4K 콘텐츠 | format=mp4, height=2048 등 필터링 |
| [Insta360 sample gallery](https://www.insta360.com) | 6K/8K SBS / TaB / mono | 무료 sample |
| Apple TV "Encounter Dinosaurs" | MV-HEVC spatial video | F-10 검증용 (현재 미지원, 분류기 갭) |

---

## 알려진 한계 (P4 후속)

- **VR360 ↔ VR180 ratio 동일 (2:1)** — `proj.equi.proj_bounds` 박스 정밀 파싱 필요.
  현재 둘 다 `VR180_HEMISPHERE` 로 분류되어 VR360 콘텐츠가 hemisphere 안쪽에서
  앞 절반만 보임.
- **Half-SBS / MV-HEVC** — ratio 만으론 일반 영상과 구분 불가. 파일명 / metadata
  의존. MV-HEVC 는 별도 트랙 구조라 추가 파서 필요.
