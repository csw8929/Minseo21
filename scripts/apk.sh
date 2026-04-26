#!/usr/bin/env bash
#
# Minseo21 APK 매니저 — 연결된 adb 디바이스에 APK 설치 / 제거 / logcat.
# Git Bash (Windows) 또는 Linux / macOS 어디에서든 실행 가능.
#
# 사용법:
#   bash scripts/apk.sh
#
# 변수:
#   APK_FILE  — 설치할 APK 경로 (절대 또는 스크립트 기준 상대). 기본값은
#               ./app/build/outputs/apk/debug/Minseo21.apk
#   PACKAGE   — 설치/제거 대상 패키지.
#
set -u

# ── 설정 ────────────────────────────────────────────────────────────────────
APK_FILE="${APK_FILE:-app/build/outputs/apk/debug/Minseo21.apk}"
PACKAGE="${PACKAGE:-com.example.minseo21}"

# 알려진 디바이스 시리얼 → 사람 이름. 목록에 없는 기기는 getprop 모델명으로 폴백.
declare -A DEVICE_NAMES=(
  [R54Y1003KXN]="탭"
  [R3CT70FY0ZP]="폴드"
  [R3CX705W62D]="플립"
  [T813128GB25301890106]="미니"
  [R34YA0007ZJ]="xr"
)

# 스크립트 위치에서 프로젝트 루트로 이동 — APK_FILE 이 상대 경로여도 작동.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 디바이스 조회 ───────────────────────────────────────────────────────────

# 연결된 디바이스 시리얼만 stdout 으로 한 줄에 하나씩.
list_devices() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'
}

# 시리얼을 "시리얼 (이름)" 형태로 표시. 이름이 없으면 시리얼만.
display_name() {
  local serial="$1"
  local name="${DEVICE_NAMES[$serial]:-}"
  if [ -n "$name" ]; then
    echo "$serial ($name)"
    return
  fi
  # 매핑에 없으면 기기 모델명 조회 (2초 타임아웃 — 무반응 방지).
  local model
  model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
  if [ -n "$model" ]; then
    echo "$serial ($model)"
  else
    echo "$serial"
  fi
}

# ── 메뉴 핸들러 ─────────────────────────────────────────────────────────────

action_list_devices() {
  echo ""
  echo "== Connected devices =="
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo "  (연결된 디바이스 없음)"
    return
  fi
  for s in "${devices[@]}"; do
    echo "  - $(display_name "$s")"
  done
}

install_on_device() {
  local serial="$1"
  if [ ! -f "$APK_FILE" ]; then
    echo "  ✗ APK 없음: $APK_FILE"
    echo "    ./gradlew assembleDebug 먼저 실행하세요."
    return 1
  fi
  echo "  → $(display_name "$serial") 에 설치 중..."
  adb -s "$serial" install -r "$APK_FILE"
}

uninstall_on_device() {
  local serial="$1"
  echo "  → $(display_name "$serial") 에서 $PACKAGE 제거 중..."
  adb -s "$serial" uninstall "$PACKAGE"
}

copy_to_clipboard() {
  if command -v clip.exe &>/dev/null; then
    clip.exe
  elif command -v clip &>/dev/null; then
    clip
  elif command -v pbcopy &>/dev/null; then
    pbcopy
  elif command -v xclip &>/dev/null; then
    xclip -selection clipboard
  elif command -v xsel &>/dev/null; then
    xsel --clipboard --input
  else
    echo "  (클립보드 복사 불가 — clip/pbcopy/xclip 없음)"
    return 1
  fi
}

logcat_on_device() {
  local serial="$1"
  read -rp "  grep pattern (빈 값=취소)> " pattern
  if [ -z "$pattern" ]; then
    echo "  취소됨."
    return
  fi
  echo "  → $(display_name "$serial") logcat -c (버퍼 초기화)..."
  adb -s "$serial" logcat -c
  clear
  echo "  → logcat | grep '$pattern'  — Ctrl+C 로 중단"
  echo ""
  local tmpfile
  tmpfile=$(mktemp)
  # 파이프라인 중단 (Ctrl+C) 시 스크립트 탈출 방지 — 메뉴로 복귀.
  trap ':' INT
  adb -s "$serial" logcat | grep --line-buffered -- "$pattern" | tee "$tmpfile" || true
  trap - INT
  local lines
  lines=$(wc -l < "$tmpfile")
  copy_to_clipboard < "$tmpfile"
  echo ""
  echo "  ✓ ${lines}줄 클립보드에 복사됨"
  rm -f "$tmpfile"
}

# 디바이스 선택 서브메뉴. callback 함수 이름을 받아 선택된 시리얼로 호출.
# 세 번째 인자 "all" 이면 마지막에 "a) 전체" 추가 — 모든 디바이스에 callback 반복.
pick_device_and_run() {
  local title="$1"
  local callback="$2"
  local include_all="${3:-}"
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo ""
    echo "  (연결된 디바이스 없음)"
    return
  fi
  # 단말이 하나면 선택 없이 바로 실행.
  if [ "${#devices[@]}" -eq 1 ]; then
    echo ""
    "$callback" "${devices[0]}"
    return
  fi
  echo ""
  echo "== $title =="
  local i=1
  for s in "${devices[@]}"; do
    echo "  $i) $(display_name "$s")"
    i=$((i+1))
  done
  if [ "$include_all" = "all" ]; then
    echo "  a) 전체"
  fi
  echo "  0) 취소"
  echo ""
  read -rsn1 -p "선택> " choice
  echo "$choice"
  if [ -z "$choice" ] || [ "$choice" = "0" ]; then
    return
  fi
  if [ "$include_all" = "all" ] && [[ "$choice" =~ ^[aA]$ ]]; then
    echo ""
    for s in "${devices[@]}"; do
      "$callback" "$s"
    done
    return
  fi
  if ! [[ "$choice" =~ ^[0-9]+$ ]]; then
    echo "  잘못된 입력."
    return
  fi
  local idx=$((choice-1))
  if [ "$idx" -lt 0 ] || [ "$idx" -ge "${#devices[@]}" ]; then
    echo "  범위 밖 번호."
    return
  fi
  echo ""
  "$callback" "${devices[$idx]}"
}

action_install()   { pick_device_and_run "install — 디바이스 선택"   install_on_device   all; }
action_uninstall() { pick_device_and_run "uninstall — 디바이스 선택" uninstall_on_device all; }
action_logcat()    { pick_device_and_run "logcat grep — 디바이스 선택" logcat_on_device; }

action_build() {
  echo ""
  echo "== build — ./gradlew assembleDebug =="
  ./gradlew assembleDebug
}

# ── 메인 루프 ───────────────────────────────────────────────────────────────

main_menu() {
  while true; do
    echo ""
    echo "┌────────────────────────────────────────────────────┐"
    echo "│  Minseo21 APK Manager                              │"
    echo "│  APK:     $APK_FILE"
    echo "│  Package: $PACKAGE"
    echo "├────────────────────────────────────────────────────┤"
    echo "│  1) devices             — 연결된 디바이스 목록"
    echo "│  2) install             — 디바이스 선택 후 설치 (a=전체)"
    echo "│  3) uninstall           — 디바이스 선택 후 제거 (a=전체)"
    echo "│  8) logcat grep         — 디바이스 선택 + 패턴으로 grep"
    echo "│  9) build               — ./gradlew assembleDebug"
    echo "│  0) exit"
    echo "└────────────────────────────────────────────────────┘"
    read -rsn1 -p "> " choice
    echo "$choice"
    case "$choice" in
      1) action_list_devices ;;
      2) action_install ;;
      3) action_uninstall ;;
      8) action_logcat ;;
      9) action_build ;;
      0|q) echo "bye"; exit 0 ;;
      "") ;;  # 빈 입력(Enter) 은 조용히 넘김
      *) echo "  잘못된 선택: $choice" ;;
    esac
  done
}

main_menu
