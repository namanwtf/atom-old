package io.github.namanwtf.atom.data.repository

import android.content.Context
import io.github.namanwtf.atom.data.datastore.SettingsDataStore
import io.github.namanwtf.atom.data.db.ConversationEntity
import io.github.namanwtf.atom.data.db.ConversationContextCheckpointEntity
import io.github.namanwtf.atom.data.db.ConversationMessageEntity
import io.github.namanwtf.atom.data.db.AtomDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtomBackupStreamingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AtomDatabase.closeForTests()
        context.deleteDatabase("atom.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AgentMemoryRepository.replaceAll("")
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
    }

    @Test
    fun aBackupAboveTheOld64MiBLimitRoundTripsWithoutCollectingAllMessages() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("large")))
        val text = "first line😀\n\"\\\t" + "x".repeat(512 * 1024)
        repeat(132) { index ->
            dao.insertMessages(listOf(ConversationMessageEntity("message-$index", "large", index, "thinking", text)))
        }
        val file = File(context.cacheDir, "large-backup-test.json")
        try {
            val exported = file.outputStream().use { AtomBackupRepository.export(context, it) }
            assertTrue(file.length() > 64L * 1024L * 1024L)
            assertEquals(132, exported.messageCount)
            assertEquals(exported, file.inputStream().use { AtomBackupRepository.inspect(context, it) })
            dao.deleteConversations()

            val imported = file.inputStream().use { AtomBackupRepository.import(context, it) }

            assertEquals(exported, imported)
            assertEquals(132, dao.messageCount("large"))
            assertEquals(text, dao.messagesPage("large", 1, 0).single().content)
            assertEquals(text, dao.messagesPage("large", 1, 131).single().content)
            assertCleanStaging()
        } finally {
            file.delete()
        }
    }

    @Test
    fun reorderedLegacyFieldsAndUnknownValuesRemainReadable() = runBlocking {
        val bytes = """{
            "messages":[{"id":"m","conversationId":"c","sortIndex":0,"type":"user","content":"hello😀"}],
            "unknown":{"nested":[1,true,null,{"text":"ignore"}]},
            "conversations":[{"id":"c","title":"old backup","thinkingEnabled":false,"createdAt":1,"updatedAt":2}],
            "exportedAt":0,"schemaVersion":1,"format":"atom-backup"
        }""".toByteArray()

        val summary = AtomBackupRepository.import(context, ByteArrayInputStream(bytes))

        assertEquals(1, summary.conversationCount)
        assertEquals("hello😀", AtomDatabase.get(context).conversationDao().messages().single().content)
        assertCleanStaging()
    }

    @Test
    fun veryLargeMessageAndCheckpointFieldsArePreserved() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        val text = "😀\n\"\\" + "x".repeat(17 * 1024 * 1024)
        val history = "[{\"role\":\"assistant\",\"content\":${Json.encodeToString(text)}}]"
        dao.insertConversations(listOf(conversation("large-field")))
        dao.insertMessages(listOf(ConversationMessageEntity("large-message", "large-field", 0, "thinking", text)))
        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity("large-field", history, history)))
        val file = File(context.cacheDir, "large-field-backup-test.json")
        try {
            file.outputStream().use { AtomBackupRepository.export(context, it) }
            dao.deleteConversations()

            file.inputStream().use { AtomBackupRepository.import(context, it) }

            assertTrue(dao.messagesPage("large-field", 1, 0).single().content == text)
            val checkpoint = dao.contextCheckpoint("large-field")!!
            assertTrue(checkpoint.historyJson == history)
            assertTrue(checkpoint.journalJson == history)
            assertCleanStaging()
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedOrLateInvalidRecordsNeverReplaceExistingData() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("original")))
        AgentMemoryRepository.replaceAll("original memory")
        val document = AtomBackupDocument(exportedAt = 0, memoryMd = "new memory",
            conversations = listOf(conversation("new")),
            messages = listOf(
                ConversationMessageEntity("valid", "new", 0, "user", "valid"),
                ConversationMessageEntity("orphan", "missing", 1, "user", "no owning conversation"),
            ),
        )
        val encoded = Json.encodeToString(document)
        val invalid = listOf(encoded, encoded.dropLast(2), "$encoded {}",
            """{"format":"atom-backup","format":"other","exportedAt":0}""")
        for (source in invalid) {
            var rejected = false
            try {
                AtomBackupRepository.import(context, ByteArrayInputStream(source.toByteArray()))
            } catch (_: AtomBackupException) { rejected = true }
            assertTrue(rejected)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
            assertEquals("original memory", AgentMemoryRepository.snapshot().content)
            assertCleanStaging()
        }
    }

    @Test
    fun cancellingStagingClosesTemporaryFilesAndPreservesTheCallerStream() = runBlocking {
        val job = Job()
        var closed = false
        val input = object : ByteArrayInputStream("""{"exportedAt":0,"messages":[],"memoryMd":"content"}""".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, minOf(length, 8))
                job.cancel()
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        var cancelled = false
        try { withContext(job) { AtomBackupJsonStreams.stage(context, input).close() } }
        catch (_: CancellationException) { cancelled = true }

        assertTrue(cancelled)
        assertFalse(closed)
        assertCleanStaging()
    }

    @Test
    fun aLateExportReadFailureLeavesTheDestinationUntouchedAndRemovesStaging() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("broken")))
        dao.insertMessages(listOf(ConversationMessageEntity("broken-message", "broken", 0, "user", "x".repeat(40_000))))
        AtomDatabase.get(context).openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_text_chunks WHERE owner_table = 'conversation_messages' AND chunk_index = 0",
        )
        val output = ByteArrayOutputStream()
        var failed = false
        try { AtomBackupRepository.export(context, output) } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(0, output.size())
        assertCleanStaging()
    }

    @Test
    fun outputIoFailuresCleanStagingAndKeepDatabaseContents() = runBlocking {
        val dao = AtomDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("existing")))
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("private-output-path") }
        }
        var failed = false
        try { AtomBackupRepository.export(context, output) } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("existing"), dao.conversationEntities().map { it.id })
        assertCleanStaging()
    }

    @Test
    fun byteLimitStopsBothSingleByteAndBufferedReads() {
        for (buffered in listOf(false, true)) {
            val input = AtomBackupJsonStreams.LimitedInput(ByteArrayInputStream(ByteArray(65)), 64)
            var rejected = false
            try {
                if (buffered) input.readBytes() else while (input.read() >= 0) { }
            } catch (_: AtomBackupException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test
    fun exportLimitsAndCancellationKeepTheDestinationUntouched() = runBlocking {
        val output = ByteArrayOutputStream()
        var rejected = false
        try {
            AtomBackupJsonStreams.export(context, output, maxBytes = 64) { writer ->
                writer.value("memoryMd", String.serializer(), "x".repeat(128))
                AtomBackupSummary(0, 0, 0, 0, 128)
            }
        } catch (_: AtomBackupException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, output.size())
        assertCleanStaging()

        val job = Job()
        var cancelled = false
        try {
            withContext(job) {
                AtomBackupJsonStreams.export(context, output) { writer ->
                    writer.value("memoryMd", String.serializer(), "content")
                    job.cancel()
                    AtomBackupSummary(0, 0, 0, 0, 6)
                }
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, output.size())
        assertCleanStaging()
    }

    private fun conversation(id: String) = ConversationEntity(id, id, false, createdAt = 1, updatedAt = 2)

    private fun assertCleanStaging() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("atom_backup_") })
    }
}
