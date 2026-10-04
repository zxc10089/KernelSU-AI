package me.weishu.kernelsu.data.agent

import me.weishu.kernelsu.data.modulemaker.ModuleDraft
import me.weishu.kernelsu.data.modulemaker.ModuleDraftValidator
import me.weishu.kernelsu.data.modulemaker.ModuleMakerFile
import org.json.JSONObject

/**
 * Parses the action list out of a model reply.
 *
 * The reply may wrap its JSON in prose or a fenced block, so the first balanced JSON object wins.
 * Anything unparseable yields an empty list, and the caller then just shows the reply as text, which
 * is the safe outcome: no actions, nothing to gate.
 */
object AiActionParser {

    fun parse(text: String): List<AiAction> {
        val body = extractObject(text) ?: return emptyList()
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return emptyList()
        }
        val array = root.optJSONArray("actions") ?: return emptyList()
        val out = ArrayList<AiAction>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(fromJson(item))
        }
        return out
    }

    /** First balanced brace-delimited object in the text, or null. */
    fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == 92.toChar() -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** A line that is nothing but a code fence and/or a language label. */
    private fun isFenceLine(line: String): Boolean {
        var body = line.trim()
        for (mark in listOf("```", "``", "`")) {
            if (body.startsWith(mark)) {
                body = body.substring(mark.length).trim()
                break
            }
        }
        for (mark in listOf("```", "``", "`")) {
            if (body.endsWith(mark)) {
                body = body.substring(0, body.length - mark.length).trim()
                break
            }
        }
        return body.isEmpty() || fenceLabels.contains(body.lowercase())
    }

    private val fenceLabels = setOf("json", "jsonc", "json5", "javascript", "js")

    /**
     * The reply as it should be shown: the action block is cut out together with the fence or
     * language label that introduced it, because removing only the object leaves a bare `json`
     * line dangling under the answer. Lines are only dropped right next to the removed object, so
     * the rest of the reply keeps its shape. A reply with no object at all is returned trimmed.
     */
    fun stripActionBlock(text: String): String {
        val obj = extractObject(text) ?: return text.trim()
        val start = text.indexOf(obj)
        val head = text.substring(0, start).split('\n').toMutableList()
        val tail = text.substring(start + obj.length).split('\n').toMutableList()
        while (head.isNotEmpty() && isFenceLine(head.last())) head.removeAt(head.size - 1)
        while (tail.isNotEmpty() && isFenceLine(tail.first())) tail.removeAt(0)
        return (head + tail).joinToString("\n").trim()
    }
    private fun text(item: JSONObject, key: String): String {
        val raw = item.opt(key)
        if (raw == null || raw == JSONObject.NULL) return ""
        return raw.toString().trim()
    }

    private fun safePackageName(value: String): Boolean {
        if (value.isEmpty() || value.length > 255) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    private fun safeModuleId(value: String): Boolean {
        if (value.isEmpty() || value.length > 128) return false
        if (value.contains('/') || value.contains("..")) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    /** The files array of make_module: path plus the full text of that file. */
    private fun moduleFiles(item: JSONObject): List<ModuleMakerFile> {
        val array = item.optJSONArray("files") ?: return emptyList()
        val files = ArrayList<ModuleMakerFile>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val path = entry.optString("path").trim()
            if (path.isEmpty()) continue
            val raw = entry.opt("content")
            val content = if (raw == null || raw == JSONObject.NULL) "" else raw.toString()
            files.add(ModuleMakerFile(path = path, content = content))
        }
        return files
    }

    private fun fromJson(item: JSONObject): AiAction {
        val reason = text(item, "reason").ifBlank { null }
        val unknown = AiAction.Unknown(item.toString(), reason)
        val verb = text(item, "action")
        return when (verb) {
            AiAction.READ_FILE -> {
                val path = text(item, "path")
                if (path.isEmpty()) unknown else AiAction.ReadFile(path, reason)
            }
            AiAction.LIST_DIR -> {
                val path = text(item, "path")
                if (path.isEmpty()) unknown else AiAction.ListDir(path, reason)
            }
            AiAction.GET_FILE_METADATA -> {
                val path = text(item, "path")
                if (path.isEmpty()) unknown else AiAction.GetFileMetadata(path, reason)
            }
            AiAction.READ_FILE_CHUNK -> {
                val path = text(item, "path")
                if (path.isEmpty()) {
                    unknown
                } else {
                    AiAction.ReadFileChunk(
                        path = path,
                        startLine = item.optInt("start_line", 1).coerceAtLeast(1),
                        maxLines = item.optInt("max_lines", 0).takeIf { it > 0 }?.coerceIn(1, AiAction.MAX_CHUNK_LINES),
                        maxChars = item.optInt("max_chars", 0).takeIf { it > 0 },
                        reason = reason,
                    )
                }
            }
            AiAction.SEARCH_IN_FILE -> {
                val path = text(item, "path")
                val pattern = text(item, "pattern")
                if (path.isEmpty() || pattern.isEmpty()) unknown else AiAction.SearchInFile(path, pattern, reason)
            }
            AiAction.RUN_COMMAND -> {
                val command = text(item, "command")
                if (command.isEmpty()) unknown else AiAction.RunCommand(command, reason)
            }
            AiAction.GRANT_SU -> {
                val pkg = text(item, "packageName")
                if (!safePackageName(pkg)) unknown else AiAction.GrantSu(pkg, reason)
            }
            AiAction.REVOKE_SU -> {
                val pkg = text(item, "packageName")
                if (!safePackageName(pkg)) unknown else AiAction.RevokeSu(pkg, reason)
            }
            AiAction.DISABLE_MODULE -> {
                val id = text(item, "id")
                if (!safeModuleId(id)) unknown else AiAction.DisableModule(id, reason)
            }
            AiAction.ENABLE_MODULE -> {
                val id = text(item, "id")
                if (!safeModuleId(id)) unknown else AiAction.EnableModule(id, reason)
            }
            AiAction.INSTALL_MODULE -> {
                val path = text(item, "path")
                if (path.isEmpty()) unknown else AiAction.InstallModule(path, reason)
            }
            AiAction.MAKE_MODULE -> {
                val id = text(item, "id")
                val name = text(item, "name")
                if (name.isEmpty() || !ModuleDraftValidator.ID_PATTERN.matches(id)) {
                    unknown
                } else {
                    AiAction.MakeModule(
                        draft = ModuleDraft(
                            id = id,
                            name = name,
                            version = text(item, "version").ifBlank { "1.0" },
                            versionCode = text(item, "version_code")
                                .ifBlank { text(item, "versionCode") }
                                .ifBlank { "1" },
                            author = text(item, "author"),
                            description = text(item, "description"),
                            files = moduleFiles(item),
                            installScript = text(item, "install_script"),
                        ),
                        install = item.optBoolean("install", true),
                        reason = reason,
                    )
                }
            }
            AiAction.MOVE_TO_TRASH -> {
                val path = text(item, "path")
                if (path.isEmpty()) unknown else AiAction.MoveToTrash(path, reason)
            }
            AiAction.SAFE_EXEC -> {
                val command = text(item, "cmd").ifBlank { text(item, "command") }
                if (command.isEmpty()) {
                    unknown
                } else {
                    AiAction.SafeExec(
                        command = command,
                        timeoutSeconds = item
                            .optInt("timeout", AiAction.DEFAULT_SAFE_EXEC_TIMEOUT)
                            .coerceIn(AiAction.MIN_SAFE_EXEC_TIMEOUT, AiAction.MAX_SAFE_EXEC_TIMEOUT),
                        reason = reason,
                    )
                }
            }
            AiAction.ROOT_APP_LIST -> AiAction.RootAppList(reason)
            else -> unknown
        }
    }
}
