package io.github.namanwtf.atom

import io.github.namanwtf.atom.agent.voice.SpeechOssUpload
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.github.namanwtf.atom.agent.skill.SkillRuntime
import io.github.namanwtf.atom.agent.browser.BrowserSessionRegistry
import io.github.namanwtf.atom.agent.device.RootAccess
import io.github.namanwtf.atom.agent.terminal.TerminalRuntime
import io.github.namanwtf.atom.config.Prefs
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.core.safeLogType
import io.github.namanwtf.atom.data.datastore.SettingsDataStore
import io.github.namanwtf.atom.data.repository.AgentMemoryRepository
import io.github.namanwtf.atom.data.repository.AppearanceSettingsRepository
import io.github.namanwtf.atom.data.repository.McpServerRepository
import io.github.namanwtf.atom.data.repository.LinuxEnvironmentSettingsRepository
import io.github.namanwtf.atom.data.repository.ProviderRepository
import io.github.namanwtf.atom.data.repository.ScheduledTaskRepository
import io.github.namanwtf.atom.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application of the module UI process.
 *
 * Registers a [XposedServiceHelper] listener at process startup; the framework pushes the binder via XposedProvider,
 * after which the UI can obtain [XposedService] to write RemotePreferences, synced cross-process to each hook process.
 *
 * The UI side writes RemotePreferences via [XposedService].
 */
class AtomApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)
        Prefs.initLocal(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@AtomApp).resume() }
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        ScheduledTaskRepository.init(this)
        applicationScope.launch {
            runCatching {
                ScheduledTaskRepository.reconcileAfterRestartOrClockChange(
                    recomputeAllSchedules = false,
                    rescheduleAlarm = true,
                    context = this@AtomApp,
                )
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Scheduled task reconcile failed: type=${throwable.safeLogType()}"
                )
            }
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@AtomApp).retryPending(this@AtomApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Atom speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@AtomApp)
            runCatching {
                SkillRuntime.createIndexService(this@AtomApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // Clear and dispatch null only when the currently held service dies;
        // with multiple frameworks the one that died may be an already-replaced old instance, so the UI need not be affected.
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
