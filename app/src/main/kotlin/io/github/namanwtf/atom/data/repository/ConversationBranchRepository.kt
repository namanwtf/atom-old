package io.github.namanwtf.atom.data.repository

import androidx.room.withTransaction
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage
import io.github.namanwtf.atom.agent.roleplay.RoleplayHistoryProjection
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageState
import io.github.namanwtf.atom.data.db.ChunkedTextDao
import io.github.namanwtf.atom.data.db.ConversationDao
import io.github.namanwtf.atom.data.db.ConversationEntity
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.db.openTextStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Callers enqueue the creation operation into the conversation save queue; the database transaction guarantees the prefix and context are committed together. */
internal object ConversationBranchRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PAGE_SIZE = 128

    suspend fun create(
        database: AtomDatabase,
        sourceId: String,
        targetMessageId: String,
        branchId: String,
        title: String,
        now: Long,
    ) = database.withTransaction {
        val dao = database.conversationDao()
        val source = dao.conversation(sourceId) ?: throw ConversationBranchUnavailable()
        check(dao.conversationMetadataRow(branchId) == null) { "conversation_branch_id_exists" }
        val outline = dao.branchMessages(sourceId)
        val target = outline.firstOrNull { it.id == targetMessageId } ?: throw ConversationBranchUnavailable()
        val roleplay = source.revisionsJson.takeIf { it.isNotBlank() }
            ?.let { json.decodeFromString<RoleplayMessageState>(it) } ?: RoleplayMessageState()
        val checkpoint = dao.contextCheckpointRow(sourceId) ?: throw ConversationBranchUnavailable()
        val rawJournal = decodeCheckpoint(dao, sourceId, "journal", checkpoint.journalJson)
            .takeIf { it.isNotEmpty() } ?: throw ConversationBranchUnavailable()
        val journal = ConversationBranchBoundary.resolve(outline, rawJournal, roleplay, targetMessageId)
        val rawHistory = checkpoint.historyJson.takeIf { it.isNotBlank() }?.let { stored ->
            if (stored == checkpoint.journalJson && !stored.startsWith(ChunkedTextDao.REFERENCE_PREFIX)) {
                rawJournal
            } else {
                decodeCheckpoint(dao, sourceId, "history", stored)
            }
        }.orEmpty()
        // When the branch point falls after the summary, keep the source conversation's already-compacted active context, avoiding restoring the full journal into the new branch by mistake.
        val historyPrefix = rawHistory.takeIf { it.isNotEmpty() }?.let {
            ConversationBranchBoundary.resolveOrNull(outline, it, roleplay, targetMessageId)
        }
        val retainedIds = outline.asSequence().filter { it.sortIndex <= target.sortIndex }.map { it.id }.toSet()
        val transcriptIds = journal.mapTo(mutableSetOf()) { it.messageId }
        val links = roleplay.links.filterKeys { it in retainedIds }
        if (links.values.any { it.transcriptMessageId !in transcriptIds }) throw ConversationBranchUnavailable()
        val revisions = roleplay.revisions.filterKeys { it in links }
        val retainedRoleplay = RoleplayMessageState(links = links, revisions = revisions)
        val history = when {
            source.roleplayJson.isBlank() -> historyPrefix ?: journal
            historyPrefix != null && historyPrefix.any { it.contextSummary } -> historyPrefix
            else -> {
                val changedTranscripts = revisions.keys.mapNotNullTo(mutableSetOf()) { links[it]?.transcriptMessageId }
                val needed = links.filterValues { it.transcriptMessageId in changedTranscripts }.keys
                val contents = buildMap {
                    needed.toList().chunked(PAGE_SIZE).forEach { ids ->
                        dao.messageContents(sourceId, ids).forEach { row ->
                            put(row.id, dao.restoreText("conversation_messages", row.id, "content", row.content))
                        }
                    }
                }
                RoleplayHistoryProjection.project(journal, retainedRoleplay) { id -> revisions[id]?.content ?: contents[id] }
                    ?: throw ConversationBranchUnavailable()
            }
        }
        fun remap(id: String) = ConversationBranchBoundary.remapId(branchId, id)
        val remappedRoleplay = retainedRoleplay.copy(
            links = links.entries.associate { (id, link) -> remap(id) to link.copy(transcriptMessageId = remap(link.transcriptMessageId)) },
            revisions = revisions.entries.associate { (id, revision) -> remap(id) to revision },
        )
        dao.insertConversations(listOf(ConversationEntity(
            id = branchId, title = title, thinkingEnabled = source.thinkingEnabled,
            reasoningEffort = source.reasoningEffort, modelId = source.modelId,
            roleplayJson = source.roleplayJson,
            revisionsJson = if (source.roleplayJson.isBlank()) "" else json.encodeToString(remappedRoleplay),
            createdAt = now, updatedAt = now,
        )))
        dao.updateContextCheckpoint(
            branchId,
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(messageId = remap(it.messageId)) }),
            AgentConversationCodec.encodeTranscriptForStorage(journal.map { it.copy(messageId = remap(it.messageId)) }),
        )
        var after = -1
        var index = 0
        while (true) {
            val page = dao.branchMessageRows(sourceId, after, target.sortIndex, PAGE_SIZE)
            if (page.isEmpty()) break
            val copies = page.map { stored ->
                val row = dao.restoreMessage(stored)
                row.copy(id = remap(row.id), conversationId = branchId, sortIndex = index++)
            }
            // restore/store will rebuild long-text chunks for the new owner; chunk references pointing to source messages must not be copied.
            dao.insertMessages(copies)
            after = page.last().sortIndex
        }
    }

    private fun decodeCheckpoint(
        dao: ConversationDao,
        id: String,
        field: String,
        stored: String?,
    ): List<ConversationMessage> {
        if (stored.isNullOrBlank()) return emptyList()
        return dao.openTextStream("conversation_context_checkpoints", id, field, stored).use {
            AgentConversationCodec.decodeTranscript(it)
        }
    }
}
