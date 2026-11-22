package io.github.namanwtf.atom.hook.hyperos

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import io.github.namanwtf.atom.config.PowerAssistantTarget
import io.github.namanwtf.atom.config.Prefs
import io.github.namanwtf.atom.core.HookInstallation
import io.github.namanwtf.atom.core.HookRegistrar
import io.github.namanwtf.atom.core.HookSupport
import io.github.namanwtf.atom.core.ModuleLogger
import io.github.namanwtf.atom.hook.system.AssistantManager

internal object HyperOsPowerHooks {
    private val dispatchGate = HyperOsPowerDispatchGate()
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsPower")
        return hooks.install {
            val type = HookSupport.findClassOrNull(loader, "com.miui.server.input.util.ShortCutActionsUtils")
            if (type == null) {
                skipped("hyperos.power-shortcut", "ShortCutActionsUtils", "current system does not provide the HyperOS shortcut-action dispatcher")
                return@install
            }
            val contextField = HookSupport.findField(type, "mContext")
            if (contextField == null || !Context::class.java.isAssignableFrom(contextField.type)) {
                missing("hyperos.power-shortcut", "ShortCutActionsUtils.mContext", "HyperOS: shortcut-action dispatcher lacks Context")
                return@install
            }
            val parameters = arrayOf(String::class.java, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType!!)
            for (signature in listOf(parameters, parameters + String::class.java)) {
                val method = HookSupport.findMethod(type, "triggerFunction", *signature)
                val id = "hyperos.power-shortcut-${signature.size}"
                if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
                    missing(id, "ShortCutActionsUtils.triggerFunction/${signature.size}", "HyperOS: shortcut-action dispatch signature ${signature.size} mismatch")
                    continue
                }
                intercept(id, method, "ShortCutActionsUtils.triggerFunction/${signature.size}") { chain ->
                    // Match both the function and the source, so we don't turn the power menu, SOS, or other shortcut actions into the assistant.
                    val function = chain.getArg(0) as? String
                    val source = chain.getArg(1) as? String
                    if (!HyperOsPowerPolicy.isAssistantShortcut(function, source)) {
                        return@intercept chain.proceed()
                    }
                    val target = Prefs.powerAssistantTarget()
                    val context = contextField.get(chain.getThisObject()) as? Context
                    if (target == PowerAssistantTarget.OEM || context == null) return@intercept chain.proceed()
                    val now = SystemClock.uptimeMillis()
                    if (dispatchGate.shouldSuppressDuplicate(now)) {
                        // HyperOS dispatches multiple triggers (imperceptible, long_press, etc.) for a single press; consume them directly when the window is hit,
                        // avoiding repeated session launches and preventing later paths from falling back to the system native assistant.
                        hooks.logger.debug { "HyperOsPower: hit dedup window, swallowing duplicate power-key assistant trigger ($source)" }
                        return@intercept true
                    }
                    if (AssistantManager.showAssistantSession(context, target, hooks.logger, "HyperOsPower", logFailures = true)) {
                        dispatchGate.markLaunched(SystemClock.uptimeMillis())
                        true
                    } else {
                        if (Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
                            AssistantManager.scheduleAssistantRecovery(
                                context = context,
                                logger = hooks.logger,
                                handler = mainHandler,
                                forceRefresh = true,
                            )
                        }
                        chain.proceed()
                    }
                }
            }
        }
    }
}
