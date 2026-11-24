# Atom — project handoff

Everything a maintainer (human or AI) needs to continue this project without
re-deriving it. Update this file when the architecture or the packaging changes.

## What Atom is

An AI agent that runs on the phone itself: it reads the screen, taps through
apps, runs shell and Linux, keeps memory, and works in the background. Android
14 and up, any model, no telemetry.

- **App id / Kotlin namespace:** `io.github.namanwtf.atom`
- **Repo:** `github.com/namanwtf/atom`, branch `main`, GPL-3.0
- **versionName / versionCode:** `3.4.5` / `2026112201` (see Versioning)
- **Releases:** `github.com/namanwtf/atom/releases`
## Current state

### Floating bubble (the part with the most recent work)

All in `app/src/main/kotlin/io/github/namanwtf/atom/agent/overlay/`:

| File | Responsibility |
| --- | --- |
| `AgentOverlayBubble.kt` | The face: 60dp face inside a 74dp ring, breathing 1.0→0.9 while working, three accent arcs, 1.14× swell while dragging. Picks one of five faces by state: idle, thinking, paused, fired (dragging), brace. Palette lives in `AtomOverlayColors` (`Bolt` = #4F8BFF overlay accent). |
| `AtomOrbMenu.kt` | Hold menu: 344dp window, 132dp radius, 52° arc step, blue rings, halo, energy lines with a travelling spark. Icon-only (52dp targets) because text labels collided on the arc. |
| `AtomDragTarget.kt` | Drag-to-dismiss: bottom X target plus the Tesla-coil effect ported from Buddy's `ArcView.java` — sparks on both ends, bolts that brighten with proximity, white core when fused. |
| `AtomBubbleService.kt` | The persistent bubble: window at `TOP|END`, drag with edge clamping and snap, position persisted in SharedPreferences `atom_overlay` (`bubble_x`, `bubble_y`), drag-to-dismiss, hold-menu window, full-height panel with keyboard-aware fit, mini-chat streaming. |

Behaviour that must not regress:

1. **The bubble stays where the user put it.** Position is saved on drag end and
   restored on start; closing the panel must not move it.
2. **The panel is a full-height sheet** between the status and navigation bars,
   top-anchored, and it *shrinks* when the composer is focused so the composer
   sits on the keyboard. An `APPLICATION_OVERLAY` window never receives IME
   insets on this ROM; the keyboard height comes from
   `AgentAccessibilityService.keyboardTop`, then the IME inset, then 32% of the
   display as the last resort. Do not reintroduce a bare percentage without a
   measured fallback.
3. **One orb only.** `AgentRuntimeService` skips its own bubble when the
   persistent one is enabled.
4. **Hold menu is anchored to the bubble**, not to the window: the window is
   placed first, then the arc is drawn from the bubble's real position.
5. **Read-aloud falls back to the system voice.** If provider synthesis fails
   (bad key, timeout, refusal) the text goes to Android `TextToSpeech`
   (`SystemTts.kt`) instead of surfacing an error.

### Settings placement

- **Floating bubble** switch: `Settings → Floating assistant`
  (`AssistantOverlaySettingsScreen`, both COUI and Miuix layouts). It must not
  live under Appearance — it used to sit inside the Monet-only block and
  disappeared whenever Monet colours were off.
- **Pure black** stays in `Settings → Appearance & Theme`.

### Everything else

Agent runtime and tools (`agent/`), personal context (`agent/context`), MCP,
skills, subagents, scheduled tasks, terminal + Alpine/Debian Linux, virtual
display, voice, roleplay characters, image generation. See `docs/`:
`AGENT_RUNTIME.md`, `TECHNICAL.md`, `TERMINAL_NATIVE.md`, `VOICE.md`,
`CHARACTERS.md`, `ROOTLESS_SUPPORT.md`, `UI_COMPONENTS.md`.

## Build and release

- **CI is the compiler.** CI runs on every push: JDK 25 plus the runner's
  preinstalled Android SDK — do **not** reintroduce `android-actions/setup-android@v3`,
  it fails on the removed `tools` package. Then `assembleRelease`, non-blocking
  unit tests, signing with repo secrets, artifact upload.
- **Publishing is tag-only.** `.github/workflows/ci.yml` has
  `if: startsWith(github.ref, 'refs/tags/v')` on the release step, so pushes to
  `main` produce a workflow artifact only. To publish an APK:

  ```bash
  git tag -f v3.4.5-atom.N && git push origin refs/tags/v3.4.5-atom.N
  ```

  The release then carries `atom-<n>.apk`.
- **Signing** uses `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`. Local keystore: `/workspace/tools/atom-release.jks`
  (alias `atom`; password is not written here). Certificate SHA-256:
  `F3:BA:9F:48:E1:53:A9:97:98:BB:0D:7C:74:C2:41:D8:D0:C3:F9:C1:DB:26:86:F7:B5:0C:28:F1:87:CB:8F:A7`.
  **Never uninstall the app to update it** — same key means `pm install -r`
  keeps keys, permissions and data.
- **Batching rule:** a CI round trip is 12–20 minutes. Ship 2–3 related fixes
  per tag, not one tag per fix — a burst of tags queues runners and wastes an
  hour.
- Download speed matters: the release asset is fast, the workflow artifact
  (~17 MB through api.github.com) is slow on this connection. Prefer the
  release asset.

## Install and verify on the device

```bash
# Linux /workspace == Android /data/local/tmp/eta
pm install -r -d /data/local/tmp/eta/<file>.apk
dumpsys package io.github.namanwtf.atom | grep lastUpdateTime
dumpsys window windows | grep -A4 'Window{.*namanwtf.atom}'   # mAttrs = geometry
screencap -p /data/local/tmp/x.png                            # then read the image
input swipe X Y X Y 900                                       # long-press
input swipe X1 Y1 X2 Y2 4000 & sleep 2; screencap ...         # mid-drag capture
dumpsys input_method | grep mInputShown                       # is the keyboard up
```

Note: with `gravity = TOP|END`, `lp.x` is the distance from the **right** edge.
Getting that backwards cost hours of "the bubble is on the left".

**Verification rule:** no claim without a window dump or a screenshot. Two
"fixed" reports in this project's history were wrong because they were reasoned
rather than measured.

## Versioning

`versionCode` is `yyyyMMdd` + a two-digit daily sequence, bumped by hand with
`versionName` in `app/build.gradle.kts`. It must never decrease. Release tags
follow `v<versionName>-atom.<n>`.

## Open issues

1. **Keyboard fit** is derived, not measured: `keyboardTop` from the
   accessibility service reads 0 here, so the panel uses the 32% fallback. It
   lands within a pixel on this device, but other keyboards will need the real
   value.
2. **Drag-to-dismiss arc** has never been captured mid-gesture on a build that
   ships it — verify visually before trusting it.
3. **Existing conversations keep old titles.** Title generation was fixed to
   follow the user's language (prompt + a deterministic guard that rejects
   ideographs when the user wrote English), but stored titles are not rewritten.
4. **Buddy's status pill** (working / hands-on / paused / waiting approval next
   to the face) is not ported. It needs run state inside the bubble.
5. **LSPosed hooks** are not verified end-to-end under Atom's package id.
6. **`AgentRuntimeService`'s bubble** is skipped when the persistent bubble is
   on, so that path is barely exercised.

## Non-negotiables

- English only: strings, prompts, tool schemas, comments, docs. No `values-zh`.
- No telemetry, no third-party download proxies, own update channel.
- Keep the Eta (GPL-3.0) and Buddy (Apache-2.0) attribution intact.
- Report partial results as partial; never present an attempted action as a
  completed one.
