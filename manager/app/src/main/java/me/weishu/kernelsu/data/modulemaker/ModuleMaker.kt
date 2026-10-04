package me.weishu.kernelsu.data.modulemaker

import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.installModuleZip

/**
 * Builds a module zip from a draft and, when asked, installs it through ksud.
 *
 * Shared by the module maker UI and the AI console's make_module action so the two paths cannot
 * drift: both write cacheDir/modulemaker/ID.zip and both install through the same daemon command.
 */
object ModuleMaker {

    /** Directory inside the app cache that holds the built zips. */
    const val DIR_NAME = "modulemaker"

    /** Same cap the console uses for one action result, so a chatty installer cannot flood the model. */
    const val MAX_OUTPUT = 6000

    private const val LINE = "\n"

    data class Result(
        val ok: Boolean,
        val zipPath: String,
        val sizeText: String,
        val output: String,
        val message: String,
        val showReboot: Boolean = false,
    )

    suspend fun buildAndInstall(draft: ModuleDraft, install: Boolean): Result = withContext(Dispatchers.IO) {
        val errors = ModuleDraftValidator.validate(draft)
        if (errors.isNotEmpty()) {
            return@withContext Result(false, "", "", "", describeErrors(errors))
        }
        val zip = try {
            ModulePackage.build(draft, File(ksuApp.cacheDir, DIR_NAME))
        } catch (t: Throwable) {
            return@withContext Result(false, "", "", "", "打包失败：" + (t.message ?: t.toString()))
        }
        val sizeText = formatBytes(zip.length())
        if (!install) {
            return@withContext Result(
                ok = true,
                zipPath = zip.absolutePath,
                sizeText = sizeText,
                output = "",
                message = "已生成安装包（未安装）：" + zip.absolutePath + "（" + sizeText + "）",
            )
        }
        val lines = Collections.synchronizedList(ArrayList<String>())
        val flash = try {
            installModuleZip(
                zipPath = zip.absolutePath,
                onStdout = { line -> lines.add(line) },
                onStderr = { line -> lines.add(line) },
            )
        } catch (t: Throwable) {
            return@withContext Result(
                ok = false,
                zipPath = zip.absolutePath,
                sizeText = sizeText,
                output = "",
                message = "安装失败：" + (t.message ?: t.toString()),
            )
        }
        val output = cap(lines.joinToString(LINE).trim())
        Result(
            ok = flash.code == 0,
            zipPath = zip.absolutePath,
            sizeText = sizeText,
            output = output,
            showReboot = flash.showReboot,
            message = if (flash.code == 0) {
                "已生成安装包：" + zip.absolutePath + "（" + sizeText + "），安装成功。"
            } else {
                "已生成安装包：" + zip.absolutePath + "（" + sizeText + "），安装失败（ksud 退出码 " + flash.code + "）。"
            },
        )
    }

    /** One actionable Chinese sentence per validation error, with the offending value when we have it. */
    fun describeErrors(errors: List<ModuleDraftError>): String = errors.joinToString(LINE) { error ->
        when (error) {
            ModuleDraftError.InvalidId ->
                "模块 id 不合法：必须以字母开头，只能含字母、数字、点、减号与下划线，且至少 2 个字符。"
            ModuleDraftError.MissingName -> "缺少模块名称（name）。"
            ModuleDraftError.MissingVersion -> "缺少版本号（version）。"
            ModuleDraftError.InvalidVersionCode -> "versionCode 必须是正整数。"
            is ModuleDraftError.InvalidFilePath ->
                "文件路径不合法（不能以斜杠开头或结尾、不能含两个点）：" + error.path
            is ModuleDraftError.DuplicateFilePath -> "文件路径重复：" + error.path
            is ModuleDraftError.TooManyFiles -> "文件数量超过上限 " + error.max + " 个。"
            is ModuleDraftError.FileTooLarge ->
                "文件过大（上限 " + error.max + " 字节）：" + error.path
            is ModuleDraftError.TotalTooLarge ->
                "模块内容总量超过上限 " + error.max + " 字节。"
        }
    }

    private fun cap(text: String): String {
        if (text.length <= MAX_OUTPUT) return text
        return text.substring(0, MAX_OUTPUT) + LINE +
            "[系统已截断] 安装器输出共约 " + text.length + " 字符，上文仅为前 " + MAX_OUTPUT + " 字符。"
    }
}