package me.weishu.kernelsu.data.agent.tool

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers shared by the eight handler objects.
 *
 * Two rules live here and nowhere else. First, nothing a handler writes into a root shell is ever
 * interpolated raw: content goes through base64 and paths go through [AiToolContext.quote]. Second,
 * every block of shell output that is too big to hand back gets [truncate]d with a visible marker, so
 * the model can tell "there is nothing more" apart from "I cut it off".
 */

internal fun textLines(text: String): List<String> =
    text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }

internal fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "\n…[输出已截断]"

internal fun jsonArray(values: List<Any?>): JSONArray = JSONArray().apply {
    for (value in values) put(value ?: JSONObject.NULL)
}

/** Splits `getprop`-style output using `@name` marker lines emitted by the command itself. */
internal fun splitBlocks(text: String, marker: String = "@"): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    out[""] = mutableListOf()
    var current = ""
    for (line in textLines(text)) {
        if (line.startsWith(marker)) {
            current = line.substring(marker.length).trim()
            out.getOrPut(current) { mutableListOf() }
        } else {
            out.getOrPut(current) { mutableListOf() }.add(line)
        }
    }
    return out
}

internal fun b64(value: String): String =
    Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

/**
 * Writes a file through the root shell without ever splicing its content into the command line:
 * the payload travels as base64, so a script containing quotes, newlines or `$(…)` cannot escape.
 */
internal suspend fun AiToolContext.writeFileBase64(path: String, content: String): AiShellIo {
    val target = quote(path) ?: return AiShellIo(-1, "", "路径无法安全引用")
    val parent = quote(path.substringBeforeLast('/', "/")) ?: return AiShellIo(-1, "", "目录无法安全引用")
    return shell("mkdir -p $parent && printf '%s' '" + b64(content) + "' | base64 -d > $target")
}

internal suspend fun AiToolContext.md5Of(path: String): String? {
    val target = quote(path) ?: return null
    return shell("md5sum $target 2>/dev/null | cut -d' ' -f1").out.trim().ifBlank { null }
}

internal fun AiShellIo.asText(max: Int = 3000): String =
    truncate(if (out.isNotBlank()) out else err, max)

internal fun textBlock(title: String, body: String, max: Int = 3000): String =
    "== " + title + " ==\n" + truncate(body.trim(), max)
