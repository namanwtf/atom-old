package io.github.namanwtf.atom.data.repository

import android.content.Context
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.auth.EncryptedProviderCredentialStorage
import io.github.namanwtf.atom.agent.auth.LiveProviderOAuthClient
import io.github.namanwtf.atom.agent.auth.ProviderAccountSummary
import io.github.namanwtf.atom.agent.auth.ProviderAuthException
import io.github.namanwtf.atom.agent.auth.ProviderCredentialSessions
import io.github.namanwtf.atom.agent.auth.ProviderCredentialBinding
import io.github.namanwtf.atom.agent.auth.ProviderLoginPrompt
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.core.AppStrings

/** Initialized and accessed only by the Atom main process; callers run on an I/O thread and never write the returned token into configuration. */
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
                "The sign-in service is not ready yet, please reopen Atom",
            ),
        )
}
