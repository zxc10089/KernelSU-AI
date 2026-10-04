package me.weishu.kernelsu.ui.screen.aiconfig

/**
 * Ceiling on how many model turns a single user message may chain.
 *
 * One round is one model reply plus, when the model asks for it, the local execution of the
 * actions in that reply. More rounds let the assistant finish multi-step work; every round costs
 * one more API call, so the user picks the ceiling on the configuration screen instead of the app
 * hard-coding it.
 */
object AiRounds {
    /** The ceiling used until the user picks another one. */
    const val DEFAULT = 50

    /** Lowest ceiling the picker, or a hand typed value, may take. */
    const val MIN = 1

    /** Highest ceiling the picker, or a hand typed value, may take. */
    const val MAX = 100

    /** Every ceiling offered by the picker, cheapest first; a custom value is allowed as well. */
    val options: List<Int> = listOf(1, 2, 3, 5, 8, 10, 30, 50, 80)

    /**
     * Clamps whatever the picker did not offer into [MIN]..[MAX], so neither a hand typed value
     * nor a hand-edited preference can turn the console into a loop.
     */
    fun sanitize(value: Int): Int = value.coerceIn(MIN, MAX)
}
