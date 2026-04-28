# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minseo21 (app name: "삿치") is an Android video player app built with Java and libVLC. It provides a file browser for on-device videos and a full-featured player supporting local files and network streams (HTTP/HTTPS/HLS).

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK (output: app/build/outputs/apk/debug/Minseo21.apk)
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on connected device
./gradlew clean                  # Clean build outputs
```

- Single module project (`app`), no multi-module setup
- Min SDK 24, Target/Compile SDK 36, Java 11
- Version catalog at `gradle/libs.versions.toml`

## Architecture

**Two-activity design:**

1. **FileListActivity** (launcher) — Browses device video folders using `MediaStore`, groups by bucket. Sorts videos by episode number (regex `[Ee](\d+)`). On cold start, checks if the previous session ended on the player screen and offers to resume.

2. **MainActivity** — VLC-based video player with:
   - Pinch-to-zoom, rotation lock, screen mode (가로채움/세로채움) persistence
   - Gesture controls for showing/hiding overlays
   - Periodic position saving (every 5s) to Room DB
   - Subtitle/audio track selection and persistence
   - Playlist navigation (prev/next) via `PlaylistHolder`

**Data flow between activities:**
- `PlaylistHolder` — static holder passing the video list and current index from FileListActivity to MainActivity
- `PlaybackDatabase` (Room, `playback.db`) — persists per-video playback position, subtitle/audio track IDs, and screen mode. Schema is at version 2.
- `SharedPreferences` (`player_prefs`) — stores `last_app_state` (list vs player) for resume-on-launch, and `screen_mode` preference

**Room DB migrations:**
- Schema version 2. Migration 1→2 added `subtitleTrackId`, `audioTrackId`, `screenMode` columns to `playback_position` table. Any new columns require a new migration in `PlaybackDatabase`.

**Key dependencies:**
- `org.videolan.android:libvlc-all:3.6.0` — video playback engine
- `androidx.room:room-runtime:2.6.1` — playback position persistence
- UI is Korean-language throughout

## Important Notes

- **MainActivity is ~10,700 lines.** When editing, read only the relevant section using line offsets rather than the entire file.
- **Package:** `com.example.minseo21`
- **External intent handling:** MainActivity registers as a viewer for `video/*` MIME types, HTTP/HTTPS streams, and HLS manifests. It also handles specific file extensions (.mp4, .mkv, .avi, .mov, .wmv, .ts, .m4v, .3gp, .webm, .flv, .m3u8) — see `AndroidManifest.xml`.
- **Subtitle formats:** External subtitle detection supports `.smi`, `.srt`, `.ass`, `.ssa` files alongside the video file.
- **Permissions:** `READ_EXTERNAL_STORAGE` (≤SDK 32) / `READ_MEDIA_VIDEO` (≥SDK 33), `INTERNET`. Cleartext traffic is enabled for network streaming.

## Language

The app UI and code comments are in Korean. Maintain Korean for user-facing strings and comments.


## Documentation (필수)

**코드를 수정하거나 동작을 변경했으면 무조건 `docs/` 에 작업 기록을 남긴다.** 예외 없음.

- 위치: 프로젝트 루트의 `docs/` 폴더 (소스 디렉토리 안에 만들지 않는다).
- 파일명 규칙: `<주제>-YYYY-MM-DD.md` (예: `position-timer-lifecycle-2026-04-28.md`). 같은 날 충돌 시에만 `-HHMM` 추가.
- 내용: 배경/문제 → 수정 내용 (diff 또는 hunk 단위) → trade-off → 검증 방법.
- 큰 폭 갱신은 같은 파일을 수정 (날짜 변경 X). 별개 후속 분석이면 새 파일 + 새 날짜.
- PR 생성 시 관련 docs 변경도 같은 PR 에 포함.

이는 workspace 루트 `D:\workspace\CLAUDE.md` 의 Document 정책을 프로젝트 레벨에 박아둔 것 — 이 프로젝트에서 작업할 때마다 명시적으로 적용.


## XR (Galaxy XR) 확장 — 분리 원칙

**메인 기능은 일반 안드로이드 단말 (libVLC 비디오 플레이어). XR 은 부가 확장.**
이 관계가 코드 구조에 그대로 드러나야 한다.

원칙:
- **모든 XR 코드는 `com.example.minseo21.xr` 패키지에 모은다.**
  현재 멤버: `XrConfig.kt` (단말/SBS 검출 + screen pose/shape), `XrFullSpaceLauncher.java` (Bundle launch),
  `XrSurfaceController.kt` (SurfaceEntity 양안 렌더링 + Movable/Resizable + 비율 적용).
- **`MainActivity` 는 매니페스트 무수정 (옵션 B 핵심) — Home Space + system mainPanel decoration 정상.**
  코드 변경은 protected hook 3개 (`onConfigureVlcOptions` / `attemptStereoTakeover` / `onVideoTrackInfo`) 추가 + 호출 지점 삽입만.
  모두 기본 no-op/false 라 비-XR 단말 동작 0 변경.
- **`FileListActivity` 의 XR 흔적은 `XrFullSpaceLauncher` 필드 한 개 + 라우팅 분기 한 곳 (파일명 SBS keyword 검출 시 `SbsPlayerActivity` 로 launch) 만.**
- **SBS 3D path 는 별 Activity (`SbsPlayerActivity extends MainActivity`) 로 분리한다.**
  - SbsPlayerActivity 매니페스트 entry 만 `XR_ACTIVITY_START_MODE_Full_Space_Activity`. MainActivity 는 무수정.
  - SbsPlayerActivity 가 protected hook override 로 SurfaceEntity takeover 만 처리 — 모든 재생 기능(이어보기/자막/오디오 트랙/스피드/즐겨찾기/Playlist)은 부모 inherit.
  - 자식의 hook 의존 필드 (`xr` 등) 는 `super.onCreate` _전_ 에 초기화 — 부모 onCreate 가 동기로 hook 발화하는 race 회피 (2026-04-26 logcat 진단으로 확인).
- **메인의 일반 동작은 비-XR 단말에서도 항상 정상이어야 한다.**
  XR 분기로 메인 동작 흐름을 깨거나, XR 전용 상태 변수가 일반 로직에 스며들지 않게 한다.
- **새 XR 기능을 추가할 때**: `xr/` 패키지에 클래스/메서드 추가 + 필요 시 MainActivity 의 protected hook 한 개 추가. 메인 흐름에 새 if 블록·새 필드를 만들지 않는다.

이 원칙은 사용자가 명시적으로 요구한 사항(2026-04-25, 옵션 B 디자인 2026-04-26 에서 강화). 이상은 "메인을 보면 XR 코드가 거의 안 보이는 것".


## Skill routing

When the user's request matches an available skill, ALWAYS invoke it using the Skill
tool as your FIRST action. Do NOT answer directly, do NOT use other tools first.
The skill has specialized workflows that produce better results than ad-hoc answers.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke office-hours
- Bugs, errors, "why is this broken", 500 errors → invoke investigate
- Ship, deploy, push, create PR → invoke ship
- QA, test the site, find bugs → invoke qa
- Code review, check my diff → invoke review
- Update docs after shipping → invoke document-release
- Weekly retro → invoke retro
- Design system, brand → invoke design-consultation
- Visual audit, design polish → invoke design-review
- Architecture review → invoke plan-eng-review
- Save progress, checkpoint, resume → invoke checkpoint
- Code quality, health check → invoke health
