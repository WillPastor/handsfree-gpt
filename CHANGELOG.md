# Changelog

Notable changes to this project. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
