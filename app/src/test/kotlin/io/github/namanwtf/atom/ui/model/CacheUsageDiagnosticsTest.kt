package io.github.namanwtf.atom.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheUsageDiagnosticsTest {
    @Test
    fun attributionSatisfiesExactTokenIdentityAcrossGrowthCompactionAndTtlExpiry() {
        val messages = listOf(
            // round 1: cold start 10,000 (all counted as incremental new content)
            AgentMessageUi(
                id = "a1",
                content = "r1",
                usage = TokenUsageUi(
                    contextTokens = 10_200,
                    inputTokens = 10_000,
                    cachedTokens = 0,
                    outputTokens = 200,
                    stepCount = 1,
                ),
            ),
            // round 2: grew to 12,000, hits 10,000, increment 2,000
            AgentMessageUi(
                id = "a2",
                content = "r2",
                usage = TokenUsageUi(
                    contextTokens = 12_300,
                    inputTokens = 12_000,
                    cachedTokens = 10_000,
                    outputTokens = 300,
                    stepCount = 1,
                ),
            ),
            // compaction occurs (prior text 80,000, summary output 1,000, the compaction request hits 98% cache)
            SystemNoticeMessageUi(
                id = "c1",
                code = SystemNoticeCode.ContextCompaction,
                archiveId = "fold-1",
                tokensBefore = 80_000,
                usage = TokenUsageUi(
                    inputTokens = 80_500,
                    cachedTokens = 79_000,
                    outputTokens = 1_000,
                ),
            ),
            // first round after compaction: rebuilt context to 15,000 (4,000 hits, 11,000 counted as compaction re-payment)
            AgentMessageUi(
                id = "a3",
                content = "r3",
                usage = TokenUsageUi(
                    contextTokens = 15_200,
                    inputTokens = 15_000,
                    cachedTokens = 4_000,
                    outputTokens = 200,
                    stepCount = 1,
                ),
            ),
            // later the TTL expires: input 16,000, cached = 0 -> 1,000 is incremental, 15,000 is a non-incremental miss
            AgentMessageUi(
                id = "a4",
                content = "r4",
                usage = TokenUsageUi(
                    contextTokens = 16_200,
                    inputTokens = 16_000,
                    cachedTokens = 0,
                    outputTokens = 200,
                    stepCount = 1,
                ),
            ),
        )

        val report = CacheUsageDiagnostics.analyze(messages)
        val attr = report.attribution
        assertTrue(attr.identityValid)
        assertEquals(53_000, attr.totalInputTokens)
        assertEquals(14_000, attr.cachedTokens)
        assertEquals(13_000, attr.newContentTokens)
        assertEquals(11_000, attr.compactionRepayTokens)
        assertEquals(15_000, attr.ttlOrPrefixMissTokens)
        assertEquals(
            attr.totalInputTokens,
            attr.cachedTokens + attr.newContentTokens + attr.compactionRepayTokens + attr.ttlOrPrefixMissTokens,
        )

        val payback = checkNotNull(report.lastCompactionPayback)
        assertEquals("fold-1", payback.archiveId)
        assertEquals(2, payback.turnsAfter)
        assertNotNull(payback.paybackTurns)
        assertTrue(payback.paidBack)
    }

    @Test
    fun paybackMarksNotPaidBackWhenTooFewTurnsFollowExpensiveCompaction() {
        val messages = listOf(
            AgentMessageUi(
                id = "a1",
                content = "r1",
                usage = TokenUsageUi(inputTokens = 40_000, cachedTokens = 35_000, outputTokens = 200, stepCount = 1),
            ),
            SystemNoticeMessageUi(
                id = "c1",
                code = SystemNoticeCode.ContextCompaction,
                archiveId = "fold-1",
                tokensBefore = 40_000,
                usage = TokenUsageUi(
                    inputTokens = 40_000,
                    cachedTokens = 0,
                    cacheWriteTokens = 38_000,
                    outputTokens = 3_000,
                ),
            ),
            AgentMessageUi(
                id = "a2",
                content = "r2",
                usage = TokenUsageUi(inputTokens = 12_000, cachedTokens = 2_000, outputTokens = 200, stepCount = 1),
            ),
        )

        val payback = checkNotNull(CacheUsageDiagnostics.analyze(messages).lastCompactionPayback)
        assertEquals(1, payback.turnsAfter)
        assertTrue((payback.paybackTurns ?: 0) > 1)
        assertFalse(payback.paidBack)
    }
}
