package io.github.namanwtf.atom.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.data.db.AtomDatabase
import io.github.namanwtf.atom.data.db.toDomain
import io.github.namanwtf.atom.data.db.toEntity
import io.github.namanwtf.atom.data.model.McpAuthorizationType
import io.github.namanwtf.atom.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object McpServerRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlowRows().map { rows ->
        val db = AtomDatabase.get(context())
        db.withTransaction {
            val serverDao = db.mcpServerDao()
            rows.map { serverDao.restoreServer(it).toDomain() }
        }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val secretStore = secrets()
        updateSecret(secretStore, added, bearerToken)
        try {
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.clear(added.id) }
            throw failure
        }
        return added
    }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null) {
        val previous = requireNotNull(serverById(server.id)) {
            AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
        }
        val changesSecret = bearerToken != null ||
            (previous.authorizationType != McpAuthorizationType.NONE &&
                server.authorizationType == McpAuthorizationType.NONE)
        val secretStore = if (changesSecret) secrets() else null
        val previousToken = secretStore?.bearerToken(server.id)
        val updated = if (bearerToken != null) {
            server.copy(lastProtocolVersion = null, toolsExpireAt = null)
        } else {
            server
        }
        if (changesSecret && secretStore != null) {
            updateSecret(secretStore, updated, bearerToken.orEmpty())
        }
        try {
            require(dao().update(updated.toEntity()) == 1) {
                AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
            }
        } catch (failure: Throwable) {
            if (changesSecret && secretStore != null) runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        val server = requireNotNull(serverById(serverId)) {
            AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
        }
        val names = server.enabledToolNames.toMutableSet()
        if (enabled) names += toolName else names -= toolName
        update(server.copy(enabledToolNames = names))
    }

    suspend fun setAllToolsEnabled(serverId: String, enabled: Boolean) {
        val server = requireNotNull(serverById(serverId)) {
            AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
        }
        val names = if (enabled) server.tools.mapTo(linkedSetOf()) { it.name } else emptySet()
        update(server.copy(enabledToolNames = names))
    }

    suspend fun setAllServersToolsEnabled(enabled: Boolean) {
        servers().forEach { server ->
            val names = if (enabled) server.tools.mapTo(linkedSetOf()) { it.name } else emptySet()
            if (names != server.enabledToolNames) {
                update(server.copy(enabledToolNames = names))
            }
        }
    }

    suspend fun delete(serverId: String) {
        val previous = requireNotNull(serverById(serverId)) {
            AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
        }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) {
                AppStrings.get(R.string.repo_err_mcp_server_not_found, "MCP server does not exist")
            }
        } catch (failure: Throwable) {
            runCatching { updateSecret(secretStore, previous, previousToken.orEmpty()) }
            throw failure
        }
    }

    fun bearerToken(serverId: String): String? = secrets().bearerToken(serverId)

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (server.authorizationType == McpAuthorizationType.BEARER) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.clear(server.id)
        }
    }

    private fun dao() = AtomDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}
