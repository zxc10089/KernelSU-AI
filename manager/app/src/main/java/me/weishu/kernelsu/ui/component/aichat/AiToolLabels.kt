package me.weishu.kernelsu.ui.component.aichat

import me.weishu.kernelsu.R

/**
 * Business wording per tool, so an approval row, a blocked step or a result card says what will
 * happen ("Delete permanently") instead of echoing the protocol name. A tool missing from this table
 * falls back to its protocol name, so adding a tool never needs an edit here to stay readable.
 *
 * One table, two readers: the console view model (approval rows, batch sheet) and the result card
 * title both read this map, so a tool can never appear under two different names in one screen.
 */
internal val AiToolLabels: Map<String, Int> = mapOf(
    "get_battery_info" to R.string.ai_tool_get_battery_info,
    "get_cpu_memory_info" to R.string.ai_tool_get_cpu_memory_info,
    "get_storage_info" to R.string.ai_tool_get_storage_info,
    "get_system_properties" to R.string.ai_tool_get_system_properties,
    "get_system_logs" to R.string.ai_tool_get_system_logs,
    "find_crash_logs" to R.string.ai_tool_find_crash_logs,
    "get_selinux_status" to R.string.ai_tool_get_selinux_status,
    "get_process_info" to R.string.ai_tool_get_process_info,
    "get_installed_apps" to R.string.ai_tool_get_installed_apps,
    "get_app_info" to R.string.ai_tool_get_app_info,
    "get_app_permissions" to R.string.ai_tool_get_app_permissions,
    "freeze_app" to R.string.ai_tool_freeze_app,
    "unfreeze_app" to R.string.ai_tool_unfreeze_app,
    "clear_app_data" to R.string.ai_tool_clear_app_data,
    "uninstall_app" to R.string.ai_tool_uninstall_app,
    "set_root_policy" to R.string.ai_tool_set_root_policy,
    "check_su_audit_logs" to R.string.ai_tool_check_su_audit_logs,
    "get_network_status" to R.string.ai_tool_get_network_status,
    "test_connectivity" to R.string.ai_tool_test_connectivity,
    "get_firewall_rules" to R.string.ai_tool_get_firewall_rules,
    "get_dns_config" to R.string.ai_tool_get_dns_config,
    "scan_open_ports" to R.string.ai_tool_scan_open_ports,
    "get_module_scripts" to R.string.ai_tool_get_module_scripts,
    "compare_modules" to R.string.ai_tool_compare_modules,
    "backup_module" to R.string.ai_tool_backup_module,
    "restore_module" to R.string.ai_tool_restore_module,
    "generate_module_template" to R.string.ai_tool_generate_module_template,
    "extract_archive" to R.string.ai_tool_extract_archive,
    "check_module_integrity" to R.string.ai_tool_check_module_integrity,
    "import_module_from_sdcard" to R.string.ai_tool_import_module_from_sdcard,
    "list_scheduled_tasks" to R.string.ai_tool_list_scheduled_tasks,
    "schedule_task" to R.string.ai_tool_schedule_task,
    "cancel_task" to R.string.ai_tool_cancel_task,
    "set_boot_script" to R.string.ai_tool_set_boot_script,
    "get_ksud_logs" to R.string.ai_tool_get_ksud_logs,
    "detect_malicious_apps" to R.string.ai_tool_detect_malicious_apps,
    "audit_root_access" to R.string.ai_tool_audit_root_access,
    "check_system_integrity" to R.string.ai_tool_check_system_integrity,
    "detect_root_cloaking" to R.string.ai_tool_detect_root_cloaking,
    "scan_adb_ports" to R.string.ai_tool_scan_adb_ports,
    "get_installed_certificates" to R.string.ai_tool_get_installed_certificates,
    "write_file" to R.string.ai_tool_write_file,
    "move_file" to R.string.ai_tool_move_file,
    "delete_file" to R.string.ai_tool_delete_file,
    "check_file_integrity" to R.string.ai_tool_check_file_integrity,
    "create_directory" to R.string.ai_tool_create_directory,
    "request_user_input" to R.string.ai_tool_request_user_input,
)
