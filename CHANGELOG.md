# Changelog

Notable changes to this project. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [0.3.0] - 2026-11-02

ChatGPT's replies can now be read aloud. Checked with the live ChatGPT app on a Samsung Galaxy S26 Ultra running
Android 16.

### Added

- **Read replies aloud** ([#3](https://github.com/WillPastor/handsfree-gpt/issues/3)). When a reply finishes, the
  app presses ChatGPT's own Read aloud button, then waits until ChatGPT stops speaking before listening again
  (continuous mode) or pausing.
  - Play/pause or tapping the pill pauses and resumes the reply. Next ⏭ skips the rest and starts talking, and
    previous ⏮ stops it.
  - A reply you pause in ChatGPT stays paused: the app waits rather than taking the pause for the end.
  - Turning hands-free off pauses the reply, even one ChatGPT is still fetching.
  - The app never reads the reply text. It watches ChatGPT's buttons, its audio bar, and whether audio is playing.
- The mock ChatGPT has a Read aloud button and ChatGPT's audio bar, and "speaks" for 6 s. End-to-end tests
  cover:
  - reading, and waiting for the end
  - pausing, in ChatGPT and with play/pause
  - skipping, including before the speech has started
  - turning hands-free off mid-reply

  Debug-only receivers let the tests switch settings and take captures.

### Changed

- **Shorter UI captures.** Layout wrappers with nothing of their own are left out, so a capture fits in a
  message. Read-aloud controls are listed at the top. Captures pasted from a phone were being cut off.

### Fixed

- **Continuous mode waited for a reply when nothing had been sent, then read the previous reply aloud again**
  ([#13](https://github.com/WillPastor/handsfree-gpt/issues/13)). Found in phone testing. It happened when a
  dictation was cancelled with ChatGPT's own X, and when one with nothing in it was sent. After 8 s the app gave
  up waiting and pressed Read aloud on the reply on screen, then listened again with nobody talking. A message now
  only counts as sent once ChatGPT has taken it: a reply starts, or the text box empties.
  - ChatGPT's own X counts as cancelled, as "cancel that" does. Its own ■ keeps the text in the box, and its
    own ↑ still sends.
  - A dictation sent with nothing in it shows "🤐 Nothing to send".
  - Next ⏭ with text in the box now checks that it went too, as a voice command's send does.
  - A reply already showing as the dictation bar goes counts as sent at once. A short one could otherwise be over
    before the app first looked (Codex review of #16).
  - End-to-end tests tap the mock's own X, ■ and ↑ during a dictation, send a dictation with nothing in it, and
    have ChatGPT reply within half a second, in a chat that already has a reply.
- **Chat text could pass for ChatGPT's buttons.** ChatGPT labels each paragraph of a reply with its own text.
  A reply with "stop" or "done" near the bottom of the screen could therefore look like a stop button, and the
  app would wait for it to go away. Text that can't be clicked no longer counts.
- **UI captures could include chat text**, despite the privacy notes. ChatGPT repeats each paragraph in its
  accessibility description, and captures showed the first 100 characters of those, short paragraphs and the
  text box in full. Now they show only the length.

- **A message that itself ended in "send it" lost those words.** After removing the spoken command, the 0.2.1
  fix could remove the phrase a second time, so "Repeat the words send it. Send it." was sent as "Repeat the
  words". The app now works out the text without the command once, and only checks that the edit took. Found by
  an [automated Codex review of #2](https://github.com/WillPastor/handsfree-gpt/pull/2#discussion_r4436439782).

## [0.2.1] - 2026-11-01

First release checked on a real phone: dictation and "send it" work with the live ChatGPT app (1.2026.300) on a
Samsung Galaxy S26 Ultra running Android 16.

### Fixed

- **"…send it" on a longer dictation left the message unsent**, with the command still in it, while the pill
  said "✅ Sent" ([#1](https://github.com/WillPastor/handsfree-gpt/issues/1)). The app acted as soon as ChatGPT's
  text box came back, before the transcript was in it. It now waits for the transcript itself,
  and only says "Sent" once ChatGPT has taken the message. Otherwise it presses send once more, then says it
  couldn't send.

### Changed

- The wait for ChatGPT's transcript is now up to 20 s (was 10 s), so long dictations have time to transcribe.
- The mock ChatGPT takes 2 s to transcribe, like the real app, and can refuse to send. End-to-end tests cover
  both.

## [0.2.0] - 2026-10-31

Finish a dictation without needing silence or touching the phone.

### Added

- **Voice commands.** At the end of a dictation, say:
  - "send it" to send
  - "stop dictation" to keep the text
  - "cancel that" to discard it

  Commands are recognised offline by Android's on-device speech recogniser (Android 13+), fed the audio the app
  already shares with ChatGPT. A command only fires when it's the last thing said, and it's removed from the
  message before sending. Phrases are editable. Settings show offline-language status, offer a download, and
  include a **Test voice commands** dialog.
- **Steering-wheel, headset and Android Auto buttons.** Play/pause toggles talking and finishing, next sends,
  previous cancels, stop exits. HandsFree GPT is also an Android Auto media source, with Send and Cancel on the
  car screen, and "Hey Google, play HandsFree GPT" starts it.
- **Live words on the pill** while you talk.
- **Optional silence auto-stop**, so it can be turned off for noisy cars.
- **Tests:**
  - unit tests for the command matcher and command stripping
  - end-to-end scenarios for media buttons and a spoken "…send it", replayed through the real on-device
    recogniser via a debug-only audio injector

### Changed

- When silence ends a dictation but a voice command is still being recognised, the app waits briefly so the
  command wins.
- Music now pauses while hands-free is on, because the media buttons need it.

## [0.1.0] - 2026-10-31

First MVP: hands-free start and stop of ChatGPT's dictation.

### Added

- Accessibility service that presses ChatGPT's dictation mic, then stops (■) or sends (↑) the dictation.
- Silence detection on a microphone shared with ChatGPT (`SpeechTracker`), tuned for road noise.
- Auto-send, plus an experimental continuous-conversation loop that waits for each reply and then listens again.
- Floating status pill (tap to talk or stop, hold to exit, drag to move) that keeps the screen on.
- Quick Settings tile, launcher shortcuts, and `START` / `STOP` intents for routines.
- Button finding by accessibility label, with a position fallback and UI captures for diagnosis.
- Unit tests for silence detection.
- A mock ChatGPT app and an end-to-end test script.
