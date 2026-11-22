package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun searchSuggestionsStayInLoadedConversationAndNeverReachMessageStorage() = runBlocking {
        val message = AgentMessageUi("search-answer", "body and citations", searchSuggestionsHtml = listOf("<div>short-term suggestion</div>"))
        val conversation = state("search").copy(messages = listOf(message))
        AgentConversationPersistence().save(context, snapshot(mapOf("search" to conversation)))
        val restored = AgentConversationStore.loadAllMessages(context, "search").filterIsInstance<AgentMessageUi>().single()
        assertEquals(message.content, restored.content)
        assertTrue(restored.searchSuggestionsHtml.isEmpty())
        val unloaded = AgentConversationStore.unloadConversation(conversation)
        assertTrue((unloaded.messages.single() as AgentMessageUi).searchSuggestionsHtml.isEmpty())
    }

    @Test
    fun appendingOnlyWritesTheChangedConversationAndNewMessages() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "dormant" to state("dormant")))
        persistence.save(context, initial)
        installAudit()
        val active = initial.conversationsById.getValue("active")
        val addition = AgentModelClient.ConversationMessage(role = "user", content = "continue")
        val next = initial.copy(
            conversationsById = initial.conversationsById + ("active" to active.copy(
                messages = active.messages + UserMessageUi("new-user", "continue"),
                history = active.history + addition, journal = active.journal + addition,
                appliedRuntimeRunIds = listOf("applied-run"),
            )), updatedAt = initial.updatedAt + ("active" to 9L),
        )

        persistence.save(context, next)

        assertEquals(0, events("conversations", "dormant"))
        assertEquals(0, events("conversation_context_checkpoints", "dormant"))
        assertEquals(0, events("conversation_messages", "active-user"))
        assertEquals(0, events("conversation_messages", "active-thinking"))
        assertEquals(0, events("conversation_messages", "dormant-thinking"))
        assertEquals(1, events("conversation_messages", "new-user"))
        val rows = EtaDatabase.get(context).conversationDao().conversationEntities().associateBy { it.id }
        assertEquals(1L, rows.getValue("active").createdAt)
        assertEquals(9L, rows.getValue("active").updatedAt)
        assertEquals(next.conversationsById.getValue("active").journal, AgentConversationCodec.decodeTranscript(
            EtaDatabase.get(context).conversationDao().contextCheckpoint("active")!!.journalJson))
    }

    @Test
    fun selectionAndDraftChangesDoNotRewriteStoredContent() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "other" to state("other")))
        persistence.save(context, initial)
        installAudit()
        val next = initial.copy(selectedConversationId = "other", conversationsById = initial.conversationsById +
            ("active" to initial.conversationsById.getValue("active").copy(input = "unsent draft")))

        persistence.save(context, next)

        assertEquals(0, allEvents())
        assertEquals("other", EtaDatabase.get(context).conversationDao().state()?.selectedConversationId)
    }

    @Test
    fun contextCompressionDoesNotRewriteTheUnchangedJournalOrMessages() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active")))
        persistence.save(context, initial)
        installAudit()
        val next = initial.copy(conversationsById = mapOf("active" to initial.conversationsById.getValue("active").copy(
            history = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "summary", contextSummary = true)),
        )))

        persistence.save(context, next)

        assertEquals(0, chunkEvents("active", "journal"))
        assertEquals(0, events("conversation_messages", "active-thinking"))
        val restored = EtaDatabase.get(context).conversationDao().contextCheckpoint("active")!!
        assertEquals(initial.conversationsById.getValue("active").journal, AgentConversationCodec.decodeTranscript(restored.journalJson))
        assertEquals("summary", AgentConversationCodec.decodeTranscript(restored.historyJson).single().content)
    }

    @Test
    fun aFailedTransactionRollsBackAndTheSameWriterCanRetry() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active")))
        persistence.save(context, initial)
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_test_message BEFORE INSERT ON conversation_messages " +
            "WHEN NEW.id = 'rejected-user' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val next = initial.copy(conversationsById = mapOf("active" to initial.conversationsById.getValue("active").copy(
            messages = initial.conversationsById.getValue("active").messages + UserMessageUi("rejected-user", "new message"),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "new history")),
            appliedRuntimeRunIds = listOf("new-run"),
        )), titles = mapOf("active" to "new title"), updatedAt = mapOf("active" to 10L))
        var failed = false
        try { persistence.save(context, next) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals("active", dao.conversations().single().title)
        assertEquals(initial.conversationsById.getValue("active").history,
            AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("active")!!.historyJson))
        assertEquals("[]", dao.conversations().single().appliedRuntimeRunIdsJson)
        sql.execSQL("DROP TRIGGER reject_test_message")

        persistence.save(context, next)

        assertEquals("new title", dao.conversations().single().title)
        assertEquals(3, dao.messageCount("active"))
        assertEquals(next.conversationsById.getValue("active").history,
            AgentConversationCodec.decodeTranscript(dao.contextCheckpoint("active")!!.historyJson))
        assertEquals("[\"new-run\"]", dao.conversations().single().appliedRuntimeRunIdsJson)
    }

    @Test
    fun deletingAConversationCleansItsChunksAndPreservesOthers() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = snapshot(mapOf("active" to state("active"), "deleted" to state("deleted")))
        persistence.save(context, initial)
        installAudit()

        persistence.save(context, initial.copy(conversationsById = initial.conversationsById - "deleted"))

        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(listOf("active"), dao.conversations().map { it.id })
        assertEquals(0, events("conversation_messages", "active-thinking"))
        val count = EtaDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM agent_text_chunks WHERE owner_id IN ('deleted', 'deleted-thinking')",
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count)
    }

    @Test
    fun restartAfterSavingAroundAStreamingPlaceholderKeepsEveryMessage() = runBlocking {
        // blank streaming placeholders are not persisted; saving mid-run leaves holes in sort_index, and the list is compacted after restart.
        val running = state("active").copy(messages = listOf(
            UserMessageUi("user", "request"),
            AgentMessageUi(id = "assistant-run", content = "", isStreaming = true, renderMarkdown = false),
            ThinkingMessageUi("thinking", "thinking", false),
            ThinkingMessageUi("later", "keep thinking", false),
        ))
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to running)))

        val loaded = AgentConversationStore.load(context)
        assertEquals(setOf("active"), loaded.unalignedConversationIds)
        val restored = loaded.conversationsById.getValue("active")
        AgentConversationPersistence(loaded).save(context, loaded.copy(conversationsById = mapOf("active" to
            restored.copy(messages = restored.messages + AgentMessageUi(id = "answer", content = "final answer")))))

        val reloaded = AgentConversationStore.load(context)
        assertEquals(listOf("user", "thinking", "later", "answer"),
            reloaded.conversationsById.getValue("active").messages.map { it.id })
        assertTrue(reloaded.unalignedConversationIds.isEmpty())
    }

    @Test
    fun appendingAndDeletingWithinATailWindowNeverRewritesTheUnloadedPrefix() = runBlocking {
        val messages = (0 until 300).map { UserMessageUi("user-$it", "message-$it") }
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to state("active").copy(messages = messages))))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val tail = loaded.conversationsById.getValue("active")
        assertEquals(172, tail.messageStartIndex)
        installAudit()

        val appended = tail.copy(messages = tail.messages + AgentMessageUi("new-answer", "new answer"))
        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to appended)))

        assertEquals(0, events("conversation_messages", "user-0"))
        assertEquals(0, events("conversation_messages", "user-171"))
        assertEquals(messages + appended.messages.last(), AgentConversationStore.loadAllMessages(context, "active"))

        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to appended.copy(messages = emptyList()))))

        assertEquals(messages.take(172), AgentConversationStore.loadAllMessages(context, "active"))
        assertEquals(0, events("conversation_messages", "user-171"))
    }

    @Test
    fun renamingAnUnloadedConversationPreservesItsMessagesAndContext() = runBlocking {
        val original = snapshot(mapOf("active" to state("active"), "dormant" to state("dormant")))
        AgentConversationPersistence().save(context, original)
        val dormantBefore = AgentConversationStore.loadConversation(context, "dormant")!!
        val loaded = AgentConversationStore.load(context)
        assertTrue(!loaded.conversationsById.getValue("dormant").isConversationLoaded)
        installAudit()

        AgentConversationPersistence(loaded).save(context, loaded.copy(
            titles = loaded.titles + ("dormant" to "new name"), updatedAt = loaded.updatedAt + ("dormant" to 20L),
        ))

        assertEquals(0, events("conversation_messages", "dormant-thinking"))
        assertEquals(0, events("conversation_context_checkpoints", "dormant"))
        val dormant = AgentConversationStore.loadConversation(context, "dormant")!!
        assertEquals(dormantBefore.messages, dormant.messages)
        assertEquals(dormantBefore.history, dormant.history)
        assertEquals("new name", EtaDatabase.get(context).conversationDao().conversation("dormant")!!.title)
    }

    @Test
    fun adoptingAnEarlierPageDoesNotRewriteItsUnchangedRows() = runBlocking {
        val original = state("active").copy(messages = (0 until 200).map { UserMessageUi("user-$it", "message-$it") })
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val state = loaded.conversationsById.getValue("active")
        val page = AgentConversationStore.loadEarlierMessages(context, "active", state.messageStartIndex)
        val expanded = state.copy(messages = page.messages + state.messages, messageStartIndex = page.startIndex,
            hasEarlierMessages = page.hasEarlierMessages)
        persistence.adoptConversation("active", expanded, loaded.titles.getValue("active"), loaded.updatedAt.getValue("active"))
        installAudit()

        persistence.save(context, loaded.copy(conversationsById = mapOf("active" to expanded)))

        assertEquals(0, allEvents())
        assertEquals(original.messages, AgentConversationStore.loadAllMessages(context, "active"))
    }

    @Test
    fun offscreenRoleplayRevisionsUpdateStoredRowsWithoutLoadingTheirMessageWindow() = runBlocking {
        val messages = (0 until 300).map { index ->
            if (index % 2 == 0) UserMessageUi("message-$index", "body-$index")
            else AgentMessageUi("message-$index", "body-$index")
        }
        val original = state("active").copy(
            messages = messages,
            roleplay = RoleplayBinding("character", "{}", "traveler"),
            roleplayMessages = RoleplayMessageState(links = messages.associate { it.id to RoleplayMessageLink(it.id) }),
        )
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val tail = loaded.conversationsById.getValue("active")
        assertEquals(172, tail.messageStartIndex)
        val editedContent = "revision".repeat(10_000)
        val revised = tail.copy(roleplayMessages = tail.roleplayMessages.copy(revisions = mapOf(
            "message-0" to RoleplayReplyRevision("body-0", listOf("body-0", editedContent), 1),
            "message-1" to RoleplayReplyRevision("body-1", listOf("body-1", "new answer"), 1),
        )))

        AgentConversationPersistence(loaded).save(context, loaded.copy(conversationsById = mapOf("active" to revised)))

        val allMessages = AgentConversationStore.loadAllMessages(context, "active")
        assertEquals(300, allMessages.size)
        assertEquals(UserMessageUi("message-0", editedContent, isEdited = true), allMessages[0])
        assertEquals(AgentMessageUi("message-1", "new answer"), allMessages[1])
        assertEquals(messages.drop(2), allMessages.drop(2))
        assertEquals(setOf("active"), AgentConversationStore.searchConversationIds(context, "new answer"))
    }

    @Test
    fun aNewOffscreenRevisionWithAMissingTargetRollsBackWithoutRejectingOldMetadata() = runBlocking {
        val original = state("active").copy(
            roleplay = RoleplayBinding("character", "{}", "traveler"),
            roleplayMessages = RoleplayMessageState(revisions = mapOf(
                "legacy-missing" to RoleplayReplyRevision("old body", listOf("old body"), 0),
            )),
        )
        AgentConversationPersistence().save(context, snapshot(mapOf("active" to original)))
        val loaded = AgentConversationStore.load(context)
        val persistence = AgentConversationPersistence(loaded)
        val current = loaded.conversationsById.getValue("active")
        val revised = current.copy(roleplayMessages = current.roleplayMessages.copy(revisions =
            current.roleplayMessages.revisions + ("missing-target" to RoleplayReplyRevision("original text", listOf("original text", "modify"), 1))))
        var failed = false
        try {
            persistence.save(context, loaded.copy(conversationsById = mapOf("active" to revised), titles = mapOf("active" to "must not commit")))
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        val restored = AgentConversationStore.loadConversation(context, "active")!!
        assertEquals(current.roleplayMessages, restored.roleplayMessages)
        assertEquals("active", EtaDatabase.get(context).conversationDao().conversation("active")!!.title)

        persistence.save(context, loaded.copy(titles = mapOf("active" to "legal rename")))
        assertEquals("legal rename", EtaDatabase.get(context).conversationDao().conversation("active")!!.title)
        assertEquals(current.roleplayMessages, AgentConversationStore.loadConversation(context, "active")!!.roleplayMessages)
    }

    private fun state(id: String): AgentChatHomeUiState {
        val history = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "history".repeat(12_000)))
        return AgentChatHomeUiState(
            messages = listOf(UserMessageUi("$id-user", "request"),
                ThinkingMessageUi("$id-thinking", "thinking".repeat(12_000), false)),
            history = history, journal = history, input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun snapshot(states: Map<String, AgentChatHomeUiState>) = AgentConversationStore.Snapshot(
        "active", states, states.keys.associateWith { it }, states.keys.associateWith { 1L },
    )

    private fun installAudit() {
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TABLE mutation_log (table_name TEXT, owner_id TEXT, field TEXT)")
        for ((table, key) in mapOf("conversations" to "id", "conversation_messages" to "id", "conversation_context_checkpoints" to "conversation_id")) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val row = if (operation == "DELETE") "OLD" else "NEW"
                sql.execSQL("CREATE TRIGGER audit_${table}_$operation AFTER $operation ON $table BEGIN " +
                    "INSERT INTO mutation_log VALUES ('$table', $row.$key, ''); END")
            }
        }
        for (operation in listOf("INSERT", "DELETE")) {
            val row = if (operation == "DELETE") "OLD" else "NEW"
            sql.execSQL("CREATE TRIGGER audit_chunks_$operation AFTER $operation ON agent_text_chunks BEGIN " +
                "INSERT INTO mutation_log VALUES ('agent_text_chunks', $row.owner_id, $row.field); END")
        }
    }

    private fun events(table: String, owner: String): Int =
        count("SELECT COUNT(*) FROM mutation_log WHERE table_name = ? AND owner_id = ?", arrayOf(table, owner))

    private fun chunkEvents(owner: String, field: String): Int =
        count("SELECT COUNT(*) FROM mutation_log WHERE table_name = 'agent_text_chunks' AND owner_id = ? AND field = ?", arrayOf(owner, field))

    private fun allEvents(): Int = count("SELECT COUNT(*) FROM mutation_log", emptyArray())

    private fun count(sql: String, args: Array<Any?>): Int = EtaDatabase.get(context).openHelper.readableDatabase
        .query(sql, args).use { it.moveToFirst(); it.getInt(0) }
}
