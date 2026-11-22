package io.github.namanwtf.atom.agent.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun spokenTextSkipsCodeAndKeepsLinkLabelAndInlineIdentifiers() {
        val text = SpeechText.readable("""
            # instructions

            see **settings** and [help](https://example.invalid), the parameter is `user_name`.

            ```kotlin
            secretCommand()
            ```

                anotherSecretCommand()

            Done.
        """.trimIndent())
        assertTrue(text.contains("instructions"))
        assertTrue(text.contains("settings"))
        assertTrue(text.contains("help"))
        assertTrue(text.contains("user_name"))
        assertFalse(text.contains("see the body"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("secretCommand"))
        assertFalse(text.contains("anotherSecretCommand"))
        assertFalse(text.contains("**"))
        assertTrue(text.endsWith("Done."))
    }

    @Test fun sentenceChunkingPreservesContentAndSurrogatePairs() {
        val text = "First sentence. Second sentence." + "hello😀".repeat(30)
        val chunks = SpeechText.chunks(text, 17)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 17 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun codeOnlyAnswersHaveNoSpeech() {
        for (markdown in listOf("```\nhello\n```", "    hello", "```kotlin\nhello")) {
            assertEquals("", SpeechText.readable(markdown))
        }
    }

    @Test fun tablesAreOmittedWithoutAnnouncementsAndPreserveSurroundingParagraphs() {
        val table = """
            | product | price | condition |
            | --- | ---: | --- |
            | Product A | ¥108 | available only after authorization |
            | Product B | ¥209 | no offline support |
        """.trimIndent()
        val text = SpeechText.readable("Conclusion first.\n\n$table\n\nContinue.")

        assertTrue(Regex("Conclusion first.\\n+Continue.").matches(text))
    }

    @Test fun tableOnlyAnswersHaveNoSpeechRegardlessOfCellContent() {
        for (table in listOf(
            """
                | product | price |
                | --- | ---: |
                | Product A | ¥108 |
                | Product B | ¥209 |
            """.trimIndent(),
            """
                | item | | note |
                | --- | --- | --- |
                | A | | normal |
                | B |
                | C | 3 | keep | extra cell |
            """.trimIndent(),
            """
                | approach | duration | memory |
                | --- | --- | --- |
                | [**A**](https://example.invalid/a) | 1.5 sec | 200 MB |
                | `B` | 2 sec | 100 MB |
            """.trimIndent(),
            "| item | quantity |\n| --- | --- |",
            """
                | type | content |
                | --- | --- |
                | image | ![diagram](https://example.invalid/diagram.png) |
                | embedded image | <img src="https://example.invalid/diagram.png"> |
                | bare URL | https://example.invalid/download |
            """.trimIndent(),
        )) {
            assertEquals(table, "", SpeechText.readable(table))
        }
    }

    @Test fun urlOnlyAnswersHaveNoSpeech() {
        for (markdown in listOf(
            "https://example.invalid/download?token=abc&lang=zh",
            "http://example.invalid/help",
            "www.example.invalid/help",
            "<https://example.invalid/help>",
            "[https://example.invalid/help](https://example.invalid/help)",
            "[www.example.invalid/help](https://example.invalid/help)",
            "https://example.test/document/install?language=Chinese",
            "[https://example.invalid/help][target]\n\n[target]: https://example.invalid/help",
        )) {
            assertEquals(markdown, "", SpeechText.readable(markdown))
        }
    }

    @Test fun codeAndLinksTogetherDoNotProducePlaceholderSpeech() {
        val text = SpeechText.readable("```sh\nrun_command\n```\n\nhttps://example.invalid/download")

        assertEquals("", text)
    }

    @Test fun imagesAndHtmlBlocksAreSkippedWithoutNoticesOrLostParagraphBoundaries() {
        for (content in listOf(
            "![diagram alt text](https://example.invalid/diagram.png)",
            "<div>HTMLcontent inside the block</div>",
            "<img src=\"https://example.invalid/diagram.png\" alt=\"image description\">",
        )) {
            assertEquals(content, "", SpeechText.readable(content))
            val text = SpeechText.readable("the preceding note.\n\n$content\n\nthe following note.")
            assertTrue(content, Regex("the preceding note.\\n+the following note.").matches(text))
        }
    }

    @Test fun descriptiveLinkLabelsSurviveInlineAndReferenceLinks() {
        val text = SpeechText.readable("""
            see [Help Center](https://example.invalid/help),[installation instructions][install],
            [Troubleshooting], then open [**advanced settings**](https://example.invalid/settings).

            [install]: https://example.invalid/install
            [Troubleshooting]: https://example.invalid/faq
        """.trimIndent())

        for (label in listOf("Help Center", "installation instructions", "Troubleshooting", "advanced settings")) {
            assertTrue(label, text.contains(label))
        }
        assertFalse(text.contains("example.invalid"))
        assertFalse(text.contains("[install]"))
        assertFalse(text.contains("**"))
    }

    @Test fun urlsDoNotConsumeChinesePunctuationOrFollowingInstructions() {
        for ((markdown, continuation) in listOf(
            "open https://example.invalid/a?x=1, then check permissions." to ", then check permissions.",
            "open https://example.invalid/help,then check permissions." to ",then check permissions.",
            "open https://example.invalid/help;then check permissions." to ";then check permissions.",
            "download http://example.invalid/pkg. Restart when done." to ". Restart when done.",
            "visit www.example.invalid/help, then return." to ", then return.",
            "Address: https://example.invalid/help, continue to the next step." to ", continue to the next step.",
            "view <https://example.invalid/help>. Then return." to ". Then return.",
        )) {
            val text = SpeechText.readable(markdown)
            assertFalse(markdown, text.contains("see body for link"))
            assertTrue(markdown, text.endsWith(continuation))
            assertFalse(markdown, text.contains("example.invalid"))
            assertFalse(markdown, text.contains("<"))
            assertFalse(markdown, text.contains(">"))
        }
    }

    @Test fun skippedLinksAndImagesDoNotJoinNeighboringWords() {
        for (markdown in listOf(
            "alpha<https://example.invalid/help>beta",
            "alpha[https://example.invalid/help](https://example.invalid/help)beta",
            "alpha[](https://example.invalid/help)beta",
            "alpha![image description](https://example.invalid/diagram.png)beta",
        )) {
            val text = SpeechText.readable(markdown)
            assertTrue(markdown, Regex("alpha\\s+beta").matches(text))
        }
    }

    @Test fun skippedLinksPreserveParagraphAndListBoundaries() {
        val text = SpeechText.readable("""
            the first paragraph

            https://example.invalid/help

            the second paragraph

            - keep the first item
            - <https://example.invalid/download>
            - keep the second item
        """.trimIndent())

        assertInOrder(text, "the first paragraph", "the second paragraph", "keep the first item", "keep the second item")
        assertTrue(Regex("the first paragraph\\n+").containsMatchIn(text))
        assertTrue(Regex("\\nthe second paragraph\\n").containsMatchIn(text))
        assertFalse(text.contains("example.invalid"))
        assertFalse(text.contains("see body for link"))
    }

    @Test fun orderedListsKeepTheirStartAndRenderedSequence() {
        val text = SpeechText.readable("""
            3. back up first
            3. then update
            3. final check
        """.trimIndent())

        var offset = 0
        for ((number, item) in listOf(3 to "back up first", 4 to "then update", 5 to "final check")) {
            val marker = Regex("(?<!\\d)$number(?!\\d)\\s*(?:item|step)?\\s*[::,.)]?\\s*${Regex.escape(item)}")
            val match = marker.find(text, startIndex = offset)
            assertNotNull("Expected item $number before '$item' in '$text'", match)
            offset = match!!.range.last + 1
        }
    }

    @Test fun taskListsKeepCompletedAndIncompleteStates() {
        val text = SpeechText.readable("""
            - [x] backup completed
            - [ ] not yet updated
            - [X] verified
            - don't change permissions
        """.trimIndent())

        assertInOrder(text, "Done: backup completed", "Not done: not yet updated", "Done: verified", "don't change permissions")
        assertFalse(text.contains("[x]"))
        assertFalse(text.contains("[ ]"))
        assertFalse(text.contains("[X]"))
    }

    @Test fun numbersUnitsEnglishAndShortInlineIdentifiersRemainReadable() {
        val text = SpeechText.readable(
            "version v1.2.3, temperature -3.5°C, amount 1,234.50 yuan, length 10 mm, progress 42%;" +
                "OpenAI API parameter `user_name`, file `config.json`.",
        )

        for (content in listOf("v1.2.3", "-3.5°C", "¥1,234.50", "10 mm", "42%", "OpenAI API", "user_name", "config.json")) {
            assertTrue(content, text.contains(content))
        }
    }

    private fun assertInOrder(text: String, vararg expected: String) {
        fun compact(value: String) = value.replace(Regex("\\s+"), "").replace(':', ':')
        val actual = compact(text)
        var offset = 0
        for (part in expected) {
            val normalized = compact(part)
            val index = actual.indexOf(normalized, startIndex = offset)
            assertTrue("Expected '$part' after offset $offset in '$text'", index >= 0)
            offset = index + normalized.length
        }
    }
}
