# Standard and Rooted Devices

Atom supports Android 14 and above on ARM64 (arm64-v8a) devices, using a single APK and providing capabilities based on system version and actual authorization. The APK contains only ARM64 native libraries and provides no 32-bit ARM, x86, or x86_64 builds. Core features do not wait for root probing; root authorization and framework-service connection are shown separately. Authorization denial, disconnection, or recovery never overwrite the toggles the user saved.

The "Framework communication" row on the System enhancements page only indicates that a framework-service Binder was received; it does not mean the Atom switch in the LSPosed Manager is on or that hooks have taken effect. The current libxposed Service interface has no module-switch query or change notification; after turning the module off, an existing service connection may still be retained. The module switch and scope are authoritative from the manager, and specific features also depend on loading in the target process. The running-target list cannot substitute for the module switch either: an empty list only means no running targets were returned.

## Capability Boundaries

| Feature | Standard device | Enhancement condition |
| --- | --- | --- |
| Chat, models, memory, Skills, MCP, built-in browser | Available; runs per their own switches and configuration | None |
| Screen context of the digital-assistant overlay | Once Atom is set as the default digital assistant and allowed to use screen and app data, it automatically attaches the screenshot and app content from wake-up | Does not require Root or accessibility; still subject to system sharing settings and protected windows |
| GUI nodes, gestures, input, and waiting | Enable Atom's accessibility service | LSPosed accessibility protection can provide limited rebinding |
| Background virtual-display execution and main-screen handoff | Automatically falls back to foreground operation on the physical main screen | With Root or LSPosed (the `system` scope) enabled and focus-isolation and IME-policy checks passed, supports the trusted background virtual display, the capsule mini viewfinder, and in-place full-screen handoff |
| GUI per-window screenshot | Enable Atom's accessibility service | Root screenshot falls back only when existing conditions such as no entry window to exclude are met |
| Launching apps, opening links, alarms, and timers | Uses Android foreground Intents | Accessibility is not a precondition |
| Current notifications and notification history | Grant notification access; current notifications require the listener service to be connected | Root users keep the existing system source |
| App usage, location | Grant the corresponding Android access permissions | Background location requires Always allow |
| Android Shell, files, and images | App UID, private workspace, or authorized sources | Root users keep privileged paths |
| Alpine, Debian, PTY | Runs via PRoot | chroot can be installed separately |
| System modification, freezing apps, private-data reads | Not exposed to the model | Requires Root; some data also requires the corresponding ROM |
| System assistant routing and Circle to Search | Can be viewed under System enhancements | Requires LSPosed and the corresponding ROM (system, SystemUI, and Google scopes) |

The tools page defaults to "This device". Features whose ordinary Android permissions are not yet enabled can still be discovered; "All capabilities" shows additional descriptions and actual conditions, and viewing does not request permissions or expand the model's permissions. The System enhancements entry uses an ordinary settings row and does not persistently prompt about missing authorization. Root users keep their existing configuration location; when disconnected, used configurations are retained, no reconnect card is shown at the top of the settings page, and framework-communication status can be viewed on the System enhancements page.

This release adds no new ordinary-permission read paths for data such as contacts, SMS, and calendar.

When per-window screenshots are unavailable, screen observation can still return nodes and screen dimensions and indicate the screenshot status. The default digital assistant receives the system snapshot from wake-up. Accessibility-protection control broadcasts verify identity using the sender UID provided by the system.

The background virtual display requires system support for independent window focus, top-focus protection, and hidden IME. Devices lacking these abilities run on the main screen even with Root authorized. If the virtual display is removed by the system or the bridge process exits mid-run, the current canvas becomes invalid and reports `DISPLAY_UNAVAILABLE`; observation and operation can resume only after restarting the target app, and actions on the old canvas are not automatically transferred to the main screen.

Android 17 adds a runtime permission for accessing the local network. Before connecting to LAN model services, MCP, web pages, or accessing the LAN through the ordinary terminal, enable "Local network access" on Atom's permissions page; after denial or revocation you can re-authorize from that entry or go to app settings. Public-network access does not require this permission. This permission also applies to Atom's WebView and to the network requests of the ordinary terminal and PRoot running under the app UID.

## Files and Linux Data

A new ordinary workspace is located at `filesDir/terminal-user/workspace`, and the PRoot environment at `filesDir/terminal-user/proot/<distribution>`, avoiding the old `filesDir/terminal` parent directory that could be created by Root. Ordinary workspaces and PRoot environments that already exist under the old layout keep their original location and are not migrated or re-owned; directory choice does not depend on Root authorization. The workspace is still mapped as `/workspace` inside Linux. Android file and command tools use the same private workspace; when Root authorization is confirmed the default is to use Root directly, otherwise the app UID, and an explicit `identity=user` selects the ordinary identity. The workspace page supports listing and exporting Root-generated files through the Root file backend. No extra file-path whitelist is set; whether other paths are readable/writable is decided by system permissions. Each run fixes the working directory and selected Linux environment; gaining or losing Root does not automatically switch the workspace. If a URI returned by the file picker cannot be used as an app-UID-readable path, it is first imported into the workspace with bounds and then referenced; directories that cannot be imported directly get an explicit explanation. Public directory sharing requests "All files access" on demand; after denial, the private workspace and import/export still work.

PRoot and chroot use independent rootfs images. The old chroot, `/data/local/tmp/eta`, and privileged shared mounts are not migrated; running sessions and tasks are pinned to the backend and path at creation. Root status changes do not delete environments, change ownership, or automatically switch existing sessions. The simulated root inside PRoot has no Android root permissions.

The rootless installer reuses the pinned-rootfs download verification, streams unpacking into a temporary directory, handles archive paths, links, cancellation, and failure cleanup, and writes the completion marker only after a successful runtime check. Base tools, Python/uv, Node.js, SSH, and APK analysis are installed and checked through the selected backend.

## Running and Stopping

The ordinary terminal, background commands, and the PRoot tracer maintain their lifecycle through task references held by an execution foreground service. Background commands do not stop when the agent's reply ends; they send a system notification on completion, and logs and status can be viewed from the terminal page or later tool calls; results are kept even when notifications are off. Leaving the page does not stop these tasks; they can be stopped proactively via the task entry and execution notification. The foreground service is released after the last task ends.

The Root daemon keeps its original independent lifecycle and is not bulk-recycled by the ordinary task service.

Notification-permission denial does not directly block a legitimate start. Foreground services are still subject to Android's [background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and vendor process management; when a start is rejected, it returns to Atom for retry. Commands are not automatically replayed after a force stop or reboot.

## Verification Boundaries and On-Device Checklist

Source regression covers tool-metadata integrity, ordinary capability projection, consistency between the per-turn tool catalog and the permission projection, mid-run permission revocation, accessibility disconnection, archive unpacking, PRoot command reference, backend selection, task references, and routing round-trips. Building and unit tests are not equivalent to successfully running native binaries on Android.

PTY, PRoot, and the paired loader are built for the minimum supported system and provided only as ARM64. The ELF's 16 KiB alignment, system dynamic dependencies, and source-archive consistency need to be checked together with each rebuild. Android 14 minimum-version compatibility and Android 17's LAN authorization and audio behavior still require device acceptance; unit tests cannot replace these runtime verifications.

The directory-isolation fix is additionally covered by 32 targeted tests, covering an unwritable old parent directory, in-place reads of old ordinary data, file import, install unpacking, backend selection, and the ordinary terminal. On an Android 16 arm64 device, it has been verified that when the old `terminal` is Root-owned and unwritable, the new workspace can be created under the app UID, the Debian PRoot base environment installs successfully, the workspace page reads correctly, and test files can be read/written in both directions between host and guest. The old chroot directory's inode, owner, permissions, size, modification time, and ready-marker hash remain consistent before and after verification. Full tool installation, long-term background running, and the complete Root-authorization switch matrix for that device are not yet covered.

The following full acceptance is executed on Android 14 and Android 17 devices according to the capability boundaries:

- No Root, Root only, LSPosed only, and both: inspect the tool catalog and System enhancements; verify denial, the 30-second timeout, revocation, disconnection, and recovery; confirm switches are not reset, and viewing all capabilities does not trigger authorization prompts.
- With accessibility enabled, complete node clicks, Chinese input, and waiting; verify per-window screenshots, node observation when screenshots fail, and Root fallback under window-exclusion conditions. Report errors immediately on mid-run disconnection; do not blindly replay after nodes go stale or action results are uncertain.
- On Android 17, verify the connection behavior of models, MCP, WebView, and the ordinary terminal after LAN-permission denial, grant, and revocation, and that public-network requests are unaffected by this permission.
- Import files after denying public-file access, read/write in the private workspace and export; after authorization, check bidirectional read/write of shared directories.
- Install Alpine and Debian PRoot separately on an ARM64 device; verify download failure, insufficient space, cancellation, reinstallation, and base-tool and optional-tool installation. Root devices also regress the old chroot and shared mounts.
- Run PTY, multiple sessions, and background commands; verify window resizing, Ctrl-C, natural exit, stop notification, process reclamation, and logs. Stopping ordinary tasks does not affect the Root daemon.
- Tool-view switching and detail-back restore the reading position; re-entering defaults to the current device. Verify narrow screens, large fonts, landscape, light/dark, system bars, and IME.
- After force stop and reboot there is no automatic command replay; state can be refreshed and manually restarted; saving model configuration shows no LSPosed sync prompt that an ordinary user cannot handle.
