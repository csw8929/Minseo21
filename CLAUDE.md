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


## XR (Galaxy XR) 확장 — 분리 원칙

**메인 기능은 일반 안드로이드 단말 (libVLC 비디오 플레이어). XR 은 부가 확장.**
이 관계가 코드 구조에 그대로 드러나야 한다.

원칙:
- **모든 XR 코드는 `com.example.minseo21.xr` 패키지에 모은다.**
  현재 멤버: `XrPlayerManager.kt` (low-level SurfaceEntity), `XrFullSpaceLauncher.java` (Bundle launch),
  `XrPlaybackController` (high-level lifecycle/이벤트 hook 통합).
- **`MainActivity` / `FileListActivity` 같은 메인 클래스에 XR 분기/필드/직접 호출을 직접 넣지 않는다.**
  XR 흔적은 (a) controller import 한 줄, (b) 필드 한 개, (c) 컨트롤러 메서드 호출 몇 줄로 제한한다.
- **Lifecycle / Host 콜백 패턴을 사용한다.**
  - 컨트롤러는 `DefaultLifecycleObserver` 로 onPause/onDestroy 를 받아 자체 정리한다 — 메인이 `xrManager.release()` 같은 호출을 직접 들고 있지 않게.
  - 컨트롤러가 메인의 view/handler/컨트롤 자동숨김 토글 등에 접근할 때는 좁은 `Host` 인터페이스로만 — 메인은 그 인터페이스만 구현하면 된다.
- **메인의 일반 동작은 비-XR 단말에서도 항상 정상이어야 한다.**
  XR 분기로 메인 동작 흐름을 깨거나, XR 전용 상태 변수가 일반 로직에 스며들지 않게 한다 (예: `xrSbsMode` 같은 boolean 을 메인에 두기보다, 컨트롤러가 메인에 "자동숨김 허용/금지" 같은 일반 신호로만 전달).
- **새 XR 기능을 추가할 때**: 컨트롤러에 메서드 한 개 추가 + 메인에서 그 메서드 한 줄 호출. 메인에 새 if 블록·새 필드를 만들지 않는다.

이 원칙은 사용자가 명시적으로 요구한 사항(2026-04-25). 이상은 "메인을 보면 XR 코드가 거의 안 보이는 것".


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
