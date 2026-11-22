package io.github.mangi.eta.ui.app

/** Request to open an external entry's archived conversation: the conversation ID is derived UI-side from source and conversation key. */
data class ArchivedConversationRequest(
    val source: String,
    val conversationKey: String,
)
