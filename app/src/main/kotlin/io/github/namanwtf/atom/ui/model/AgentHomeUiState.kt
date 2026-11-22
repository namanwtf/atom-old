package io.github.namanwtf.atom.ui.model

import androidx.compose.runtime.Immutable

/**
 * State for the first screen, AgentChatHome.
 * Currently identical in structure to AgentChatUiState; kept as a separate type to allow future home-specific fields.
 */
internal typealias AgentChatHomeUiState = AgentChatUiState

@Immutable
data class ActiveRunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val elapsedLabel: String,
    val currentStep: String,
)

@Immutable
data class RunSummaryUi(
    val runId: String,
    val status: RunStatusUi,
    val title: String,
    val timeLabel: String,
    val toolCount: Int,
    val durationLabel: String,
)

@Immutable
enum class RunStatusUi {
    Running,
    Success,
    Failed,
    Cancelled,
}
