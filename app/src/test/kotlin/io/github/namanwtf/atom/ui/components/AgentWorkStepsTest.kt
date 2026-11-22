package io.github.namanwtf.atom.ui.components

import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkStepsTest {

    @Test
    fun consecutiveSameToolCallsFormOneGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            tool("t2", "read_file"),
            tool("t3", "read_file"),
            tool("t4", "run_command"),
        ).toWorkSteps()

        assertEquals(2, steps.size)
        val group = steps[0] as AgentWorkStep.ToolGroup
        assertEquals(listOf("t1", "t2", "t3"), group.calls.map { it.id })
        assertTrue(steps[1] is AgentWorkStep.Single)
    }

    @Test
    fun thinkingBreaksToolGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            ThinkingMessageUi(id = "k1", content = "think", isStreaming = false),
            tool("t2", "read_file"),
        ).toWorkSteps()

        assertEquals(listOf("t1", "k1", "t2"), steps.map { it.key })
    }

    @Test
    fun browserCallsStayIndividual() {
        val steps = listOf(tool("b1", "browser_use"), tool("b2", "browser_use")).toWorkSteps()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it is AgentWorkStep.Single })
    }

    @Test
    fun groupKeyStaysStableWhileCallsAppend() {
        val first = listOf(tool("t1", "read_file"), tool("t2", "read_file")).toWorkSteps()
        val next = listOf(tool("t1", "read_file"), tool("t2", "read_file"), tool("t3", "read_file")).toWorkSteps()

        assertEquals(first.single().key, next.single().key)
    }

    @Test
    fun terminalOutcomeUsesLastOutputLine() {
        val message = tool("t1", "run_command", resultSummary = "execution complete\nline 1\nline 2\n…")

        assertEquals("line 2", message.outcomeLine())
    }

    @Test
    fun trivialSuccessHasNoOutcome() {
        assertNull(tool("t1", "observe_screen", resultSummary = "done").outcomeLine())
        assertEquals("2 images", tool("t2", "observe_screen", resultSummary = "done · 2 images").outcomeLine())
    }

    @Test
    fun failureOutcomeStripsPrefixAndCode() {
        val message = tool(
            "t1",
            "tap",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "failed · element not found · code=E_NOT_FOUND",
        )

        assertEquals("element not found", message.outcomeLine())
    }

    @Test
    fun failedTerminalFallsBackToOutput() {
        val message = tool(
            "t1",
            "run_command",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "failed · exit code 1\nno such file",
        )

        assertEquals("exit code 1", message.outcomeLine())
    }

    @Test
    fun runningToolHasNoOutcome() {
        assertNull(tool("t1", "read_file", status = ToolActivityStatusUi.Running, resultSummary = "x").outcomeLine())
    }

    private fun tool(
        id: String,
        name: String,
        status: ToolActivityStatusUi = ToolActivityStatusUi.Success,
        resultSummary: String? = null,
    ) = ToolActivityMessageUi(
        id = id,
        toolName = name,
        status = status,
        argumentsSummary = name,
        resultSummary = resultSummary,
    )
}
