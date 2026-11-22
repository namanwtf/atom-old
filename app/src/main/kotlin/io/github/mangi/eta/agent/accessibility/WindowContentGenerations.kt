package io.github.mangi.eta.agent.accessibility

/** Window IDs are unique across displays; the monotonic sequence ensures that an ID reused after removal or capacity eviction never reuses an old snapshot generation. */
internal class WindowContentGenerations(private val capacity: Int = 256) {
    private val generations = linkedMapOf<Int, Long>()
    private var sequence = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun current(windowId: Int): Long = generations[windowId] ?: advance(windowId)

    @Synchronized
    fun advance(windowId: Int): Long {
        val generation = ++sequence
        generations.remove(windowId)
        generations[windowId] = generation
        while (generations.size > capacity) {
            generations.remove(generations.keys.first())
        }
        return generation
    }

    @Synchronized
    fun remove(windowId: Int) {
        generations.remove(windowId)
    }

    @Synchronized
    fun clear() {
        generations.clear()
    }
}
