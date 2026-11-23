# Syncing upstream (Mangi-11/Eta)

Atom is a hard fork with product work upstream does not have: the floating bubble
and its hold menu, the panel, the privacy patches (own update channel, no CN
mirrors or proxies), and an English pass over every string, comment and prompt.
Upstream moves fast and in Chinese. The job of a sync is to take upstream's
*structure* without giving up any of Atom's four layers.

## Measured baseline (2026-11-23)

| Fact | Value |
| --- | --- |
| Upstream head synced to | `5e048c5a` |
| Upstream head available | `05086d3a` (5 commits) |
| Atom commits ahead | 70 |
| Atom changed files since the base | 1,146 |
| Files upstream touched in those 5 commits | 41 |
| Files both sides touched | 3 (all `values-b+zh*/root_capabilities.xml`) |
| Merge conflicts | 23 files, ~60 hunks |

The interesting number is the last row against the third: Atom has rewritten
essentially the whole tree, so *textual* overlap looks tiny while *semantic*
overlap is the real risk. A sync that only counts conflicts will look safe and
still fail to compile.

## Merge, never rebase

Rebasing `main` onto upstream replays all 70 Atom commits over a rewritten
1,146-file tree. Every upstream touch becomes a conflict, repeatedly, including
the Chinese resources Atom deletes on purpose — the first rebase attempt died on
exactly that modify/delete pair. Rebasing also rewrites published history, which
breaks every existing tag and forces a force-push on a shipped repo.

A merge resolves the divergence once, keeps history honest, and leaves `main`
fast-forwardable. `.github/workflows/sync-upstream.yml` therefore merges, opens a
PR only when the merge is clean *and* the invariants pass, otherwise opens an
issue. It never tags and never releases; publishing stays a manual decision.

## Resolution policy

Resolve by layer, not by hunk. When in doubt, upstream's logic wins and Atom's
text wins.

| Conflict | Resolution |
| --- | --- |
| `values-b+zh-*`, any new Chinese resource | Keep deleted. English only; no `values-zh`. |
| Kotlin comments/strings in upstream-changed code | Take upstream structure, re-apply Atom's English wording. |
| `agent/media`, `agent/device` (storage guardrails, image pipeline) | Take upstream wholesale, then re-English the comments. Atom has no product edits here — verify with `git log --oneline 5e048c5a..HEAD -- <file>` before assuming. |
| `ui/components`, `ui/app` | Take upstream layout/animation changes, re-apply the panel specifics: no chevrons, model chip, header icon sizing. |
| `ui/screens/scheduled`, `ui/screens/skills`, `ui/screens/tools` | Upstream feature work; take it, re-English strings, keep Atom's spacing decisions if they were deliberate. |
| `build.gradle.kts`, `gradle/*`, `gradlew*` | Take upstream dependency bumps. Keep Atom's signing config, `atom.updateRepo`, `versionCode`/`versionName`. |
| `agent/overlay/**` | Atom-only. If upstream touched it, treat as suspicious and review by hand. |

## Invariants (run before pushing)

```bash
# rg is UTF-8 aware; git grep -P is bytewise and false-positives on box drawing.
rg -l '[\p{Han}]' --glob '*.{kt,kts,java,xml,md,json,gradle,pro,txt,sh,yml,yaml}' \
  --glob '!docs/THIRD_PARTY_NOTICES.md' .          # must print nothing
git grep -n 'io\.github\.mangi\.eta' -- '*.kt' '*.java' '*.xml' '*.gradle*' '*.pro'
git grep -n '/data/local/tmp/eta' -- '*.kt' '*.java' '*.sh'
grep atom.updateRepo gradle.properties
grep -E 'versionName|versionCode' app/build.gradle.kts
```

Verified on the current tree: the Han check prints nothing. Documentation
legitimately names the old package id while explaining this invariant, so the
rename check is restricted to source files.

These are the same checks the workflow runs on the merge result. They catch the
two recurring failure modes: a Chinese resource coming back through a conflict,
and a rename reverting to Eta's identifiers.

## Semantic risks to check after the merge

Textual clean does not mean compiles. For the 5-commit sync these are the known
hotspots:

1. **Media/storage refactor** (`36b1c77f`): a three-layer streaming pipeline and
   a rewritten file-storage guardrail. Signatures moved; every Atom call site
   (image tools, chat image search, runtime image transfer) needs a compile, not
   a reading.
2. **COUI 2.1.0** (`aec537db`): Atom's settings screens have a COUI branch and a
   Miuix branch; both are hand-maintained. Check `AssistantOverlaySettingsScreen`
   after the dependency bump.
3. **Miuix 0.9.5-beta03 + default style change** (`29804218`, `a14286b9`): the
   default appearance changing is a product decision, not a sync side effect.
   Keep Atom's current default unless deliberately changed.
4. **Scheduled-task editor dialog** (`05086d3a`, `ac3ef4b7`): touches the same
   screens Atom edited for spacing; recheck the 12dp group spacing.
5. **Gradle wrapper/dependency bumps** auto-merge silently and can break CI's
   JDK 25 + preinstalled SDK setup.

## Verification sequence

1. Merge and resolve.
2. Run the invariants above.
3. Push a branch; let CI compile (`assembleRelease`, non-blocking tests).
4. Install the CI artifact with `pm install -r -d` (never uninstall).
5. Dump the three windows and compare with the recorded state: bubble position,
   panel `1050x2277` and `1050x1528` with the IME, hold menu on long-press.
6. Only then bump `versionCode` and tag `v<versionName>-atom.N`, batching 2–3
   fixes per tag.

The bubble position and the panel geometry are the two things a UI-layer merge
breaks quietly; if either moved without a deliberate change, the merge is wrong.
