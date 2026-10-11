package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiExecutionOutcome

/**
 * The single place where a tool name becomes code.
 *
 * [AiToolRegistry.selfCheck] compares this table against the registry in both directions, so a tool the
 * model is told about but nobody implemented - or a handler no model can ever reach - is reported as a
 * problem instead of failing silently at runtime.
 */
object AiToolDispatch {

    private val TABLE: Map<String, suspend (Map<String, Any?>, AiToolContext) -> AiExecutionOutcome> = mapOf(
        // ---------------------------------------------------------------- (1) system diagnostics
        "get_battery_info" to { a, c -> AiSystemTools.getBatteryInfo(a, c) },
        "get_cpu_memory_info" to { a, c -> AiSystemTools.getCpuMemoryInfo(a, c) },
        "get_storage_info" to { a, c -> AiSystemTools.getStorageInfo(a, c) },
        "get_system_properties" to { a, c -> AiSystemTools.getSystemProperties(a, c) },
        "get_system_logs" to { a, c -> AiSystemTools.getSystemLogs(a, c) },
        "find_crash_logs" to { a, c -> AiSystemTools.findCrashLogs(a, c) },
        "get_selinux_status" to { a, c -> AiSystemTools.getSelinuxStatus(a, c) },
        "get_process_info" to { a, c -> AiSystemTools.getProcessInfo(a, c) },

        // ---------------------------------------------------------------- (2) apps and root grants
        "get_installed_apps" to { a, c -> AiAppTools.getInstalledApps(a, c) },
        "get_app_info" to { a, c -> AiAppTools.getAppInfo(a, c) },
        "get_app_permissions" to { a, c -> AiAppTools.getAppPermissions(a, c) },
        "freeze_app" to { a, c -> AiAppTools.freezeApp(a, c) },
        "unfreeze_app" to { a, c -> AiAppTools.unfreezeApp(a, c) },
        "clear_app_data" to { a, c -> AiAppTools.clearAppData(a, c) },
        "uninstall_app" to { a, c -> AiAppTools.uninstallApp(a, c) },
        "set_root_policy" to { a, c -> AiAppTools.setRootPolicy(a, c) },
        "check_su_audit_logs" to { a, c -> AiAppTools.checkSuAuditLogs(a, c) },

        // ---------------------------------------------------------------- (3) network
        "get_network_status" to { a, c -> AiNetworkTools.getNetworkStatus(a, c) },
        "test_connectivity" to { a, c -> AiNetworkTools.testConnectivity(a, c) },
        "get_firewall_rules" to { a, c -> AiNetworkTools.getFirewallRules(a, c) },
        "get_dns_config" to { a, c -> AiNetworkTools.getDnsConfig(a, c) },
        "scan_open_ports" to { a, c -> AiNetworkTools.scanOpenPorts(a, c) },

        // ---------------------------------------------------------------- (4) modules
        "get_module_scripts" to { a, c -> AiModuleTools.getModuleScripts(a, c) },
        "compare_modules" to { a, c -> AiModuleTools.compareModules(a, c) },
        "backup_module" to { a, c -> AiModuleTools.backupModule(a, c) },
        "restore_module" to { a, c -> AiModuleTools.restoreModule(a, c) },
        "generate_module_template" to { a, c -> AiModuleTools.generateModuleTemplate(a, c) },
        "extract_archive" to { a, c -> AiModuleTools.extractArchive(a, c) },
        "import_module_from_sdcard" to { a, c -> AiModuleTools.importModuleFromSdcard(a, c) },
        "check_module_integrity" to { a, c -> AiModuleTools.checkModuleIntegrity(a, c) },

        // ---------------------------------------------------------------- (5) automation
        "list_scheduled_tasks" to { a, c -> AiAutomationTools.listScheduledTasks(a, c) },
        "schedule_task" to { a, c -> AiAutomationTools.scheduleTask(a, c) },
        "cancel_task" to { a, c -> AiAutomationTools.cancelTask(a, c) },
        "set_boot_script" to { a, c -> AiAutomationTools.setBootScript(a, c) },
        "get_ksud_logs" to { a, c -> AiAutomationTools.getKsudLogs(a, c) },

        // ---------------------------------------------------------------- (6) security
        "detect_malicious_apps" to { a, c -> AiSecurityTools.detectMaliciousApps(a, c) },
        "audit_root_access" to { a, c -> AiSecurityTools.auditRootAccess(a, c) },
        "check_system_integrity" to { a, c -> AiSecurityTools.checkSystemIntegrity(a, c) },
        "detect_root_cloaking" to { a, c -> AiSecurityTools.detectRootCloaking(a, c) },
        "scan_adb_ports" to { a, c -> AiSecurityTools.scanAdbPorts(a, c) },
        "get_installed_certificates" to { a, c -> AiSecurityTools.getInstalledCertificates(a, c) },

        // ---------------------------------------------------------------- (7) files
        "write_file" to { a, c -> AiFileTools.writeFile(a, c) },
        "move_file" to { a, c -> AiFileTools.moveFile(a, c) },
        "delete_file" to { a, c -> AiFileTools.deleteFile(a, c) },
        "check_file_integrity" to { a, c -> AiFileTools.checkFileIntegrity(a, c) },
        "create_directory" to { a, c -> AiFileTools.createDirectory(a, c) },

        // ---------------------------------------------------------------- (8) human in the loop
        "request_user_input" to { a, c -> AiInteractTools.requestUserInput(a, c) },
    )

    /** Every tool name this build can actually execute. Consumed by the registry self check. */
    fun handlerNames(): Set<String> = TABLE.keys

    suspend fun run(toolName: String, args: Map<String, Any?>, ctx: AiToolContext): AiExecutionOutcome =
        TABLE[toolName]?.invoke(args, ctx)
            ?: toolFailed(
                toolName,
                "unknown_tool",
                "本机没有名为「" + toolName + "」的工具，请只使用清单里列出的工具。",
            )
}
