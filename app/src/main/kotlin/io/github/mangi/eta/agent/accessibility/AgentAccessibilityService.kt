package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.device.ScrollAxis
import io.github.mangi.eta.agent.device.ScrollAxisContract
import io.github.mangi.eta.agent.device.ScrollAmount
import io.github.mangi.eta.agent.device.ScrollDirection
import io.github.mangi.eta.agent.device.ScrollEvidence
import io.github.mangi.eta.agent.device.ScrollEvidenceContract
import io.github.mangi.eta.agent.device.ScrollMovementSource
import io.github.mangi.eta.agent.device.RootScrollMotionContract
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private data class ScreenshotWindow(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val active: Boolean,
        val focused: Boolean,
    )

    private data class NodeTraversalState(
        val maxVisitedNodes: Int,
        val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
        var visitedNodes: Int = 0,
        var truncated: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = SCREENSHOT_EXECUTOR
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private val scrollEventLock = ReentrantLock()
    private val scrollEventArrived = scrollEventLock.newCondition()
    private val scrollActionLock = ReentrantLock()
    private var scrollEventSequence = 0L
    private val recentScrollSignals = ArrayDeque<ScrollSignal>()
    private val scrollEventObservationGate = ScrollEventObservationGate(
        uptimeMillis = SystemClock::uptimeMillis,
    )
    // A remote node query may hold the thread for seconds; keep only one latest candidate and forbid scroll events from piling up.
    private val scrollEventExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SCROLL_EVENT_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "agent-scroll-event").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val windowContentGenerations = WindowContentGenerations()
    private val queryExecutor = AccessibilityQueryExecutor()
    @Volatile
    private var serviceToken = SERVICE_TOKENS.incrementAndGet()

    override fun onServiceConnected() {
        serviceToken = SERVICE_TOKENS.incrementAndGet()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCurrentInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCurrentInstance()
        scrollEventExecutor.shutdownNow()
        queryExecutor.close()
        super.onDestroy()
    }

    private fun clearCurrentInstance() {
        if (instance === this) instance = null
        serviceToken = SERVICE_TOKENS.incrementAndGet()
        scrollEventObservationGate.clear()
        windowContentGenerations.clear()
        signalWindowChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                    event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0
                ) {
                    windowContentGenerations.remove(event.windowId)
                } else {
                    bumpWindowContentGeneration(event.windowId)
                }
                signalWindowChanged()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                bumpWindowContentGeneration(event.windowId)
                observeScrollEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                bumpWindowContentGeneration(event.windowId)
        }
    }

    override fun onInterrupt() = Unit

    /**
     * A single observation queries only the target display; window, package name, and nodes all
     * come from the same window selection. Node actions must pass back the same snapshot instance
     * so another run or wait_for_text cannot change what an index means.
     */
    fun observeNodeSnapshot(
        maxNodes: Int,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
        includeNodes: Boolean = true,
        timeoutMillis: Long = 3_000L,
    ): NodeSnapshotObservation {
        if (instance !== this) return NodeSnapshotObservation(NodeSnapshotStatus.SERVICE_UNAVAILABLE)
        // The synchronous API is only for Runtime worker threads; it must not bring waiting on a background Binder back onto the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return NodeSnapshotObservation(NodeSnapshotStatus.BUSY)
        }
        val connectionToken = serviceToken
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(1L, 3_000L)
        val retryDeadline = minOf(deadline, startedAt + 850L)
        while (true) {
            val result = queryExecutor.query(
                (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L),
                isActive = { instance === this && connectionToken == serviceToken },
            ) {
                captureNodeObservationOnce(maxNodes, displayId, includeNodes, connectionToken)
            }
            val observation = when (result) {
                is AccessibilityQueryExecutor.Result.Completed -> result.value
                is AccessibilityQueryExecutor.Result.Failed -> {
                    AndroidAgentLogger.warnThrottled("accessibility_query") {
                        "Agent accessibility action=observe_tree outcome=query_failed " +
                            "error=${result.error.javaClass.simpleName}"
                    }
                    NodeSnapshotObservation(NodeSnapshotStatus.QUERY_FAILED)
                }
                AccessibilityQueryExecutor.Result.Busy -> NodeSnapshotObservation(NodeSnapshotStatus.BUSY)
                AccessibilityQueryExecutor.Result.TimedOut -> NodeSnapshotObservation(NodeSnapshotStatus.TIMEOUT)
                AccessibilityQueryExecutor.Result.Closed -> NodeSnapshotObservation(NodeSnapshotStatus.SERVICE_UNAVAILABLE)
            }
            if (Thread.currentThread().isInterrupted) return NodeSnapshotObservation(NodeSnapshotStatus.TIMEOUT)
            if (instance !== this || connectionToken != serviceToken) {
                return NodeSnapshotObservation(NodeSnapshotStatus.SERVICE_UNAVAILABLE)
            }
            val retryable = observation.status == NodeSnapshotStatus.NO_WINDOW ||
                observation.status == NodeSnapshotStatus.ROOT_UNAVAILABLE ||
                observation.status == NodeSnapshotStatus.EMPTY
            val remaining = retryDeadline - SystemClock.elapsedRealtime()
            if (!includeNodes || displayId == android.view.Display.DEFAULT_DISPLAY || !retryable || remaining <= 0L) {
                return observation
            }
            try {
                Thread.sleep(minOf(120L, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return NodeSnapshotObservation(NodeSnapshotStatus.TIMEOUT)
            }
        }
    }

    fun captureNodeSnapshot(
        maxNodes: Int,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeSnapshot? = observeNodeSnapshot(maxNodes, displayId).snapshot

    private fun captureNodeObservationOnce(
        maxNodes: Int,
        displayId: Int,
        includeNodes: Boolean,
        connectionToken: Long,
    ): NodeSnapshotObservation {
        val startedAt = SystemClock.elapsedRealtime()
        val window = selectedWindowOnDisplay(displayId)
            ?: return NodeSnapshotObservation(NodeSnapshotStatus.NO_WINDOW)
        queryExecutor.ensureActive()
        val root = window.root
        queryExecutor.ensureActive()
        val observedWindow = ObservedWindow(
            packageName = root?.packageName?.toString()?.takeIf(String::isNotBlank),
            windowId = window.id,
            displayId = displayId,
            focused = window.isFocused,
            active = window.isActive,
        )
        if (root == null) return NodeSnapshotObservation(NodeSnapshotStatus.ROOT_UNAVAILABLE, window = observedWindow)
        if (!includeNodes) return NodeSnapshotObservation(NodeSnapshotStatus.AVAILABLE, window = observedWindow)
        val contentGeneration = windowContentGeneration(root.windowId)
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val indexedNodes = mutableListOf<IndexedNode>()
        val traversal = NodeTraversalState(
            maxVisitedNodes = (nodeLimit * UI_TREE_VISIT_MULTIPLIER)
                .coerceIn(MIN_UI_TREE_VISITED_NODES, MAX_UI_TREE_VISITED_NODES),
        )
        collectNodes(root, indexedNodes, nodeLimit, depth = 0, traversal = traversal)
        val snapshot = NodeSnapshot(
            id = "o${SNAPSHOT_IDS.incrementAndGet()}",
            serviceToken = connectionToken,
            packageName = root.packageName?.toString().orEmpty(),
            windowId = root.windowId,
            contentGeneration = contentGeneration,
            capturedAtElapsedMs = startedAt,
            truncated = traversal.truncated,
            indexedNodes = indexedNodes.toList(),
            displayId = displayId,
        )
        AndroidAgentLogger.debug {
            "Agent accessibility action=observe_tree observation=${snapshot.id} " +
                "display=$displayId nodes=${snapshot.nodes.size} visited=${traversal.visitedNodes} " +
                "truncated=${traversal.truncated} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }
        return NodeSnapshotObservation(
            status = if (snapshot.nodes.isEmpty()) NodeSnapshotStatus.EMPTY else NodeSnapshotStatus.AVAILABLE,
            snapshot = snapshot,
            window = observedWindow,
        )
    }

    /** A temporary query publishes no global node state; it is used by wait_for_text. */
    fun queryNodes(
        maxNodes: Int,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): List<UiNode> = captureNodeSnapshot(maxNodes, displayId)?.nodes.orEmpty()

    fun currentPackageName(displayId: Int = android.view.Display.DEFAULT_DISPLAY): String? =
        observeNodeSnapshot(maxNodes = 1, displayId = displayId, includeNodes = false).window?.packageName

    fun displaySize(displayId: Int = android.view.Display.DEFAULT_DISPLAY): Pair<Int, Int>? = runCatching {
        if (displayId != android.view.Display.DEFAULT_DISPLAY) {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val targetDisplay = dm?.getDisplay(displayId)
            if (targetDisplay != null) {
                val point = Point()
                @Suppress("DEPRECATION")
                targetDisplay.getRealSize(point)
                if (point.x > 0 && point.y > 0) return@runCatching point.x to point.y
            }
        }
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) point.x to point.y else null
    }.getOrNull()

    private fun selectedWindowOnDisplay(displayId: Int): AccessibilityWindowInfo? =
        windowsOnAllDisplays.get(displayId).orEmpty()
            .asSequence()
            .filter { window ->
                window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || window.isFocused || window.isActive)
            }
            // A background display never takes global focus; the window layer is more reliable than stale focused/active flags.
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.layer }
                    .thenByDescending { it.isFocused || it.isActive },
            )
            .firstOrNull()

    private fun activeRootOnDisplay(displayId: Int): AccessibilityNodeInfo? {
        val window = selectedWindowOnDisplay(displayId) ?: return null
        queryExecutor.ensureActive()
        return window.root
    }

    internal fun packageWindowVisibility(packageName: String): PackageWindowVisibility =
        runAccessibilityQuery {
            var inspectedRoot = false
            var hasUnknownRelevantWindow = false
            val displayWindows = windowsOnAllDisplays.get(android.view.Display.DEFAULT_DISPLAY).orEmpty()
            for (window in displayWindows) {
                queryExecutor.ensureActive()
                val root = window.root
                if (root == null) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || window.isActive || window.isFocused) {
                        hasUnknownRelevantWindow = true
                    }
                    continue
                }
                inspectedRoot = true
                if (root.packageName?.toString() == packageName) {
                    return@runAccessibilityQuery PackageWindowVisibility.VISIBLE
                }
            }
            if (inspectedRoot && !hasUnknownRelevantWindow) PackageWindowVisibility.GONE else PackageWindowVisibility.UNKNOWN
        } ?: PackageWindowVisibility.UNKNOWN

    /**
     * BACK only means the system accepted the exit action; the overlay usually still runs an exit
     * animation. Wait for the target package's window to actually disappear and stay stable for two
     * sampling periods, so the next screenshot is not taken before removeView runs.
     */
    fun awaitPackageWindowGone(
        packageName: String,
        timeoutMillis: Long = 1_000L,
        minimumWaitMillis: Long = 160L,
        stableMillis: Long = 80L,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceIn(200L, 2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            when (packageWindowVisibility(packageName)) {
                PackageWindowVisibility.VISIBLE,
                PackageWindowVisibility.UNKNOWN -> absentSince = 0L
                PackageWindowVisibility.GONE -> {
                    if (absentSince == 0L) absentSince = now
                    if (
                        now - startedAt >= minimumWaitMillis &&
                        now - absentSince >= stableMillis
                    ) {
                        return true
                    }
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun signalWindowChanged() {
        windowChangeLock.lock()
        try {
            windowChanged.signalAll()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun awaitWindowChanged(timeoutMillis: Long) {
        windowChangeLock.lock()
        try {
            windowChanged.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun observeScrollEvent(event: AccessibilityEvent) {
        scrollEventObservationGate.withMatchingObservation(
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            eventTimeMillis = event.eventTime,
        ) { observation ->
            // The system may clear the node cache after a scroll; source must copy the event and parse it in the background.
            val eventCopy = try {
                AccessibilityEvent(event)
            } catch (error: RuntimeException) {
                AndroidAgentLogger.warnThrottled("scroll_event_copy") {
                    "Agent accessibility action=observe_scroll_event " +
                        "outcome=copy_failed error=${error.javaClass.simpleName}"
                }
                return@withMatchingObservation
            }
            scrollEventExecutor.execute {
                if (!scrollEventObservationGate.isActive(observation)) return@execute
                val signal = resolveScrollSignal(eventCopy) ?: return@execute
                if (scrollEventObservationGate.isActive(observation)) {
                    recordScrollSignal(signal)
                }
            }
        }
    }

    private fun resolveScrollSignal(event: AccessibilityEvent): ScrollSignal? {
        val source = try {
            event.getSource(0)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warnThrottled("scroll_event_source") {
                "Agent accessibility action=resolve_scroll_event " +
                    "outcome=source_failed error=${error.javaClass.simpleName}"
            }
            null
        } ?: return null
        return ScrollSignal(
            sequence = 0L,
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            sourceUniqueId = source.uniqueId.orEmpty(),
            sourceViewId = source.viewIdResourceName.orEmpty(),
            sourceClassName = source.className?.toString().orEmpty(),
            sourceBounds = source.bounds(),
        )
    }

    private fun recordScrollSignal(signal: ScrollSignal) {
        scrollEventLock.lock()
        try {
            scrollEventSequence++
            recentScrollSignals.addLast(signal.copy(sequence = scrollEventSequence))
            while (recentScrollSignals.size > MAX_SCROLL_SIGNALS) {
                recentScrollSignals.removeFirst()
            }
            scrollEventArrived.signalAll()
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun bumpWindowContentGeneration(windowId: Int) {
        if (windowId >= 0) windowContentGenerations.advance(windowId)
    }

    private fun windowContentGeneration(windowId: Int): Long = windowContentGenerations.current(windowId)

    private fun <T> runAccessibilityQuery(block: () -> T): T? {
        if (instance !== this || Looper.myLooper() == Looper.getMainLooper()) return null
        val connectionToken = serviceToken
        return when (val result = queryExecutor.query(
            MAIN_SYNC_TIMEOUT_SECONDS * 1_000L,
            isActive = { instance === this && connectionToken == serviceToken },
            block = block,
        )) {
            is AccessibilityQueryExecutor.Result.Completed ->
                result.value.takeIf { instance === this && connectionToken == serviceToken && !Thread.currentThread().isInterrupted }
            is AccessibilityQueryExecutor.Result.Failed -> {
                AndroidAgentLogger.warnThrottled("accessibility_query") {
                    "Agent accessibility action=query outcome=failed error=${result.error.javaClass.simpleName}"
                }
                null
            }
            else -> null
        }
    }

    private fun currentScrollEventSequence(): Long {
        scrollEventLock.lock()
        return try {
            scrollEventSequence
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun awaitScrollSignal(
        afterSequence: Long,
        packageName: String,
        windowId: Int,
        targetIdentity: ScrollTargetIdentity,
        timeoutMillis: Long,
    ): ScrollSignal? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        scrollEventLock.lock()
        try {
            while (true) {
                val signal = recentScrollSignals.firstOrNull { candidate ->
                    candidate.sequence > afterSequence &&
                        candidate.windowId == windowId &&
                        candidate.packageName == packageName &&
                        candidate.matchesTarget(targetIdentity)
                }
                if (
                    signal != null
                ) {
                    return signal
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    scrollEventArrived.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            scrollEventLock.unlock()
        }
    }

    fun clickNode(snapshot: NodeSnapshot, index: Int): NodeActionResult =
        withValidatedIndexedNode(snapshot, index) { indexed ->
            val node = indexed.node
            val actionable = indexed.clickTarget?.resolveFor(node)
            if (indexed.clickTarget != null && actionable == null) {
                NodeActionResult.failure(
                    "STALE_ACTION_TARGET",
                    "The node's tappable target has changed; please re-observe the screen",
                )
            } else if (actionable != null) {
                when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK, connectionToken = snapshot.serviceToken)) {
                    ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_CLICK")
                    ActionDispatch.REJECTED ->
                        NodeActionResult.failure("ACTION_FAILED", "Target node rejected the tap action")
                    ActionDispatch.NOT_DISPATCHED -> NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility action was not dispatched; please re-observe the screen")
                    ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
                }
            } else {
                val bounds = clippedNodeBounds(node, snapshot.displayId)
                if (bounds.isEmpty) {
                    NodeActionResult.failure("INVALID_NODE_BOUNDS", "Target node has no tappable area")
                } else gestureTapOnConnection(
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 50L, snapshot.displayId, snapshot.serviceToken,
                )
            }
        }

    fun longClickNode(
        snapshot: NodeSnapshot,
        index: Int,
        durationMs: Long,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        val node = indexed.node
        val actionable = indexed.longClickTarget?.resolveFor(node)
        if (indexed.longClickTarget != null && actionable == null) {
            NodeActionResult.failure(
                "STALE_ACTION_TARGET",
                "The node's long-press target has changed; please re-observe the screen",
            )
        } else if (actionable != null) {
            when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK, connectionToken = snapshot.serviceToken)) {
                ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_LONG_CLICK")
                ActionDispatch.REJECTED ->
                    NodeActionResult.failure("ACTION_FAILED", "Target node rejected the long-press action")
                ActionDispatch.NOT_DISPATCHED -> NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility action was not dispatched; please re-observe the screen")
                ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            }
        } else {
            val bounds = clippedNodeBounds(node, snapshot.displayId)
            if (bounds.isEmpty) {
                NodeActionResult.failure("INVALID_NODE_BOUNDS", "Target node has no long-pressable area")
            } else {
                gestureTapOnConnection(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    durationMs = durationMs.coerceIn(300L, 3_000L),
                    displayId = snapshot.displayId,
                    connectionToken = snapshot.serviceToken,
                ).let { result ->
                    if (result.ok) result.copy(method = "GESTURE_LONG_PRESS") else result
                }
            }
        }
    }

    internal fun scrollNode(
        snapshot: NodeSnapshot,
        index: Int,
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.PAGE,
    ): ScrollActionResult {
        val validation = runAccessibilityQuery { validateNode(snapshot, index) }
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "SERVICE_TIMEOUT",
                message = "Accessibility node query not completed",
                targetIndex = index,
            )
        val validationNode = when (validation) {
            is NodeValidation.Invalid -> return ScrollActionResult.failure(
                direction = direction,
                code = validation.result.code,
                message = validation.result.message,
                targetIndex = index,
            )
            is NodeValidation.Valid -> validation.indexedNode
        }
        val scrollable = validationNode.scrollTarget?.resolveFor(validationNode.node)
        if (validationNode.scrollTarget != null && scrollable == null) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_ACTION_TARGET",
                message = "The node's scroll container has changed; please re-observe the screen",
                targetIndex = index,
            )
        }
        scrollable ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NOT_SCROLLABLE",
            message = "The specified node and its parent are not scrollable",
            targetIndex = index,
        )
        return executeScroll(scrollable, direction, targetIndex = index, amount = amount, displayId = snapshot.displayId, connectionToken = snapshot.serviceToken)
    }

    internal fun scrollCurrent(
        direction: ScrollDirection,
        amount: ScrollAmount = ScrollAmount.PAGE,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): ScrollActionResult {
        val connectionToken = serviceToken
        val target = runAccessibilityQuery {
            activeRootOnDisplay(displayId)?.let { root -> findBestScrollableNode(root, direction, displayId) }
        } ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NO_ACTIVE_WINDOW",
            message = "The current active window is inaccessible",
        )
        return executeScroll(target, direction, targetIndex = null, amount = amount, displayId = displayId, connectionToken = connectionToken)
    }

    private fun executeScroll(
        target: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetIndex: Int?,
        amount: ScrollAmount,
        displayId: Int,
        connectionToken: Long,
    ): ScrollActionResult = scrollActionLock.withLock {
        if (instance !== this || connectionToken != serviceToken) {
            return ScrollActionResult.failure(direction, "SERVICE_RECONNECTED", "Accessibility connection has changed; please re-observe the screen")
        }
        val startedAt = SystemClock.elapsedRealtime()
        val refreshed = runAccessibilityQuery { target.refresh() } == true
        if (!refreshed || !target.isVisibleToUser || !target.isEnabled) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_NODE",
                message = "The scroll target is no longer valid; please re-observe the screen",
                targetIndex = targetIndex,
            )
        }
        if (target.exposesOnlyOppositeAxis(direction)) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "AXIS_MISMATCH",
                message = "The target only supports the other scroll axis; to avoid an accidental side swipe, no action was performed this time",
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val packageName = target.packageName?.toString().orEmpty()
        val windowId = target.windowId
        val beforeAnchors = scrollContentAnchors(target)
        val targetIdentity = ScrollTargetIdentity.from(target)
        val beforeSequence = currentScrollEventSequence()
        // A node scroll action always pages by roughly one screen; small scrolls can only control displacement via gestures.
        val method = if (amount == ScrollAmount.PAGE) chooseScrollMethod(target, direction) else null
        var methodName = method?.name.orEmpty()
        return withScrollEventObservation(packageName, windowId) {
            val nodeDispatch = method?.let { selected ->
                performNodeAction(target, selected.actionId, selected.args, connectionToken)
            }
            if (nodeDispatch == ActionDispatch.NOT_DISPATCHED) {
                return ScrollActionResult.failure(direction, "SERVICE_TIMEOUT", "Scroll action was not dispatched; please re-observe the screen")
            }
            if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_OUTCOME_UNKNOWN",
                    message = "The scroll action may have been submitted, but the system did not return a result in time; re-observe first to avoid repeating the scroll",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            var accepted = nodeDispatch == ActionDispatch.ACCEPTED

            if (!accepted) {
                val boundaryBeforeGesture = runAccessibilityQuery {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundaryBeforeGesture) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                val bounds = clippedNodeBounds(target, displayId)
                val gesture = direction.gestureWithin(bounds, amount)
                    ?: return ScrollActionResult.failure(
                        direction = direction,
                        code = "INVALID_NODE_BOUNDS",
                        message = "The scroll target does not have enough usable area",
                        targetIndex = targetIndex,
                    )
                methodName = "GESTURE_SWIPE"
                val gestureResult = gestureSwipeOnConnection(
                    gesture.start.x.toFloat(),
                    gesture.start.y.toFloat(),
                    gesture.end.x.toFloat(),
                    gesture.end.y.toFloat(),
                    SCROLL_GESTURE_DURATION_MS,
                    displayId = displayId,
                    connectionToken = connectionToken,
                )
                if (!gestureResult.ok) {
                    return ScrollActionResult.failure(
                        direction = direction,
                        code = gestureResult.code,
                        message = gestureResult.message,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                accepted = true
            }
            if (!accepted) {
                val boundary = runAccessibilityQuery {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundary) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    )
                }
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "ACTION_FAILED",
                    message = "System refused the scroll action",
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }

            val signal = awaitScrollSignal(
                afterSequence = beforeSequence,
                packageName = packageName,
                windowId = windowId,
                targetIdentity = targetIdentity,
                timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
            )
            val afterAnchors = runAccessibilityQuery {
                if (target.refresh()) scrollContentAnchors(target) else emptyList()
            }.orEmpty()
            val eventDelta = signal?.axisDelta(direction.axis)?.takeIf { delta -> delta != 0 }
            val anchorDelta = inferScrollAnchorDelta(beforeAnchors, afterAnchors, direction)
            val delta = eventDelta ?: anchorDelta
            val movementSource = when {
                eventDelta != null -> ScrollMovementSource.EVENT
                anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
                else -> null
            }
            val boundary = runAccessibilityQuery {
                target.refresh() && isAtScrollBoundary(target, direction, signal)
            } == true
            val evidence = ScrollEvidenceContract.classify(
                direction = direction,
                delta = delta,
                movementSource = movementSource,
                atBoundary = boundary,
            )
            if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
                return ScrollActionResult.failure(
                    direction = direction,
                    code = "DIRECTION_MISMATCH",
                    message = "The UI moved in the opposite direction of the request",
                    method = methodName,
                    targetIndex = targetIndex,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (
                evidence == ScrollEvidence.MOVED_BY_EVENT ||
                evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
            ) {
                return ScrollActionResult(
                    ok = true,
                    direction = direction,
                    moved = true,
                    atBoundary = boundary,
                    method = methodName,
                    deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                    deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                    verifiedBy = if (evidence == ScrollEvidence.MOVED_BY_EVENT) {
                        "scroll_event"
                    } else {
                        "anchor_motion"
                    },
                    elapsedMs = elapsedMs,
                    targetIndex = targetIndex,
                )
            }
            if (evidence == ScrollEvidence.AT_BOUNDARY) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                    elapsedMs = elapsedMs,
                )
            }
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "The scroll action was issued, but the content movement cannot be confirmed; re-observe first, do not blindly retry",
                method = methodName,
                targetIndex = targetIndex,
                deltaX = delta.takeIf { direction.axis == ScrollAxis.HORIZONTAL },
                deltaY = delta.takeIf { direction.axis == ScrollAxis.VERTICAL },
                elapsedMs = elapsedMs,
            )
        }
    }

    private inline fun <T> withScrollEventObservation(
        packageName: String,
        windowId: Int,
        block: () -> T,
    ): T {
        val observation = scrollEventObservationGate.begin(
            packageName = packageName,
            windowId = windowId,
            validForMillis = SCROLL_EVENT_OBSERVATION_TIMEOUT_MS,
        )
        return try {
            block()
        } finally {
            scrollEventObservationGate.end(observation)
        }
    }

    private sealed interface TextInsertionPreparation {
        data class Ready(
            val node: AccessibilityNodeInfo,
            val text: String,
            val cursor: Int,
            val connectionToken: Long,
        ) : TextInsertionPreparation
        data class Rejected(val result: NodeActionResult) : TextInsertionPreparation
    }

    private fun prepareTextInsertion(text: String, displayId: Int): TextInsertionPreparation {
        val connectionToken = serviceToken
        return runAccessibilityQuery {
            val node = findFocusedEditableNode(displayId)
                ?: return@runAccessibilityQuery TextInsertionPreparation.Rejected(
                    NodeActionResult.failure("NO_FOCUSED_EDITABLE", "No editable node has input focus"),
                )
            node.incrementalTextValidationError()?.let { error ->
                return@runAccessibilityQuery TextInsertionPreparation.Rejected(error)
            }
            val plan = TextEditPlanner.insertAtSelection(
                currentText = node.realText(),
                insertedText = text,
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
            ) ?: return@runAccessibilityQuery TextInsertionPreparation.Rejected(
                NodeActionResult.failure("TEXT_SELECTION_UNAVAILABLE", "The current input does not provide a reliable cursor or selection; use replace_text to provide the full value"),
            )
            TextInsertionPreparation.Ready(node, plan.text, plan.cursor, connectionToken)
        } ?: TextInsertionPreparation.Rejected(
            NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility query not completed; text was not modified this time"),
        )
    }

    fun inputTextFocused(
        text: String,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult = when (val preparation = prepareTextInsertion(text, displayId)) {
        is TextInsertionPreparation.Rejected -> preparation.result
        is TextInsertionPreparation.Ready -> setNodeText(preparation.node, preparation.text, preparation.cursor, preparation.connectionToken)
    }

    fun setTextNode(
        snapshot: NodeSnapshot?,
        index: Int?,
        text: String,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult {
        if (index != null) {
            val requiredSnapshot = snapshot
                ?: return NodeActionResult.failure("NO_OBSERVATION", "The specified index requires a valid observation snapshot")
            return withValidatedNode(requiredSnapshot, index) { node ->
                if (!node.isEditable) {
                    NodeActionResult.failure("NOT_EDITABLE", "The specified node is not editable")
                } else {
                    setNodeText(node, text, text.length, requiredSnapshot.serviceToken)
                }
            }
        }
        val connectionToken = serviceToken
        val node = runAccessibilityQuery { findFocusedEditableNode(snapshot?.displayId ?: displayId) }
            ?: return NodeActionResult.failure("NO_FOCUSED_EDITABLE", "No editable node has input focus")
        return setNodeText(node, text, text.length, connectionToken)
    }

    /** Prefer writing directly by selection, and fall back to system paste only when the target rejects SET_TEXT. */
    fun pasteText(
        text: String,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult {
        val preparation = prepareTextInsertion(text, displayId)
        if (preparation is TextInsertionPreparation.Rejected) return preparation.result
        preparation as TextInsertionPreparation.Ready
        val node = preparation.node
        val directResult = setNodeText(node, preparation.text, preparation.cursor, preparation.connectionToken)
        if (directResult.ok) return directResult.copy(method = "ACTION_SET_TEXT_PASTE")
        if (directResult.code != "ACTION_FAILED") return directResult
        val dispatched = when (val result = callOnMainSync(preparation.connectionToken) {
            pasteWithTemporaryClipboard(node, text)
        }) {
            is MainThreadCallResult.Completed -> result.value ?: NodeActionResult.outcomeUnknown()
            MainThreadCallResult.NOT_STARTED ->
                NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility action was not dispatched; please re-observe the screen")
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown().copy(clipboardWritten = true)
        }
        if (!dispatched.ok) return dispatched
        val verified = runAccessibilityQuery { node.refresh() && node.realText() == preparation.text } == true
        return if (verified) dispatched.copy(verified = true)
        else NodeActionResult.outcomeUnknown().copy(clipboardWritten = dispatched.clipboardWritten)
    }

    private fun pasteWithTemporaryClipboard(node: AccessibilityNodeInfo, text: String): NodeActionResult {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        val temporaryLabel = "$CLIP_LABEL:${CLIP_IDS.incrementAndGet()}"
        val temporaryClip = ClipData.newPlainText(temporaryLabel, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val copied = runCatching { clipboard.setPrimaryClip(temporaryClip) }.isSuccess
        if (!copied) return NodeActionResult.failure("CLIPBOARD_WRITE_FAILED", "Failed to write to the clipboard")
        var restored = false
        val result = try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                NodeActionResult.success(method = "ACTION_PASTE")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "Input node rejected the paste action")
            }
        } catch (_: Throwable) {
            NodeActionResult.outcomeUnknown()
        } finally {
            // A caller timeout does not mean ACTION_PASTE has returned; restoration must stay inside the started action so the target does not read the old clipboard.
            restored = restoreClipboardIfStillOwned(clipboard, temporaryLabel, originalClip)
        }
        return (if (restored) result else NodeActionResult.outcomeUnknown()).copy(clipboardWritten = true)
    }

    private fun restoreClipboardIfStillOwned(
        clipboard: ClipboardManager,
        temporaryLabel: String,
        originalClip: ClipData?,
    ): Boolean {
        val currentClip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return false
        if (currentClip.description.label?.toString() != temporaryLabel) {
            // The user or another app has already written new content; do not overwrite it with the old snapshot.
            return true
        }
        return runCatching {
            if (originalClip != null) {
                clipboard.setPrimaryClip(originalClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }.isSuccess
    }

    fun imeEnter(
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult {
        val connectionToken = serviceToken
        val node = runAccessibilityQuery { findFocusedEditableNode(displayId) }
            ?: return NodeActionResult.failure("NO_FOCUSED_EDITABLE", "No editable node has input focus")
        return when (performNodeAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, connectionToken = connectionToken)) {
            ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_IME_ENTER")
            ActionDispatch.REJECTED -> NodeActionResult.failure("ACTION_FAILED", "Input node rejected the enter action")
            ActionDispatch.NOT_DISPATCHED -> NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility action was not dispatched; please re-observe the screen")
            ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
        }
    }

    fun gestureTap(
        x: Float,
        y: Float,
        durationMs: Long = 50,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult = gestureTapOnConnection(x, y, durationMs, displayId, serviceToken)

    private fun gestureTapOnConnection(
        x: Float,
        y: Float,
        durationMs: Long,
        displayId: Int,
        connectionToken: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            },
            durationMs.coerceIn(1, 3_000),
            successMethod = "GESTURE_TAP",
            displayId = displayId,
            connectionToken = connectionToken,
        )

    fun gestureSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): NodeActionResult = gestureSwipeOnConnection(x1, y1, x2, y2, durationMs, displayId, serviceToken)

    private fun gestureSwipeOnConnection(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        displayId: Int,
        connectionToken: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs.coerceIn(100, 3_000),
            successMethod = "GESTURE_SWIPE",
            displayId = displayId,
            connectionToken = connectionToken,
        )

    fun globalActionResult(name: String): NodeActionResult {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return NodeActionResult.failure("INVALID_ARGUMENT", "Unsupported system action")
        }
        return runNodeActionOnMainSync {
            if (performGlobalAction(action)) {
                NodeActionResult.success(method = "GLOBAL_ACTION_${name.uppercase()}")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "System refused the global action")
            }
        }
    }

    fun globalAction(name: String): Boolean = globalActionResult(name).ok

    fun copyToClipboard(text: String): NodeActionResult =
        runNodeActionOnMainSync {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            NodeActionResult.success(method = "CLIPBOARD_SET")
        }

    fun readClipboard(): ClipboardReadResult = runOnMainSync {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return@runOnMainSync ClipboardReadResult.failure()
        if (clip.itemCount <= 0) return@runOnMainSync ClipboardReadResult.failure()
        ClipboardReadResult(
            ok = true,
            text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty(),
        )
    } ?: ClipboardReadResult.failure(code = "SERVICE_TIMEOUT")

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * Captures the full screen of the given displayId (used as the accessibility screenshot
     * fallback for virtual displays). The virtual display itself carries no Eta system overlay,
     * so no per-window exclusion is needed.
     */
    fun captureDisplayScreenshot(displayId: Int): ScreenshotCaptureResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable()
        }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var failureCode: Int? = null
        runCatching {
            takeScreenshot(displayId, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        bitmap = convertToSoftwareBitmap(screenshot)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failureCode = errorCode
                    latch.countDown()
                }
            })
        }.onFailure {
            failureCode = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            latch.countDown()
        }
        val completed = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val captured = bitmap
        return ScreenshotCaptureResult(
            bitmap = captured,
            complete = completed && captured != null,
            expectedWindows = 1,
            capturedWindows = if (captured != null) 1 else 0,
            missingWindowIds = if (captured != null) emptyList() else listOf(displayId),
            failureCodes = failureCode?.let { mapOf(displayId to it) }.orEmpty(),
            timedOut = !completed,
            criticalWindowMissing = captured == null,
        )
    }

    /**
     * Captures the current screen, excluding TYPE_ACCESSIBILITY_OVERLAY overlays
     * (glow/orb/bubble/resultCard/GestureIndicator). Called from an agent-runtime worker thread;
     * takeScreenshotOfWindow posts to the main thread internally, the callback performs the bitmap
     * copy on a bounded background thread, and the latch blocks only the agent-runtime worker thread.
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
        onWindowsSubmitted: (() -> Unit)? = null,
    ): ScreenshotCaptureResult {
        val windowsSubmitted = AtomicBoolean(false)
        fun signalWindowsSubmitted() {
            if (windowsSubmitted.compareAndSet(false, true)) {
                runCatching { onWindowsSubmitted?.invoke() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val allWindows = windows
            ?: return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenshotCaptureResult.unavailable().also {
                allWindows.forEach { window -> runCatching { window.recycle() } }
                signalWindowsSubmitted()
            }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y
        if (screenW <= 0 || screenH <= 0) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val screenBounds = Rect(0, 0, screenW, screenH)

        // Filter only accessibility overlays confirmed to belong to Eta; third-party overlays must
        // be kept, otherwise the screenshot would diverge from the window that actually receives
        // coordinate gestures.
        val windowPackages = allWindows.associate { window ->
            window.id to window.root?.packageName?.toString()
        }
        val windowDecisions = allWindows.associate { window ->
            window.id to ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay =
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                active = window.isActive,
                focused = window.isFocused,
                resolvedPackage = windowPackages[window.id],
                ownPackage = packageName,
                excludedPackages = excludedPackages,
            )
        }
        val unknownRelevantWindowIds = windowDecisions
            .filterValues { decision ->
                decision == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN
            }
            .keys
            .toList()
        if (unknownRelevantWindowIds.isNotEmpty()) {
            val expectedWindows = allWindows.size
            allWindows.forEach { window -> runCatching { window.recycle() } }
            AndroidAgentLogger.warn(
                "Agent accessibility action=capture_screenshot outcome=failed " +
                    "reason=unknown_relevant_window_during_exclusion " +
                    "windows=${unknownRelevantWindowIds.size}"
            )
            return ScreenshotCaptureResult.blockedByUnknownWindow(
                expectedWindows = expectedWindows,
                windowIds = unknownRelevantWindowIds,
            ).also { signalWindowsSubmitted() }
        }
        val captureWindows = allWindows.mapNotNull { window ->
            if (windowDecisions[window.id] == ScreenshotWindowPolicy.Decision.EXCLUDE) {
                return@mapNotNull null
            }
            val bounds = Rect().also(window::getBoundsInScreen)
            if (
                bounds.width() <= 0 ||
                bounds.height() <= 0 ||
                !Rect.intersects(bounds, screenBounds)
            ) {
                return@mapNotNull null
            }
            ScreenshotWindow(
                id = window.id,
                layer = window.layer,
                type = window.type,
                bounds = bounds,
                active = window.isActive,
                focused = window.isFocused,
            )
        }.distinctBy { window -> window.id }.sortedBy { window -> window.layer }
        if (captureWindows.isEmpty()) {
            allWindows.forEach { window -> runCatching { window.recycle() } }
            return ScreenshotCaptureResult.unavailable().also { signalWindowsSubmitted() }
        }
        val topApplicationWindowId = captureWindows
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull(ScreenshotWindow::layer)
            ?.id
            ?: captureWindows.maxByOrNull(ScreenshotWindow::layer)?.id
        val criticalWindowIds = captureWindows
            .asSequence()
            .filter { window ->
                window.id == topApplicationWindowId || window.active || window.focused
            }
            .map(ScreenshotWindow::id)
            .toSet()

        val latch = CountDownLatch(captureWindows.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val failures = mutableMapOf<Int, Int>()
        val lock = Any()
        val acceptingResults = AtomicBoolean(true)

        for (window in captureWindows) {
            runCatching {
                takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val sw = convertToSoftwareBitmap(screenshot)
                                ?: throw IllegalStateException("screenshot bitmap unavailable")
                            var retained = false
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    screenshots[window.id] = sw to Rect(window.bounds)
                                    retained = true
                                }
                            }
                            if (!retained && !sw.isRecycled) sw.recycle()
                        } catch (_: Exception) {
                            synchronized(lock) {
                                if (acceptingResults.get()) {
                                    failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                }
                            }
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        synchronized(lock) {
                            if (acceptingResults.get()) failures[window.id] = errorCode
                        }
                        latch.countDown()
                    }
                })
            }.onFailure {
                synchronized(lock) {
                    if (acceptingResults.get()) {
                        failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    }
                }
                latch.countDown()
            }
        }
        // Eta can be shown once the window IDs are fixed and all submitted; the later merge and encode steps will no longer capture the entry overlay.
        signalWindowsSubmitted()
        val completed = try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        acceptingResults.set(false)
        val captured = synchronized(lock) {
            screenshots.toMap().also { screenshots.clear() }
        }
        val capturedIds = captured.keys
        val missingIds = captureWindows.map(ScreenshotWindow::id).filterNot(capturedIds::contains)
        val criticalWindowMissing = missingIds.any(criticalWindowIds::contains)
        val merged = if (captured.isEmpty() || criticalWindowMissing) {
            null
        } else {
            mergeScreenshots(captured, captureWindows, screenW, screenH)
        }
        val failureCodes = synchronized(lock) { failures.toMap() }
        val complete = completed && missingIds.isEmpty() && merged != null
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=merged " +
                "allWindows=${allWindows.size} validWindows=${captureWindows.size} " +
                "excludedPackages=${excludedPackages.size} " +
                "completed=$completed screenshots=${captured.size} " +
                "complete=$complete criticalMissing=$criticalWindowMissing " +
                "screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }
        captured.values.forEach { (bitmap, _) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        allWindows.forEach { window -> runCatching { window.recycle() } }
        return ScreenshotCaptureResult(
            bitmap = merged,
            complete = complete,
            expectedWindows = captureWindows.size,
            capturedWindows = captured.size,
            missingWindowIds = missingIds,
            failureCodes = failureCodes,
            timedOut = !completed,
            criticalWindowMissing = criticalWindowMissing,
        )
    }

    private fun convertToSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? =
        screenshot.hardwareBuffer.use { hardwareBuffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@use null
            try {
                if (wrapped.config == Bitmap.Config.HARDWARE) {
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    wrapped
                }
            } finally {
                if (wrapped.config == Bitmap.Config.HARDWARE && !wrapped.isRecycled) {
                    wrapped.recycle()
                }
            }
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<ScreenshotWindow>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap? {
        var merged: Bitmap? = null
        return try {
            val output = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val occlusionPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            for (window in sortedWindows) {
                val pair = screenshots[window.id]
                if (pair == null) {
                    // When an upper window fails to capture, its region must be blanked out; the underlying UI that is actually obscured must not be exposed to the model.
                    canvas.drawRect(RectF(window.bounds), occlusionPaint)
                } else {
                    val (bmp, bounds) = pair
                    if (bmp.isRecycled) continue
                    // Scale the window bitmap to its bounds when drawing, handling the case where
                    // takeScreenshotOfWindow returns a size that differs from the bounds
                    // (logical pixels vs physical pixels).
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, RectF(bounds), null)
                }
            }
            output
        } catch (_: Exception) {
            merged?.takeUnless(Bitmap::isRecycled)?.recycle()
            null
        }
    }

    /**
     * The real content of an input field. When an empty field shows hint text, text returns the
     * hint; if it is treated as existing content, appending input would splice the hint into the
     * result, and the read-back check after clearing would also falsely report failure.
     */
    private fun AccessibilityNodeInfo.realText(): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
        connectionToken: Long,
    ): NodeActionResult {
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        when (performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs, connectionToken)) {
            ActionDispatch.REJECTED -> return NodeActionResult.failure("ACTION_FAILED", "Input node rejected the text modification action")
            ActionDispatch.NOT_DISPATCHED -> return NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility action was not dispatched; please re-observe the screen")
            ActionDispatch.OUTCOME_UNKNOWN -> return NodeActionResult.outcomeUnknown()
            ActionDispatch.ACCEPTED -> Unit
        }
        val safeCursor = cursor.coerceIn(0, text.length)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, safeCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, safeCursor)
        }
        val refreshed = runAccessibilityQuery { node.refresh() } == true
        if (!node.isPassword && (!refreshed || node.realText() != text)) {
            return NodeActionResult.outcomeUnknown()
        }
        val selectionRestored = refreshed && performNodeAction(
            node,
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            selectionArgs,
            connectionToken,
        ) == ActionDispatch.ACCEPTED
        return NodeActionResult.success(
            method = if (selectionRestored) {
                "ACTION_SET_TEXT_AND_SELECTION"
            } else {
                "ACTION_SET_TEXT"
            },
            verified = !node.isPassword,
        )
    }

    private fun findFocusedEditableNode(
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): AccessibilityNodeInfo? {
        val root = activeRootOnDisplay(displayId) ?: return null
        queryExecutor.ensureActive()
        val inputFocus = runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (inputFocus?.isUsableInputTarget() == true) return inputFocus
        return findNode(root) { node -> node.isFocused && node.isUsableInputTarget() }
    }

    private fun AccessibilityNodeInfo.isUsableInputTarget(): Boolean =
        isEditable && isEnabled && isVisibleToUser

    private fun AccessibilityNodeInfo.incrementalTextValidationError(): NodeActionResult? {
        val currentText = text
        if (isPassword || currentText == null) {
            return NodeActionResult.failure(
                "TEXT_CONTENT_UNAVAILABLE",
                "The current input does not allow reliably reading existing text; use replace_text to provide the full value",
            )
        }
        if (
            !TextEditPlanner.canSafelyReconstruct(
                password = false,
                textAvailable = true,
                textLength = currentText.length,
                selectionStart = textSelectionStart,
                selectionEnd = textSelectionEnd,
            )
        ) {
            return NodeActionResult.failure(
                "TEXT_SELECTION_UNAVAILABLE",
                "The current input does not provide a reliable cursor or selection; use replace_text to provide the full value",
            )
        }
        return null
    }

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        direction: ScrollDirection,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): AccessibilityNodeInfo {
        val candidates = mutableListOf<ScrollCandidate>()
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            queryExecutor.ensureActive()
            if (
                depth > MAX_UI_TREE_DEPTH ||
                visitedNodes >= MAX_SCROLL_SEARCH_NODES ||
                !activePath.add(node)
            ) return
            visitedNodes++
            try {
                if (!node.isVisibleToUser) return
                if (node.isEnabled && node.hasScrollCapability()) {
                    val bounds = clippedNodeBounds(node, displayId)
                    val supportRank = node.directionSupportRank(direction)
                    if (
                        !node.exposesOnlyOppositeAxis(direction) &&
                        !bounds.isEmpty &&
                        direction.gestureWithin(bounds) != null
                    ) {
                        candidates += ScrollCandidate(
                            node = node,
                            supportRank = supportRank,
                            depth = depth,
                            area = bounds.width().toLong() * bounds.height().toLong(),
                        )
                    }
                }
                for (childIndex in 0 until node.childCount) {
                    queryExecutor.ensureActive()
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return candidates.maxWithOrNull(
            compareBy<ScrollCandidate> { it.area }
                .thenBy { it.supportRank }
                .thenBy { -it.depth },
        )?.node ?: root
    }

    private fun AccessibilityNodeInfo.directionSupportRank(direction: ScrollDirection): Int {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return when {
            direction.exactScrollActionId() in actions -> 4
            direction.pageActionId() in actions -> 3
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            ) && (
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
                ) -> 2
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id in actions -> 1
            else -> 0
        }
    }

    private fun AccessibilityNodeInfo.exposesOnlyOppositeAxis(
        direction: ScrollDirection,
    ): Boolean {
        val actions = supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actions::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actions::contains)
        return ScrollAxisContract.exposesOnlyOppositeAxis(
            requestedAxis = direction.axis,
            hasVerticalActions = hasVertical,
            hasHorizontalActions = hasHorizontal,
        )
    }

    private fun AccessibilityNodeInfo.hasScrollCapability(): Boolean {
        if (isScrollable) return true
        val actions = supportedActionIds()
        return SCROLL_ACTION_IDS.any(actions::contains)
    }

    private fun AccessibilityNodeInfo.supportedActionIds(): Set<Int> =
        actionList.mapTo(hashSetOf()) { action -> action.id }

    private fun chooseScrollMethod(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
    ): ScrollMethod? {
        if (node.exposesOnlyOppositeAxis(direction)) return null
        val actionIds = node.supportedActionIds()
        val hasVertical = VERTICAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val hasHorizontal = HORIZONTAL_DIRECTION_ACTION_IDS.any(actionIds::contains)
        val exactAction = direction.exactScrollActionId()
        if (exactAction in actionIds) {
            return ScrollMethod(exactAction, "ACTION_SCROLL_${direction.name}")
        }
        val pageAction = direction.pageActionId()
        if (pageAction in actionIds) {
            return ScrollMethod(pageAction, "ACTION_PAGE_${direction.name}")
        }
        if (
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = direction.axis,
                hasVerticalActions = hasVertical,
                hasHorizontalActions = hasHorizontal,
            )
        ) {
            val fallback = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (fallback in actionIds) {
                return ScrollMethod(
                    actionId = fallback,
                    name = if (direction == ScrollDirection.DOWN) {
                        "ACTION_SCROLL_FORWARD"
                    } else {
                        "ACTION_SCROLL_BACKWARD"
                    },
                )
            }
        }
        val inDirection = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id
        if (inDirection in actionIds) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_DIRECTION_INT,
                    direction.focusDirection(),
                )
            }
            return ScrollMethod(inDirection, "ACTION_SCROLL_IN_DIRECTION", args)
        }
        return null
    }

    private fun isAtScrollBoundary(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection,
        signal: ScrollSignal? = null,
    ): Boolean {
        if (signal != null && signal.axisDelta(direction.axis) != null) {
            when (direction) {
                ScrollDirection.UP -> if (
                    signal.maxScrollY > 0 && signal.scrollY <= 0
                ) return true
                ScrollDirection.DOWN -> if (
                    signal.maxScrollY > 0 && signal.scrollY >= signal.maxScrollY
                ) return true
                ScrollDirection.LEFT -> if (
                    signal.maxScrollX > 0 && signal.scrollX <= 0
                ) return true
                ScrollDirection.RIGHT -> if (
                    signal.maxScrollX > 0 && signal.scrollX >= signal.maxScrollX
                ) return true
            }
        }
        if (chooseScrollMethod(node, direction) != null) return false
        return chooseScrollMethod(node, direction.opposite()) != null
    }

    private fun clippedNodeBounds(
        node: AccessibilityNodeInfo,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ): Rect {
        val bounds = node.bounds()
        val size = displaySize(displayId)
        if (size != null) {
            if (!bounds.intersect(0, 0, size.first, size.second)) bounds.setEmpty()
        }
        return bounds
    }

    private fun scrollContentAnchors(root: AccessibilityNodeInfo): List<ScrollAnchor> {
        val anchors = ArrayList<ScrollAnchor>(SCROLL_ANCHOR_NODE_LIMIT)
        val activePath = hashSetOf<AccessibilityNodeInfo>()
        val rootBounds = root.bounds()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            queryExecutor.ensureActive()
            if (
                depth > MAX_UI_TREE_DEPTH ||
                anchors.size >= SCROLL_ANCHOR_NODE_LIMIT ||
                !activePath.add(node)
            ) return
            try {
                if (!node.isVisibleToUser) return
                val bounds = node.bounds()
                val key = buildString {
                    append(node.uniqueId.orEmpty())
                    append('|')
                    append(node.className?.toString().orEmpty())
                    append('|')
                    append(node.viewIdResourceName.orEmpty())
                    append('|')
                    append(node.text?.toString().orEmpty().take(80))
                    append('|')
                    append(node.contentDescription?.toString().orEmpty().take(80))
                }
                if (
                    node.uniqueId?.isNotBlank() == true ||
                    node.viewIdResourceName?.isNotBlank() == true ||
                    node.text?.isNotBlank() == true ||
                    node.contentDescription?.isNotBlank() == true
                ) {
                    anchors += ScrollAnchor(
                        key = key,
                        centerX = bounds.centerX() - rootBounds.left,
                        centerY = bounds.centerY() - rootBounds.top,
                    )
                }
                for (childIndex in 0 until node.childCount) {
                    queryExecutor.ensureActive()
                    val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                    visit(child, depth + 1)
                }
            } finally {
                activePath.remove(node)
            }
        }

        visit(root, 0)
        return anchors
    }

    private fun inferScrollAnchorDelta(
        before: List<ScrollAnchor>,
        after: List<ScrollAnchor>,
        direction: ScrollDirection,
    ): Int? {
        fun unique(anchors: List<ScrollAnchor>): Map<String, ScrollAnchor> = anchors
            .groupBy(ScrollAnchor::key)
            .mapNotNull { (key, matches) -> matches.singleOrNull()?.let { anchor -> key to anchor } }
            .toMap()
        val beforeUnique = unique(before)
        val afterUnique = unique(after)
        val contentDeltas = beforeUnique.mapNotNull { (key, oldAnchor) ->
            val newAnchor = afterUnique[key] ?: return@mapNotNull null
            if (direction.axis == ScrollAxis.VERTICAL) {
                newAnchor.centerY - oldAnchor.centerY
            } else {
                newAnchor.centerX - oldAnchor.centerX
            }
        }
        return RootScrollMotionContract.inferScrollDelta(contentDeltas)
    }

    private fun ScrollDirection.exactScrollActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
    }

    private fun ScrollDirection.pageActionId(): Int = when (this) {
        ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
        ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
        ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
        ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
    }

    private fun ScrollDirection.focusDirection(): Int = when (this) {
        ScrollDirection.UP -> View.FOCUS_UP
        ScrollDirection.DOWN -> View.FOCUS_DOWN
        ScrollDirection.LEFT -> View.FOCUS_LEFT
        ScrollDirection.RIGHT -> View.FOCUS_RIGHT
    }

    private fun ScrollDirection.opposite(): ScrollDirection = when (this) {
        ScrollDirection.UP -> ScrollDirection.DOWN
        ScrollDirection.DOWN -> ScrollDirection.UP
        ScrollDirection.LEFT -> ScrollDirection.RIGHT
        ScrollDirection.RIGHT -> ScrollDirection.LEFT
    }

    private fun withValidatedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (AccessibilityNodeInfo) -> NodeActionResult,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        block(indexed.node)
    }

    private fun withValidatedIndexedNode(
        snapshot: NodeSnapshot,
        index: Int,
        block: (IndexedNode) -> NodeActionResult,
    ): NodeActionResult {
        val validation = runAccessibilityQuery { validateNode(snapshot, index) }
            ?: return NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility node query not completed")
        return when (validation) {
            is NodeValidation.Invalid -> validation.result
            is NodeValidation.Valid -> block(validation.indexedNode)
        }
    }

    private fun validateNode(snapshot: NodeSnapshot, index: Int): NodeValidation {
        if (snapshot.serviceToken != serviceToken) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("SERVICE_RECONNECTED", "Accessibility service has reconnected; please re-observe the screen"),
            )
        }
        val indexed = snapshot.indexedNodes.firstOrNull { it.index == index }
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("INVALID_NODE_INDEX", "No node with index=$index exists in the observation snapshot"),
            )
        val activeRoot = activeRootOnDisplay(snapshot.displayId)
            ?: return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "The current active window is inaccessible; please re-observe the screen"),
            )
        val activePackage = activeRoot.packageName?.toString().orEmpty()
        if (activeRoot.windowId != snapshot.windowId || activePackage != snapshot.packageName) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_WINDOW", "The active window has changed; please re-observe the screen"),
            )
        }
        if (
            windowContentGeneration(snapshot.windowId) != snapshot.contentGeneration &&
            !snapshot.hasUnambiguousIdentity(indexed)
        ) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_CONTENT", "The window content has changed; please re-observe the screen"),
            )
        }
        val node = indexed.node
        queryExecutor.ensureActive()
        if (!runCatching { node.refresh() }.getOrDefault(false)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("STALE_NODE", "The target node is no longer valid; please re-observe the screen"),
            )
        }
        if (!indexed.identityMatches(node)) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("IDENTITY_CHANGED", "The target node's content or identity has changed; please re-observe the screen"),
            )
        }
        if (!node.isVisibleToUser || !node.isEnabled) {
            return NodeValidation.Invalid(
                NodeActionResult.failure("NODE_NOT_ACTIONABLE", "The target node is currently not visible or not actionable"),
            )
        }
        return NodeValidation.Valid(indexed)
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val visited = hashSetOf<AccessibilityNodeInfo>()
        queue.addLast(node to 0)
        while (queue.isNotEmpty() && visited.size < MAX_FOCUS_SEARCH_NODES) {
            queryExecutor.ensureActive()
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (predicate(current)) return current
            if (depth >= MAX_UI_TREE_DEPTH) continue
            for (childIndex in 0 until current.childCount) {
                queryExecutor.ensureActive()
                val child = runCatching { current.getChild(childIndex) }.getOrNull() ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<IndexedNode>,
        maxNodes: Int,
        depth: Int,
        traversal: NodeTraversalState,
        clickableAncestor: NodeActionTarget? = null,
        longClickableAncestor: NodeActionTarget? = null,
        scrollableAncestor: NodeActionTarget? = null,
    ) {
        queryExecutor.ensureActive()
        if (out.size >= maxNodes) {
            traversal.truncated = true
            return
        }
        if (depth > MAX_UI_TREE_DEPTH || traversal.visitedNodes >= traversal.maxVisitedNodes) {
            traversal.truncated = true
            return
        }
        if (!traversal.activePath.add(node)) {
            traversal.truncated = true
            return
        }
        traversal.visitedNodes++
        try {
            // Descendants of an invisible parent can never become actionable targets; prune such large branches early.
            val visible = node.isVisibleToUser
            if (depth > 0 && !visible) return

            val bounds = node.bounds()
            val text = node.realText().take(120)
            val hint = node.hintText?.toString().orEmpty().take(60)
            val desc = node.contentDescription?.toString().orEmpty().take(120)
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val scrollable = node.isScrollable
            val focused = node.isFocused
            val editable = node.isEditable
            val password = node.isPassword
            val enabled = node.isEnabled
            val clickTarget = if (clickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                clickableAncestor
            }
            val longClickTarget = if (longClickable && enabled) {
                NodeActionTarget.capture(node)
            } else {
                longClickableAncestor
            }
            val hasScrollCapability = enabled && node.hasScrollCapability()
            val scrollTarget = if (hasScrollCapability) {
                NodeActionTarget.capture(node)
            } else {
                scrollableAncestor
            }
            val useful = text.isNotBlank() ||
                desc.isNotBlank() ||
                clickable ||
                longClickable ||
                hasScrollCapability ||
                focused ||
                editable
            if (visible && bounds.width() > 2 && bounds.height() > 2 && useful) {
                out += IndexedNode(
                    index = out.size,
                    node = node,
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    text = text,
                    desc = desc,
                    className = node.className?.toString().orEmpty(),
                    packageName = node.packageName?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    bounds = Rect(bounds),
                    clickable = clickable,
                    longClickable = longClickable,
                    scrollable = scrollTarget != null,
                    focused = focused,
                    editable = editable,
                    password = password,
                    enabled = enabled,
                    checked = node.isCheckable.takeIf { it }?.let {
                        if (Build.VERSION.SDK_INT >= 36) {
                            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
                        } else {
                            @Suppress("DEPRECATION")
                            node.isChecked
                        }
                    },
                    selected = node.isSelected,
                    hint = hint,
                    clickTarget = clickTarget,
                    longClickTarget = longClickTarget,
                    scrollTarget = scrollTarget,
                )
            }
            for (index in 0 until node.childCount) {
                queryExecutor.ensureActive()
                if (
                    out.size >= maxNodes ||
                    traversal.visitedNodes >= traversal.maxVisitedNodes
                ) {
                    traversal.truncated = true
                    return
                }
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                collectNodes(
                    node = child,
                    out = out,
                    maxNodes = maxNodes,
                    depth = depth + 1,
                    traversal = traversal,
                    clickableAncestor = clickTarget,
                    longClickableAncestor = longClickTarget,
                    scrollableAncestor = scrollTarget,
                )
            }
        } finally {
            traversal.activePath.remove(node)
        }
    }

    private fun AccessibilityNodeInfo.bounds(): Rect =
        Rect().also { getBoundsInScreen(it) }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null,
        connectionToken: Long = serviceToken,
    ): ActionDispatch =
        when (val result = callOnMainSync(connectionToken) {
            if (args == null) node.performAction(action) else node.performAction(action, args)
        }) {
            is MainThreadCallResult.Completed -> {
                if (result.value == true) ActionDispatch.ACCEPTED else ActionDispatch.REJECTED
            }
            MainThreadCallResult.NOT_STARTED -> ActionDispatch.NOT_DISPATCHED
            MainThreadCallResult.OUTCOME_UNKNOWN -> ActionDispatch.OUTCOME_UNKNOWN
        }

    private fun dispatchGestureResult(
        path: Path,
        durationMs: Long,
        successMethod: String,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
        connectionToken: Long = serviceToken,
    ): NodeActionResult {
        if (instance !== this || connectionToken != serviceToken || Thread.currentThread().isInterrupted) {
            return NodeActionResult.failure("SERVICE_RECONNECTED", "Accessibility connection is no longer valid; the gesture was not executed")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return NodeActionResult.failure(
                "GESTURE_NOT_DISPATCHED",
                "Cannot synchronously wait for a gesture on the accessibility main thread",
            )
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        val outcome = java.util.concurrent.atomic.AtomicReference<GestureDispatch>()
        val posted = mainHandler.post {
            if (!gate.tryStart()) {
                latch.countDown()
                return@post
            }
            if (instance !== this || connectionToken != serviceToken) {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
                return@post
            }
            val gesture = runCatching {
                GestureDescription.Builder().apply {
                    if (displayId != android.view.Display.DEFAULT_DISPLAY) {
                        setDisplayId(displayId)
                    }
                    addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                }.build()
            }.getOrElse {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
                return@post
            }
            val dispatched = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.COMPLETED)
                            gate.finish()
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            outcome.set(GestureDispatch.CANCELLED)
                            gate.finish()
                            latch.countDown()
                        }
                    },
                    GESTURE_CALLBACK_HANDLER,
                )
            } catch (_: Throwable) {
                // The Binder transaction may already have been delivered; an exception does not prove the gesture did not run, so Root replay is forbidden.
                outcome.set(GestureDispatch.OUTCOME_UNKNOWN)
                gate.finish()
                latch.countDown()
                return@post
            }
            if (!dispatched) {
                outcome.set(GestureDispatch.NOT_DISPATCHED)
                gate.finish()
                latch.countDown()
            }
        }
        if (!posted) {
            return NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "Accessibility main thread refused the gesture task")
        }
        val finishedInTime = try {
            latch.await(durationMs + GESTURE_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finishedInTime) {
            if (gate.cancelIfPending()) {
                return NodeActionResult.failure(
                    "GESTURE_NOT_DISPATCHED",
                    "Accessibility main thread is busy; the gesture was canceled before execution",
                )
            }
            return NodeActionResult.outcomeUnknown()
        }
        return when (outcome.get()) {
            GestureDispatch.COMPLETED -> NodeActionResult.success(successMethod)
            GestureDispatch.CANCELLED -> NodeActionResult.outcomeUnknown()
            GestureDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            GestureDispatch.NOT_DISPATCHED,
            null -> NodeActionResult.failure("GESTURE_NOT_DISPATCHED", "System refused the gesture task")
        }
    }

    private fun runNodeActionOnMainSync(block: () -> NodeActionResult): NodeActionResult =
        when (val result = callOnMainSync(block = block)) {
            is MainThreadCallResult.Completed -> result.value
                ?: NodeActionResult.failure("ACTION_FAILED", "Accessibility action execution error")
            MainThreadCallResult.NOT_STARTED ->
                NodeActionResult.failure("SERVICE_TIMEOUT", "Accessibility service main thread is unresponsive; the action was not executed")
            MainThreadCallResult.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
        }

    private fun <T> runOnMainSync(block: () -> T): T? =
        when (val result = callOnMainSync(block = block)) {
            is MainThreadCallResult.Completed -> result.value
            MainThreadCallResult.NOT_STARTED,
            MainThreadCallResult.OUTCOME_UNKNOWN -> null
        }

    private fun <T> callOnMainSync(
        connectionToken: Long = serviceToken,
        block: () -> T,
    ): MainThreadCallResult<T> {
        if (instance !== this || connectionToken != serviceToken || Thread.currentThread().isInterrupted) {
            return MainThreadCallResult.NOT_STARTED
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                MainThreadCallResult.Completed(block())
            } catch (_: Throwable) {
                // When a framework call throws, side effects cannot be proven absent; the caller must not fall back and replay.
                MainThreadCallResult.OUTCOME_UNKNOWN
            }
        }
        val latch = CountDownLatch(1)
        val gate = MainThreadCallGate()
        var value: T? = null
        var failed = false
        var invalidated = false
        val posted = mainHandler.post {
            if (gate.tryStart()) {
                try {
                    if (instance === this && connectionToken == serviceToken) value = block()
                    else invalidated = true
                } catch (_: Throwable) {
                    failed = true
                } finally {
                    gate.finish()
                }
            }
            latch.countDown()
        }
        if (!posted) return MainThreadCallResult.NOT_STARTED
        val completed = try {
            latch.await(MAIN_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (gate.cancelIfPending()) return MainThreadCallResult.NOT_STARTED
            // A side effect that already started must not be redone by the caller's fallback; return an unknown result so the model re-observes first.
            return MainThreadCallResult.OUTCOME_UNKNOWN
        }
        if (invalidated) return MainThreadCallResult.NOT_STARTED
        if (failed) return MainThreadCallResult.OUTCOME_UNKNOWN
        return MainThreadCallResult.Completed(value)
    }

    data class NodeActionResult(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val clipboardWritten: Boolean = false,
        val verified: Boolean? = null,
    ) {
        companion object {
            fun success(method: String, verified: Boolean? = null): NodeActionResult =
                NodeActionResult(ok = true, method = method, verified = verified)

            fun failure(code: String, message: String): NodeActionResult =
                NodeActionResult(ok = false, code = code, message = message)

            fun outcomeUnknown(): NodeActionResult = failure(
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "The action may have executed, but the system did not return a result in time; re-observe first to avoid repeating it",
            )
        }
    }

    private enum class ActionDispatch {
        NOT_DISPATCHED,
        ACCEPTED,
        REJECTED,
        OUTCOME_UNKNOWN,
    }

    private enum class GestureDispatch {
        COMPLETED,
        CANCELLED,
        NOT_DISPATCHED,
        OUTCOME_UNKNOWN,
    }

    private sealed interface MainThreadCallResult<out T> {
        data class Completed<T>(val value: T?) : MainThreadCallResult<T>
        data object NOT_STARTED : MainThreadCallResult<Nothing>
        data object OUTCOME_UNKNOWN : MainThreadCallResult<Nothing>
    }

    data class ScreenshotCaptureResult(
        val bitmap: Bitmap?,
        val complete: Boolean,
        val expectedWindows: Int,
        val capturedWindows: Int,
        val missingWindowIds: List<Int>,
        val failureCodes: Map<Int, Int>,
        val timedOut: Boolean,
        val criticalWindowMissing: Boolean,
    ) {
        val partial: Boolean get() = bitmap != null && !complete

        companion object {
            fun unavailable(): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = 0,
                capturedWindows = 0,
                missingWindowIds = emptyList(),
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = false,
            )

            fun blockedByUnknownWindow(
                expectedWindows: Int,
                windowIds: List<Int>,
            ): ScreenshotCaptureResult = ScreenshotCaptureResult(
                bitmap = null,
                complete = false,
                expectedWindows = expectedWindows,
                capturedWindows = 0,
                missingWindowIds = windowIds,
                failureCodes = emptyMap(),
                timedOut = false,
                criticalWindowMissing = true,
            )
        }
    }

    data class ClipboardReadResult(
        val ok: Boolean,
        val text: String = "",
        val code: String = "",
    ) {
        companion object {
            fun failure(code: String = "CLIPBOARD_UNAVAILABLE_OR_EMPTY"): ClipboardReadResult =
                ClipboardReadResult(ok = false, code = code)
        }
    }

    internal data class ScrollActionResult(
        val ok: Boolean,
        val direction: ScrollDirection,
        val moved: Boolean,
        val atBoundary: Boolean?,
        val code: String = "",
        val message: String = "",
        val method: String = "",
        val deltaX: Int? = null,
        val deltaY: Int? = null,
        val verifiedBy: String = "",
        val elapsedMs: Long = 0L,
        val targetIndex: Int? = null,
    ) {
        companion object {
            fun boundary(
                direction: ScrollDirection,
                method: String,
                targetIndex: Int?,
                elapsedMs: Long,
            ): ScrollActionResult = ScrollActionResult(
                ok = true,
                direction = direction,
                moved = false,
                atBoundary = true,
                method = method,
                verifiedBy = "action_list",
                elapsedMs = elapsedMs,
                targetIndex = targetIndex,
            )

            fun failure(
                direction: ScrollDirection,
                code: String,
                message: String,
                method: String = "",
                targetIndex: Int? = null,
                deltaX: Int? = null,
                deltaY: Int? = null,
                elapsedMs: Long = 0L,
            ): ScrollActionResult = ScrollActionResult(
                ok = false,
                direction = direction,
                moved = false,
                atBoundary = null,
                code = code,
                message = message,
                method = method,
                targetIndex = targetIndex,
                deltaX = deltaX,
                deltaY = deltaY,
                elapsedMs = elapsedMs,
            )
        }
    }

    enum class NodeSnapshotStatus {
        AVAILABLE, EMPTY, NO_WINDOW, ROOT_UNAVAILABLE, TIMEOUT, BUSY, SERVICE_UNAVAILABLE, QUERY_FAILED,
    }

    data class ObservedWindow(
        val packageName: String?,
        val windowId: Int,
        val displayId: Int,
        val focused: Boolean,
        val active: Boolean,
    )

    data class NodeSnapshotObservation(
        val status: NodeSnapshotStatus,
        val snapshot: NodeSnapshot? = null,
        val window: ObservedWindow? = null,
    )

    class NodeSnapshot internal constructor(
        val id: String,
        internal val serviceToken: Long,
        val packageName: String,
        val windowId: Int,
        internal val contentGeneration: Long,
        val capturedAtElapsedMs: Long,
        val truncated: Boolean,
        internal val indexedNodes: List<IndexedNode>,
        val displayId: Int = android.view.Display.DEFAULT_DISPLAY,
    ) {
        val nodes: List<UiNode> = indexedNodes.map(IndexedNode::toUiNode)

        internal fun hasUnambiguousIdentity(target: IndexedNode): Boolean {
            if (!target.hasStrongIdentity()) return false
            val hasUniqueId = target.uniqueId.isNotBlank()
            val matches = if (hasUniqueId) {
                indexedNodes.count { candidate -> candidate.uniqueId == target.uniqueId }
            } else {
                indexedNodes.count(target::sameSemanticIdentity)
            }
            return AccessibilityIdentityFreshnessPolicy.canBypassContentChange(
                hasUniqueId = hasUniqueId,
                snapshotTruncated = truncated,
                identityMatchCount = matches,
            )
        }
    }

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        /** The state of a toggle, checkbox, etc.; null means the node is not checkable. */
        val checked: Boolean? = null,
        val selected: Boolean = false,
        /** The input field's hint, meaningful only when the content is empty, used to identify which input field this is. */
        val hint: String = "",
    )

    internal data class IndexedNode(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val uniqueId: String,
        val windowId: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean,
        val checked: Boolean?,
        val selected: Boolean,
        val hint: String,
        val clickTarget: NodeActionTarget?,
        val longClickTarget: NodeActionTarget?,
        val scrollTarget: NodeActionTarget?,
    ) {
        fun hasStrongIdentity(): Boolean = identity().strong

        fun sameSemanticIdentity(other: IndexedNode): Boolean =
            windowId == other.windowId &&
                packageName == other.packageName &&
                className == other.className &&
                viewId == other.viewId &&
                text == other.text &&
                desc == other.desc

        fun identityMatches(refreshed: AccessibilityNodeInfo): Boolean =
            identity().matches(refreshed.toIdentity())

        private fun identity(): AccessibilityNodeIdentity = AccessibilityNodeIdentity(
            uniqueId = uniqueId,
            windowId = windowId,
            packageName = packageName,
            className = className,
            viewId = viewId,
            text = text,
            description = desc,
            password = password,
        )

        private fun AccessibilityNodeInfo.toIdentity(): AccessibilityNodeIdentity =
            AccessibilityNodeIdentity(
                uniqueId = uniqueId.orEmpty(),
                windowId = windowId,
                packageName = packageName?.toString().orEmpty(),
                className = className?.toString().orEmpty(),
                viewId = viewIdResourceName.orEmpty(),
                text = (if (isShowingHintText) "" else text?.toString().orEmpty()).take(120),
                description = contentDescription?.toString().orEmpty().take(120),
                password = isPassword,
            )

        fun toUiNode(): UiNode =
            UiNode(
                index = index,
                text = text,
                desc = desc,
                className = className,
                packageName = packageName,
                viewId = viewId,
                bounds = bounds,
                clickable = clickable,
                longClickable = longClickable,
                scrollable = scrollable,
                focused = focused,
                editable = editable,
                password = password,
                enabled = enabled,
                checked = checked,
                selected = selected,
                hint = hint,
            )
    }

    internal data class NodeActionTarget(
        val node: AccessibilityNodeInfo,
        val identity: AccessibilityNodeIdentity,
    ) {
        fun resolveFor(descendant: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!runCatching { node.refresh() }.getOrDefault(false)) return null
            if (!node.isVisibleToUser || !node.isEnabled) return null
            val refreshedIdentity = AccessibilityNodeIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                windowId = node.windowId,
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                text = node.text?.toString().orEmpty().take(120),
                description = node.contentDescription?.toString().orEmpty().take(120),
                password = node.isPassword,
            )
            if (!identity.matches(refreshedIdentity)) return null
            var current: AccessibilityNodeInfo? = descendant
            var depth = 0
            while (current != null && depth <= MAX_UI_TREE_DEPTH) {
                if (current == node) return node
                current = current.parent
                depth++
            }
            return null
        }

        companion object {
            fun capture(node: AccessibilityNodeInfo): NodeActionTarget = NodeActionTarget(
                node = node,
                identity = AccessibilityNodeIdentity(
                    uniqueId = node.uniqueId.orEmpty(),
                    windowId = node.windowId,
                    packageName = node.packageName?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty().take(120),
                    description = node.contentDescription?.toString().orEmpty().take(120),
                    password = node.isPassword,
                ),
            )
        }
    }

    private data class ScrollCandidate(
        val node: AccessibilityNodeInfo,
        val supportRank: Int,
        val depth: Int,
        val area: Long,
    )

    private data class ScrollAnchor(
        val key: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class ScrollMethod(
        val actionId: Int,
        val name: String,
        val args: Bundle? = null,
    )

    private data class ScrollSignal(
        val sequence: Long,
        val packageName: String,
        val windowId: Int,
        val deltaX: Int,
        val deltaY: Int,
        val scrollX: Int,
        val scrollY: Int,
        val maxScrollX: Int,
        val maxScrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val sourceUniqueId: String,
        val sourceViewId: String,
        val sourceClassName: String,
        val sourceBounds: Rect,
    ) {
        fun axisDelta(axis: ScrollAxis): Int? = ScrollEvidenceContract.normalizeAccessibilityDelta(
            when (axis) {
            ScrollAxis.HORIZONTAL -> deltaX
            ScrollAxis.VERTICAL -> deltaY
            },
        )

        fun matchesTarget(target: ScrollTargetIdentity): Boolean {
            if (target.uniqueId.isNotBlank() && sourceUniqueId.isNotBlank()) {
                return target.uniqueId == sourceUniqueId
            }
            if (target.viewId.isNotBlank() && sourceViewId.isNotBlank()) {
                return target.viewId == sourceViewId && target.className == sourceClassName
            }
            return target.className == sourceClassName && target.bounds == sourceBounds
        }
    }

    private data class ScrollTargetIdentity(
        val uniqueId: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
    ) {
        companion object {
            fun from(node: AccessibilityNodeInfo): ScrollTargetIdentity = ScrollTargetIdentity(
                uniqueId = node.uniqueId.orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = Rect().also(node::getBoundsInScreen),
            )
        }
    }

    companion object {
        private const val CLIP_LABEL = "eta_agent"
        private const val WINDOW_POLL_FALLBACK_MS = 80L
        private const val MAX_UI_TREE_DEPTH = 24
        private const val UI_TREE_VISIT_MULTIPLIER = 8
        private const val MIN_UI_TREE_VISITED_NODES = 128
        private const val MAX_UI_TREE_VISITED_NODES = 960
        private const val MAX_FOCUS_SEARCH_NODES = 512
        private const val MAX_SCROLL_SEARCH_NODES = 512
        private const val SCROLL_ANCHOR_NODE_LIMIT = 48
        private const val SCROLL_GESTURE_DURATION_MS = 300L
        private const val SCROLL_VERIFY_TIMEOUT_MS = 520L
        private const val GESTURE_CALLBACK_GRACE_MS = 1_500L
        private const val MAIN_SYNC_TIMEOUT_SECONDS = 3L
        private const val SCROLL_EVENT_OBSERVATION_TIMEOUT_MS =
            MAIN_SYNC_TIMEOUT_SECONDS * 1_000L +
                SCROLL_GESTURE_DURATION_MS +
                GESTURE_CALLBACK_GRACE_MS +
                SCROLL_VERIFY_TIMEOUT_MS
        private const val SCROLL_EVENT_QUEUE_CAPACITY = 1
        private const val MAX_SCROLL_SIGNALS = 64

        private val GESTURE_CALLBACK_THREAD = HandlerThread("agent-gesture-callback").apply {
            isDaemon = true
            start()
        }
        private val GESTURE_CALLBACK_HANDLER = Handler(GESTURE_CALLBACK_THREAD.looper)

        /**
         * The process-level executor is not shutdownNow-ed on service reconnect; otherwise
         * ScreenshotResults already created by the framework and still in the queue could not
         * enter the callback to release their HardwareBuffer.
         */
        private val SCREENSHOT_EXECUTOR: ExecutorService =
            Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "agent-screenshot-callback").apply { isDaemon = true }
            }

        private val SCROLL_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id,
        )
        private val VERTICAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
        )
        private val HORIZONTAL_DIRECTION_ACTION_IDS = setOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id,
        )

        private val SERVICE_TOKENS = AtomicLong(0)
        private val SNAPSHOT_IDS = AtomicLong(0)
        private val CLIP_IDS = AtomicLong(0)

        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun current(): AgentAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }

    private sealed interface NodeValidation {
        data class Valid(val indexedNode: IndexedNode) : NodeValidation
        data class Invalid(val result: NodeActionResult) : NodeValidation
    }
}
