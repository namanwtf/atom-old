package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Handles only SSE framing; each protocol interprets events itself and returns false at the terminal state without waiting for the server to close the connection. */
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    dispatchPartialFrameAtEof: Boolean = true,
    onEvent: (event: String, data: String) -> Boolean,
) {
    var event = ""
    val dataLines = mutableListOf<String>()

    fun dispatch(): Boolean {
        val name = event
        val payload = dataLines.joinToString("\n")
        event = ""
        dataLines.clear()
        if (payload.isBlank()) return true
        runController.throwIfCancelled()
        return onEvent(name, payload)
    }

    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        while (true) {
            runController.throwIfCancelled()
            val line = reader.readLine()
            if (line == null) {
                if (dispatchPartialFrameAtEof) dispatch()
                break
            }
            when {
                line.isEmpty() -> if (!dispatch()) break
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }
}
