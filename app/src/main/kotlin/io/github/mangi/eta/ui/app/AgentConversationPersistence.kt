package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.ConversationBranchRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Advances the save baseline only after a transaction commits; doesn't hold temporary UI state like input drafts or pending images. */
internal class AgentConversationPersistence(initial: AgentConversationStore.Snapshot? = null) {
    private val mutex = Mutex()
    // Unaligned conversations don't enter the baseline; the first save writes a new conversation in full and re-sorts sort_index.
    private var saved = initial?.let { snapshot -> project(snapshot) - snapshot.unalignedConversationIds }.orEmpty()
    // Branches committed but not yet in Main state must merge into the old snapshot until the save queue observes the published state.
    private var unpublishedBranches = emptyMap<String, Content>()

    suspend fun save(context: Context, snapshot: AgentConversationStore.Snapshot) = mutex.withLock {
        val projected = project(snapshot)
        val current = unpublishedBranches + projected
        AgentConversationStore.saveChanges(context, snapshot.selectedConversationId, current, saved)
        saved = current
        unpublishedBranches = unpublishedBranches - projected.keys
    }

    suspend fun branch(
        context: Context,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ): AgentChatHomeUiState = mutex.withLock {
        val database = EtaDatabase.get(context)
        val state = database.withTransaction {
            ConversationBranchRepository.create(database, sourceId, targetMessageId, branchId, title, now)
            checkNotNull(AgentConversationStore.loadConversation(context, branchId))
        }
        val content = Content(state, title, now)
        saved = saved + (branchId to content)
        unpublishedBranches = unpublishedBranches + (branchId to content)
        state
    }

    suspend fun adoptConversation(
        id: String,
        state: AgentChatHomeUiState,
        title: String,
        updatedAt: Long,
        isPinned: Boolean = false,
    ) = mutex.withLock {
        saved = saved + (id to Content(state, title, updatedAt, isPinned))
    }

    suspend fun forgetConversation(id: String) = mutex.withLock {
        saved = saved - id
    }

    private fun project(snapshot: AgentConversationStore.Snapshot): Map<String, Content> =
        snapshot.conversationsById.mapValues { (id, state) ->
            Content(state, snapshot.titles[id].orEmpty(), snapshot.updatedAt[id] ?: 0L, id in snapshot.pinnedIds)
        }

    internal data class Content(
        val title: String,
        val updatedAt: Long,
        val isPinned: Boolean,
        val reasoningEffort: ReasoningEffort,
        val appliedRuntimeRunIds: List<String>,
        val roleplay: RoleplayBinding?,
        val roleplayMessages: RoleplayMessageState,
        val modelId: String?,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        val journal: List<AgentModelClient.ConversationMessage>,
        val isConversationLoaded: Boolean,
        val messageStartIndex: Int,
    ) {
        constructor(
            state: AgentChatHomeUiState,
            title: String,
            updatedAt: Long,
            isPinned: Boolean = false,
        ) : this(
            title, updatedAt, isPinned, state.reasoningEffort, state.appliedRuntimeRunIds,
            state.roleplay, if (state.roleplay == null) RoleplayMessageState() else state.roleplayMessages,
            state.modelId, state.messages, state.history, state.journal.ifEmpty { state.history },
            state.isConversationLoaded, state.messageStartIndex,
        )

        fun sameMetadata(other: Content): Boolean =
            title == other.title && updatedAt == other.updatedAt && isPinned == other.isPinned &&
                reasoningEffort == other.reasoningEffort &&
                appliedRuntimeRunIds == other.appliedRuntimeRunIds && roleplay == other.roleplay &&
                roleplayMessages == other.roleplayMessages && modelId == other.modelId
    }
}
