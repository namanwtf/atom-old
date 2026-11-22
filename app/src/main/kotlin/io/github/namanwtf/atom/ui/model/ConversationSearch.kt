package io.github.namanwtf.atom.ui.model

import io.github.namanwtf.atom.agent.model.AgentFileReferencePromptCodec

/**
 * Conversation content search: case-insensitive matching over the full message stream.
 * Matching against title and preview is the caller's responsibility; the notice text is injected by the caller in the current language.
 */
internal fun AgentChatHomeUiState.contentMatches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean {
    if (query.isBlank()) return true
    return messages.any { message -> message.matches(query, noticeText) }
}

private fun AgentChatMessageUi.matches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean = when (this) {
    is UserMessageUi -> {
        val prompt = AgentFileReferencePromptCodec.parse(content)
        prompt.request.contains(query, ignoreCase = true) ||
            prompt.references.any { reference ->
                reference.displayName.contains(query, ignoreCase = true) ||
                    reference.absolutePath.contains(query, ignoreCase = true)
            }
    }

    is AgentMessageUi -> content.contains(query, ignoreCase = true)

    is ThinkingMessageUi -> content.contains(query, ignoreCase = true)

    is ToolActivityMessageUi ->
        toolName.contains(query, ignoreCase = true) ||
            command?.contains(query, ignoreCase = true) == true ||
            argumentsSummary.contains(query, ignoreCase = true) ||
            resultSummary?.contains(query, ignoreCase = true) == true

    is ToolSummaryMessageUi -> tools.any { it.contains(query, ignoreCase = true) }

    is SystemNoticeMessageUi ->
        noticeText(code).contains(query, ignoreCase = true) ||
            detail?.contains(query, ignoreCase = true) == true

    else -> false
}
