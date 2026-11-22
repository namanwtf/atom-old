# HyperOS System Entry Integration

Atom places HyperOS system shortcut actions and launcher navigation gestures under `hook/hyperos/` respectively, dispatched through `ModuleMain` and `SystemServerHooks`. This document describes the source-level integration and does not imply completed on-device verification on HyperOS.

## Power Button

- Process: `system_server`, corresponding to the `system` module scope.
- Class: `com.miui.server.input.util.ShortCutActionsUtils`.
- Hooks: the `boolean`-returning `triggerFunction(String, String, Bundle, boolean)` and the five-parameter overload with an extra trailing `String`; at install time it checks the signature and the type of `mContext`.
- Trigger condition: the first argument is `launch_voice_assistant` and the second is `long_press_power_key` or `imperceptible_press_power_key`. The latter is the short-duration or pre-trigger power-key assistant path on some system versions.
- Execution: reads the current power-key target. The system default assistant is passed through directly; Gemini / Atom request the current user's digital-assistant session via `AssistantManager.showAssistantSession`. It returns `true` to consume the shortcut action and record a dedup window only when the call succeeds; on failure it calls the original method. When the same press re-dispatches `imperceptible_press_power_key` / `long_press_power_key` (or the four/five-parameter overload) within the dedup window, it directly returns `true` to consume it, avoiding launching the session repeatedly or falling back to the system default assistant. For repeated long-press release wakes beyond the system-side dedup window, Atom's floating-window session also merges duplicate presentations within its initial idle presentation window, avoiding replaying the entry light effect and preserving the initially captured screen context.

This entry takes over after the system has recognized the key action; it does not rewrite key timing or touch events, nor modify system power-button settings. Power menu, SOS, double-press camera, smart home, and non-power-key assistant actions do not match the conditions and keep system logic. The current user must have enabled the power-key assistant shortcut and set the target as the default digital assistant; it reuses Atom's "Set default assistant automatically" switch and existing recovery mechanism. The HyperOS path has no plain Activity fallback, to avoid mistaking a session failure for the assistant having already been woken.

The system private dispatch signature has source-usage evidence, and the assistant function name matches the system setting that XiaoAI reads; without the framework / services APK of the current target ROM, we cannot assert that the call chains, fields, and press-and-release timing are the same across all HyperOS versions. Unknown classes, fields, and signatures are diagnosed through install reports rather than being hooked by fuzzy method names.

## Bottom Gesture Handle

The process is the main process of `com.miui.home` or `com.mi.android.globallauncher`; the launcher scope that is actually installed must be selected. `ModuleMain` only keeps these main processes and installs only when the first package is ready, without injecting business hooks into launcher subprocesses.

The following targets are tried in order; once one is registered successfully, it stops trying the remaining entries:

| Order | System class and method | Context source |
| --- | --- | --- |
| 1 | `com.miui.home.recents.gesture.NavStubGestureEventManager.handleLongPressEvent()` | `com.miui.home.launcher.Application.getInstance()` |
| 2 | `com.miui.home.recents.cts.CircleToSearchHelper.invokeOmni(Context, int, int)` | first argument |
| 3 | `com.miui.home.recents.cts.NavBarEventHelper.onLongPress(MotionEvent)` | instance `mContext` |
| 4 | `com.miui.home.recents.NavStubView.onTouchEvent(MotionEvent)` | the View's own Context |

The first three entries reuse the launcher's own long-press recognition and only replace the search dispatch. When "Gesture-handle long-press triggers Circle to Search" is off, context is missing, the Google entry is missing, or the service call fails, the original method runs. `invokeOmni` only accepts `void` / `boolean` return types and returns the corresponding empty value / `true` on success. Navigation search uniformly uses entry point `1`; other vendor calls keep their original entry point.

The legacy touch detection is enabled only when none of the first three entries are installed and `NavStubView` has no `mCheckLongPress`. Launchers that already have native detection with unknown callbacks do not get a second layer of detection.

- Each View manages its gesture and delayed task independently; it uses the system long-press timeout and touch-slide threshold.
- Sliding past the threshold, multi-touch, lifting the finger, cancellation, and view removal all cancel the task; the switch is re-read before delayed execution.
- After a successful trigger, the next event is converted to `ACTION_CANCEL` to end the native gesture; a failed search keeps the original event.
- `startRecentsAnimationPre()` is suppressed only while the legacy gesture is waiting for a long press. Movement past the threshold releases it; recents pre-launch is not globally disabled.
- Delayed tasks use a weak reference to the view, do not hold the Hook Chain, and add no threads or polling.

## HyperOS 4 Navigation Screen-Search Service Entry

On Android 17 / HyperOS 4, a long press on the handle can be handed directly to Super XiaoAI screen search, bypassing the launcher callbacks above. In the `com.miui.voiceassist` package process, Atom installs only the clearly-structured `HyperOsScreenSearchHooks`, hooking `com.xiaomi.voiceassistant.VoiceService.onStartCommand(Intent, int, int)` to forward navigation screen-search requests to `contextual_search` without taking over the conversation business.

Search dispatch is replaced only when the existing gesture switch is on and the request satisfies all of the following:

- The Action is `android.intent.action.ASSIST`.
- `voice_assist_function_key` is `start_screen_recognition`.
- `triggerType` is `NavLongPress`.
- `voice_assist_start_from_key` is `long_press_fullscreen_gesture_line`, `long_press_home_key`, or `two_gesture_long_press`. These sources are treated as navigation screen search only when they match the first two conditions simultaneously; they cannot intercept ordinary assistant requests based on the source alone.

Empty Intents, missing fields / mismatched types, and other functions and sources are all passed through. If the search request fails to send, the original `onStartCommand` runs; on a successful send it returns `START_NOT_STICKY`, and only removes the foreground state when `stopSelfResult(startId)` confirms this is still the latest start, to avoid stopping already-queued subsequent starts. Cleanup failures record throttled diagnostics without re-waking the original screen-search UI. The original Intent, image, and other extras are not forwarded to Google; search parameters are still generated deterministically by Atom.

Enablement conditions:

1. In LSPosed's Atom scope, check "Super XiaoAI" (`com.miui.voiceassist`), keep the `system`, Google App, and the launcher scope actually in use, and reboot to make the new hooks effective.
2. In HyperOS system navigation, set "Long-press gesture hint line" to "Super XiaoAI screen search".
3. Enable Atom's "Gesture-handle long-press triggers Circle to Search" and make sure Google App is installed and initialized.

When the system setting does not point to XiaoAI screen search, there is no guarantee that the above service request will be produced. Android 17's `startContextualSearch(int, ContextualSearchConfig)` is already supported by the shared invoker, passing `null` for the config parameter, while the older one-parameter signature is still kept. Interface compatibility does not mean the entry is connected; diagnostics must inspect both `HyperOsScreenSearch` and `ContextualSearch` logs.

## Search Service and Permissions

Both the launcher and XiaoAI screen-search service entries reuse Atom's `CircleToSearchInvoker`, call the `contextual_search` Binder, skip native screen-search processing, and do not run the Agent Runtime in the launcher or XiaoAI hooks.

`ContextualSearchHooks` is responsible for:

1. Correcting `SystemServer.deviceHasConfigString(Context, int)`'s check of `config_defaultContextualSearchPackageName` to report launchable; other resource checks follow original logic.
2. Keeping the service re-start at the tail of `startOtherServices`; it does not start again when the service already exists.
3. Pointing `ContextualSearchManagerService.getContextualSearchPackageName()` to Google App.
4. Extending trusted HyperOS UIDs only for `enforcePermission("startContextualSearch")`: the calling UID's package list must contain the launcher package above or `com.miui.voiceassist`, the corresponding package must be a system app or system-app update, and the gesture switch must be on. Existing SystemUI and ColorOS screen-search callers keep their original permission rules.

The Binder oneway call provides no confirmation of whether the UI is shown; a successful return only means the request was sent, not that Google has displayed the search interface. Permissions are decided by UID; other processes under a shared UID share the same permission boundary.

The current implementation does not provide the legacy `voiceinteraction.showSessionFromSession` plus resource-override search bypass, nor does it remove identity verification for the whole voice-interaction service. If the ROM lacks `ContextualSearchManagerService`, this module alone cannot supply an implementation that does not exist in the system, and the original trigger logic is kept.

## Known Boundaries and On-Device Verification

- `MiuiSingleKeyRule.supportLongPress()` / `onLongPress(long)` with `mKeyCode == KEYCODE_HOME` is a separate Home-key long-press path, not a power-key or handle event. Atom does not hook that low-level key path; the service only processes the compatible sources within it when it receives an explicit `NavLongPress` screen-search request.
- A class existing does not mean the callback is actually used by the current launcher. A native entry that exists but is blocked by regional eligibility switches, the recents animation during the native long press, and the international launcher's package and process layout all need per-version verification.
- Google App eligibility patching continues to use the existing Google Hook; no additional launcher device spoofing is added.
- Two-finger screen search and ColorOS's lock-screen voice and hotword compensation have no new HyperOS-specific implementation.
- Config keys, defaults, and the storage protocol are unchanged; after adding a scope you must check it in the module manager and reboot, as existing processes do not automatically gain the new hooks.

On-device verification should cover: the three targets (system default / Gemini / Atom), a target not set as default or not installed, the original power-off and SOS actions, stationary long-press and slide on the handle, multi-touch and lift-to-cancel, portrait/landscape switching, switch-off, and recovery after reboot. Record the ROM / Android / launcher / XiaoAI versions and the `HyperOsPower`, `HyperOsLauncher`, `HyperOsScreenSearch`, `ContextualSearch` install reports, to distinguish a missing target, an entry not being called, and a Binder / Google-side failure.
