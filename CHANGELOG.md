# Changelog

All notable changes to Atom are recorded here. The version is `3.4.5`; release
tags are `v3.4.5-atom.<n>` and each tag ships a signed APK.

## 3.4.5

### Added
- **Floating bubble** (adapted from Buddy, Apache-2.0): a 60dp face in a 74dp
  ring, five state-driven faces (idle / thinking / paused / fired / brace),
  breathing and working arcs, a hold menu, and drag-to-dismiss with a
  Tesla-coil effect onto a bottom X target.
- **Mini chat panel**: full-height sheet between the system bars, header with
  `+` / expand / `X`, a model chip, a one-pill composer (mic + round send), and
  it sits flush on the keyboard by shrinking when the composer is focused.
- **System voice fallback**: when a cloud TTS provider fails, read-aloud falls
  back to Android `TextToSpeech`.
- **Position memory**: the bubble restores where the user left it.

### Changed
- The bubble switch moved out of Appearance into **Settings → Floating
  assistant** (it was hidden inside the Monet-only block before).
- Conversation titles follow the user's language; a guard rejects a title
  written in ideographs when the message contains none.
- README rewritten around the product, with real device screenshots; GitHub
  description, topics and homepage updated; `PROJECT.md` rewritten as a handoff.

### Fixed
- Panel no longer leaves a 210px gap above the keyboard (was reserving 40% of
  the display; now uses the accessibility keyboard top, then the IME inset,
  then a measured 32%).
- The bubble no longer jumps to the bottom edge when the panel closes.
- Header chevrons that rendered as a capital "V" were removed.
- Hold-menu labels that collided with their neighbours were removed (icons only).

### Performance
- An idle bubble is now a static image; the breathing/spin animation only runs
  while a run is active or the bubble is being dragged. This removes a
  continuous frame-rate overlay that cost ~13% CPU at idle.

## Unreleased / next
- Route the bubble chat through the agent runtime (tools + streaming) instead of
  a bare model call.
- Release the keep-screen-on wake lock between GUI steps.
- Battery and thermal guardrails for long runs.
