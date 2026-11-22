package io.github.namanwtf.atom.data.repository

import io.github.namanwtf.atom.data.datastore.SettingsDataStore
import io.github.namanwtf.atom.data.db.ConversationContextCheckpointEntity
import io.github.namanwtf.atom.data.db.ConversationEntity
import io.github.namanwtf.atom.data.db.ConversationMessageEntity
import io.github.namanwtf.atom.data.db.ConversationStateEntity
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.model.ModelSource
import io.github.namanwtf.atom.data.model.ProviderAuthModes
import io.github.namanwtf.atom.data.model.OpenAiCompatibleProviderSetting
import io.github.namanwtf.atom.data.model.Model
import io.github.namanwtf.atom.data.model.withApiKey
import io.github.namanwtf.atom.data.provider.BuiltinProviders
import io.github.namanwtf.atom.data.provider.OfficialModelCatalog
import io.github.namanwtf.atom.agent.roleplay.CharacterCardCodec
import io.github.namanwtf.atom.agent.roleplay.RoleplayBinding
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageLink
import io.github.namanwtf.atom.agent.roleplay.RoleplayMessageState
import io.github.namanwtf.atom.agent.roleplay.RoleplayReplyRevision
import io.github.namanwtf.atom.agent.roleplay.UserPersona
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AtomDatabase.closeForTests()
        context.deleteDatabase("atom.db")
        SettingsDataStore.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test
    fun oauthBackupPreservesConnectionModeButCannotRestoreSecretThroughApiKey() = runBlocking {
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "oauth-backup", name = "subscription", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
        val dao = AtomDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById("oauth-backup")).provider
        dao.updateProvider(stored.copy(apiKey = "fixture-secret-in-legacy-column"))
        val output = ByteArrayOutputStream()
        AtomBackupRepository.export(context, output)
        val raw = output.toString(Charsets.UTF_8.name())
        assertFalse(raw.contains("fixture-secret-in-legacy-column"))
        val exported = Json.decodeFromString<AtomBackupDocument>(raw)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT,
            exported.providers.single { it.provider.id == "oauth-backup" }.provider.authMode)

        val imported = AtomBackupDocument(exportedAt = 0, providers = listOf(
            AtomBackupProvider(stored.copy(apiKey = "fixture-secret-from-backup")),
        ))
        AtomBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(imported).toByteArray()))
        val restored = requireNotNull(dao.providerById("oauth-backup")).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertEquals("", restored.apiKey)
    }

    @Test
    fun oauthRestoreActivatesConnectionAfterCleanupAndRemembersItsModelWhileRequiringLogin() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-restore", name = "original connection", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "oauth-restored-model", modelId = "fixture-model", displayName = "test model")),
        )
        ProviderRepository.addProvider(provider)
        val dao = AtomDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = AtomBackupDocument(
            exportedAt = 0,
            providers = listOf(AtomBackupProvider(stored.provider.copy(name = "imported connection"), stored.models)),
            selectedProviderId = provider.id,
            selectedModelId = provider.models.single().id,
        )
        var committed = false
        val credentials = mutableMapOf(provider.id to "fixture-existing-credential")
        AtomBackupRepository.import(
            context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
            onCommitted = { committed = true },
            forgetCredentials = { id ->
                assertTrue(committed)
                val pending = runBlocking { requireNotNull(dao.providerById(provider.id)).provider }
                assertEquals("imported connection", pending.name)
                assertEquals(ProviderAuthModes.API_KEY, pending.authMode)
                assertFalse(pending.isEnabled)
                assertEquals("", pending.apiKey)
                credentials.remove(id)
            },
        )

        assertTrue(credentials.isEmpty())
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertTrue(restored.isEnabled)
        assertEquals(provider.models.single().id, SettingsDataStore.selectedModelIdForProvider(provider.id))
        assertEquals(provider.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(provider.models.single().id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun cleanupFailureReportsCommittedDataAndLeavesImportedOAuthConnectionsUnableToAuthenticate() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-cleanup-failure", name = "original connection", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        AgentMemoryRepository.replaceAll("old memory")
        val dao = AtomDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = AtomBackupDocument(exportedAt = 0, memoryMd = "imported memory", providers = listOf(
            AtomBackupProvider(stored.provider.copy(name = "imported connection")),
        ))
        var committed = false
        val failure = runCatching {
            AtomBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true },
                forgetCredentials = { throw java.io.IOException("fixture cleanup failure") },
            )
        }.exceptionOrNull()

        assertTrue(committed)
        assertTrue(failure is IllegalStateException)
        assertEquals(
            context.getString(io.github.namanwtf.atom.R.string.backup_err_import_oauth_cleanup_incomplete),
            failure?.message,
        )
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals("imported connection", restored.name)
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
        assertFalse(restored.isEnabled)
        assertEquals("", restored.apiKey)
        assertEquals("imported memory", AgentMemoryRepository.snapshot().content)
    }

    @Test
    fun cancellationAfterCommitStillFinishesCredentialCleanupAndConnectionActivation() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-commit-cancel", name = "subscription", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        val dao = AtomDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = AtomBackupDocument(exportedAt = 0, providers = listOf(AtomBackupProvider(stored.provider)))
        var committed = false
        val cleared = mutableListOf<String>()
        val operation = launch {
            val importJob = coroutineContext.job
            AtomBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true; importJob.cancel() },
                forgetCredentials = { cleared += it },
            )
        }
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(committed)
        assertTrue(provider.id in cleared)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, requireNotNull(dao.providerById(provider.id)).provider.authMode)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.allProviders().first().withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# Core memory\nLikes Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "backup conversation",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )
        AtomDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "keep this message",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = AtomBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# Core memory\nLikes Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        AtomDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = AtomBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# Core memory\nLikes Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "keep this message",
            AtomDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test(expected = AtomBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        AtomBackupRepository.inspect(context,
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }

    @Test
    fun roleplayBackupRestoresCardsPersonaAvatarMemoryAndLargeSessionMetadata() = runBlocking {
        CharacterRepository.initialize(context)
        val profile = CharacterRepository.create(CharacterCardCodec.create("character").withEdits(description = "setup".repeat(40_000)))
        CharacterRepository.writeAvatar(profile.id, Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
        )).let { CharacterRepository.save(profile.copy(avatarPath = it)) }
        CharacterRepository.savePersona(UserPersona("traveler", "from afar"))
        CharacterMemoryRepository.replaceAll(context, profile.id, "# story\nbeen to the mountains")
        val binding = CharacterRepository.binding(profile.id)
        val revisions = Json.encodeToString(RoleplayMessageState(
            links = mapOf("role-message" to RoleplayMessageLink("role-message")),
            revisions = mapOf("role-message" to RoleplayReplyRevision("original version", listOf("original version", "version".repeat(40_000)), 1)),
        ))
        AtomDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity("role-session", "story", false, roleplayJson = Json.encodeToString(binding),
                revisionsJson = revisions, createdAt = 1, updatedAt = 1),
        ))
        AtomDatabase.get(context).conversationDao().insertMessages(listOf(
            ConversationMessageEntity("role-message", "role-session", 0, "assistant", "version".repeat(40_000)),
        ))
        val output = ByteArrayOutputStream()
        assertEquals(1, AtomBackupRepository.export(context, output).characterCount)
        CharacterRepository.save(profile.copy(card = profile.card.withEdits(description = "change")))
        CharacterMemoryRepository.replaceAll(context, profile.id, "change")
        CharacterRepository.savePersona(UserPersona("other", ""))
        AtomBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals("setup".repeat(40_000), CharacterRepository.get(profile.id)?.card?.description)
        assertEquals("traveler", CharacterRepository.persona().name)
        assertEquals("# story\nbeen to the mountains", CharacterMemoryRepository.snapshot(context, profile.id).content)
        val row = AtomDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(revisions, row.revisionsJson)
        val restoredBinding = Json.decodeFromString<RoleplayBinding>(row.roleplayJson)
        assertTrue(java.io.File(restoredBinding.avatarPath!!).isFile)
        assertEquals(CharacterRepository.get(profile.id)?.avatarPath, restoredBinding.avatarPath)
    }

    @Test
    fun versionOneBackupRemainsReadable() = runBlocking {
        val summary = AtomBackupRepository.inspect(context, ByteArrayInputStream(
            """{"format":"atom-backup","schemaVersion":1,"exportedAt":0}""".toByteArray(),
        ))
        assertEquals(0, summary.characterCount)
    }

    @Test
    fun oldBackupReceivesOnlyModelsAddedAfterItsCatalogRevision() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = AtomDatabase.get(context).providerDao().providers().map { row ->
            val models = if (row.provider.id == BuiltinProviders.OPENAI_ID) {
                row.models.filterNot { it.modelId == "gpt-6.1-sol" }
            } else {
                row.models
            }
            AtomBackupProvider(row.provider, models)
        }
        val oldBackup = AtomBackupDocument(exportedAt = 0, providers = providers)
        AtomBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(oldBackup).toByteArray()))

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!.models
        assertTrue(restored.any { it.modelId == "gpt-6-astra" })
        assertTrue(restored.none { it.modelId == "gpt-5.6-terra" })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
        assertEquals(ProviderAuthModes.API_KEY, ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)?.authMode)
    }

    @Test
    fun currentBackupKeepsDeletionOfNewCatalogModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val removed = provider.models.first { it.modelId == "gpt-6.1-sol" }
        ModelRepository.deleteModel(provider.id, removed.id)
        val output = ByteArrayOutputStream()
        AtomBackupRepository.export(context, output)
        val document = Json.decodeFromString<AtomBackupDocument>(output.toString(Charsets.UTF_8.name()))
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, document.catalogRevision)

        AtomBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))

        assertTrue(ProviderRepository.providerById(provider.id)!!.models.none { it.modelId == removed.modelId })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles() = runBlocking {
        CharacterRepository.initialize(context)
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "existing-provider", name = "original subscription", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
        val first = CharacterRepository.create(CharacterCardCodec.create("original character"))
        val blocked = CharacterRepository.create(CharacterCardCodec.create("blocked character"))
        CharacterMemoryRepository.replaceAll(context, first.id, "original story memory")
        AgentMemoryRepository.replaceAll("original real-world memory")
        val database = AtomDatabase.get(context)
        val records = database.characterDao().characters().map { row ->
            if (row.id == first.id) row.copy(cardJson = CharacterCardCodec.encodeJson(first.card.withEdits(name = "backup character"))) else row
        }
        val document = AtomBackupDocument(exportedAt = 0, memoryMd = "new real-world memory",
            roleplay = CharacterBackupData(characters = records, assets = listOf(
                CharacterBackupAsset(first.id, memoryMd = "new story memory"),
                CharacterBackupAsset(blocked.id, memoryMd = "write failure"),
            )),
        )
        val blocker = java.io.File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("block directory creation")
        val credentials = mutableMapOf("existing-provider" to "fixture-existing-credential")
        var committed = false
        try {
            var failed = false
            try {
                AtomBackupRepository.import(
                    context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                    onCommitted = { committed = true },
                    forgetCredentials = { credentials.remove(it) },
                )
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
            assertFalse(committed)
            assertEquals(mapOf("existing-provider" to "fixture-existing-credential"), credentials)
            assertEquals("original character", CharacterRepository.get(first.id)?.card?.name)
            assertEquals("original story memory", CharacterMemoryRepository.snapshot(context, first.id).content)
            assertEquals("original real-world memory", AgentMemoryRepository.snapshot().content)
        } finally {
            check(blocker.delete())
        }
    }

    @Test
    fun invalidRevisionBackupIsRejectedBeforeExistingConversationsAreChanged() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        val original = ConversationEntity("original", "original conversation", false, createdAt = 1, updatedAt = 1)
        dao.insertConversations(listOf(original))
        val invalidStates = listOf(
            "not-json",
            Json.encodeToString(RoleplayMessageState(
                links = mapOf("reply" to RoleplayMessageLink("reply")),
                revisions = mapOf("reply" to RoleplayReplyRevision("original version", listOf("original version"), 9)),
            )),
            Json.encodeToString(RoleplayMessageState(pendingRewrites = mapOf("run" to "outside-conversation"))),
        )
        invalidStates.forEach { revisions ->
            val document = AtomBackupDocument(exportedAt = 0,
                conversations = listOf(ConversationEntity("new", "imported conversation", false, revisionsJson = revisions, createdAt = 1, updatedAt = 1)),
                messages = listOf(ConversationMessageEntity("reply", "new", 0, "assistant", "original version")),
            )
            var failed = false
            try {
                AtomBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: AtomBackupException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
        }
    }
}
