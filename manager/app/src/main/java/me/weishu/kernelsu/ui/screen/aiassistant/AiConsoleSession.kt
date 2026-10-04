package me.weishu.kernelsu.ui.screen.aiassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In memory conversation shared by every console entry point.
 *
 * The console is reachable from the home card, the superuser page and the module page, so a plain
 * view model would drop the conversation on every navigation. Nothing is persisted: closing the
 * process clears the chat, while the audit log keeps the record of what was actually done.
 */
object AiConsoleSession {

    private val _messages = MutableStateFlow<List<AiConsoleMessage>>(emptyList())
    val messages: StateFlow<List<AiConsoleMessage>> = _messages

    private var seed = 0L

    fun nextId(): Long {
        seed += 1
        return seed
    }

    fun replace(list: List<AiConsoleMessage>) {
        _messages.value = list
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
