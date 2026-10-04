package me.weishu.kernelsu.data.remote

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** One turn of the conversation as the provider expects it. */
@Immutable
data class AiChatMessage(
    val role: String,
    val content: String,
    /**
     * Pixels the user attached to this turn. Empty keeps the body plain text exactly as before; a
     * non-empty list switches this one message to the typed parts both families accept for images.
     */
    val images: List<AiImage> = emptyList(),
)

/** One image part: the base64 payload plus the media type it has to be labelled with. */
@Immutable
data class AiImage(val mediaType: String, val base64: String)

/** A streaming chunk: reasoning text and answer text are kept apart for the collapsed thinking card. */
@Immutable
data class AiChatDelta(val reasoning: String? = null, val text: String? = null)

@Immutable
data class AiChatResult(val text: String, val reasoning: String, val httpStatus: Int)

enum class AiChatErrorKind { HTTP, NETWORK, MALFORMED }

/** Any failure of a chat request; [detail] carries the provider message so the UI can show it. */
class AiChatException(
    val kind: AiChatErrorKind,
    val code: Int,
    val detail: String,
) : Exception(detail)

/**
 * Chat completions for both provider families.
 *
 * Streaming is requested first because the console shows tokens as they arrive; if a provider
 * answers with a plain JSON body anyway, that body is parsed as a single chunk. Nothing here is
 * trusted beyond being text: the reply is handed to [me.weishu.kernelsu.data.agent.AiActionParser]
 * and every proposed action is gated again on the client.
 */
object AiChatClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val ANTHROPIC_VERSION = "2023-06-01"

    suspend fun stream(
        family: AiApiFamily,
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        maxTokens: Int = 4096,
        onDelta: (AiChatDelta) -> Unit = {},
    ): Result<AiChatResult> = withContext(Dispatchers.IO) {
        try {
            if (model.isBlank()) {
                throw AiChatException(AiChatErrorKind.MALFORMED, 0, "model name is empty")
            }
            if (endpoint.isBlank()) {
                throw AiChatException(AiChatErrorKind.MALFORMED, 0, "endpoint is empty")
            }
            Result.success(perform(family, endpoint, apiKey, model, systemPrompt, history, maxTokens, onDelta))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun perform(
        family: AiApiFamily,
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        maxTokens: Int,
        onDelta: (AiChatDelta) -> Unit,
    ): AiChatResult {
        val payload = buildBody(family, model, systemPrompt, history, maxTokens)
        val builder = Request.Builder()
            .url(endpoint.trim())
            .post(payload.toRequestBody(JSON))
        when (family) {
            AiApiFamily.OPENAI_COMPATIBLE -> {
                if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + apiKey)
            }
            AiApiFamily.ANTHROPIC -> {
                if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", ANTHROPIC_VERSION)
            }
        }
        val call = AiHttpClient.client.newCall(builder.build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response)
                val source = response.body?.source()
                    ?: throw AiChatException(AiChatErrorKind.MALFORMED, response.code, "empty response body")
                val contentType = response.header("Content-Type").orEmpty()
                return if (contentType.contains("event-stream")) {
                    readStream(source, family, response.code, onDelta)
                } else {
                    readWhole(source.readUtf8(), family, response.code)
                }
            }
        } finally {
            call.cancel()
        }
    }

    private fun buildBody(
        family: AiApiFamily,
        model: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
        maxTokens: Int,
    ): String {
        val messages = JSONArray()
        if (family == AiApiFamily.OPENAI_COMPATIBLE && systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (message in history) {
            messages.put(JSONObject().put("role", message.role).put("content", contentOf(message, family)))
        }
        val root = JSONObject().put("model", model).put("messages", messages).put("stream", true)
        if (family == AiApiFamily.ANTHROPIC) {
            root.put("system", systemPrompt)
            root.put("max_tokens", maxTokens)
        }
        return root.toString()
    }

    /**
     * The body of one message: plain text unless the turn carries images, in which case the content
     * becomes an array of typed parts. Images are the user's own files and go only to the endpoint
     * the user configured, the same as every other byte of the conversation.
     */
    private fun contentOf(message: AiChatMessage, family: AiApiFamily): Any {
        if (message.images.isEmpty()) return message.content
        val parts = JSONArray()
        if (message.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.content))
        }
        for (image in message.images) {
            when (family) {
                AiApiFamily.OPENAI_COMPATIBLE -> parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:" + image.mediaType + ";base64," + image.base64),
                        ),
                )
                AiApiFamily.ANTHROPIC -> parts.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", image.mediaType)
                                .put("data", image.base64),
                        ),
                )
            }
        }
        return parts
    }

    private fun httpFailure(response: Response): AiChatException {
        val detail = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        return AiChatException(
            AiChatErrorKind.HTTP,
            response.code,
            detail.ifBlank { "HTTP " + response.code },
        )
    }

    /** Reads an SSE body, emitting deltas as they arrive. */
    private suspend fun readStream(
        source: BufferedSource,
        family: AiApiFamily,
        status: Int,
        onDelta: (AiChatDelta) -> Unit,
    ): AiChatResult {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break
            val delta = extractDelta(payload, family, status) ?: continue
            delta.reasoning?.let { reasoning.append(it) }
            delta.text?.let { text.append(it) }
            if (delta.reasoning != null || delta.text != null) onDelta(delta)
        }
        return AiChatResult(text.toString(), reasoning.toString(), status)
    }

    /**
     * [org.json]'s optString renders an explicit JSON `null` as the four character string
     * "null", so a stream carrying `"content": null` would inject literal "null" text into the
     * answer. Read the raw value and treat both a JSON null and an empty string as absent.
     */
    private fun JSONObject.optText(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.toString().ifEmpty { null }
    }

    /** One chunk or one whole body: OpenAI compatible. */
    private fun openAiPiece(json: JSONObject): AiChatDelta? {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val carrier = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (carrier == null) return null
        val reasoning = carrier.optText("reasoning_content")
        val text = carrier.optText("content")
        if (reasoning == null && text == null) return null
        return AiChatDelta(reasoning = reasoning, text = text)
    }

    private fun extractDelta(payload: String, family: AiApiFamily, status: Int): AiChatDelta? {
        val json = runCatching { JSONObject(payload) }.getOrElse {
            throw AiChatException(AiChatErrorKind.MALFORMED, status, payload.take(300))
        }
        return when (family) {
            AiApiFamily.OPENAI_COMPATIBLE -> openAiPiece(json)
            AiApiFamily.ANTHROPIC -> {
                when (json.optText("type")) {
                    "content_block_delta" -> {
                        val carrier = json.optJSONObject("delta") ?: return null
                        val text = carrier.optText("text")
                        val reasoning = carrier.optText("thinking")
                        if (text == null && reasoning == null) null else AiChatDelta(reasoning, text)
                    }
                    "error" -> throw AiChatException(
                        AiChatErrorKind.HTTP,
                        status,
                        json.optJSONObject("error")?.optText("message").orEmpty(),
                    )
                    else -> null
                }
            }
        }
    }

    /** A non streaming body, parsed with the same rules. */
    private fun readWhole(body: String, family: AiApiFamily, status: Int): AiChatResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw AiChatException(AiChatErrorKind.MALFORMED, status, body.take(300))
        }
        when (family) {
            AiApiFamily.OPENAI_COMPATIBLE -> {
                val piece = openAiPiece(json)
                if (piece != null) {
                    return AiChatResult(piece.text.orEmpty(), piece.reasoning.orEmpty(), status)
                }
            }
            AiApiFamily.ANTHROPIC -> {
                val blocks = json.optJSONArray("content")
                if (blocks != null) {
                    val text = StringBuilder()
                    for (i in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(i) ?: continue
                        if (block.optText("type") == "text") text.append(block.optText("text").orEmpty())
                    }
                    if (text.isNotEmpty()) return AiChatResult(text.toString(), "", status)
                }
            }
        }
        throw AiChatException(AiChatErrorKind.MALFORMED, status, body.take(300))
    }
}
