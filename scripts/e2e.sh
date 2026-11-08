#!/usr/bin/env bash
# End-to-end test: drives HandsFree GPT against the mock ChatGPT (./mock-chatgpt) on an emulator
# and checks in logcat what it pressed. Needs one running emulator; takes about eight minutes.
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
last_line_of() { grep -nE -- "$1" "$LOG" | tail -n1 | cut -d: -f1; }
in_order() { local a b; a="$(line_of "$1")"; b="$(line_of "$2")"; [[ -n $a && -n $b && $a -lt $b ]]; }
after() { local a b; a="$(last_line_of "$1")"; b="$(line_of "$2")"; [[ -n $a && -n $b && $a -gt $b ]]; } # last $1 after first $2

begin() { adb_ logcat -c; }
collect() { adb_ logcat -d -s HandsFreeGPT:V MockGPT:V AndroidRuntime:E | tr -d '\r' >"$LOG"; }
skip() { echo "  skip  $1"; skipped=$((skipped + 1)); }
handsfree() { shell am start -a "com.handsfreegpt.action.$1" -n com.handsfreegpt/.MainActivity >/dev/null; }
app_settings() { adb_ shell am broadcast -n com.handsfreegpt/.DebugPrefsReceiver "$@" >/dev/null; } # --ez key bool
wait_for() { # until a log line matches $1, for at most $2 seconds
  local i
  for ((i = 0; i < $2; i++)); do
    adb_ logcat -d -s HandsFreeGPT:V MockGPT:V | grep -qE -- "$1" && return 0
    sleep 1
  done
  return 1
}
SAID='Hello from dictation. Send it.' # what the mock's dictations transcribe to, unless a scenario says otherwise
mock() { # (re)start the mock ChatGPT with known (false) or unknown (true) button labels, then go home.
  # A second argument of true makes its send arrow do nothing; a third sets what dictations transcribe to
  # ('' for nothing); a fourth, how long Read aloud takes to start speaking, in ms; a fifth of true starts it
  # with a reply already in the chat; a sixth of true makes it transcribe at once and reply in about 0.4 s; and a
  # seventh of true makes its replies taller than the screen.
  shell am force-stop com.openai.chatgpt
  shell am start -n com.openai.chatgpt/.MainActivity --ez odd "$1" --ez ignore_send "${2:-false}" \
    --es transcript "'${3-$SAID}'" --ei read_delay "${4:-800}" --ez replied "${5:-false}" \
    --ez quick "${6:-false}" --ez long "${7:-false}" >/dev/null
  sleep 2
  shell input keyevent KEYCODE_HOME
  sleep 1
}
overlay_windows() { shell dumpsys window windows | grep -c 'handsfreegpt' || true; }
user_tap() { # tap the mock as a person would: its dictation bar's cancel (X), stop (■) or send (↑), or its audio
  # bar's toggle (play/pause), rewind (to the start) or close (X); or scroll_top, to scroll its chat up to the top
  adb_ shell am broadcast -a com.openai.chatgpt.USER_TAP -p com.openai.chatgpt --es target "$1" >/dev/null
}
no_voice() { grep -q 'voice commands unavailable' "$LOG"; }
capture_now() { # capture ChatGPT's screen, as the app's Capture button does, and print the capture; with an
  # argument of labels, short names inside things you can tap are shown too, as when mapping ChatGPT by hand
  adb_ shell am broadcast -n com.handsfreegpt/.DebugCaptureReceiver ${1:+--ez labels true} >/dev/null
  sleep 1
  shell run-as com.handsfreegpt cat files/last_capture.txt
}
has() { grep -qE -- "$1"; }    # stdin matches
lacks() { ! grep -qE -- "$1"; } # stdin doesn't match

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
sleep 4 # ChatGPT takes 2 s to transcribe and send it, and only then does it count as sent
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

step "10. Read aloud: once the reply is done, presses Read aloud, then listens again when it has finished"
mock false
app_settings --ez read_aloud true --ez continuous true
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READ ALOUD DONE' 45 || true
sleep 5
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition"
else
  expect "presses Read aloud" 'MockGPT.*TAP read aloud'
  check "only once the reply has finished" in_order 'MockGPT.*REPLY DONE' 'MockGPT.*TAP read aloud'
  check "then listens again" test "$(count 'MockGPT.*TAP mic')" -eq 2
  check "but only after the reading has ended" after 'MockGPT.*TAP mic' 'MockGPT.*READ ALOUD DONE'
  CAPTURE="$(capture_now)"
  check "a capture shows the ended reply's audio bar: play, the X, the seek bar at its end" \
    has 'resume="play audio".* close="".* position=1\.000$' <<<"$CAPTURE"
  check "captures leave out chat text" lacks 'Stop reading here|word[0-9]' <<<"$CAPTURE"
fi
handsfree STOP

step "11. Next ⏭ while a reply is being read: stops the reading and starts talking"
mock false
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READING ALOUD' 45 || true
shell input keyevent KEYCODE_MEDIA_NEXT
sleep 4
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition"
else
  expect "stops the reading with the audio bar's X" 'MockGPT.*TAP close audio'
  check "and starts talking" after 'MockGPT.*TAP mic' 'MockGPT.*TAP close audio'
  expect_not "without waiting for the reading to end" 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP

step "12. Next ⏭ while ChatGPT is still fetching the speech: stops the reading once it starts, then talks"
mock false false "$SAID" 4000
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*TAP read aloud' 45 || true
shell input keyevent KEYCODE_MEDIA_NEXT
sleep 10 # the speech starts 4 s after the press and would end 6 s later
collect
if grep -q 'voice commands unavailable' "$LOG"; then
  skip "this emulator has no on-device speech recognition"
else
  check "⏭ came before the speech started" in_order 'media button: send' 'MockGPT.*READING ALOUD'
  check "stops the reading once it starts" in_order 'MockGPT.*READING ALOUD' 'MockGPT.*TAP close audio'
  check "only then starts talking" after 'MockGPT.*TAP mic' 'MockGPT.*TAP close audio'
  expect_not "doesn't let the reply play on" 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP

step "13. Pausing a reply in ChatGPT: waits rather than taking it as the end, even back at the start, then listens once it has played on"
mock false
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READING ALOUD' 45 || true
sleep 2
user_tap toggle # pause it
sleep 3
CAPTURE="$(capture_now)"
user_tap rewind # drag it back to the start
sleep 3         # in all, far longer than the 2 s of quiet that end a reply
AT_START="$(capture_now)"
user_tap toggle # and play it on
wait_for 'MockGPT.*READ ALOUD DONE' 15 || true
sleep 5
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  check "a capture shows it paused partway" has '\(paused partway\)' <<<"$CAPTURE"
  check "and still paused once dragged back to the start" has 'position=0\.000 \(paused partway\)' <<<"$AT_START"
  expect "shows the reply as paused" 'Reply paused'
  check "doesn't listen while it's paused" test "$(count 'MockGPT.*TAP mic')" -eq 2
  check "but does once it has played on to the end" after 'MockGPT.*TAP mic' 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP

step "14. Play/pause while a reply is read: pauses it, then plays it on; previous ⏮ then stops it and waits"
mock false
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READING ALOUD' 45 || true
sleep 1
shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
sleep 5
shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
sleep 2
shell input keyevent KEYCODE_MEDIA_PREVIOUS
sleep 4
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  expect "play/pause pauses the reply" 'MockGPT.*TAP pause audio'
  check "and plays it on when pressed again" in_order 'MockGPT.*TAP pause audio' 'MockGPT.*TAP play audio'
  check "previous ⏮ then stops it with the X" after 'MockGPT.*TAP close audio' 'MockGPT.*TAP play audio'
  check "and waits instead of listening" test "$(count 'MockGPT.*TAP mic')" -eq 1
  expect_not "the reply never plays to the end" 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP

step "15. Turning hands-free off while a reply is read pauses it, even one ChatGPT is still fetching"
mock false
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READING ALOUD' 45 || true
shell input keyevent KEYCODE_MEDIA_STOP
sleep 8
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  expect "the stop button turns hands-free off" 'media button: stop'
  expect "and pauses the reply" 'MockGPT.*TAP pause audio'
  expect_not "rather than letting it play on" 'MockGPT.*READ ALOUD DONE'
fi
mock false false "$SAID" 4000
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*TAP read aloud' 45 || true
shell input keyevent KEYCODE_MEDIA_STOP
sleep 10 # the speech starts 4 s after the press and would end 6 s later
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  check "hands-free went off before the speech started" in_order 'media button: stop' 'MockGPT.*READING ALOUD'
  check "pauses the reply once it starts" in_order 'MockGPT.*READING ALOUD' 'MockGPT.*TAP pause audio'
  expect_not "rather than letting it play on" 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP

step "16. ChatGPT's own X during a dictation counts as cancelled: nothing is waited for, or read again (#13)"
mock false false "$SAID" 800 true # a chat that already has a reply
begin
handsfree START
wait_for 'LISTENING: ' 15 || true
sleep 1
user_tap cancel # ChatGPT's X
sleep 12        # before the fix, the app waited 8 s for a reply, then read the last one again
collect
check "ChatGPT's X ends the dictation" in_order 'MockGPT.*USER taps cancel' 'MockGPT.*TAP cancel'
expect "says it was cancelled" 'READY: .*Dictation cancelled'
expect_not "doesn't wait for a reply" 'Waiting for reply'
check "or read the last reply again" test "$(count 'MockGPT.*TAP read aloud')" -eq 0
check "or listen again" test "$(count 'MockGPT.*TAP mic')" -eq 1
handsfree STOP

step "17. An empty dictation sent with ↑ sends nothing: nothing is waited for, or read again (#13)"
mock false false "" 800 true # dictations transcribe to nothing, in a chat that already has a reply
begin
handsfree START
wait_for 'LISTENING: ' 15 || true
sleep 1
tap_pill # finish without having said anything: continuous mode sends with ↑
sleep 14
collect
check "↑ sends a dictation with nothing in it" in_order 'MockGPT.*TAP send' 'MockGPT.*TRANSCRIPT EMPTY'
expect "says there was nothing to send" 'READY: .*Nothing to send'
expect_not "doesn't wait for a reply" 'Waiting for reply'
check "or read the last reply again" test "$(count 'MockGPT.*TAP read aloud')" -eq 0
check "or listen again" test "$(count 'MockGPT.*TAP mic')" -eq 1
handsfree STOP

step "18. ChatGPT's own ■ keeps the text, which next ⏭ then sends, and its own ↑ still sends: both replies are read"
mock false false "$SAID" 800 true
begin
handsfree START
wait_for 'LISTENING: ' 15 || true
sleep 1
user_tap stop # ChatGPT's ■
sleep 12
collect
check "ChatGPT's ■ ends the dictation" in_order 'MockGPT.*USER taps stop' 'MockGPT.*TRANSCRIPT READY'
expect "and the app says the text is in the box" 'READY: .*Text is in the box'
expect_not "rather than waiting for a reply" 'Waiting for reply'
check "or reading the last one again" test "$(count 'MockGPT.*TAP read aloud')" -eq 0
begin
shell input keyevent KEYCODE_MEDIA_NEXT # send what's in the box
wait_for 'MockGPT.*TAP read aloud' 20 || true
collect
expect "next ⏭ then sends it" 'MockGPT.*SENT: Hello from dictation\. Send it\.$'
check "and the reply is read once it's done" in_order 'MockGPT.*REPLY DONE' 'MockGPT.*TAP read aloud'
begin
wait_for 'LISTENING: ' 20 || true # with the reply read, continuous mode listens again
sleep 1
user_tap send # ChatGPT's ↑
wait_for 'MockGPT.*TAP read aloud' 20 || true
collect
check "ChatGPT's ↑ sends the dictation" in_order 'MockGPT.*USER taps send' 'MockGPT.*SENT: hello from dictation$'
check "which still counts as sent: the reply is read once it's done" \
  in_order 'MockGPT.*REPLY DONE' 'MockGPT.*TAP read aloud'
handsfree STOP

step "19. A reply that's over within half a second of the dictation ending still counts as sent (Codex review of #16)"
mock false false "$SAID" 800 true true # ChatGPT transcribes at once, and its replies are over in about 0.4 s
begin
handsfree START
wait_for 'LISTENING: ' 15 || true
sleep 1
user_tap send # ChatGPT's ↑: before the fix, the reply was over by the time the app first looked for one
wait_for 'MockGPT.*TAP read aloud' 20 || true
collect
check "ChatGPT's ↑ sends it at once" in_order 'MockGPT.*USER taps send' 'MockGPT.*SENT: hello from dictation$'
expect_not "which doesn't count as cancelled" 'Dictation cancelled'
check "and the reply is read" in_order 'MockGPT.*REPLY DONE' 'MockGPT.*TAP read aloud'
begin
wait_for 'LISTENING: ' 20 || true # with the reply read, continuous mode listens again
sleep 1
tap_pill # finish talking: continuous mode sends with ↑
wait_for 'MockGPT.*TAP read aloud' 20 || true
collect
check "so does the app's own ↑" in_order 'MockGPT.*TAP send' 'MockGPT.*SENT: hello from dictation$'
expect_not "which doesn't count as sending nothing" 'Nothing to send'
check "and that reply is read too" in_order 'MockGPT.*REPLY DONE' 'MockGPT.*TAP read aloud'
handsfree STOP
app_settings --ez read_aloud false --ez continuous false

step "20. Leaving ChatGPT while a reply is read: keeps waiting for it, a tap brings ChatGPT back, and it listens once the reply has ended"
mock false
app_settings --ez read_aloud true --ez continuous true
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*READING ALOUD' 45 || true
shell input keyevent KEYCODE_HOME # the reply plays on, out of sight
sleep 2
tap_pill
wait_for 'MockGPT.*READ ALOUD DONE' 10 || true
sleep 5
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  expect "keeps waiting for the reply while ChatGPT is off screen" 'READING: .*Open ChatGPT to continue'
  expect_not "rather than giving up on it" 'left the screen'
  check "a tap brings ChatGPT back, and the pill shows the reply again" \
    after 'READING: .*Reading the reply' 'READING: .*Open ChatGPT to continue'
  expect_not "without pausing or closing the reply" 'MockGPT.*TAP (pause|close) audio'
  check "listens again only once the reply has ended" after 'MockGPT.*TAP mic' 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP
app_settings --ez read_aloud false --ez continuous false

step "21. Turning hands-free off and straight back on while a reply is fetched: still pauses it once it starts, rather than letting it play over the new dictation (#18)"
mock false false "$SAID" 4000
app_settings --ez read_aloud true --ez continuous true
begin
adb_ shell am broadcast -n com.handsfreegpt/.DebugAudioReceiver --es asset send-it.pcm >/dev/null
handsfree START
wait_for 'MockGPT.*TAP read aloud' 45 || true
shell input keyevent KEYCODE_MEDIA_STOP
sleep 1
handsfree START
sleep 10 # the speech starts 4 s after the press and would end 6 s later
collect
if no_voice; then
  skip "this emulator has no on-device speech recognition"
else
  stop_at="$(line_of 'media button: stop' || true)"
  back_on="$(last_line_of 'WAIT_IDLE: .*Getting ready' || true)"
  speech_at="$(line_of 'MockGPT.*READING ALOUD' || true)"
  check "hands-free went off, then back on, before the speech started" \
    test "${stop_at:-0}" -gt 0 -a "${back_on:-0}" -gt "${stop_at:-0}" -a "${speech_at:-0}" -gt "${back_on:-0}"
  check "pauses the reply once it starts" in_order 'MockGPT.*READING ALOUD' 'MockGPT.*TAP pause audio'
  expect_not "rather than letting it play over the new dictation" 'MockGPT.*READ ALOUD DONE'
fi
handsfree STOP
app_settings --ez read_aloud false --ez continuous false

step "22. Read aloud with the chat scrolled up: scrolls back down, then reads the new reply, not an older one (#17)"
mock false false "$SAID" 800 true false true # a reply already in the chat, and replies taller than the screen
app_settings --ez read_aloud true
begin
handsfree START
wait_for 'LISTENING: ' 15 || true
sleep 1
shell input keyevent KEYCODE_MEDIA_NEXT # send it
wait_for 'MockGPT.*SENT:' 10 || true
user_tap scroll_top # back up to the earlier reply, as the new one comes in below
wait_for 'MockGPT.*TAP read aloud' 30 || true
CAPTURE="$(capture_now)"
collect
check "the chat was scrolled up as the new reply came in" in_order 'MockGPT.*USER taps scroll_top' 'MockGPT.*REPLY DONE'
check "scrolls the chat back down once the reply is done" in_order 'MockGPT.*REPLY DONE' 'scrolled the chat down'
check "then presses the new reply's Read aloud" in_order 'scrolled the chat down' 'MockGPT.*TAP read aloud \(reply 2\)'
expect_not "not the earlier one's, which was on screen" 'MockGPT.*TAP read aloud \(reply 1\)'
check "a capture shows the chat, at the bottom" has '^Chat: .* SCROLL\(up,back\)$' <<<"$CAPTURE"
handsfree STOP
app_settings --ez read_aloud false

step "23. Captures show ChatGPT as it is, even after it changed screens in place with hands-free off; short labels still leave out chat text"
# The real app's in-place changes didn't reach the service, so its captures went stale until they cleared its
# cache. The mock's changes do reach it, so this checks captures keep up rather than reproducing that.
shell am force-stop com.openai.chatgpt
shell am start -n com.openai.chatgpt/.MainActivity --ez odd false --ez ignore_send false --es transcript "'$SAID'" \
  --ei read_delay 800 --ez replied true --ez quick false --ez long false >/dev/null # stays in front this time
sleep 2
BEFORE="$(capture_now)"
read -r MIC_X MIC_Y <<<"$(sed -nE 's/^  mic="[^"]*" \[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/p' \
  <<<"$BEFORE" | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')"
begin
shell input tap "${MIC_X:-0}" "${MIC_Y:-0}" # a dictation started in ChatGPT itself, which hands-free doesn't hear of
sleep 1
AFTER="$(capture_now)"
user_tap cancel
sleep 1
LABELLED="$(capture_now labels)"
collect
check "a capture shows ChatGPT ready to dictate" has '^Detected: IDLE$' <<<"$BEFORE"
check "and only the length of the name inside a tappable row" has 'text=\[9 chars\]' <<<"$BEFORE"
expect "a dictation then starts in ChatGPT itself" 'MockGPT.*TAP mic -> dictation started'
check "a capture straight after shows its dictation bar, not the screen before" has '^Detected: DICTATING$' <<<"$AFTER"
check "with short labels, the row's name shows" has 'text="Mock chat"' <<<"$LABELLED"
check "but chat text still shows only its length" lacks 'earlier (question|reply)|Stop reading here' <<<"$LABELLED"
shell input keyevent KEYCODE_HOME

step "Stability"
check "no crashes" test "$(adb_ logcat -d -b crash | grep -c 'com.handsfreegpt' || true)" -eq 0

rm -f "$LOG"
printf '\n%d passed, %d failed, %d skipped\n' "$passed" "$failed" "$skipped"
[[ $failed -eq 0 ]]
