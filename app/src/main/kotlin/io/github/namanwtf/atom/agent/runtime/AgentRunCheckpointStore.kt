package io.github.namanwtf.atom.agent.runtime

import android.content.Context
import io.github.namanwtf.atom.agent.model.AgentContextSnapshot
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.agent.model.AgentToolBatchRecovery
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.db.RuntimeInFlightEventEntity
import io.github.namanwtf.atom.data.db.RuntimeInFlightRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * UI events, the complete sanitized transcript, and model-context snapshots for in-flight runs.
 * The three have independent purposes; config and keys are not persisted, and sensitive raw tool data is filtered by the session codec.
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val contextSnapshot: AgentContextSnapshot? = null,
        val operation: String = AgentRuntimeWire.OP_CHAT,
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
        val rewriteTargetMessageId: String? = null,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext).runtimeRunDao().replaceInFlightRun(
                RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = ownerInstanceId,
                    operation = request.operation,
                    rewriteTargetMessageId = request.rewriteTargetMessageId,
                    handoffId = handoff.id,
                    handoffSource = handoff.source,
                    handoffPayload = handoff.payload,
                    dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val durableEvent = event.recoveryProjection() ?: return
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext).runtimeRunDao().appendInFlightEvent(
                event = RuntimeInFlightEventEntity(
                    runId = runId,
                    sortIndex = sortIndex,
                    eventJson = AgentEventJsonCodec.encode(durableEvent),
                ),
                updatedAt = now,
            )
        }
    }

    /** Return all unacknowledged runs; whether active or completed is decided by the recovery coordinator together with Runtime state. */
    fun list(context: Context): List<Checkpoint> =
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .inFlightRuns()
                .asSequence()
                .map { stored ->
                    Checkpoint(
                        runId = stored.run.runId,
                        ownerInstanceId = stored.run.ownerInstanceId,
                        contextSnapshot = AgentContextSnapshot.decode(stored.run.contextSnapshotJson),
                        operation = stored.run.operation,
                        rewriteTargetMessageId = stored.run.rewriteTargetMessageId,
                        transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.decodeTranscript(stored.run.transcriptJson)),
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = stored.run.handoffId,
                            source = stored.run.handoffSource,
                            payload = stored.run.handoffPayload,
                            dismissEntrySurfaceOnForegroundOperation =
                                stored.run.dismissEntrySurface,
                        ),
                        events = stored.events
                            .sortedBy { it.sortIndex }
                            .mapNotNull { AgentEventJsonCodec.decode(it.eventJson) },
                        createdAt = stored.run.createdAt,
                        updatedAt = stored.run.updatedAt,
                    )
                }
                .toList()
        }

    fun saveTranscript(context: Context, runId: String, transcript: List<AgentModelClient.ConversationMessage>) {
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext).runtimeRunDao()
                .updateTranscript(runId, AgentConversationCodec.encodeTranscriptForStorage(transcript))
        }
    }

    fun saveContext(context: Context, runId: String, snapshot: AgentContextSnapshot) {
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext).runtimeRunDao().updateContextSnapshot(runId, snapshot.encode())
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            AtomDatabase.get(context.applicationContext)
                .runtimeRunDao()
                .deleteInFlightRun(runId)
        }
    }
}
