package me.weishu.kernelsu.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.repository.AiAuditRepository
import me.weishu.kernelsu.data.repository.AiSettingsRepository
import me.weishu.kernelsu.data.modulemaker.ModuleDraftValidator
import me.weishu.kernelsu.data.modulemaker.ModuleMaker
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray
import org.json.JSONObject
import me.weishu.kernelsu.ui.util.execKsud
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.toggleModule

/** What one action actually did. [output] is shown in the terminal card and fed back to the model. */
data class AiExecutionOutcome(
    val ok: Boolean,
    val output: String,
    val undoable: Boolean = false,
)

/**
 * Performs a single action that has already passed [AiPathGuard].
 *
 * The executor never decides anything: gating and confirmation happen before it is called, and it
 * only writes audit entries for changes that really happened. Output is capped so a huge file cannot
 * blow up the conversation.
 */
interface AiActionExecutor {
    suspend fun execute(action: AiAction, scope: AiAccessScope): AiExecutionOutcome
}

class AiActionExecutorImpl(
    private val audit: AiAuditRepository = me.weishu.kernelsu.data.repository.AiAuditRepositoryImpl(),
    private val superUser: me.weishu.kernelsu.data.repository.SuperUserRepository =
        me.weishu.kernelsu.data.repository.SuperUserRepositoryImpl(),
    private val settings: AiSettingsRepository = me.weishu.kernelsu.data.repository.AiSettingsRepositoryImpl(),
) : AiActionExecutor {

    companion object {
        const val TRASH_ROOT = "/data/adb/.ai_trash"
        const val MAX_OUTPUT = 6000
        const val KIND_SU = "su"
        const val KIND_MODULE = "module"
        const val KIND_TRASH = "move_to_trash"
        const val KIND_INSTALL = "install_module"
        const val KIND_MAKE_MODULE = "make_module"
        const val KIND_COMMAND = "command"
        const val KIND_SAFE_EXEC = "safe_exec_shell"
        const val TIMEOUT_EXIT_CODE = 124

        /** Lower floor for the user configurable read window, so a silly setting cannot break reads. */
        const val MIN_READ_LIMIT = 1000

        /** Files above this are never loaded as text at all; the model must search and window them. */
        const val MAX_TEXT_FILE_BYTES = 2L * 1024L * 1024L

        /** Bytes sniffed before a file is called binary. */
        const val BINARY_PROBE_BYTES = 512

        /**
         * Extensions that are binary by definition. The KernelSU allowlist is a binary database, and
         * the model must read root grants through get_root_app_list instead of cat'ing it.
         */
        val BINARY_EXTENSIONS = setOf(
            "db", "sqlite", "sqlite3", "db-wal", "db-shm", "wal", "shm", "allowlist", "so", "zip",
            "apk", "jar", "dex", "vdex", "oat", "art", "img", "bin", "png", "jpg", "jpeg", "webp",
            "gif", "ttf", "otf", "mp3", "mp4", "wav",
        )

        /**
         * Shell commands whose purpose is to print a file. The read tools refuse binary input, and the
         * shell has to agree: otherwise `cat /data/adb/ksu/.allowlist` would bypass every text guard.
         */
        val SHELL_TEXT_READERS = setOf(
            "cat", "head", "tail", "less", "more", "tac", "nl", "strings", "xxd", "od", "hexdump",
            "grep", "sed", "base64",
        )
    }

    private data class FileMeta(val bytes: Long, val isDirectory: Boolean, val binary: Boolean)

    private data class ShellIo(val code: Int, val out: String, val err: String)

    override suspend fun execute(action: AiAction, scope: AiAccessScope): AiExecutionOutcome =
        withContext(Dispatchers.IO) {
            when (action) {
                is AiAction.ReadFile -> readFileWindow(action.path, 1, null, null, whole = true)
                is AiAction.GetFileMetadata -> fileMetadata(action.path)
                is AiAction.ReadFileChunk -> readFileWindow(
                    action.path,
                    action.startLine,
                    action.maxLines,
                    action.maxChars,
                    whole = false,
                )
                is AiAction.SearchInFile -> searchInFile(action.path, action.pattern)
                is AiAction.ListDir -> listDir(action.path)
                is AiAction.RunCommand -> runCommandAction(action.command, scope)
                is AiAction.SafeExec -> safeExec(action.command, action.timeoutSeconds)
                is AiAction.RootAppList -> rootAppList()
                is AiAction.GrantSu -> setSu(action.packageName, true)
                is AiAction.RevokeSu -> setSu(action.packageName, false)
                is AiAction.DisableModule -> setModule(action.id, false)
                is AiAction.EnableModule -> setModule(action.id, true)
                is AiAction.InstallModule -> installModule(action.path)
                is AiAction.MakeModule -> makeModule(action)
                is AiAction.MoveToTrash -> moveToTrash(action.path)
                is AiAction.Unknown -> AiExecutionOutcome(false, "无法识别的动作，已拒绝执行")
            }
        }

    /**
     * The one text read path. Binary files never reach the model, a directory is redirected to
     * list_dir, and anything past the read window ends with an explicit start_line to continue from,
     * because the model reads this text back as its tool result.
     */
    private suspend fun readFileWindow(
        path: String,
        startLine: Int,
        maxLines: Int?,
        maxChars: Int?,
        whole: Boolean,
    ): AiExecutionOutcome {
        val quoted = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val meta = probe(path, quoted)
        if (meta.isDirectory) return AiExecutionOutcome(false, "这是一个目录，请改用 list_dir：" + path)
        if (meta.binary) return AiExecutionOutcome(false, binaryRefusal(path))
        if (meta.bytes > MAX_TEXT_FILE_BYTES) {
            return AiExecutionOutcome(
                false,
                "文件约 " + kb(meta.bytes) + " KB，超过客户端可读文本上限 " + kb(MAX_TEXT_FILE_BYTES) +
                    " KB。请先用 search_in_file 定位关键行，再用 read_file_chunk 分段读取。",
            )
        }
        val io = shell("cat " + quoted)
        if (io.code != 0) {
            return AiExecutionOutcome(false, cap(io.err.ifBlank { io.out }.ifBlank { "(无输出)" }))
        }
        val text = io.out
        val chars = text.length
        val all = text.split(10.toChar())
        val totalLines = all.size
        val limit = settings.readChunkLimit.coerceAtLeast(MIN_READ_LIMIT)
        val unlimited = whole && meta.bytes <= settings.fullReadThresholdKb.toLong() * 1024L
        val charCap = if (unlimited) Int.MAX_VALUE else minOf(maxChars ?: limit, limit).coerceAtLeast(200)
        val from = (startLine - 1).coerceIn(0, maxOf(0, totalLines - 1))
        val takeLines = maxLines ?: AiAction.MAX_CHUNK_LINES
        var body = all.drop(from).take(takeLines).joinToString(10.toChar().toString())
        var consumed = all.size - from
        if (body.length > charCap) {
            val slice = body.substring(0, charCap)
            val cut = slice.lastIndexOf(10.toChar())
            body = if (cut > 0) slice.substring(0, cut) else slice
            consumed = body.count { it == 10.toChar() }.coerceAtLeast(1)
        }
        val endLine = from + consumed
        val truncated = endLine < totalLines
        val trailer = if (!truncated) {
            ""
        } else {
            newline() + "[系统已截断，原文件共 " + totalLines + " 行 / " + chars + " 字符，剩余内容请使用 start_line=" +
                (endLine + 1) + " 继续读取]"
        }
        return AiExecutionOutcome(true, body + trailer)
    }

    /** Literal, line numbered search; answers with line numbers so the next read can be a window. */
    private suspend fun searchInFile(path: String, pattern: String): AiExecutionOutcome {
        val quoted = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val meta = probe(path, quoted)
        if (meta.isDirectory) return AiExecutionOutcome(false, "这是一个目录，请改用 list_dir：" + path)
        if (meta.binary) return AiExecutionOutcome(false, binaryRefusal(path))
        val needle = quote(pattern) ?: return AiExecutionOutcome(false, "搜索词包含非法字符，已拒绝")
        val io = shell("grep -n -F -- " + needle + " " + quoted)
        if (io.code == 1) {
            return AiExecutionOutcome(true, "未找到匹配「" + pattern + "」（文件共 " + kb(meta.bytes) + " KB）")
        }
        if (io.code != 0) {
            return AiExecutionOutcome(false, cap(io.err.ifBlank { io.out }.ifBlank { "(无输出)" }))
        }
        val limit = settings.readChunkLimit.coerceAtLeast(MIN_READ_LIMIT)
        return AiExecutionOutcome(true, cap(io.out, limit))
    }

    /** Size, kind and binary sniff of one path, so the read tools can refuse before they leak noise. */
    private suspend fun probe(path: String, quoted: String): FileMeta {
        val sizeIo = shell("wc -c " + quoted)
        val bytes = sizeIo.out.trim().split(' ').firstOrNull()?.toLongOrNull() ?: -1L
        val listing = shell("ls -ld " + quoted)
        val isDirectory = listing.code == 0 && listing.out.startsWith("d")
        val hidden = path.substringAfterLast('/').startsWith(".allowlist")
        val extension = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        val looksBinary = hidden || extension in BINARY_EXTENSIONS
        val binary = looksBinary || sniffBinary(quoted)
        return FileMeta(bytes = bytes, isDirectory = isDirectory, binary = binary)
    }

    /** Reads the first bytes and calls it binary when they carry control characters. */
    private suspend fun sniffBinary(quoted: String): Boolean {
        val head = shell("head -c " + BINARY_PROBE_BYTES + " " + quoted)
        if (head.code != 0) return false
        val sample = head.out
        if (sample.isEmpty()) return false
        return sample.any { ch -> ch.code < 9 || (ch.code in 14..31) || ch.code == 127 }
    }

    private fun binaryRefusal(path: String): String {
        if (path.substringAfterLast('/').startsWith(".allowlist")) {
            return "该文件是 KernelSU 的二进制授权数据库，客户端禁止以文本读取；请调用 get_root_app_list " +
                "获取解析后的授权列表（包含包名、UID 与授权状态）。"
        }
        return "该文件是二进制（含不可打印字节），客户端不会把二进制内容交给模型。若它属于某个系统数据库，" +
            "请改用对应的结构化接口，例如 root 授权列表用 get_root_app_list。"
    }

    /** Metadata as JSON: the model gets numbers it can plan with instead of guessing file sizes. */
    private suspend fun fileMetadata(path: String): AiExecutionOutcome {
        val quoted = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val meta = probe(path, quoted)
        val exists = meta.bytes >= 0 || meta.isDirectory
        if (!exists) return AiExecutionOutcome(false, "无法读取文件信息（可能不存在）：" + path)
        val json = JSONObject()
        json.put("path", path)
        json.put("exists", true)
        json.put("isDirectory", meta.isDirectory)
        json.put("binary", meta.binary)
        json.put("sizeBytes", meta.bytes)
        if (!meta.isDirectory && !meta.binary && meta.bytes <= MAX_TEXT_FILE_BYTES) {
            val io = shell("wc -l " + quoted)
            val lines = io.out.trim().split(' ').firstOrNull()?.toLongOrNull() ?: -1L
            json.put("lines", lines)
            json.put("chars", meta.bytes)
        }
        json.put("readLimitChars", settings.readChunkLimit)
        json.put("fullReadThresholdKb", settings.fullReadThresholdKb)
        json.put("readableAsText", !meta.isDirectory && !meta.binary && meta.bytes <= MAX_TEXT_FILE_BYTES)
        return AiExecutionOutcome(true, json.toString(2))
    }

    private suspend fun listDir(path: String): AiExecutionOutcome {
        val quoted = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val io = shell("ls -la " + quoted)
        if (io.code != 0) {
            return AiExecutionOutcome(false, cap(io.err.ifBlank { io.out }.ifBlank { "(无输出)" }))
        }
        return AiExecutionOutcome(true, cap(io.out.ifBlank { "(空目录)" }))
    }

    /**
     * A plain shell file reader can reach the same bytes the text tools refuse, so `cat` on the
     * allowlist database has to fail exactly like read_file does. Returns the refusal text, or null
     * when the command is not a file reader or its target is readable text.
     */
    private suspend fun shellBinaryGuard(command: String): String? {
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = tokens.firstOrNull()?.lowercase() ?: return null
        if (head !in SHELL_TEXT_READERS) return null
        val targets = tokens.drop(1).filter { !it.startsWith("-") && it.startsWith("/") }
        for (target in targets) {
            val quoted = quote(target) ?: continue
            if (probe(target, quoted).binary) return binaryRefusal(target)
        }
        return null
    }

    private suspend fun runCommandAction(command: String, scope: AiAccessScope): AiExecutionOutcome {
        shellBinaryGuard(command)?.let { return AiExecutionOutcome(false, it) }
        val io = shell(command)
        val body = buildString {
            if (io.out.isNotBlank()) appendLine(io.out)
            if (io.err.isNotBlank()) appendLine(io.err)
            if (isEmpty()) appendLine("(无输出)")
        }.trim()
        val ok = io.code == 0
        if (scope != AiAccessScope.NONE) {
            audit.append(
                AiAuditEntry(
                    ts = System.currentTimeMillis(),
                    kind = KIND_COMMAND,
                    target = command,
                    result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                    undoable = false,
                ),
            )
        }
        return AiExecutionOutcome(ok, cap(body))
    }

    /**
     * The whitelisted query channel. The command already passed AiPathGuard.decideSafeExec and the user
     * confirmed it; toybox timeout keeps a command that starts streaming from hanging the console.
     */
    private suspend fun safeExec(command: String, timeoutSeconds: Int): AiExecutionOutcome {
        shellBinaryGuard(command)?.let { return AiExecutionOutcome(false, it) }
        val io = shell("timeout " + timeoutSeconds + " " + command)
        if (io.code == TIMEOUT_EXIT_CODE) {
            return AiExecutionOutcome(false, "命令超过 " + timeoutSeconds + " 秒仍未结束，已被终止")
        }
        val body = buildString {
            if (io.out.isNotBlank()) appendLine(io.out)
            if (io.err.isNotBlank()) appendLine(io.err)
            if (isEmpty()) appendLine("(无输出)")
        }.trim()
        val ok = io.code == 0
        audit.append(
            AiAuditEntry(
                ts = System.currentTimeMillis(),
                kind = KIND_SAFE_EXEC,
                target = command,
                command = command,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
            ),
        )
        return AiExecutionOutcome(ok, cap(body))
    }

    /**
     * Which apps hold root right now, read through the same root service and kernel profiles the superuser
     * screen uses. The app process cannot see other packages on its own (no QUERY_ALL_PACKAGES), so a plain
     * PackageManager query would silently report only this app.
     */
    /**
     * Structured root grants. The model gets JSON instead of a text database dump, so it never has to
     * guess at field boundaries, and the KernelSU allowlist itself stays unreadable on purpose.
     */
    private suspend fun rootAppList(): AiExecutionOutcome {
        val apps = superUser.getAppList().getOrNull()?.first
            ?: return AiExecutionOutcome(false, "读取应用列表失败")
        val list = JSONArray()
        for (app in apps.filter { it.allowSu }.sortedBy { it.packageName }) {
            val entry = JSONObject()
            entry.put("packageName", app.packageName)
            entry.put("uid", app.uid)
            entry.put("label", app.label)
            entry.put("granted", true)
            entry.put("loadsModules", app.profile?.umountModules != true)
            list.put(entry)
        }
        val json = JSONObject()
        json.put("total", list.length())
        json.put("source", "KernelSU root service")
        json.put("apps", list)
        return AiExecutionOutcome(true, cap(json.toString()))
    }

    private suspend fun setSu(packageName: String, allow: Boolean): AiExecutionOutcome {
        val uid = packageUid(packageName)
            ?: return AiExecutionOutcome(false, "找不到应用 " + packageName)
        if (uid < 2000 && uid != 1000) {
            return AiExecutionOutcome(false, "系统应用（uid " + uid + "）不允许通过助手授权")
        }
        val before = runCatching { Natives.getAppProfile(packageName, uid) }.getOrNull()
            ?: return AiExecutionOutcome(false, "读取不到 " + packageName + " 的授权配置")
        val updated = before.copy(allowSu = allow)
        val ok = runCatching { Natives.setAppProfile(updated) }.getOrDefault(false)
        if (ok) {
            audit.append(
                AiAuditEntry(
                    ts = System.currentTimeMillis(),
                    kind = KIND_SU,
                    target = packageName,
                    before = if (before.allowSu) "allow" else "deny",
                    after = if (allow) "allow" else "deny",
                    result = AiAuditEntry.RESULT_OK,
                    undoable = true,
                ),
            )
        }
        val text = if (ok) {
            (if (allow) "已授予 " else "已撤销 ") + packageName + " 的 root 权限"
        } else {
            "写入失败：" + packageName
        }
        return AiExecutionOutcome(ok, text, undoable = ok)
    }

    private suspend fun setModule(id: String, enable: Boolean): AiExecutionOutcome {
        val ok = runCatching { toggleModule(id, enable) }.getOrDefault(false)
        if (ok) {
            audit.append(
                AiAuditEntry(
                    ts = System.currentTimeMillis(),
                    kind = KIND_MODULE,
                    target = id,
                    before = if (enable) "disabled" else "enabled",
                    after = if (enable) "enabled" else "disabled",
                    result = AiAuditEntry.RESULT_OK,
                    undoable = true,
                ),
            )
        }
        val text = if (ok) {
            (if (enable) "已启用模块 " else "已禁用模块 ") + id
        } else {
            "操作失败：" + id
        }
        return AiExecutionOutcome(ok, text, undoable = ok)
    }

    private suspend fun installModule(path: String): AiExecutionOutcome {
        val quoted = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val ok = runCatching { execKsud("module install " + quoted, true) }.getOrDefault(false)
        audit.append(
            AiAuditEntry(
                ts = System.currentTimeMillis(),
                kind = KIND_INSTALL,
                target = path,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
            ),
        )
        val text = if (ok) "已安装模块（可能需要重启生效）：" + path else "模块安装失败：" + path
        return AiExecutionOutcome(ok, text)
    }

    /**
     * The model wrote this module itself. Validate it first so a fixable mistake comes back as a
     * concrete Chinese reason instead of an opaque failed build, then package and install it.
     */
    private suspend fun makeModule(action: AiAction.MakeModule): AiExecutionOutcome {
        val draft = action.draft
        val errors = ModuleDraftValidator.validate(draft)
        if (errors.isNotEmpty()) {
            return AiExecutionOutcome(false, ModuleMaker.describeErrors(errors))
        }
        val result = ModuleMaker.buildAndInstall(draft, action.install)
        audit.append(
            AiAuditEntry(
                ts = System.currentTimeMillis(),
                kind = KIND_MAKE_MODULE,
                target = draft.id,
                result = if (result.ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
            ),
        )
        val body = buildString {
            appendLine(result.message)
            if (result.output.isNotBlank()) {
                appendLine("安装器输出：")
                appendLine(result.output)
            }
            append("模块安装在 /data/adb/modules_update 下，重启后生效；系统模块目录尚未被这一动作改动。")
        }.trim()
        val text = if (body.length <= MAX_OUTPUT) body else body.substring(0, MAX_OUTPUT) + "\n[系统已截断]"
        return AiExecutionOutcome(result.ok, text)
    }

    private suspend fun moveToTrash(path: String): AiExecutionOutcome {
        val source = quote(path) ?: return AiExecutionOutcome(false, "路径包含非法字符，已拒绝")
        val stamp = System.currentTimeMillis().toString()
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { "unnamed" }
        val dir = TRASH_ROOT + "/" + stamp
        val target = dir + "/" + name
        val io = shell("mkdir -p '" + dir + "' && mv " + source + " '" + target + "'")
        val ok = io.code == 0
        if (ok) {
            audit.append(
                AiAuditEntry(
                    ts = System.currentTimeMillis(),
                    kind = KIND_TRASH,
                    target = path,
                    before = path,
                    after = target,
                    result = AiAuditEntry.RESULT_OK,
                    undoable = true,
                ),
            )
        }
        val text = if (ok) "已移入回收站：" + target else "移动失败：" + cap(io.err.ifBlank { io.out })
        return AiExecutionOutcome(ok, text, undoable = ok)
    }

    private fun packageUid(packageName: String): Int? = runCatching {
        ksuApp.packageManager.getPackageInfo(packageName, 0)?.applicationInfo?.uid
    }.getOrNull()

    /** Single quoted argument; null when the path cannot be quoted safely. */
    private fun quote(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(39.toChar()) || trimmed.contains(10.toChar()) || trimmed.contains(13.toChar())) return null
        return 39.toChar() + trimmed + 39.toChar()
    }

    private fun shell(command: String): ShellIo {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = getRootShell().newJob().add(command).to(stdout, stderr).exec()
        val newline = 10.toChar().toString()
        return ShellIo(result.code, stdout.joinToString(newline).trim(), stderr.joinToString(newline).trim())
    }

    /**
     * Output cap. The marker names the real size, because the model reads this text back as a tool
     * result: without it, a truncated file looks like the whole file and invites made up conclusions.
     */
    private fun cap(text: String, limit: Int = MAX_OUTPUT): String {
        if (text.length <= limit) return text
        val size = kb(text.length.toLong())
        return text.substring(0, limit) +
            newline() + "[系统已截断] 原始内容约 " + size + " KB（" + text.length + " 字符），上文仅为前 " + limit +
            " 字符。"
    }

    private fun newline(): String = 10.toChar().toString()

    /** Rounded KB for a byte or char count, used in the messages the model reads back. */
    private fun kb(value: Long): Long = (value + 1023L) / 1024L
}
