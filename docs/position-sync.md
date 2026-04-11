# 재생 위치 동기화 설계

## 1. NAS / Local 업데이트 기준

### 저장 계층

재생 위치는 두 계층에 독립적으로 저장된다.

| 계층 | 저장소 | 키 | 저장 시점 |
|------|--------|-----|-----------|
| Local | Room DB (`playback.db`) | URI (content:// 또는 NAS canonical URL) | 재생 중 5초마다, onPause 시 |
| NAS | `{cfgPosDir}/{user}_positions.json` | 파일명 (확장자 포함, 폴더 없음) | 인메모리 캐시 경유 → 30초마다 or onPause 시 플러시 |

### syncKey 설계

NAS 저장 키는 **파일명만** 사용한다 (폴더명 제외).

```
예: "Mr_ Plankton_S01E02.mkv"   (O)
예: "11월최신드라마/Mr_ Plankton_S01E02.mkv"  (X)
```

**이유**: 단말 A / B가 같은 파일을 서로 다른 폴더에 가지고 있을 수 있다. 폴더명을 포함하면 cross-device 매칭이 불가능하다.

### NAS 저장 항목 구조

```json
{
  "Mr_ Plankton_S01E02.mkv": {
    "positionMs": 1167875,
    "audioTrackId": 1,
    "subtitleTrackId": -1,
    "screenMode": 0,
    "updatedAt": 1744371961000,
    "deviceId": "R3CT70FY0ZP",
    "nasPath": "/video/드라마/Mr_ Plankton_S01E02.mkv"
  }
}
```

- `updatedAt`: 비교 기준. 두 항목이 충돌하면 더 큰 값이 이긴다.
- `deviceId`: 저장한 단말의 `ANDROID_ID`. cross-device 판단에 사용.
- `nasPath`: 선택 필드. NAS 파일이면 저장, 로컬 파일이면 없음.

### NAS 플러시 흐름

onPause 시 덮어쓰기를 방지하기 위해 download → merge → upload 순서로 처리한다.

```
[onPause]
  └─ dbExecutor: flushToNasBlocking()
       1. dirty 아니면 skip
       2. downloadUserPositionsSync()  ← 현재 NAS 파일 가져오기
       3. mergePositions(remote, localSnapshot, positionsCache)  ← updatedAt 기준 병합
       4. uploadUserPositionsSync(merged)
```

onStop에서 최대 4초 대기(`nasFlushing.get(4000ms)`)하여 앱 종료 전 플러시가 완료되도록 보장한다.

### 병합 규칙

같은 키에 대해 두 항목이 존재할 경우 `updatedAt`이 더 큰 항목을 채택한다. 서로 다른 키는 모두 보존된다 (다른 단말의 다른 영상 이력 유지).

---

## 2. Startup 시 재생 판단 기준

앱 시작 시 `FileListActivity.checkResumeOnLaunch()`가 한 번만 실행된다 (`hasCheckedResumeThisSession` 플래그로 중복 방지).

### 판단 흐름

```
checkResumeOnLaunch()
  ├─ Room DB: 마지막 재생 이력 (localLast) 조회
  ├─ NAS 로그인 확인 (캐시 SID or 재로그인)
  └─ fetchNasAndShowResume(localLast, sid)
       └─ NAS positions.json 다운로드 → 가장 최신 항목 (nasEntry) 추출
```

### 분기 조건

| 조건 | 동작 |
|------|------|
| `nasTime > localTime` + **다른 단말** | 로컬에서 같은 파일명 검색 → cross-device 다이얼로그 |
| `nasTime > localTime` + **같은 단말** | 동일하게 처리 (앱 재설치로 Room DB 초기화된 경우 등) |
| `nasTime <= localTime` + `localLast` 있음 | 기존 이어보기 다이얼로그 (로컬 or NAS 스트림) |
| 둘 다 없음 | 다이얼로그 없이 파일 목록 표시 |

- `nasTime` = `nasEntry.updatedAt` (NAS의 가장 최근 항목 타임스탬프)
- `localTime` = `localLast.updatedAt` (Room DB의 마지막 저장 타임스탬프)

### Cross-device 다이얼로그

다른 단말의 NAS 항목이 최신일 때:

1. MediaStore에서 `DISPLAY_NAME = 파일명`으로 로컬 파일 검색
2. **로컬에 있으면** → "다른 단말에서 재생 중이던 영상입니다." 다이얼로그 표시
3. **로컬에 없으면** → `localLast`가 있으면 기존 이어보기로 폴백, 없으면 리스트

사용자가 "예" 선택 시 `playVideo(localUri, fileName, nasEntry.positionMs)`로 NAS 위치를 Intent extra로 전달한다.

### 플레이어에서 위치 복원 (`NasSyncManager.loadPosition`)

플레이어가 시작되면 두 단계로 위치를 복원한다.

```
loadPosition(syncKey, roomDbKey, callback)
  1. Room DB 조회 → 즉시 콜백 (빠른 시작)
  2. NAS 캐시 비교
     - 캐시 비어있음 → downloadUserPositionsSync()로 동기 로드 후 비교
     - NAS updatedAt > DB updatedAt → 두 번째 콜백으로 NAS 위치 적용
```

콜백은 최대 2회 호출될 수 있다. 두 번째 콜백이 더 최신 위치를 가져오면 플레이어가 해당 위치로 seek한다.

### 같은 단말 vs 다른 단말 복원 범위

| 항목 | 같은 단말 | 다른 단말 |
|------|----------|----------|
| 재생 위치 (`positionMs`) | 복원 | 복원 |
| 오디오 트랙 (`audioTrackId`) | 복원 | **무시** |
| 자막 트랙 (`subtitleTrackId`) | 복원 | **무시** |
| 화면 모드 (`screenMode`) | 복원 | **무시** |

다른 단말은 화면 크기, 자막 취향이 다를 수 있으므로 위치만 이어받는다.
