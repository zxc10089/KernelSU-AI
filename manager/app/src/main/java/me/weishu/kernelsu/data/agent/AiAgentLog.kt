package me.weishu.kernelsu.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory bus behind the AI_AGENT execution log panel.
 *
 * The panel shows what the assistant is doing while it runs: client side recon, model calls, gate
 * decisions and command output. This log is deliberately memory only; the durable record of
 * state changing operations lives in the audit repository, which only stores what actually changed
 * something.
 */
object AiAgentLog {

    enum class Level { INFO, ACTION, RESULT, DENIED, ERROR }

    data class Line(
        val ts: Long,
        val level: Level,
        val message: String,
    ) {
        val tag: String get() = "AI_AGENT"
    }

    const val MAX_LINES = 400

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun info(message: String) = append(Level.INFO, message)
    fun action(message: String) = append(Level.ACTION, message)
    fun result(message: String) = append(Level.RESULT, message)
    fun denied(message: String) = append(Level.DENIED, message)
    fun error(message: String) = append(Level.ERROR, message)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun append(level: Level, message: String) {
        val line = Line(System.currentTimeMillis(), level, message)
        _lines.update { current ->
            val next = current + line
            if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
    }
}
