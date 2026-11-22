package io.github.mangi.eta.ui.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.agent.voice.SpeechPlaybackController
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Voice settings draft for a single sub-page: loaded on page entry, edits only mutate memory, and save first runs the page's own
 * validation before persisting as a whole. Drafts are not shared across pages; unsaved edits are discarded when leaving the page.
 */
internal class SpeechSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(SpeechSettings())
        private set
    var credentials by mutableStateOf(SpeechCredentials())
        private set
    var loaded by mutableStateOf(false)
        private set
    var hasProviderDeepSeekKey by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    val recognition = SpeechInputController(context, scope, onResult = {
        message = context.getString(R.string.speech_test_result, it)
        messageIsError = false
    })
    val playback = SpeechPlaybackController(context, scope)

    init {
        scope.launch {
            try {
                settings = SpeechSettingsRepository.settings()
                hasProviderDeepSeekKey = SpeechSettingsRepository.deepSeekProviderCredentials() != null
                try {
                    credentials = SpeechSettingsRepository.credentials(context)
                } catch (_: SpeechCredentialsUnavailable) {
                    message = context.getString(R.string.speech_credentials_lost)
                    messageIsError = true
                }
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.speech_load_failed)
                messageIsError = true
            }
        }
    }

    fun edit(value: SpeechSettings) {
        stop()
        settings = value
        message = null
    }

    fun credential(field: SpeechCredentialField, value: String) {
        stop()
        credentials = credentials.withValue(field, value)
        message = null
    }

    fun stop() {
        recognition.cancel()
        playback.stop()
    }

    /** [validate] is provided by the page according to its own direction (recognition / speech / no validation); a failed validation aborts the save. */
    fun save(validate: () -> Unit) {
        if (!loaded || saving) return
        val config = settings
        val secrets = credentials
        stop()
        try {
            validate()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = (error as? SpeechFailure)?.userMessage
                ?: context.getString(R.string.speech_save_retry)
            message = context.getString(R.string.speech_save_failed, reason)
            messageIsError = true
            return
        }
        saving = true
        scope.launch {
            try {
                SpeechSettingsRepository.saveCredentials(context, secrets)
                SpeechSettingsRepository.save(config)
                message = context.getString(
                    if (settings === config && credentials === secrets) {
                        R.string.speech_saved
                    } else {
                        R.string.speech_saved_with_pending
                    },
                )
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = (error as? SpeechFailure)?.userMessage
                    ?: context.getString(R.string.speech_save_retry)
                message = context.getString(R.string.speech_save_failed, reason)
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
}
