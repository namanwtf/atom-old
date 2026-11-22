package io.github.namanwtf.atom.agent.virtualdisplay

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface
import io.github.namanwtf.atom.agent.accessibility.AccessibilityProtectionProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * In-process temporary registry of Surfaces.
 *
 * When the device has only Root and no system_server hook mounted, the Atom main process places ImageReader.surface
 * into this table under a one-shot random token; a privileged child process (UID 0 / 1000) uses the protected ContentProvider to
 * retrieve the Parcelable Surface and create a trusted VirtualDisplay under system privileges.
 */
internal object VirtualDisplaySurfaceRegistry {
    private data class Entry(
        val surface: Surface,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val TOKEN_TTL_MS = 15_000L

    fun register(surface: Surface): String {
        pruneExpired()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(surface)
        return token
    }

    fun consume(token: String): Surface? {
        pruneExpired()
        val entry = entries.remove(token) ?: return null
        if (System.currentTimeMillis() - entry.createdAtMs > TOKEN_TTL_MS) return null
        return entry.surface.takeIf { it.isValid }
    }

    fun unregister(token: String) {
        entries.remove(token)
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, entry) -> now - entry.createdAtMs > TOKEN_TTL_MS }
    }
}

/**
 * Root-privileged virtual display bridge entry point (launched by app_process).
 *
 * Supports two modes:
 * 1. action="hold_display": obtains the Atom process's Surface via ContentProvider, and creates under system identity
 *    a TRUSTED VirtualDisplay, prints ATOM_VD_READY:<displayId>, then stays alive; it is released automatically when stdin disconnects.
 * 2. action="move_to_main": finds the RootTask for the given displayId or packageName and calls
 *    IActivityTaskManager.moveRootTaskToDisplay to move it to the physical main display (DEFAULT_DISPLAY).
 */
internal object VirtualDisplayCommandMain {
    const val READY_PREFIX = "ATOM_VD_READY:"
    const val RESULT_PREFIX = "ATOM_VD_RESULT:"

    // Hold VirtualDisplay and Surface via static strong references to prevent ART local stack-frame optimization from triggering GC finalize and destroying the virtual display while waiting.
    @Volatile
    private var retainedVirtualDisplay: android.hardware.display.VirtualDisplay? = null

    @Volatile
    private var retainedSurface: Surface? = null

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        try {
            val uid = Process.myUid()
            if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
                println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "ROOT_REQUIRED"))
                exitProcess(1)
            }
            exemptHiddenApis()
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
            val firstLine = reader.readLine()?.trim().orEmpty()
            if (firstLine.isEmpty()) exitProcess(1)
            val request = JSONObject(firstLine)
            when (request.optString("action")) {
                "hold_display" -> holdVirtualDisplay(request, reader)
                "move_to_main" -> {
                    val result = moveTaskToMainDisplay(request)
                    println(RESULT_PREFIX + result.toString())
                    exitProcess(0)
                }
                else -> {
                    println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "UNKNOWN_ACTION"))
                    exitProcess(1)
                }
            }
        } catch (t: Throwable) {
            println(
                RESULT_PREFIX + JSONObject()
                    .put("ok", false)
                    .put("code", "BRIDGE_ERROR")
                    .put("type", t.javaClass.simpleName),
            )
            exitProcess(1)
        }
    }

    private fun holdVirtualDisplay(request: JSONObject, reader: BufferedReader) {
        val userId = request.optInt("user_id", 0)
        val token = request.optString("surface_token")
        val width = request.optInt("width", 1080).coerceIn(320, 4096)
        val height = request.optInt("height", 2400).coerceIn(320, 4096)
        val densityDpi = request.optInt("density_dpi", 420).coerceIn(120, 960)

        val surface = acquireSurfaceFromApp(userId, token)
        if (surface == null || !surface.isValid) {
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "SURFACE_UNAVAILABLE"))
            exitProcess(1)
        }

        // Prefer switching to SYSTEM_UID (1000) to pass DisplayManagerService checks for ADD_TRUSTED_DISPLAY and CAPTURE_VIDEO_OUTPUT
        switchToSystemUidIfPossible()

        val virtualDisplay = createTrustedVirtualDisplay(surface, width, height, densityDpi)
        if (virtualDisplay == null) {
            surface.release()
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "CREATE_DISPLAY_FAILED"))
            exitProcess(1)
        }

        val displayId = virtualDisplay.display?.displayId ?: Display.INVALID_DISPLAY
        if (displayId <= 0) {
            runCatching { virtualDisplay.release() }
            surface.release()
            println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "INVALID_DISPLAY_ID"))
            exitProcess(1)
        }

        retainedVirtualDisplay = virtualDisplay
        retainedSurface = surface
        val displayFlags = virtualDisplay.display.flags
        if (!CanvasRoutingPolicy.hasRequiredDisplayFlags(displayFlags)) {
            releaseRetainedDisplay()
            println(
                RESULT_PREFIX + JSONObject()
                    .put("ok", false)
                    .put("code", "DISPLAY_ISOLATION_UNAVAILABLE")
                    .put("reason", "MISSING_DISPLAY_FLAGS")
                    .put("flags", "0x${Integer.toHexString(displayFlags)}")
                    .put("missing", CanvasRoutingPolicy.missingDisplayFlagsDescription(displayFlags))
                    .put("uid", Process.myUid()),
            )
            exitProcess(1)
        }
        val imePolicyResult = applyDisplayImePolicyHide(displayId)
        if (!imePolicyResult.ok) {
            releaseRetainedDisplay()
            val errorJson = JSONObject()
                .put("ok", false)
                .put("code", "DISPLAY_ISOLATION_UNAVAILABLE")
                .put("reason", imePolicyResult.reason)
                .put("uid", Process.myUid())
            imePolicyResult.actualPolicy?.let { errorJson.put("actual_policy", it) }
            imePolicyResult.errorType?.let { errorJson.put("error_type", it) }
            println(RESULT_PREFIX + errorJson)
            exitProcess(1)
        }

        println("$READY_PREFIX$displayId")
        System.out.flush()

        val mainHandler = android.os.Handler(Looper.getMainLooper())
        Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "release") break
                }
            } catch (_: Throwable) {
                // The host closed the pipe or cancelled the task
            } finally {
                mainHandler.post {
                    releaseRetainedDisplay()
                    exitProcess(0)
                }
            }
        }, "atom-vd-stdin-watcher").apply {
            isDaemon = true
            start()
        }

        try {
            Looper.loop()
        } finally {
            releaseRetainedDisplay()
        }
        exitProcess(0)
    }

    private fun releaseRetainedDisplay() {
        val vd = retainedVirtualDisplay
        val surf = retainedSurface
        retainedVirtualDisplay = null
        retainedSurface = null
        runCatching { vd?.release() }
        runCatching { surf?.release() }
    }

    private fun exemptHiddenApis() {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/",
                "Landroid/hardware/display/",
                "Landroid/app/",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun switchToSystemUidIfPossible() {
        runCatching {
            if (Process.myUid() == Process.ROOT_UID) {
                android.system.Os.setuid(Process.SYSTEM_UID)
            }
        }
    }

    private fun applyDisplayImePolicyHide(displayId: Int): CanvasRoutingPolicy.ImePolicyCheckResult {
        if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            return CanvasRoutingPolicy.ImePolicyCheckResult(ok = false, reason = "INVALID_DISPLAY_ID")
        }
        switchToSystemUidIfPossible()
        return try {
            val wms = resolveWindowManagerService()
            // DisplayContent and mDisplayImePolicyCache may have a brief async readiness window right after the virtual display is created; retry briefly before deciding to fall back to the main display.
            CanvasRoutingPolicy.applyDisplayImePolicyHide(
                wms = wms,
                displayId = displayId,
                retryDelaysMs = longArrayOf(15L, 30L, 50L),
            )
        } catch (exception: Exception) {
            val cause = (exception as? java.lang.reflect.InvocationTargetException)?.cause ?: exception
            CanvasRoutingPolicy.ImePolicyCheckResult(
                ok = false,
                reason = "WMS_LOOKUP_EXCEPTION",
                errorType = cause.javaClass.simpleName,
            )
        }
    }

    private fun resolveWindowManagerService(): Any? {
        // app_process does not go through Zygote bindApplication; on Android 16/17, WindowManagerGlobal.getWindowManagerService()
        // may throw IllegalStateException from reading uninitialized ApplicationSharedMemory; prefer obtaining the Binder proxy directly.
        val directProxy = runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, android.content.Context.WINDOW_SERVICE) as? IBinder ?: return@runCatching null
            val stubClass = Class.forName("android.view.IWindowManager\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
        if (directProxy != null) return directProxy

        ensureApplicationSharedMemoryInitialized()
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        return wmgClass.getMethod("getWindowManagerService").invoke(null)
    }

    private fun ensureApplicationSharedMemoryInitialized() {
        runCatching {
            val cls = Class.forName("com.android.internal.os.ApplicationSharedMemory")
            val alreadyInitialized = runCatching {
                cls.getMethod("getInstance").invoke(null) != null
            }.getOrDefault(false)
            if (!alreadyInitialized) {
                val inst = cls.getMethod("create").invoke(null)
                cls.getMethod("setInstance", cls).invoke(null, inst)
            }
        }
    }

    private fun acquireSurfaceFromApp(userId: Int, surfaceToken: String): Surface? {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getMethod("getService")
            .invoke(null) ?: return null
        val iActivityManager = Class.forName("android.app.IActivityManager")
        val iContentProvider = Class.forName("android.content.IContentProvider")
        val binderToken: IBinder = Binder()
        val holder = iActivityManager.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(
            activityManager,
            AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
            userId,
            binderToken,
            "AtomVirtualDisplay",
        ) ?: return null

        return try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val attribution = android.content.AttributionSource.Builder(Process.myUid())
                .setPackageName("root")
                .build()
            val extras = AccessibilityProtectionProtocol.request().apply {
                putString(AccessibilityProtectionProtocol.EXTRA_SURFACE_TOKEN, surfaceToken)
            }
            val callMethod = iContentProvider.methods.firstOrNull {
                it.name == "call" && it.parameterCount == 5
            } ?: return null
            val reply = callMethod.invoke(
                provider,
                attribution,
                AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
                AccessibilityProtectionProtocol.VIRTUAL_DISPLAY_SURFACE_METHOD,
                null,
                extras,
            ) as? Bundle ?: return null
            reply.classLoader = Surface::class.java.classLoader
            reply.getParcelable(AccessibilityProtectionProtocol.EXTRA_SURFACE, Surface::class.java)
        } finally {
            runCatching {
                iActivityManager.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(
                    activityManager,
                    AccessibilityProtectionProtocol.HEALTH_AUTHORITY,
                    binderToken,
                    userId,
                )
            }
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun createTrustedVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): android.hardware.display.VirtualDisplay? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
        val config = android.hardware.display.VirtualDisplayConfig.Builder(
            CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
        )
            .setSurface(surface)
            .setFlags(CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
            .build()
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
        val createMethod = dmgClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" &&
                it.parameterCount >= 3 &&
                it.parameterTypes[0] == android.content.Context::class.java &&
                it.parameterTypes[2] == android.hardware.display.VirtualDisplayConfig::class.java
        }
        if (createMethod != null) {
            val args = arrayOfNulls<Any?>(createMethod.parameterCount)
            args[0] = systemContext
            args[1] = null // MediaProjection
            args[2] = config
            createMethod.invoke(dmg, *args) as? android.hardware.display.VirtualDisplay
        } else {
            val dm = systemContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            dm?.createVirtualDisplay(config)
        }
    }.getOrNull()

    private fun moveTaskToMainDisplay(request: JSONObject): JSONObject {
        val displayId = request.optInt("display_id", Display.INVALID_DISPLAY)
        val targetPackage = request.optString("package_name").trim()
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val service = atmClass.getMethod("getService").invoke(null)
            ?: return JSONObject().put("ok", false).put("code", "ATM_UNAVAILABLE")
        val serviceClass = service.javaClass

        val getTasks = serviceClass.methods.firstOrNull {
            it.name == "getTasks" && it.parameterCount >= 1
        }
        val rawTasks = runCatching {
            when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 50)
                3 -> getTasks.invoke(service, 50, false, false)
                4 -> getTasks.invoke(service, 50, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*>
        }.getOrNull().orEmpty()

        var matchedTaskId = -1
        for (item in rawTasks) {
            val taskInfo = item as? android.app.TaskInfo ?: continue
            val taskDisplayId = runCatching {
                android.app.TaskInfo::class.java.getField("displayId").getInt(taskInfo)
            }.getOrDefault(Display.INVALID_DISPLAY)
            val topPkg = taskInfo.topActivity?.packageName
                ?: taskInfo.baseActivity?.packageName
                ?: taskInfo.baseIntent.component?.packageName
                ?: taskInfo.baseIntent.`package`
            val displayMatches = displayId > 0 && taskDisplayId == displayId
            val pkgMatches = targetPackage.isNotEmpty() && topPkg == targetPackage
            if (displayMatches || pkgMatches) {
                matchedTaskId = taskInfo.taskId
                if (displayMatches && pkgMatches) break
            }
        }

        if (matchedTaskId <= 0) {
            return JSONObject().put("ok", false).put("code", "TASK_NOT_FOUND")
        }

        val moveMethod = serviceClass.methods.firstOrNull {
            it.name == "moveRootTaskToDisplay" && it.parameterCount == 2
        } ?: return JSONObject().put("ok", false).put("code", "MOVE_METHOD_NOT_FOUND")

        moveMethod.invoke(service, matchedTaskId, Display.DEFAULT_DISPLAY)
        runCatching {
            serviceClass.methods.firstOrNull {
                it.name == "setFocusedRootTask" && it.parameterCount == 1
            }?.invoke(service, matchedTaskId)
        }
        return JSONObject()
            .put("ok", true)
            .put("task_id", matchedTaskId)
            .put("target_display_id", Display.DEFAULT_DISPLAY)
    }
}
