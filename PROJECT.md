# Atom — project handoff

Everything a future maintainer (human or AI) needs to continue this fork without surprises.

## What this is

**Atom** is an English-first fork of [Eta](https://github.com/Mangi-11/Eta), a system-level Android AI agent.

- **App id (applicationId):** `io.github.namanwtf.atom`
- **Code namespace:** `io.github.namanwtf.atom` (same value) — the entire codebase was renamed; no "Eta" remains in the app (package, classes, resources, gradle, internal thread/temp/cache names).
- **Repo:** `github.com/namanwtf/atom`, branch `main`
- **Latest release:** `v3.4.4-atom.1` (signed APK) — `github.com/namanwtf/atom/releases`
- **Upstream:** `https://github.com/Mangi-11/Eta` (remote `upstream`)

Because the app id differs from the original Eta, Atom **installs alongside** Eta and has its own data. The original `io.github.mangi.eta` can be uninstalled without touching Atom.

## What we changed vs upstream

1. **Full English pass.** Every user-facing string, prompt, tool schema, test fixture, doc and built-in skill is English. The `values-b+zh+Hans/Hant` locales and `localeFilters` zh entries were removed. The working tree has **0 Chinese characters** (verified by `git ls-files | xargs rg -l '[\p{Han}]'`).
2. **OLED pure black**, default ON in dark mode, fixed to apply in both Miuix and COUI styles (root backdrop + chat backdrop via `LocalPureBlack`).
3. **Floating orb** — `agent/overlay/AgentOverlayBubble.kt`, hosted by `AgentRuntimeService`, drag + electric ring + Hide / Open chat / Talk arc.
4. **Own identity** — app id, signature permission and provider authority are app-specific (`io.github.namanwtf.atom.permission.CONTROL_ACCESSIBILITY_PROTECTION`, `…accessibility.health`). Without this, two Atom/Eta installs collide.
5. **Privacy strip** — own update channel (`BuildConfig.UPDATE_REPO`), no `gh-proxy.com`, no CN-only mirrors (Debian → `deb.debian.org`, Alpine → `dl-cdn.alpinelinux.org`, Node → `nodejs.org`).
6. **CI** — `ci.yml` (Java 25, preinstalled SDK, `assembleRelease`, optional signing, artifact + release), `sync-upstream.yml` (weekly rebase onto upstream, PR on clean / issue on conflict).
7. **README** rewritten for Atom with credits.

## Build

- Pushing to `main` triggers `ci.yml`. It uses Temurin 25 and the runner's preinstalled Android SDK (do **not** reintroduce `android-actions/setup-android@v3` — it fails on the removed `tools` package).
- APK filename comes from `app/build.gradle.kts`: `Atom-v<versionName>.apk`.
- Release signing: either set repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so CI signs, or sign locally with `apksigner` / `uber-apk-signer`.
- **On-device local builds are impractical** (no Android SDK in the phone's Linux env). CI is the compiler — trust its logs.

## Keeping up with Eta releases

Our changes are ordinary commits on top of upstream, so the fork can rebase onto new Eta releases:

- `sync-upstream.yml` runs weekly, `git fetch upstream && git rebase upstream/main`, opens a PR when clean or an issue listing conflicts.
- **Expect conflicts** in translated files — we rewrote nearly every string and comment.
- Resolution playbook:
  - Logic-only change upstream → take upstream (`git checkout --theirs <file>`, then `git add`).
  - Translated file with new upstream logic → keep ours (`git checkout --ours`) and manually port the new logic, then translate any new strings.
  - Never reintroduce Chinese. After a merge, run the zero-Han check above.

## Known issues / TODO

- **LSPosed module**: code constants were updated to the new package, but the Xposed entry class is still `io.github.mangi.eta.ModuleMain` (correct — it follows the namespace). Virtual-display hooks that compare against the app package were updated; if you use the module, verify them on the new id.
- A few **CJK-width unit tests** assert byte/char counts that changed after translation — they compile but may fail at test time. Harmless for the app; fix if you enable test runs in CI.
- **Fullwidth punctuation** (`：，、（）`) survives in some English strings — cosmetic.
- "Eta" still appears in **class names** (`EtaApp`, `EtaDatabase`, `EtaAssistantOverlayService`, …). Intentional; internal only.

## Credits

- **[Eta](https://github.com/Mangi-11/Eta)** by **Mangi-11** (GPL-3.0) — the entire core engine.
- **Claude / Anthropic** — the original project was built with substantial AI assistance.
- Atom's fork work: English pass, OLED theme, floating orb, independent identity, CI + privacy hardening. Bugs in those are ours.

## Current state

- **No "Eta" in the app.** Package, classes, resources, gradle props and internal thread/temp/cache names were all renamed to Atom. CI build of the rename passed (`44993339986`), APK installed and launching.
- **First release:** `v3.4.4-atom.1` with signed `Atom-v3.4.4.apk`; the download link works.
- **Still manual (need a token with Administration/Secrets scope, or the GitHub web UI):**
  - Repo description + topics.
  - CI signing secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) so future CI builds are signed automatically. Until then, sign locally with `uber-apk-signer` + `atom-release.jks`.
- **Dormant/optional:** a few CJK-width unit tests assert byte counts (tests do not run in CI); the LSPosed module is not e2e-tested on the new package.
