package me.weishu.kernelsu.data.remote

/**
 * Wire format of a provider. Only the two field layouts the KernelSU assistant has to speak are
 * modelled here; an "OpenAI compatible" endpoint always uses the OpenAI layout.
 */
enum class AiApiFamily {
    /** OpenAI POST /chat/completions and GET /models with an Authorization: Bearer header. */
    OPENAI_COMPATIBLE,

    /** Anthropic POST /messages with an x-api-key header; GET /models takes the same header. */
    ANTHROPIC,
}
