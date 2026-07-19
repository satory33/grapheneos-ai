package com.satory.graphenosai.ui

import android.content.Context
import android.content.SharedPreferences
import com.satory.graphenosai.llm.SystemPrompts

/**
 * Settings manager for app preferences.
 */
class SettingsManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "assistant_settings"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_CUSTOM_MODEL = "custom_model_id"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_VOICE_INPUT_METHOD = "voice_input_method"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_AUTO_SEND_VOICE = "auto_send_voice"
        private const val KEY_AUTO_START_VOICE = "auto_start_voice"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_SECONDARY_LANGUAGE = "secondary_voice_language"
        private const val KEY_MULTILINGUAL_ENABLED = "multilingual_enabled"
        private const val KEY_API_PROVIDER = "api_provider"
        private const val KEY_WHISPER_PROVIDER = "whisper_provider"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_TTS_LANGUAGE = "tts_language"
        
        const val VOICE_INPUT_SYSTEM = "system"
        const val VOICE_INPUT_VOSK = "vosk"
        const val VOICE_INPUT_WHISPER = "whisper"  // Cloud Whisper API
        
        // Whisper providers
        const val WHISPER_GROQ = "groq"
        const val WHISPER_OPENAI = "openai"
        
        // API Providers
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_LOCAL = "local"  // Local llama.cpp models
        
        // Search Engines
        const val SEARCH_BRAVE = "brave"
        const val SEARCH_EXA = "exa"
        const val SEARCH_LANGSEARCH = "langsearch"
        
        // Local model settings
        private const val KEY_LOCAL_MODEL_ID = "local_model_id"
        const val DEFAULT_LOCAL_MODEL = "qwen3-4b"
        
        val AVAILABLE_MODELS = listOf(
            ModelInfo("openai/gpt-5.5", "GPT-5.5", "Latest OpenAI flagship, vision", true),
            ModelInfo("openai/gpt-5.4-mini", "GPT-5.4 Mini", "Fast OpenAI, vision", true),
            ModelInfo("openai/gpt-chat-latest", "GPT Chat Latest", "OpenAI chat alias, vision", true),
            ModelInfo("anthropic/claude-opus-4.8", "Claude Opus 4.8", "Top Claude, vision", true),
            ModelInfo("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", "Balanced Claude, vision", true),
            ModelInfo("anthropic/claude-haiku-4.5", "Claude Haiku 4.5", "Fast Claude, vision", true),
            ModelInfo("google/gemini-3.5-flash", "Gemini 3.5 Flash", "Fast multimodal", true),
            ModelInfo("google/gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", "Advanced multimodal", true),
            ModelInfo("mistralai/mistral-large-2512", "Mistral Large 3", "European flagship, vision", true),
            ModelInfo("mistralai/mistral-medium-3-5", "Mistral Medium 3.5", "Efficient multimodal", true),
            ModelInfo("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro", "Reasoning and coding", false),
            ModelInfo("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", "Fast reasoning", false),
            ModelInfo("qwen/qwen3.7-max", "Qwen3.7 Max", "Agentic long context", false),
            ModelInfo("qwen/qwen3.7-plus", "Qwen3.7 Plus", "Cost-effective, vision", true),
            ModelInfo("meta-llama/llama-4-maverick", "Llama 4 Maverick", "Open model, vision", true),
            ModelInfo("meta-llama/llama-4-scout", "Llama 4 Scout", "Long-context open model, vision", true),
            // Free models
            ModelInfo("nex-agi/nex-n2-pro:free", "Nex N2 Pro (Free)", "Free multimodal", true),
            ModelInfo("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra (Free)", "Free reasoning", false),
            ModelInfo("qwen/qwen3-next-80b-a3b-instruct:free", "Qwen3 Next 80B (Free)", "Free open model", false),
            ModelInfo("openai/gpt-oss-120b:free", "GPT-OSS 120B (Free)", "Free open-weight", false),
            ModelInfo("openai/gpt-oss-20b:free", "GPT-OSS 20B (Free)", "Fast free open-weight", false),
            ModelInfo("qwen/qwen3-coder:free", "Qwen3 Coder (Free)", "Free coding model", false),
            ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (Free)", "Free open model", false),
            ModelInfo("meta-llama/llama-3.2-3b-instruct:free", "Llama 3.2 3B (Free)", "Free tier", false),
        )
        
        const val DEFAULT_MODEL = "openai/gpt-5.4-mini"
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_TTS_LANGUAGE = "en-US"
        
        val DEFAULT_SYSTEM_PROMPT = SystemPrompts.CLOUD_DEFAULT
    }
    
    data class ModelInfo(
        val id: String, 
        val name: String, 
        val description: String,
        val supportsVision: Boolean = false
    )
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()
    
    var customModelId: String
        get() = prefs.getString(KEY_CUSTOM_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_MODEL, value).apply()
    
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()
    
    var voiceInputMethod: String
        get() = prefs.getString(KEY_VOICE_INPUT_METHOD, VOICE_INPUT_VOSK) ?: VOICE_INPUT_VOSK
        set(value) = prefs.edit().putString(KEY_VOICE_INPUT_METHOD, value).apply()
    
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()
    
    var secondaryVoiceLanguage: String
        get() = prefs.getString(KEY_SECONDARY_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_SECONDARY_LANGUAGE, value).apply()
    
    var multilingualEnabled: Boolean
        get() = prefs.getBoolean(KEY_MULTILINGUAL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MULTILINGUAL_ENABLED, value).apply()
    
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()
    
    var autoSendVoice: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND_VOICE, value).apply()
    
    var autoStartVoice: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_VOICE, value).apply()
    
    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
        set(value) = prefs.edit().putString(KEY_API_PROVIDER, value).apply()
    
    var whisperProvider: String
        get() = prefs.getString(KEY_WHISPER_PROVIDER, WHISPER_GROQ) ?: WHISPER_GROQ
        set(value) = prefs.edit().putString(KEY_WHISPER_PROVIDER, value).apply()
    
    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, SEARCH_BRAVE) ?: SEARCH_BRAVE
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()

    var ttsLanguage: String
        get() = prefs.getString(KEY_TTS_LANGUAGE, DEFAULT_TTS_LANGUAGE) ?: DEFAULT_TTS_LANGUAGE
        set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()
    
    var localModelId: String
        get() = prefs.getString(KEY_LOCAL_MODEL_ID, DEFAULT_LOCAL_MODEL) ?: DEFAULT_LOCAL_MODEL
        set(value) = prefs.edit().putString(KEY_LOCAL_MODEL_ID, value).apply()
    
    /**
     * Check if current provider is local (offline)
     */
    fun isLocalProvider(): Boolean = apiProvider == PROVIDER_LOCAL
    
    /**
     * Get the effective model ID to use.
     * Returns custom model if set, otherwise selected model.
     */
    fun getEffectiveModel(): String {
        return if (customModelId.isNotBlank()) customModelId else selectedModel
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
