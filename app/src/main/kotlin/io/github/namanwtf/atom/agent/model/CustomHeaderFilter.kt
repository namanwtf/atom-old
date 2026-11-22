package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.data.model.CustomHeader

/**
 * Security management for custom HTTP headers.
 *
 * Filters out headers that would break the HTTP protocol or are managed by the framework automatically, and redacts sensitive headers in logs.
 */
internal object CustomHeaderFilter {

    /**
     * Header names that users are forbidden to set manually (case-insensitive).
     */
    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "anthropic-version"
    )

    /**
     * Header names that need redaction in logs (case-insensitive).
     */
    private val SENSITIVE_NAMES = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key",
        "api-key"
    )

    /**
     * Whether this is a forbidden header name.
     */
    fun isForbidden(name: String): Boolean =
        name.trim().isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    /**
     * Filters illegal headers out of the list.
     */
    fun sanitize(headers: List<CustomHeader>): List<CustomHeader> =
        headers.filterNot { isForbidden(it.name) }

    fun validationError(headers: List<CustomHeader>): String? {
        val names = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            val prefix = "Request header ${index + 1}"
            if (isForbidden(name)) return "${prefix} name is empty or managed by the system"
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                return "${prefix} name contains invalid characters"
            }
            if (header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "${prefix} value may contain only printable ASCII characters or tabs"
            }
            if (!names.add(name.lowercase())) return "${prefix} name is duplicated (case-insensitive)"
        }
        return null
    }

    /**
     * Merges custom headers into the OkHttp Headers Builder; later-added headers override earlier ones with the same name.
     */
    fun mergeInto(
        builder: okhttp3.Headers.Builder,
        headers: List<CustomHeader>
    ) {
        sanitize(headers).forEach { header ->
            builder.set(header.name.trim(), header.value)
        }
    }

    /**
     * Redacts sensitive headers for log output.
     */
    fun redactForLog(headers: List<CustomHeader>): List<Pair<String, String>> =
        sanitize(headers).map { header ->
            val nameLower = header.name.lowercase()
            val value = if (nameLower in SENSITIVE_NAMES) {
                "***"
            } else {
                header.value
            }
            header.name to value
        }
}
