package me.weishu.kernelsu.ui.component.aichat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAction
import me.weishu.kernelsu.data.agent.AiSafetyTier
import me.weishu.kernelsu.ui.screen.aiassistant.AiActionStatus

/*
 * Shared pieces of the AI console that do not depend on the active UI mode.
 *
 * The terminal colours are deliberately fixed: a command preview has to read as a terminal in
 * both Miuix and Material, including light themes, so it does not follow the theme palette.
 */
val TerminalBackground = Color(0xFF10161A)
val TerminalForeground = Color(0xFF7CE38B)
val TerminalAccent = Color(0xFF7FB3FF)

/** Human readable name of the operation; the target is rendered separately. */
@Composable
fun aiActionTitle(action: AiAction): String = when (action) {
    is AiAction.ReadFile -> stringResource(R.string.ai_console_action_read_file)
    is AiAction.GetFileMetadata -> stringResource(R.string.ai_console_action_get_file_metadata)
    is AiAction.ReadFileChunk -> stringResource(R.string.ai_console_action_read_file_chunk)
    is AiAction.SearchInFile -> stringResource(R.string.ai_console_action_search_in_file)
    is AiAction.ListDir -> stringResource(R.string.ai_console_action_list_dir)
    is AiAction.RunCommand -> stringResource(R.string.ai_console_action_run_command)
    is AiAction.SafeExec -> stringResource(R.string.ai_console_action_safe_exec)
    is AiAction.RootAppList -> stringResource(R.string.ai_console_action_root_app_list)
    is AiAction.GrantSu -> stringResource(R.string.ai_console_action_grant_su)
    is AiAction.RevokeSu -> stringResource(R.string.ai_console_action_revoke_su)
    is AiAction.DisableModule -> stringResource(R.string.ai_console_action_disable_module)
    is AiAction.EnableModule -> stringResource(R.string.ai_console_action_enable_module)
    is AiAction.InstallModule -> stringResource(R.string.ai_console_action_install_module)
    is AiAction.MoveToTrash -> stringResource(R.string.ai_console_action_move_to_trash)
    is AiAction.MakeModule -> stringResource(R.string.ai_console_action_make_module)
    is AiAction.Unknown -> stringResource(R.string.ai_console_action_unknown)
}

/** Path, command, package name or module id the operation works on. */
@Composable
fun aiActionTarget(action: AiAction): String = when (action) {
    is AiAction.ReadFile -> action.path
    is AiAction.GetFileMetadata -> action.path
    is AiAction.ReadFileChunk -> action.path + " @ " + action.startLine
    is AiAction.SearchInFile -> action.path + " :: " + action.pattern
    is AiAction.ListDir -> action.path
    is AiAction.RunCommand -> action.command
    is AiAction.SafeExec -> action.command
    is AiAction.RootAppList -> ""
    is AiAction.GrantSu -> action.packageName
    is AiAction.RevokeSu -> action.packageName
    is AiAction.DisableModule -> action.id
    is AiAction.EnableModule -> action.id
    is AiAction.InstallModule -> action.path
    is AiAction.MoveToTrash -> action.path
    is AiAction.MakeModule -> action.draft.id + " · " + action.draft.name
    is AiAction.Unknown -> action.raw.take(400)
}

/** One line summary of a captured result; the body stays hidden behind it until asked for. */
@Composable
fun aiOutputSummary(output: String): String {
    val lines = aiOutputLineCount(output)
    return stringResource(R.string.ai_console_output_summary, lines, output.length)
}

/**
 * A captured result opens itself when it is short enough to read at a glance. The check counts
 * lines as well as characters: a forty-line listing and a six-hundred-character paragraph are
 * very different amounts of screen, and characters alone opened walls of text.
 */
fun aiOutputAutoExpanded(output: String): Boolean =
    output.length <= 600 || aiOutputLineCount(output) <= 8

/** Trailing newlines are not another line; a file ending in a newline used to read one line longer. */
private fun aiOutputLineCount(output: String): Int =
    output.trimEnd('\n', '\r').count { it == '\n' } + 1

/**
 * Turns the raw shell failure of an action into one sentence a user can act on. Anything the client
 * does not recognise returns null and the raw text is shown instead: a wrong friendly message would
 * be worse than the real error.
 */
@Composable
fun aiFriendlyError(output: String?): String? {
    val text = output?.lowercase().orEmpty()
    if (text.isBlank()) return null
    return when {
        "no such file or directory" in text || "没有那个文件或目录" in text ||
            "没有这样的文件或目录" in text -> stringResource(R.string.ai_error_no_such_file)
        "permission denied" in text || "权限不够" in text || "权限不足" in text ->
            stringResource(R.string.ai_error_permission)
        "is a directory" in text || "是一个目录" in text -> stringResource(R.string.ai_error_is_dir)
        "read-only file system" in text || "只读文件系统" in text ->
            stringResource(R.string.ai_error_read_only)
        "no space left" in text || "空间不足" in text -> stringResource(R.string.ai_error_no_space)
        "command not found" in text || ": not found" in text ->
            stringResource(R.string.ai_error_command_not_found)
        else -> null
    }
}

@Composable
fun aiTierLabel(tier: AiSafetyTier?): String? = when (tier) {
    null -> null
    AiSafetyTier.SILENT -> stringResource(R.string.ai_console_tier_silent)
    AiSafetyTier.LIGHT -> stringResource(R.string.ai_console_tier_light)
    AiSafetyTier.STRICT -> stringResource(R.string.ai_console_tier_strict)
}

@Composable
fun aiStatusLabel(status: AiActionStatus): String = when (status) {
    AiActionStatus.PROPOSED -> stringResource(R.string.ai_console_status_proposed)
    AiActionStatus.AWAITING_CONFIRM -> stringResource(R.string.ai_console_status_awaiting)
    AiActionStatus.RUNNING -> stringResource(R.string.ai_console_status_running)
    AiActionStatus.DONE -> stringResource(R.string.ai_console_status_done)
    AiActionStatus.FAILED -> stringResource(R.string.ai_console_status_failed)
    AiActionStatus.DENIED -> stringResource(R.string.ai_console_status_denied)
    AiActionStatus.CANCELLED -> stringResource(R.string.ai_console_status_cancelled)
    AiActionStatus.SKIPPED -> stringResource(R.string.ai_console_status_skipped)
}
/**
 * Lightweight markdown reader for model output: the assistant answers with markdown in practice,
 * and rendering the markers literally reads as broken text. Bold runs, bullet markers, heading
 * hashes, inline code and fenced blocks are handled; the reply stays a plain Text, so the cost is
 * not a WebView per message. Inline code used to have its backticks deleted, which turned paths
 * and flags into prose; a fence keeps its monospace shape and loses only the fence lines.
 */
@Composable
fun aiRichText(
    text: String,
    codeBackground: Color = Color.Unspecified,
): AnnotatedString = remember(text, codeBackground) {
    buildAnnotatedString {
        val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        val tick = 96.toChar()
        val fence = tick.toString() + tick + tick
        var inFence = false
        val lines = ArrayList<Pair<String, Boolean>>()
        text.split("\n").forEach { raw ->
            if (raw.trimStart().startsWith(fence)) {
                inFence = !inFence
            } else {
                lines.add(raw to inFence)
            }
        }
        lines.forEachIndexed { index, entry ->
            if (index > 0) append("\n")
            if (entry.second) {
                withStyle(code) { append(entry.first) }
            } else {
                appendMarkupLine(entry.first, bold, code)
            }
        }
    }
}

/**
 * One prose line: bullet and heading markers are normalised first, then the remaining markup is
 * emitted with bold and inline-code spans. A marker that never closes is shown verbatim, so one
 * stray character can never swallow the rest of a reply.
 */
private fun AnnotatedString.Builder.appendMarkupLine(line: String, bold: SpanStyle, code: SpanStyle) {
    val tick = 96.toChar()
    val marker = tick.toString() + tick
    val trimmed = line.trimStart()
    val indent = line.substring(0, line.length - trimmed.length)
    val bullet = "- "
    val star = "* "
    val hash = 35.toChar()
    val normalised = when {
        trimmed.startsWith(bullet) -> indent + "\u2022 " + trimmed.removePrefix(bullet)
        trimmed.startsWith(star) -> indent + "\u2022 " + trimmed.removePrefix(star)
        trimmed.startsWith(hash) -> trimmed.dropWhile { it == hash }.trimStart()
        else -> line
    }
    var cursor = 0
    while (cursor < normalised.length) {
        val codeAt = normalised.indexOf(tick, cursor)
        val boldAt = normalised.indexOf(marker, cursor)
        val next = when {
            codeAt < 0 -> boldAt
            boldAt < 0 -> codeAt
            else -> if (codeAt < boldAt) codeAt else boldAt
        }
        if (next < 0) {
            append(normalised.substring(cursor))
            return
        }
        append(normalised.substring(cursor, next))
        if (next == codeAt) {
            val close = normalised.indexOf(tick, next + 1)
            if (close < 0) {
                append(normalised.substring(next))
                return
            }
            if (close == next + 1) {
                append(normalised.substring(next, close + 1))
            } else {
                withStyle(code) { append(normalised.substring(next + 1, close)) }
            }
            cursor = close + 1
        } else {
            val close = normalised.indexOf(marker, next + 2)
            if (close < 0) {
                append(normalised.substring(next))
                return
            }
            if (close == next + 2) {
                append(normalised.substring(next, close + 2))
            } else {
                withStyle(bold) { append(normalised.substring(next + 2, close)) }
            }
            cursor = close + 2
        }
    }
}

/**
 * Contrast-aware link colour.
 *
 * The dynamic palette can hand out a primary that reads as 3.39:1 on the surface behind it, below
 * the 4.5:1 floor for body text. Walking that colour towards the opposite end of the surface keeps
 * the hue and the affordance while making the link readable in every palette. The caller passes the
 * surface *closest* to the link, which is the worst case in both light and dark themes.
 */
fun aiLinkColor(base: Color, surface: Color): Color {
    if (contrastRatio(base, surface) >= MIN_LINK_CONTRAST) return base
    val target = if (relativeLuminance(surface) > 0.5f) Color.Black else Color.White
    for (step in 1..CONTRAST_STEPS) {
        val candidate = lerp(base, target, step.toFloat() / CONTRAST_STEPS)
        if (contrastRatio(candidate, surface) >= MIN_LINK_CONTRAST) return candidate
    }
    return target
}

private const val MIN_LINK_CONTRAST = 4.5f
private const val CONTRAST_STEPS = 20

private fun relativeLuminance(color: Color): Float =
    0.2126f * linearChannel(color.red) +
        0.7152f * linearChannel(color.green) +
        0.0722f * linearChannel(color.blue)

private fun linearChannel(value: Float): Float =
    if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

private fun contrastRatio(a: Color, b: Color): Float {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    val high = max(first, second)
    val low = min(first, second)
    return (high + 0.05f) / (low + 0.05f)
}
