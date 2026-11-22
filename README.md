# Atom

![minSdk 34](https://img.shields.io/badge/minSdk-34-3DDC84?logo=android&logoColor=white)
[![GPL-3.0 License](https://img.shields.io/github/license/namanwtf/atom?color=1677FF)](LICENSE)

**A system-level AI assistant and mobile agent for Android — an English-first fork of [Eta](https://github.com/Mangi-11/Eta).**

Atom is built on top of **Eta**, the excellent system-level Android AI agent by [@Mangi-11](https://github.com/Mangi-11). We keep Eta's engine — accessibility-driven GUI control, the background virtual display, the mobile terminal and Linux environment, the personal-context tools and the model ecosystem — and rebuild the product around it:

- **English-first, end to end.** Every UI string, prompt, tool schema, doc and built-in skill ships in English. The Chinese-only locale is gone.
- **True OLED black.** A real `#000000` chat and app background (both Miuix and COUI styles), on by default in dark mode.
- **A floating orb.** A draggable orb with an electric ring that expands into **Hide / Open chat / Talk**.
- **Its own identity.** Ships as `io.github.namanwtf.atom`, so it installs **alongside** the original Eta.
- **Hardened defaults.** No third-party download proxies, no CN-only mirrors, its own update channel, no telemetry SDKs.

![Atom home — pitch-black OLED chat](docs/Screenshots/atom_home.png)

## Credits

Atom would not exist without the original work:

- **[Eta](https://github.com/Mangi-11/Eta)** — the original Android system-level AI agent, by **Mangi-11** (GPL-3.0). The core engine, architecture and the overwhelming majority of the code are theirs.
- **Claude / Anthropic** — the original project was developed with substantial AI assistance from Claude; much of the agent architecture and code owe their shape to it.

Our contribution is the fork around that core: the full English pass, the OLED theme, the floating orb, the hardened CI and privacy defaults, and the independent app identity. Bugs introduced by those changes are ours; the core belongs to the Eta authors.

## Features (inherited from Eta)

- System-level assistant with native digital-assistant wake and screen context; personal data access (notifications, SMS/OTP, calendar, photos, recordings, bills, orders, notes…); cross-app GUI control.
- Background virtual display (Root / LSPosed), parallel Subagents, scheduled tasks, a mobile PTY terminal + Alpine/Debian Linux, web search & browser, AI image generation, long-term memory, Skills, MCP, and Tavern character roleplay.
- BYOK or Codex (ChatGPT) / Grok subscription sign-in. Requires Android 14+.

## Install

Download the signed APK from [Releases](https://github.com/namanwtf/atom/releases), or build it yourself — CI (`.github/workflows/ci.yml`) compiles and (optionally) signs it on every push.

## License

GPL-3.0 — see [LICENSE](LICENSE). Atom is a derivative work of Eta; the original copyright and license remain in force.
