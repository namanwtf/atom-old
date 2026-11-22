package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMarkdownExporterTest {

    private val labels = ConversationMarkdownExporter.Labels(
        user = "User",
        assistant = "Assistant",
        thinking = "Thinking",
        toolLineFormat = "Tool: %1\$s (%2\$s)",
        toolsLineFormat = "Tools: %1\$s",
        argumentsFormat = "Arguments: %1\$s",
        resultFormat = "Result: %1\$s",
        imagesFormat = "Images: %1\$d",
        toolStatusRunning = "Running",
        toolStatusSuccess = "Completed",
        toolStatusFailed = "Failed",
        toolStatusUnknown = "Result unknown",
        noticeStopped = "Stopped",
        noticeEmptyResult = "Empty result",
        noticeModelRetry = "Retrying",
        noticeRuntimeFailed = "Runtime failed",
        noticeInterrupted = "Interrupted",
    )

    @Test
    fun exportRendersFullConversationInOrder() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "hello"),
            AgentMessageUi(id = "a1", content = "Hello! **What can I help you with?**"),
            ThinkingMessageUi(id = "t1", content = "think first", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool1",
                toolName = "run_terminal_command",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "ls -la",
                command = "ls -la",
                resultSummary = "ok",
            ),
            ToolSummaryMessageUi(id = "ts1", tools = listOf("observe_screen", "tap_element")),
            SystemNoticeMessageUi(id = "n1", code = SystemNoticeCode.Stopped, detail = "user canceled"),
            AgentMessageUi(id = "a2", content = "end"),
        )

        val result = ConversationMarkdownExporter.export("weekly meeting notes", messages, labels)

        val expected = """
            # weekly meeting notes

            ## User

            hello

            ## Assistant

            Hello! **What can I help you with?**

            <details>
            <summary>Thinking</summary>

            think first

            </details>

            > Tool: run_terminal_command (Completed)
            >
            > ls -la
            >
            > Arguments: ls -la
            >
            > Result: ok

            > Tools: observe_screen, tap_element

            > Stopped
            >
            > user canceled

            ## Assistant

            end
        """.trimIndent()
        assertEquals(expected + "\n", result)
    }

    @Test
    fun exportParsesFileReferencesInUserContent() {
        val content = AgentFileReferencePromptCodec.format(
            request = "summarize this file",
            references = listOf(
                AgentFileReference("notes.txt", "/sdcard/notes.txt", AgentFileReferenceKind.File),
            ),
        )

        val result = ConversationMarkdownExporter.export(
            "t",
            listOf(UserMessageUi(id = "u", content = content)),
            labels,
        )

        assertTrue(result.contains("summarize this file"))
        assertTrue(result.contains("- /sdcard/notes.txt"))
        assertFalse(result.contains("# Files mentioned by the user:"))
    }

    @Test
    fun exportAnnotatesImagesWithoutEmbeddingDataUrls() {
        val message = UserMessageUi(
            id = "u",
            content = "look at this image",
            images = listOf("data:image/png;base64,AAAA", "data:image/jpeg;base64,BBBB"),
        )

        val result = ConversationMarkdownExporter.export("t", listOf(message), labels)

        assertTrue(result.contains("_Images: 2_"))
        assertFalse(result.contains("base64"))
    }

    @Test
    fun exportSkipsBlankStreamingAndNonContentMessages() {
        val messages = listOf(
            AgentMessageUi(id = "a", content = "", isStreaming = true),
            RunTraceMessageUi(id = "r", capabilities = emptyList()),
            SuggestionChipsMessageUi(id = "s", prompts = listOf("try this")),
        )

        val result = ConversationMarkdownExporter.export("t", messages, labels)

        assertEquals("# t\n", result)
    }

    @Test
    fun exportRendersToolStatusVariants() {
        val messages = listOf(
            ToolActivityMessageUi(
                id = "t1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "",
                resultSummary = null,
            ),
            ToolActivityMessageUi(
                id = "t2",
                toolName = "tap_element",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "confirm",
            ),
        )

        val result = ConversationMarkdownExporter.export("t", messages, labels)

        assertTrue(result.contains("> Tool: observe_screen (Failed)"))
        assertTrue(result.contains("> Tool: tap_element (Result unknown)"))
        assertTrue(result.contains("> Arguments: confirm"))
    }

    @Test
    fun exportSanitizesMultilineTitle() {
        val result = ConversationMarkdownExporter.export(
            "first line\nsecond line",
            listOf(UserMessageUi(id = "u", content = "hi")),
            labels,
        )

        assertTrue(result.startsWith("# first line second line\n"))
    }

    @Test
    fun defaultFileNameSanitizesTitle() {
        val name = ConversationMarkdownExporter.defaultFileName(
            title = "a/b\\c:d*e?f\"g<h>i|j",
            fallback = "Conversation",
            nowMillis = 0L,
        )

        assertTrue(name.startsWith("Eta-abcdefghij-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun defaultFileNameFallsBackWhenBlankAndCollapsesWhitespace() {
        val fallback = ConversationMarkdownExporter.defaultFileName(
            title = "   ",
            fallback = "Conversation",
            nowMillis = 0L,
        )
        val spaced = ConversationMarkdownExporter.defaultFileName(
            title = "hello   world\nagain",
            fallback = "Conversation",
            nowMillis = 0L,
        )

        assertTrue(fallback.startsWith("Eta-Conversation-"))
        assertTrue(spaced.startsWith("Eta-hello world again-"))
    }

    @Test
    fun defaultFileNameTruncatesLongTitles() {
        val name = ConversationMarkdownExporter.defaultFileName(
            title = "x".repeat(100),
            fallback = "Conversation",
            nowMillis = 0L,
        )

        val match = Regex("^Eta-(.+)-\\d{8}-\\d{4}\\.md$").matchEntire(name)
        assertTrue(match != null)
        assertEquals(40, match!!.groupValues[1].length)
    }
}
