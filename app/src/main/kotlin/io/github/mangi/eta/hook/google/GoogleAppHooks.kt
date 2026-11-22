package io.github.mangi.eta.hook.google

import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.lang.reflect.Field

internal object GoogleAppHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleApp")
        val logger = hooks.logger
        return hooks.install {
            // Device spoofing: masquerade as a Samsung S24 Ultra inside the Google process to unlock the Circle to Search capability.
            // The Build fields are a one-time side effect written at startup, and are always applied as a low-level dependency of Circle to Search.
            setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
            setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
            setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
            setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
            setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)
        }
    }

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String,
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.warn("GSA: Build.$fieldName not found, type=${throwable.safeLogType()}")
            return
        }

        try {
            try {
                field.set(null, value)
            } catch (_: IllegalAccessException) {
                // Android 17 forbids reflecting writes to these static final fields; the static-field API lives on the internal Unsafe.
                val unsafeClass = Class.forName("jdk.internal.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }.get(null)
                val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                    .invoke(theUnsafe, field)
                val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                    .invoke(theUnsafe, field) as Long
                unsafeClass.getDeclaredMethod(
                    "putReferenceVolatile",
                    Any::class.java,
                    Long::class.javaPrimitiveType!!,
                    Any::class.java,
                ).invoke(theUnsafe, base, offset, value)
            }
            check(field.get(null) == value) { "build_field_write_not_applied" }
        } catch (exception: Exception) {
            logger.warn("GSA: failed to modify Build.$fieldName, type=${exception.safeLogType()}")
        }
    }
}
