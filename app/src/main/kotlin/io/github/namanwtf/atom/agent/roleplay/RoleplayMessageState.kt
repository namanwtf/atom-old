package io.github.namanwtf.atom.agent.roleplay

import kotlinx.serialization.Serializable

/** Body revisions are independent of the original execution history; association uses the message IDs produced by the run, not body equality. */
@Serializable
internal data class RoleplayMessageState(
    val links: Map<String, RoleplayMessageLink> = emptyMap(),
    val revisions: Map<String, RoleplayReplyRevision> = emptyMap(),
    val pendingRewrites: Map<String, String> = emptyMap(),
)

@Serializable
internal data class RoleplayMessageLink(val transcriptMessageId: String, val blockOrder: Int = 0)

@Serializable
internal data class RoleplayReplyRevision(
    val original: String,
    val candidates: List<String>,
    val selected: Int,
) {
    val content: String get() = candidates.getOrElse(selected) { original }
}
