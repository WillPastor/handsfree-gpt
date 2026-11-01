#!/usr/bin/env bash
# End-to-end test: drives HandsFree GPT against the mock ChatGPT (./mock-chatgpt) on an emulator
# and checks in logcat what it pressed. Needs one running emulator; takes about three minutes.
#
#   scripts/e2e.sh                      # build, install, run every scenario
#   ADB=/path/to/adb scripts/e2e.sh     # if adb isn't on PATH
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="com.handsfreegpt/com.handsfreegpt.HandsFreeService"
TILE="com.handsfreegpt/.HandsFreeTileService"
LOG="$(mktemp)"
passed=0
failed=0
skipped=0

if [[ -z "${ADB:-}" ]]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [[ -n "${ANDROID_HOME:-}" ]]; then ADB="$ANDROID_HOME/platform-tools/adb"
  elif [[ -n "${LOCALAPPDATA:-}" ]]; then ADB="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk/platform-tools/adb.exe"
  else ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  fi
fi

adb_() { "$ADB" "$@"; }
shell() { "$ADB" shell "$@" | tr -d '\r'; }

step() { printf '\n> %s\n' "$*"; }
ok() { echo "  ok    $1"; passed=$((passed + 1)); }
bad() { echo "  FAIL  $1"; failed=$((failed + 1)); }
expect() { if grep -qE -- "$2" "$LOG"; then ok "$1"; else bad "$1 (no log line matching /$2/)"; fi; }
expect_not() { if grep -qE -- "$2" "$LOG"; then bad "$1 (unexpected /$2/)"; else ok "$1"; fi; }
check() { if "${@:2}"; then ok "$1"; else bad "$1"; fi; }
count() { grep -cE -- "$1" "$LOG" || true; }
line_of() { grep -nE -- "$1" "$LOG" | head -n1 | cut -d: -f1; }
in_order() { local a b; a="$(line_of "$1")"; b="$(line_of "$2")"; [[ -n $a && -n $b && $a -lt $b ]]; }

begin() { adb_ logcat -c; }
collect() { adb_ logcat -d -s HandsFreeGPT:V MockGPT:V AndroidRuntime:E | tr -d '\r' >"$LOG"; }
skip() { echo "  skip  $1"; skipped=$((skipped + 1)); }
handsfree() { shell am start -a "com.handsfreegpt.action.$1" -n com.handsfreegpt/.MainActivity >/dev/null; }
mock() { # (re)start the mock ChatGPT with known (false) or unknown (true) button labels, then go home.
  # A second argument of true makes its send arrow do nothing; a third sets what dictations transcribe to.
  shell am force-stop com.openai.chatgpt
  shell am start -n com.openai.chatgpt/.MainActivity --ez odd "$1" --ez ignore_send "${2:-false}" \
    --es transcript "'${3:-Hello from dictation. Send it.}'" >/dev/null
  sleep 2
  shell input keyevent KEYCODE_HOME
  sleep 1
}
overlay_windows() { shell dumpsys window windows | grep -c 'handsfreegpt' || true; }

if [[ "$(shell getprop ro.boot.qemu)" != "1" && "$(shell getprop ro.kernel.qemu)" != "1" ]]; then
  echo "Refusing to run: this installs a fake com.openai.chatgpt, so use an emulator, not a phone." >&2
  exit 2
fi

# The status pill sits at the top-right, 30% of the way down the screen.
read -r W H <<<"$(shell wm size | tail -n1 | sed -E 's/.*: *([0-9]+)x([0-9]+).*/\1 \2/')"
PILL_X=$((W - 80))
PILL_Y=$((H * 30 / 100 + 45))
tap_pill() { shell input tap "$PILL_X" "$PILL_Y"; }
hold_pill() { shell input swipe "$PILL_X" "$PILL_Y" "$PILL_X" "$PILL_Y" 1200; }

step "Build and install the app and the mock ChatGPT"
(cd "$ROOT" && ./gradlew -q :app:assembleDebug :mock-chatgpt:assembleDebug)
adb_ uninstall com.handsfreegpt >/dev/null 2>&1 || true # start from default settings
adb_ install -r -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb_ install -r -g "$ROOT/mock-chatgpt/build/outputs/apk/debug/mock-chatgpt-debug.apk" >/dev/null
# Enable the accessibility service, keeping any others. Android drops a reinstalled app's service from the
# list asynchronously, which can undo our first attempt, so retry until it is actually bound.
bound=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if shell dumpsys accessibility | grep -q 'label=HandsFree GPT'; then bound=true; break; fi
  enabled="$(shell settings get secure enabled_accessibility_services)"
  if [[ "$enabled" != *"$SERVICE"* ]]; then
    if [[ -z "$enabled" || "$enabled" == "null" ]]; then enabled="$SERVICE"; else enabled="$enabled:$SERVICE"; fi
    shell settings put secure enabled_accessibility_services "$enabled"
  fi
  shell settings put secure accessibility_enabled 1
  sleep 2
done
if [[ $bound != true ]]; then
  echo "Couldn't enable the HandsFree GPT accessibility service." >&2
  exit 1
fi
adb_ logcat -b crash -c

step "1. Start: presses the dictation mic, hears nothing, cancels"
mock false
begin
handsfree START
sleep 17
collect
expect "opens ChatGPT and presses the dictation mic" 'MockGPT.*TAP mic'
expect "sees the dictation bar and starts listening" 'LISTENING'
expect "ChatGPT keeps the microphone while we listen too" 'silenced=false'
expect "cancels (X) after 12 s without speech" 'MockGPT.*TAP cancel'
expect_not "never presses the voice-mode button" 'VOICE MODE'

step "2. Tap the pill to talk, tap again to finish: presses the stop button, keeps the text"
begin
tap_pill
sleep 4
tap_pill
sleep 2
collect
expect "a tap starts dictation" 'MockGPT.*TAP mic'
expect "a second tap presses the stop button" 'MockGPT.*TAP stop'
expect "pauses, ready for the next turn" 'READY'

step "3. Unknown button labels: finds the mic and the stop button by position"
handsfree STOP
mock true
begin
handsfree START
sleep 5
tap_pill
sleep 9
collect
expect "presses the unlabeled mic (left of voice mode)" 'clicked "speak"'
expect "presses the unlabeled stop button (left of send)" 'MockGPT.*TAP stop'
check "doesn't mistake the filled-in composer for the dictation bar" test "$(count 'MockGPT.*TAP mic')" -eq 1

step "4. Hold the pill: hands-free turns off"
hold_pill
sleep 1
check "the pill is gone" test "$(overlay_windows)" -eq 0

step "5. Quick Settings tile toggles hands-free"
mock false
shell cmd statusbar add-tile "$TILE" >/dev/null 2>&1 || true
begin
shell cmd statusbar click-tile "$TILE"
sleep 4
shell cmd statusbar click-tile "$TILE"
sleep 2
collect
expect "the tile starts listening" 'MockGPT.*TAP mic'
expect "turning off mid-dictation presses stop instead of leaving ChatGPT recording" 'MockGPT.*TAP stop'
check "the pill is gone" test "$(overlay_windows)" -eq 0

step "6. Steering-wheel buttons: next sends, play/pause talks again, previous cancels"
mock false
begin
handsfree START
sleep 5
shell input keyevent KEYCODE_MEDIA_NEXT
sleep 3
shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
sleep 6
shell input keyevent KEYCODE_MEDIA_PREVIOUS
sleep 2
collect
expect "next presses send" 'MockGPT.*TAP send'
check "play/pause starts the next dictation" test "$(count 'MockGPT.*TAP mic')" -eq 2
expect "previous cancels it" 'MockGPT.*TAP cancel'
handsfree STOP

step "7. Voice command: \"…send it\" sends the message, without the command"
mock false
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
sleep 18
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition ($(grep -o 'unavailable: .*' "$LOG" | head -n1))"
else
  expect "hears the spoken command" 'voice command SEND'
  expect "presses stop so the command can be removed" 'MockGPT.*TAP stop'
  check "waits for the transcript before removing the command (#1)" in_order 'MockGPT.*TRANSCRIPT READY' 'set composer text'
  expect "sends the message without \"Send it\"" 'SENT: Hello from dictation\.$'
  check "says Sent only once ChatGPT has taken the message (#1)" in_order 'MockGPT.*SENT:' 'READY: .*Sent'
fi
handsfree STOP

step "8. ChatGPT doesn't take the message: presses send once more, then says so rather than \"Sent\""
mock false true
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
sleep 22
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition"
else
  check "presses send, then once more" test "$(count 'MockGPT.*TAP send \(ignored\)')" -eq 2
  expect "says it couldn't send" "Couldn't send"
  expect_not "never claims it was sent" 'READY: .*Sent'
fi
handsfree STOP

step "9. A message that itself ends in \"send it\": only the spoken command is removed"
mock false false 'Repeat the words send it. Send it.'
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
sleep 18
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition"
else
  expect "keeps the message's own \"send it\"" 'SENT: Repeat the words send it\.$'
  expect_not "doesn't remove the command a second time" 'SENT: Repeat the words$'
fi
handsfree STOP

step "Stability"
check "no crashes" test "$(adb_ logcat -d -b crash | grep -c 'com.handsfreegpt' || true)" -eq 0

rm -f "$LOG"
printf '\n%d passed, %d failed, %d skipped\n' "$passed" "$failed" "$skipped"
[[ $failed -eq 0 ]]
