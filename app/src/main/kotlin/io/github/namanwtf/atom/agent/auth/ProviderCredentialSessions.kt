package io.github.namanwtf.atom.agent.auth

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.core.AppStrings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The refresh lock only coalesces network requests for the same account; sign-out does not wait for the network and invalidates writes from old requests. */
internal class ProviderCredentialSessions(
    private val storage: ProviderCredentialStorage,
    private val oauth: ProviderOAuthClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Session {
        val stateLock = ReentrantLock()
        val refreshLock = ReentrantLock()
        var generation = 0L
        var loginGeneration = 0L
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    fun account(providerId: String, authMode: String): ProviderAccountSummary? {
        val session = session(providerId)
        return session.stateLock.withLock { storage.read(providerId)?.takeIf { it.authMode == authMode }?.summary() }
    }

    fun binding(providerId: String, authMode: String): ProviderCredentialBinding {
        val session = session(providerId)
        return session.stateLock.withLock {
            requireCredential(storage.read(providerId), authMode)
            ProviderCredentialBinding(providerId, authMode, session.generation)
        }
    }

    fun isCurrent(binding: ProviderCredentialBinding): Boolean {
        val session = session(binding.providerId)
        return session.stateLock.withLock {
            val credential = storage.read(binding.providerId)
            session.generation == binding.generation && credential?.authMode == binding.authMode &&
                credential.subject != null && credential.accessToken.isNotBlank() && credential.planEnabled
        }
    }

    fun login(providerId: String, authMode: String, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderAccountSummary {
        val provider = OAuthProvider.fromAuthMode(authMode)
        val session = session(providerId)
        val loginGeneration = session.stateLock.withLock { ++session.loginGeneration }
        val credential = oauth.login(provider, controller, onPrompt)
        controller.throwIfCancelled()
        val saved = session.stateLock.withLock {
            controller.throwIfCancelled()
            // While browser sign-in has not yet succeeded, the existing session can still be refreshed; cancelling sign-in must not discard already-rotated credentials.
            requireGeneration(session.loginGeneration, loginGeneration)
            storage.write(providerId, credential)
            ++session.generation
            credential
        }
        return saved.summary()
    }

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String {
        OAuthProvider.fromAuthMode(authMode)
        val session = session(providerId)
        while (!session.refreshLock.tryLock(100, TimeUnit.MILLISECONDS)) controller.throwIfCancelled()
        try {
            controller.throwIfCancelled()
            val (generation, credential) = session.stateLock.withLock {
                session.generation to requireCredential(storage.read(providerId), authMode)
            }
            val rejected = rejectedAccessToken != null && rejectedAccessToken == credential.accessToken
            val expiresSoon = credential.expiresAt <= now() + REFRESH_MARGIN_MS
            if (!rejected && !expiresSoon && credential.expiresAt > now()) return credential.accessToken
            if (credential.refreshToken.isBlank()) {
                invalidate(providerId, session, generation)
                throw ProviderAuthException(
                    "oauth_session_expired",
                    AppStrings.get(R.string.auth_err_session_expired, "Sign-in expired; please sign in again"),
                )
            }
            val refreshed = try {
                oauth.refresh(credential, controller)
            } catch (error: ProviderAuthException) {
                if (error.code == "oauth_session_expired") invalidate(providerId, session, generation)
                throw error
            }
            // Rotation has already completed server-side; even if the caller just cancelled, persist the valid result first to avoid losing the new refresh token.
            session.stateLock.withLock {
                requireGeneration(session.generation, generation)
                storage.write(providerId, refreshed)
            }
            controller.throwIfCancelled()
            return requireCredential(refreshed, authMode).accessToken
        } finally {
            session.refreshLock.unlock()
        }
    }

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? {
        val session = session(providerId)
        val credential = session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            val current = try { storage.read(providerId) } catch (_: ProviderAuthException) {
                storage.delete(providerId)
                return false
            }
            if (current != null && current.authMode == authMode) storage.write(providerId, current.withoutTokens())
            current?.takeIf { it.authMode == authMode }
        } ?: return true
        return try { oauth.revoke(credential, controller) } catch (_: ProviderAuthException) { false }
    }

    fun forget(providerId: String) {
        val session = session(providerId)
        session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            storage.delete(providerId)
        }
    }

    private fun invalidate(providerId: String, session: Session, generation: Long) {
        session.stateLock.withLock {
            if (session.generation != generation) return
            storage.read(providerId)?.let { storage.write(providerId, it.withoutTokens()) }
            ++session.generation
        }
    }

    private fun requireCredential(credential: ProviderCredential?, authMode: String): ProviderCredential {
        if (credential == null || credential.subject == null || credential.authMode != authMode || credential.accessToken.isBlank()) {
            throw ProviderAuthException(
                "oauth_login_required",
                AppStrings.get(R.string.auth_err_login_required, "Please sign in to this provider account first"),
            )
        }
        if (!credential.planEnabled) throw ProviderAuthException(
            "oauth_plan_permission_required",
            AppStrings.get(R.string.auth_err_plan_permission_required, "Account credentials cannot be used for the current subscription connection; please sign in again"),
        )
        return credential
    }

    private fun session(providerId: String): Session {
        if (providerId.isBlank()) throw ProviderAuthException(
            "oauth_provider_missing",
            AppStrings.get(R.string.auth_err_provider_missing, "Provider connection does not exist"),
        )
        return sessions.computeIfAbsent(providerId) { Session() }
    }

    private fun requireGeneration(current: Long, expected: Long) {
        if (current != expected) throw ProviderAuthException(
            "oauth_session_changed",
            AppStrings.get(R.string.auth_err_session_changed, "Sign-in state changed; please retry"),
        )
    }

    private companion object { const val REFRESH_MARGIN_MS = 60_000L }
}
