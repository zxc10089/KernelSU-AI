package me.weishu.kernelsu.data.agent.tool

import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.data.agent.AiAuditEntry
import me.weishu.kernelsu.data.agent.AiExecutionOutcome
import me.weishu.kernelsu.data.agent.AiOutcomeStatus
import me.weishu.kernelsu.data.agent.aiResultJson

/** One root shell invocation: exit code plus captured stdout/stderr. */
data class AiShellIo(val code: Int, val out: String, val err: String)

/**
 * Everything a tool handler is allowed to touch.
 *
 * Handlers never reach for the shell, the package manager or the audit log directly: they get this
 * context, which the executor fills in. That keeps "what a tool may do" in one place, and it is why
 * a handler cannot quietly ignore the granted scope or the shell switch.
 */
class AiToolContext(
    val scope: AiAccessScope,
    val allowShell: Boolean,
    val batchSize: Int,
    private val shellRunner: suspend (String) -> AiShellIo,
    private val quoteFn: (String) -> String?,
    private val uidFn: (String) -> Int?,
    private val auditSink: suspend (AiAuditEntry) -> Unit,
) {
    suspend fun shell(command: String): AiShellIo = shellRunner(command)

    /** Single quoted shell argument, or null when the value cannot be quoted safely. */
    fun quote(value: String): String? = quoteFn(value)

    fun packageUid(packageName: String): Int? = uidFn(packageName)

    /**
     * The uid of an installed package, falling back to the root shell.
     *
     * [packageUid] goes through PackageManager, which on Android 11+ only answers for the packages
     * this manager is allowed to see; `cmd package list packages` runs as root and sees every
     * installed package, so a tool that only needs a uid (the root policy) keeps working for an app
     * the manager itself cannot enumerate. The first uid of a multi-user line is the one the root
     * shell reports, i.e. the current user.
     */
    suspend fun packageUidOrShell(packageName: String): Int? {
        uidFn(packageName)?.let { return it }
        val quoted = quoteFn(packageName) ?: return null
        val line = shellRunner("cmd package list packages -U $quoted").out
            .lineSequence()
            .firstOrNull { it.startsWith("package:") }
            ?: return null
        return line.substringAfter("uid:", "").substringBefore(',').trim().toIntOrNull()
    }

    suspend fun audit(entry: AiAuditEntry) = auditSink(entry)

    fun now(): Long = System.currentTimeMillis()
}

/**
 * The JSON envelope every structured tool answers with; see design/ai-tool-api.md section 2.3.
 *
 * Every field the model needs to act correctly is here: [ok] and [status] say what happened, [error]
 * is the stable code, and [message] is the sentence in the user's language that has to reach the user
 * when the tool could not do the job.
 */
fun toolOutcome(
    tool: String,
    ok: Boolean,
    data: Any? = null,
    error: String? = null,
    message: String? = null,
    undoable: Boolean = false,
    status: AiOutcomeStatus = AiOutcomeStatus.of(ok),
): AiExecutionOutcome = AiExecutionOutcome(
    ok = ok,
    output = aiResultJson(tool, ok, status, error, message, data),
    undoable = undoable,
    status = status,
    error = error,
    message = message,
)

fun toolFailed(tool: String, error: String, message: String? = null): AiExecutionOutcome =
    toolOutcome(tool, false, error = error, message = message)

/**
 * A refusal that never reached the handler: the granted scope, the shell switch, the risk tier or the
 * validator said no. Kept apart from [toolFailed] because the model must report "you are not allowed
 * to do this, here is the switch" — not "it failed", which is what invites a made up explanation.
 */
fun toolBlocked(tool: String, error: String, message: String? = null): AiExecutionOutcome =
    toolOutcome(tool, false, error = error, message = message, status = AiOutcomeStatus.BLOCKED)

/** Declared parameters arrive already validated, so these readers only do defaults and casts. */
fun Map<String, Any?>.str(key: String, default: String = ""): String = when (val v = this[key]) {
    null -> default
    is String -> v
    else -> v.toString()
}

fun Map<String, Any?>.optStr(key: String): String? = this[key]?.let { if (it is String) it else it.toString() }

fun Map<String, Any?>.int(key: String, default: Int): Int = when (val v = this[key]) {
    null -> default
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: default
    else -> default
}

fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
    null -> default
    is Boolean -> v
    is String -> when (v.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }
    else -> default
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.strList(key: String): List<String> = when (val v = this[key]) {
    null -> emptyList()
    is List<*> -> v.mapNotNull { it?.toString() }
    else -> emptyList()
}
