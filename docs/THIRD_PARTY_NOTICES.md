# Third-Party Notices

## Native Terminal Components

Atom's rootless Linux backend runs [PRoot](https://github.com/termux/proot) as an independent process and statically links [talloc](https://talloc.samba.org/) and [libandroid-shmem](https://github.com/termux/libandroid-shmem). The PRoot source uses GPL-2.0-or-later; talloc uses LGPL-3.0-or-later; libandroid-shmem uses BSD-3-Clause. The bundled combined PRoot executable is distributed under GPL-3.0-or-later, with each component's copyright and license retained.

The APK's `assets/native-sources` ships verified original sources, plus scripts, PTY source, and patch archives generated automatically from the actual build entry; `assets/licenses` ships the full license texts. The build script and instructions are at [Native Terminal Components](TERMINAL_NATIVE.md). These independent third-party programs continue to apply their own licenses.

## models.dev Model Catalog

Atom's offline model catalog snapshot comes from [models.dev](https://models.dev/api.json), fetched as raw JSON by `scripts/update-models-dev-catalog.py`, compressed, and distributed with the APK. models.dev's catalog data and source use the [MIT License](https://github.com/anomalyco/models.dev/blob/dev/LICENSE), copyright © 2025 models.dev. The copyright and full license text are shipped with the APK at [`assets/licenses/models-dev-MIT.txt`](../app/src/main/assets/licenses/models-dev-MIT.txt).

## Web Page Text Parsing

Atom uses [jsoup](https://jsoup.org/) to parse already-downloaded HTML on-device. jsoup uses the MIT License, copyright Jonathan Hedley; the full license text ships with the APK at [`assets/licenses/jsoup-MIT.txt`](../app/src/main/assets/licenses/jsoup-MIT.txt). Dependency versions are authoritative from `gradle/libs.versions.toml`.

Android standard-library compatibility uses the NIO spec of [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs), distributed under GNU GPL v2 with Classpath Exception, with the full license text kept at [`assets/licenses/desugar-jdk-libs-LICENSE.txt`](../app/src/main/assets/licenses/desugar-jdk-libs-LICENSE.txt). These components retain their respective license rights.

## Account Authentication

Atom uses [Nimbus JOSE + JWT](https://connect2id.com/products/nimbus-jose-jwt) to verify the signatures and identity claims of OAuth logins. The library is maintained by Connect2id Ltd. and uses Apache License 2.0; the full license text ships with the APK at [`assets/licenses/nimbus-jose-jwt-Apache-2.0.txt`](../app/src/main/assets/licenses/nimbus-jose-jwt-Apache-2.0.txt). Dependency versions are authoritative from `gradle/libs.versions.toml`.

## COUI and Miuix

Atom's app UI uses [COUI](https://github.com/suqi8/coui) (Maven group `io.github.suqi8.coui.kmp`; full license text ships with the APK at [`assets/licenses/coui-Apache-2.0.txt`](../app/src/main/assets/licenses/coui-Apache-2.0.txt)) and [Miuix](https://github.com/compose-miuix-ui/miuix) (under [Apache License 2.0](https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE)). Dependency versions are authoritative from `gradle/libs.versions.toml`.

## DexKit

Atom uses [DexKit](https://github.com/LuckyPray/DexKit) to parse DEX signatures of system apps and locate obfuscated hook targets. Dependency versions are authoritative from `gradle/libs.versions.toml`; official release records are at [Releases](https://github.com/LuckyPray/DexKit/releases).

DexKit's non-`Core/` code such as the JVM interface uses [Apache License 2.0](https://github.com/LuckyPray/DexKit/blob/2.2.0/LICENSE), and the `Core/` native parsing code uses [GNU LGPL v3](https://github.com/LuckyPray/DexKit/blob/2.2.0/Core/LICENSE). Atom uses its standalone native dynamic library through the official AAR; upstream source, build configuration, and copyright notices are available at [official source](https://github.com/LuckyPray/DexKit/tree/2.2.0). These third-party components continue to apply their own licenses.

## JetBrains Mono

The terminal UI uses the no-ligature version (Regular, Bold) of [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) 2.304, distributed as a font resource with the APK. The font uses the SIL Open Font License 1.1, copyright The JetBrains Mono Project Authors; the full license text ships with the APK at [`assets/licenses/jetbrains-mono-OFL.txt`](../app/src/main/assets/licenses/jetbrains-mono-OFL.txt).

## Material Icons

Atom's feature icons use the Rounded series of AndroidX Compose Material Icons, provided via the `material-icons-extended` dependency. The icons and their AndroidX implementation use [Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt); source at [Material Design Icons](https://github.com/google/material-design-icons).

## App Icon

Atom's app icon is derived from the app icon of Google's [AI Edge Gallery](https://github.com/google-ai-edge/gallery), converted to a VectorDrawable with safe padding retained, and the notification icon is its monochrome variant. AI Edge Gallery uses [Apache License 2.0](https://github.com/google-ai-edge/gallery/blob/main/LICENSE).

## Lucide Atom

The thinking icon uses [Lucide Atom](https://github.com/lucide-icons/lucide/blob/main/icons/atom.svg), kept as a local VectorDrawable resource without depending on the Lucide icon library. The icon uses the ISC License:

```text
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## Android Hidden API Bypass

Atom uses [Android Hidden API Bypass](https://github.com/LSPosed/AndroidHiddenApiBypass) to apply the user-selected predictive-back setting. The library uses [Apache License 2.0](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE).

## Lobe Icons

Model and provider brand icons come from `@lobehub/icons-static-avatar` 1.13.0 of [Lobe Icons](https://github.com/lobehub/lobe-icons). The original 1280×1280 WebP assets are losslessly scaled to 128×128 without changing colors or proportions and packaged locally with Atom.

| Atom resource | Lobe Icons Avatar |
| --- | --- |
| `provider_logo_openai.webp` | `openai.webp` |
| `provider_logo_anthropic.webp` | `anthropic.webp` |
| `provider_logo_bailian.webp` | `bailian.webp` |
| `provider_logo_deepseek.webp` | `deepseek.webp` |
| `provider_logo_kimi.webp` | `kimi.webp` |
| `provider_logo_mimo.webp` | `xiaomimimo.webp` |
| `provider_logo_minimax.webp` | `minimax.webp` |
| `provider_logo_stepfun.webp` | `stepfun.webp` |
| `provider_logo_siliconflow.webp` | `siliconcloud.webp` |
| `provider_logo_openrouter.webp` | `openrouter.webp` |
| `model_logo_claude.webp` | `claude.webp` |
| `model_logo_qwen.webp` | `qwen.webp` |
| `model_logo_zai.webp` | `zai.webp` |
| `model_logo_chatglm.webp` | `chatglm.webp` |
| `model_logo_gemini.webp` | `gemini.webp` |
| `model_logo_gemma.webp` | `gemma.webp` |
| `model_logo_grok.webp` | `grok.webp` |
| `model_logo_meta.webp` | `meta.webp` |
| `model_logo_mistral.webp` | `mistral.webp` |
| `model_logo_doubao.webp` | `doubao.webp` |
| `model_logo_hunyuan.webp` | `hunyuan.webp` |
| `model_logo_yi.webp` | `yi.webp` |

Lobe Icons uses the MIT License:

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The names, icons, and other brand marks of OpenAI, Anthropic, Claude, Alibaba Cloud Bailian, Qwen, DeepSeek, Kimi, Xiaomi MiMo, MiniMax, StepFun, Z.ai, ChatGLM, Gemini, Gemma, Grok, Meta, Mistral, Doubao, Hunyuan, Yi, SiliconFlow, and OpenRouter belong to their respective rights holders. Atom displays these icons only to accurately identify the model service the user is configuring, and does not imply sponsorship, endorsement, or partnership of these vendors with Atom. Use of the OpenAI icon should also follow its [brand guidelines](https://openai.com/brand/).

## Optional Language Runtimes

Atom does not bundle uv or Node.js into the APK. When the user actively installs the corresponding profile, Atom downloads the currently pinned latest stable artifacts; Debian uses the official Node.js Linux glibc distribution package, Alpine uses `nodejs-current` from its stable repository, and uv downloads the official glibc or musl artifact according to the selected distribution.

| Tool | Source | License |
| --- | --- | --- |
| uv / uvx | [astral-sh/uv](https://github.com/astral-sh/uv) | Apache License 2.0 / MIT |
| Node.js / npm / npx | [nodejs/node](https://github.com/nodejs/node) | MIT and third-party licenses inside the distribution package |

## Optional APK Analysis Tools

Atom does not bundle the following tools into the APK. When the user actively installs "APK analysis" on the Linux tool environment page, Atom downloads and verifies artifacts from the pinned official Releases; tools are kept in the user's currently selected Alpine or Debian environment and apply their own licenses:

| Tool | Source | License |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Apache License 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Apache License 2.0 |
| smali / baksmali | [google/smali](https://github.com/google/smali) | BSD 3-Clause License |

The license of JADX's distribution package is retained together with the required CLI files; the licenses and third-party notices of Apktool, smali, and baksmali are retained within their respective JAR artifacts. Atom only provides verified installation, command entry points, and capability boundaries, and does not re-license these tools.

When GitHub's actual artifact domain is unreachable or slow, the installer may request the same public Release URL through `gh-proxy.com`; Node.js artifacts may be requested through `cdn.npmmirror.com` first. The download entry learns the user's network address and the requested public artifact; Atom does not send it accounts, cookies, API keys, or other Atom data, and continues to verify the built-in official artifact size and SHA-256 before writing to disk. Users who do not want to use the download entry can simply not install the corresponding optional profile.
