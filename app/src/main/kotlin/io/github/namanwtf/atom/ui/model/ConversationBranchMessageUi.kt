package io.github.namanwtf.atom.ui.model

import io.github.namanwtf.atom.data.model.ConversationBranchMessage

internal fun List<AgentChatMessageUi>.branchMessageOutline(): List<ConversationBranchMessage> = mapIndexed { index, message ->
    ConversationBranchMessage(
        id = message.id,
        sortIndex = index,
        type = when (message) {
            is UserMessageUi -> "user"
            is AgentMessageUi -> "assistant"
            is SystemNoticeMessageUi -> "system_notice"
            is ThinkingMessageUi -> "thinking"
            is ToolActivityMessageUi -> "tool"
            is ToolSummaryMessageUi -> "tool_summary"
            else -> ""
        },
        hasContent = message is AgentMessageUi && message.content.isNotBlank() && !message.isStreaming,
        noticeCode = (message as? SystemNoticeMessageUi)?.code?.wireValue.orEmpty(),
    )
}
