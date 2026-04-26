# QA Report — 삿치 (com.example.minseo2)
**Date:** 2026-04-10
**Branch:** main (commit 890f88a)
**Device:** R3CT70FY0ZP (ADB)
**Build:** debug APK — BUILD SUCCESSFUL
**Install:** Success
**Mode:** Diff-aware (NAS integration + credential UI + eng-review fixes)

---

## Summary

| Category | Score | Issues |
|----------|-------|--------|
| Launch / Crash | ✅ 100 | 0 |
| NAS credential load | ✅ 100 | 0 |
| Code-level bugs | ⚠️ 70 | 2 medium |
| NAS UI flow | ⬜ manual | requires human tester |
| Local playback | ⬜ manual | requires human tester |

**Overall automated health score: 90/100**

---

## Automated Checks (ADB + Logcat)

### Launch
```
adb shell am start -n com.example.minseo2/.FileListActivity
→ Started successfully, no crash
```

### Logcat (first 5s after launch)
```
I NAS: NAS 인증 정보 적용 완료 (baseUrl=https://gomji17.tw3.quickconnect.to)
```
- No `AndroidRuntime` FATAL errors
- No `E/` errors in first 5 seconds
- NasCredentialStore loaded DsFileConfig fallback credentials correctly (no user-set creds → using DsFileConfig.BASE_URL)

---

## Code Issues Found (static analysis this session)

### ISSUE-001 — MEDIUM — `NasCredentialStore.hasCredentials()` returns true with hardcoded DsFileConfig values

**Description:**
`hasCredentials()` returns `!getBaseUrl().isEmpty() && !getUser().isEmpty() && !getPass().isEmpty()`.
If `DsFileConfig.USER` and `DsFileConfig.PASS` are non-empty (hardcoded), this returns `true` even when
the user has never gone through NasSetupActivity. This means the "first launch → show NAS setup" flow
in `showNasTab()` will NOT trigger for this developer's build (since DsFileConfig has real credentials).

**Impact:** Works correctly for developer (DsFileConfig creds are real). Would fail for a clean deploy
with empty DsFileConfig template — but that case is the `DsFileConfig.template.java` placeholder,
and users who deploy from template would get empty credentials and the setup screen would trigger.
**Net: acceptable for current use case. No fix needed.**

**Fix Status:** Deferred (by design — DsFileConfig is the developer's own NAS)

---

### ISSUE-002 — MEDIUM — NasSetupActivity `btnSave` stays disabled if user edits only basePath/posDir

**Description:**
`btnSave` is enabled only after a successful `runConnectionTest()`. If the user opens the setup screen
in edit mode and only changes basePath/posDir (not URL/user/pass), they must re-run the connection test
to save. This is unnecessary since the NAS is already verified.

**Impact:** Minor friction. User has to re-test the connection just to change a path.

**Repro:**
1. Open NAS tab → gear icon → NasSetupActivity (edit mode)
2. Change only the basePath field
3. Try to tap Save → disabled, must tap "연결 테스트" first

**Fix:** In edit mode, if credentials are already saved (`editMode && credStore.hasCredentials()`), pre-enable the Save button.

---

## Manual QA Checklist

These require human testing on device. Please verify each item and note any issues:

### A. Local tab (로컬)
- [ ] Folder list loads correctly
- [ ] Tapping a folder shows video list sorted by episode number
- [ ] Tapping a video opens player
- [ ] Back button from video list returns to folder list
- [ ] Back button from folder list returns to "로컬" tab root

### B. NAS tab — first-use flow
- [ ] If NasCredentialStore has credentials → NAS tab directly connects (no setup screen)
- [ ] Gear icon (⚙️) in header → opens NasSetupActivity
- [ ] In NasSetupActivity: basePath and posDir fields show DsFileConfig defaults as hint text
- [ ] Leaving basePath/posDir empty → saves; DsFileConfig defaults used on connect
- [ ] Filling basePath with custom value → saved; used on next connect
- [ ] "연결 테스트" button → connects, shows "✓ 연결 성공!" in green
- [ ] After success, "저장" button becomes enabled
- [ ] Tap "저장" → returns to FileListActivity, NAS connects automatically

### C. NAS browsing
- [ ] Folder list loads after login
- [ ] Navigating into subfolder works
- [ ] Back button returns to parent folder
- [ ] Back from root NAS folder → switches to local tab
- [ ] Screen rotation while on NAS tab → reconnects (accepts going back to root)

### D. NAS playback + position sync
- [ ] Tapping a NAS video file → plays correctly
- [ ] Pause at position X → exit to FileListActivity → reopen same file → resumes from X (Room DB)
- [ ] Seek to position, wait ~5s (auto-save) → force-close app → reopen → should resume from last saved position

### E. Player features (regression check)
- [ ] Pinch-to-zoom works
- [ ] Screen mode toggle (가로채움) works
- [ ] Korean audio track auto-selected if present
- [ ] Subtitle display works

---

## Issues Fixed This Session (pre-QA, eng-review)

| ID | Severity | Description | Commit |
|----|----------|-------------|--------|
| ENG-001 | P0 | connectNas/loadNasFolder missing lifecycle guards | 442bf1c |
| ENG-002 | P1 | reLoginAndRetryListFolder infinite loop potential | 442bf1c |
| ENG-003 | P1 | reLoginAndRetryListFolder duplicated reLoginSync() logic | 442bf1c |

---

## Fix: ISSUE-002 (btnSave pre-enabled in edit mode)

Recommended fix for `NasSetupActivity.java`:

```java
// In onCreate(), after setting up fields:
boolean editMode = getIntent().getBooleanExtra(EXTRA_EDIT_MODE, false);
if (editMode && credStore.hasCredentials()) {
    // ... populate fields ...
    btnSave.setEnabled(true);   // ← add this
    btnSave.setAlpha(1.0f);     // ← add this
}
```

---

## Deferred Issues (TODOS.md)

See `TODOS.md` for:
- P2: Video quality tuning (--android-display-chroma RV16→RV32)
- P2: NAS subtitle auto-load (srt/smi)
- P3: Null-guard for currentDbKey in saveCurrentPosition

---

## QA Summary

**PR Summary:** QA found 2 medium issues; 1 is by-design (no fix), 1 is minor UX friction (btnSave in edit mode). 3 eng-review bugs fixed pre-QA. Manual device testing checklist above covers all NAS integration paths.

**Health score: 90/100**
