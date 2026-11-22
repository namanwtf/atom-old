package io.github.mangi.eta.core

import android.os.Build
import android.os.Process
import java.io.File

/** The native library must be loaded by the module's ClassLoader; the host only provides a private cache directory. */
internal object DexKitNativeLibrary {
    private var loaded = false

    @Synchronized
    fun load(moduleApkPath: String, nativeDirectory: String?, cacheDirectory: File, logger: AgentLogger) {
        if (loaded) return
        try {
            System.loadLibrary("dexkit")
            loaded = true
            return
        } catch (error: UnsatisfiedLinkError) {
            logger.debug { "DexKit standard loading unavailable: reason=${nativeLoadFailureReason(error)}" }
        }
        if (!nativeDirectory.isNullOrBlank()) {
            try {
                System.load(File(nativeDirectory, "libdexkit.so").absolutePath)
                loaded = true
                return
            } catch (error: UnsatisfiedLinkError) {
                logger.debug { "DexKit install directory loading unavailable: reason=${nativeLoadFailureReason(error)}" }
            }
        }
        // Compressed APK entries can't be dlopen'd directly; the install directory provided by the framework may also be unusable for the current process.
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val library = DexKitNativeLibraryFile.extract(moduleApkPath, cacheDirectory, abis.toList())
        System.load(library.absolutePath)
        loaded = true
        logger.info("DexKit native library loaded from module APK")
    }
}

/** Logs only a fixed error category, avoiding writing file paths from linker messages into the log. */
internal fun nativeLoadFailureReason(error: UnsatisfiedLinkError): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        "namespace" in message -> "namespace"
        "permission denied" in message -> "permission_denied"
        "already loaded" in message -> "classloader_conflict"
        "no implementation found" in message -> "jni_binding"
        "not found" in message || "couldn't find" in message || "no such file" in message -> "not_found"
        "elf" in message || "32-bit" in message || "64-bit" in message -> "abi_mismatch"
        else -> "linker_error"
    }
}
