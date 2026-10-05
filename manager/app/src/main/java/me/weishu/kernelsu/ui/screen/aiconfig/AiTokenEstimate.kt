package me.weishu.kernelsu.ui.screen.aiconfig

import java.util.Locale

/**
 * Worst case size of a single conversation, shown under the settings so the token magnitude of
 * the numbers the user picks stays visible while they pick them.
 *
 * The estimate is deliberately pessimistic: it counts the largest whole read once plus a fixed
 * per round overhead for every round. Both inputs are the user's own settings, so the line moves
 * as those settings move.
 */
object AiTokenEstimate {
    /** Cheap, stable rule of thumb for English prose and logs; CJK text packs more per token. */
    private const val CHARS_PER_TOKEN = 4L

    /** Conversation history, system prompt, tool results and the answer of one round. */
    private const val ROUND_OVERHEAD_TOKENS = 2000L

    fun worstCaseTokens(fullReadKb: Int, maxRounds: Int): Long =
        fullReadKb.toLong() * 1024L / CHARS_PER_TOKEN + maxRounds.toLong() * ROUND_OVERHEAD_TOKENS

    /** 万 tokens, the unit the estimate line is written in. */
    fun wan(tokens: Long): String = String.format(Locale.US, "%.0f", tokens / 10_000.0)

    /** Compact count for the English line, where 万 has no meaning: 620000 -> "620K". */
    fun kilo(tokens: Long): String =
        if (tokens >= 1000) ((tokens + 500) / 1000).toString() + "K" else tokens.toString()
}
