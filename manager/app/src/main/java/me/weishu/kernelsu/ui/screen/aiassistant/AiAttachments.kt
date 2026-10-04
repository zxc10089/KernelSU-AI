package me.weishu.kernelsu.ui.screen.aiassistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R

/** How a picked file is turned into model input. */
enum class AiAttachmentKind { TEXT, ARCHIVE, IMAGE }

/** Why a picked file was rejected. */
enum class AiAttachmentError { UNSUPPORTED, TOO_LARGE, IMAGE_TOO_LARGE, UNREADABLE }

class AiAttachmentException(
    val error: AiAttachmentError,
    val fileName: String,
    cause: Throwable? = null,
) : Exception("attachment failed: " + error + " (" + fileName + ")", cause)

/**
 * One file the user attached to the next message.
 *
 * Text is read on the client and travels as text; an image is the one kind that travels as pixels
 * ([mediaType] plus [base64], which the chat client turns into an image part). [text] stays null for
 * an image because a picture is not text.
 */
@Immutable
data class AiAttachment(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val kind: AiAttachmentKind,
    val text: String? = null,
    /** True when the text had to be cut to fit the prompt budget. */
    val truncated: Boolean = false,
    /**
     * Image payload: the media type and the base64 bytes of a picked .png/.jpg/.jpeg. Both stay null
     * for text and archive files, which travel as [text] instead.
     */
    val mediaType: String? = null,
    val base64: String? = null,
)

/** Hard cap for a single picked file. */
private const val MAX_FILE_BYTES = 8L * 1024L * 1024L

/**
 * Separate, smaller cap for images: base64 inflates the payload by a third on the wire and a
 * full resolution photo is not needed to answer a question about what is on screen.
 */
private const val MAX_IMAGE_BYTES = 5L * 1024L * 1024L

/** Characters kept from one text file. */
private const val MAX_TEXT_CHARS = 64_000

/** Characters kept from one entry inside an archive. */
private const val MAX_ARCHIVE_ENTRY_CHARS = 4_000

/** How many archive entries are expanded at most. */
private const val MAX_ARCHIVE_ENTRIES = 10

/** Budget for all attachments of one message. */
private const val MAX_PROMPT_CHARS = 120_000

private const val ATTACHMENT_NEWLINE = "\n"

private val TEXT_EXTENSIONS = setOf("log", "txt")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

/** 1.2 MB / 345 KB / 12 B. */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/**
 * Reads a picked document. Runs on IO and never throws: the caller maps the failure to a message
 * with the file name, so one bad file in a multi-select does not drop the good ones.
 */
suspend fun readAttachment(context: Context, uri: Uri, id: Long): Result<AiAttachment> =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = queryName(context, uri)
            val declaredSize = querySize(context, uri)
            val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
            val kind = when {
                extension in TEXT_EXTENSIONS -> AiAttachmentKind.TEXT
                extension == "zip" -> AiAttachmentKind.ARCHIVE
                extension in IMAGE_EXTENSIONS -> AiAttachmentKind.IMAGE
                else -> throw AiAttachmentException(AiAttachmentError.UNSUPPORTED, name)
            }
            if (kind == AiAttachmentKind.IMAGE) {
                if (declaredSize > MAX_IMAGE_BYTES) {
                    throw AiAttachmentException(AiAttachmentError.IMAGE_TOO_LARGE, name)
                }
                // Nothing is decoded or resized on device: the pixels go over as they are stored.
                val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw AiAttachmentException(AiAttachmentError.UNREADABLE, name)
                if (imageBytes.size > MAX_IMAGE_BYTES) {
                    throw AiAttachmentException(AiAttachmentError.IMAGE_TOO_LARGE, name)
                }
                return@runCatching AiAttachment(
                    id = id,
                    name = name,
                    sizeBytes = if (declaredSize > 0) declaredSize else imageBytes.size.toLong(),
                    kind = kind,
                    mediaType = imageMediaType(extension),
                    base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
                )
            }
            if (declaredSize > MAX_FILE_BYTES) throw AiAttachmentException(AiAttachmentError.TOO_LARGE, name)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AiAttachmentException(AiAttachmentError.UNREADABLE, name)
            if (bytes.size > MAX_FILE_BYTES) throw AiAttachmentException(AiAttachmentError.TOO_LARGE, name)
            val size = if (declaredSize > 0) declaredSize else bytes.size.toLong()
            when (kind) {
                AiAttachmentKind.TEXT -> {
                    val raw = bytes.toString(Charsets.UTF_8)
                    val cut = raw.length > MAX_TEXT_CHARS
                    AiAttachment(
                        id = id,
                        name = name,
                        sizeBytes = size,
                        kind = kind,
                        text = if (cut) raw.take(MAX_TEXT_CHARS) else raw,
                        truncated = cut,
                    )
                }
                AiAttachmentKind.ARCHIVE -> {
                    val archive = readArchive(bytes)
                    AiAttachment(
                        id = id,
                        name = name,
                        sizeBytes = size,
                        kind = kind,
                        text = archive.first,
                        truncated = archive.second,
                    )
                }
                // Unreachable: an image returns with its pixels before this point.
                AiAttachmentKind.IMAGE -> throw AiAttachmentException(AiAttachmentError.UNREADABLE, name)
            }
        }
    }

/** Flattens the text entries of a zip; returns the text and whether anything was cut. */
private fun readArchive(bytes: ByteArray): Pair<String, Boolean> {
    val builder = StringBuilder()
    var entries = 0
    var cut = false
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            if (entries >= MAX_ARCHIVE_ENTRIES) {
                cut = true
                break
            }
            val entryExtension = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
            if (entryExtension !in TEXT_EXTENSIONS) continue
            entries += 1
            val buffer = ByteArray(MAX_ARCHIVE_ENTRY_CHARS * 4)
            var filled = 0
            while (filled < buffer.size) {
                val read = zip.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled >= buffer.size) cut = true
            val content = String(buffer, 0, filled, Charsets.UTF_8)
            if (content.length > MAX_ARCHIVE_ENTRY_CHARS) cut = true
            builder.append(ATTACHMENT_NEWLINE).append("--- ").append(entry.name).append(" ---")
            builder.append(ATTACHMENT_NEWLINE)
            builder.append(content.take(MAX_ARCHIVE_ENTRY_CHARS))
            builder.append(ATTACHMENT_NEWLINE)
            zip.closeEntry()
        }
    }
    return builder.toString().trim() to cut
}

private fun queryName(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val value = cursor.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"
}

private fun querySize(context: Context, uri: Uri): Long {
    val projection = arrayOf(OpenableColumns.SIZE)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
        }
    }
    return -1L
}

/**
 * The block handed to the model for one message's attachments. Deliberately not localised: it is
 * model-facing scaffolding, not UI copy.
 */
fun promptBlock(attachments: List<AiAttachment>): String {
    if (attachments.isEmpty()) return ""
    val builder = StringBuilder()
    builder
        .append("用户上传了 ")
        .append(attachments.size)
        .append(" 个附件，下面是客户端在发送前读取到的内容（可能被截断）：")
        .append(ATTACHMENT_NEWLINE)
    var used = 0
    attachments.forEachIndexed { index, attachment ->
        builder
            .append(ATTACHMENT_NEWLINE)
            .append("[附件 ")
            .append(index + 1)
            .append("] ")
            .append(attachment.name)
            .append("（")
            .append(formatSize(attachment.sizeBytes))
            .append("，")
            .append(kindLabel(attachment.kind))
            .append("）")
        if (attachment.truncated) builder.append("（已截断）")
        builder.append(ATTACHMENT_NEWLINE)
        val body = attachment.text
        if (body.isNullOrBlank()) {
            builder.append(
                if (attachment.kind == AiAttachmentKind.IMAGE) {
                    "（这是一张图片：像素内容已随本条消息一起发出，请直接看图回答；如果图上看不出问题，再说明还需要什么信息。）"
                } else {
                    "（没有可读的文本内容）"
                },
            )
            builder.append(ATTACHMENT_NEWLINE)
            return@forEachIndexed
        }
        val remaining = MAX_PROMPT_CHARS - used
        if (remaining <= 0) {
            builder.append("（附件总长度已达上限，这里只保留文件名）").append(ATTACHMENT_NEWLINE)
            return@forEachIndexed
        }
        val slice = if (body.length > remaining) body.take(remaining) else body
        used += slice.length
        builder.append(slice)
        if (slice.length < body.length) builder.append(ATTACHMENT_NEWLINE).append("（内容过长，已截断）")
        builder.append(ATTACHMENT_NEWLINE)
    }
    return builder.toString()
}

/** The media types both provider families accept for the extensions the picker allows. */
private fun imageMediaType(extension: String): String =
    if (extension == "png") "image/png" else "image/jpeg"

private fun kindLabel(kind: AiAttachmentKind): String = when (kind) {
    AiAttachmentKind.TEXT -> "纯文本"
    AiAttachmentKind.ARCHIVE -> "压缩包，已解出其中的文本条目"
    AiAttachmentKind.IMAGE -> "图片（已随消息发送像素）"
}

/**
 * Picks one or more documents and reads them off the main thread.
 *
 * The returned lambda opens the system picker with a wide mime type on purpose: file managers
 * report .log and extension-less files inconsistently, so the extension check happens after the
 * pick and rejects what the client cannot use.
 */
@Composable
fun rememberAttachmentPicker(
    onPicked: (List<AiAttachment>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val base = System.currentTimeMillis()
            val picked = mutableListOf<AiAttachment>()
            uris.forEachIndexed { index, uri ->
                readAttachment(context, uri, base + index)
                    .onSuccess { picked += it }
                    .onFailure { failure ->
                        val attachmentFailure = failure as? AiAttachmentException
                        val message = if (attachmentFailure != null) {
                            val textRes = when (attachmentFailure.error) {
                                AiAttachmentError.UNSUPPORTED -> R.string.ai_console_attach_unsupported
                                AiAttachmentError.TOO_LARGE -> R.string.ai_console_attach_too_large
                                AiAttachmentError.IMAGE_TOO_LARGE -> R.string.ai_console_attach_image_too_large
                                AiAttachmentError.UNREADABLE -> R.string.ai_console_attach_failed
                            }
                            context.getString(textRes, attachmentFailure.fileName)
                        } else {
                            context.getString(
                                R.string.ai_console_attach_failed,
                                uri.lastPathSegment ?: "attachment",
                            )
                        }
                        onError(message)
                    }
            }
            if (picked.isNotEmpty()) onPicked(picked)
        }
    }
    return { launcher.launch("*/*") }
}
