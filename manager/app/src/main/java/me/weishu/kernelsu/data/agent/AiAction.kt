package me.weishu.kernelsu.data.agent

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.modulemaker.ModuleDraft

/**
 * How much friction an action needs before it may run.
 *
 * SILENT runs unattended, LIGHT asks for a light confirmation, STRICT always shows the red
 * interception dialog and needs an explicit typed or long press confirmation.
 */
enum class AiSafetyTier { SILENT, LIGHT, STRICT }

/** One operation the model asked the client to perform. */
@Immutable
sealed interface AiAction {

    /** Why the model wants this; free text, shown in the log and the confirmation dialogs. */
    val reason: String?

    /** Stable name used in model JSON, the log and the audit trail. */
    val name: String

    @Immutable
    data class ReadFile(val path: String, override val reason: String? = null) : AiAction {
        override val name: String get() = READ_FILE
    }

    @Immutable
    data class RunCommand(val command: String, override val reason: String? = null) : AiAction {
        override val name: String get() = RUN_COMMAND
    }

    /** Directory listing: lets the model discover what really exists instead of guessing names. */
    @Immutable
    data class ListDir(val path: String, override val reason: String? = null) : AiAction {
        override val name: String get() = LIST_DIR
    }

    /** Cheap metadata probe, so the model can size a file up before it reads a single byte of it. */
    @Immutable
    data class GetFileMetadata(val path: String, override val reason: String? = null) : AiAction {
        override val name: String get() = GET_FILE_METADATA
    }

    /**
     * One window of a text file. [startLine] is 1 based, so the trailer the client appends can send
     * the model straight back here instead of it re-reading the whole file.
     */
    @Immutable
    data class ReadFileChunk(
        val path: String,
        val startLine: Int = 1,
        val maxLines: Int? = null,
        val maxChars: Int? = null,
        override val reason: String? = null,
    ) : AiAction {
        override val name: String get() = READ_FILE_CHUNK
    }

    /** Literal search inside one text file; answers with line numbers so the model can read around them. */
    @Immutable
    data class SearchInFile(
        val path: String,
        val pattern: String,
        override val reason: String? = null,
    ) : AiAction {
        override val name: String get() = SEARCH_IN_FILE
    }

    /**
     * One read only query command taken from the client whitelist. The user always confirms it before
     * it runs, and it may not carry pipes, redirects, globs or quotes.
     */
    @Immutable
    data class SafeExec(
        val command: String,
        val timeoutSeconds: Int = 15,
        override val reason: String? = null,
    ) : AiAction {
        override val name: String get() = SAFE_EXEC
    }

    /** Reads which apps currently hold root. It changes nothing, so it never needs a confirmation. */
    @Immutable
    data class RootAppList(override val reason: String? = null) : AiAction {
        override val name: String get() = ROOT_APP_LIST
    }

    @Immutable
    data class GrantSu(val packageName: String, override val reason: String? = null) : AiAction {
        override val name: String get() = GRANT_SU
    }

    @Immutable
    data class RevokeSu(val packageName: String, override val reason: String? = null) : AiAction {
        override val name: String get() = REVOKE_SU
    }

    @Immutable
    data class DisableModule(val id: String, override val reason: String? = null) : AiAction {
        override val name: String get() = DISABLE_MODULE
    }

    @Immutable
    data class EnableModule(val id: String, override val reason: String? = null) : AiAction {
        override val name: String get() = ENABLE_MODULE
    }

    @Immutable
    data class InstallModule(val path: String, override val reason: String? = null) : AiAction {
        override val name: String get() = INSTALL_MODULE
    }

    /** Never deletes: the executor moves the target into the AI trash directory instead. */
    @Immutable
    data class MoveToTrash(val path: String, override val reason: String? = null) : AiAction {
        override val name: String get() = MOVE_TO_TRASH
    }

    /**
     * Builds a whole KernelSU module from a draft the model wrote and (by default) installs it.
     *
     * This is the only action where the model authors the payload itself, including an optional
     * customize.sh that ksud runs as root at install time, so [AiPathGuard] always gates it at
     * STRICT regardless of scope or batch size.
     */
    @Immutable
    data class MakeModule(
        val draft: ModuleDraft,
        val install: Boolean = true,
        override val reason: String? = null,
    ) : AiAction {
        override val name: String get() = MAKE_MODULE
    }

    /**
     * Anything the client does not understand. Kept (and shown) instead of dropped so the user sees
     * the request, and gated at STRICT so an unknown verb can never run quietly.
     */
    @Immutable
    data class Unknown(val raw: String, override val reason: String? = null) : AiAction {
        override val name: String get() = NAME
    }

    companion object {
        const val NAME = "unknown"
        const val READ_FILE = "read_file"
        const val GET_FILE_METADATA = "get_file_metadata"
        const val READ_FILE_CHUNK = "read_file_chunk"
        const val SEARCH_IN_FILE = "search_in_file"
        const val MAX_CHUNK_LINES = 2000
        const val MAX_SEARCH_PATTERN_LENGTH = 200
        const val LIST_DIR = "list_dir"
        const val RUN_COMMAND = "run_command"
        const val SAFE_EXEC = "safe_exec_shell"
        const val DEFAULT_SAFE_EXEC_TIMEOUT = 15
        const val MIN_SAFE_EXEC_TIMEOUT = 5
        const val MAX_SAFE_EXEC_TIMEOUT = 60
        const val GRANT_SU = "grant_su"
        const val REVOKE_SU = "revoke_su"
        const val DISABLE_MODULE = "disable_module"
        const val ENABLE_MODULE = "enable_module"
        const val INSTALL_MODULE = "install_module"
        const val MOVE_TO_TRASH = "move_to_trash"
        const val MAKE_MODULE = "make_module"
        const val ROOT_APP_LIST = "get_root_app_list"
    }
}

/** The gate verdict for one action. */
@Immutable
sealed interface AiGuardDecision {

    @Immutable
    data class Allow(val tier: AiSafetyTier) : AiGuardDecision

    @Immutable
    data class Deny(val reason: String) : AiGuardDecision
}
