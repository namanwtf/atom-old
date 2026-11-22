package io.github.namanwtf.atom.hook.colordirect

import io.github.namanwtf.atom.core.HookSupport
import io.github.namanwtf.atom.core.DexKitTargets
import io.github.namanwtf.atom.core.HookInstallation
import io.github.namanwtf.atom.core.HookRegistrar
import io.github.namanwtf.atom.core.ModuleConfig
import io.github.namanwtf.atom.core.ModuleLogger
import io.github.namanwtf.atom.hook.system.CircleToSearchInvoker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Parcelable
import android.os.SystemClock
import io.github.namanwtf.atom.config.Prefs
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

internal object ColorDirectHooks {
    private const val SOURCE = "ColorDirectActivity"
    private const val DIRECT_EXT_FINGER_TRIGGER = "fingerTrigger"
    private const val DIRECT_EXT_TOUCH_INFO = "touchInfo"
    private const val DIRECT_EXT_FINGER_COUNT = "fingerCount"

    @Volatile
    private var lastHandledUptime = 0L

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorDirect")
        return hooks.install {
            hookCollectInfoActivity(
                hooks, classLoader, applicationInfo, module.moduleApplicationInfo,
            )
        }
    }

    private fun hookCollectInfoActivity(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
        moduleApplicationInfo: ApplicationInfo,
    ) {
        val logger = hooks.logger
        val activityClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS
        )
        if (activityClass == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent handling entry",
                detail = "CollectInfoActivity not found, cannot take over the two-finger screen-recognition Activity entry"
            )
            return
        }
        val startInfoClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_START_INFO_CLASS
        )

        val method = DexKitTargets(
            apkPath = applicationInfo.sourceDir,
            classLoader = classLoader,
            logger = logger,
            cacheDirectory = applicationInfo.dataDir?.let { File(it, "cache/atom-dexkit") },
            moduleNativeLibraryDirectory = moduleApplicationInfo.nativeLibraryDir,
            moduleApkPath = moduleApplicationInfo.sourceDir,
        ).use { targets ->
            ColorDirectTargets.findCollectIntent(targets, activityClass)
        }
        if (method == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent handling entry",
                detail = "Could not uniquely locate the CollectInfoActivity Intent handler, keeping system two-finger screen recognition"
            )
            return
        }

        hooks.intercept(
            id = "colordirect.collect-info",
            executable = method,
            description = "CollectInfoActivity Intent handling entry"
        ) { chain ->
            // If the toggle is off, fall back to the original two-finger screen-recognition logic.
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollectIntent(intent, startInfoClass)) {
                return@intercept chain.proceed()
            }

            if (tryStartCircleToSearch(activity, logger)) {
                finishColorDirectActivity(activity)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun tryStartCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$SOURCE: hit dedup window, swallowing duplicate two-finger screen recognition" }
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "fall back to Breeno two-finger screen recognition")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE two-finger screen recognition")) {
            return false
        }

        lastHandledUptime = now
        logger.debug { "$SOURCE: hit two-finger screen recognition, forwarded to Circle to Search" }
        return true
    }

    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun isDoubleFingerCollectIntent(intent: Intent?, startInfoClass: Class<*>?): Boolean {
        val directExt = resolveDirectExt(intent, startInfoClass) ?: return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(DIRECT_EXT_FINGER_TRIGGER, false) &&
                json.optJSONObject(DIRECT_EXT_TOUCH_INFO)
                    ?.optInt(DIRECT_EXT_FINGER_COUNT, 0) == ModuleConfig.COLOR_DIRECT_DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(ModuleConfig.COLOR_DIRECT_EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typedStartInfoClass = startInfoClass as Class<Parcelable>
            intent.getParcelableExtra(
                ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO,
                typedStartInfoClass
            )?.let { return it }
        }

        return runCatching {
            intent.extras?.get(ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO)
        }.getOrNull()
    }
}
