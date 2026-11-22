package io.github.namanwtf.atom.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import io.github.namanwtf.atom.agent.model.AgentModelClient
import java.io.ByteArrayOutputStream
import java.io.File

internal object AgentImageCodec {
    fun fromBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg"
    ): AgentModelClient.ModelImage {
        require(bytes.isNotEmpty()) { "image content is empty" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "image data too large: ${bytes.size}" }
        // For already-encoded images only size and format are read; the body keeps the original bytes.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val recognizedImage = bytes.hasSupportedImageMagic()
        val mime = opts.outMimeType
            ?.takeIf { recognizedImage && it.isNotBlank() }
            ?: mimeHint.normalizedAgentImageMimeType()
        return AgentModelClient.ModelImage(
            reference = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            mimeType = mime,
            bytes = bytes.size,
            width = opts.outWidth.takeIf { recognizedImage && it > 0 },
            height = opts.outHeight.takeIf { recognizedImage && it > 0 },
            source = source
        )
    }

    /** User attachments are uniformly encoded as model-compatible JPEG while keeping the original pixel dimensions. */
    fun fromAttachmentBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage =
        AgentModelImageEncoder.attachment(bytes, source, mimeHint)
            ?: error("cannot decode image")

    /** Existing screenshot encodings are passed through directly, without decoding pixels, scaling, or format conversion. */
    fun fromScreenBytes(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/png",
    ): AgentModelClient.ModelImage =
        fromBytes(bytes, source, mimeHint).copy(preserveOriginal = true)

    fun fromScreenBitmap(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage = AgentModelImageEncoder.screen(bitmap, source)

    /** The assistant and GUI screenshots share the same raw-pixel contract. */
    fun fromScreenContextBitmap(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage = fromScreenBitmap(bitmap, source)

    /**
     * Generates a separate small preview for the chat list. The model still reads the original from [image.reference], so the preview never takes part in model input.
     */
    fun previewFromReference(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = AgentModelImageEncoder.preview(context, image)

    /** File-tool images are compressed before being sent to the model to avoid several originals bloating the OpenAI-compatible request body. */
    fun fromToolFile(file: File, source: String): AgentModelClient.ModelImage? = runCatching {
        AgentModelImageEncoder.toolVision(file.readBytesLimited(), source)
    }.getOrNull()

    fun fromReference(
        context: Context?,
        value: String,
        source: String,
        preserveOriginal: Boolean = false,
    ): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return AgentModelClient.ModelImage(
                reference = trimmed,
                mimeType = "image/*",
                bytes = 0,
                source = source,
                preserveOriginal = preserveOriginal,
            )
        }
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val markerIndex = trimmed.indexOf("base64,", ignoreCase = true)
            if (markerIndex < 0) return null
            val encoded = trimmed.substring(markerIndex + "base64,".length)
            if (encoded.isBlank() || encoded.length > MAX_AGENT_IMAGE_BYTES * 2) return null
            val raw = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
            if (raw.isEmpty() || raw.size > MAX_AGENT_IMAGE_BYTES) return null
            val mimeHint = trimmed.substring("data:".length).substringBefore(";")
            return if (preserveOriginal) fromScreenBytes(raw, source, mimeHint)
            else AgentModelImageEncoder.attachment(raw, source, mimeHint)
        }

        if (context != null) {
            val uri = Uri.parse(trimmed)
            if (uri.scheme == "content") {
                return readContentUri(context, uri, source)?.copy(preserveOriginal = preserveOriginal)
            }
            if (uri.scheme == "file") {
                val file = uri.path?.let(::File)
                return file?.takeIf(File::isFile)?.let { candidate ->
                    runCatching {
                        fromBytes(candidate.readBytesLimited(), source).copy(preserveOriginal = preserveOriginal)
                    }.getOrNull()
                }
            }

            runCatching {
                val file = File(trimmed)
                if (file.isFile) {
                    return fromBytes(file.readBytesLimited(), source).copy(preserveOriginal = preserveOriginal)
                }
            }
        }

        return runCatching {
            if (!trimmed.looksLikeBase64()) return@runCatching null
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (decoded.hasSupportedImageMagic()) fromBytes(decoded, source).copy(preserveOriginal = preserveOriginal) else null
        }.getOrNull()
    }

    /**
     * Some ROMs' Photo Picker only implements typed-asset or file-descriptor reads.
     * Try the standard stream, typed asset, and file descriptor in order to avoid the attachment being silently dropped after a successful pick.
     */
    private fun readContentUri(
        context: Context,
        uri: Uri,
        source: String,
    ): AgentModelClient.ModelImage? {
        val resolver = context.contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytesLimited() }
        }.getOrNull()?.takeIf(ByteArray::isNotEmpty)
            ?: runCatching {
                resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.use { descriptor ->
                    descriptor.createInputStream().use { it.readBytesLimited() }
                }
            }.getOrNull()?.takeIf(ByteArray::isNotEmpty)
            ?: runCatching {
                resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytesLimited() }
                }
            }.getOrNull()?.takeIf(ByteArray::isNotEmpty)
            ?: return null
        val mimeHint = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        return runCatching {
            fromBytes(bytes, source, mimeHint)
        }.getOrNull()
    }

    /**
     * Resolves images for cross-process requests. Remote URLs and existing data URLs are kept as-is; for local URIs/paths only metadata is read,
     * and the body is transferred later by [io.github.namanwtf.atom.agent.runtime.AgentRuntimeImageTransfer] via a file descriptor.
     */
    fun fromTransferReference(
        context: Context?,
        value: String,
        source: String,
    ): AgentModelClient.ModelImage? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true)
        ) {
            return fromReference(context, trimmed, source)
        }
        if (context == null) return fromReference(null, trimmed, source)

        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return inspectContentUri(context, uri, trimmed, source)
        }
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return inspectFile(File(path), trimmed, source)
        }
        val file = File(trimmed)
        if (file.isFile) return inspectFile(file, trimmed, source)
        return fromReference(context, trimmed, source)
    }

    private fun inspectContentUri(
        context: Context,
        uri: Uri,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length
        } ?: -1L
        require(length <= MAX_AGENT_IMAGE_BYTES || length < 0L) { "image file too large: $length" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        val mime = options.outMimeType
            ?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
            ?: "image/*"
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = mime,
            bytes = length.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            source = source,
        )
    }.getOrNull()

    private fun inspectFile(
        file: File,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val length = file.length()
        require(file.isFile && length in 1..MAX_AGENT_IMAGE_BYTES.toLong()) { "image file unreadable or too large" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        AgentModelClient.ModelImage(
            reference = reference,
            mimeType = options.outMimeType?.takeIf { it.isNotBlank() } ?: "image/*",
            bytes = length.toInt(),
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            source = source,
        )
    }.getOrNull()

    private fun File.readBytesLimited(): ByteArray {
        require(length() <= MAX_AGENT_IMAGE_BYTES.toLong()) { "image file too large: ${length()}" }
        return readBytes()
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_AGENT_IMAGE_BYTES) { "image data too large: $total" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun String.looksLikeBase64(): Boolean {
        if (length < 64 || length > MAX_AGENT_IMAGE_BYTES * 2) return false
        return all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' || it == '\r' }
    }
}
