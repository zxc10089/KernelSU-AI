package me.weishu.kernelsu.data.repository

import android.content.Context
import androidx.core.content.edit
import me.weishu.kernelsu.data.agent.AiAccessScope
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.aiconfig.AiProviders
import me.weishu.kernelsu.ui.screen.aiconfig.AiReadLimits
import me.weishu.kernelsu.ui.screen.aiconfig.AiRounds

/**
 * The AI configuration deliberately lives in its own preferences file instead of the shared
 * `settings` store, so the API key is not mixed with ordinary UI preferences.
 *
 * Risk note: SharedPreferences is a plain-text XML file under `/data/data/<pkg>/shared_prefs`.
 * On a rooted device any `su` process (and the KernelSU manager itself) can read it. That is
 * accepted here because the app already runs as root and the feature is useless without the key,
 * but it must not be presented as secure storage. Do not add an encryption dependency for it.
 */
private const val AI_PREFS = "ai_settings"

private const val KEY_PROVIDER = "provider"
private const val KEY_ENDPOINT = "endpoint"
private const val KEY_API_KEY = "api_key"
private const val KEY_MODEL_NAME = "model_name"
private const val KEY_ALLOW_SHELL = "allow_shell"
private const val KEY_ALLOW_MODULE_DIR = "allow_module_dir"
private const val KEY_ACCESS_SCOPE = "access_scope"
private const val KEY_MAX_ROUNDS = "max_rounds"
private const val KEY_READ_CHUNK_LIMIT = "read_chunk_limit"
private const val KEY_FULL_READ_KB = "full_read_threshold_kb"

class AiSettingsRepositoryImpl : AiSettingsRepository {

    private val prefs by lazy {
        ksuApp.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
    }

    override var providerId: String
        get() = prefs.getString(KEY_PROVIDER, AiProviders.DEFAULT_ID) ?: AiProviders.DEFAULT_ID
        set(value) = prefs.edit { putString(KEY_PROVIDER, value) }

    override var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ENDPOINT, value) }

    override var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    override var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MODEL_NAME, value) }

    override var allowShell: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SHELL, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_SHELL, value) }

    override var allowModuleDir: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_MODULE_DIR, false)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_MODULE_DIR, value) }

    override var accessScope: AiAccessScope
        get() {
            val stored = prefs.getString(KEY_ACCESS_SCOPE, null)
            if (stored != null) return AiAccessScope.fromKey(stored)
            // One-time migration from the phase-1 switch: the module directory lives under
            // /data/adb. The legacy key itself is left alone so an older build still reads it.
            return AiAccessScope.fromLegacyAllowModuleDir(
                prefs.getBoolean(KEY_ALLOW_MODULE_DIR, false)
            )
        }
        set(value) = prefs.edit { putString(KEY_ACCESS_SCOPE, value.name) }

    /**
     * Sanitised on both sides: the getter repairs a preference written by an older or hand-edited
     * build, the setter keeps an out-of-range value from ever reaching storage.
     */
    override var maxRounds: Int
        get() = AiRounds.sanitize(prefs.getInt(KEY_MAX_ROUNDS, AiRounds.DEFAULT))
        set(value) = prefs.edit { putInt(KEY_MAX_ROUNDS, AiRounds.sanitize(value)) }

    override var readChunkLimit: Int
        get() = AiReadLimits.sanitizeChunk(prefs.getInt(KEY_READ_CHUNK_LIMIT, AiReadLimits.DEFAULT_CHUNK))
        set(value) = prefs.edit { putInt(KEY_READ_CHUNK_LIMIT, AiReadLimits.sanitizeChunk(value)) }

    override var fullReadThresholdKb: Int
        get() = AiReadLimits.sanitizeFullRead(
            prefs.getInt(KEY_FULL_READ_KB, AiReadLimits.DEFAULT_FULL_READ_KB)
        )
        set(value) = prefs.edit {
            putInt(KEY_FULL_READ_KB, AiReadLimits.sanitizeFullRead(value))
        }
}
