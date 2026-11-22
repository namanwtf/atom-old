package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage

/** 正文修订只改变模型投影，原始 journal 和已经发生的工具批次保持不变。 */
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
                // 缺失块意味着本轮投影无法完整重建，不能把剩余片段冒充完整正文。
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
