package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import org.json.JSONObject

/**
 * Family ③ 网络与连接 — status, connectivity probing, firewall rules, DNS and listening ports.
 *
 * All five tools are read-only probes. `test_connectivity` is the only one that generates traffic, and
 * it only ever talks to the host the user asked about; the target is validated by the registry pattern
 * and quoted here before it reaches the shell.
 */
object AiNetworkTools {

    suspend fun getNetworkStatus(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @addr; ip -o addr show 2>/dev/null; " +
                "echo @routes; ip route show 2>/dev/null; " +
                "echo @props; getprop 2>/dev/null | grep -iE 'net\\.dns|net\\.|dhcp\\.' | head -n 40"
        )
        val blocks = splitBlocks(io.out)
        val interfaces = JSONObject()
        for (line in blocks["addr"].orEmpty()) {
            val match = IP_ADDR.find(line) ?: continue
            val name = match.groupValues[1]
            val family = if (match.groupValues[2].startsWith("inet6")) "inet6" else "inet"
            val address = match.groupValues[3]
            val list = interfaces.optJSONArray(name) ?: jsonArray(emptyList()).also { interfaces.put(name, it) }
            list.put(JSONObject().put("family", family).put("address", address))
        }
        if (blocks["addr"].orEmpty().isEmpty()) {
            return toolFailed(TOOL_NETWORK_STATUS, "ip_command_unavailable", io.asText(300))
        }
        val routes = blocks["routes"].orEmpty()
        val gateway = routes.firstOrNull { it.startsWith("default") }
            ?.split(Regex("\\s+"))?.let { parts ->
                val idx = parts.indexOf("via")
                if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else null
            }
        val data = JSONObject()
            .put("interfaces", interfaces)
            .put("routes", jsonArray(routes))
            .put("defaultRoute", gateway ?: JSONObject.NULL)
            .put("properties", jsonArray(blocks["props"].orEmpty()))
        return toolOutcome(TOOL_NETWORK_STATUS, true, data = data)
    }

    suspend fun testConnectivity(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val target = args.str("target")
        val mode = args.str("mode", "ping").lowercase()
        val count = args.int("count", 3).coerceIn(1, 8)
        val timeout = args.int("timeout", 5).coerceIn(1, 30)
        return if (mode == "http") httpProbe(target, timeout, ctx) else pingProbe(target, count, timeout, ctx)
    }

    suspend fun getFirewallRules(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val family = args.str("family", "all").lowercase()
        val commands = ArrayList<String>()
        if (family == "v4" || family == "all") {
            commands.add("echo @ipv4_filter; iptables -L -n -v 2>&1 | head -n 400")
            commands.add("echo @ipv4_nat; iptables -t nat -L -n 2>&1 | head -n 200")
            commands.add("echo @ipv4_mangle; iptables -t mangle -L -n 2>&1 | head -n 100")
        }
        if (family == "v6" || family == "all") {
            commands.add("echo @ipv6_filter; ip6tables -L -n -v 2>&1 | head -n 400")
            commands.add("echo @ipv6_nat; ip6tables -t nat -L -n 2>&1 | head -n 200")
        }
        val io = ctx.shell(commands.joinToString("; "))
        val blocks = splitBlocks(io.out)
        val data = JSONObject()
        for ((key, lines) in blocks) {
            if (key.isEmpty()) continue
            data.put(key, jsonArray(lines))
        }
        val empty = blocks.entries.none { it.key.isNotEmpty() && it.value.isNotEmpty() }
        if (empty) return toolFailed(TOOL_FIREWALL, "iptables_unavailable", io.asText(300))
        data.put("family", family)
        return toolOutcome(TOOL_FIREWALL, true, data = data)
    }

    suspend fun getDnsConfig(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell(
            "echo @props; getprop 2>/dev/null | grep -i 'net\\.dns'; " +
                "echo @resolv; cat /etc/resolv.conf 2>&1; " +
                "echo @connectivity; dumpsys connectivity 2>/dev/null | grep -iA3 'dns' | head -n 60"
        )
        val blocks = splitBlocks(io.out)
        val servers = ArrayList<String>()
        for (line in blocks["resolv"].orEmpty()) {
            val value = line.removePrefix("nameserver").trim()
            if (line.startsWith("nameserver") && value.isNotEmpty()) servers.add(value)
        }
        for (line in blocks["props"].orEmpty()) {
            val value = line.substringAfter(']', "").trim().removePrefix("[").removeSuffix("]")
            if (value.isNotEmpty() && value != "[null]") servers.add(value)
        }
        val data = JSONObject()
            .put("servers", jsonArray(servers.distinct()))
            .put("resolvConf", jsonArray(blocks["resolv"].orEmpty()))
            .put("properties", jsonArray(blocks["props"].orEmpty()))
            .put("connectivity", jsonArray(blocks["connectivity"].orEmpty()))
        if (servers.isEmpty() && blocks["resolv"].orEmpty().isEmpty() && blocks["connectivity"].orEmpty().isEmpty()) {
            return toolFailed(TOOL_DNS, "dns_config_unavailable", io.asText(300))
        }
        return toolOutcome(TOOL_DNS, true, data = data)
    }

    suspend fun scanOpenPorts(args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome {
        val io = ctx.shell("netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null")
        if (io.out.isBlank()) return toolFailed(TOOL_PORTS, "netstat_unavailable", io.asText(300))
        val listeners = ArrayList<Any?>()
        val raw = textLines(io.out)
        for (line in raw.drop(1)) {
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 4) continue
            val local = parts.getOrNull(3) ?: continue
            val port = local.substringAfterLast(':', "")
            listeners.add(
                JSONObject()
                    .put("protocol", parts.first().lowercase())
                    .put("local", local)
                    .put("state", parts.getOrNull(5) ?: parts.last())
                    .put("port", port.toIntOrNull() ?: JSONObject.NULL)
            )
        }
        val data = JSONObject()
            .put("listening", jsonArray(listeners))
            .put("count", listeners.size)
            .put("raw", truncate(io.out, 2500))
        return toolOutcome(TOOL_PORTS, true, data = data)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun pingProbe(
        target: String,
        count: Int,
        timeout: Int,
        ctx: AiToolContext,
    ): AiExecutionOutcome {
        val quoted = ctx.quote(target) ?: return toolFailed(TOOL_CONNECTIVITY, "invalid_target")
        val io = ctx.shell("ping -c $count -W $timeout $quoted 2>&1")
        val text = if (io.out.isNotBlank()) io.out else io.err
        val summary = PING_SUMMARY.find(text)
        val loss = PING_LOSS.find(text)
        val rtt = PING_RTT.find(text)
        val data = JSONObject()
            .put("target", target)
            .put("mode", "ping")
            .put("count", count)
            .put("timeoutSeconds", timeout)
            .put("transmitted", summary?.groupValues?.getOrNull(1)?.toIntOrNull() ?: JSONObject.NULL)
            .put("received", summary?.groupValues?.getOrNull(2)?.toIntOrNull() ?: JSONObject.NULL)
            .put("packetLossPercent", loss?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("rttAvgMs", rtt?.groupValues?.getOrNull(2)?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("exitCode", io.code)
            .put("output", truncate(text.trim(), 2000))
        return toolOutcome(TOOL_CONNECTIVITY, true, data = data)
    }

    private suspend fun httpProbe(target: String, timeout: Int, ctx: AiToolContext): AiExecutionOutcome {
        val url = if (target.startsWith("http://") || target.startsWith("https://")) target else "http://$target"
        val quoted = ctx.quote(url) ?: return toolFailed(TOOL_CONNECTIVITY, "invalid_target")
        val io = ctx.shell(
            "if command -v curl >/dev/null 2>&1; then " +
                "curl -sS -o /dev/null -m $timeout -w '%{http_code} %{time_total} %{remote_ip}' $quoted 2>&1; " +
                "else echo 'curl unavailable'; fi"
        )
        val text = if (io.out.isNotBlank()) io.out else io.err
        val parts = text.trim().split(' ')
        val httpCode = parts.getOrNull(0)?.toIntOrNull()
        val data = JSONObject()
            .put("target", target)
            .put("url", url)
            .put("mode", "http")
            .put("timeoutSeconds", timeout)
            .put("httpCode", httpCode ?: JSONObject.NULL)
            .put("timeTotalSeconds", parts.getOrNull(1)?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("remoteIp", parts.getOrNull(2) ?: JSONObject.NULL)
            .put("reachable", httpCode != null)
            .put("exitCode", io.code)
            .put("output", truncate(text.trim(), 1200))
        return toolOutcome(TOOL_CONNECTIVITY, true, data = data)
    }

    private const val TOOL_NETWORK_STATUS = "get_network_status"
    private const val TOOL_CONNECTIVITY = "test_connectivity"
    private const val TOOL_FIREWALL = "get_firewall_rules"
    private const val TOOL_DNS = "get_dns_config"
    private const val TOOL_PORTS = "scan_open_ports"

    private val IP_ADDR = Regex("^\\d+:\\s+(\\S+)\\s+(inet6?)\\s+(\\S+)")
    private val PING_SUMMARY = Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received")
    private val PING_LOSS = Regex("([\\d.]+)% packet loss")
    private val PING_RTT = Regex("= ([\\d.]+)/([\\d.]+)/")
}
