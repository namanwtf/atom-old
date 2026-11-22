package io.github.namanwtf.atom.agent.overlay

import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeWire
import io.github.namanwtf.atom.agent.runtime.GuiControlStatus
import io.github.namanwtf.atom.agent.tool.AgentToolRequirements

/** The overlay only follows actual phone control; the model generating tool arguments does not mean the foreground can already be operated. */
internal object AgentOverlayVisibilityPolicy {
    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.GuiControlChanged -> event.status == GuiControlStatus.Acquired
        is AgentEvent.ToolFinished -> isForegroundOperationTool(event.name)
        is AgentEvent.ToolImagesAttached -> isForegroundOperationTool(event.toolName)
        else -> false
    }

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean =
        event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired

    internal fun isForegroundOperationTool(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name)

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        AgentToolRequirements.requiresGuiControl(name) || AgentToolRequirements.hasForegroundIntentFallback(name)

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
    ): Boolean = entrySurfaceReady && event is AgentEvent.ToolFinished &&
        requiresEntrySurfaceDismissal(event.name) && event.success != false

    /**
     * For tasks initiated from the app's own chat page, when the user is still on the Atom foreground and the background virtual display is not enabled, no system overlay is stacked,
     * avoiding covering the conversation stream that is already showing the live process; if the background virtual display is active, keep the top micro-lens capsule so the user can expand to view or take over at any time.
     */
    internal fun shouldSuppressOverlayInAtomUi(
        handoffSource: String?,
        etaUiInForeground: Boolean,
        virtualDisplayActive: Boolean = false,
    ): Boolean = !virtualDisplayActive &&
        etaUiInForeground &&
        handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE

    internal fun shouldShowResultCard(
        hasExecutedForegroundTool: Boolean,
        handoffSource: String?,
        etaUiInForeground: Boolean,
    ): Boolean = hasExecutedForegroundTool && !shouldSuppressOverlayInAtomUi(handoffSource, etaUiInForeground, virtualDisplayActive = false)
}
