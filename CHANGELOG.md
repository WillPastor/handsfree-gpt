# Changelog

Notable changes to this project. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
