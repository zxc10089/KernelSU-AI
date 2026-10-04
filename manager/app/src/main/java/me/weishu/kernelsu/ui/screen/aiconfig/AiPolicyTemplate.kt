package me.weishu.kernelsu.ui.screen.aiconfig

import androidx.annotation.StringRes
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.agent.AiAccessScope

/**
 * One-tap presets for the permission switches below them.
 *
 * A template is only a shorthand for those switches: it writes the same two fields they write and
 * every switch stays editable afterwards. Widening the file scope still goes through the typed
 * elevation confirmation, because a template is a shortcut and not a way around that gate.
 */
enum class AiPolicyTemplate(
    @get:StringRes val labelRes: Int,
    @get:StringRes val summaryRes: Int,
    /** Value written to the "allow shell" switch. */
    val allowShell: Boolean,
    /** Value written to the file access scope. */
    val scope: AiAccessScope,
) {
    /** Reads only: shell commands are off and the file scope stays inside /data/adb. */
    STRICT_READ_ONLY(
        labelRes = R.string.ai_template_strict,
        summaryRes = R.string.ai_template_strict_summary,
        allowShell = false,
        scope = AiAccessScope.DATA_ADB,
    ),

    /** Reads and tool calls run; everything that changes state still asks first. */
    SMART_ASSISTANT(
        labelRes = R.string.ai_template_smart,
        summaryRes = R.string.ai_template_smart_summary,
        allowShell = true,
        scope = AiAccessScope.DATA_ADB,
    ),

    /** Everything on; the hard-coded dangerous command floor stays in place regardless. */
    GEEK(
        labelRes = R.string.ai_template_geek,
        summaryRes = R.string.ai_template_geek_summary,
        allowShell = true,
        scope = AiAccessScope.ROOT_FS,
    ),
    ;

    companion object {
        /**
         * The template whose two settings match the switches, or null when the user built a custom
         * combination - in that case no card is highlighted.
         */
        fun matching(allowShell: Boolean, scope: AiAccessScope): AiPolicyTemplate? =
            values().firstOrNull { it.allowShell == allowShell && it.scope == scope }
    }
}
