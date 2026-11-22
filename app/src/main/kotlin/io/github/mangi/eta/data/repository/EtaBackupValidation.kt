package io.github.mangi.eta.data.repository

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.model.ProviderAuthModes

/** Validation keeps only the message types needed for identity and character association; bodies and checkpoints are read one at a time and released. */
internal object EtaBackupValidation {
    suspend fun validate(staged: EtaBackupJsonStreams.StagedDocument): EtaBackupSummary {
        val header = staged.header
        validateHeader(header)
        val conversationIds = mutableSetOf<String>()
        val revisionConversationIds = mutableSetOf<String>()
        val bindingIds = mutableSetOf<String>()
        try {
            staged.conversations { row ->
                if (row.id.isBlank() || !conversationIds.add(row.id)) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_invalid_conversation_id,
                            "A conversation in the backup has a duplicate or invalid ID",
                        ),
                    )
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException(
                    AppStrings.get(
                        R.string.backup_err_selected_conversation_missing,
                        "The current conversation in the backup does not exist",
                    ),
                )
            }
            val messageIds = mutableSetOf<String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_message_missing_conversation,
                            "A message in the backup is missing its conversation",
                        ),
                    )
                }
                if (row.id.isBlank() || !messageIds.add(row.id)) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_duplicate_message_id,
                            "Duplicate message ID in the backup",
                        ),
                    )
                }
                if (!messagePositions.add(row.conversationId to row.sortIndex)) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_duplicate_message_order,
                            "Duplicate message order in the backup",
                        ),
                    )
                }
                if (row.conversationId in revisionConversationIds) {
                    revisionMessageTypes.getOrPut(row.conversationId) { mutableMapOf() }[row.id] = row.type
                }
                messageCount++
            }
            staged.conversations { row ->
                CharacterBackupTransfer.validateRevisions(row, revisionMessageTypes[row.id].orEmpty())
            }
            val checkpointIds = mutableSetOf<String>()
            staged.checkpoints { row ->
                if (row.conversationId !in conversationIds) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_checkpoint_missing_conversation,
                            "A context checkpoint in the backup is missing its conversation",
                        ),
                    )
                }
                if (!checkpointIds.add(row.conversationId)) {
                    throw EtaBackupException(
                        AppStrings.get(
                            R.string.backup_err_duplicate_checkpoint,
                            "Duplicate context checkpoint in the backup",
                        ),
                    )
                }
            }
            header.roleplay?.let { CharacterBackupTransfer.validate(it, bindingIds) }
            return EtaBackupSummary(
                providerCount = header.providers.size,
                modelCount = header.providers.sumOf { it.models.size },
                conversationCount = conversationIds.size,
                messageCount = messageCount,
                memoryBytes = header.memoryMd.toByteArray(Charsets.UTF_8).size,
                characterCount = header.roleplay?.characters?.size ?: 0,
            )
        } catch (failure: IllegalArgumentException) {
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_invalid_records_or_characters,
                    "A record or character data in the backup is invalid",
                ),
                failure,
            )
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) {
            throw EtaBackupException(
                AppStrings.get(R.string.backup_err_not_eta_backup, "This is not an Eta backup file"),
            )
        }
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_unsupported_version,
                    "Unsupported Eta backup version：%1\$d",
                    document.schemaVersion,
                ),
            )
        }
        if (document.catalogRevision < 0) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_invalid_catalog_revision,
                    "The model catalog version in the backup is invalid",
                ),
            )
        }
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_invalid_provider_id,
                    "A model provider in the backup has a duplicate or invalid ID",
                ),
            )
        }
        val modelIds = document.providers.flatMap { provider ->
            if (provider.provider.authMode != ProviderAuthModes.API_KEY &&
                !ProviderAuthModes.isOAuth(provider.provider.authMode)
            ) {
                throw EtaBackupException(
                    AppStrings.get(
                        R.string.backup_err_unsupported_provider_auth,
                        "The provider authentication method in the backup is unsupported",
                    ),
                )
            }
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                throw EtaBackupException(
                    AppStrings.get(
                        R.string.backup_err_invalid_model_id,
                        "A model in the backup has a duplicate or invalid ID",
                    ),
                )
            }
            if (provider.models.any { it.providerId != provider.provider.id }) {
                throw EtaBackupException(
                    AppStrings.get(
                        R.string.backup_err_model_provider_mismatch,
                        "A model in the backup does not match its provider",
                    ),
                )
            }
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_duplicate_model_id,
                    "Duplicate model ID in the backup",
                ),
            )
        }
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_selected_provider_missing,
                    "The current provider in the backup does not exist",
                ),
            )
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_selected_model_missing,
                    "The current model in the backup does not exist",
                ),
            )
        }
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_selected_model_provider_mismatch,
                    "The current model in the backup does not match its provider",
                ),
            )
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_invalid_conversation_state,
                    "The conversation state in the backup is invalid",
                ),
            )
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            throw EtaBackupException(
                AppStrings.get(
                    R.string.backup_err_memory_too_large,
                    "MEMORY.md exceeds the 1 MiB limit",
                ),
            )
        }
    }
}
