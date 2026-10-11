package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONObject

/**
 * Family ④ 高级模块与自动化 — reading module scripts, comparing two modules, backup/restore,
 * template generation, archive extraction and integrity checks.
 *
 * `restore_module` is the dangerous one, and it is written defensively: before it touches a module
 * directory it archives whatever is already there, and it records that safety archive in the audit
 * trail so "undo" has somewhere real to go back to. `generate_module_template` runs no shell at all -
 * it only produces text, and installing that text is a separate, gated step the user approves.
 */
object AiModuleTools {

    suspend fun getModuleScripts(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val id = args.str("module_id")
        val dir = MODULE_ROOT + "/" + id
        val quotedDir = ctx.quote(dir) ?: return toolFailed(TOOL_SCRIPTS, "invalid_module_id")
        val commands = ArrayList<String>()
        commands.add("echo @@listing; ls -la $quotedDir 2>&1 | head -n 60")
        for (name in SCRIPT_NAMES) {
            val file = dir + "/" + name
            val quotedFile = ctx.quote(file) ?: continue
            commands.add(
                "echo @@script:$name; if [ -f $quotedFile ]; then head -c 8000 $quotedFile; " +
                    "else echo __missing__; fi"
            )
        }
        val io = ctx.shell(commands.joinToString("; "))
        val blocks = splitBlocks(io.out, "@@")
        val listing = blocks["listing"].orEmpty()
        val exists = listing.isNotEmpty() && !listing.first().contains("No such file")
        if (!exists) return toolFailed(TOOL_SCRIPTS, "module_not_found", dir)
        val scripts = JSONObject()
        val missing = ArrayList<String>()
        for (name in SCRIPT_NAMES) {
            val body = blocks["script:$name"].orEmpty()
            if (body.isEmpty() || body.first() == "__missing__") {
                missing.add(name)
            } else {
                scripts.put(name, truncate(body.joinToString("\n"), MAX_SCRIPT_CHARS))
            }
        }
        val data = JSONObject()
            .put("moduleId", id)
            .put("directory", dir)
            .put("listing", jsonArray(listing))
            .put("scripts", scripts)
            .put("missing", jsonArray(missing))
            .put("disabled", listing.any { it.endsWith(" disable") || it.contains(" disable") })
        return toolOutcome(TOOL_SCRIPTS, true, data = data)
    }

    suspend fun compareModules(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val a = args.str("module_a")
        val b = args.str("module_b")
        val dirA = MODULE_ROOT + "/" + a
        val dirB = MODULE_ROOT + "/" + b
        val qa = ctx.quote(dirA) ?: return toolFailed(TOOL_COMPARE, "invalid_module_a")
        val qb = ctx.quote(dirB) ?: return toolFailed(TOOL_COMPARE, "invalid_module_b")
        val commands = ArrayList<String>()
        commands.add("echo @@filesA; ls -R $qa 2>&1 | head -n 200")
        commands.add("echo @@filesB; ls -R $qb 2>&1 | head -n 200")
        commands.add("echo @@prop; diff -u $qa/module.prop $qb/module.prop 2>&1 | head -n 120")
        for (name in SCRIPT_NAMES) {
            if (name == "module.prop") continue
            val qfa = ctx.quote(dirA + "/" + name) ?: continue
            val qfb = ctx.quote(dirB + "/" + name) ?: continue
            commands.add("echo @@script:$name; diff -u $qfa $qfb 2>&1 | head -n 120")
        }
        val io = ctx.shell(commands.joinToString("; "))
        val blocks = splitBlocks(io.out, "@@")
        val filesA = blocks["filesA"].orEmpty()
        val filesB = blocks["filesB"].orEmpty()
        if (filesA.firstOrNull()?.contains("No such file") == true) {
            return toolFailed(TOOL_COMPARE, "module_not_found", dirA)
        }
        if (filesB.firstOrNull()?.contains("No such file") == true) {
            return toolFailed(TOOL_COMPARE, "module_not_found", dirB)
        }
        val scriptDiffs = JSONObject()
        for (name in SCRIPT_NAMES) {
            if (name == "module.prop") continue
            val diff = blocks["script:$name"].orEmpty()
            if (diff.isEmpty()) continue
            scriptDiffs.put(name, truncate(diff.joinToString("\n"), MAX_DIFF_CHARS))
        }
        val data = JSONObject()
            .put("moduleA", a)
            .put("moduleB", b)
            .put("filesA", jsonArray(filesA))
            .put("filesB", jsonArray(filesB))
            .put("propDiff", truncate(blocks["prop"].orEmpty().joinToString("\n"), MAX_DIFF_CHARS))
            .put("scriptDiffs", scriptDiffs)
            .put(
                "note",
                "diff 输出为空表示对应脚本在两个模块中完全一致；只在一个模块里存在的脚本不会被对比。"
            )
        return toolOutcome(TOOL_COMPARE, true, data = data)
    }

    suspend fun backupModule(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val id = args.str("module_id")
        val dir = MODULE_ROOT + "/" + id
        val quotedDir = ctx.quote(dir) ?: return toolFailed(TOOL_BACKUP, "invalid_module_id")
        val quotedRoot = ctx.quote(MODULE_ROOT) ?: return toolFailed(TOOL_BACKUP, "module_root_unavailable")
        val exists = ctx.shell("test -d $quotedDir && echo yes || echo no").out.trim() == "yes"
        if (!exists) return toolFailed(TOOL_BACKUP, "module_not_found", dir)
        val archive = BACKUP_ROOT + "/" + id + "-" + ctx.now() + ".tar.gz"
        val quotedBackupDir = ctx.quote(BACKUP_ROOT) ?: return toolFailed(TOOL_BACKUP, "backup_root_unavailable")
        val quotedArchive = ctx.quote(archive) ?: return toolFailed(TOOL_BACKUP, "invalid_module_id")
        val quotedId = ctx.quote(id) ?: return toolFailed(TOOL_BACKUP, "invalid_module_id")
        val io = ctx.shell(
            "mkdir -p $quotedBackupDir && tar -czf $quotedArchive -C $quotedRoot $quotedId && " +
                "stat -c %s $quotedArchive"
        )
        if (io.code != 0) return toolFailed(TOOL_BACKUP, "backup_failed", io.asText(300))
        val bytes = io.out.trim().lines().lastOrNull()?.trim()?.toLongOrNull()
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_MODULE_BACKUP,
                target = id,
                before = dir,
                after = archive,
                undoable = true,
            )
        )
        return toolOutcome(
            TOOL_BACKUP,
            true,
            data = JSONObject().put("moduleId", id).put("archive", archive).put("sizeBytes", bytes ?: JSONObject.NULL),
        )
    }

    suspend fun restoreModule(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val id = args.str("module_id")
        val backupPath = args.str("backup_path")
        val dir = MODULE_ROOT + "/" + id
        val quotedDir = ctx.quote(dir) ?: return toolFailed(TOOL_RESTORE, "invalid_module_id")
        val quotedRoot = ctx.quote(MODULE_ROOT) ?: return toolFailed(TOOL_RESTORE, "module_root_unavailable")
        val quotedBackup = ctx.quote(backupPath) ?: return toolFailed(TOOL_RESTORE, "invalid_backup_path")
        val archiveOk = ctx.shell("test -f $quotedBackup && echo yes || echo no").out.trim() == "yes"
        if (!archiveOk) return toolFailed(TOOL_RESTORE, "backup_not_found", backupPath)

        val existed = ctx.shell("test -d $quotedDir && echo yes || echo no").out.trim() == "yes"
        var safety: String? = null
        if (existed) {
            val candidate = BACKUP_ROOT + "/restore-safety-" + id + "-" + ctx.now() + ".tar.gz"
            val quotedBackupDir = ctx.quote(BACKUP_ROOT)
            val quotedCandidate = ctx.quote(candidate)
            val quotedId = ctx.quote(id)
            if (quotedBackupDir != null && quotedCandidate != null && quotedId != null) {
                val code = ctx.shell(
                    "mkdir -p $quotedBackupDir && tar -czf $quotedCandidate -C $quotedRoot $quotedId"
                ).code
                if (code == 0) safety = candidate
            }
            if (safety == null) {
                return toolFailed(
                    TOOL_RESTORE,
                    "safety_backup_failed",
                    "无法为当前模块创建安全备份，已中止恢复以免丢失现场。"
                )
            }
        }

        val io = ctx.shell("rm -rf $quotedDir && tar -xzf $quotedBackup -C $quotedRoot && ls -la $quotedDir | head -n 40")
        val ok = io.code == 0
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_MODULE_RESTORE,
                target = id,
                before = safety ?: ABSENT,
                after = backupPath,
                command = "tar -xzf $backupPath -C $MODULE_ROOT",
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                // Re-extracting an archived module directory is not a one-tap reverse; the entry
                // states that instead of advertising an undo the page cannot perform.
                undoable = false,
            )
        )
        if (!ok) return toolFailed(TOOL_RESTORE, "restore_failed", io.asText(400))
        return toolOutcome(
            TOOL_RESTORE,
            true,
            data = JSONObject()
                .put("moduleId", id)
                .put("restoredFrom", backupPath)
                .put("safetyArchive", safety ?: JSONObject.NULL)
                .put("listing", jsonArray(io.out.trim().lines())),
            message = "如与预期不符，可在审计页一键撤销（将回到恢复前的状态）。"
        )
    }

    suspend fun generateModuleTemplate(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val type = args.str("type", "basic").lowercase()
        val id = args.str("id")
        val name = args.optStr("name")?.takeIf { it.isNotBlank() } ?: id
        val description = args.optStr("description").orEmpty()
        val files = linkedMapOf<String, String>()
        files["module.prop"] = buildString {
            append("id=").append(id).append('\n')
            append("name=").append(name).append('\n')
            append("version=1.0").append('\n')
            append("versionCode=1").append('\n')
            append("author=KernelSU AI").append('\n')
            append("description=").append(description.ifBlank { name }).append('\n')
        }
        files["customize.sh"] = "#!/system/bin/sh\n" +
            "# 安装时执行一次。失败会中断安装，保持简短。\n" +
            "ui_print \"- installing " + id + "\"\n"
        files["service.sh"] = "#!/system/bin/sh\n" +
            "# 开机较晚阶段执行，适合常驻任务。\n" +
            "MODDIR=\${0%/*}\n" +
            "# 在此启动你的服务，例如：\n" +
            "# \"\$MODDIR/bin/daemon\" &\n"
        val notes = ArrayList<String>()
        when (type) {
            "systemless" -> {
                files["post-fs-data.sh"] = "#!/system/bin/sh\n" +
                    "# /data 尚未完全就绪，只做必要的挂载与属性写入。\n"
                files["README.md"] = "把要覆盖的文件放在模块的 system/ 目录下，路径与设备上一致。\n" +
                    "KernelSU 会把它们挂载到 /system、/vendor 等位置。\n"
                notes.add("systemless 模板通过 system/ 目录镜像文件，卸载后原文件自动恢复。")
            }
            "app_replace" -> {
                files["README.md"] = "把替换用的 apk 放到 system/app/<包名>/base.apk。\n" +
                    "模块会被挂载为系统应用，因此可以获取该目录的 SELinux 上下文。\n"
                files["system/app/.keep"] = ""
                notes.add("替换系统应用需要与设备签名兼容，安装前请确认 apk 与目标设备的签名策略。")
            }
            "overlay" -> {
                files["README.md"] = "把 RRO overlay apk 放到 system/overlay/ 下。\n" +
                    "overlay 不修改原始 apk，冲突风险最低。\n"
                files["system/overlay/.keep"] = ""
                notes.add("overlay 需要目标应用声明可覆盖的资源；不会改变原始 apk 的签名。")
            }
            else -> {
                files["post-fs-data.sh"] = "#!/system/bin/sh\n" +
                    "# /data 尚未完全就绪，只做必要的挂载与属性写入。\n"
                notes.add("basic 模板只包含 module.prop 与两个生命周期脚本，适合从零开始。")
            }
        }
        val payload = JSONObject()
        for ((path, content) in files) payload.put(path, content)
        val data = JSONObject()
            .put("type", type)
            .put("id", id)
            .put("name", name)
            .put("files", payload)
            .put("notes", jsonArray(notes))
            .put(
                "next",
                "确认内容后可用 make_module 直接写入并安装，或自行保存后手动打包。"
            )
        return toolOutcome(TOOL_TEMPLATE, true, data = data)
    }

    suspend fun extractArchive(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val archive = args.str("path")
        val subdir = args.optStr("subdir")
        val quoted = ctx.quote(archive) ?: return toolFailed(TOOL_EXTRACT, "invalid_path")
        val exists = ctx.shell("test -f $quoted && echo yes || echo no").out.trim() == "yes"
        if (!exists) return toolFailed(TOOL_EXTRACT, "archive_not_found", archive)
        val name = archive.substringAfterLast('/').substringBeforeLast('.').ifBlank { "archive" }
        val target = EXTRACT_ROOT + "/" + name + (if (subdir.isNullOrBlank()) "" else "/" + subdir)
        val quotedTarget = ctx.quote(target) ?: return toolFailed(TOOL_EXTRACT, "invalid_target")
        val io = ctx.shell(
            "mkdir -p $quotedTarget && " +
                "(unzip -o $quoted -d $quotedTarget >/dev/null 2>&1 || tar -xf $quoted -C $quotedTarget 2>&1) && " +
                "ls -laR $quotedTarget 2>/dev/null | head -n 250"
        )
        if (io.code != 0) return toolFailed(TOOL_EXTRACT, "extract_failed", io.asText(400))
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_ARCHIVE_EXTRACT,
                target = archive,
                before = ABSENT,
                after = target,
                command = "tar -xf $archive -C $target",
                // Undoing an extraction would mean deleting a tree, which is the job of an explicit
                // delete_file the user approves - not of a one-tap undo.
                undoable = false,
            )
        )
        return toolOutcome(
            TOOL_EXTRACT,
            true,
            data = JSONObject()
                .put("archive", archive)
                .put("targetDir", target)
                .put("listing", jsonArray(io.out.trim().lines().take(250))),
        )
    }

    suspend fun checkModuleIntegrity(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val id = args.str("module_id")
        val dir = MODULE_ROOT + "/" + id
        val quotedDir = ctx.quote(dir) ?: return toolFailed(TOOL_CHECK, "invalid_module_id")
        val quotedProp = ctx.quote(dir + "/module.prop") ?: return toolFailed(TOOL_CHECK, "invalid_module_id")
        val io = ctx.shell(
            "echo @@listing; ls -la $quotedDir 2>&1 | head -n 80; " +
                "echo @@prop; cat $quotedProp 2>&1 | head -n 40; " +
                "echo @@scripts; ls -la $quotedDir/*.sh 2>&1 | head -n 40"
        )
        val blocks = splitBlocks(io.out, "@@")
        val listing = blocks["listing"].orEmpty()
        if (listing.isEmpty() || listing.first().contains("No such file")) {
            return toolFailed(TOOL_CHECK, "module_not_found", dir)
        }
        val propLines = blocks["prop"].orEmpty()
        val prop = JSONObject()
        for (line in propLines) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            prop.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        val issues = ArrayList<Any?>()
        if (propLines.isEmpty() || propLines.first().contains("No such file")) {
            issues.add(issue("blocker", "缺少 module.prop", "模块无法被 KernelSU 识别"))
        } else {
            if (prop.optString("id") != id) {
                issues.add(
                    issue("high", "module.prop 的 id 与目录名不一致", "id=" + prop.optString("id") + " 目录=" + id)
                )
            }
            if (prop.optString("version").isBlank()) issues.add(issue("medium", "缺少 version", "版本号将显示为空"))
            if (prop.optString("name").isBlank()) issues.add(issue("low", "缺少 name", "模块名将显示为目录名"))
        }
        for (line in blocks["scripts"].orEmpty()) {
            if (line.contains("No such file")) continue
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val mode = parts.getOrNull(0).orEmpty()
            val file = parts.lastOrNull().orEmpty()
            if (mode.length >= 10 && mode[3] != 'x' && mode[6] != 'x' && mode[9] != 'x') {
                issues.add(issue("medium", "脚本没有执行权限", file + " 权限=" + mode))
            }
        }
        val disabled = listing.any { it.contains(" disable") }
        val data = JSONObject()
            .put("moduleId", id)
            .put("directory", dir)
            .put("prop", prop)
            .put("disabled", disabled)
            .put("issues", jsonArray(issues))
            .put("issueCount", issues.size)
            .put(
                "note",
                if (disabled) "模块处于停用状态（存在 disable 文件），不会被加载。" else "未发现结构性损坏。"
            )
        return toolOutcome(TOOL_CHECK, true, data = data)
    }

    /**
     * Stages a module zip the user downloaded on shared storage and hands it to ksud, so a module that
     * arrived through a browser or a chat can be installed without the AI issuing rm/mv on /sdcard. The
     * original zip is left alone; the only write is the staged copy, and the install itself is ksud's,
     * the same path the Modules screen uses.
     */
    suspend fun importModuleFromSdcard(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val source = args.str("path")
        if (!source.startsWith(SDCARD_ROOT)) return toolFailed(TOOL_IMPORT, "source_outside_sdcard", source)
        if (!source.endsWith(".zip", ignoreCase = true)) return toolFailed(TOOL_IMPORT, "not_a_zip", source)
        val quoted = ctx.quote(source) ?: return toolFailed(TOOL_IMPORT, "invalid_path")
        val size = ctx.shell("stat -c %s $quoted 2>/dev/null || echo 0").out.trim().toLongOrNull() ?: 0L
        if (size <= 0L) return toolFailed(TOOL_IMPORT, "source_not_found", source)
        if (size > MAX_IMPORT_BYTES) return toolFailed(TOOL_IMPORT, "source_too_large", size.toString())
        val name = source.substringAfterLast('/').ifBlank { "module.zip" }
        val staged = STAGING_ROOT + "/" + name
        val quotedStaging = ctx.quote(STAGING_ROOT) ?: return toolFailed(TOOL_IMPORT, "invalid_target")
        val quotedStaged = ctx.quote(staged) ?: return toolFailed(TOOL_IMPORT, "invalid_target")
        val copy = ctx.shell(
            "mkdir -p $quotedStaging && cp -f $quoted $quotedStaged && test -s $quotedStaged && echo staged"
        )
        if (copy.code != 0 || !copy.out.contains("staged")) {
            return toolFailed(TOOL_IMPORT, "stage_failed", copy.asText(400))
        }
        // /data/adb/ksud is where the manager keeps it; the PATH lookup is only a convenience.
        val ksud = ctx.shell("command -v ksud 2>/dev/null || { test -x $KSUD_FALLBACK && echo $KSUD_FALLBACK; }")
            .out.trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()
        if (ksud.isBlank()) return toolFailed(TOOL_IMPORT, "ksud_missing")
        val quotedKsud = ctx.quote(ksud) ?: return toolFailed(TOOL_IMPORT, "ksud_missing")
        val command = "$quotedKsud module install $quotedStaged"
        val install = ctx.shell("$command 2>&1 | tail -n 40")
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_MODULE_IMPORT,
                target = source,
                before = ABSENT,
                after = staged,
                command = command,
                result = if (install.code == 0) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                // Removing the staged copy would not uninstall anything, so this never claims a reverse.
                undoable = false,
            )
        )
        if (install.code != 0) return toolFailed(TOOL_IMPORT, "install_failed", install.asText(400))
        return toolOutcome(
            TOOL_IMPORT,
            true,
            data = JSONObject()
                .put("source", source)
                .put("staged", staged)
                .put("bytes", size)
                .put("output", install.out.trim().take(MAX_DIFF_CHARS))
                .put("note", "模块已交给 ksud 安装；重启后生效。"),
        )
    }

    private fun issue(severity: String, problem: String, detail: String): JSONObject =
        JSONObject().put("severity", severity).put("problem", problem).put("detail", detail)

    private const val MODULE_ROOT = "/data/adb/modules"
    private const val BACKUP_ROOT = "/data/adb/.ai_backups"
    private const val EXTRACT_ROOT = "/data/adb/.ai_extract"
    private const val STAGING_ROOT = "/data/adb/AI/staging"
    private const val SDCARD_ROOT = "/sdcard/"
    private const val KSUD_FALLBACK = "/data/adb/ksud"
    private const val ABSENT = "absent"
    private const val MAX_SCRIPT_CHARS = 6000
    private const val MAX_DIFF_CHARS = 4000
    private const val MAX_IMPORT_BYTES = 200L * 1024 * 1024

    private const val TOOL_SCRIPTS = "get_module_scripts"
    private const val TOOL_COMPARE = "compare_modules"
    private const val TOOL_BACKUP = "backup_module"
    private const val TOOL_RESTORE = "restore_module"
    private const val TOOL_TEMPLATE = "generate_module_template"
    private const val TOOL_EXTRACT = "extract_archive"
    private const val TOOL_CHECK = "check_module_integrity"
    private const val TOOL_IMPORT = "import_module_from_sdcard"

    private val SCRIPT_NAMES = listOf(
        "module.prop",
        "customize.sh",
        "service.sh",
        "post-fs-data.sh",
        "boot-completed.sh",
        "uninstall.sh",
        "system.prop",
        "sepolicy.rule",
        "disable",
    )
}
