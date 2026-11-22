package io.github.mangi.eta.core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method

/** 单次 Hook 安装共享 APK 解析资源；查询语义变化时，调用方必须更新 key 的版本。 */
internal class DexKitTargets(
    private val apkPath: String,
    private val classLoader: ClassLoader,
    private val logger: AgentLogger,
    private val cacheDirectory: File? = null,
    private val moduleNativeLibraryDirectory: String? = null,
    private val moduleApkPath: String,
) : Closeable {
    private var bridge: DexKitBridge? = null
    private var bridgeUnavailable = false
    private var closed = false
    private val resolvedMethods = mutableMapOf<String, Method?>()
    private val cache = cacheDirectory?.let { DexKitTargetCache(apkPath, it, logger) }

    fun findMethod(
        key: String,
        validate: (Method) -> Boolean,
        query: DexKitBridge.() -> List<MethodData>,
    ): Method? {
        check(!closed) { "DexKit target resolver is closed" }
        require(KEY_PATTERN.matches(key)) { "DexKit target identity is invalid" }
        if (resolvedMethods.containsKey(key)) return resolvedMethods[key]
        val cached = readCachedMethod(key, validate)
        if (cached != null) {
            resolvedMethods[key] = cached
            logger.debug { "DexKit target cache hit: $key" }
            return cached
        }

        val result = try {
            val activeBridge = getBridge() ?: return null
            val matches = query(activeBridge).distinctBy { it.descriptor }
            val match = matches.singleOrNull()
            if (match == null) {
                logger.warn("DexKit did not find a unique target: key=$key, matches=${matches.size}")
                null
            } else {
                val method = match.getMethodInstance(classLoader)
                if (!validate(method)) {
                    logger.warn("DexKit target signature verification failed: key=$key")
                    null
                } else {
                    cache?.write(key, match.descriptor)
                    logger.debug { "DexKit located target: $key" }
                    method
                }
            }
        } catch (exception: Exception) {
            logger.warn("DexKit target query failed: key=$key, type=${exception.safeLogType()}")
            null
        } catch (error: LinkageError) {
            logger.warn("DexKit target link failed: key=$key, type=${error.safeLogType()}")
            null
        }
        resolvedMethods[key] = result
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeBridge = bridge
        bridge = null
        resolvedMethods.clear()
        activeBridge?.close()
    }

    private fun getBridge(): DexKitBridge? {
        bridge?.let { return it }
        if (bridgeUnavailable) return null
        bridgeUnavailable = true
        try {
            DexKitNativeLibrary.load(
                moduleApkPath,
                moduleNativeLibraryDirectory,
                requireNotNull(cacheDirectory) { "DexKit native library cache directory is unavailable" },
                logger,
            )
        } catch (error: UnsatisfiedLinkError) {
            logger.warn("DexKit native library load failed: reason=${nativeLoadFailureReason(error)}")
            return null
        } catch (exception: Exception) {
            logger.warn("DexKit native library preparation failed: type=${exception.safeLogType()}")
            return null
        }
        try {
            val created = DexKitBridge.create(apkPath)
            if (!created.isValid) {
                created.close()
                logger.warn("DexKit cannot parse target APK")
                return null
            }
            bridge = created
            return created
        } catch (exception: Exception) {
            logger.warn("DexKit initialization failed: type=${exception.safeLogType()}")
        } catch (error: LinkageError) {
            logger.warn("DexKit JNI initialization failed: type=${error.safeLogType()}")
        }
        return null
    }

    private fun readCachedMethod(key: String, validate: (Method) -> Boolean): Method? {
        val descriptor = cache?.read(key) ?: return null
        return try {
            DexMethod(descriptor).getMethodInstance(classLoader).takeIf(validate)
        } catch (exception: Exception) {
            logger.warn("DexKit target cache is invalid: key=$key, type=${exception.safeLogType()}")
            null
        } catch (error: LinkageError) {
            logger.warn("DexKit cached target link failed: key=$key, type=${error.safeLogType()}")
            null
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
