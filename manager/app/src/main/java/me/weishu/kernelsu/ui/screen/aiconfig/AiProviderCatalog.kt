package me.weishu.kernelsu.ui.screen.aiconfig

import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.remote.AiApiFamily

/**
 * Phase 1 of the AI assistant only stores configuration; nothing here talks to the network yet.
 *
 * The catalogue is intentionally a plain data list so the same entries can be rendered by the
 * Miuix and the Material bottom sheet without duplicating any content.
 */
enum class AiProviderGroup {
    /** Core providers, shown first and badged as recommended. */
    RECOMMENDED,

    /** Local / private inference servers the user runs on their own machine. */
    LOCAL,

    /** Everything else, both domestic and international. */
    MORE,
}

data class AiProvider(
    val id: String,
    val labelRes: Int,
    val group: AiProviderGroup,
    val recommended: Boolean = false,
    /** Suggested endpoint. Empty means "no default, the user has to fill it in". */
    val endpoint: String = "",
    /** Suggested model shown as a placeholder. Null keeps the generic hint string. */
    val modelHint: String? = null,
    /** Wire format used for requests and for deriving the model-list URL. */
    val family: AiApiFamily = AiApiFamily.OPENAI_COMPATIBLE,
) {
    val hasEndpointDefault: Boolean get() = endpoint.isNotEmpty()
}

object AiProviders {

    /** DeepSeek is the default because it is the endpoint named in the design spec. */
    const val DEFAULT_ID = "deepseek"

    val all: List<AiProvider> = listOf(
        AiProvider(
            id = "deepseek",
            labelRes = R.string.ai_provider_deepseek,
            group = AiProviderGroup.RECOMMENDED,
            recommended = true,
            endpoint = "https://api.deepseek.com/v1/chat/completions",
        ),
        AiProvider(
            id = "openai_compatible",
            labelRes = R.string.ai_provider_openai_compatible,
            group = AiProviderGroup.RECOMMENDED,
            recommended = true,
            modelHint = "gpt-4o-mini",
        ),
        AiProvider(
            id = "anthropic",
            labelRes = R.string.ai_provider_anthropic,
            group = AiProviderGroup.RECOMMENDED,
            recommended = true,
            endpoint = "https://api.anthropic.com/v1/messages",
            modelHint = "claude-3-5-sonnet-latest",
            family = AiApiFamily.ANTHROPIC,
        ),
        AiProvider(
            id = "openai",
            labelRes = R.string.ai_provider_openai,
            group = AiProviderGroup.RECOMMENDED,
            recommended = true,
            endpoint = "https://api.openai.com/v1/chat/completions",
            modelHint = "gpt-4o, gpt-4o-mini",
        ),
        AiProvider(
            id = "ollama",
            labelRes = R.string.ai_provider_ollama,
            group = AiProviderGroup.LOCAL,
            endpoint = "http://127.0.0.1:11434/v1/chat/completions",
            modelHint = "llama3.1, qwen2.5",
        ),
        AiProvider(
            id = "lmstudio",
            labelRes = R.string.ai_provider_lmstudio,
            group = AiProviderGroup.LOCAL,
            endpoint = "http://127.0.0.1:1234/v1/chat/completions",
            modelHint = "local-model",
        ),
        AiProvider(
            id = "zhipu",
            labelRes = R.string.ai_provider_zhipu,
            group = AiProviderGroup.MORE,
            endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            modelHint = "glm-4-plus",
        ),
        AiProvider(
            id = "qwen",
            labelRes = R.string.ai_provider_qwen,
            group = AiProviderGroup.MORE,
            endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            modelHint = "qwen-plus",
        ),
        AiProvider(
            id = "ernie",
            labelRes = R.string.ai_provider_ernie,
            group = AiProviderGroup.MORE,
            endpoint = "https://qianfan.baidubce.com/v2/chat/completions",
            modelHint = "ernie-4.5-turbo",
        ),
        AiProvider(
            id = "spark",
            labelRes = R.string.ai_provider_spark,
            group = AiProviderGroup.MORE,
            endpoint = "https://spark-api-open.xf-yun.com/v1/chat/completions",
            modelHint = "generalv3.5",
        ),
        AiProvider(
            id = "gemini",
            labelRes = R.string.ai_provider_gemini,
            group = AiProviderGroup.MORE,
            endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            modelHint = "gemini-2.0-flash",
        ),
    )

    /** Fixed display order of the groups. */
    val groupOrder: List<AiProviderGroup> = listOf(
        AiProviderGroup.RECOMMENDED,
        AiProviderGroup.LOCAL,
        AiProviderGroup.MORE,
    )

    fun byId(id: String): AiProvider = all.firstOrNull { it.id == id } ?: all.first()
}
