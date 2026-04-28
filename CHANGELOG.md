# Changelog

All notable changes to this project will be documented in this file.

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
