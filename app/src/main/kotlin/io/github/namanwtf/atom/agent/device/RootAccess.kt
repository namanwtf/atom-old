package io.github.namanwtf.atom.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.namanwtf.atom.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Root probing is owned by the App process; reading the capability never executes su or pops up a grant prompt. */
internal object RootAccess {
    private const val PREFERENCES = "atom_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private const val RETRY_COOLDOWN_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null
    @Volatile
    private var lastAutoRetryAtElapsedMs: Long = 0L

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** Request once the first time su is found; after a denial only an explicit request asks again. */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /**
     * Tool or Runtime startup waits for the probe to finish; if access was previously granted
     * (last_granted=true) but this probe is in a temporary failure state (for example, KernelSU's
     * temporary Root is not ready yet or timed out after a reboot), re-check automatically once
     * after a cooldown so the whole process lifetime does not lose access.
     */
    fun awaitOrRetryIfPreviouslyGranted(context: Context, maxWaitMs: Long = 3_000L) {
        if (isGranted) return
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        val activeJob = synchronized(this) {
            val currentJob = probeJob?.takeIf { it.isActive }
            if (currentJob != null) {
                currentJob
            } else {
                val status = mutableState.value.status
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                val now = SystemClock.elapsedRealtime()
                if (shouldAutoRetryOnUse(status, wasGranted, now - lastAutoRetryAtElapsedMs)) {
                    lastAutoRetryAtElapsedMs = now
                    startProbe(context, explicit = false)
                } else {
                    null
                }
            }
        }
        if (activeJob != null) {
            runBlocking {
                withTimeoutOrNull(maxWaitMs.coerceAtLeast(200L)) {
                    activeJob.join()
                }
            }
        }
    }

    /** Called only after the executor confirms an su denial; an ordinary command failure does not mean the grant was revoked. */
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        mutableState.value = mutableState.value.copy(isChecking = true)
        scope.launch {
            try {
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                val present = suExists()
                // If access was previously granted (wasGranted=true), allow attempting to run su even when KernelSU hides the su file or it is absent from PATH.
                if (!present && !explicit && !wasGranted) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = present,
                    )
                    return@launch
                }
                // Remember the attempt first, so that if the process exits during the grant prompt it does not bother the user again on the next launch.
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = present)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val (status, nextLastGranted) = classifyRootProbeOutcome(
                    wasGranted = wasGranted,
                    result = result,
                )
                if (nextLastGranted != wasGranted) {
                    prefs.edit().putBoolean(LAST_GRANTED, nextLastGranted).apply()
                }
                if (status != RootAccessStatus.GRANTED) {
                    AndroidAgentLogger.warn(
                        "Root access probe outcome=${status.name.lowercase()} " +
                            "suPresent=$present exit=${result.exitCode} err=${result.errorCode.ifBlank { "none" }}",
                    )
                }
                mutableState.value = RootAccessState(
                    status = status,
                    suPresent = present || status == RootAccessStatus.GRANTED,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    private fun suExists(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .any { path -> File(path, "su").let { it.isFile && it.canExecute() } }
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted

internal fun shouldAutoRetryOnUse(
    status: RootAccessStatus,
    wasGranted: Boolean,
    elapsedSinceLastRetryMs: Long,
    cooldownMs: Long = 5_000L,
): Boolean = wasGranted &&
    status != RootAccessStatus.GRANTED &&
    status != RootAccessStatus.DENIED &&
    elapsedSinceLastRetryMs >= cooldownMs

internal fun classifyRootProbeOutcome(
    wasGranted: Boolean,
    result: BoundedRootCommandExecutor.Result,
): Pair<RootAccessStatus, Boolean> {
    val granted = result.ok && result.stdout.trim() == "0"
    if (granted) return RootAccessStatus.GRANTED to true
    val status = when {
        result.errorCode == "ROOT_UNAVAILABLE" -> RootAccessStatus.UNAVAILABLE
        result.timedOut -> RootAccessStatus.TIMED_OUT
        result.errorCode == "ROOT_INPUT_FAILED" || result.errorCode == "ROOT_OUTPUT_FAILED" -> RootAccessStatus.UNAVAILABLE
        else -> RootAccessStatus.DENIED
    }
    // Temporary unavailability or timeout does not erase the historical grant; clear last_granted only when a denial is confirmed.
    val keepLastGranted = wasGranted && status != RootAccessStatus.DENIED
    return status to keepLastGranted
}
