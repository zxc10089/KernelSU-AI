package me.weishu.kernelsu.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** The provider answered, but not with a success status. */
class AiHttpStatusException(val code: Int) : Exception("HTTP " + code)

/** The provider answered with a 2xx body that has no data array. */
class AiResponseFormatException : Exception("malformed model list")

/**
 * Reads the provider model list (GET /models) so the user can pick a model instead of typing its
 * name from memory.
 *
 * The chat endpoint is the only thing the configuration screen stores, so the list URL is derived
 * from it by dropping the completion suffix and appending /models.
 */
object AiModelListClient {

    private val completionSuffixes = listOf("/chat/completions", "/completions", "/messages")

    /**
     * https://api.deepseek.com/v1/chat/completions -> https://api.deepseek.com/v1/models.
     * Returns null when [endpoint] is not an http(s) URL, which is the same rule the configuration
     * screen already applies before it lets the user save the field.
     */
    fun modelsUrl(endpoint: String): String? {
        val trimmed = endpoint.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        if (trimmed.endsWith("/models")) return trimmed
        val suffix = completionSuffixes.firstOrNull { trimmed.endsWith(it) }
        return if (suffix != null) {
            trimmed.dropLast(suffix.length) + "/models"
        } else {
            trimmed + "/models"
        }
    }

    suspend fun fetch(
        family: AiApiFamily,
        endpoint: String,
        apiKey: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val url = modelsUrl(endpoint)
            ?: return@withContext Result.failure(IllegalArgumentException("invalid endpoint"))

        runCatching {
            val request = Request.Builder()
                .url(url)
                .apply {
                    when (family) {
                        AiApiFamily.OPENAI_COMPATIBLE -> header("Authorization", "Bearer " + apiKey)
                        AiApiFamily.ANTHROPIC -> {
                            header("x-api-key", apiKey)
                            header("anthropic-version", "2023-06-01")
                        }
                    }
                }
                .get()
                .build()

            AiHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AiHttpStatusException(response.code)
                parse(response.body?.string().orEmpty())
            }
        }
    }

    /** A body shaped like {"data":[{"id":"deepseek-chat"}]} becomes a sorted id list. */
    private fun parse(body: String): List<String> {
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: throw AiResponseFormatException()
        val ids = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            // optString turns an explicit JSON null into the literal string "null"; skip those.
            val entry = data.optJSONObject(i) ?: continue
            val raw = entry.opt("id") ?: continue
            if (raw === JSONObject.NULL) continue
            val id = raw.toString()
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids.distinct().sorted()
    }
}
