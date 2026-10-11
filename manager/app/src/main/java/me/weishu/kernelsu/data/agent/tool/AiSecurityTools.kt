package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Family ⑥ 安全与审计 — suspicious apps, root audit, system integrity, root cloaking, ADB exposure
 * and installed CAs.
 *
 * These are heuristics, and the payloads say so: every finding carries the evidence and the reason it
 * was flagged, plus an explicit note when a check has no baseline to compare against. A tool that
 * shouted "malicious" without evidence would be worse than no tool at all.
 */
object AiSecurityTools {

    suspend fun detectMaliciousApps(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @thirdparty; pm list packages -3 -f -U -i 2>/dev/null; " +
                "echo @disabled; pm list packages -d 2>/dev/null; " +
                "echo @admins; dumpsys device_policy 2>/dev/null | grep -iE 'PackageName|admin=' | head -n 40; " +
                "echo @accessibility; settings get secure enabled_accessibility_services 2>/dev/null; " +
                "echo @installers; pm list packages -3 -i 2>/dev/null | grep -c 'installer='"
        )
        val blocks = splitBlocks(io.out)
        val adminText = blocks["admins"].orEmpty().joinToString(" ")
        val accessibility = blocks["accessibility"].orEmpty().firstOrNull().orEmpty()
        val findings = ArrayList<Any?>()
        for (line in blocks["thirdparty"].orEmpty()) {
            val app = parsePackageLine(line) ?: continue
            val pkg = app.optString("packageName")
            val installer = app.optString("installer").ifBlank { null }
            var score = 0
            val reasons = ArrayList<String>()
            if (installer.isNullOrBlank() || installer == "null") {
                score += 2
                reasons.add("未知安装来源（installer 为空），疑似侧载")
            }
            if (looksLikeMimic(pkg) && installer.isNullOrBlank()) {
                score += 2
                reasons.add("包名模仿系统/厂商应用，且非商店安装")
            }
            if (adminText.contains(pkg, ignoreCase = true)) {
                score += 2
                reasons.add("注册为设备管理员")
            }
            if (accessibility.contains(pkg, ignoreCase = true)) {
                score += 3
                reasons.add("正在使用无障碍服务（可读取屏幕与模拟操作）")
            }
            if (pkg.length > 40) {
                score += 1
                reasons.add("包名异常冗长")
            }
            if (score > 0) {
                findings.add(
                    JSONObject()
                        .put("packageName", pkg)
                        .put("apkPath", app.optString("apkPath").ifBlank { null } ?: JSONObject.NULL)
                        .put("uid", app.opt("uid") ?: JSONObject.NULL)
                        .put("installer", installer ?: JSONObject.NULL)
                        .put("score", score)
                        .put("reasons", jsonArray(reasons))
                )
            }
        }
        val sorted = findings.sortedByDescending { (it as JSONObject).optInt("score") }
        val data = JSONObject()
            .put("scannedThirdParty", blocks["thirdparty"].orEmpty().size)
            .put("flagged", sorted.size)
            .put("findings", jsonArray(sorted))
            .put("disabledPackages", jsonArray(blocks["disabled"].orEmpty()))
            .put("accessibilityServices", accessibility)
            .put(
                "note",
                "这是启发式筛查：分数只代表可疑程度，不构成结论。安装来源为空的第三方应用最常见的原因是侧载。"
            )
        return toolOutcome(TOOL_MALICIOUS, true, data = data)
    }

    suspend fun auditRootAccess(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @ksud; ksud su list 2>&1 | head -n 200; " +
                "echo @dir; ls -la /data/adb/ksu 2>&1 | head -n 40; " +
                "echo @db; ls -la /data/adb/ksu/db /data/adb/ksud 2>&1 | head -n 40"
        )
        val blocks = splitBlocks(io.out)
        val entries = ArrayList<Any?>()
        for (line in blocks["ksud"].orEmpty()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[")) continue
            val pkg = PKG_TOKEN.find(trimmed)?.value
            entries.add(
                JSONObject()
                    .put("packageName", pkg ?: JSONObject.NULL)
                    .put("raw", trimmed)
            )
        }
        val installed = ArrayList<String>()
        for (line in blocks["ksud"].orEmpty()) {
            PKG_TOKEN.find(line)?.value?.let { installed.add(it) }
        }
        var notInstalled = 0
        for (pkg in installed.distinct()) {
            val quoted = ctx.quote(pkg) ?: continue
            val exists = ctx.shell("pm path $quoted 2>/dev/null | head -n 1").out.trim()
            if (exists.isEmpty()) notInstalled++
        }
        val data = JSONObject()
            .put("suListRaw", jsonArray(blocks["ksud"].orEmpty()))
            .put("grants", jsonArray(entries))
            .put("grantCount", entries.size)
            .put("grantedButNotInstalled", notInstalled)
            .put("ksuDir", jsonArray(blocks["dir"].orEmpty()))
            .put("dbFiles", jsonArray(blocks["db"].orEmpty()))
            .put(
                "note",
                "`ksud su list` 的输出格式未被本工具解析为强类型，原始行一并返回；grantedButNotInstalled 大于 0 时值得排查。"
            )
        return toolOutcome(TOOL_ROOT_AUDIT, true, data = data)
    }

    suspend fun checkSystemIntegrity(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val requested = args.strList("paths")
        val paths = if (requested.isEmpty()) DEFAULT_INTEGRITY_PATHS else requested
        val commands = ArrayList<String>()
        for ((index, path) in paths.withIndex()) {
            val quoted = ctx.quote(path) ?: continue
            commands.add(
                "echo @$index; ls -ld $quoted 2>&1 | head -n 1; " +
                    "stat -c 'size=%s mtime=%Y mode=%a owner=%U' $quoted 2>&1 | head -n 1; " +
                    "md5sum $quoted 2>/dev/null | cut -d' ' -f1; " +
                    "head -c 2048 $quoted 2>/dev/null | base64 | head -c 3000"
            )
        }
        val io = ctx.shell(commands.joinToString("; "))
        val blocks = splitBlocks(io.out)
        val items = ArrayList<Any?>()
        for ((index, path) in paths.withIndex()) {
            val lines = blocks[index.toString()].orEmpty()
            val ls = lines.getOrNull(0).orEmpty()
            val stat = lines.getOrNull(1).orEmpty()
            val md5 = lines.getOrNull(2).orEmpty().trim()
            val exists = !ls.contains("No such file") && !ls.contains("Not found") && lines.isNotEmpty()
            val suspicious = ArrayList<String>()
            if (path.endsWith("hosts") && lines.size > 3) {
                suspicious.add("hosts 文件条目异常（可能被用于广告拦截或劫持）")
            }
            if (path.endsWith("/su") && exists) suspicious.add("系统路径下存在 su 二进制")
            if (path == "/data/adb/ksu" && exists) suspicious.add("KernelSU 目录存在（Root 环境特征）")
            items.add(
                JSONObject()
                    .put("path", path)
                    .put("exists", exists)
                    .put("ls", ls)
                    .put("statLine", stat)
                    .put("md5", md5.ifBlank { JSONObject.NULL })
                    .put("suspicious", jsonArray(suspicious))
                    .put("previewBase64", lines.getOrNull(3) ?: JSONObject.NULL)
            )
        }
        val data = JSONObject()
            .put("items", jsonArray(items))
            .put("checked", items.size)
            .put("baselineKnown", false)
            .put(
                "note",
                "设备上没有可信基线，因此本工具只报告状态与启发式异常，无法断言文件被篡改；" +
                    "若要对比，请先用 check_file_integrity 记录当前 md5。"
            )
        return toolOutcome(TOOL_INTEGRITY, true, data = data)
    }

    suspend fun detectRootCloaking(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @props; getprop 2>/dev/null | grep -iE 'ro.debuggable|ro.secure|ro.build.tags|ro.build.type|ro.boot.verifiedbootstate|ro.boot.flash.locked|init.svc.magisk|ro.magisk|persist.sys.ksu'; " +
                "echo @bins; command -v su ksud resetprop magisk 2>&1; " +
                "echo @paths; ls -d /data/adb /data/adb/ksu /data/adb/modules /sbin/.magisk /system/etc/init/magisk 2>&1; " +
                "echo @mounts; mount 2>/dev/null | grep -iE 'magisk|overlay|/system' | head -n 20; " +
                "echo @selinux; getenforce 2>/dev/null; " +
                "echo @deny; getprop ro.kernel.selinux 2>/dev/null"
        )
        val blocks = splitBlocks(io.out)
        val bins = blocks["bins"].orEmpty().joinToString(" ")
        val paths = blocks["paths"].orEmpty().joinToString(" ")
        val rootPresent = paths.contains("/data/adb") && !paths.contains("/data/adb: No such")
        val suOnPath = bins.split(' ').any { it.endsWith("/su") || it == "su" }
        val ksudOnPath = bins.contains("ksud")
        val cloakingSuspected = rootPresent && !suOnPath
        val data = JSONObject()
            .put("rootPresent", rootPresent)
            .put("ksudBinaryFound", ksudOnPath)
            .put("suOnPath", suOnPath)
            .put("cloakingSuspected", cloakingSuspected)
            .put("properties", jsonArray(blocks["props"].orEmpty()))
            .put("binaries", jsonArray(blocks["bins"].orEmpty()))
            .put("paths", jsonArray(blocks["paths"].orEmpty()))
            .put("mounts", jsonArray(blocks["mounts"].orEmpty()))
            .put("selinux", blocks["selinux"].orEmpty().firstOrNull().orEmpty())
            .put(
                "note",
                if (cloakingSuspected) {
                    "存在 Root 目录但 PATH 上没有 su，说明隐藏机制正在生效（这正是 KernelSU 的预期行为）。"
                } else {
                    "未发现明显的隐藏冲突；是否可被检测到取决于目标应用的检测手段。"
                }
            )
        return toolOutcome(TOOL_CLOAKING, true, data = data)
    }

    suspend fun scanAdbPorts(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @port; getprop service.adb.tcp.port 2>/dev/null; " +
                "echo @persist; getprop persist.adb.tcp.port 2>/dev/null; " +
                "echo @enabled; settings get global adb_enabled 2>/dev/null; " +
                "echo @secure; getprop ro.adb.secure 2>/dev/null; " +
                "echo @listen; netstat -tuln 2>/dev/null | grep -E ':(5555|5037)'"
        )
        val blocks = splitBlocks(io.out)
        val tcpPort = blocks["port"].orEmpty().firstOrNull()?.trim().orEmpty()
        val persistPort = blocks["persist"].orEmpty().firstOrNull()?.trim().orEmpty()
        val enabled = blocks["enabled"].orEmpty().firstOrNull()?.trim().orEmpty()
        val listening = blocks["listen"].orEmpty()
        val insecure = tcpPort == "5555" || persistPort == "5555" || listening.isNotEmpty()
        val data = JSONObject()
            .put("serviceAdbTcpPort", tcpPort)
            .put("persistAdbTcpPort", persistPort)
            .put("adbEnabled", enabled)
            .put("secureAdb", blocks["secure"].orEmpty().firstOrNull()?.trim().orEmpty())
            .put("listeningLines", jsonArray(listening))
            .put("networkAdbExposed", insecure)
            .put(
                "note",
                if (insecure) "检测到网络 ADB 相关端口处于活动状态，请确认这是用户主动开启的。" else "未发现网络 ADB 端口在监听。"
            )
        return toolOutcome(TOOL_ADB, true, data = data)
    }

    suspend fun getInstalledCertificates(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val store = args.str("store", "all").lowercase()
        return try {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null, null)
            val entries = ArrayList<Any?>()
            var system = 0
            var user = 0
            for (alias in keyStore.aliases()) {
                val isSystem = alias.startsWith("system:")
                val isUser = alias.startsWith("user:")
                if (!isSystem && !isUser) continue
                if (store == "system" && !isSystem) continue
                if (store == "user" && !isUser) continue
                if (isSystem) system++ else user++
                val certificate = keyStore.getCertificate(alias)
                val selfSigned = certificate is X509Certificate && certificate.subjectDN == certificate.issuerDN
                entries.add(
                    JSONObject()
                        .put("alias", alias)
                        .put("store", if (isSystem) "system" else "user")
                        .put("subject", (certificate as? X509Certificate)?.subjectDN?.name ?: alias)
                        .put("issuer", (certificate as? X509Certificate)?.issuerDN?.name ?: JSONObject.NULL)
                        .put("notAfter", (certificate as? X509Certificate)?.notAfter?.time ?: JSONObject.NULL)
                        .put("selfSigned", selfSigned)
                )
                if (entries.size >= MAX_CERTS) break
            }
            val userCerts = entries.count { (it as JSONObject).optString("store") == "user" }
            val data = JSONObject()
                .put("count", entries.size)
                .put("systemCount", system)
                .put("userCount", user)
                .put("certificates", jsonArray(entries))
                .put(
                    "note",
                    if (userCerts > 0) {
                        "存在用户安装的证书：这既可能是正常的私有 CA，也可能是中间人抓包留下的。"
                    } else {
                        "没有用户安装的证书，系统证书数量正常。"
                    }
                )
            toolOutcome(TOOL_CERTS, true, data = data)
        } catch (error: Throwable) {
            toolFailed(TOOL_CERTS, "keystore_unavailable", error.message ?: error.javaClass.simpleName)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun parsePackageLine(line: String): JSONObject? {
        if (!line.startsWith("package:")) return null
        val body = line.removePrefix("package:")
        val path = body.substringBefore('=')
        val rest = body.substringAfter('=', "")
        val pkg = rest.substringBefore(' ').trim()
        if (pkg.isEmpty()) return null
        val uid = Regex("uid:(\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull()
        val installer = Regex("installer=([^\\s]+)").find(rest)?.groupValues?.get(1)
        return JSONObject()
            .put("packageName", pkg)
            .put("apkPath", path)
            .put("uid", uid ?: JSONObject.NULL)
            .put("installer", installer ?: JSONObject.NULL)
    }

    private fun looksLikeMimic(pkg: String): Boolean =
        MIMIC_MARKERS.any { pkg.contains(it, ignoreCase = true) }

    private const val TOOL_MALICIOUS = "detect_malicious_apps"
    private const val TOOL_ROOT_AUDIT = "audit_root_access"
    private const val TOOL_INTEGRITY = "check_system_integrity"
    private const val TOOL_CLOAKING = "detect_root_cloaking"
    private const val TOOL_ADB = "scan_adb_ports"
    private const val TOOL_CERTS = "get_installed_certificates"

    private const val MAX_CERTS = 300

    private val MIMIC_MARKERS = listOf("system", "update", "google", "android", "service", "secure", "settings", "huawei", "miui")
    private val PKG_TOKEN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+){1,}")
    private val DEFAULT_INTEGRITY_PATHS = listOf(
        "/system/build.prop",
        "/system/etc/hosts",
        "/vendor/build.prop",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu",
    )
}
