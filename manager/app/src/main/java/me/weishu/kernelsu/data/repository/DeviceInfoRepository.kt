package me.weishu.kernelsu.data.repository

import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.home.getManagerVersion
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.getSuperuserCount
import me.weishu.kernelsu.ui.util.listModules
import me.weishu.kernelsu.ui.util.rootAvailable
import org.json.JSONArray

/**
 * What the assistant knows about the device before it ever talks to a model.
 *
 * Recon is client side on purpose: it costs nothing, needs no network, and works even when the file
 * access scope is NONE, because modules and device facts are read through ksud and the platform
 * APIs rather than through the file system.
 */
data class DeviceInfoSnapshot(
    val rootAvailable: Boolean,
    val scope: AiAccessScope,
    val allowShell: Boolean,
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int,
    val enabledModules: List<String>,
    val disabledModules: List<String>,
    val superuserCount: Int,
) {
    fun seccompLabel(): String = when (seccompStatus) {
        -1 -> "不支持"
        0 -> "关闭"
        1 -> "严格模式"
        2 -> "过滤模式"
        else -> "未知"
    }

    /** Compact plain text context appended to the first user turn. */
    fun toPromptContext(): String = buildString {
        appendLine("设备信息（客户端侦察，无需你请求）：")
        appendLine("- root 可用：" + (if (rootAvailable) "是" else "否"))
        appendLine("- 文件访问范围：" + scope.name)
        appendLine("- 允许执行 shell：" + (if (allowShell) "是" else "否"))
        appendLine("- 管理器版本：" + managerVersion)
        appendLine("- 内核版本：" + kernelVersion)
        appendLine("- 设备型号：" + deviceModel)
        appendLine("- 系统指纹：" + fingerprint)
        appendLine("- SELinux：" + selinuxStatus + "；Seccomp：" + seccompLabel())
        appendLine("- 已启用模块（" + enabledModules.size + "）：" + if (enabledModules.isEmpty()) "无" else enabledModules.joinToString(", "))
        appendLine("- 已禁用模块（" + disabledModules.size + "）：" + if (disabledModules.isEmpty()) "无" else disabledModules.joinToString(", "))
        appendLine("- 已授权 root 的应用数：" + superuserCount)
    }
}

interface DeviceInfoRepository {
    suspend fun snapshot(scope: AiAccessScope, allowShell: Boolean): DeviceInfoSnapshot
}

class DeviceInfoRepositoryImpl : DeviceInfoRepository {

    override suspend fun snapshot(scope: AiAccessScope, allowShell: Boolean): DeviceInfoSnapshot =
        withContext(Dispatchers.IO) {
            val manager = runCatching { getManagerVersion(ksuApp) }.getOrNull()
            val modules = runCatching { parseModules(listModules()) }.getOrDefault(emptyList())
            DeviceInfoSnapshot(
                rootAvailable = runCatching { rootAvailable() }.getOrDefault(false),
                scope = scope,
                allowShell = allowShell,
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("unknown"),
                managerVersion = if (manager == null) "unknown" else manager.versionName + " (" + manager.versionCode + ")",
                deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
                fingerprint = Build.FINGERPRINT ?: "unknown",
                selinuxStatus = runCatching { getSELinuxStatusRaw() }.getOrDefault("Unknown"),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
                enabledModules = modules.filter { it.second }.map { it.first },
                disabledModules = modules.filter { !it.second }.map { it.first },
                superuserCount = runCatching { getSuperuserCount() }.getOrDefault(0),
            )
        }

    private fun parseModules(json: String): List<Pair<String, Boolean>> {
        val array = JSONArray(json)
        val out = ArrayList<Pair<String, Boolean>>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            out.add(id to obj.optBoolean("enabled"))
        }
        return out
    }
}
