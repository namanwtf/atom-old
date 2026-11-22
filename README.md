# Atom

**An AI agent that lives on your phone, not in a browser tab.**

It reads the screen, taps the button, runs the command, remembers the context,
and keeps working while you do something else.

![Atom home](docs/Screenshots/atom_home.png)

---

## The problem Atom solves

Every assistant you use today is a **visitor**. You open it, you type, it answers, you leave. It can describe how to book a train, but it cannot book one. It can write a script, but it cannot run it. It cannot read the photo you just took, the message that just arrived, or the button that is right there on your screen.

Meanwhile the phone is the only computer that goes everywhere with you and knows your whole day — location, messages, money, calendar, what you photographed, what you have to do next. It is also the only one with no agent actually running on it.

Atom runs on that phone. It is a full agent runtime — not a chat window with an API key — with real reach into the system: the screen, the apps, the shell, the files, the data you have already accumulated.

Hand it a goal. It plans, acts, recovers from its own mistakes, and shows you the result.

---

## What it actually does

### It operates the phone

Accessibility-driven control with a real observation loop: it reads the UI tree, taps by element or coordinate, types, scrolls, and re-checks the screen after every action instead of assuming the tap landed. It opens apps, fills forms, navigates settings, plays media, sets alarms and timers, and verifies the outcome before reporting success.

Coordinates are never guessed when a node can be found. When the action result is ambiguous, it re-observes rather than repeating the gesture — the failure mode that makes most phone agents quietly wrong.

### It works while you are not looking

Cross-app tasks run on a **background virtual display** (Root / LSPosed). The agent can drive another app on a hidden screen without hijacking yours, then hand the finished result back to the main display the moment it matters — a payment page, a QR code, a confirmation.

The same idea powers the floating **Atom bubble**: an orb of orbiting electrons on top of whatever you are doing. Tap it for a mini-chat, drag it anywhere, hide it. On supported system builds it also registers as the **system assistant**, so a long-press or gesture opens Atom instead of a cloud assistant.

### It knows the context

An agent that only sees the current screen is a macro. Atom reads what makes an answer *yours*: notifications, SMS and OTP codes, calendar, contacts, call history, photos and screenshots, recordings, clipboard history, health data, downloaded files, orders and deliveries, and the app you were just using.

That context is retrieved on demand, in bounded slices, with the observation recorded — not dumped into a prompt.

### It reaches past the UI

When the UI is the wrong tool, it uses the right one. With Root, Atom controls the system directly: brightness, volume and sound modes, Wi-Fi, Bluetooth, mobile data, airplane mode, location, NFC, dark mode, battery saver, screen timeout; freezes, unfreezes and force-stops apps; reads and writes protected settings; queries system logs; and inspects private app databases read-only.

Two layers exist because the real world has two layers: a well-behaved app uses the public API, and a stubborn one gets the shell.

### It has a workbench

A real PTY terminal, plus Alpine and Debian Linux on device. Files, `git`, Python, Node, `rg`, `jq`, `sqlite3`, APK inspection — a full development environment in your pocket that the agent can use *and* that you can use yourself.

The agent does not just suggest code. It writes it, runs it, reads the errors, and fixes it.

### It is a platform, not a feature list

- **Skills** — installable capability packages with a `SKILL.md`, resources and scripts, from a curated catalog or any public GitHub repo. Three are built in: self-improvement, skill authoring, skill installation.
- **MCP** — connect external Model Context Protocol servers and their tools over HTTP.
- **Subagents** — spawn specialised ephemeral agents (explore, coder, planner, or your own role) in isolated contexts, in parallel, and get back a summary instead of a polluted transcript. Reusable profiles can be saved.
- **Scheduled tasks** — recurring jobs with alarm scheduling, health checks, run reports and notifications. The agent can run at 07:30 without you opening the app.
- **Memory** — persistent, user-editable memory plus conversation retrieval that survives context compaction, so long projects do not restart from zero.

### It stays yours

No telemetry SDK. No analytics back end. No third-party download proxy. Its own update channel, pointing at this repository. Keys are stored locally; usage stays between your phone and the model provider you picked.

Root access is genuinely powerful, so the boundary is explicit: what needs root is listed, what works without it is listed, and nothing silently escalates.

### It speaks your model's language

Twelve providers ship in the box — OpenAI, Anthropic, Google Gemini, DeepSeek, Kimi, Qwen (DashScope), xAI, MiniMax, MiMo, StepFun, SiliconFlow, OpenRouter — plus custom OpenAI-compatible endpoints, subscription login where the provider supports OAuth, and capability detection for reasoning models.

Bring your own key, or use a plan you already pay for. Atom is the layer above the model, not a lock-in below it.

### It also just looks good

Miuix and COUI native surface styles, true OLED black (`#000000`) on by default in dark mode, native LaTeX rendering, streaming voice in and out, and image generation. Design is the last thing listed here on purpose — it is how Atom feels, not what Atom is.

---

## What it needs

| Capability | Requirement |
| --- | --- |
| Chat, memory, skills, MCP, subagents, scheduled tasks, terminal, Linux, files, web | Nothing beyond the app |
| Screen reading, tapping, cross-app automation | Accessibility service |
| Background virtual display | Root or LSPosed |
| Deep system control, private app data, system logs | Root |
| Circle to Search integration, system assistant hooks | LSPosed module |

**Android 14+.** The core is fully usable without root; root and LSPosed unlock the parts that need to reach underneath the system.

---

## Architecture

Atom is not a thin client. The runtime is a foreground service with its own process identity, and the UI talks to it over a wire protocol.

| Concern | Where |
| --- | --- |
| Run lifecycle, checkpoints, recovery | `agent/runtime/` — `AgentRunController`, `AgentRunCheckpointStore`, `AgentRunArchiveStore`, `ExecutionLeaseRegistry` |
| Tool surface | `agent/tool/`, `agent/model/Agent*ToolCatalog` — capability, requirements and after-action verification per tool |
| GUI control | `agent/runtime/GuiControlCoordinator`, `agent/virtualdisplay/CanvasRoutingPolicy` |
| Background execution | `agent/virtualdisplay/VirtualDisplayController` |
| Root bridge | `agent/device/RootShellDeviceController` |
| Terminal + Linux | `agent/terminal/` |
| Context | `agent/context/`, `agent/tool/AgentPersonalContextTools` |
| Extensibility | `agent/mcp/`, `agent/skill/`, `agent/subagent/`, `agent/scheduler/` |
| Presence | `agent/overlay/AtomBubbleService`, `AtomBubbleChat`, `AgentOverlayBubble` |
| Voice | `agent/voice/` — recogniser, synthesiser, interaction session |

Design notes live in [`docs/`](docs): runtime, technical overview, terminal, voice, characters, rootless support.

Runs are checkpointed and recoverable. An interrupted task resumes instead of vanishing, actions are attributed to a run, and a second run cannot steal the phone mid-gesture. That plumbing is most of the work, and it is why the agent can be trusted with a payment screen.

---

## Install

Download the signed APK from [Releases](https://github.com/namanwtf/atom/releases).

If Atom is already installed with the same signing key, installing over it **keeps your keys, models and permissions** — no reconfiguration. Never uninstall first.

---

## Build

CI is the compiler: every push to `main` runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (JDK 25, Android SDK, `assembleRelease`, optional signing, release publishing).

Locally:

```bash
./gradlew assembleDebug
```

On-device Gradle builds are impractical; use the CI artifact.

---

## Roadmap

- Wake word — always-listening activation without touching the bubble.
- On-device and cross-device agent hand-off.
- A proper plugin surface for third-party tools, beyond MCP and skills.
- End-to-end verification of the LSPosed hooks under Atom's own identity.

Directions, not promises. Anything shipped first appears in the commit log.

---

## Credits

Atom's agent foundation grows out of [Eta](https://github.com/Mangi-11/Eta) by Mangi-11 (GPL-3.0): its accessibility and root layers and the virtual display design. The floating bubble's face, geometry and its Tesla-coil drag effect are adapted from [Buddy](https://github.com/ghorbelhamdi/buddy-android) (Apache-2.0), attributed in [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md).

Everything that makes Atom Atom — the English-first product, its identity, the update channel, the mini-chat panel, the model and privacy posture — is this project's own work.

GPL-3.0 — see [LICENSE](LICENSE).
