package me.weishu.kernelsu.data.repository

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.HidingPack
import me.weishu.kernelsu.data.model.HidingPackCategory
import me.weishu.kernelsu.data.model.HidingPackEntry
import me.weishu.kernelsu.data.model.HidingPackMode
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.execKsud
import me.weishu.kernelsu.ui.util.withNewRootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

private const val TAG = "HidingPackRepository"
private const val MANIFEST_ASSET = "hiding/manifest.json"
private const val PACK_CACHE_DIR = "hiding_pack"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Root `pm install` decides; only its failure falls back to the system package installer. */
private const val DETECTOR_INSTALL_TIMEOUT_MS = 60_000L
private const val DETECTOR_POLL_INTERVAL_MS = 1_000L

private data class RootCommandResult(val code: Int, val output: String)

interface HidingPackRepository {
    /** Parses `assets/hiding/manifest.json`. Never throws; failures stay inside the [Result]. */
    suspend fun loadManifest(): Result<HidingPack>

    /** Resolves which of the given pack entries are already present on the device. */
    suspend fun installedIds(entries: List<HidingPackEntry>): Set<String>

    /** Installs one entry. Never throws; failures stay inside the [Result]. */
    suspend fun install(entry: HidingPackEntry): Result<Unit>
}

class HidingPackRepositoryImpl(
    private val moduleRepository: ModuleRepository = ModuleRepositoryImpl(),
) : HidingPackRepository {

    override suspend fun loadManifest(): Result<HidingPack> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = ksuApp.assets.open(MANIFEST_ASSET)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            parseManifest(JSONObject(raw))
        }
    }

    override suspend fun installedIds(entries: List<HidingPackEntry>): Set<String> =
        withContext(Dispatchers.IO) {
            val modulesById = moduleRepository.getModules()
                .getOrNull()
                .orEmpty()
                .associateBy { it.id }
            // One root call for all detectors instead of one per entry; empty when there is no
            // root, in which case the honest answer is "not installed".
            val installedPackages = if (entries.any { it.category == HidingPackCategory.Detector }) {
                installedPackagesViaRoot()
            } else {
                emptySet()
            }
            entries.filter { entry ->
                when (entry.category) {
                    // Module archives are unpacked by ksud into /data/adb/modules. Presence alone is
                    // not enough: the pack ships one specific build per module, so a module that
                    // sits on the device at another versionCode must stay installable. Equality
                    // rather than "installed < pack" because a fork may renumber downwards - the
                    // AlwaysStrong build of tricky_store declares 104 where the TEESimulator-RS
                    // build it replaces declared 235/307. versionCode <= 0 means the manifest does
                    // not carry one, in which case presence is all we can honestly assert.
                    HidingPackCategory.Module -> {
                        val installed = modulesById[entry.moduleId ?: entry.id]
                        installed != null &&
                            (entry.versionCode <= 0 || installed.versionCode == entry.versionCode)
                    }

                    HidingPackCategory.PathMask -> isPathMaskVariantInstalled(entry, modulesById)
                    HidingPackCategory.Detector -> entry.packageName?.let {
                        it in installedPackages || isPackageInstalled(it)
                    } ?: false

                    HidingPackCategory.Data -> false
                }
            }.map { it.id }.toSet()
        }

    /**
     * Every PathMask branch ships `id=pathmask`, so the module id alone cannot say which branch is
     * on the device: only one of the six can be installed at a time and ksud keeps the branch in
     * the installed module.prop's `updateJson` (`.../android16-6.12.json`). When the manifest
     * carries no branch for the entry, the module's presence is all we can honestly assert.
     *
     * A branch that is on the device at another versionCode is an older build of that very branch
     * (the branches are versioned separately), so the row has to offer its update instead of
     * claiming to be installed already.
     */
    private fun isPathMaskVariantInstalled(
        entry: HidingPackEntry,
        modulesById: Map<String, Module>,
    ): Boolean {
        val installed = modulesById[entry.moduleId ?: entry.id] ?: return false
        if (entry.versionCode > 0 && installed.versionCode != entry.versionCode) return false
        val branch = entry.branch ?: return true
        return installed.updateJson.substringAfterLast('/') == "$branch.json"
    }

    override suspend fun install(entry: HidingPackEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = extractAsset(entry)
            try {
                when (entry.category) {
                    HidingPackCategory.Module, HidingPackCategory.PathMask -> {
                        check(execKsud("module install ${file.absolutePath}", true)) {
                            "ksud refused to install ${entry.id}"
                        }
                    }

                    HidingPackCategory.Detector -> installDetector(entry, file)

                    HidingPackCategory.Data -> {
                        // Data entries ship as plain resources; the frozen schema defines no install action.
                        error("entry ${entry.id} has no install action")
                    }
                }
            } finally {
                // One entry at a time: its temp copy is dropped as soon as the verdict is known.
                if (!file.delete()) Log.w(TAG, "cannot delete ${file.absolutePath}")
            }
        }
    }

    /**
     * Root first: `pm install -r -t --user 0 <abs>` makes the exit code the verdict and needs no
     * unknown-sources permission. Only when that fails do we hand the APK to the system installer
     * through the FileProvider and wait for a positive package-manager hit — never fire-and-forget.
     */
    private suspend fun installDetector(entry: HidingPackEntry, file: File) {
        val packageName = entry.packageName?.takeIf { it.isNotBlank() }
            ?: error("entry ${entry.id} has no packageName")
        val root = rootPmInstall(file)
        if (root.code == 0) {
            Log.i(TAG, "pm install ${entry.id} succeeded")
            return
        }
        Log.w(TAG, "pm install ${entry.id} failed (code ${root.code}): ${root.output}")
        requestDetectorInstall(file)
        if (awaitPackageInstalled(packageName)) return
        val detail = root.output.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        error(
            "pm install failed (code ${root.code}$detail); $packageName did not appear within " +
                "${DETECTOR_INSTALL_TIMEOUT_MS / 1000}s"
        )
    }

    private fun rootPmInstall(file: File): RootCommandResult = runCatching {
        withNewRootShell {
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            val result = newJob()
                .add("pm install -r -t --user 0 ${file.absolutePath}")
                .to(out, err)
                .exec()
            RootCommandResult(result.code, (err + out).filter { it.isNotBlank() }.joinToString("\n").trim())
        }
    }.getOrElse { RootCommandResult(-1, it.message.orEmpty()) }

    /**
     * Cache files are exposed by the FileProvider declared in AndroidManifest.xml
     * (`${applicationId}.fileprovider`), whose paths file only whitelists cacheDir.
     */
    private fun requestDetectorInstall(file: File) {
        val uri = FileProvider.getUriForFile(ksuApp, "${ksuApp.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ksuApp.startActivity(intent)
    }

    private suspend fun awaitPackageInstalled(packageName: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + DETECTOR_INSTALL_TIMEOUT_MS
        while (true) {
            if (isPackageInstalled(packageName) || isPackageInstalledViaRoot(packageName)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(DETECTOR_POLL_INTERVAL_MS)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        ksuApp.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * The manager declares no `<queries>`, so on a modern target SDK the in-process package
     * visibility hides third-party packages and [isPackageInstalled] alone can never confirm a
     * detector. `pm path` asks the platform package manager over the root shell, which is not
     * filtered, so a positive hit here is still a real package-manager answer.
     */
    private fun isPackageInstalledViaRoot(packageName: String): Boolean = runCatching {
        withNewRootShell { ShellUtils.fastCmdResult(this, "pm path $packageName") }
    }.getOrDefault(false)

    private fun installedPackagesViaRoot(): Set<String> = runCatching {
        withNewRootShell {
            val out = ArrayList<String>()
            newJob().add("pm list packages").to(out, null).exec()
            out.mapNotNull { line ->
                line.trim().removePrefix("package:").trim().takeIf { it.isNotBlank() }
            }.toSet()
        }
    }.getOrDefault(emptySet())

    private fun extractAsset(entry: HidingPackEntry): File {
        val dir = File(ksuApp.cacheDir, PACK_CACHE_DIR).apply { mkdirs() }
        val fileName = entry.asset.substringAfterLast('/').ifEmpty { "${entry.id}.bin" }
        val target = File(dir, "${entry.id}-$fileName")
        ksuApp.assets.open(entry.asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun parseManifest(root: JSONObject): HidingPack {
        val includes = root.optJSONObject("includes") ?: JSONObject()
        val rawEntries = root.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until rawEntries.length()).mapNotNull { index ->
            rawEntries.optJSONObject(index)?.toEntry()
        }
        return HidingPack(
            version = root.optInt("version", 1),
            generatedAt = root.optString("generatedAt"),
            sourcePack = root.optString("sourcePack"),
            includesModules = includes.optBoolean("modules", false),
            includesPathMask = includes.optBoolean("pathmask", false),
            includesDetectors = includes.optBoolean("detectors", false),
            includesData = includes.optBoolean("data", false),
            includesKeybox = includes.optBoolean("keybox", false),
            totalBytes = root.optLong("totalBytes", entries.sumOf { it.sizeBytes }),
            entries = entries,
        )
    }

    private fun JSONObject.toEntry(): HidingPackEntry? {
        val id = optString("id")
        val asset = optString("asset")
        if (id.isBlank() || asset.isBlank()) return null
        return HidingPackEntry(
            id = id,
            moduleId = stringOrNull("moduleId"),
            name = optString("name").ifBlank { id },
            version = optString("version"),
            versionCode = optInt("versionCode", 0),
            author = optString("author"),
            description = optString("description"),
            category = HidingPackCategory.fromValue(optString("category")),
            // org.json turns a JSON null into the literal string "null", so probe isNull first.
            branch = stringOrNull("branch"),
            packageName = stringOrNull("packageName"),
            asset = asset,
            sizeBytes = optLong("sizeBytes", 0L),
            sha256 = optString("sha256"),
            sourceFile = optString("sourceFile"),
            // Frozen parser rules: a missing/non-array `modes` means "both", a missing/non-number
            // `order` means 100. Dropping these keys here would silently disable the gating.
            modes = modes(),
            order = intOrNull("order") ?: HidingPackEntry.DEFAULT_ORDER,
        )
    }

    private fun JSONObject.modes(): Set<HidingPackMode> {
        val array = optJSONArray("modes") ?: return emptySet()
        return (0 until array.length())
            .mapNotNull { index -> HidingPackMode.fromValue(array.optString(index)) }
            .toSet()
    }

    private fun JSONObject.intOrNull(key: String): Int? = (opt(key) as? Number)?.toInt()

    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
