package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * Family ① 深度系统诊断 — battery, CPU/memory, storage, properties, logs, crash logs, SELinux and
 * process inspection. Every tool here is read-only and silent: it never changes device state, so it
 * needs no confirmation. Commands are written by this file, never by the model.
 */
object AiSystemTools {

    suspend fun getBatteryInfo(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell("dumpsys battery")
        if (io.out.isBlank()) return toolFailed(TOOL_BATTERY, "battery_service_unavailable", io.asText(300))
        val fields = JSONObject()
        for (line in textLines(io.out)) {
            val idx = line.indexOf(':')
            if (idx <= 0 || line.startsWith("{")) continue
            val key = line.substring(0, idx).trim()
            if (key.isEmpty() || key.length > 40 || key.contains(' ')) continue
            fields.put(key, line.substring(idx + 1).trim())
        }
        val status = fields.optString("status")
        val level = fields.optString("level").toIntOrNull()
        val temperature = fields.optString("temperature").toIntOrNull()
        val data = JSONObject()
            .put("levelPercent", level ?: JSONObject.NULL)
            .put("temperatureCelsius", temperature?.let { it / 10.0 } ?: JSONObject.NULL)
            .put("health", fields.optString("health"))
            .put("status", status)
            .put("charging", isCharging(status))
            .put("voltageMv", fields.optString("voltage").toIntOrNull() ?: JSONObject.NULL)
            .put("plugged", fields.optString("plugged"))
            .put("present", fields.optString("present"))
            .put("raw", truncate(io.out, 2500))
        return toolOutcome(TOOL_BATTERY, true, data = data)
    }

    suspend fun getCpuMemoryInfo(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @load; cat /proc/loadavg 2>/dev/null; " +
                "echo @mem; cat /proc/meminfo 2>/dev/null; " +
                "echo @freq; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null; " +
                "echo @online; cat /sys/devices/system/cpu/online 2>/dev/null; " +
                "echo @stat; head -n 1 /proc/stat 2>/dev/null"
        )
        val blocks = splitBlocks(io.out)
        val load = textLines(blocks["load"].orEmpty().joinToString("\n")).firstOrNull().orEmpty().split(' ')
        val memory = JSONObject()
        for (line in blocks["mem"].orEmpty()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            if (key == "MemTotal" || key == "MemFree" || key == "MemAvailable" ||
                key == "Buffers" || key == "Cached" || key == "SwapTotal" || key == "SwapFree"
            ) {
                memory.put(key, value)
            }
        }
        val total = memory.optLong("MemTotal")
        val available = memory.optLong("MemAvailable")
        val usedPercent = if (total > 0 && available > 0) (total - available) * 100.0 / total else null
        val freqs = blocks["freq"].orEmpty().mapNotNull { it.trim().toLongOrNull() }
        val data = JSONObject()
            .put(
                "load",
                JSONObject()
                    .put("avg1", load.getOrNull(0)?.toDoubleOrNull() ?: JSONObject.NULL)
                    .put("avg5", load.getOrNull(1)?.toDoubleOrNull() ?: JSONObject.NULL)
                    .put("avg15", load.getOrNull(2)?.toDoubleOrNull() ?: JSONObject.NULL)
                    .put("runnable", load.getOrNull(3).orEmpty())
                    .put("totalThreads", load.getOrNull(4)?.toIntOrNull() ?: JSONObject.NULL)
            )
            .put("memoryKb", memory)
            .put("memoryUsedPercent", usedPercent ?: JSONObject.NULL)
            .put("cpuOnline", blocks["online"].orEmpty().firstOrNull().orEmpty())
            .put("cpuFreqsKhz", jsonArray(freqs))
            .put("cpuStat", blocks["stat"].orEmpty().firstOrNull().orEmpty())
        return toolOutcome(TOOL_CPU_MEMORY, true, data = data)
    }

    suspend fun getStorageInfo(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell("df -k /data /system /data/adb 2>/dev/null; echo @all; df -k 2>/dev/null")
        val blocks = splitBlocks(io.out)
        val primary = parseDf(blocks[""].orEmpty())
        val all = parseDf(blocks["all"].orEmpty())
        val data = JSONObject()
            .put("requested", jsonArray(primary))
            .put("filesystems", jsonArray(all))
        if (primary.isEmpty() && all.isEmpty()) return toolFailed(TOOL_STORAGE, "df_unavailable", io.asText(300))
        return toolOutcome(TOOL_STORAGE, true, data = data)
    }

    suspend fun getSystemProperties(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val name = args.optStr("prop_name")
        if (name != null) {
            val quoted = ctx.quote(name) ?: return toolFailed(TOOL_PROPS, "invalid_property_name")
            val io = ctx.shell("getprop $quoted")
            val value = io.out.trim()
            val data = JSONObject().put("name", name).put("value", value).put("empty", value.isEmpty())
            return toolOutcome(TOOL_PROPS, true, data = data)
        }
        val io = ctx.shell("getprop")
        val map = JSONObject()
        var truncatedCount = 0
        for (line in textLines(io.out)) {
            val match = PROP_LINE.find(line) ?: continue
            if (map.length() >= MAX_PROPS) {
                truncatedCount++
                continue
            }
            map.put(match.groupValues[1], match.groupValues[2])
        }
        val data = JSONObject()
            .put("count", map.length())
            .put("truncated", truncatedCount > 0)
            .put("omitted", truncatedCount)
            .put("properties", map)
        return toolOutcome(TOOL_PROPS, true, data = data)
    }

    suspend fun getSystemLogs(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val type = args.str("type", "logcat").lowercase()
        val level = args.optStr("level")?.uppercase()?.firstOrNull()
        val tag = args.optStr("tag")
        val lines = args.int("lines", DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)
        val command = when (type) {
            "logcat" -> "logcat -d -t $lines"
            else -> "dmesg 2>/dev/null | tail -n $lines"
        }
        val io = ctx.shell(command)
        if (io.out.isBlank()) return toolFailed(TOOL_LOGS, "log_unavailable", io.asText(300))
        val minRank = level?.let { LOG_RANKS[it] } ?: 0
        val entries = ArrayList<String>()
        var dropped = 0
        for (line in textLines(io.out)) {
            val rank = if (type == "logcat") logcatRank(line) else kernelRank(line)
            if (rank < minRank) {
                dropped++
                continue
            }
            if (tag != null && !line.contains(tag, ignoreCase = true)) {
                dropped++
                continue
            }
            entries.add(line)
        }
        val kept = entries.takeLast(MAX_RETURNED_LOG_LINES)
        val data = JSONObject()
            .put("type", type)
            .put("level", level?.toString() ?: JSONObject.NULL)
            .put("tag", tag ?: JSONObject.NULL)
            .put("requestedLines", lines)
            .put("matched", entries.size)
            .put("filteredOut", dropped)
            .put("entries", jsonArray(kept))
        return toolOutcome(TOOL_LOGS, true, data = data)
    }

    suspend fun findCrashLogs(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val pkg = args.str("package_name")
        val quoted = ctx.quote(pkg) ?: return toolFailed(TOOL_CRASH, "invalid_package_name")
        val io = ctx.shell(
            "echo @tombstones; ls -lt /data/tombstones 2>/dev/null | head -n 10; " +
                "echo @dropbox; ls -lt /data/system/dropbox 2>/dev/null | grep -i $quoted | head -n 20; " +
                "echo @anr; ls -lt /data/anr 2>/dev/null | head -n 10; " +
                "echo @crashbuf; logcat -b crash -d -t 800 2>/dev/null | grep -i $quoted | tail -n 60"
        )
        val blocks = splitBlocks(io.out)
        val tombstones = blocks["tombstones"].orEmpty()
        val dropbox = blocks["dropbox"].orEmpty()
        val anr = blocks["anr"].orEmpty()
        val crashBuf = blocks["crashbuf"].orEmpty()
        val data = JSONObject()
            .put("packageName", pkg)
            .put("tombstones", jsonArray(tombstones))
            .put("dropbox", jsonArray(dropbox))
            .put("anr", jsonArray(anr))
            .put("crashBuffer", jsonArray(crashBuf))
            .put("found", dropbox.isNotEmpty() || crashBuf.isNotEmpty())
            .put(
                "note",
                "tombstones 与 anr 是目录列表，具体内容可用 read_file 读取对应文件"
            )
        return toolOutcome(TOOL_CRASH, true, data = data)
    }

    suspend fun getSelinuxStatus(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @mode; getenforce 2>/dev/null; " +
                "echo @enforce; cat /sys/fs/selinux/enforce 2>/dev/null; " +
                "echo @context; cat /proc/self/attr/current 2>/dev/null"
        )
        val blocks = splitBlocks(io.out)
        val mode = blocks["mode"].orEmpty().firstOrNull().orEmpty()
        if (mode.isBlank()) return toolFailed(TOOL_SELINUX, "selinux_unavailable", io.asText(300))
        val data = JSONObject()
            .put("mode", mode)
            .put("enforcing", mode.equals("Enforcing", ignoreCase = true) || blocks["enforce"].orEmpty().firstOrNull() == "1")
            .put("context", blocks["context"].orEmpty().firstOrNull().orEmpty())
        return toolOutcome(TOOL_SELINUX, true, data = data)
    }

    suspend fun getProcessInfo(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val target = args.str("pid_or_name")
        val pid = target.toIntOrNull() ?: resolvePid(ctx, target)
            ?: return toolFailed(TOOL_PROCESS, "process_not_found", "未找到匹配的进程：" + target)
        val io = ctx.shell(
            "echo @header; ps -A -o PID,PPID,USER,STAT,VSZ,RSS,NAME 2>/dev/null | head -n 1; " +
                "echo @ps; ps -A -o PID,PPID,USER,STAT,VSZ,RSS,NAME 2>/dev/null | grep -w $pid; " +
                "echo @status; cat /proc/$pid/status 2>/dev/null; " +
                "echo @cmdline; tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null; echo; " +
                "echo @fds; ls /proc/$pid/fd 2>/dev/null | wc -l"
        )
        val blocks = splitBlocks(io.out)
        val status = JSONObject()
        for (line in blocks["status"].orEmpty()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key in STATUS_KEYS) status.put(key, line.substring(idx + 1).trim())
        }
        if (blocks["status"].orEmpty().isEmpty() && blocks["ps"].orEmpty().isEmpty()) {
            return toolFailed(TOOL_PROCESS, "process_not_found", "进程 $pid 已不存在（可能是瞬时进程）")
        }
        val data = JSONObject()
            .put("pid", pid)
            .put("requested", target)
            .put("psLine", blocks["ps"].orEmpty().firstOrNull().orEmpty())
            .put("status", status)
            .put("cmdline", blocks["cmdline"].orEmpty().firstOrNull().orEmpty().trim())
            .put("openFdCount", blocks["fds"].orEmpty().firstOrNull()?.trim()?.toIntOrNull() ?: JSONObject.NULL)
        return toolOutcome(TOOL_PROCESS, true, data = data)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun resolvePid(ctx: AiToolContext, name: String): Int? {
        val quoted = ctx.quote(name) ?: return null
        val byName = ctx.shell("pidof $quoted 2>/dev/null | tr ' ' '\\n' | head -n 1").out.trim()
        byName.toIntOrNull()?.let { return it }
        val byPattern = ctx.shell("pgrep -f $quoted 2>/dev/null | head -n 1").out.trim()
        return byPattern.toIntOrNull()
    }

    private fun parseDf(lines: List<String>): List<Any?> {
        val out = ArrayList<Any?>()
        for (line in lines) {
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 5) continue
            if (parts[0].equals("Filesystem", ignoreCase = true) || parts[0].startsWith("1K")) continue
            val size = parts[1].toLongOrNull() ?: continue
            val used = parts[2].toLongOrNull() ?: continue
            val avail = parts[3].toLongOrNull() ?: continue
            out.add(
                JSONObject()
                    .put("filesystem", parts[0])
                    .put("mount", parts.last())
                    .put("sizeKb", size)
                    .put("usedKb", used)
                    .put("availKb", avail)
                    .put("usePercent", parts[4])
            )
        }
        return out
    }

    private fun isCharging(status: String): Boolean {
        if (status.contains("charg", ignoreCase = true)) return true
        if (status.equals("full", ignoreCase = true)) return true
        return status == "2" || status == "5"
    }

    private fun logcatRank(line: String): Int {
        val match = LOGCAT_LEVEL.find(line) ?: return 0
        return LOG_RANKS[match.groupValues[1].firstOrNull() ?: return 0] ?: 0
    }

    private fun kernelRank(line: String): Int {
        val match = KERNEL_PRIORITY.find(line) ?: return 0
        return when (match.groupValues[1].toInt()) {
            0, 1, 2, 3 -> LOG_RANKS.getValue('E')
            4 -> LOG_RANKS.getValue('W')
            5, 6 -> LOG_RANKS.getValue('I')
            else -> LOG_RANKS.getValue('D')
        }
    }

    private const val TOOL_BATTERY = "get_battery_info"
    private const val TOOL_CPU_MEMORY = "get_cpu_memory_info"
    private const val TOOL_STORAGE = "get_storage_info"
    private const val TOOL_PROPS = "get_system_properties"
    private const val TOOL_LOGS = "get_system_logs"
    private const val TOOL_CRASH = "find_crash_logs"
    private const val TOOL_SELINUX = "get_selinux_status"
    private const val TOOL_PROCESS = "get_process_info"

    private const val DEFAULT_LOG_LINES = 200
    private const val MAX_LOG_LINES = 500
    private const val MAX_RETURNED_LOG_LINES = 400
    private const val MAX_PROPS = 400

    private val STATUS_KEYS = setOf("Name", "State", "Tgid", "Pid", "PPid", "Uid", "Gid", "Threads", "VmPeak", "VmSize", "VmRSS")
    private val PROP_LINE = Regex("^\\[([^]]+)]:\\s*\\[(.*)]$")
    private val LOGCAT_LEVEL = Regex(" ([VDIWEF]) ")
    private val KERNEL_PRIORITY = Regex("^<(\\d)>")
    private val LOG_RANKS = mapOf('V' to 0, 'D' to 1, 'I' to 2, 'W' to 3, 'E' to 4, 'F' to 5)
}
