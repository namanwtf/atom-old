package io.github.namanwtf.atom.agent.runtime

import android.os.SystemClock
import io.github.namanwtf.atom.agent.accessibility.AgentAccessibilityService
import io.github.namanwtf.atom.agent.accessibility.PackageWindowVisibility
import io.github.namanwtf.atom.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolates the external entry from the real operation target of the foreground tool.
 *
 * The close action, window-stability confirmation, and first-screenshot exclusion share the same entry description, so each layer does not guess the entry state independently.
 */
internal class EntrySurfaceGuard private constructor(
    internal val targetPackageName: String?,
    private val logger: AgentLogger,
    private val ownedSurfaceDismissal: (() -> Boolean)?,
) {
    private val triggered = AtomicBoolean(false)
    private val dismissalCompleted = AtomicBoolean(false)
    // The accessibility window may disappear before the exiting Surface; it must be consumed by the first screenshot after close, not cleared on close confirmation.
    private val screenshotExclusionPending = AtomicBoolean(targetPackageName != null)

    val wasTriggered: Boolean
        get() = triggered.get()

    fun dismissOnce(): Boolean {
        if (dismissalCompleted.get()) return true
        if (!triggered.compareAndSet(false, true)) return dismissalCompleted.get()
        ownedSurfaceDismissal?.let { dismiss ->
            val startedAt = System.nanoTime()
            val completed = runCatching(dismiss).getOrDefault(false)
            val waitedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            if (completed) {
                dismissalCompleted.set(true)
                logger.debug {
                    "Agent runtime owned entry surface dismissed before foreground operation " +
                        "waitedMs=$waitedMillis"
                }
            } else {
                // Closing our own entry is an idempotent targeted operation; a retry confirmation is allowed after failure, and it will not accidentally dismiss the underlying app.
                triggered.set(false)
                logger.warn(
                    "Agent runtime owned entry surface dismiss incomplete before foreground " +
                        "operation: waitedMs=$waitedMillis"
                )
            }
            return completed
        }

        val service = AgentAccessibilityService.current()
        if (service == null) {
            triggered.set(false)
            logger.warn("Agent runtime entry surface dismiss skipped: accessibility service unavailable")
            return false
        }

        val packageName = targetPackageName
        val visibility = packageName?.let(service::packageWindowVisibility)
        when (EntrySurfaceDismissPolicy.decide(packageName, visibility)) {
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE -> {
                if (!service.awaitPackageWindowGone(packageName!!)) {
                    triggered.set(false)
                    logger.warn(
                        "Agent runtime entry surface absence was not stable; keep screenshot " +
                            "exclusion and retry later: package=$packageName",
                    )
                    return false
                }
                dismissalCompleted.set(true)
                logger.debug {
                    "Agent runtime entry surface already gone before foreground operation " +
                        "package=$packageName"
                }
                return true
            }
            EntrySurfaceDismissPolicy.Decision.DEFER -> {
                triggered.set(false)
                logger.warn(
                    "Agent runtime entry surface visibility unknown; keep screenshot exclusion " +
                        "and retry later: package=$packageName",
                )
                return false
            }
            EntrySurfaceDismissPolicy.Decision.SEND_BACK -> Unit
        }
        val startedAt = SystemClock.elapsedRealtime()
        val actionResult = service.globalActionResult("BACK")
        val windowGone = packageName?.let(service::awaitPackageWindowGone) ?: actionResult.ok
        val waitedMillis = SystemClock.elapsedRealtime() - startedAt
        val completed = if (packageName == null) actionResult.ok else windowGone

        if (completed) {
            dismissalCompleted.set(true)
            logger.debug {
                "Agent runtime entry surface dismissed before foreground operation " +
                    "package=$packageName visibilityBefore=$visibility waitedMs=$waitedMillis"
            }
        } else {
            logger.warn(
                "Agent runtime entry surface dismiss incomplete before foreground operation: " +
                    "actionCode=${actionResult.code} windowGone=$windowGone package=$packageName " +
                    "visibilityBefore=$visibility waitedMs=$waitedMillis"
            )
        }
        return completed
    }

    fun consumeScreenshotExcludedPackages(): Set<String> {
        val packageName = targetPackageName ?: return emptySet()
        return if (screenshotExclusionPending.compareAndSet(true, false)) {
            setOf(packageName)
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(
            handoff: AgentRuntimeWire.EntryHandoff?,
            logger: AgentLogger,
            etaVoiceSurfaceDismissal: (() -> Boolean)? = null,
        ): EntrySurfaceGuard? {
            if (handoff?.dismissEntrySurfaceOnForegroundOperation != true) return null
            val packageName = when (handoff.source) {
                AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE -> ATOM_PACKAGE_NAME
                else -> null
            }
            val ownedSurfaceDismissal = etaVoiceSurfaceDismissal.takeIf {
                handoff.source == AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE
            }
            return EntrySurfaceGuard(packageName, logger, ownedSurfaceDismissal)
        }

        private const val ATOM_PACKAGE_NAME = "io.github.namanwtf.atom"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object EntrySurfaceDismissPolicy {
    enum class Decision {
        ALREADY_GONE,
        SEND_BACK,
        DEFER,
    }

    fun decide(
        targetPackageName: String?,
        visibility: PackageWindowVisibility?,
    ): Decision = when {
        targetPackageName == null -> Decision.SEND_BACK
        visibility == PackageWindowVisibility.GONE -> Decision.ALREADY_GONE
        visibility == PackageWindowVisibility.VISIBLE -> Decision.SEND_BACK
        else -> Decision.DEFER
    }
}
