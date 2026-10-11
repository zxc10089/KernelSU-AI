package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * Family ② 应用与权限 — listing apps, reading their metadata and permissions, freezing, wiping,
 * uninstalling and moving an app between the three root policies.
 *
 * Two rules shape this file. Package level facts (the list, versions, permissions) come from `pm` /
 * `dumpsys` because the manager cannot see every installed package itself - `QUERY_ALL_PACKAGES` is
 * deliberately not declared in the manifest. Root policy, on the other hand, never touches a shell at
 * all: it goes through [Natives] in process, exactly like the pre-existing grant/revoke path, so it
 * keeps working when shell execution is switched off.
 */
object AiAppTools {

    suspend fun getInstalledApps(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val type = args.str("type", "user").lowercase()
        val flag = when (type) {
            "system" -> " -s"
            "all" -> ""
            "disabled" -> " -d"
            else -> " -3"
        }
        val io = ctx.shell("pm list packages -f -U$flag 2>&1 | sort")
        val systemPackages = if (type == "all") {
            ctx.shell("pm list packages -s 2>/dev/null").out
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .toSet()
        } else {
            emptySet()
        }
        val apps = JSONArray()
        for (raw in io.out.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val apkPath = body.substring(0, eq)
            var rest = body.substring(eq + 1)
            var uid: Int? = null
            val uidAt = rest.indexOf(" uid:")
            if (uidAt >= 0) {
                uid = rest.substring(uidAt + 5).trim().toIntOrNull()
                rest = rest.substring(0, uidAt).trim()
            }
            val pkg = rest
            if (pkg.isEmpty()) continue
            val system = when (type) {
                "user" -> false
                "system" -> true
                else -> pkg in systemPackages
            }
            apps.put(
                JSONObject()
                    .put("packageName", pkg)
                    .put("apkPath", apkPath)
                    .put("uid", uid ?: JSONObject.NULL)
                    .put("system", system)
            )
            if (apps.length() >= MAX_APPS) break
        }
        if (apps.length() == 0 && !io.out.contains("package:")) {
            return toolFailed(TOOL_APPS, "package_list_unavailable", io.asText(300))
        }
        val data = JSONObject()
            .put("type", type)
            .put("count", apps.length())
            .put("truncated", apps.length() >= MAX_APPS)
            .put("apps", apps)
            .put(
                "note",
                "不含应用显示名与签名（本轮只做结构化列表，细节用 get_app_info 逐个查询）；" +
                    "type=disabled 时系统/第三方不会区分。"
            )
        return toolOutcome(TOOL_APPS, true, data = data)
    }

    suspend fun getAppInfo(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(TOOL_INFO, "invalid_package_name")
        val io = ctx.shell(
            "echo @@path; pm path $quoted 2>&1; " +
                "echo @@dump; dumpsys package $quoted 2>&1 | head -n 600"
        )
        val blocks = splitBlocks(io.out, "@@")
        val dump = blocks["dump"].orEmpty()
        if (dump.isEmpty() || dump.first().contains("Unable to find package")) {
            return toolFailed(TOOL_INFO, "package_not_found", pkg)
        }
        val text = dump.joinToString("\n")
        val paths = blocks["path"].orEmpty().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        val codePath = match(text, REGEX_CODE_PATH)
        val dataDir = match(text, REGEX_DATA_DIR)
        val sizes = JSONObject()
        val quotaPaths = ArrayList<String>()
        codePath?.substringBeforeLast('/')?.let { quotaPaths.add(it) }
        dataDir?.let { quotaPaths.add(it) }
        if (quotaPaths.isNotEmpty()) {
            val quotedPaths = quotaPaths.mapNotNull { ctx.quote(it) }
            if (quotedPaths.isNotEmpty()) {
                val du = ctx.shell("du -sk " + quotedPaths.joinToString(" ") + " 2>/dev/null")
                for (line in du.out.lineSequence()) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2) continue
                    val kb = parts[0].toLongOrNull() ?: continue
                    sizes.put(parts[1], kb * 1024L)
                }
            }
        }
        val signatureLines = dump.filter {
            it.contains("signatures=") || it.contains("SigningInfo") || it.contains("signature=")
        }.take(12)
        val data = JSONObject()
            .put("packageName", pkg)
            .put("apkPaths", jsonArray(paths))
            .put("versionName", match(text, REGEX_VERSION_NAME) ?: JSONObject.NULL)
            .put("versionCode", match(text, REGEX_VERSION_CODE)?.toIntOrNull() ?: JSONObject.NULL)
            .put("codePath", codePath ?: JSONObject.NULL)
            .put("dataDir", dataDir ?: JSONObject.NULL)
            .put("uid", match(text, REGEX_USER_ID)?.toIntOrNull() ?: JSONObject.NULL)
            .put("minSdk", match(text, REGEX_MIN_SDK)?.toIntOrNull() ?: JSONObject.NULL)
            .put("targetSdk", match(text, REGEX_TARGET_SDK)?.toIntOrNull() ?: JSONObject.NULL)
            .put("primaryCpuAbi", match(text, REGEX_CPU_ABI) ?: JSONObject.NULL)
            .put("installerPackageName", match(text, REGEX_INSTALLER) ?: JSONObject.NULL)
            .put("firstInstallTime", match(text, REGEX_FIRST_INSTALL) ?: JSONObject.NULL)
            .put("lastUpdateTime", match(text, REGEX_LAST_UPDATE) ?: JSONObject.NULL)
            .put("enabled", match(text, REGEX_ENABLED) ?: JSONObject.NULL)
            .put("disabled", isDisabled(ctx, pkg))
            .put("sizesBytes", sizes)
            .put("signatureLines", jsonArray(signatureLines))
            .put(
                "note",
                "signatureLines 是 dumpsys 的原始行；签名摘要在不同 Android 版本上格式不同，" +
                    "不能只凭这几行判断签名可信度。"
            )
        return toolOutcome(TOOL_INFO, true, data = data)
    }

    suspend fun getAppPermissions(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(TOOL_PERMISSIONS, "invalid_package_name")
        val io = ctx.shell("dumpsys package $quoted 2>&1 | head -n 1200")
        val lines = textLines(io.out)
        if (lines.any { it.trim() == "Unable to find package: " + pkg }) {
            return toolFailed(TOOL_PERMISSIONS, "package_not_found", pkg)
        }
        val requested = section(lines, "requested permissions:")
        val install = section(lines, "install permissions:")
        val runtime = section(lines, "runtime permissions:")
        if (requested.isEmpty() && install.isEmpty() && runtime.isEmpty()) {
            return toolFailed(TOOL_PERMISSIONS, "permission_section_missing", io.asText(300))
        }
        val granted = ArrayList<String>()
        val denied = ArrayList<String>()
        for (entry in install + runtime) {
            val name = PERMISSION_ENTRY.find(entry)?.groupValues?.get(1) ?: entry
            if (entry.contains("granted=true")) granted.add(name) else denied.add(name)
        }
        val runtimeNames = runtime.map { PERMISSION_ENTRY.find(it)?.groupValues?.get(1) ?: it }
        val data = JSONObject()
            .put("packageName", pkg)
            .put("requested", jsonArray(requested))
            .put("granted", jsonArray(granted))
            .put("denied", jsonArray(denied))
            .put("runtimePermissions", jsonArray(runtimeNames))
            .put(
                "note",
                "runtimePermissions 即运行期（危险）权限，是隐私审计的重点；" +
                    "权限集合来自 dumpsys，若输出被截断请再次调用。"
            )
        return toolOutcome(TOOL_PERMISSIONS, true, data = data)
    }

    suspend fun freezeApp(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome =
        setAppEnabled(args, ctx, TOOL_FREEZE, enable = false)

    suspend fun unfreezeApp(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome =
        setAppEnabled(args, ctx, TOOL_UNFREEZE, enable = true)

    private suspend fun setAppEnabled(
        args: Map<String, Any?>,
        ctx: AiToolContext,
        tool: String,
        enable: Boolean,
    ): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(tool, "invalid_package_name")
        if (!packageExists(ctx, pkg)) return toolFailed(tool, "package_not_found", pkg)
        val wasDisabled = isDisabled(ctx, pkg)
        val command = if (enable) "pm enable $quoted" else "pm disable-user --user 0 $quoted"
        val io = ctx.shell("$command 2>&1")
        val nowDisabled = isDisabled(ctx, pkg)
        val ok = nowDisabled != enable
        val changed = ok && wasDisabled != nowDisabled
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_APP_STATE,
                target = pkg,
                before = stateOf(wasDisabled),
                after = stateOf(nowDisabled),
                command = command,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = changed,
            )
        )
        if (!ok) return toolFailed(tool, "state_not_changed", io.asText(300))
        return toolOutcome(
            tool,
            true,
            data = JSONObject()
                .put("packageName", pkg)
                .put("before", stateOf(wasDisabled))
                .put("after", stateOf(nowDisabled))
                .put("changed", changed)
                .put("output", io.asText(300)),
            message = if (changed) "可在审计页一键撤销这次状态变更。" else "应用已处于目标状态，无需撤销。"
        )
    }

    suspend fun clearAppData(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(TOOL_CLEAR, "invalid_package_name")
        if (!packageExists(ctx, pkg)) return toolFailed(TOOL_CLEAR, "package_not_found", pkg)
        val command = "pm clear $quoted"
        val io = ctx.shell("$command 2>&1")
        val ok = io.out.contains("Success")
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_APP_DESTRUCTIVE,
                target = pkg,
                before = "with_data",
                after = if (ok) "data_cleared" else "unchanged",
                command = command,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
            )
        )
        if (!ok) return toolFailed(TOOL_CLEAR, "clear_failed", io.asText(400))
        return toolOutcome(
            TOOL_CLEAR,
            true,
            data = JSONObject().put("packageName", pkg).put("output", io.asText(300)),
            message = "应用数据已清除，此操作不可撤销。"
        )
    }

    suspend fun uninstallApp(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(TOOL_UNINSTALL, "invalid_package_name")
        if (!packageExists(ctx, pkg)) return toolFailed(TOOL_UNINSTALL, "package_not_found", pkg)
        val isSystem = ctx.shell("pm list packages -s $quoted 2>/dev/null").out
            .lineSequence().any { it.trim() == "package:$pkg" }
        val command = if (isSystem) "pm uninstall --user 0 $quoted" else "pm uninstall $quoted"
        val io = ctx.shell("$command 2>&1")
        val ok = io.out.contains("Success")
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_APP_DESTRUCTIVE,
                target = pkg,
                before = "installed",
                after = if (ok) "uninstalled" else "installed",
                command = command,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = false,
            )
        )
        if (!ok) return toolFailed(TOOL_UNINSTALL, "uninstall_failed", io.asText(400))
        return toolOutcome(
            TOOL_UNINSTALL,
            true,
            data = JSONObject()
                .put("packageName", pkg)
                .put("systemApp", isSystem)
                .put("command", command)
                .put("output", io.asText(300)),
            message = if (isSystem) {
                "系统应用只对当前用户卸载（apk 仍在系统分区），可用 pm install-existing 恢复。"
            } else {
                "应用已卸载，重新安装需要原始 apk，无法由本应用撤销。"
            }
        )
    }

    /**
     * Moves an app between the three stored root policies.
     *
     * The profile before/after values are encoded as `absent`, `deny`, `allow` or `custom:<templateId>`
     * so the audit page can restore the previous state exactly - including the case where an app had a
     * custom root template, which a plain allow/deny flag would lose.
     *
     * `prompt` is not a stored state in KernelSU: an app with no record is asked on the next request, so
     * that policy is implemented by clearing the record. The native getter cannot tell "no record" from
     * "explicitly denied" (both read back as `allowSu = false`), so an unrecorded app is reported as
     * `deny`; see deviation D-aj.
     */
    suspend fun setRootPolicy(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val policy = args.str("policy").lowercase()
        val uid = ctx.packageUidOrShell(pkg) ?: return toolFailed(TOOL_POLICY, "package_not_found", pkg)
        if (uid < 2000 && uid != 1000) {
            return toolFailed(TOOL_POLICY, "system_uid_refused", "拒绝修改系统 uid 的 root 策略：" + uid)
        }
        val before = runCatching { Natives.getAppProfile(pkg, uid) }.getOrNull()
            ?: return toolFailed(TOOL_POLICY, "profile_unavailable", pkg)
        val beforeKey = encodeProfile(before)
        val afterKey: String
        val target: Natives.Profile?
        when (policy) {
            "deny" -> {
                afterKey = "deny"
                target = before.copy(allowSu = false)
            }
            "prompt" -> {
                afterKey = "absent"
                target = null
            }
            else -> {
                afterKey = "allow"
                target = before.copy(allowSu = true, rootUseDefault = true, rootTemplate = null)
            }
        }
        val ok = runCatching { Natives.setAppProfile(target) }.getOrDefault(false)
        ctx.audit(
            AiAuditEntry(
                ts = ctx.now(),
                kind = AiAuditEntry.KIND_ROOT_POLICY,
                target = pkg,
                before = beforeKey,
                after = afterKey,
                command = "set_root_policy " + policy,
                result = if (ok) AiAuditEntry.RESULT_OK else AiAuditEntry.RESULT_FAILED,
                undoable = ok && beforeKey != afterKey,
            )
        )
        if (!ok) {
            return toolFailed(
                TOOL_POLICY,
                "policy_write_failed",
                "写入失败（重置为「仅询问」需要设备内核支持，若不支持会返回失败）"
            )
        }
        return toolOutcome(
            TOOL_POLICY,
            true,
            data = JSONObject()
                .put("packageName", pkg)
                .put("uid", uid)
                .put("policy", policy)
                .put("before", beforeKey)
                .put("after", afterKey),
            message = when (policy) {
                "deny" -> "已拒绝该应用的 root 请求。"
                "prompt" -> "已清除授权记录，下次请求 root 时会重新询问。"
                else -> "已允许该应用使用 root（默认模板）。"
            }
        )
    }

    suspend fun checkSuAuditLogs(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val limit = args.int("limit", 100).coerceIn(1, 500)
        val candidates = listOf(
            "/data/adb/ksu/su_audit.log",
            "/data/adb/ksu/log/su.log",
            "/data/adb/ksu/log/audit.log"
        )
        val probe = candidates.joinToString(" ") { path ->
            "echo @@probe:$path; if [ -f " + (ctx.quote(path) ?: "''") + " ]; then tail -n $limit " +
                (ctx.quote(path) ?: "''") + "; else echo __missing__; fi"
        }
        val io = ctx.shell(
            "echo @@list; ksud su list 2>&1 | head -n $limit; " +
                probe + "; " +
                "echo @@logcat; logcat -d -t $limit 2>&1 | grep -i -E 'ksud|grant|denied' | tail -n $limit"
        )
        val blocks = splitBlocks(io.out, "@@")
        val granted = blocks["list"].orEmpty()
        val logcat = blocks["logcat"].orEmpty()
        val auditFiles = JSONObject()
        for (path in candidates) {
            val body = blocks["probe:$path"].orEmpty()
            if (body.isEmpty() || body.first() == "__missing__") continue
            auditFiles.put(path, jsonArray(body))
        }
        val data = JSONObject()
            .put("grantedList", jsonArray(granted))
            .put("auditLogs", auditFiles)
            .put("logcat", jsonArray(logcat))
            .put("requestedLimit", limit)
            .put(
                "note",
                "auditLogs 只包含实际存在的日志文件；不同 KernelSU 版本上报路径不同，" +
                    "grantedList 是最可靠的授权现状。"
            )
        return toolOutcome(TOOL_SU_AUDIT, true, data = data)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun packageExists(ctx: AiToolContext, pkg: String): Boolean {
        val quoted = ctx.quote(pkg) ?: return false
        return ctx.shell("pm path $quoted 2>/dev/null").out.contains("package:")
    }

    private suspend fun isDisabled(ctx: AiToolContext, pkg: String): Boolean {
        val quoted = ctx.quote(pkg) ?: return false
        return ctx.shell("pm list packages -d $quoted 2>/dev/null").out
            .lineSequence().any { it.trim() == "package:$pkg" }
    }

    private fun stateOf(disabled: Boolean): String = if (disabled) STATE_DISABLED else STATE_ENABLED

    private fun encodeProfile(profile: Natives.Profile): String = when {
        !profile.allowSu -> "deny"
        profile.rootUseDefault -> "allow"
        profile.rootTemplate != null -> "custom:" + profile.rootTemplate
        else -> "custom"
    }

    private fun match(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Collects the indented body of one `dumpsys` section, stopping at the next header or blank line. */
    private fun section(lines: List<String>, header: String): List<String> {
        val out = ArrayList<String>()
        var headerIndent = -1
        for (line in lines) {
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()
            if (headerIndent < 0) {
                if (trimmed == header) headerIndent = indent
                continue
            }
            if (trimmed.isEmpty()) break
            if (indent < headerIndent) break
            if (indent == headerIndent && trimmed.endsWith(":")) break
            out.add(trimmed)
        }
        return out
    }

    private const val TOOL_APPS = "get_installed_apps"
    private const val TOOL_INFO = "get_app_info"
    private const val TOOL_PERMISSIONS = "get_app_permissions"
    private const val TOOL_FREEZE = "freeze_app"
    private const val TOOL_UNFREEZE = "unfreeze_app"
    private const val TOOL_CLEAR = "clear_app_data"
    private const val TOOL_UNINSTALL = "uninstall_app"
    private const val TOOL_POLICY = "set_root_policy"
    private const val TOOL_SU_AUDIT = "check_su_audit_logs"

    private const val STATE_ENABLED = "enabled"
    private const val STATE_DISABLED = "disabled"
    private const val MAX_APPS = 500

    private val PERMISSION_ENTRY = Regex("^([A-Za-z0-9_.]+):")

    private val REGEX_VERSION_NAME = Regex("\\bversionName=(\\S+)")
    private val REGEX_VERSION_CODE = Regex("\\bversionCode=(\\d+)")
    private val REGEX_CODE_PATH = Regex("\\bcodePath=(\\S+)")
    private val REGEX_DATA_DIR = Regex("\\bdataDir=(\\S+)")
    private val REGEX_USER_ID = Regex("\\buserId=(\\d+)")
    private val REGEX_MIN_SDK = Regex("\\bminSdk=(\\d+)")
    private val REGEX_TARGET_SDK = Regex("\\btargetSdk=(\\d+)")
    private val REGEX_CPU_ABI = Regex("\\bprimaryCpuAbi=(\\S+)")
    private val REGEX_INSTALLER = Regex("\\binstallerPackageName=(\\S+)")
    private val REGEX_FIRST_INSTALL = Regex("\\bfirstInstallTime=(.+)$", RegexOption.MULTILINE)
    private val REGEX_LAST_UPDATE = Regex("\\blastUpdateTime=(.+)$", RegexOption.MULTILINE)
    private val REGEX_ENABLED = Regex("\\benabled=(\\S+)")
}
