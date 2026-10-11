package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiPathGuard

/**
 * The single source of truth for the structured tool set.
 *
 * design/ai-tool-api.md documents this table; the registry is what the guard, the validator, the
 * model prompt and the console all read. Adding a tool means adding exactly one entry here plus its
 * handler in [AiToolDispatch], and [selfCheck] fails loudly when only one of the two happened.
 */
object AiToolRegistry {

    /**
     * Model facing names that are not separate tools but existing actions under a friendlier name.
     * The parser resolves them, so the model can say `grant_root` and the client runs `grant_su`.
     */
    val ALIASES: Map<String, String> = mapOf(
        "grant_root" to AiAction.GRANT_SU,
        "revoke_root" to AiAction.REVOKE_SU,
        "run_safe_shell" to AiAction.SAFE_EXEC,
        "build_and_install_module" to AiAction.MAKE_MODULE,
    )

    private val NAME_RE = Regex("^[a-z][a-z0-9_]{2,48}\$")

    private fun str(name: String, required: Boolean = false, pattern: String? = null, maxLength: Int = 0) =
        AiToolParam(name = name, type = AiParamType.STRING, required = required, pattern = pattern, maxLength = maxLength)

    private fun path(name: String, required: Boolean = true, scope: AiAccessScope = AiAccessScope.ROOT_FS) =
        AiToolParam(name = name, type = AiParamType.PATH, required = required, pathScope = scope)

    private fun int(name: String, min: Int? = null, max: Int? = null) =
        AiToolParam(name = name, type = AiParamType.INT, min = min, max = max)

    private fun bool(name: String, required: Boolean = false) =
        AiToolParam(name = name, type = AiParamType.BOOL, required = required)

    private fun enum(name: String, vararg values: String) =
        AiToolParam(name = name, type = AiParamType.ENUM, required = true, values = values.toList())

    private fun enumOpt(name: String, vararg values: String) =
        AiToolParam(name = name, type = AiParamType.ENUM, required = false, values = values.toList())

    private fun list(name: String, maxItems: Int, scope: AiAccessScope = AiAccessScope.ROOT_FS) =
        AiToolParam(name = name, type = AiParamType.PATH_LIST, required = false, maxItems = maxItems, pathScope = scope)

    private fun strList(name: String, maxItems: Int) =
        AiToolParam(name = name, type = AiParamType.STRING_LIST, required = false, maxItems = maxItems)

    private fun packageParam() = str("package_name", required = true, pattern = AiToolPatterns.PACKAGE)
    private fun moduleParam(name: String = "module_id") = str(name, required = true, pattern = AiToolPatterns.MODULE_ID)

    /** MEDIUM inside /data/adb, STRICT anywhere else: the two file scopes are not equally forgiving. */
    private fun pathRisk(args: Map<String, Any?>): AiRiskLevel {
        val p = args["path"] as? String ?: args["destination"] as? String ?: return AiRiskLevel.HIGH
        return if (AiPathGuard.requiredScope(p) == AiAccessScope.DATA_ADB) AiRiskLevel.MEDIUM else AiRiskLevel.HIGH
    }

    private fun moveRisk(args: Map<String, Any?>): AiRiskLevel {
        val a = args["source"] as? String
        val b = args["destination"] as? String
        val root = listOfNotNull(a, b).any { AiPathGuard.requiredScope(it) == AiAccessScope.ROOT_FS }
        return if (root || a == null || b == null) AiRiskLevel.HIGH else AiRiskLevel.MEDIUM
    }

    val specs: List<AiToolSpec> = listOf(
        // ---------------------------------------------------------------- (1) system diagnostics
        AiToolSpec(
            name = "get_battery_info",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取电量、温度、健康度与充电状态",
        ),
        AiToolSpec(
            name = "get_cpu_memory_info",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取 CPU 负载、各核心当前频率与内存占用",
        ),
        AiToolSpec(
            name = "get_storage_info",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取 /data、/system、/data/adb 的空间使用",
        ),
        AiToolSpec(
            name = "get_system_properties",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取系统属性（不传参数时返回常用键）",
            params = listOf(str("prop_name", pattern = AiToolPatterns.PROP)),
        ),
        AiToolSpec(
            name = "get_system_logs",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取 logcat / dmesg / kmsg，可按级别与 TAG 过滤",
            params = listOf(
                enumOpt("type", "logcat", "dmesg", "kmsg"),
                enumOpt("level", "V", "D", "I", "W", "E", "F"),
                str("tag", maxLength = 64),
                int("lines", 1, 500),
            ),
        ),
        AiToolSpec(
            name = "find_crash_logs",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "查找指定应用的历史崩溃日志与 tombstone",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "get_selinux_status",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "读取当前 SELinux 模式（Enforcing / Permissive）",
        ),
        AiToolSpec(
            name = "get_process_info",
            category = AiToolCategory.SYSTEM,
            risk = AiRiskLevel.LOW,
            summary = "查看进程的详细状态、内存占用与打开的文件",
            params = listOf(str("pid_or_name", required = true, pattern = AiToolPatterns.PID_OR_NAME)),
        ),

        // ---------------------------------------------------------------- (2) apps and root grants
        AiToolSpec(
            name = "get_installed_apps",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.LOW,
            summary = "获取用户应用 / 系统应用 / 游戏 / 已停用应用的结构化列表",
            params = listOf(enumOpt("type", "user", "system", "all", "disabled")),
        ),
        AiToolSpec(
            name = "get_app_info",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.LOW,
            summary = "获取版本、安装路径、UID、体积与签名",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "get_app_permissions",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.LOW,
            summary = "获取应用申请与已授予的权限（含危险权限）",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "freeze_app",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "冻结（停用）应用，可撤销",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "unfreeze_app",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "解冻（启用）应用，可撤销",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "clear_app_data",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.HIGH,
            summary = "清除应用全部数据（不可撤销）",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "uninstall_app",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.HIGH,
            summary = "卸载应用（不可撤销）",
            params = listOf(packageParam()),
        ),
        AiToolSpec(
            name = "set_root_policy",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.MEDIUM,
            needsShell = false,
            undoable = true,
            summary = "修改单个应用的 root 策略：allow / deny / prompt",
            params = listOf(packageParam(), enum("policy", "allow", "deny", "prompt")),
        ),
        AiToolSpec(
            name = "check_su_audit_logs",
            category = AiToolCategory.APP,
            risk = AiRiskLevel.LOW,
            summary = "列出当前持有 root 授权的应用，排查异常提权",
            params = listOf(int("limit", 1, 500)),
        ),

        // ---------------------------------------------------------------- (3) network
        AiToolSpec(
            name = "get_network_status",
            category = AiToolCategory.NETWORK,
            risk = AiRiskLevel.LOW,
            summary = "读取网络接口、地址、路由与 Wi-Fi 状态",
        ),
        AiToolSpec(
            name = "test_connectivity",
            category = AiToolCategory.NETWORK,
            risk = AiRiskLevel.LOW,
            summary = "向指定主机做 ping 或 HTTP 请求，测试连通性",
            params = listOf(
                str("target", required = true, pattern = "^[A-Za-z0-9._:/?&=%~-]{1,512}\$"),
                enumOpt("mode", "ping", "http"),
                int("count", 1, 8),
                int("timeout", 1, 30),
            ),
        ),
        AiToolSpec(
            name = "get_firewall_rules",
            category = AiToolCategory.NETWORK,
            risk = AiRiskLevel.LOW,
            summary = "查看 iptables / ip6tables / nft 规则（广告拦截模块常用）",
            params = listOf(enumOpt("family", "v4", "v6", "all")),
        ),
        AiToolSpec(
            name = "get_dns_config",
            category = AiToolCategory.NETWORK,
            risk = AiRiskLevel.LOW,
            summary = "查看当前 DNS 服务器配置",
        ),
        AiToolSpec(
            name = "scan_open_ports",
            category = AiToolCategory.NETWORK,
            risk = AiRiskLevel.LOW,
            summary = "列出本机监听端口（只读监听表，不对外发包）",
        ),

        // ---------------------------------------------------------------- (4) modules
        AiToolSpec(
            name = "get_module_scripts",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.LOW,
            summary = "读取模块的 module.prop 与各生命周期脚本",
            params = listOf(moduleParam()),
        ),
        AiToolSpec(
            name = "compare_modules",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.LOW,
            summary = "对比两个模块的文件清单、prop 与脚本差异",
            params = listOf(moduleParam("module_a"), moduleParam("module_b")),
        ),
        AiToolSpec(
            name = "backup_module",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "把模块完整备份为 tar.gz 归档，可撤销",
            params = listOf(moduleParam()),
        ),
        AiToolSpec(
            name = "restore_module",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.HIGH,
            undoable = true,
            summary = "从备份恢复模块（现模块先移入回收站，可撤销）",
            params = listOf(moduleParam(), path("backup_path", scope = AiAccessScope.DATA_ADB)),
        ),
        AiToolSpec(
            name = "generate_module_template",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.LOW,
            needsShell = false,
            summary = "生成模块骨架代码（module.prop + 脚本），不落盘",
            params = listOf(
                enum("type", "basic", "systemless", "app_replace", "overlay"),
                str("id", required = true, pattern = AiToolPatterns.MODULE_ID),
                str("name"),
                str("description", maxLength = 300),
            ),
        ),
        AiToolSpec(
            name = "extract_archive",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "解压 zip 归档到客户端固定目录，可撤销",
            params = listOf(
                path("path", scope = AiAccessScope.DATA_ADB),
                str("subdir", pattern = AiToolPatterns.FILE_NAME),
            ),
        ),
        AiToolSpec(
            name = "import_module_from_sdcard",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.HIGH,
            // The staged copy could be deleted, but the module ksud installed would stay: no reverse.
            undoable = false,
            summary = "把 /sdcard 下的模块 zip 暂存到 /data/adb/AI/staging 后交给 ksud 安装",
            params = listOf(path("path", required = true)),
        ),
        AiToolSpec(
            name = "check_module_integrity",
            category = AiToolCategory.MODULE,
            risk = AiRiskLevel.LOW,
            summary = "检查模块是否损坏、缺文件或权限错误",
            params = listOf(moduleParam()),
        ),

        // ---------------------------------------------------------------- (5) automation
        AiToolSpec(
            name = "list_scheduled_tasks",
            category = AiToolCategory.AUTOMATION,
            risk = AiRiskLevel.LOW,
            summary = "列出开机脚本与定时任务",
        ),
        AiToolSpec(
            name = "schedule_task",
            category = AiToolCategory.AUTOMATION,
            risk = AiRiskLevel.HIGH,
            undoable = true,
            summary = "创建定时任务（5 段 cron + 脚本路径），可取消",
            params = listOf(
                str("id", required = true, pattern = AiToolPatterns.TASK_ID),
                str("cron_expression", required = true, maxLength = 120),
                path("script_path", scope = AiAccessScope.DATA_ADB),
            ),
        ),
        AiToolSpec(
            name = "cancel_task",
            category = AiToolCategory.AUTOMATION,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "取消指定定时任务（移入回收站，可撤销）",
            params = listOf(str("task_id", required = true, pattern = AiToolPatterns.TASK_ID)),
        ),
        AiToolSpec(
            name = "set_boot_script",
            category = AiToolCategory.AUTOMATION,
            risk = AiRiskLevel.HIGH,
            undoable = true,
            summary = "写入开机自启脚本，可撤销",
            params = listOf(
                str("name", required = true, pattern = AiToolPatterns.FILE_NAME),
                str("script_content", required = true),
                bool("overwrite"),
            ),
        ),
        AiToolSpec(
            name = "get_ksud_logs",
            category = AiToolCategory.AUTOMATION,
            risk = AiRiskLevel.LOW,
            summary = "查看 KernelSU 守护进程自身的日志",
            params = listOf(int("lines", 1, 1000)),
        ),

        // ---------------------------------------------------------------- (6) security audit
        AiToolSpec(
            name = "detect_malicious_apps",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            summary = "按安装来源、权限组合与 root 持有情况筛查可疑应用",
        ),
        AiToolSpec(
            name = "audit_root_access",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            summary = "审计所有持有 root 的应用并给出风险评估",
        ),
        AiToolSpec(
            name = "check_system_integrity",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            summary = "检查关键系统文件是否被篡改，并给出哈希",
            params = listOf(list("paths", maxItems = 12)),
        ),
        AiToolSpec(
            name = "detect_root_cloaking",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            summary = "检测当前 Root 隐藏状态是否被攻破",
        ),
        AiToolSpec(
            name = "scan_adb_ports",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            summary = "检测是否开启了不安全的 ADB 网络调试",
        ),
        AiToolSpec(
            name = "get_installed_certificates",
            category = AiToolCategory.SECURITY,
            risk = AiRiskLevel.LOW,
            needsShell = false,
            summary = "列出系统与用户证书，排查恶意证书",
            params = listOf(enumOpt("store", "system", "user", "all")),
        ),

        // ---------------------------------------------------------------- (7) files
        AiToolSpec(
            name = "write_file",
            category = AiToolCategory.FILE,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "写入文件（默认先备份，可撤销）",
            params = listOf(path("path"), str("content", required = true), bool("backup")),
            riskFor = { args -> pathRisk(args) },
        ),
        AiToolSpec(
            name = "move_file",
            category = AiToolCategory.FILE,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "移动文件，可撤销",
            params = listOf(path("source"), path("destination")),
            riskFor = { args -> moveRisk(args) },
        ),
        AiToolSpec(
            name = "delete_file",
            category = AiToolCategory.FILE,
            risk = AiRiskLevel.HIGH,
            undoable = true,
            summary = "删除文件：默认移入回收站（可撤销），force=true 时真正删除",
            params = listOf(path("path"), bool("force")),
            riskFor = { args -> if (args["force"] == true) AiRiskLevel.FATAL else AiRiskLevel.HIGH },
        ),
        AiToolSpec(
            name = "check_file_integrity",
            category = AiToolCategory.FILE,
            risk = AiRiskLevel.LOW,
            summary = "计算文件哈希并与期望值比对",
            params = listOf(
                path("path"),
                enumOpt("algorithm", "md5", "sha256"),
                str("expected", maxLength = 128),
            ),
        ),
        AiToolSpec(
            name = "create_directory",
            category = AiToolCategory.FILE,
            risk = AiRiskLevel.MEDIUM,
            undoable = true,
            summary = "创建目录，可撤销",
            params = listOf(path("path")),
        ),

        // ---------------------------------------------------------------- (8) human in the loop
        AiToolSpec(
            name = "request_user_input",
            category = AiToolCategory.INTERACT,
            risk = AiRiskLevel.LOW,
            needsShell = false,
            summary = "向用户提问并等待答复（可选答案列表）",
            params = listOf(
                str("question", required = true, maxLength = 500),
                strList("options", maxItems = 8),
                bool("allow_free_text"),
            ),
        ),
    )

    private val byName: Map<String, AiToolSpec> by lazy { specs.associateBy { it.name } }

    fun byName(name: String): AiToolSpec? = byName[name]

    fun contains(name: String): Boolean = byName.containsKey(name)

    fun names(): Set<String> = byName.keys

    /** Alias target for a model facing name, or the name itself when it is already canonical. */
    fun resolveAlias(name: String): String = ALIASES[name] ?: name

    fun categoryTitle(category: AiToolCategory): String = when (category) {
        AiToolCategory.SYSTEM -> "系统诊断"
        AiToolCategory.APP -> "应用与权限"
        AiToolCategory.NETWORK -> "网络与连接"
        AiToolCategory.MODULE -> "模块高级"
        AiToolCategory.AUTOMATION -> "自动化与调度"
        AiToolCategory.SECURITY -> "安全与审计"
        AiToolCategory.FILE -> "文件与脚本"
        AiToolCategory.INTERACT -> "人机协同"
    }

    /** The tool catalogue the model sees. Generated, so it can never drift from [specs]. */
    fun promptBlock(): String {
        val sb = StringBuilder()
        sb.append("【结构化工具】actions[].action 只能取下列名字（或别名），参数名必须与声明一致，不要自创参数。\n")
        for (category in AiToolCategory.values()) {
            val group = specs.filter { it.category == category }
            if (group.isEmpty()) continue
            sb.append('\n').append(categoryTitle(category)).append('\n')
            for (spec in group) {
                sb.append("- ").append(signature(spec))
                if (spec.risk == AiRiskLevel.HIGH || spec.risk == AiRiskLevel.FATAL) sb.append(" [高风险，需用户确认]")
                sb.append("：").append(spec.summary).append('\n')
            }
        }
        sb.append("\n【别名】grant_root=授权 root，revoke_root=撤销 root，run_safe_shell=受限只读 shell，")
        sb.append("build_and_install_module=make_module。\n")
        return sb.toString()
    }

    fun signature(spec: AiToolSpec): String {
        val parts = ArrayList<String>()
        for (p in spec.params) if (p.required) parts.add(p.name + "=" + typeLabel(p))
        for (p in spec.params) if (!p.required) parts.add(p.name + "?=" + typeLabel(p))
        return spec.name + "(" + parts.joinToString(", ") + ")"
    }

    private fun typeLabel(param: AiToolParam): String = when (param.type) {
        AiParamType.ENUM -> param.values.joinToString("|")
        AiParamType.INT -> "整数"
        AiParamType.BOOL -> "true|false"
        AiParamType.STRING_LIST -> "字符串数组"
        AiParamType.PATH_LIST -> "路径数组"
        AiParamType.PATH -> "绝对路径"
        AiParamType.STRING -> "字符串"
    }

    /** Cross checks the registry against the handlers; returns "OK" or the list of problems. */
    fun selfCheck(): String {
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for (spec in specs) {
            if (!NAME_RE.matches(spec.name)) problems.add("非法工具名：" + spec.name)
            if (!seen.add(spec.name)) problems.add("重复工具名：" + spec.name)
            if (spec.summary.isBlank()) problems.add("缺少说明：" + spec.name)
            val paramNames = HashSet<String>()
            for (param in spec.params) {
                if (!paramNames.add(param.name)) problems.add(spec.name + " 重复参数：" + param.name)
                if (param.type == AiParamType.ENUM && param.values.isEmpty()) {
                    problems.add(spec.name + "." + param.name + " ENUM 缺少取值")
                }
                if ((param.type == AiParamType.PATH || param.type == AiParamType.PATH_LIST) &&
                    param.pathScope == AiAccessScope.NONE
                ) {
                    problems.add(spec.name + "." + param.name + " PATH 范围非法")
                }
            }
        }
        for ((alias, target) in ALIASES) {
            if (!seen.contains(target) && !ACTION_TARGETS.contains(target)) {
                problems.add("别名 " + alias + " 指向未知目标 " + target)
            }
            if (seen.contains(alias)) problems.add("别名与工具名冲突：" + alias)
        }
        val handled = AiToolDispatch.handlerNames()
        for (spec in specs) if (!handled.contains(spec.name)) problems.add("缺少 handler：" + spec.name)
        for (name in handled) if (!seen.contains(name)) problems.add("handler 未被注册：" + name)
        return if (problems.isEmpty()) "OK" else problems.joinToString("\n")
    }

    /** Action constants an alias may legitimately point at (the 15 pre-existing verbs). */
    private val ACTION_TARGETS = setOf(
        AiAction.GRANT_SU,
        AiAction.REVOKE_SU,
        AiAction.SAFE_EXEC,
        AiAction.MAKE_MODULE,
        AiAction.ROOT_APP_LIST,
        AiAction.READ_FILE,
        AiAction.LIST_DIR,
        AiAction.MOVE_TO_TRASH,
        AiAction.INSTALL_MODULE,
        AiAction.ENABLE_MODULE,
        AiAction.DISABLE_MODULE,
        AiAction.RUN_COMMAND,
    )
}
