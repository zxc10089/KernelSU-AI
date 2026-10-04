package me.weishu.kernelsu.data.modulemaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off slot between the AI console and the module maker page.
 *
 * When a model turn proposes a module the console drops the draft here immediately, so the page can
 * open pre-filled with the AI draft even while the user is still deciding about the confirmation
 * dialog.
 */
object ModuleDraftStore {

    private val _draft = MutableStateFlow<ModuleDraft?>(null)

    val draft: StateFlow<ModuleDraft?> = _draft.asStateFlow()

    fun publish(draft: ModuleDraft) {
        _draft.value = draft
    }

    /** Consumes the pending draft; the next reader gets null until something publishes again. */
    fun take(): ModuleDraft? {
        val current = _draft.value
        _draft.value = null
        return current
    }
}