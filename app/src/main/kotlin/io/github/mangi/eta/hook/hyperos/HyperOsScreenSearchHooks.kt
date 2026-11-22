package io.github.mangi.eta.hook.hyperos

import android.app.Service
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

internal object HyperOsScreenSearchHooks {
    private const val SERVICE_CLASS = "com.xiaomi.voiceassistant.VoiceService"

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsScreenSearch")
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            val method = serviceClass?.let {
                HookSupport.findMethod(
                    it,
                    "onStartCommand",
                    Intent::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
            }
            if (serviceClass == null || !Service::class.java.isAssignableFrom(serviceClass) ||
                method == null || method.declaringClass != serviceClass ||
                method.returnType != Int::class.javaPrimitiveType
            ) {
                missing(
                    "hyperos.screen-search-service",
                    "VoiceService.onStartCommand",
                    "HyperOS: no matching Xiaoai screen-recognition service entry found",
                )
                return@install
            }
            intercept(
                "hyperos.screen-search-service",
                method,
                "VoiceService.onStartCommand",
            ) { chain ->
                if (!HyperOsSearchTrigger.isEnabled()) return@intercept chain.proceed()
                val intent = chain.getArg(0) as? Intent
                val matches = try {
                    HyperOsScreenSearchRequest.matches(intent)
                } catch (exception: Exception) {
                    hooks.logger.warnThrottled("hyperos_screen_search_invalid_intent") {
                        "HyperOS: cannot read screen-recognition request, keeping original logic, type=${exception.safeLogType()}"
                    }
                    false
                }
                if (!matches) return@intercept chain.proceed()
                val service = chain.getThisObject() as Service
                if (!HyperOsSearchTrigger.trigger(service, hooks.logger)) {
                    hooks.logger.warnThrottled("hyperos_screen_search_dispatch_failed") {
                        "HyperOS: navbar screen-recognition search request not sent, falling back to Xiaoai screen recognition"
                    }
                    return@intercept chain.proceed()
                }
                finishHandledStart(service, chain.getArg(2) as Int, hooks.logger)
                hooks.logger.debug { "HyperOS: navbar screen-recognition request handed to contextual_search" }
                Service.START_NOT_STICKY
            }
        }
    }

    private fun finishHandledStart(service: Service, startId: Int, logger: ModuleLogger) {
        try {
            // Only clean up the service when this is still the latest start; do not use an unconditional stopSelf that would drop subsequent requests.
            if (service.stopSelfResult(startId)) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        } catch (exception: Exception) {
            // The search has already been dispatched; even if cleanup fails, do not re-run the original screen-recognition entry and cause a double invocation.
            logger.warnThrottled("hyperos_screen_search_cleanup_failed") {
                "HyperOS: screen-recognition service startup cleanup failed, type=${exception.safeLogType()}"
            }
        }
    }
}
