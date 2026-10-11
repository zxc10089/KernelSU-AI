package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiActionExecutorImpl
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONObject

/**
 * Family ⑦ 文件与脚本 — the only tools that write to the filesystem, and the reason the audit trail
 * exists.
 *
 * Three decisions are baked in. Content never reaches the shell as text (it travels as base64).
 * `delete_file` defaults to the trash directory, which is why the ordinary call is STRICT and
 * undoable while only `force=true` becomes FATAL. And a write records the before/after digest, so
 * "undo" means "put the bytes back", not "hope for the best".
 */
object AiFileTools {

    suspend fun writeFile(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val path = args.str("path")
        val content = args.str("content")
        val backup = args.bool("backup", true)
        val quoted = ctx.quote(path) ?: return toolFailed(TOOL_WRITE, "invalid_path")
        val existed = ctx.shell("test -e $quoted && echo yes || echo no").out.trim() == "yes"
        val beforeDigest = if (existed) ctx.md5Of(path) else null

        var backupPath: String? = null
        if (backup && existed) {
            val candidate = path + ".bak." + ctx.now()
            val quotedBackup = ctx.quote(candidate)
            if (quotedBackup != null && ctx.shell("cp -f $quoted $quotedBackup").code == 0) {
                backupPath = candidate
            }
        }

        val io = ctx.writeFileBase64(path, content)
        if (io.code != 0) return toolFailed(TOOL_WRITE, "write_failed", io.asText(300))
        val afterDigest = ctx.md5Of(path)
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_FILE_WRITE,
                target = path,
                before = beforeDigest ?: ABSENT,
                after = afterDigest,
                command = backupPath,
                // Reversible when a fresh file can be deleted, or when the backup of an overwrite
                // is available; an overwrite with backup=false has nothing to put back.
                undoable = backupPath != null || !existed,
            )
        )
        val data = JSONObject()
            .put("path", path)
            .put("mode", if (existed) "overwrite" else "create")
            .put("bytes", content.toByteArray(Charsets.UTF_8).size)
            .put("md5Before", beforeDigest ?: JSONObject.NULL)
            .put("md5After", afterDigest ?: JSONObject.NULL)
            .put("backupPath", backupPath ?: JSONObject.NULL)
        return toolOutcome(TOOL_WRITE, true, data = data)
    }

    suspend fun moveFile(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val source = args.str("source")
        val destination = args.str("destination")
        val from = ctx.quote(source) ?: return toolFailed(TOOL_MOVE, "invalid_source")
        val to = ctx.quote(destination) ?: return toolFailed(TOOL_MOVE, "invalid_destination")
        val parent = ctx.quote(destination.substringBeforeLast('/', "/"))
        val command = if (parent != null) "mkdir -p $parent && mv -f $from $to" else "mv -f $from $to"
        val io = ctx.shell(command)
        if (io.code != 0) return toolFailed(TOOL_MOVE, "move_failed", io.asText(300))
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_FILE_MOVE,
                target = source,
                before = source,
                after = destination,
                command = "mv -f $source $destination",
                undoable = true,
            )
        )
        return toolOutcome(
            TOOL_MOVE,
            true,
            data = JSONObject().put("source", source).put("destination", destination),
        )
    }

    suspend fun deleteFile(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val path = args.str("path")
        val force = args.bool("force", false)
        val quoted = ctx.quote(path) ?: return toolFailed(TOOL_DELETE, "invalid_path")
        if (force) {
            val io = ctx.shell("rm -rf $quoted")
            val ok = io.code == 0
            ctx.audit(
                AiAuditEntry(
                    ts = ctx.now(),
                    kind = AiAuditEntry.KIND_FILE_DELETE,
                    target = path,
                    before = "present",
                    after = "deleted",
                    command = "rm -rf $path",
                    result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                    undoable = false,
                )
            )
            if (!ok) return toolFailed(TOOL_DELETE, "delete_failed", io.asText(300))
            return toolOutcome(
                TOOL_DELETE,
                true,
                data = JSONObject().put("path", path).put("force", true).put("undoable", false),
                message = "已永久删除，此操作不可撤销。",
            )
        }

        val trash = AiActionExecutorImpl.TRASH_ROOT
        val quotedTrash = ctx.quote(trash) ?: return toolFailed(TOOL_DELETE, "trash_unavailable")
        val stamp = ctx.now()
        val name = path.substringAfterLast('/').ifBlank { "entry" }
        val trashed = "$trash/$stamp-$name"
        val quotedTarget = ctx.quote(trashed) ?: return toolFailed(TOOL_DELETE, "trash_unavailable")
        val io = ctx.shell("mkdir -p $quotedTrash && mv -f $quoted $quotedTarget")
        if (io.code != 0) return toolFailed(TOOL_DELETE, "move_to_trash_failed", io.asText(300))
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiActionExecutorImpl.KIND_TRASH,
                target = path,
                before = path,
                after = trashed,
                command = "mv -f $path $trashed",
                undoable = true,
            )
        )
        return toolOutcome(
            TOOL_DELETE,
            true,
            data = JSONObject()
                .put("path", path)
                .put("force", false)
                .put("trashedTo", trashed)
                .put("undoable", true),
            message = "已移入回收站，可在审计页一键撤销。",
        )
    }

    suspend fun checkFileIntegrity(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val path = args.str("path")
        val algorithm = args.str("algorithm", "md5").lowercase()
        val expected = args.optStr("expected")
        val quoted = ctx.quote(path) ?: return toolFailed(TOOL_INTEGRITY, "invalid_path")
        val binary = if (algorithm == "sha256") "sha256sum" else "md5sum"
        val io = ctx.shell("test -e $quoted && $binary $quoted 2>/dev/null | cut -d' ' -f1 || echo __missing__")
        val value = io.out.trim()
        if (value == "__missing__" || value.isEmpty()) {
            return toolFailed(TOOL_INTEGRITY, "file_not_found", path)
        }
        val data = JSONObject()
            .put("path", path)
            .put("algorithm", algorithm)
            .put("hash", value)
            .put("expected", expected ?: JSONObject.NULL)
            .put("matches", if (expected == null) JSONObject.NULL else value.equals(expected, ignoreCase = true))
        return toolOutcome(TOOL_INTEGRITY, true, data = data)
    }

    suspend fun createDirectory(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val path = args.str("path")
        val quoted = ctx.quote(path) ?: return toolFailed(TOOL_MKDIR, "invalid_path")
        val existed = ctx.shell("test -d $quoted && echo yes || echo no").out.trim() == "yes"
        val io = ctx.shell("mkdir -p $quoted")
        if (io.code != 0) return toolFailed(TOOL_MKDIR, "mkdir_failed", io.asText(300))
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_MKDIR,
                target = path,
                before = if (existed) "present" else ABSENT,
                after = "created",
                undoable = !existed,
            )
        )
        return toolOutcome(TOOL_MKDIR, true, data = JSONObject().put("path", path).put("created", !existed))
    }

    private const val TOOL_WRITE = "write_file"
    private const val TOOL_MOVE = "move_file"
    private const val TOOL_DELETE = "delete_file"
    private const val TOOL_INTEGRITY = "check_file_integrity"
    private const val TOOL_MKDIR = "create_directory"
    private const val ABSENT = "absent"
}
