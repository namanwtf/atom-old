package io.github.mangi.eta.ui.components

/**
 * Generates an independent Compose state boundary for the chat stage.
 *
 * On conversation switch, LazyListState, streaming Markdown state, and the bottom-follow task must be rebuilt,
 * otherwise the old conversation's list position could be reused by the new one.
 */
internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"
