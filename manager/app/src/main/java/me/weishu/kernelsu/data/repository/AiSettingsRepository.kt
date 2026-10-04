package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.agent.AiAccessScope

/**
 * Storage for the AI assistant configuration.
 *
 * Phase 0 stores parameters and can only read the provider model list; nothing on the device is
 * ever executed from here.
 */
interface AiSettingsRepository {

    /** Id of the selected entry in [me.weishu.kernelsu.ui.screen.aiconfig.AiProviders]. */
    var providerId: String

    /** Chat-completions endpoint the assistant will use once networking lands. */
    var endpoint: String

    /** API key. Stored in plain text, see [AiSettingsRepositoryImpl]. */
    var apiKey: String

    /** Model name, e.g. `deepseek-chat`. */
    var modelName: String

    /** Whether the assistant is allowed to run shell commands at all. Off by default. */
    var allowShell: Boolean

    /** Whether the assistant may read and modify module directories. Off by default. */
    var allowModuleDir: Boolean

    /**
     * Which part of the file system the assistant may touch. Fail-closed default
     * ([me.weishu.kernelsu.data.agent.AiAccessScope.DEFAULT]); [allowModuleDir] is only kept as
     * the legacy key this value is migrated from.
     */
    var accessScope: AiAccessScope

    /**
     * Ceiling on how many model turns one user message may chain. The values the UI offers live
     * in [me.weishu.kernelsu.ui.screen.aiconfig.AiRounds].
     */
    var maxRounds: Int

    /**
     * Characters one file read may hand back to the model. The UI offers
     * [me.weishu.kernelsu.ui.screen.aiconfig.AiReadLimits.chunkOptions].
     */
    var readChunkLimit: Int

    /**
     * Largest file (KB) that read_file returns complete before it falls back to a windowed read.
     * The UI offers [me.weishu.kernelsu.ui.screen.aiconfig.AiReadLimits.fullReadOptions].
     */
    var fullReadThresholdKb: Int
}
