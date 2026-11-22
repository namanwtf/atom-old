package io.github.mangi.eta.agent.runtime

import java.util.UUID

/** Records the process that created the checkpoint, for diagnostics only; recovery state is decided by Runtime reconciliation. */
internal object AgentRuntimeProcessIdentity {
    val id: String = UUID.randomUUID().toString()
}
