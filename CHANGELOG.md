# Changelog

All notable changes to Atom are recorded here. The version is `3.4.5`; release
tags are `v3.4.5-atom.<n>` and each tag ships a signed APK.

## 3.4.5

### Added
- **Floating bubble** (adapted from Buddy, Apache-2.0): a 74dp orbit ring with
  a minimal atom glyph (nucleus + crossing orbits), breathing and working
  arcs, a hold menu, and drag-to-dismiss with a smooth proximity beam onto a
  bottom X target.
- **Mini chat panel**: full-height sheet between the system bars, header with
  `+` / expand / `X`, a model chip, a one-pill composer (mic + round send), and
  it sits flush on the keyboard by shrinking when the composer is focused.
- **Bubble chat now runs through the full agent runtime** (tools + streaming)
  instead of a bare model call, and persists one conversation across restarts
  — cleared only by the explicit "new chat" action — surfaced in the app's own
  conversation list.
- **System voice fallback**: when a cloud TTS provider fails, read-aloud falls
  back to Android `TextToSpeech`.
- **Position memory**: the bubble restores where the user left it.
- **New launcher icon**: the atom emblem, replacing the placeholder icon.
- **Autonomous release pipeline**: a merge to `main` now computes the next
  `v3.4.5-atom.N`, builds, signs, and publishes the GitHub Release on its own —
  nothing to tag by hand.

### Changed
- The bubble switch moved out of Appearance into **Settings → Floating
  assistant** (it was hidden inside the Monet-only block before).
- Conversation titles follow the user's language; a guard rejects a title
  written in ideographs when the message contains none.
- The reply-language instruction is now enforced at both ends of the request,
  fixing occasional wrong-language replies.
- README rewritten around the product, with real device screenshots; GitHub
  description, topics and homepage updated; `PROJECT.md` rewritten as a handoff.
- The bubble's cartoon faces (direct Buddy assets) and the drag-to-dismiss
  "Tesla-coil" lightning/sparks are replaced with a minimal atom glyph and a
  calm gradient beam — both were flagged as off-brand and visually busy.

### Fixed
- The chat panel's keyboard fit raced four independent signals (accessibility
  `keyboardTop`, an insets listener, an insets-animation callback, and a 400ms
  `imm.isActive()` poll); collapsed to one authoritative source, fixing the
  jitter and stuck-half-height, and the panel now reliably returns to full
  height when the keyboard is dismissed, not just when it opens.
- The bubble no longer jumps to the bottom edge when the panel closes.
- Header chevrons that rendered as a capital "V" were removed.
- Hold-menu labels that collided with their neighbours were removed (icons only).

### Performance
- An idle bubble is now a static image; the breathing/spin animation only runs
  while a run is active or the bubble is being dragged. This removes a
  continuous frame-rate overlay that cost ~13% CPU at idle.
- The keyboard-visibility poll is cancelled when the panel closes instead of
  running for the rest of the service's lifetime, and the keep-screen-on wake
  lock has a backstop timeout so a crashed run can no longer hold it forever.
- Removed the 400ms keyboard poll entirely as part of the keyboard-fit fix
  above — one less background timer running while the panel is open.

## Unreleased / next
- Battery and thermal guardrails for long runs.
- Narrow the accessibility event subscription to the minimum set when idle,
  full set only during an active run.
- ROM-agnostic keyboard fit (currently tuned against one device's behavior).
