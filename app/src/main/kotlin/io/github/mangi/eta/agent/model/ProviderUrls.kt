package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderUrls {
    private const val OFFICIAL_VERTEX_HOST = "aiplatform.googleapis.com"
    private val vertexResourceId = Regex("[A-Za-z0-9_-]+")
    private val vertexLocationId = Regex("[a-z0-9-]+")
    private val vertexProjectPath = Regex("/v1(?:beta1)?/projects/([A-Za-z0-9_-]+)/locations/([a-z0-9-]+)")

    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun openAiImagesGenerationsUrl(baseUrl: String): String =
        appendPath(baseUrl, "images/generations")

    fun openAiImagesEditsUrl(baseUrl: String): String =
        appendPath(baseUrl, "images/edits")

    fun qwenMultimodalGenerationUrl(baseUrl: String): String =
        appendPath(baseUrl, "api/v1/services/aigc/multimodal-generation/generation")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    fun geminiInteractionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "interactions")

    fun geminiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun vertexStreamGenerateContentUrl(
        baseUrl: String,
        model: String,
        project: String = "",
        location: String = "global",
    ): String = "${vertexChatModelBaseUrl(baseUrl, model, project, location)}:streamGenerateContent?alt=sse"

    fun vertexGenerateContentUrl(
        baseUrl: String,
        model: String,
        project: String = "",
        location: String = "global",
    ): String = "${vertexModelBaseUrl(baseUrl, model, project, location)}:generateContent"

    fun vertexCountTokensUrl(
        baseUrl: String,
        model: String,
        project: String = "",
        location: String = "global",
    ): String = "${vertexChatModelBaseUrl(baseUrl, model, project, location)}:countTokens"

    fun validateVertexChatEndpoint(baseUrl: String, project: String, location: String) {
        val url = requireNotNull(normalizeBaseUrl(baseUrl).toHttpUrlOrNull()) {
            AppStrings.get(R.string.provider_url_vertex_invalid, "Vertex AI API URL is invalid.")
        }
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) {
            AppStrings.get(R.string.provider_url_vertex_https, "The Vertex AI API must use HTTPS, except for local debug URLs.")
        }
        require(url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
            AppStrings.get(R.string.provider_url_vertex_clean, "The Vertex AI API URL must not contain query parameters, fragments, or credentials.")
        }
        val projectId = project.trim()
        require(projectId.isEmpty() || vertexResourceId.matches(projectId)) {
            AppStrings.get(R.string.provider_url_vertex_project_invalid, "The Vertex AI Project ID or project number is invalid.")
        }
        val region = location.trim().lowercase().ifEmpty { "global" }
        require(vertexLocationId.matches(region)) {
            AppStrings.get(R.string.provider_url_vertex_location_invalid, "The Vertex AI Location is invalid.")
        }
        if (isOfficialVertexHost(url.host)) {
            val scoped = vertexProjectPath.matchEntire(url.encodedPath)
            require(scoped != null || url.encodedPath in setOf("/", "/v1", "/v1beta1")) {
                AppStrings.get(R.string.provider_url_vertex_official_path, "The official Vertex AI URL should be the API root URL or the project and region path.")
            }
            require(projectId.isNotEmpty() || scoped != null) {
                AppStrings.get(R.string.provider_url_vertex_project_required, "Gemini 4 requires a standard Vertex AI project; enter the Project ID or project number.")
            }
            require(scoped == null || projectId.isEmpty() ||
                (projectId == scoped.groupValues[1] && region == scoped.groupValues[2])) {
                AppStrings.get(R.string.provider_url_vertex_mismatch, "The project and region in the Vertex AI API URL must match the configuration.")
            }
        }
    }

    fun isOfficialVertexBaseUrl(baseUrl: String): Boolean {
        val host = normalizeBaseUrl(baseUrl).toHttpUrlOrNull()?.host ?: return false
        return isOfficialVertexHost(host)
    }

    fun vertexConfiguredLocation(baseUrl: String, location: String): String =
        normalizeBaseUrl(baseUrl).toHttpUrlOrNull()?.encodedPath?.let(vertexProjectPath::matchEntire)
            ?.groupValues?.get(2) ?: location.trim().lowercase().ifBlank { "global" }

    private fun vertexChatModelBaseUrl(baseUrl: String, model: String, project: String, location: String): String {
        validateVertexChatEndpoint(baseUrl, project, location)
        require(GeminiModelCapabilities.isGemini4(model)) {
            AppStrings.get(R.string.provider_url_vertex_gemini4_only, "Vertex AI chat supports only Gemini 4 series models.")
        }
        val apiBase = if (isOfficialVertexBaseUrl(baseUrl) &&
            normalizeBaseUrl(baseUrl).toHttpUrlOrNull()?.encodedPath == "/"
        ) {
            appendPath(baseUrl, GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL.substringAfterLast('/'))
        } else baseUrl
        return vertexModelBaseUrl(apiBase, GeminiModelCapabilities.normalize(model), project, location)
    }

    private fun vertexModelBaseUrl(
        baseUrl: String,
        model: String,
        project: String,
        location: String,
    ): String {
        val normalizedBase = normalizeBaseUrl(baseUrl)
        val normalizedModel = model.trim()
            .removePrefix("models/")
            .removePrefix("publishers/google/models/")
            .trim('/')
        val trimmedProject = project.trim()
        val normalizedLocation = location.trim().lowercase().ifBlank { "global" }
        val scoped = normalizedBase.toHttpUrlOrNull()?.encodedPath?.let(vertexProjectPath::matchEntire)
        val resolvedBase = resolveVertexHost(
            normalizedBase, scoped?.groupValues?.get(1) ?: trimmedProject,
            scoped?.groupValues?.get(2) ?: normalizedLocation,
        )
        val hasProjectPath = resolvedBase.contains("/projects/", ignoreCase = true)
        val path = if (trimmedProject.isNotEmpty() && !hasProjectPath) {
            "projects/$trimmedProject/locations/$normalizedLocation/publishers/google/models/$normalizedModel"
        } else {
            "publishers/google/models/$normalizedModel"
        }
        return appendPath(resolvedBase, path)
    }

    private fun resolveVertexHost(baseUrl: String, project: String, location: String): String {
        if (project.isEmpty()) return baseUrl
        val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        if (!isOfficialVertexHost(parsed.host)) return baseUrl
        val targetHost = when (location) {
            "global" -> OFFICIAL_VERTEX_HOST
            "us", "eu" -> "aiplatform.$location.rep.googleapis.com"
            else -> "$location-$OFFICIAL_VERTEX_HOST"
        }
        return if (parsed.host == targetHost) baseUrl else parsed.newBuilder().host(targetHost).build().toString().trimEnd('/')
    }

    private fun isOfficialVertexHost(host: String): Boolean = host == OFFICIAL_VERTEX_HOST ||
        host.endsWith("-$OFFICIAL_VERTEX_HOST") || host in setOf("aiplatform.us.rep.googleapis.com", "aiplatform.eu.rep.googleapis.com")

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
