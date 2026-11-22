package io.github.namanwtf.atom.ui.app

import android.content.Context
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.agent.roleplay.CharacterCardCodec
import io.github.namanwtf.atom.agent.roleplay.RoleplayBinding
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageLink
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageState
import io.github.namanwtf.atom.data.db.ConversationEntity
import io.github.namanwtf.atom.data.db.ConversationMessageEntity
import io.github.namanwtf.atom.data.db.ConversationStateEntity
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.model.ReasoningEffort
import io.github.namanwtf.atom.ui.model.AgentChatHomeUiState
import io.github.namanwtf.atom.ui.model.AgentMessageUi
import io.github.namanwtf.atom.ui.model.SystemNoticeCode
import io.github.namanwtf.atom.ui.model.SystemNoticeMessageUi
import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.TokenUsageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityStatusUi
import io.github.namanwtf.atom.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AtomDatabase.closeForTests()
        context.deleteDatabase("atom.db")
    }

    @Test
    fun repeatedSavePreservesRoleBindingRevisionsPendingRewriteAndOriginalJournal() = runBlocking {
        val original = AgentModelClient.ConversationMessage(
            role = "assistant", content = "original answer", messageId = "assistant-role-1",
        )
        val binding = RoleplayBinding(
            characterId = "character-1",
            cardSnapshotJson = CharacterCardCodec.encodeJson(CharacterCardCodec.create("traveler")),
            characterName = "traveler", userName = "friend", userDescription = "traveling companion",
        )
        val state = AgentChatHomeUiState(
            messages = listOf(AgentMessageUi(id = original.messageId, content = original.content, isStreaming = false)),
            input = "", isStreaming = false, thinkingEnabled = false,
            journal = listOf(original), history = listOf(original), roleplay = binding,
            roleplayMessages = RoleplayMessageState(
                links = mapOf(original.messageId to RoleplayMessageLink(original.messageId)),
                pendingRewrites = mapOf("rewrite-in-flight" to original.messageId),
            ),
        )
        var role = RoleplayConversationReducer.edit(state, original.messageId, "user-revised answer")!!
        repeat(2) {
            AgentConversationStore.save(
                context, "role", mapOf("role" to role, "ordinary" to AgentChatHomeUiState(
                    messages = listOf(UserMessageUi(id = "ordinary-user", content = "check battery")),
                    input = "", isStreaming = false, thinkingEnabled = false,
                )), mapOf("role" to "traveler", "ordinary" to "check battery"), mapOf("role" to 1L, "ordinary" to 2L),
            )
            val restored = AgentConversationStore.load(context)
            role = restored.conversationsById.getValue("role")
            assertEquals(binding, role.roleplay)
            assertEquals(listOf(original), role.journal)
            assertEquals("user-revised answer", role.history.single().content)
            assertEquals("rewrite-in-flight", role.roleplayMessages.pendingRewrites.keys.single())
            assertEquals(listOf("original answer", "user-revised answer"), role.roleplayMessages.revisions.getValue(original.messageId).candidates)
            assertEquals(2, (role.messages.single() as AgentMessageUi).candidateCount)
            assertEquals(null, restored.conversationsById.getValue("ordinary").roleplay)
            assertTrue(restored.conversationsById.getValue("ordinary").roleplayMessages.revisions.isEmpty())
        }
        val switched = RoleplayConversationReducer.select(role, original.messageId, 0)!!
        assertEquals("original answer", switched.history.single().content)
        assertEquals(listOf(original), switched.journal)
    }

    @Test
    fun saveAndLoadPreservesConversations() {
        val conversation = AgentChatHomeUiState(
            messages = listOf(
                UserMessageUi(
                    id = "user-1",
                    content = "look at the current screen",
                    isEdited = true,
                ),
                ThinkingMessageUi(
                    id = "thinking-1",
                    content = "need to observe the screen first",
                    isStreaming = false,
                    elapsedSeconds = 3,
                    collapsed = true,
                ),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "execute command · Android · root",
                    command = "pm list packages | head",
                    resultSummary = "ok=true, chars=100",
                    imageCount = 1,
                ),
                AgentMessageUi(
                    id = "assistant-1",
                    content = "| Item | content |\n| --- | --- |\n| Battery | 88% |",
                    isStreaming = false,
                    renderMarkdown = true,
                    usage = TokenUsageUi(
                        contextTokens = 100,
                        inputTokens = 30,
                        outputTokens = 40,
                        reasoningTokens = 20,
                        cachedTokens = 10,
                        cacheWriteTokens = 5,
                    ),
                ),
                SystemNoticeMessageUi(
                    id = "compaction-1",
                    code = SystemNoticeCode.ContextCompaction,
                    detail = "context compacted (fold-1)",
                    usage = TokenUsageUi(
                        inputTokens = 95,
                        outputTokens = 12,
                        cachedTokens = 90,
                        cacheWriteTokens = 2,
                        requestKind = io.github.namanwtf.atom.agent.runtime.AgentTokenUsage.REQUEST_KIND_COMPACTION,
                    ),
                    archiveId = "fold-1",
                    tokensBefore = 92,
                ),
            ),
            history = listOf(
                io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "look at the current screen",
                ),
                io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    reasoningContent = "need to observe the screen first",
                    toolCallsJson = """[{"id":"toolu_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]""",
                ),
                io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage(
                    role = "tool",
                    content = "{\"ok\":true}",
                    toolCallId = "toolu_1",
                ),
                io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "| Item | content |\n| --- | --- |\n| Battery | 88% |",
                ),
            ),
            input = "must not save the draft",
            isStreaming = true,
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf("conv-1" to conversation),
                titles = mapOf("conv-1" to "screen analysis"),
                updatedAt = mapOf("conv-1" to 1234L),
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals("conv-1", snapshot.selectedConversationId)
        assertEquals("screen analysis", snapshot.titles.getValue("conv-1"))
        assertEquals(1234L, snapshot.updatedAt.getValue("conv-1"))
        val restored = snapshot.conversationsById.getValue("conv-1")
        assertEquals("", restored.input)
        assertFalse(restored.isStreaming)
        assertTrue(restored.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoningEffort)
        assertEquals(conversation.messages, restored.messages)
        assertEquals(conversation.history, restored.history)
    }

    @Test
    fun saveAndLoadPreservesSemanticSystemNoticesWithoutTranslatedContent() {
        val notice = SystemNoticeMessageUi(
            id = "assistant-run-1-1",
            code = SystemNoticeCode.RuntimeFailed,
            detail = "upstream timeout",
        )
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-notice",
                conversationsById = mapOf(
                    "conv-notice" to AgentChatHomeUiState(
                        messages = listOf(notice),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-notice" to ""),
                updatedAt = mapOf("conv-notice" to 1L),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertEquals("", snapshot.titles.getValue("conv-notice"))
        assertEquals(
            notice,
            snapshot.conversationsById.getValue("conv-notice").messages.single(),
        )
    }

    @Test
    fun conversationModelBindingRoundTripsAndChangeAloneIsPersisted() = runBlocking {
        val user = UserMessageUi(id = "user-1", content = "hello")
        val base = AgentChatHomeUiState(
            messages = listOf(user), input = "", isStreaming = false, thinkingEnabled = false,
            reasoningEffort = ReasoningEffort.OFF,
        )
        val persistence = AgentConversationPersistence()
        fun snapshot(state: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
            selectedConversationId = "conv-a",
            conversationsById = mapOf("conv-a" to state, "conv-b" to base),
            titles = mapOf("conv-a" to "A", "conv-b" to "B"),
            updatedAt = mapOf("conv-a" to 1L, "conv-b" to 1L),
        )
        persistence.save(context, snapshot(base.copy(modelId = "model-x")))
        assertEquals("model-x", AgentConversationStore.load(context).conversationsById.getValue("conv-a").modelId)

        persistence.save(context, snapshot(base.copy(modelId = "model-y")))
        val restored = AgentConversationStore.load(context).conversationsById
        assertEquals("model-y", restored.getValue("conv-a").modelId)
        assertEquals(null, restored.getValue("conv-b").modelId)
    }

    @Test
    fun unknownStoredEffortFallsBackToDefault() {
        runBlocking {
            AtomDatabase.get(context).conversationDao().replaceAll(
                conversations = listOf(
                    ConversationEntity(
                        id = "conv-unknown",
                        title = "Unknown",
                        thinkingEnabled = false,
                        reasoningEffort = "future_effort",
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                ),
                messages = emptyList(),
                state = ConversationStateEntity(selectedConversationId = "conv-unknown"),
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-unknown")

        assertEquals(ReasoningEffort.DEFAULT, restored.reasoningEffort)
        assertTrue(restored.thinkingEnabled)
    }

    @Test
    fun initialLoadKeepsOnlyTheSelectedMessageWindowAndEarlierPagesRemainComplete() {
        val longContent = "x".repeat(20_000)
        val primaryMessages = buildList {
            add(UserMessageUi(id = "conv-0-user-long", content = longContent))
            repeat(130) { index ->
                add(
                    AgentMessageUi(
                        id = "conv-0-assistant-$index",
                        content = "assistant-$index",
                        isStreaming = false,
                    )
                )
            }
        }
        val conversations = buildMap {
            put(
                "conv-0",
                AgentChatHomeUiState(
                    messages = primaryMessages,
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                )
            )
            repeat(59) { index ->
                val id = "conv-${index + 1}"
                put(
                    id,
                    AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "$id-user", content = "message-$id")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                )
            }
        }
        val titles = conversations.keys.associateWith { id -> "title-$id" }
        val updatedAt = conversations.keys.associateWith { id -> id.removePrefix("conv-").toLong() }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-0",
                conversationsById = conversations,
                titles = titles,
                updatedAt = updatedAt,
            )
        }

        val snapshot = AgentConversationStore.load(context)

        assertEquals(60, snapshot.conversationsById.size)
        val restored = snapshot.conversationsById.getValue("conv-0")
        assertEquals(128, restored.messages.size)
        assertEquals(3, restored.messageStartIndex)
        assertTrue(restored.hasEarlierMessages)
        assertEquals("assistant-129", (restored.messages.last() as AgentMessageUi).content)
        assertTrue(snapshot.conversationsById.filterKeys { it != "conv-0" }.values.none { it.isConversationLoaded })
        val earlier = runBlocking { AgentConversationStore.loadEarlierMessages(context, "conv-0", restored.messageStartIndex) }
        assertEquals(0, earlier.startIndex)
        assertFalse(earlier.hasEarlierMessages)
        assertEquals(primaryMessages, earlier.messages + restored.messages)
        assertEquals(longContent, (earlier.messages.first() as UserMessageUi).content)
        assertEquals(primaryMessages, runBlocking { AgentConversationStore.loadAllMessages(context, "conv-0") })
    }

    @Test
    fun unopenedConversationOnlyReadsItsBoundedPreview() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(
            ConversationEntity("active", "current", false, createdAt = 1L, updatedAt = 2L),
            ConversationEntity("dormant", "unopened", false, revisionsJson = "invalid deferred revisions",
                createdAt = 1L, updatedAt = 1L),
        ))
        dao.insertMessages(listOf(ConversationMessageEntity("dormant-last", "dormant", 0, "assistant", "content".repeat(20_000))))
        dao.insertState(ConversationStateEntity(selectedConversationId = "active"))

        val dormant = AgentConversationStore.load(context).conversationsById.getValue("dormant")

        assertFalse(dormant.isConversationLoaded)
        assertTrue(dormant.history.isEmpty())
        assertTrue(dormant.journal.isEmpty())
        assertEquals(256, (dormant.messages.single() as AgentMessageUi).content.length)
    }

    @Test
    fun unloadedCharacterKeepsItsIdentityWithoutRetainingTheCard() = runBlocking {
        val base = AgentChatHomeUiState(messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false)
        val role = base.copy(roleplay = RoleplayBinding(
            characterId = "character", cardSnapshotJson = "character😀".repeat(20_000), characterName = "traveler",
            avatarPath = "/test/avatar.png", userDescription = "very long user setting".repeat(5_000),
        ))
        AgentConversationStore.save(context, "active", mapOf("active" to base, "role" to role),
            mapOf("active" to "current", "role" to "traveler"), mapOf("active" to 2L, "role" to 1L))

        val placeholder = AgentConversationStore.load(context).conversationsById.getValue("role")

        assertFalse(placeholder.isConversationLoaded)
        assertEquals(null, placeholder.roleplay)
        assertEquals("traveler", placeholder.conversationCharacterName)
        assertEquals("/test/avatar.png", placeholder.conversationCharacterAvatarPath)
        val unloaded = AgentConversationStore.unloadConversation(role)
        assertEquals(null, unloaded.roleplay)
        assertEquals("traveler", unloaded.conversationCharacterName)
        assertEquals("/test/avatar.png", unloaded.conversationCharacterAvatarPath)
    }

    @Test
    fun searchFindsUnloadedChunkBoundariesAndTreatsSqlWildcardsLiterally() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(
            ConversationEntity("literal", "text", false, createdAt = 1L, updatedAt = 1L),
            ConversationEntity("other", "another one", false, createdAt = 1L, updatedAt = 2L),
        ))
        dao.insertMessages(listOf(
            ConversationMessageEntity("literal-message", "literal", 0, "assistant", "x".repeat(16_381) + "cross boundary 100%_done"),
            ConversationMessageEntity("other-message", "other", 0, "assistant", "100xxfinished"),
        ))

        assertEquals(setOf("literal"), AgentConversationStore.searchConversationIds(context, "cross boundary"))
        assertEquals(setOf("literal"), AgentConversationStore.searchConversationIds(context, "%_"))
        assertEquals(setOf("literal"), AgentConversationStore.searchConversationIds(context, "DONE"))
        assertEquals(emptySet<String>(), AgentConversationStore.searchConversationIds(context, "atom:chunks"))
        assertEquals(emptySet<String>(), AgentConversationStore.searchConversationIds(context, "[]"))
    }

    @Test
    fun searchRetainsUnicodeCaseFoldingAndQueriesSpanningManyChunks() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(
            ConversationEntity("unicode", "text", false, createdAt = 1L, updatedAt = 1L),
            ConversationEntity("large", "long text", false, createdAt = 1L, updatedAt = 2L),
            ConversationEntity("folded", "compatible characters", false, createdAt = 1L, updatedAt = 3L),
        ))
        val query = "start" + "a".repeat(60_000) + "end"
        dao.insertMessages(listOf(
            ConversationMessageEntity("unicode-message", "unicode", 0, "assistant", "äpfel"),
            ConversationMessageEntity("large-message", "large", 0, "assistant", "prefix$query suffix"),
            ConversationMessageEntity("folded-message", "folded", 0, "assistant", "İKſ"),
        ))

        assertEquals(setOf("unicode"), AgentConversationStore.searchConversationIds(context, "ÄPFEL"))
        assertEquals(setOf("large"), AgentConversationStore.searchConversationIds(context, query))
        assertEquals(setOf("folded"), AgentConversationStore.searchConversationIds(context, "iks"))
    }

    @Test
    fun searchIncludesMatchingLocalizedNoticeCodesFromEarlierMessages() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(ConversationEntity("notice", "title", false, createdAt = 1L, updatedAt = 1L)))
        dao.insertMessages(listOf(
            ConversationMessageEntity("old-notice", "notice", 0, "system_notice", SystemNoticeCode.Interrupted.wireValue),
            ConversationMessageEntity("new-message", "notice", 1, "assistant", "new answer"),
        ))

        assertEquals(setOf("notice"), AgentConversationStore.searchConversationIds(context, "interrupted", listOf(SystemNoticeCode.Interrupted)))
    }

    @Test
    fun savePreservesCompleteContextAndDisplayedMessages() {
        val displayedContent = "display message-${"d".repeat(120_000)}"
        val history = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "history-$index-${"h".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "latest context"))
        }

        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-large",
                conversationsById = mapOf(
                    "conv-large" to AgentChatHomeUiState(
                        messages = listOf(
                            UserMessageUi(id = "user-large", content = displayedContent)
                        ),
                        history = history,
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-large" to "long conversation"),
                updatedAt = mapOf("conv-large" to 1L),
            )
        }

        val checkpoint = runBlocking {
            AtomDatabase.get(context)
                .conversationDao()
                .contextCheckpoint("conv-large")!!
        }
        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-large")

        assertTrue(checkpoint.historyJson.length > 96_000)
        assertEquals(history, restored.history)
        assertEquals(history, restored.journal)
        assertEquals(displayedContent, (restored.messages.single() as UserMessageUi).content)
        assertEquals("latest context", restored.history.last().content)
    }

    @Test
    fun loadIgnoresLegacyHistoryColumnAndFallsBackToMessageRows() {
        runBlocking {
            val dao = AtomDatabase.get(context).conversationDao()
            dao.insertConversations(
                listOf(
                    ConversationEntity(
                        id = "conv-legacy-large",
                        title = "old long conversation",
                        thinkingEnabled = false,
                        historyJson = "x".repeat(2_500_000),
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                )
            )
            dao.insertMessages(
                listOf(
                    ConversationMessageEntity(
                        id = "legacy-user",
                        conversationId = "conv-legacy-large",
                        sortIndex = 0,
                        type = "user",
                        content = "restore from message records",
                    )
                )
            )
        }

        val restored = AgentConversationStore.load(context)
            .conversationsById
            .getValue("conv-legacy-large")

        assertEquals("restore from message records", restored.history.single().content)
        assertEquals("restore from message records", (restored.messages.single() as UserMessageUi).content)
    }

    @Test
    fun loadKeepsDatabaseEmptyUntilFirstMessageIsSent() {
        val snapshot = AgentConversationStore.load(context)

        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun characterGreetingIsLocalAndOrdinaryNewConversationReturnsToAtom() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)
            val binding = RoleplayBinding(
                "local-character", CharacterCardCodec.encodeJson(CharacterCardCodec.create("traveler")),
                "traveler", userName = "Xiaolin",
            )
            state.startCharacterConversation(binding, "Hello, {{user}}, I am {{char}}.")
            assertFalse(state.homeState.isStreaming)
            assertEquals("Hello, Xiaolin, I am the traveler.", (state.homeState.messages.single() as AgentMessageUi).content)
            assertEquals(binding, state.homeState.roleplay)
            assertTrue(state.homeState.appliedRuntimeRunIds.isEmpty())
            assertTrue(state.homeState.roleplayMessages.pendingRewrites.isEmpty())

            state.createConversation()
            assertEquals(null, state.homeState.roleplay)
            assertTrue(state.homeState.history.isEmpty())
            assertTrue(state.homeState.messages.isEmpty())
            assertFalse(state.homeState.isStreaming)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun creatingConversationKeepsEmptyStateOutOfHistoryAndDatabase() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = AgentAppState(context, scope)

            state.createConversation()
            state.createConversation()

            assertEquals(null, state.conversationPaneState.selectedConversationId)
            assertTrue(state.conversationPaneState.conversations.isEmpty())
            assertTrue(
                runBlocking {
                    AtomDatabase.get(context).conversationDao().conversations().isEmpty()
                }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun savingEmptySnapshotClearsPreviouslyPersistedConversations() {
        runBlocking {
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "hello")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    )
                ),
                titles = mapOf("conv-1" to "hello"),
                updatedAt = mapOf("conv-1" to 1L),
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val snapshot = AgentConversationStore.load(context)
        assertTrue(snapshot.conversationsById.isEmpty())
        assertEquals(null, snapshot.selectedConversationId)
    }

    @Test
    fun pinnedConversationsRoundTripAcrossLoadedAndUnloadedStates() {
        runBlocking {
            val persistence = AgentConversationPersistence()
            val firstSnapshot = AgentConversationStore.Snapshot(
                selectedConversationId = "conv-1",
                conversationsById = mapOf(
                    "conv-1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-1", content = "first item")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                    "conv-2" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "user-2", content = "second item")),
                        input = "",
                        isStreaming = false,
                        thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("conv-1" to "conversation one", "conv-2" to "conversation two"),
                updatedAt = mapOf("conv-1" to 2L, "conv-2" to 1L),
                pinnedIds = setOf("conv-1"),
            )
            persistence.save(context, firstSnapshot)

            val loaded = AgentConversationStore.load(context)
            assertEquals(setOf("conv-1"), loaded.pinnedIds)

            // when conv-2 is in the unexpanded state (isConversationLoaded = false) and its pin state is toggled, it should also be written back to the database correctly.
            val secondPersistence = AgentConversationPersistence(loaded)
            secondPersistence.save(
                context,
                loaded.copy(pinnedIds = setOf("conv-2")),
            )

            val reloaded = AgentConversationStore.load(context)
            assertEquals(setOf("conv-2"), reloaded.pinnedIds)
        }
    }
}
