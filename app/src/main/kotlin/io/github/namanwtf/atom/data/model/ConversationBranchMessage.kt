package io.github.namanwtf.atom.data.model

/** Branch boundaries only read the message structure, avoiding loading all bodies and images to locate old turns. */
internal data class ConversationBranchMessage(
    val id: String,
    val sortIndex: Int,
    val type: String,
    val hasContent: Boolean,
    val noticeCode: String = "",
)
