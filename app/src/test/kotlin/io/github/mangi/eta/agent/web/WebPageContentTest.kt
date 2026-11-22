package io.github.mangi.eta.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageContentTest {
    private val url = "https://example.com/news/story"

    @Test fun htmlSelectsMainContentAndPreservesArticleHeadingAndAuthor() {
        val html = """
            <html><head><title>report &amp; analyze</title><style>.x{color:red}</style></head><body>
            <header>site navigation</header><nav>log in sign up</nav>
            <main><article><header><h1>headline</h1><p>Author A</p></header>
            <p>the first paragraph <a href="../source">the source material</a>.</p>
            <aside>ad recommendations</aside><p hidden>invisible content</p><p aria-hidden="true">hidden note</p>
            <script>window.secret = "must not output";</script><p>The second paragraph.</p></article></main>
            <footer>site copyright navigation</footer></body></html>
        """.trimIndent()
        val document = WebPageContent.extract(html, "text/html; charset=utf-8", url)
        assertEquals("report & analyze", document.title)
        assertTrue(document.content.contains("headline"))
        assertTrue(document.content.contains("Author A"))
        assertTrue(document.content.contains("the source material [1]"))
        assertTrue(document.content.contains("The second paragraph."))
        for (noise in listOf("site navigation", "log in", "ad recommendations", "invisible content", "hidden note", "must not output", "color:red", "site copyright navigation")) {
            assertFalse(noise, document.content.contains(noise))
        }
        assertEquals("https://example.com/source", document.links.single().url)
        assertFalse(document.truncated)
    }

    @Test fun mainKeepsMultipleArticlesAndArticleIsUsedWithoutMain() {
        val main = WebPageContent.extract("<main><article>first article</article><article>second article</article></main>", "text/html", url)
        assertTrue(main.content.contains("first article"))
        assertTrue(main.content.contains("second article"))
        val article = WebPageContent.extract("<div>content outside the page</div><article>the main article</article>", "text/html", url)
        assertEquals("the main article", article.content)
    }

    @Test fun bodyFallbackRetainsParagraphsEntitiesAndPreformattedCode() {
        val html = "<p>A &lt; B &amp; C</p><p>the next paragraph<br>line break</p><pre>  first\n    second</pre><table><tr><td>Column A</td><td>Column B</td></tr></table>"
        val document = WebPageContent.extract(html, "text/html", url)
        assertTrue(document.content.contains("A < B & C\n\nthe next paragraph\nline break"))
        assertTrue(document.content.contains("  first\n    second"))
        assertTrue(document.content.contains("Column A | Column B"))
    }

    @Test fun htmlLinksResolveAgainstBaseAndDeduplicateWithoutExecutableSchemes() {
        val html = """
            <head><base href="https://reference.example/docs/"></head><main>
            <a href="../spec?q=1&amp;x=2">material</a><a href="../spec?q=1&amp;x=2">cited again</a>
            <a href="javascript:alert(1)">script entry</a><a href="data:text/plain,test">inline data</a>
            <a href="mailto:test@example.com">email</a><a href="/other#part">section</a></main>
        """.trimIndent()
        val document = WebPageContent.extract(html, "application/xhtml+xml", url)
        assertEquals(listOf("https://reference.example/spec?q=1&x=2", "https://reference.example/other#part"), document.links.map { it.url })
        assertTrue(document.content.contains("material [1]"))
        assertTrue(document.content.contains("cited again [1]"))
        assertTrue(document.content.contains("section [2]"))
    }

    @Test fun textMarkdownAndJsonAreNotReformattedOrParsedAsHtml() {
        val fixtures = mapOf(
            "text/plain" to "  raw line\n\tpreserve indentation <tag>\n",
            "text/markdown" to "# title\n\n```html\n<b>source code</b>\n```\n[document](https://example.com/spec)\n",
            "application/problem+json; charset=UTF-8" to " {\n  \"value\": \"<p>not HTML</p>\"\n}\n",
        )
        for ((type, text) in fixtures) {
            val document = WebPageContent.extract(text, type, url)
            assertEquals(text, document.content)
            assertFalse(document.truncated)
        }
        assertEquals("json", WebPageContent.extract("null", "application/json", url).format)
    }

    @Test fun textLinkExtractionPreservesBalancedParenthesesAndSourceText() {
        val text = "[material](https://example.com/a_(b)), also see https://example.com/c."
        val document = WebPageContent.extract(text, "text/markdown", url)
        assertEquals(text, document.content)
        assertEquals(listOf("https://example.com/a_(b)", "https://example.com/c"), document.links.map { it.url })
    }

    @Test fun pageCursorNeverSplitsSupplementaryCharacters() {
        val document = WebPageContent.extract("ab😀cd\n", "text/plain", url)
        val first = WebPageContent.page(document, maxChars = 3)
        val second = WebPageContent.page(document, first.nextOffsetChars, 3)
        val third = WebPageContent.page(document, second.nextOffsetChars, 3)
        assertEquals("ab", first.content)
        assertEquals("😀c", second.content)
        assertEquals(document.content, first.content + second.content + third.content)
        assertEquals(document.content.length, third.nextOffsetChars)
        assertFalse(third.hasMore)
        assertFailure("INVALID_CONTENT_OFFSET") { WebPageContent.page(document, 3) }
        assertFailure("CONTENT_PAGE_TOO_SMALL") { WebPageContent.page(document, 2, 1) }
    }

    @Test fun sourceAndContentLimitsRemainVisibleAfterLastRetainedPage() {
        val text = "x".repeat(WebPageContent.MAX_INPUT_CHARS + 1)
        val document = WebPageContent.extract(text, "text/plain", url, sourceTruncated = true)
        assertEquals(WebPageContent.MAX_CONTENT_CHARS, document.content.length)
        assertEquals(setOf("source_limit", "input_limit", "content_limit"), document.truncationReasons.toSet())
        val last = WebPageContent.page(document, document.content.length - 3)
        assertFalse(last.hasMore)
        assertTrue(last.sourceTruncated)
    }

    @Test fun contentLimitKeepsAnIntactUnicodePrefix() {
        val prefix = "a".repeat(WebPageContent.MAX_CONTENT_CHARS - 1)
        val document = WebPageContent.extract(prefix + "😀after", "text/plain", url)
        assertEquals(prefix, document.content)
        assertTrue(document.truncated)
        val html = WebPageContent.extract("<article>$prefix😀after<p>subsequent</p></article>", "text/html", url)
        assertEquals(prefix, html.content)
        assertTrue(html.truncated)
    }

    @Test fun linksHaveIndependentCountAndAggregateSizeBudgets() {
        val many = (0 until 220).joinToString("") { "<a href='https://example.com/$it'>link$it</a> " }
        val document = WebPageContent.extract("<main>$many</main>", "text/html", url)
        assertEquals(WebPageContent.MAX_LINKS, document.links.size)
        assertTrue(document.linksTruncated)
        val long = (0 until 100).joinToString("") { "<a href='https://example.com/${"x".repeat(500)}/$it'>link$it</a> " }
        val longLinks = WebPageContent.extract("<main>$long</main>", "text/html", url)
        assertTrue(longLinks.linksTruncated)
        assertTrue(longLinks.links.sumOf { it.url.length + it.text.length } <= WebPageContent.MAX_LINK_TOTAL_CHARS)
        assertTrue(longLinks.content.contains("link99"))
    }

    @Test fun htmlTitleHasAnIndependentBudgetAndCanUseHeadingFallback() {
        val title = "x".repeat(WebPageContent.MAX_TITLE_CHARS + 10)
        val document = WebPageContent.extract("<title>$title</title><main>body</main>", "text/html", url)
        assertEquals(WebPageContent.MAX_TITLE_CHARS, document.title!!.length)
        assertTrue(document.titleTruncated)
        assertFalse(document.truncated)
        val heading = WebPageContent.extract("<article><h1>body title</h1>content</article>", "text/html", url)
        assertEquals("body title", heading.title)
    }

    @Test fun malformedHtmlIsToleratedWithoutRunningScriptContent() {
        val document = WebPageContent.extract("<main><p>first paragraph<p>second paragraph<a href='/source'>source<script>throw Error('secret')", "text/html", url)
        assertTrue(document.content.contains("first paragraph"))
        assertTrue(document.content.contains("second paragraph"))
        assertFalse(document.content.contains("secret"))
        assertEquals("https://example.com/source", document.links.single().url)
    }

    @Test fun unsupportedBinaryMimeAndInvalidRangesHaveExplicitErrors() {
        assertFailure("UNSUPPORTED_CONTENT_TYPE") { WebPageContent.extract("%PDF-1.7", "application/pdf", url) }
        val document = WebPageContent.extract("text", "text/plain", url)
        assertFailure("INVALID_CONTENT_RANGE") { WebPageContent.page(document, -1) }
        assertFailure("INVALID_CONTENT_RANGE") { WebPageContent.page(document, 5) }
    }

    @Test fun cancellationFromCallerPropagatesDuringHtmlTraversal() {
        var checks = 0
        try {
            WebPageContent.extract("<main>" + "<p>body</p>".repeat(30) + "</main>", "text/html", url, ensureActive = {
                if (++checks == 8) throw InterruptedException("cancelled")
            })
            throw AssertionError("cancelled extraction completed")
        } catch (_: InterruptedException) { assertEquals(8, checks) }
    }

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected $code")
        } catch (error: WebPageContentException) { assertEquals(code, error.code) }
    }
}
