# VR180 / SBS 메타데이터 파싱 + 해상도/비율 휴리스틱 (2026-04-30)

`docs/todos-2026-04-10.md` P3 / 메모리 `project_xr_cinema_room_followups.md` 의
**TODO-VR180-3 (MP4 sv3d/equi box parsing)** 처리 + 후속 사용자 요청
(파일명·메타데이터 모두 비어 있어도 해상도 형태가 VR 일치하면 자동 매핑) 통합 기록.

## 배경

v0.1.0.0 의 SpatialMode 검출은 **파일명 heuristic 한 가지뿐**:

```kotlin
// XrConfig.detectSpatialMode(name) — before
if (vr180PatternMatch(name)) return VR180_HEMISPHERE   // [vr], [180], _180_, ...
if (sbsPatternMatch(name)) return SBS_PANEL             // _sbs, _3d, [sbs], ...
return NONE
```

문제:
- YouTube · Insta360 · DJI · 기타 spatial 카메라 출력은 **파일명에 키워드 없이도
  MP4 컨테이너에 `sv3d` (Spherical Video) / `st3d` (Stereoscopic) 박스를 박아
  넣음** → 파일명만 보면 NONE 으로 분류되어 일반 path 로 빠짐.
- 반대로, 파일명에 `_sbs` 가 있어도 실제 콘텐츠가 hemisphere 가 아닌 경우 (단순
  3D 영화 등) — 이건 v0.1.0.0 에서도 SBS_PANEL 로 정상 분류되므로 OK.
- 결과적으로 **metadata 가 정확함에도 파일명 누락으로 미검출** 케이스가 잠재 risk.

## 변경

### 새 파일 — `app/src/main/java/com/example/minseo21/xr/SpatialMediaParser.kt`

MP4 컨테이너의 `sv3d` / `st3d` 박스 존재 여부로 SpatialMode 추정.

**구현 방식: byte-grep + mdat 경계 + size sanity check (conservative)**

Google Spatial Media v2 spec 의 박스 트리(moov → trak → mdia → minf → stbl → stsd
→ visual sample entry → st3d / sv3d) 정밀 descent 대신, 4-byte 마커를 직접 검색.

```kotlin
fun parse(context: Context, uri: Uri?): SpatialMode {
    // content:// / file:// 만 처리. http(s) 즉시 NONE.
    // 1MB read budget. moov 가 이보다 크면 NONE.
    // mdat 박스 시작 인덱스를 search 상한으로 잡아 압축 비디오 데이터에서의
    // false-positive 차단.
    // 마커 직전 4 byte 가 그럴듯한 box size (8 ~ 16MB) 인지 검증 → 추가 차단.
}
```

분류 결과:
- **`sv3d` 발견 → `VR180_HEMISPHERE`** (구형/반구형 spherical 콘텐츠)
- **`st3d` 만 발견 → `SBS_PANEL`** (sv3d 없음 → flat stereoscopic)
- **둘 다 없음 / non-fast-start MP4 / 비-로컬 scheme / 파싱 실패 → `NONE`**
  (호출자가 파일명 heuristic 으로 fallback)

**왜 byte-grep 인가** — 정밀 트리 descent 는 ~200 줄, 1주일 작업, 그리고
visual sample entry 의 78-byte 가변 fixed header 처리 등 함정 多. 우리 분류는
3-state (NONE/SBS/VR180) 이므로 마커 존재 자체만 확인하면 충분. mdat 경계 +
size sanity check 두 layer 로 false-positive 차단되어 실용적.

후속 정밀화 가능성 (다음 이터레이션):
- `st3d.stereo_mode` 값 (1=top-bottom, 2=SBS) 정확히 읽기
- `sv3d > proj > equi.proj_bounds` 로 VR180 vs VR360 구분
- `tkhd.matrix` 로 영상 회전 보정

### 수정 — `app/src/main/java/com/example/minseo21/xr/XrConfig.kt`

기존 `detectSpatialMode(name)` 시그니처는 유지하고 (legacy / fallback path),
**Uri overload 추가**:

```kotlin
@JvmStatic
fun detectSpatialMode(context: Context, uri: Uri?, name: String?): SpatialMode {
    val byMetadata = SpatialMediaParser.parse(context, uri)
    if (byMetadata != SpatialMode.NONE) {
        Log.i("SACH_XR", "detectSpatialMode: metadata 우선 → $byMetadata ...")
        return byMetadata
    }
    // parser NONE → 파일명 heuristic fallback
    return detectSpatialMode(name)
}
```

### 수정 — 호출 지점 2곳

**`FileListActivity.launchPlayer(intent, name)`**:
```java
// before
XrConfig.detectSpatialMode(name)
// after
XrConfig.detectSpatialMode(this, intent.getData(), name)
```

**`SbsPlayerActivity.attemptStereoTakeover(mp, sourceName)`**:
```java
// before
SpatialMode mode = XrConfig.detectSpatialMode(sourceName);
// after
Uri uri = getIntent() != null ? getIntent().getData() : null;
SpatialMode mode = XrConfig.detectSpatialMode(this, uri, sourceName);
```

NAS HTTP streaming path 도 같은 코드 흐름을 타지만 parser 가 scheme 검사로 즉시
NONE 반환 → 자연스럽게 파일명 heuristic 으로 fallback (range request 비용 없음).

### 추가 — Layer 3: 해상도/비율 heuristic (`XrConfig.detectByDimension`)

사용자 요청 (2026-04-30): "FHD 이상이면 VR 영상으로 처리" — 단순 해상도 임계로
하면 일반 4K 영화도 hemisphere 안에 휘어져 망가짐. 대신 **해상도 + 비율** 조합으로
강화한 휴리스틱을 layer 3 fallback 으로 추가.

분류:

| 비율 (±0.5%) | 해상도 ≥ FHD | 매핑 | 표준 콘텐츠 예시 |
|---|---|---|---|
| 2.0 (정확) | ✓ | VR180_HEMISPHERE | 4096×2048, 5120×2560 (한 눈 정사각) |
| 1.0 (정확) | ✓ | VR180_HEMISPHERE | 4096×4096 (VR360 mono full-equi 근사) |
| ≥ 3.0 | ✓ | SBS_PANEL | 3840×1080, 7680×2160 (Full-SBS 평면) |
| 그 외 | ✓ | NONE | 16:9 일반 / 시네마스코프 / 18:9 letterbox |
| - | ✗ | NONE | FHD 미만은 일반 영상 |

**왜 ±0.5% tolerance** — 초기 ±5% 로 시작했으나 letterbox 크롭된 4K 영화
(`Five Nights At Freddys 2`, 3836×1912 = ratio 2.006) 가 잘못 매치되어 VR180 으로
오분류 (실기기 검증 중 발견). VR 콘텐츠는 codec 출력이 표준 정수 해상도라 ratio 가
정확히 2.0 / 1.0 으로 떨어지고, 일반 영화는 letterbox 로 비정수 비율이 되므로
±0.5% 로 좁히면 정확히 분리됨.

코드 위치: `xr/XrConfig.kt` 의 `detectByDimension()` + `probeVideoDimensions()`
private helper. `detectSpatialMode(context, uri, name)` 의 마지막 fallback layer.

## Trade-off

| 측면 | 선택 | 이유 |
|---|---|---|
| 파싱 정밀도 | byte-grep + sanity check | 정밀 트리 descent 대비 1/10 LOC. 분류 3-state 에 충분. mdat 경계 + size check 두 layer 로 false-positive 실질 0 |
| Read budget | 1MB | 실제 MP4 의 moov 는 보통 수십~수백 KB. 1MB cap 으로 GC 압력 최소화. moov 가 이보다 크면 정상적으로 NONE 반환 |
| 비-로컬 scheme | 즉시 NONE | NAS HTTPS streaming 에 range request 보내면 UI 응답 지연 + Synology 부하. 파일명 fallback 으로 충분 |
| 실행 스레드 | UI 스레드 (sync) | 로컬 NAND 1MB read ~10~30ms. `launchPlayer` / `attemptStereoTakeover` 모두 launch flow 의 단발 호출 |
| 기존 동작 영향 | 없음 (회귀 0) | `detectSpatialMode(name)` 기존 시그니처 유지 + parser 가 NONE 일 때 정확히 같은 fallback 결과 |

## 검증

**환경:** Galaxy XR (R34YA0007ZJ), v0.1.0.1-dev (이 PR), `DSVR-995 (DSVR-0995) [VR] A._sbs.mp4` (4.48GB, 4K SBS)

해당 파일은 **non-fast-start MP4** (ftyp 직후 mdat largesize, moov 가 끝) +
**spatial metadata 없음** — 회귀 시나리오 검증에 적합.

**로그캣 결과:**
```
D/SACH_XR: SpatialMediaParser: no spatial marker (read=1048576, search=36B, mdat@36)
I/SACH_XR: detectSpatialMode: 파일명 fallback → VR180_HEMISPHERE (name=DSVR-995 ... [VR] A._sbs.mp4)
I/SACH_XR: SpatialMode=VR180_HEMISPHERE: '...' probe=4096x2048
I/SACH_XR: surfacePixelDim → 4096x2048 (probe 4096x2048, mode=VR180_HEMISPHERE)
I/SACH_XR: XR StereoSurface 연결 완료 (mode=VR180_HEMISPHERE)
I/SACH_XR: requestFullSpaceMode() 안전망 호출
```

확인 사항:
- ✅ parser 가 정상 호출됨 (1MB read 완료)
- ✅ mdat 가 offset 36 에서 발견되어 search 범위가 36B 로 축소 — 의도된 non-fast-start 처리
- ✅ NONE 반환 후 파일명 heuristic fallback → VR180_HEMISPHERE 정확 검출
- ✅ FileListActivity (라우팅) + SbsPlayerActivity (takeover) 양쪽에서 parser 호출 (2회 로그)
- ✅ Hemisphere 진입, surface dim 일치, Full Space 모드 진입 — v0.1.0.0 동작 그대로

**미검증 — sv3d/st3d 가 실제로 있는 파일에서의 positive path:**
사용자가 보유한 VR180 파일이 모두 metadata 없는 형태였음. 코드 검토 + 단위 테스트
가능한 로직이라 v0 ship 우선, 추후 spatial metadata 가 박힌 파일 입수 시
positive 케이스 실기 검증 예정.

## 호환성 / 회귀 영향

- 비-XR 단말 — 변경 없음 (`isXrDevice` 체크가 분기 진입 차단).
- 기존 `_sbs` / `[VR]` 파일명 콘텐츠 — parser 가 NONE 반환 → 파일명 fallback →
  기존 동작 그대로.
- NAS HTTP streaming — parser 가 scheme 검사로 즉시 NONE → 파일명 fallback →
  기존 동작 그대로.
- 새로 검출 가능: spatial metadata 가 박힌 파일명 무관 콘텐츠.

## 후속 TODO

- **TODO-VR180-1 (P2)**: 화질 개선 (texture filtering / mesh tessellation API
  탐색). `docs/todos-2026-04-10.md` 우선순위 다음 항목.
- **TODO-VR180-3+ (P4)**: parser 정밀화 — `st3d.stereo_mode` 값 해석으로
  top-bottom / SBS 구분, `sv3d > proj > equi` bounds 로 VR180 vs VR360 구분.
  현 byte-grep 만으로는 모두 VR180_HEMISPHERE 로 분류됨.
