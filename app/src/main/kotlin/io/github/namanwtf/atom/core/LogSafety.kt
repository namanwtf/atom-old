package io.github.namanwtf.atom.core

private const val UNKNOWN_LOG_TOKEN = "unknown"

/** Returns a stable exception type that never carries exception messages or runtime data. */
internal fun Throwable.safeLogType(): String =
    javaClass.simpleName.takeIf { it.isNotBlank() } ?: Throwable::class.java.simpleName

/** Keeps the failure site and cause type while forbidding exception messages, payloads, or suppressed content from reaching diagnostic logs. */
internal fun Throwable.safeStackTrace(): String = buildString {
    var failure: Throwable? = this@safeStackTrace
    val visited = java.util.IdentityHashMap<Throwable, Boolean>()
    repeat(3) {
        val current = failure ?: return@buildString
        if (visited.put(current, true) != null) return@buildString
        if (isNotEmpty()) append("\nCaused by: ")
        append(current.safeLogType().take(128))
        val frames = current.stackTrace
        frames.take(16).forEach { frame ->
            append("\n at ")
            append(frame.className.take(256)).append('.').append(frame.methodName.take(128))
            append('(').append(frame.fileName?.take(128) ?: "unknown").append(':').append(frame.lineNumber).append(')')
        }
        if (frames.size > 16) append("\n ...")
        failure = current.cause
    }
}

/**
 * Constrain external or model-generated identifiers to a low-cardinality, single-line log token.
 */
internal fun String?.toSafeLogToken(maxLength: Int = 64): String {
    require(maxLength > 0) { "maxLength must be greater than 0" }
    val value = this ?: return UNKNOWN_LOG_TOKEN
    if (value.isEmpty() || value.length > maxLength) return UNKNOWN_LOG_TOKEN
    return value.takeIf { token ->
        token.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == '-'
        }
    } ?: UNKNOWN_LOG_TOKEN
}
