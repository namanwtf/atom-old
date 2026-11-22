package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<String>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    val isPaused: Boolean
        get() = paused

    fun cancel() {
        lock.withLock {
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * Queue the supplement into the next turn. Steering does not cancel the current model request or tool batch.
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            steeringMessages.addLast(prompt)
        }
        return true
    }

    /** Consume one at a time by default, so a later supplement does not overtake the previous one's model turn. */
    fun pollSteeringMessage(): String? =
        lock.withLock { steeringMessages.pollFirst() }

    /**
     * Atomically consume the last steering before natural completion; if the queue is empty, permanently close this run's intake.
     * This way the Service does not still misreport a supplement as accepted after the loop has already returned.
     */
    fun pollSteeringOrSeal(): String? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * Pause execution: subsequent [throwIfCancelled] calls will block and suspend until [resume] or [cancel].
     * Called at checkpoints on the worker thread; it does not block the caller's thread.
     */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * Resume execution: wake the worker thread blocked on [throwIfCancelled] and continue from the suspension point.
     */
    fun resume() {
        lock.withLock {
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * Checkpoint: throw if already cancelled; if paused, block and suspend until resume or cancel.
     * Called at every round/step of the agent loop, implementing pausable-resumable execution and immediate termination on cancel.
     */
    fun throwIfCancelled() {
        lock.withLock {
            while (paused && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException : RuntimeException("Agent run cancelled")
