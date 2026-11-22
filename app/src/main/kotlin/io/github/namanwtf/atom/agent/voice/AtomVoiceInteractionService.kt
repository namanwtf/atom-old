package io.github.namanwtf.atom.agent.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.namanwtf.atom.core.AssistantContextFlags

class AtomVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, AtomVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showAtomSession() {
        showSession(Bundle(), AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        @Volatile
        private var activeService: AtomVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showAtomSession()
            return true
        }
    }
}

class AtomVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AtomVoiceInteractionService.requestSession()) {
            AtomAssistantOverlayService.show(this, null)
        }
        finish()
    }
}
