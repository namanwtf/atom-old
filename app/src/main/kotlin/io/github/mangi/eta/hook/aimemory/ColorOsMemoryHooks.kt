package io.github.mangi.eta.hook.aimemory

import android.content.ContentProvider
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.Bundle
import io.github.mangi.eta.agent.tool.ColorOsMemoryDatabaseQuery
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import io.github.mangi.eta.core.DexKitTargets
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.isPlaintextColorOsMemoryDatabase
import io.github.mangi.eta.core.safeLogType
import io.github.libxposed.api.XposedModule
import java.io.File
import org.json.JSONObject

internal object ColorOsMemoryHooks {
    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorOsMemory")
        return hooks.install {
            val providerClass = HookSupport.findClassOrNull(
                classLoader,
                ColorOsMemoryBridgeProtocol.PROVIDER_CLASS,
            )
            if (providerClass == null) {
                hooks.missing(
                    id = "coloros-memory.provider-call",
                    description = "Breeno Memory DataShareProvider.call",
                    detail = "Breeno Memory DataShareProvider not found, skipping system memory bridge",
                )
                return@install
            }
            val callMethod = HookSupport.findMethod(
                providerClass,
                "call",
                String::class.java,
                String::class.java,
                Bundle::class.java,
            )
            if (callMethod == null) {
                hooks.missing(
                    id = "coloros-memory.provider-call",
                    description = "Breeno Memory DataShareProvider.call",
                    detail = "DataShareProvider.call(String,String,Bundle) not found",
                )
                return@install
            }
            val hostDatabase = DexKitTargets(
                applicationInfo.sourceDir,
                classLoader,
                hooks.logger,
                File(applicationInfo.dataDir, "cache/eta-dexkit"),
                moduleNativeLibraryDirectory = module.moduleApplicationInfo.nativeLibraryDir,
                moduleApkPath = module.moduleApplicationInfo.sourceDir,
            ).use { ColorOsMemoryHostDatabase.resolve(classLoader, it, hooks.logger) }
            if (hostDatabase == null) {
                hooks.missing(
                    id = "coloros-memory.database-connection",
                    description = "Breeno Memory encrypted database connection",
                    detail = "Breeno Memory encrypted connection not ready, check the preceding DexKit loading and target-location logs",
                )
            }
            hooks.intercept(
                id = "coloros-memory.provider-call",
                executable = callMethod,
                description = "Breeno Memory in-process read-only query bridge",
            ) { chain ->
                val method = chain.args.getOrNull(0) as? String
                if (method != ColorOsMemoryBridgeProtocol.METHOD) {
                    return@intercept chain.proceed()
                }
                handleBridgeCall(
                    provider = chain.thisObject as? ContentProvider,
                    encodedRequest = chain.args.getOrNull(1) as? String,
                    hostDatabase = hostDatabase,
                    logger = hooks.logger,
                )
            }
        }
    }

    private fun handleBridgeCall(
        provider: ContentProvider?,
        encodedRequest: String?,
        hostDatabase: ColorOsMemoryHostDatabase?,
        logger: ModuleLogger,
    ): Bundle {
        if (Binder.getCallingUid() != ROOT_UID) {
            return response(error("COLOROS_MEMORY_HOOK_CALLER_REJECTED", "System memory query caller has no permission"))
        }
        val request = encodedRequest
            ?.let(ColorOsMemoryBridgeProtocol::decodeRequest)
            ?: return response(error("COLOROS_MEMORY_HOOK_REQUEST_INVALID", "System memory query parameters invalid"))
        val context = provider?.context
            ?: return response(error("COLOROS_MEMORY_HOOK_CONTEXT_UNAVAILABLE", "Breeno Memory context unavailable"))
        val databaseFile = context.getDatabasePath(ColorOsMemoryBridgeProtocol.DATABASE_NAME)
        if (!databaseFile.isFile) {
            return response(error("COLOROS_MEMORY_DATABASE_MISSING", "Breeno Memory database does not exist"))
        }
        val identity = Binder.clearCallingIdentity()
        val content = try {
            if (isPlaintextColorOsMemoryDatabase(databaseFile)) {
                SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { database ->
                    ColorOsMemoryDatabaseQuery.execute(database, request.operation, request.args)
                }
            } else if (hostDatabase == null) {
                error("COLOROS_MEMORY_HOST_DATABASE_UNAVAILABLE", "Encrypted memory database connection not currently supported")
            } else {
                val database = hostDatabase.current(databaseFile)
                if (database == null) {
                    error("COLOROS_MEMORY_DATABASE_NOT_READY", "Breeno Memory database not ready yet, open Breeno Memory and retry")
                } else {
                    ColorOsMemoryDatabaseQuery.execute(database, request.operation, request.args)
                }
            }
        } catch (exception: Exception) {
            logger.warnThrottled("coloros_memory_query_failed") {
                "Breeno Memory in-process query failed: type=${exception.safeLogType()}"
            }
            error("COLOROS_MEMORY_HOOK_QUERY_FAILED", "Breeno Memory in-process query failed")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return response(content)
    }

    private fun response(content: String): Bundle {
        val encoded = runCatching { ColorOsMemoryBridgeProtocol.encodeResponse(content) }
            .getOrElse {
                ColorOsMemoryBridgeProtocol.encodeResponse(
                    error("COLOROS_MEMORY_HOOK_RESULT_TOO_LARGE", "System memory query result too large"),
                )
            }
        return Bundle().apply {
            putString(ColorOsMemoryBridgeProtocol.RESULT_KEY, encoded)
        }
    }

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private const val ROOT_UID = 0
}
