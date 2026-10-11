package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * Family ⑤ 自动化与调度 — KernelSU's own boot hook plus the AI schedule registry.
 *
 * Android has no cron. What it does have, and what KernelSU documents, is `/data/adb/service.d/`:
 * a shell script there runs at boot as root. So scheduling is built on that fact instead of being
 * pretended: `tasks.json` is the registry, and `ai-scheduler.sh` is *generated* from it into the boot
 * directory, with the cron expression compiled into explicit minute/hour/day gates. That makes the
 * schedule inspectable text rather than a runtime interpreter that might silently never fire.
 */
object AiAutomationTools {

    suspend fun listScheduledTasks(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val quotedTasks = ctx.quote(TASKS_FILE) ?: return toolFailed(TOOL_LIST, "internal_path_error")
        val quotedDir = ctx.quote(SCHEDULE_DIR) ?: return toolFailed(TOOL_LIST, "internal_path_error")
        val quotedBoot = ctx.quote(BOOT_DIR) ?: return toolFailed(TOOL_LIST, "internal_path_error")
        val io = ctx.shell(
            "echo @@tasks; cat $quotedTasks 2>/dev/null; " +
                "echo @@dir; ls -la $quotedDir 2>&1 | head -n 40; " +
                "echo @@boot; ls -la $quotedBoot 2>&1 | head -n 40"
        )
        val blocks = splitBlocks(io.out, "@@")
        val tasks = readTasks(blocks["tasks"].orEmpty().joinToString("\n"))
        val dispatcher = ctx.shell("test -f " + (ctx.quote(DISPATCHER) ?: "''") + " && echo yes || echo no")
            .out.trim() == "yes"
        val data = JSONObject()
            .put("tasks", tasks)
            .put("count", tasks.length())
            .put("tasksFile", TASKS_FILE)
            .put("tasksFileExists", blocks["tasks"].orEmpty().isNotEmpty())
            .put("dispatcherInstalled", dispatcher)
            .put("dispatcherPath", DISPATCHER)
            .put("scheduleDir", jsonArray(blocks["dir"].orEmpty()))
            .put("bootDir", jsonArray(blocks["boot"].orEmpty()))
            .put(
                "note",
                "调度由 /data/adb/service.d/ai-scheduler.sh 在开机后每分钟检查一次；" +
                    "服务脚本必须以 #!/system/bin/sh 开头并在 root 下可执行。"
            )
        return toolOutcome(TOOL_LIST, true, data = data)
    }

    suspend fun scheduleTask(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val id = args.str("id")
        val expression = args.str("cron_expression")
        val scriptPath = args.str("script_path")
        AiCron.validate(expression)?.let { return toolFailed(TOOL_SCHEDULE, "invalid_cron", it) }
        val quotedScript = ctx.quote(scriptPath) ?: return toolFailed(TOOL_SCHEDULE, "invalid_script_path")
        val scriptOk = ctx.shell("test -f $quotedScript && echo yes || echo no").out.trim() == "yes"
        if (!scriptOk) return toolFailed(TOOL_SCHEDULE, "script_not_found", scriptPath)

        val tasks = readTasks(readTasksFile(ctx))
        val entry = JSONObject()
            .put("id", id)
            .put("cron", expression.trim())
            .put("script", scriptPath)
            .put("createdAt", ctx.now())
            .put("enabled", true)
        var replaced = false
        for (i in 0 until tasks.length()) {
            if (tasks.optJSONObject(i)?.optString("id") == id) {
                tasks.put(i, entry)
                replaced = true
                break
            }
        }
        if (!replaced) tasks.put(entry)

        val write = writeSchedule(ctx, tasks)
        if (write != null) return write
        return toolOutcome(
            TOOL_SCHEDULE,
            true,
            data = JSONObject()
                .put("id", id)
                .put("cron", expression.trim())
                .put("script", scriptPath)
                .put("replaced", replaced)
                .put("totalTasks", tasks.length())
                .put("dispatcherPath", DISPATCHER),
            message = "已写入调度表并更新开机调度脚本；下一次开机后生效（也可重启后立即验证）。"
        )
    }

    suspend fun cancelTask(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val taskId = args.str("task_id")
        val before = readTasksFile(ctx)
        val tasks = readTasks(before)
        var removed = false
        val kept = JSONArray()
        for (i in 0 until tasks.length()) {
            val item = tasks.optJSONObject(i) ?: continue
            if (item.optString("id") == taskId) {
                removed = true
                continue
            }
            kept.put(item)
        }
        if (!removed) return toolFailed(TOOL_CANCEL, "task_not_found", taskId)
        val write = writeSchedule(ctx, kept)
        if (write != null) return write
        return toolOutcome(
            TOOL_CANCEL,
            true,
            data = JSONObject()
                .put("taskId", taskId)
                .put("remaining", kept.length())
                .put("dispatcherPath", DISPATCHER),
        )
    }

    suspend fun setBootScript(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val name = args.str("name")
        val content = args.str("script_content")
        val overwrite = args.bool("overwrite", false)
        val path = BOOT_DIR + "/" + name + ".sh"
        val quoted = ctx.quote(path) ?: return toolFailed(TOOL_BOOT, "invalid_name")
        val existed = ctx.shell("test -f $quoted && echo yes || echo no").out.trim() == "yes"
        if (existed && !overwrite) {
            return toolFailed(TOOL_BOOT, "already_exists", "开机脚本已存在；如需覆盖请传 overwrite=true")
        }
        val before = if (existed) ctx.shell("base64 $quoted 2>/dev/null | tr -d '\\n'").out.trim() else ABSENT
        val io = ctx.writeFileBase64(path, content)
        if (io.code != 0) return toolFailed(TOOL_BOOT, "write_failed", io.asText(300))
        val chmod = ctx.shell("chmod 755 $quoted")
        if (chmod.code != 0) return toolFailed(TOOL_BOOT, "chmod_failed", chmod.asText(300))
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_SCHEDULE_WRITE,
                target = path,
                before = before,
                after = b64(content),
                command = "chmod 755 " + path,
                undoable = true,
            )
        )
        return toolOutcome(
            TOOL_BOOT,
            true,
            data = JSONObject().put("path", path).put("overwritten", existed).put("mode", "755"),
            message = "开机脚本已写入，将在下次开机时以 root 执行。"
        )
    }

    suspend fun getKsudLogs(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val lines = args.int("lines", 200).coerceIn(1, 1000)
        val io = ctx.shell(
            "echo @@files; ls -lt /data/adb/ksu/log 2>&1 | head -n 20; " +
                "echo @@tail; tail -n $lines /data/adb/ksu/log/*.log 2>&1 | tail -n $lines; " +
                "echo @@logcat; logcat -d -s ksud -t $lines 2>&1 | tail -n $lines"
        )
        val blocks = splitBlocks(io.out, "@@")
        val tail = blocks["tail"].orEmpty()
        val logcat = blocks["logcat"].orEmpty()
        if (tail.isEmpty() && logcat.isEmpty()) {
            return toolFailed(TOOL_KSUD_LOGS, "ksud_log_unavailable", io.asText(400))
        }
        val data = JSONObject()
            .put("files", jsonArray(blocks["files"].orEmpty()))
            .put("tail", jsonArray(tail))
            .put("logcat", jsonArray(logcat))
            .put("requestedLines", lines)
        return toolOutcome(TOOL_KSUD_LOGS, true, data = data)
    }

    // ---- schedule plumbing ---------------------------------------------------------------------

    private suspend fun readTasksFile(ctx: AiToolContext): String {
        val quoted = ctx.quote(TASKS_FILE) ?: return ""
        return ctx.shell("cat $quoted 2>/dev/null").out
    }

    private fun readTasks(text: String): JSONArray = runCatching {
        if (text.isBlank()) JSONArray() else JSONArray(text)
    }.getOrElse { JSONArray() }

    /**
     * Writes both halves of the schedule and audits each file separately, so an undo restores the two
     * files it actually changed instead of guessing. Returns a failure outcome when a write failed.
     */
    private suspend fun writeSchedule(
        ctx: AiToolContext,
        tasks: JSONArray,
    ): AiExecutionOutcome? {
        val beforeTasks = readTasksFile(ctx)
        val tasksJson = tasks.toString(2)
        val dispatcher = buildDispatcher(tasks)
        if (dispatcher == null) {
            return toolFailed(TOOL_SCHEDULE, "unsafe_script_path", "任务中的脚本路径包含无法写入调度脚本的字符")
        }

        val quotedTasks = ctx.quote(TASKS_FILE)
        val quotedDispatcher = ctx.quote(DISPATCHER)
        if (quotedTasks == null || quotedDispatcher == null) {
            return toolFailed(TOOL_SCHEDULE, "internal_path_error")
        }
        val beforeDispatcher = ctx.shell("cat $quotedDispatcher 2>/dev/null").out
        val beforeDispatcherB64 = ctx.shell("base64 $quotedDispatcher 2>/dev/null | tr -d '\\n'").out.trim()

        val tasksIo = ctx.writeFileBase64(TASKS_FILE, tasksJson)
        if (tasksIo.code != 0) return toolFailed(TOOL_SCHEDULE, "tasks_write_failed", tasksIo.asText(300))
        val dispatcherIo = ctx.writeFileBase64(DISPATCHER, dispatcher)
        if (dispatcherIo.code != 0) return toolFailed(TOOL_SCHEDULE, "dispatcher_write_failed", dispatcherIo.asText(300))
        ctx.shell("chmod 755 $quotedDispatcher")

        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_SCHEDULE_WRITE,
                target = TASKS_FILE,
                before = if (beforeTasks.isBlank()) ABSENT else b64(beforeTasks),
                after = b64(tasksJson),
                undoable = true,
            )
        )
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_SCHEDULE_WRITE,
                target = DISPATCHER,
                before = if (beforeDispatcher.isBlank()) ABSENT else beforeDispatcherB64.ifBlank { ABSENT },
                after = b64(dispatcher),
                command = "chmod 755 " + DISPATCHER,
                undoable = true,
            )
        )
        return null
    }

    /**
     * Compiles the registry into one boot script. A cron expression is expanded into explicit
     * `case` gates on the current minute/hour/day, so what will run is visible in the file itself.
     * Returns null when a task carries a path that cannot be embedded safely.
     */
    private fun buildDispatcher(tasks: JSONArray): String? {
        val sb = StringBuilder()
        sb.append("#!/system/bin/sh\n")
        sb.append("# Generated by the KernelSU AI console from ").append(TASKS_FILE).append(".\n")
        sb.append("# Do not edit by hand: rewrite the schedule from the AI console instead.\n")
        sb.append("DIR=").append(SCHEDULE_DIR).append('\n')
        sb.append("LOG=\$DIR/run.log\n")
        sb.append("STAMP=\"\"\n")
        sb.append("while true; do\n")
        sb.append("  NOW=$(date +%Y%m%d%H%M)\n")
        sb.append("  if [ \"\$STAMP\" != \"\$NOW\" ]; then\n")
        sb.append("    STAMP=\"\$NOW\"\n")
        sb.append("    M=$(date +%-M); H=$(date +%-H); D=$(date +%-d); MO=$(date +%-m); W=$(date +%w)\n")
        var emitted = 0
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            if (!task.optBoolean("enabled", true)) continue
            val id = task.optString("id")
            val cron = task.optString("cron")
            val script = task.optString("script")
            if (script.isEmpty() || script.any { it == '\'' || it == '\\' || it == '\n' || it == '\r' || it.code < 32 }) {
                return null
            }
            val schedule = AiCron.parse(cron) ?: continue
            sb.append("    # task ").append(id).append(" (").append(cron).append(")\n")
            sb.append("    f_m=1; ").append(gate("M", schedule.minutes)).append('\n')
            sb.append("    f_h=1; ").append(gate("H", schedule.hours)).append('\n')
            sb.append("    f_d=1; ").append(gate("D", schedule.daysOfMonth)).append('\n')
            sb.append("    f_o=1; ").append(gate("MO", schedule.months)).append('\n')
            sb.append("    f_w=1; ").append(gate("W", schedule.daysOfWeek)).append('\n')
            when {
                schedule.domRestricted && schedule.dowRestricted ->
                    sb.append("    f_day=0; [ \"\$f_d\" = 1 ] && f_day=1; [ \"\$f_w\" = 1 ] && f_day=1\n")
                schedule.domRestricted -> sb.append("    f_day=\$f_d\n")
                schedule.dowRestricted -> sb.append("    f_day=\$f_w\n")
                else -> sb.append("    f_day=1\n")
            }
            sb.append("    if [ \"\$f_m\" = 1 ] && [ \"\$f_h\" = 1 ] && [ \"\$f_o\" = 1 ] && [ \"\$f_day\" = 1 ]; then\n")
            sb.append("      '").append(script).append("' >> \"\$LOG\" 2>&1\n")
            sb.append("    fi\n")
            emitted++
        }
        sb.append("  fi\n")
        sb.append("  sleep 20\n")
        sb.append("done\n")
        if (emitted == 0) {
            sb.append("# (no enabled task)\n")
        }
        return sb.toString()
    }

    private fun gate(variable: String, values: Set<Int>): String {
        if (values.isEmpty()) return "f=0"
        val pattern = values.sorted().joinToString("|")
        return "case \"\$$variable\" in " + pattern + ") ;; *) f_place=0 ;; esac"
    }

    private const val SCHEDULE_DIR = "/data/adb/.ai_scheduler"
    private const val TASKS_FILE = SCHEDULE_DIR + "/tasks.json"
    private const val DISPATCHER = "/data/adb/service.d/ai-scheduler.sh"
    private const val BOOT_DIR = "/data/adb/service.d"
    private const val ABSENT = "absent"

    private const val TOOL_LIST = "list_scheduled_tasks"
    private const val TOOL_SCHEDULE = "schedule_task"
    private const val TOOL_CANCEL = "cancel_task"
    private const val TOOL_BOOT = "set_boot_script"
    private const val TOOL_KSUD_LOGS = "get_ksud_logs"
}
