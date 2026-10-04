package me.weishu.kernelsu.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client for the assistant.
 *
 * Timeouts are generous because a reasoning model can take a minute to answer, and a long read
 * timeout is cheaper than surfacing a bogus failure. No cache: every request is a fresh answer.
 */
object AiHttpClient {

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 120L
    private const val CALL_TIMEOUT_SECONDS = 300L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cache(null)
            .build()
    }
}
