package io.github.namanwtf.atom.hook.system

import io.github.namanwtf.atom.core.HookSupport
import io.github.namanwtf.atom.core.HookInstallation
import io.github.namanwtf.atom.core.HookRegistrar
import io.github.namanwtf.atom.core.ModuleConfig
import io.github.namanwtf.atom.core.ModuleLogger

import io.github.namanwtf.atom.config.Prefs
import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.libxposed.api.XposedModule

internal object ContextualSearchHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ContextualSearch")
        return hooks.install {
            // The service may be disabled by the ROM's resource configuration; try the native launch first, then keep the startup tail bootstrap to complete it.
            hookContextualSearchConfig(hooks, classLoader)
            hookContextualSearchBootstrap(module, hooks, classLoader)
            hookContextualSearchPackage(hooks, classLoader)
            hookContextualSearchPermission(hooks, classLoader)
        }
    }

    private fun hookContextualSearchConfig(hooks: HookRegistrar, classLoader: ClassLoader) {
        val server = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val resources = HookSupport.findClassOrNull(classLoader, "com.android.internal.R\$string")
        val resourceId = resources?.let {
            HookSupport.findField(it, "config_defaultContextualSearchPackageName")?.getInt(null)
        }
        val method = server?.let {
            HookSupport.findMethod(it, "deviceHasConfigString", Context::class.java, Int::class.javaPrimitiveType!!)
        }
        if (resourceId == null || resourceId == 0 || method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.skipped("system.contextual-search-config", "SystemServer.deviceHasConfigString", "no Contextual Search resource launch condition provided, keeping the tail bootstrap")
            return
        }
        hooks.intercept("system.contextual-search-config", method, "SystemServer.deviceHasConfigString") { chain ->
            if (chain.getArg(1) == resourceId) true else chain.proceed()
        }
    }

    private fun hookContextualSearchBootstrap(
        module: XposedModule,
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        // Reboot logs on this device from 2026-03-26 prove that what actually stays stable is the tail bootstrap at the end of startOtherServices.
        // So we hold the system-service startup tail directly here, no longer treating deviceHasConfigString as the only effective point.
        val systemServerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.SYSTEM_SERVER_CLASS)
        val timingsClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS)
        val startOtherServicesMethod = if (systemServerClass != null && timingsClass != null) {
            HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
        } else {
            null
        }
        if (startOtherServicesMethod == null) {
            hooks.missing(
                id = "system.contextual-search-bootstrap",
                description = "SystemServer.startOtherServices",
                detail = "SystemServer.startOtherServices(TimingsTraceAndSlog) not found"
            )
            return
        }

        HookSupport.deoptimize(
            module,
            logger,
            startOtherServicesMethod,
            "SystemServer.startOtherServices(TimingsTraceAndSlog)"
        )
        hooks.intercept(
            id = "system.contextual-search-bootstrap",
            executable = startOtherServicesMethod,
            description = "SystemServer.startOtherServices"
        ) { chain ->
            val result = chain.proceed()
            ensureContextualSearchService(module, logger, classLoader, chain.getThisObject(), "startOtherServices")
            result
        }
    }

    private fun hookContextualSearchPackage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "getContextualSearchPackageName") }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-package",
                description = "ContextualSearchManagerService.getContextualSearchPackageName",
                detail = "ContextualSearchManagerService.getContextualSearchPackageName() not found"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-package",
            executable = method,
            description = "ContextualSearchManagerService.getContextualSearchPackageName"
        ) { ModuleConfig.GOOGLE_PACKAGE }
    }

    private fun hookContextualSearchPermission(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        val method = serviceClass?.let { HookSupport.findMethod(it, "enforcePermission", String::class.java) }
        if (method == null) {
            hooks.missing(
                id = "system.contextual-search-permission",
                description = "ContextualSearchManagerService.enforcePermission",
                detail = "ContextualSearchManagerService.enforcePermission(String) not found"
            )
            return
        }

        hooks.intercept(
            id = "system.contextual-search-permission",
            executable = method,
            description = "ContextualSearchManagerService.enforcePermission"
        ) { chain ->
            val functionName = chain.getArg(0) as? String
            if (functionName == "startContextualSearch" && isAllowedContextualSearchUid(chain.getThisObject())) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun isAllowedContextualSearchUid(serviceInstance: Any): Boolean {
        val context = HookSupport.invokeNoArgs(serviceInstance, "getContext") as? Context
            ?: HookSupport.getFieldValue(serviceInstance, "mContext") as? Context
            ?: return false
        // IContextualSearchManager is a oneway AIDL, so the caller PID is fixed and unusable; the Android permission subject itself is the UID.
        // So here we can only authorize by UID, and the result of getPackagesForUid represents the whole shared-UID security boundary.
        val callingUid = Binder.getCallingUid()
        val packages = try {
            context.packageManager.getPackagesForUid(callingUid)
        } catch (_: Exception) {
            null
        } ?: return false
        return packages.contains(ModuleConfig.SYSTEM_UI_PACKAGE) ||
            packages.contains(ModuleConfig.COLOR_DIRECT_PACKAGE) ||
            ContextualSearchCallerPolicy.allowsHyperOsCaller(
                context,
                packages,
                gestureEnabled = Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH),
            )
    }

    private fun ensureContextualSearchService(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        systemServerInstance: Any,
        source: String
    ) {
        if (isContextualSearchServiceAlive()) {
            logger.debug { "$source: contextual_search service already exists" }
            return
        }

        val systemServiceManager = HookSupport.getFieldValue(systemServerInstance, "mSystemServiceManager")
        if (systemServiceManager == null) {
            logger.warn("$source: mSystemServiceManager is null, cannot bootstrap contextual_search")
            return
        }

        val serviceClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CONTEXTUAL_SEARCH_CLASS)
        if (serviceClass == null) {
            logger.warn("$source: ContextualSearchManagerService class not found, cannot bootstrap")
            return
        }

        val startServiceMethod = HookSupport.findMethod(
            systemServiceManager.javaClass,
            "startService",
            Class::class.java
        )
        if (startServiceMethod == null) {
            logger.warn("$source: SystemServiceManager.startService(Class) not found")
            return
        }

        try {
            module.getInvoker(startServiceMethod).invoke(systemServiceManager, serviceClass)
            if (isContextualSearchServiceAlive()) {
                logger.debug { "$source: bootstrapped ContextualSearchManagerService" }
            } else {
                logger.warn("$source: startService(Class) invoked but contextual_search still unavailable")
            }
        } catch (exception: Exception) {
            // XposedFrameworkError is an Error and must be rethrown for the framework to handle.
            logger.error("$source: failed to bootstrap ContextualSearchManagerService", exception)
        }
    }

    private fun isContextualSearchServiceAlive(): Boolean =
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
            binder?.isBinderAlive == true
        }.getOrDefault(false)
}
