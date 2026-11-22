package io.github.namanwtf.atom.ui.markdown

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownParsingTest {
    @Test
    fun continuousAppendsPublishProgressBeforeTheStreamEnds() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        val chunk = "continue analyzing this section.\n\n"
        targets.send(StreamingMarkdownTarget(chunk, isStreaming = true))
        var parseCount = 0

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                parseCount += 1
                // a new batch arrives before the previous parse returns, simulating production speed continuously outpacing parse speed.
                if (parseCount < 20) {
                    targets.send(target.copy(content = target.content + chunk))
                } else {
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(20, published.size)
        assertTrue(published.all { !it.isComplete })
        assertEquals((1..20).map { chunk.repeat(it) }, published.map { it.originalSource })
    }

    @Test
    fun pendingTargetsAreCoalescedWithoutParsingEveryNetworkChunk() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val parsedSources = mutableListOf<String>()
        repeat(100) { targets.send(StreamingMarkdownTarget("content".repeat(it + 1), true)) }
        targets.close()

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                parsedSources += target.content
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = {},
        )

        assertEquals(listOf("content".repeat(100)), parsedSources)
    }

    @Test
    fun correctedSourceDoesNotPublishAnObsoleteSnapshot() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("old content", isStreaming = true))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (target.content == "old content") {
                    targets.send(StreamingMarkdownTarget("corrected content", isStreaming = false))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(listOf("corrected content"), published.map { it.originalSource })
        assertTrue(published.single().isComplete)
    }

    @Test
    fun completionWithUnchangedTextStillPublishesTheFinalSnapshot() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("**final content**", isStreaming = true))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (target.isStreaming) {
                    targets.send(target.copy(isStreaming = false))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(listOf(false, true), published.map { it.isComplete })
        assertNotNull(published.last().completedDocumentFor("**final content**"))
    }

    @Test
    fun resumedStreamDoesNotPublishAnObsoleteCompletion() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("the same content", isStreaming = false))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (!target.isStreaming) {
                    targets.send(target.copy(isStreaming = true))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(1, published.size)
        assertFalse(published.single().isComplete)
    }

    @Test
    fun disposingConsumerCancelsParsingWithoutPublishingLateResults() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val started = CompletableDeferred<Unit>()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("thinking content", isStreaming = true))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            consumeStreamingMarkdownTargets(
                targets = targets,
                parse = {
                    started.complete(Unit)
                    awaitCancellation()
                },
                publish = published::add,
            )
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(published.isEmpty())
        assertTrue(job.isCancelled)
    }
}
