package io.github.namanwtf.atom.ui.preview

import io.github.namanwtf.atom.ui.model.AgentChatHomeUiState
import io.github.namanwtf.atom.ui.model.AgentChatMessageUi
import io.github.namanwtf.atom.ui.model.AgentChatUiState
import io.github.namanwtf.atom.ui.model.AgentToolsUiState
import io.github.namanwtf.atom.ui.model.ConversationModeUi
import io.github.namanwtf.atom.ui.model.ConversationPaneUiState
import io.github.namanwtf.atom.ui.model.ConversationSummaryUi
import io.github.namanwtf.atom.ui.model.PermissionHealthItemUi
import io.github.namanwtf.atom.ui.model.PermissionHealthUiState
import io.github.namanwtf.atom.ui.model.PermissionStatusUi
import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.TokenUsageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityStatusUi
import io.github.namanwtf.atom.ui.model.ToolGroupUi
import io.github.namanwtf.atom.ui.model.ToolItemUi
import io.github.namanwtf.atom.ui.model.UserMessageUi
import io.github.namanwtf.atom.ui.model.AgentMessageUi

internal object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "Let Atom operate the phone",
                preview = "Ready to take over the screen and tools, waiting for the next task",
                timeLabel = "Now",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "Today's schedule and reminders",
                preview = "Check the weather, sync the schedule, and set an outing reminder",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "Open NetEase Cloud Music to play the daily recommendations",
                preview = "Completed 4 tool calls in 8 seconds",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "Analyze the current screen capture",
                preview = "Summarize the page structure and point out button hierarchy issues",
                timeLabel = "Yesterday",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "Check the Alpine terminal environment",
                preview = "List available commands, Python/Node versions, and background jobs",
                timeLabel = "Friday",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "Tidy the download folder every night",
                preview = "Scan files on schedule and sort images, installers, and documents",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "Open battery optimization in Settings",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "The phone screen currently shows the **home screen**. Key info below:\n\n| Item | Content |\n| --- | --- |\n| Time | 09:55 |\n| Network | 5G + Wi-Fi |\n| Battery | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "The user wants me to look at the current screen; I need to call observe_screen first to get the screen structure, then summarize the visible information.",
                isStreaming = false,
                elapsedSeconds = 24,
                collapsed = true,
            ),
            ToolActivityMessageUi(
                id = "tools-01",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"include_screenshot\":true}",
                resultSummary = "ok=true, chars=1820, images=1",
                imageCount = 1,
            ),
        ),
        input = "",
        isStreaming = false,
        thinkingEnabled = true,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "Accessibility service",
                summary = "Enabled, the Agent can operate the UI",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "Overlay permission",
                summary = "Granted, can show a running overlay",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "Notification permission",
                summary = "Used for background task completion reminders",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "Location permission",
                summary = "Read only when the Agent calls tools",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root permission",
                summary = "Not granted, some terminal commands are restricted",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "Check",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "Not configured, ADB-level capabilities unavailable",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "Configure",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "Framework not activated, system enhancement unavailable",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "View",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "Background keep-alive",
                summary = "Battery optimization is not disabled, long tasks may be interrupted",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "Settings",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "Screen actions",
                tools = listOf(
                    ToolItemUi("observe_screen", "Observe screen", "Read UI nodes, attach the screenshot when necessary"),
                    ToolItemUi("tap_element", "Tap", "Tap the given coordinate or element"),
                    ToolItemUi("long_press", "Long press", "Long-press the given element"),
                    ToolItemUi("swipe", "Swipe", "Swipe, scroll, back, and other gestures"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "Input and keys",
                tools = listOf(
                    ToolItemUi("input_text", "Input text", "Type text at the focus"),
                    ToolItemUi("paste_text", "Paste text", "Write text via the clipboard"),
                    ToolItemUi("wait_for_text", "Wait for text", "Wait until the given text appears on screen"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "Web browsing",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent browser", "Browse off-screen while keeping a takeover-able session"),
                    ToolItemUi("browser_read", "Read page", "Extract the rendered page text"),
                    ToolItemUi("browser_interact", "Interact", "Find, click, and type into elements"),
                    ToolItemUi("browser_screenshot", "Page screenshot", "Hand the page viewport to the vision model"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "App and URI",
                tools = listOf(
                    ToolItemUi("get_current_context", "Time and location", "Read system time and last known location"),
                    ToolItemUi("launch_app", "Open app", "Launch an app by package name"),
                    ToolItemUi("open_uri", "Open with app", "Explicitly hand off to an external app"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "Terminal and files",
                tools = listOf(
                    ToolItemUi("terminal", "Terminal command", "Run shell in Android/Alpine/Debian environments"),
                ),
            ),
        ),
    )
}
