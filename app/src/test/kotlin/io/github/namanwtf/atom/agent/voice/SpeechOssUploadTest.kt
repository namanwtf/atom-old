package io.github.namanwtf.atom.agent.voice

import android.app.Application
import android.content.Context
import io.github.namanwtf.atom.data.model.SpeechCredentials
import io.github.namanwtf.atom.data.model.SpeechOssConfig
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechOssUploadTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val config = SpeechOssConfig(bucket = "atom-test")
    private val secrets = SpeechCredentials(ossAccessKeyId = "test-id", ossAccessKeySecret = "test-secret")
    private fun pending() = context.getSharedPreferences("atom_speech_oss_cleanup", Context.MODE_PRIVATE).getStringSet("pending", emptySet())!!
    @Before fun reset() { context.getSharedPreferences("atom_speech_oss_cleanup", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun successfulTranscriptionDeletesUploadedObject() = runBlocking {
        val methods = mutableListOf<String>()
        val upload = SpeechOssUpload(context) { methods += it.method }
        val text = upload.withAudio(config, secrets, byteArrayOf(1, 2)) { url ->
            assertTrue(url.startsWith("https://atom-test.oss-cn-beijing.aliyuncs.com/atom-speech/"))
            assertEquals(1, pending().size)
            "transcript result"
        }
        assertEquals("transcript result", text)
        assertEquals(listOf("PUT", "DELETE"), methods)
        assertTrue(pending().isEmpty())
    }

    @Test fun failedDeletionSurvivesNewUploaderAndIsRetried() = runBlocking {
        SpeechOssUpload(context) { if (it.method == "DELETE") throw IOException("offline") }
            .withAudio(config, secrets, byteArrayOf(1, 2)) { "result" }
        assertEquals(1, pending().size)
        val deleted = mutableListOf<String>()
        SpeechOssUpload(context) { deleted += it.method }.retryPending(secrets)
        assertEquals(listOf("DELETE"), deleted)
        assertTrue(pending().isEmpty())
    }

    @Test fun cancelDuringUploadKeepsUncertainObjectForNextStartup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        val upload = SpeechOssUpload(context) {
            methods += it.method
            if (it.method == "PUT") { started.complete(Unit); awaitCancellation() }
        }
        val job = launch { upload.withAudio(config, secrets, byteArrayOf(1, 2)) { fail("Canceled upload must not submit transcription") } }
        started.await()
        job.cancelAndJoin()
        assertEquals(listOf("PUT", "DELETE"), methods)
        assertEquals(1, pending().size)
        SpeechOssUpload(context) {}.retryPending(secrets)
        assertTrue(pending().isEmpty())
    }

    @Test fun startupCleanupNeverDeletesLiveUpload() = runBlocking {
        val upload = SpeechOssUpload(context) {}
        upload.withAudio(config, secrets, byteArrayOf(1, 2)) {
            SpeechOssUpload(context) { fail("Must not delete a live upload") }.retryPending(secrets)
            "done"
        }
        assertTrue(pending().isEmpty())
    }
}
