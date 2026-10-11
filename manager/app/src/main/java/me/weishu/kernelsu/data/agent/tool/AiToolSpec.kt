package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiSafetyTier

/**
 * The eight families of the built-in tool set, mirroring design/ai-tool-api.md.
 *
 * The category is presentation only: it groups the tools in the model prompt and in the console, it
 * never changes how a call is gated.
 */
enum class AiToolCategory { SYSTEM, APP, NETWORK, MODULE, AUTOMATION, SECURITY, FILE, INTERACT }

/**
 * How dangerous one structured tool call is.
 *
 * The [tier] drives the confirmation gate; the level itself drives the risk tag in the approval
 * dialog. FATAL is deliberately not a separate gate tier: it is STRICT plus the extra "type
 * 确认执行" barrier that only the batch approval dialog enforces.
 */
enum class AiRiskLevel(val tier: AiSafetyTier) {
    LOW(AiSafetyTier.SILENT),
    MEDIUM(AiSafetyTier.LIGHT),
    HIGH(AiSafetyTier.STRICT),
    FATAL(AiSafetyTier.STRICT),
    ;

    /** True when the user has to see and accept this call before it runs. */
    val needsConfirm: Boolean get() = tier != AiSafetyTier.SILENT

    val isFatal: Boolean get() = this == FATAL
}

enum class AiParamType { STRING, INT, BOOL, ENUM, STRING_LIST, PATH, PATH_LIST }

/**
 * One declared parameter. Everything a handler may read has to be declared here first, and every
 * rule the validator enforces lives on the declaration, so the prompt, the guard and the handler can
 * never disagree about what a call means.
 */
data class AiToolParam(
    val name: String,
    val type: AiParamType,
    val required: Boolean = false,
    val values: List<String> = emptyList(),
    val pattern: String? = null,
    /** Non-zero means "at most this many characters". */
    val maxLength: Int = 0,
    val min: Int? = null,
    val max: Int? = null,
    /** Non-zero means "at most this many elements" (STRING_LIST). */
    val maxItems: Int = 0,
    /** For PATH: the narrowest scope this path has to live in. */
    val pathScope: AiAccessScope = AiAccessScope.ROOT_FS,
)

/**
 * One structured tool. The model never writes a command: it names a tool and fills in declared
 * parameters, and the handler builds the actual command from them.
 */
data class AiToolSpec(
    val name: String,
    val category: AiToolCategory,
    val risk: AiRiskLevel,
    val summary: String,
    val params: List<AiToolParam> = emptyList(),
    /** True when the handler runs a shell command (through the root shell) to do its job. */
    val needsShell: Boolean = true,
    val undoable: Boolean = false,
    /**
     * Overrides [risk] for calls whose danger depends on the arguments: `delete_file` is HIGH by
     * default but FATAL with `force`, and a write into `/data/adb` is MEDIUM only while a write
     * anywhere else stays STRICT.
     */
    val riskFor: ((Map<String, Any?>) -> AiRiskLevel)? = null,
) {
    fun riskOf(args: Map<String, Any?>): AiRiskLevel = riskFor?.invoke(args) ?: risk

    fun param(name: String): AiToolParam? = params.firstOrNull { it.name == name }
}

/**
 * Shared validation patterns. The parser, the registry and the handlers all read them from here, so
 * a package name that the parser accepts can never be rejected later for a different reason.
 */
object AiToolPatterns {
    const val PACKAGE = "^[A-Za-z0-9._]{1,255}\$"
    const val MODULE_ID = "^[A-Za-z0-9._-]{1,128}\$"
    const val HOST = "^[A-Za-z0-9._:-]{1,255}\$"
    const val PROP = "^[A-Za-z0-9._-]{1,128}\$"
    const val TASK_ID = "^[A-Za-z0-9._-]{1,64}\$"
    const val PID_OR_NAME = "^[A-Za-z0-9._/:~-]{1,255}\$"
    const val FILE_NAME = "^[A-Za-z0-9._-]{1,64}\$"
    const val URL = "^https?://[A-Za-z0-9._:/-]{1,512}\$"

    val PACKAGE_RE = Regex(PACKAGE)
    val MODULE_ID_RE = Regex(MODULE_ID)
    val HOST_RE = Regex(HOST)
    val PROP_RE = Regex(PROP)
    val TASK_ID_RE = Regex(TASK_ID)
    val PID_OR_NAME_RE = Regex(PID_OR_NAME)
    val FILE_NAME_RE = Regex(FILE_NAME)
    val URL_RE = Regex(URL)

    /** A path is rejected when it is relative, carries a dot segment or hides control characters. */
    fun pathRejected(value: String): Boolean {
        if (value.isEmpty() || !value.startsWith("/")) return true
        if (value.any { it.code < 32 || it.code == 127 }) return true
        for (segment in value.split('/')) {
            if (segment == "..") return true
        }
        return false
    }
}
