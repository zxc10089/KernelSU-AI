package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.ModuleConflict
import me.weishu.kernelsu.data.model.ModuleConflictLevel
import me.weishu.kernelsu.data.model.ModuleConflictReport
import me.weishu.kernelsu.data.model.ModuleConflictType
import me.weishu.kernelsu.ui.util.getRootShell

/**
 * Local, deterministic conflict scan (spec 3.6).
 *
 * A module counts as enabled exactly when it has no disable file, which is the rule the daemon
 * itself uses, so the result does not depend on the module list JSON parsing. Every command is
 * read-only and the model is not involved, so the page works with no API key configured.
 */
class ModuleConflictRepositoryImpl : ModuleConflictRepository {

    override suspend fun scan(): Result<ModuleConflictReport> = withContext(Dispatchers.IO) {
        runCatching { scanBlocking() }
    }

    private fun scanBlocking(): ModuleConflictReport {
        val names = shellLines("ls -1 " + MODULES_DIR)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .take(MAX_MODULES)
        if (names.isEmpty()) {
            return ModuleConflictReport(scannedModules = 0, conflicts = emptyList())
        }

        val dirs = names.map { name ->
            val path = MODULES_DIR + "/" + name
            ModuleDir(
                name = name,
                path = path,
                propId = readValue(path + "/module.prop", "id"),
                enabled = shellLines("test -e " + path + "/disable && echo disabled").isEmpty(),
            )
        }
        val enabled = dirs.filter { it.enabled }
        val conflicts = ArrayList<ModuleConflict>()

        enabled.groupBy { it.propId }.forEach { entry ->
            val id = entry.key
            val group = entry.value
            if (id != null && group.size > 1) {
                conflicts.add(
                    ModuleConflict(
                        level = ModuleConflictLevel.HIGH,
                        type = ModuleConflictType.DUPLICATE_ID,
                        subject = id,
                        modules = group.map { it.name }.sorted(),
                    ),
                )
            }
        }

        enabled.forEach { dir ->
            if (dir.propId == null) {
                conflicts.add(
                    ModuleConflict(
                        level = ModuleConflictLevel.MEDIUM,
                        type = ModuleConflictType.MISSING_PROP,
                        subject = dir.name,
                        modules = listOf(dir.name),
                    ),
                )
            } else if (dir.propId != dir.name) {
                conflicts.add(
                    ModuleConflict(
                        level = ModuleConflictLevel.MEDIUM,
                        type = ModuleConflictType.DIR_ID_MISMATCH,
                        subject = dir.name,
                        modules = listOf(dir.name),
                    ),
                )
            }
        }

        val fileOwners = HashMap<String, MutableList<String>>()
        enabled.forEach { dir ->
            shellLines("find " + dir.path + "/system -type f")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_FILES_PER_MODULE)
                .forEach { file ->
                    val relative = file.removePrefix(dir.path + "/")
                    fileOwners.getOrPut(relative) { ArrayList<String>() }.add(dir.name)
                }
        }
        fileOwners.forEach { entry ->
            val owners = entry.value.distinct().sorted()
            if (owners.size > 1) {
                conflicts.add(
                    ModuleConflict(
                        level = ModuleConflictLevel.HIGH,
                        type = ModuleConflictType.FILE_OVERLAP,
                        subject = entry.key,
                        modules = owners,
                    ),
                )
            }
        }

        val propOwners = HashMap<String, MutableList<String>>()
        enabled.forEach { dir ->
            readPropertyKeys(dir.path + "/system.prop").forEach { key ->
                propOwners.getOrPut(key) { ArrayList<String>() }.add(dir.name)
            }
        }
        propOwners.forEach { entry ->
            val owners = entry.value.distinct().sorted()
            if (owners.size > 1) {
                conflicts.add(
                    ModuleConflict(
                        level = ModuleConflictLevel.MEDIUM,
                        type = ModuleConflictType.PROP_DUPLICATE,
                        subject = entry.key,
                        modules = owners,
                    ),
                )
            }
        }

        val ordered = conflicts
            .sortedWith(compareBy({ it.level.ordinal }, { it.subject }))
            .take(MAX_CONFLICTS)
        return ModuleConflictReport(scannedModules = enabled.size, conflicts = ordered)
    }

    private fun readValue(path: String, key: String): String? = shellLines("cat " + path)
        .firstOrNull { it.trim().startsWith(key + "=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun readPropertyKeys(path: String): List<String> = shellLines("cat " + path)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotEmpty() }

    private fun shellLines(command: String): List<String> {
        val result = getRootShell().newJob().add(command).to(ArrayList(), null).exec()
        return if (result.code == 0) result.out.map { it.trim() } else emptyList()
    }

    private data class ModuleDir(
        val name: String,
        val path: String,
        val propId: String?,
        val enabled: Boolean,
    )

    private companion object {
        const val MODULES_DIR = "/data/adb/modules"

        /** A device with more modules than this is not worth scanning in one pass. */
        const val MAX_MODULES = 200

        /** Per module cap so one huge module cannot stall the scan. */
        const val MAX_FILES_PER_MODULE = 2000

        const val MAX_CONFLICTS = 200
    }
}
