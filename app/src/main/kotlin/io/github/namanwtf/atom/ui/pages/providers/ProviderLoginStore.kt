package io.github.namanwtf.atom.ui.pages.providers

import io.github.namanwtf.atom.AtomApp
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.auth.ProviderAccountSummary
import io.github.namanwtf.atom.agent.auth.ProviderAuthException
import io.github.namanwtf.atom.agent.auth.ProviderLoginPrompt
import io.github.namanwtf.atom.agent.runtime.AgentRunCancelledException
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.data.model.ProviderAuthModes
import io.github.namanwtf.atom.data.model.ProviderSetting
import io.github.namanwtf.atom.data.repository.ModelRepository
import io.github.namanwtf.atom.data.repository.RemoteModelFetcher
import io.github.namanwtf.atom.data.repository.ProviderCredentialRepository
import io.github.namanwtf.atom.data.repository.ProviderRepository
import io.github.namanwtf.atom.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProviderLoginPhase { Idle, Loading, Saving, Authorizing, SyncingModels, Disconnecting }

internal data class ProviderLoginState(
    val authMode: String = ProviderAuthModes.API_KEY,
    val account: ProviderAccountSummary? = null,
    val prompt: ProviderLoginPrompt? = null,
    val phase: ProviderLoginPhase = ProviderLoginPhase.Idle,
    val message: String? = null,
) {
    val isBusy: Boolean get() = phase != ProviderLoginPhase.Idle
}

/** The login session belongs to the current config page; browser switches don't cancel it, while leaving the page closes polling or callback listeners. */
internal class ProviderLoginStore(
    private val providerId: String,
    parentScope: CoroutineScope,
    private val operations: ProviderLoginOperations = RepositoryProviderLoginOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ProviderLoginState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var controller: AgentRunController? = null
    private var generation = 0L

    fun load(authMode: String, interrupted: Boolean = false) {
        if (mutableState.value.isBusy) return
        if (!ProviderAuthModes.isOAuth(authMode)) {
            mutableState.value = ProviderLoginState(authMode = authMode)
            return
        }
        runOperation(authMode, ProviderLoginPhase.Loading) { _, _ ->
            val account = withContext(ioDispatcher) { operations.account(providerId, authMode) }
            mutableState.value = mutableState.value.copy(
                account = account,
                message = if (interrupted) AppStrings.get(R.string.provider_i18n_login_interrupted, "Login interrupted, please retry") else null,
            )
        }
    }

    fun login(provider: ProviderSetting) {
        if (mutableState.value.isBusy || provider.id != providerId) return
        runOperation(provider.authMode, ProviderLoginPhase.Saving) { activeController, attempt ->
            withContext(ioDispatcher) { operations.save(provider) }
            activeController.throwIfCancelled()
            mutableState.value = mutableState.value.copy(phase = ProviderLoginPhase.Authorizing)
            val account = withContext(ioDispatcher) {
                operations.login(providerId, provider.authMode, activeController) { prompt ->
                    scope.launch {
                        if (generation == attempt && !activeController.isCancelled &&
                            mutableState.value.phase == ProviderLoginPhase.Authorizing
                        ) {
                            mutableState.value = mutableState.value.copy(prompt = prompt)
                        }
                    }
                }
            }
            mutableState.value = mutableState.value.copy(account = account, prompt = null, phase = ProviderLoginPhase.SyncingModels)
            val count = withContext(ioDispatcher) { operations.syncModels(providerId, provider.authMode) }
            mutableState.value = mutableState.value.copy(
                message = AppStrings.quantity(
                    R.plurals.provider_i18n_login_synced_models,
                    count,
                    "Logged in, %1\$d remote models synced",
                    "Logged in, %1\$d remote models synced",
                    count,
                ),
            )
        }
    }

    fun disconnect() {
        val authMode = mutableState.value.authMode
        if (mutableState.value.isBusy || !ProviderAuthModes.isOAuth(authMode)) return
        runOperation(authMode, ProviderLoginPhase.Disconnecting) { activeController, _ ->
            val revoked = withContext(ioDispatcher) { operations.disconnect(providerId, authMode, activeController) }
            mutableState.value = mutableState.value.copy(
                account = null,
                message = if (revoked == false) {
                    AppStrings.get(R.string.provider_i18n_logout_unconfirmed, "Logged out, remote revocation unconfirmed")
                } else {
                    AppStrings.get(R.string.provider_i18n_logout_done, "Logged out")
                },
            )
        }
    }

    fun cancel() {
        controller?.cancel()
        operation?.cancel()
        mutableState.value = mutableState.value.copy(prompt = null)
    }

    fun close() {
        generation++
        controller?.cancel()
        scope.cancel()
    }

    private fun runOperation(
        authMode: String,
        phase: ProviderLoginPhase,
        action: suspend (AgentRunController, Long) -> Unit,
    ) {
        val attempt = ++generation
        val activeController = AgentRunController()
        controller = activeController
        mutableState.value = mutableState.value.copy(authMode = authMode, phase = phase, prompt = null, message = null)
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action(activeController, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AgentRunCancelledException) {
                // Cancel means the user ended the current authorization; don't show a failure hint.
            } catch (error: Exception) {
                if (generation == attempt && !activeController.isCancelled) {
                    mutableState.value = mutableState.value.copy(
                        message = when (mutableState.value.phase) {
                            ProviderLoginPhase.SyncingModels -> AppStrings.get(
                                R.string.provider_i18n_login_sync_failed,
                                "Logged in, model sync failed, please re-fetch on the models page",
                            )
                            else -> (error as? ProviderAuthException)?.message ?: when (phase) {
                                ProviderLoginPhase.Loading -> AppStrings.get(
                                    R.string.provider_i18n_login_load_failed,
                                    "Cannot read login state, please retry",
                                )
                                ProviderLoginPhase.Disconnecting -> AppStrings.get(
                                    R.string.provider_i18n_logout_failed,
                                    "Logout failed, please retry",
                                )
                                else -> AppStrings.get(
                                    R.string.provider_i18n_login_failed,
                                    "Login failed, please retry",
                                )
                            }
                        },
                    )
                }
            } finally {
                activeController.cancel()
                if (generation == attempt) {
                    controller = null
                    mutableState.value = mutableState.value.copy(phase = ProviderLoginPhase.Idle, prompt = null)
                }
            }
        }
    }
}

internal interface ProviderLoginOperations {
    suspend fun save(provider: ProviderSetting)
    fun account(providerId: String, authMode: String): ProviderAccountSummary?
    fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderAccountSummary
    suspend fun syncModels(providerId: String, authMode: String): Int
    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean?
}

private object RepositoryProviderLoginOperations : ProviderLoginOperations {
    override suspend fun save(provider: ProviderSetting) {
        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.syncToRemotePreferences(AtomApp.serviceInstance)
    }

    override fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        ProviderCredentialRepository.account(providerId, authMode)

    override fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderAccountSummary = ProviderCredentialRepository.login(providerId, authMode, controller, onPrompt)

    override suspend fun syncModels(providerId: String, authMode: String): Int {
        val provider = requireNotNull(ProviderRepository.providerById(providerId)) {
            AppStrings.get(R.string.provider_i18n_connection_missing, "Provider connection does not exist")
        }
        if (provider.authMode != authMode) {
            throw ProviderAuthException(
                "oauth_session_changed",
                AppStrings.get(R.string.provider_i18n_auth_mode_changed, "Provider sign-in method has changed, please re-fetch models"),
            )
        }
        val catalog = RemoteModelFetcher.fetchCatalog(provider).getOrThrow()
        val result = ModelRepository.syncRemoteCatalog(catalog)
        RuntimeConfigRepository.syncToRemotePreferences(AtomApp.serviceInstance)
        return result.fetchedCount
    }

    override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        ProviderCredentialRepository.disconnect(providerId, authMode, controller)
}
