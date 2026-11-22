# Eta Release Process

## Configure Signing Secrets

Signing certificates and passwords must not be committed to Git. Before first use, add
the following under `Settings > Secrets and variables > Actions` in the repository:

- `ETA_RELEASE_KEYSTORE_BASE64`: Base64 text of the signing certificate
- `ETA_RELEASE_STORE_PASSWORD`: KeyStore password
- `ETA_RELEASE_KEY_ALIAS`: Key alias
- `ETA_RELEASE_KEY_PASSWORD`: Key password

On macOS you can copy the certificate's Base64 text with the following command:

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

You can also use the GitHub CLI. Do not write password-type Secrets directly in command
arguments; enter them at the prompt after running the command:

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## PR Checks

A Pull Request targeting `main` triggers `PR Check`, which runs Lint, unit tests, and a
Debug build in sequence, uploading the check report on failure. The workflow reads no
Secrets. PRs from forks only run after a maintainer approves them on the PR page; before
approving, review changes under `.github/` carefully.

The `main` branch requires the `Lint / Test / Debug Build` checks to pass before merging,
enforced via the repository ruleset. If you rename that job, update the ruleset accordingly.

## Build and Release

The following cases generate a Debug APK and a signature-verified Release APK in the same
workflow run, saved as two directly downloadable Actions Artifacts for 14 days:

- Pushing a commit to `main`
- Pushing a `v*` tag
- Manually running `Actions > Eta Build` on GitHub

The same workflow runs Lint and unit tests in parallel. Check failures do not affect APK
upload, but pushing a tag will not create a Release draft.

Non-tag builds append SemVer build metadata `+<short-commit-hash>` after the version name,
for example `Eta-v3.2.0+a1b2c3d.apk`, to distinguish different builds under the same version
number; only tag builds produce a formal version name without a suffix. Actions Artifacts
require login to download and expire, so they are for development/testing only; user-facing
distribution uses GitHub Releases exclusively.

When pushing a `v*` tag, the workflow also creates a GitHub Release draft containing only the
Release APK, with the description pre-filled by an auto-generated changelog. Drafts are not
visible to the in-app update check; publishing is always done manually by a maintainer.

Before a formal release, update `versionCode` and `versionName`, then create a tag
corresponding to `versionName`. `versionCode` uses `yyyyMMdd` plus a two-digit sequence
number for that day and must only increase; otherwise installed users cannot install the
upgrade over the old version. When pushing a tag, the workflow verifies the tag matches
`versionName` and fails the build if they do not. For example, to release `2.2.2`:

```bash
git tag v2.2.2
git push origin v2.2.2
```

After pushing the tag, wait for the `Eta Build` workflow to finish, then:

1. Open the `vX.Y.Z` draft under `Releases` in the repository and confirm the asset is `Eta-vX.Y.Z.apk`.
2. Rewrite the release notes.
3. After checking the version, notes, and asset, have a maintainer publish manually.
