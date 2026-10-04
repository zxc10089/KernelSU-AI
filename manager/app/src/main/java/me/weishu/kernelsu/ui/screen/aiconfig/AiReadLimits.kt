package me.weishu.kernelsu.ui.screen.aiconfig

/**
 * User configurable bounds for reading text out of the device.
 *
 * [chunkOptions] is how many characters one read may hand back to the model, and [fullReadOptions]
 * is the largest file (in KB) that read_file still returns in one piece before it switches to a
 * single window plus a start_line the model continues from. Both settings also accept a hand typed
 * value, so the sanitizers clamp into a range instead of demanding one of the listed options.
 */
object AiReadLimits {

    const val DEFAULT_CHUNK = 128000

    /** Smallest and largest amount of text one read may hand back. */
    const val MIN_CHUNK = 500
    const val MAX_CHUNK = 256000

    /** Every chunk size offered by the picker; a custom value is allowed as well. */
    val chunkOptions: List<Int> = listOf(2000, 4000, 8000, 16000, 32000, 64000, 128000, 256000)

    const val DEFAULT_FULL_READ_KB = 2048

    /** Smallest and largest file size, in KB, that may still be read in one piece. */
    const val MIN_FULL_READ_KB = 8
    const val MAX_FULL_READ_KB = 4096

    /** Every whole-file threshold offered by the picker; a custom value is allowed as well. */
    val fullReadOptions: List<Int> = listOf(32, 64, 100, 256, 512, 1024, 2048)

    fun sanitizeChunk(value: Int): Int = value.coerceIn(MIN_CHUNK, MAX_CHUNK)

    fun sanitizeFullRead(value: Int): Int = value.coerceIn(MIN_FULL_READ_KB, MAX_FULL_READ_KB)
}
