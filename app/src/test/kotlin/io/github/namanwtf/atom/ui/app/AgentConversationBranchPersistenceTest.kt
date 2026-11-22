package io.github.namanwtf.atom.ui.app

import android.content.Context
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage
import io.github.namanwtf.atom.agent.roleplay.CharacterCardCodec
import io.github.namanwtf.atom.agent.roleplay.RoleplayBinding
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageLink
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageState
import io.github.namanwtf.atom.data.db.ChunkedTextDao
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.model.ReasoningEffort
import io.github.namanwtf.atom.data.repository.ConversationBranchBoundary
import io.github.namanwtf.atom.data.repository.ConversationBranchUnavailable
import io.github.namanwtf.atom.ui.model.AgentChatHomeUiState
import io.github.namanwtf.atom.ui.model.AgentMessageUi
import io.github.namanwtf.atom.ui.model.PendingImageUi
import io.github.namanwtf.atom.ui.model.QueuedMessageUi
import io.github.namanwtf.atom.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class AgentConversationBranchPersistenceTest {
    private lateinit var context: Context
    private val dao get() = AtomDatabase.get(context).conversationDao()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AtomDatabase.closeForTests()
        context.deleteDatabase("atom.db")
    }

    @After
    fun tearDown() = AtomDatabase.closeForTests()

    @Test
    fun branchCopiesUnloadedPrefixAndRebuildsHistoryWithoutFutureSummary() = runBlocking {
        val original = conversation(180).let { it.copy(history = listOf(ConversationMessage(
            "assistant", "summary containing a later secret", contextSummary = true, compactedUserTurns = 180, summaryThroughUserTurn = 180,
        ))) }
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(original))
        val source = AgentConversationStore.loadConversation(context, "source")!!
        assertTrue(source.hasEarlierMessages)
        assertFalse(source.messages.any { it.id == "assistant-30-0" })

        val child = persistence.branch(context, "source", "assistant-30-0", "child", "original conversation · branch", 2L)
        val copied = AgentConversationStore.loadAllMessages(context, "child")
        assertEquals(62, copied.size)
        assertEquals("answer 30", (copied.last() as AgentMessageUi).content)
        assertEquals(original.journal.take(62).map { it.content }, child.history.map { it.content })
        assertEquals(child.history, child.journal)
        assertFalse(child.history.any { it.contextSummary || "secret" in it.content })
        assertEquals(360, dao.messageCount("source"))
        assertEquals(original.history, AgentConversationStore.loadConversation(context, "source")!!.history)
        assertTrue(original.messages.map { it.id }.toSet().intersect(copied.map { it.id }.toSet()).isEmpty())
        assertTrue(child.appliedRuntimeRunIds.isEmpty())
        assertEquals("", child.input)
        assertTrue(child.pendingImages.isEmpty())
        assertTrue(child.queuedMessages.isEmpty())
        assertEquals("model-local", child.modelId)
        assertEquals(ReasoningEffort.HIGH, child.reasoningEffort)
        assertFalse(dao.conversation("child")!!.isPinned)
    }

    @Test
    fun branchAfterCompactionKeepsActiveSummaryHistoryAndRetainsFullJournalPrefix() = runBlocking {
        val base = conversation(5)
        val summary = ConversationMessage(
            role = "assistant",
            content = "first three rounds summary",
            contextSummary = true,
            compactedUserTurns = 3,
            summaryThroughUserTurn = 3,
            compactionArchiveId = "fold-1",
        )
        val compacted = base.copy(
            history = listOf(summary) + base.journal.drop(6),
        )
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(compacted))

        val child = persistence.branch(context, "source", "assistant-4-0", "child", "branch after compaction", 2L)
        assertEquals(10, child.journal.size)
        assertEquals(5, child.history.size)
        assertTrue(child.history.first().contextSummary)
        assertEquals("first three rounds summary", child.history.first().content)
        assertEquals(listOf("question 3", "answer 3", "question 4", "answer 4"), child.history.drop(1).map { it.content })

        val loaded = AgentConversationStore.loadConversation(context, "child")!!
        assertEquals(child.history, loaded.history)
        assertEquals(child.journal, loaded.journal)

        val earlier = persistence.branch(
            context,
            "child",
            ConversationBranchBoundary.remapId("child", "assistant-1-0"),
            "grandchild",
            "fall back to the earlier branch",
            3L,
        )
        assertFalse(earlier.history.any { it.contextSummary })
        assertEquals(base.journal.take(4).map { it.content }, earlier.history.map { it.content })
    }

    @Test
    fun queuedOldSnapshotsPreserveCommittedBranchUntilPublicationThenAllowDeletion() = runBlocking {
        val initial = snapshot(conversation(100))
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val child = persistence.branch(context, "source", "assistant-99-0", "child", "branch", 2L)
        assertTrue(child.hasEarlierMessages)
        repeat(2) { persistence.save(context, initial) }
        assertEquals(200, dao.messageCount("child"))

        val published = initial.copy(conversationsById = initial.conversationsById + ("child" to child),
            titles = initial.titles + ("child" to "branch"), updatedAt = initial.updatedAt + ("child" to 2L))
        persistence.save(context, published)
        persistence.save(context, initial)
        assertNull(dao.conversation("child"))
        assertEquals(200, dao.messageCount("source"))
    }

    @Test
    fun longBodiesImagesAndCheckpointChunksSurviveDeletingSourceAndRestarting() = runBlocking {
        val longText = "long body😀".repeat(30_000)
        val image = "data:image/png;base64," + "A".repeat(180_000)
        val original = conversation(1).copy(
            messages = listOf(UserMessageUi("user-0", "image", images = listOf(image)), AgentMessageUi("assistant-0-0", longText)),
            history = listOf(ConversationMessage("user", "image", messageId = "user-0"),
                ConversationMessage("assistant", longText, messageId = "assistant-0")),
            journal = emptyList(),
        )
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(original))
        persistence.branch(context, "source", "assistant-0-0", "child", "branch", 2L)
        dao.deleteConversation("source")
        AtomDatabase.closeForTests()

        val copied = AgentConversationStore.loadAllMessages(context, "child")
        assertEquals(image, (copied.first() as UserMessageUi).images.single())
        assertEquals(longText, (copied.last() as AgentMessageUi).content)
        assertEquals(longText, AgentConversationStore.loadConversation(context, "child")!!.journal.last().content)
        val rows = dao.messageRowsPage("child", 10, 0)
        assertTrue(rows.last().content.startsWith(ChunkedTextDao.REFERENCE_PREFIX))
        assertTrue(dao.textChunks("conversation_messages", copied.last().id, "content", 100, 0).isNotEmpty())
    }

    @Test
    fun roleplayCandidatesBlockLinksAndGreetingRemainIndependentAcrossRepeatedBranches() = runBlocking {
        val binding = RoleplayBinding(characterId = "character", characterName = "traveler", userName = "friend", userDescription = "companion",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("traveler")))
        val journal = listOf(ConversationMessage("assistant", "opening line", messageId = "greeting-source"),
            ConversationMessage("user", "depart", messageId = "user-0"),
            ConversationMessage("assistant", "first block\n\nsecond block", messageId = "assistant-0"),
            ConversationMessage("user", "follow-up question", messageId = "user-1"),
            ConversationMessage("assistant", "follow-up answer", messageId = "assistant-1"))
        var source = conversation(0).copy(roleplay = binding, history = journal, journal = journal,
            messages = listOf(AgentMessageUi("greeting-source", "opening line"), UserMessageUi("user-0", "depart"),
                AgentMessageUi("assistant-0-0", "first block"), AgentMessageUi("assistant-0-1", "second block"),
                UserMessageUi("user-1", "follow-up question"), AgentMessageUi("assistant-1-0", "follow-up answer")),
            roleplayMessages = RoleplayMessageState(links = mapOf(
                "greeting-source" to RoleplayMessageLink("greeting-source"), "user-0" to RoleplayMessageLink("user-0"),
                "assistant-0-0" to RoleplayMessageLink("assistant-0", 0), "assistant-0-1" to RoleplayMessageLink("assistant-0", 1),
                "user-1" to RoleplayMessageLink("user-1"), "assistant-1-0" to RoleplayMessageLink("assistant-1"))))
        source = RoleplayConversationReducer.edit(source, "assistant-0-1", "rewrite the second block")!!
        source = RoleplayConversationReducer.edit(source, "assistant-1-0", "follow-up rewrite")!!
        val initial = snapshot(source)
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val child = persistence.branch(context, "source", "assistant-0-1", "child", "branch", 2L)
        val target = ConversationBranchBoundary.remapId("child", "assistant-0-1")
        assertEquals(binding, child.roleplay)
        assertEquals("first block\n\nrewrite the second block", child.history.last().content)
        assertEquals("first block\n\nsecond block", child.journal.last().content)
        assertEquals(listOf("second block", "rewrite the second block"), child.roleplayMessages.revisions.getValue(target).candidates)
        assertEquals(setOf(target), child.roleplayMessages.revisions.keys)
        assertTrue(child.messages.first().id.startsWith("greeting-"))
        assertEquals(1, (child.messages.last() as AgentMessageUi).selectedCandidate)

        val grandchild = persistence.branch(context, "child", target, "grandchild", "branch again", 3L)
        assertEquals(child.history.map { it.content }, grandchild.history.map { it.content })
        val changedChild = RoleplayConversationReducer.select(child, target, 0)!!
        persistence.save(context, initial.copy(conversationsById = initial.conversationsById + ("child" to changedChild),
            titles = initial.titles + ("child" to "branch"), updatedAt = initial.updatedAt + ("child" to 2L)))
        assertEquals("first block\n\nsecond block", AgentConversationStore.loadConversation(context, "child")!!.history.last().content)
        assertEquals("first block\n\nrewrite the second block", AgentConversationStore.loadConversation(context, "grandchild")!!.history.last().content)
        assertEquals(source.history, AgentConversationStore.loadConversation(context, "source")!!.history)

        val greeting = persistence.branch(context, "child", child.messages.first().id, "greeting-child", "opening branch", 4L)
        assertEquals(1, greeting.messages.size)
        assertEquals("opening line", greeting.history.single().content)
    }

    @Test
    fun failedWriteRollsBackWholeBranchAndCanBeRetried() = runBlocking {
        val initial = snapshot(conversation(3))
        val persistence = AgentConversationPersistence()
        persistence.save(context, initial)
        val database = AtomDatabase.get(context)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_branch BEFORE INSERT ON conversation_messages " +
            "WHEN NEW.conversation_id = 'child' AND NEW.sort_index = 1 BEGIN SELECT RAISE(ABORT, 'branch_test_failure'); END")
        var failed = false
        try { persistence.branch(context, "source", "assistant-2-0", "child", "branch", 2L) } catch (_: android.database.SQLException) { failed = true }
        assertTrue(failed)
        assertNull(dao.conversation("child"))
        assertNull(dao.contextCheckpointRow("child"))
        assertEquals(0, dao.messageCount("child"))
        assertEquals(6, dao.messageCount("source"))
        persistence.save(context, initial)
        assertNull(dao.conversation("child"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_branch")
        persistence.branch(context, "source", "assistant-2-0", "child", "branch", 2L)
        assertEquals(6, dao.messageCount("child"))
    }

    @Test
    fun missingLegacyHistoryRefusesBranchWithoutChangingSource() = runBlocking {
        val source = conversation(3).copy(journal = listOf(ConversationMessage("assistant", "lost old history",
            contextSummary = true, compactedUserTurns = 3, summaryThroughUserTurn = 3)))
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(source))
        var refused = false
        try { persistence.branch(context, "source", "assistant-0-0", "child", "branch", 2L) }
        catch (_: ConversationBranchUnavailable) { refused = true }
        assertTrue(refused)
        assertEquals(1, dao.conversationIdentities().size)
        assertEquals(6, dao.messageCount("source"))
        assertEquals(source.journal, AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("source")!!.journalJson))
    }

    private fun conversation(turns: Int): AgentChatHomeUiState {
        val history = (0 until turns).flatMap { listOf(ConversationMessage("user", "question $it", messageId = "user-$it"),
            ConversationMessage("assistant", "answer $it", messageId = "assistant-$it")) }
        return AgentChatHomeUiState(
            messages = (0 until turns).flatMap { listOf(UserMessageUi("user-$it", "question $it"), AgentMessageUi("assistant-$it-0", "answer $it")) },
            history = history, journal = history, input = "source draft", isStreaming = false, thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH, modelId = "model-local", appliedRuntimeRunIds = listOf("old-run"),
            pendingImages = listOf(PendingImageUi("pending", "uri", "data", "image/png")),
            queuedMessages = listOf(QueuedMessageUi(id = "queued", content = "pending")),
        )
    }

    private fun snapshot(source: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
        "source", mapOf("source" to source), mapOf("source" to "original conversation"), mapOf("source" to 1L), pinnedIds = setOf("source"),
    )
}
