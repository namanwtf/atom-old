package io.github.namanwtf.atom.hook.system

import android.content.Context
import android.os.Handler
import io.github.namanwtf.atom.core.HookInstallation
import io.github.namanwtf.atom.core.HookRegistrar
import io.github.namanwtf.atom.core.HookSupport
import io.github.namanwtf.atom.core.ModuleConfig
import io.github.namanwtf.atom.core.ModuleLogger
import io.github.libxposed.api.XposedModule

/**
 * Attach accessibility protection after SystemServer finishes starting the other system services.
 *
 * The current ColorOS 16 target has been verified by the contextual_search startup chain for
 * the class name, signature, and invocation timing of SystemServer.startOtherServices(TimingsTraceAndSlog).
 */
internal object AccessibilityProtectionHooks {
    @Volatile
    private var enforcer: AccessibilityServiceEnforcer? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "AccessibilityProtection")
        val logger = hooks.logger
        return hooks.install {
            val systemServerClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.SYSTEM_SERVER_CLASS,
            )
            val timingsClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS,
            )
            val startOtherServices = if (systemServerClass != null && timingsClass != null) {
                HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
            } else {
                null
            }
            if (startOtherServices == null) {
                hooks.missing(
                    id = "system.accessibility-protection",
                    description = "SystemServer.startOtherServices",
                    detail = "SystemServer.startOtherServices(TimingsTraceAndSlog) not found",
                )
                return@install
            }

            hooks.intercept(
                id = "system.accessibility-protection",
                executable = startOtherServices,
                description = "SystemServer.startOtherServices",
            ) { chain ->
                val result = chain.proceed()
                startEnforcer(
                    systemServer = chain.getThisObject(),
                    classLoader = classLoader,
                    logger = logger,
                )
                result
            }
        }
    }

    @Synchronized
    private fun startEnforcer(
        systemServer: Any,
        classLoader: ClassLoader,
        logger: ModuleLogger,
    ) {
        if (enforcer != null) return
        val context = SystemServerContextResolver.resolve(systemServer)
        if (context == null) {
            logger.warn("SystemServer started, but system context unavailable")
            return
        }
        val handler = resolveSystemBackgroundHandler(classLoader)
        if (handler == null) {
            logger.warn("cannot get Android BackgroundThread, skipping accessibility protection")
            return
        }
        AccessibilityServiceEnforcer(
            handler = handler,
            logger = logger,
        ).also {
            enforcer = it
            it.start(context)
        }
    }

    /**
     * com.android.internal.os.BackgroundThread in the Android 37 source is a per-process shared thread;
     * reusing it moves component validation and Settings I/O off the system_server main thread without creating a module thread.
     */
    private fun resolveSystemBackgroundHandler(classLoader: ClassLoader): Handler? =
        runCatching {
            val backgroundThreadClass = HookSupport.findClassOrNull(
                classLoader,
                "com.android.internal.os.BackgroundThread",
            ) ?: return@runCatching null
            val getHandler = HookSupport.findMethod(backgroundThreadClass, "getHandler")
                ?: return@runCatching null
            getHandler.invoke(null) as? Handler
        }.getOrNull()
}

internal object SystemServerContextResolver {
    private val contextFieldNames = arrayOf("mSystemContext", "mContext")
    private val contextMethodNames = arrayOf("getSystemContext", "getContext")

    fun resolve(owner: Any?): Context? {
        contextFromOwner(owner)?.let { return it }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
                ?: return@runCatching null
            activityThreadClass
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(currentThread) as? Context
        }.getOrNull()
    }

    private fun contextFromOwner(owner: Any?): Context? {
        if (owner == null) return null
        contextFieldNames.forEach { name ->
            findField(owner.javaClass, name)?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(owner) as? Context
                }.getOrNull()?.let { return it }
            }
        }
        contextMethodNames.forEach { name ->
            findMethod(owner.javaClass, name)?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(owner) as? Context
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = candidate.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredMethod(name) }.getOrNull()?.let { return it }
            current = candidate.superclass
        }
        return null
    }
}
