package io.github.namanwtf.atom.agent.roleplay

import io.github.namanwtf.atom.agent.model.AgentModelClient.ConversationMessage

/** Body revisions only change the model projection; the original journal and already-executed tool batches stay unchanged. */
internal object RoleplayHistoryProjection {
    fun project(
        journal: List<ConversationMessage>,
        state: RoleplayMessageState,
        content: (String) -> String?,
    ): List<ConversationMessage>? = buildList {
        val grouped = state.links.entries.groupBy { it.value.transcriptMessageId }
        if (journal.any { it.contextSummary }) add(ConversationMessage(
            role = "system", content = "early history keeps only a summary; content revisions apply only to messages that still have their original text and cannot recover already-missing history.",
        ))
        journal.forEach { entry ->
            val links = grouped[entry.messageId].orEmpty().sortedBy { it.value.blockOrder }
            if (links.none { it.key in state.revisions }) add(entry) else {
                // A missing block means this round's projection can't be fully rebuilt; the remaining fragments must not be passed off as the complete body.
                val body = links.map { (id, _) -> content(id) ?: return null }.joinToString("\n\n")
                if (entry.role == "user") add(ConversationMessage(
                    role = "system",
                    content = "the following historical user content was revised by the user; existing later tool records come from the original request and must not be treated as if the revised request had been executed.",
                ))
                add(entry.copy(content = body, contentJson = ""))
            }
        }
    }
}
