package me.weishu.kernelsu.data.modulemaker

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file the module ships, relative to the module root. */
@Immutable
data class ModuleMakerFile(
    val path: String = "",
    val content: String = "",
)

/** Everything the module maker collects before it writes an installable zip. */
@Immutable
data class ModuleDraft(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0",
    val versionCode: String = "1",
    val author: String = "",
    val description: String = "",
    val files: List<ModuleMakerFile> = emptyList(),
    val installScript: String = "",
)

sealed interface ModuleDraftError {
    data object InvalidId : ModuleDraftError
    data object MissingName : ModuleDraftError
    data object MissingVersion : ModuleDraftError
    data object InvalidVersionCode : ModuleDraftError
    data class InvalidFilePath(val path: String) : ModuleDraftError
    data class DuplicateFilePath(val path: String) : ModuleDraftError
    data class TooManyFiles(val max: Int) : ModuleDraftError
    data class FileTooLarge(val path: String, val max: Int) : ModuleDraftError
    data class TotalTooLarge(val max: Int) : ModuleDraftError
}

object ModuleDraftValidator {
    /** The same shape ksud enforces: userspace/ksud/src/module.rs:51 validate_module_id. */
    val ID_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

    /**
     * Ceilings for a draft an AI model wrote. A human typing into the form rarely hits them, but a
     * model can propose megabytes of file content in one turn, so the limits belong to validation.
     */
    const val MAX_FILES = 20
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_TOTAL_BYTES = 128 * 1024
    const val MAX_INSTALL_SCRIPT_BYTES = 32 * 1024

    /** Reported as a file path when the customize.sh body itself is over the ceiling. */
    const val INSTALL_SCRIPT_PATH = "customize.sh"

    fun validate(draft: ModuleDraft): List<ModuleDraftError> {
        val errors = ArrayList<ModuleDraftError>()
        if (!ID_PATTERN.matches(draft.id.trim())) errors.add(ModuleDraftError.InvalidId)
        if (draft.name.isBlank()) errors.add(ModuleDraftError.MissingName)
        if (draft.version.isBlank()) errors.add(ModuleDraftError.MissingVersion)
        val code = draft.versionCode.trim().toIntOrNull()
        if (code == null || code <= 0) errors.add(ModuleDraftError.InvalidVersionCode)
        if (draft.files.size > MAX_FILES) errors.add(ModuleDraftError.TooManyFiles(MAX_FILES))
        val seen = HashSet<String>()
        var total = 0
        draft.files.forEach { file ->
            val path = file.path.trim()
            if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.contains("..")) {
                errors.add(ModuleDraftError.InvalidFilePath(path))
            } else if (!seen.add(path)) {
                errors.add(ModuleDraftError.DuplicateFilePath(path))
            }
            val bytes = file.content.toByteArray(Charsets.UTF_8).size
            total += bytes
            if (bytes > MAX_FILE_BYTES) errors.add(ModuleDraftError.FileTooLarge(path, MAX_FILE_BYTES))
        }
        if (draft.installScript.toByteArray(Charsets.UTF_8).size > MAX_INSTALL_SCRIPT_BYTES) {
            errors.add(ModuleDraftError.FileTooLarge(INSTALL_SCRIPT_PATH, MAX_INSTALL_SCRIPT_BYTES))
        }
        if (total > MAX_TOTAL_BYTES) errors.add(ModuleDraftError.TotalTooLarge(MAX_TOTAL_BYTES))
        return errors
    }
}

object ModulePropRenderer {
    fun render(draft: ModuleDraft): String {
        val builder = StringBuilder()
        builder.append("id=").append(draft.id.trim()).append("\n")
        builder.append("name=").append(draft.name.trim()).append("\n")
        builder.append("version=").append(draft.version.trim()).append("\n")
        builder.append("versionCode=").append(draft.versionCode.trim()).append("\n")
        if (draft.author.isNotBlank()) builder.append("author=").append(draft.author.trim()).append("\n")
        if (draft.description.isNotBlank()) {
            builder.append("description=").append(draft.description.replace("\n", " ").trim()).append("\n")
        }
        return builder.toString()
    }
}

data class ModulePackageEntry(val path: String, val bytes: Int)

object ModulePackage {
    const val PROP_NAME = "module.prop"
    const val INSTALL_SCRIPT_NAME = "customize.sh"

    fun entries(draft: ModuleDraft): List<ModulePackageEntry> {
        val list = ArrayList<ModulePackageEntry>()
        list.add(ModulePackageEntry(PROP_NAME, ModulePropRenderer.render(draft).toByteArray(Charsets.UTF_8).size))
        draft.files.forEach { file ->
            list.add(ModulePackageEntry(file.path.trim(), file.content.toByteArray(Charsets.UTF_8).size))
        }
        if (draft.installScript.isNotBlank()) {
            list.add(ModulePackageEntry(INSTALL_SCRIPT_NAME, draft.installScript.toByteArray(Charsets.UTF_8).size))
        }
        return list
    }

    /**
     * Writes the draft as a plain zip. A module.prop at the zip root is all ksud needs
     * (userspace/ksud/src/module.rs:523 install_module_to_system), so no META-INF wrapper.
     */
    fun build(draft: ModuleDraft, dir: File): File {
        val errors = ModuleDraftValidator.validate(draft)
        check(errors.isEmpty()) { "invalid draft: $errors" }
        if (!dir.exists() && !dir.mkdirs()) error("cannot create " + dir.absolutePath)
        val out = File(dir, draft.id.trim() + ".zip")
        if (out.exists()) out.delete()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            writeEntry(zip, PROP_NAME, ModulePropRenderer.render(draft))
            draft.files.forEach { file -> writeEntry(zip, file.path.trim(), file.content) }
            if (draft.installScript.isNotBlank()) writeEntry(zip, INSTALL_SCRIPT_NAME, draft.installScript)
        }
        return out
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
