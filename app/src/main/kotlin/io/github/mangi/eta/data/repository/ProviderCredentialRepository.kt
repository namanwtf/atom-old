package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.LiveProviderOAuthClient
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AppStrings

/** Initialized and accessed only by the Eta main process; callers run on an I/O thread and never write the returned token into configuration. */
internal object ProviderCredentialRepository {
    @Volatile private var sessions: ProviderCredentialSessions? = null

    @Synchronized
    fun init(context: Context) {
        if (sessions != null) return
        sessions = ProviderCredentialSessions(EncryptedProviderCredentialStorage(context), LiveProviderOAuthClient())
    }

    fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        sessions().account(providerId, authMode)

    fun binding(providerId: String, authMode: String): ProviderCredentialBinding = sessions().binding(providerId, authMode)

    fun isCurrent(binding: ProviderCredentialBinding): Boolean = sessions().isCurrent(binding)

    fun login(providerId: String, authMode: String, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderAccountSummary =
        sessions().login(providerId, authMode, controller, onPrompt)

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String =
        sessions().accessToken(providerId, authMode, controller, rejectedAccessToken)

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        sessions().disconnect(providerId, authMode, controller)

    fun forget(providerId: String) = sessions().forget(providerId)

    private fun sessions(): ProviderCredentialSessions = sessions
        ?: throw ProviderAuthException(
            "oauth_not_initialized",
            AppStrings.get(
                R.string.repo_err_auth_service_not_ready,
                "The sign-in service is not ready yet, please reopen Eta",
            ),
        )
}
