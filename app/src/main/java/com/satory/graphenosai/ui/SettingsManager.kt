package com.satory.graphenosai.ui

import android.content.Context
import android.content.SharedPreferences

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
        
        const val VOICE_INPUT_SYSTEM = "system"
        const val VOICE_INPUT_VOSK = "vosk"
        const val VOICE_INPUT_WHISPER = "whisper"  // Cloud Whisper API
        
        // Whisper providers
        const val WHISPER_GROQ = "groq"
        const val WHISPER_OPENAI = "openai"
        
        // API Providers
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_COPILOT = "copilot"
        
        // Search Engines (only Brave now)
        const val SEARCH_BRAVE = "brave"
        
        val AVAILABLE_MODELS = listOf(
            ModelInfo("openai/gpt-4o-mini", "GPT-4o Mini", "Fast, vision", true),
            ModelInfo("openai/gpt-4o", "GPT-4o", "Most capable, vision", true),
            ModelInfo("anthropic/claude-3-haiku", "Claude 3 Haiku", "Fast, vision", true),
            ModelInfo("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "Best for coding, vision", true),
            ModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4", "Latest Claude, vision", true),
            ModelInfo("google/gemini-flash-1.5", "Gemini Flash 1.5", "Fast, multimodal", true),
            ModelInfo("google/gemini-pro-1.5", "Gemini Pro 1.5", "Advanced", true),
            ModelInfo("google/gemini-2.0-flash-001", "Gemini 2.0 Flash", "Latest Google", true),
            ModelInfo("meta-llama/llama-3.1-70b-instruct", "Llama 3.1 70B", "Open source", false),
            ModelInfo("meta-llama/llama-3.1-8b-instruct", "Llama 3.1 8B", "Fast, open source", false),
            ModelInfo("mistralai/mistral-large", "Mistral Large", "European AI", false),
            ModelInfo("deepseek/deepseek-chat", "DeepSeek Chat", "Chinese AI", false),
            ModelInfo("deepseek/deepseek-r1", "DeepSeek R1", "Reasoning model", false),
            ModelInfo("qwen/qwen-2.5-72b-instruct", "Qwen 2.5 72B", "Alibaba", false),
            // Free models
            ModelInfo("nex-agi/deepseek-v3.1-nex-n1:free", "DeepSeek V3 (Free)", "Free tier", false),
            ModelInfo("mistralai/devstral-2512:free", "Devstral (Free)", "Free tier", false),
            ModelInfo("meta-llama/llama-3.2-3b-instruct:free", "Llama 3.2 3B (Free)", "Free tier", false),
        )
        
        // GitHub Copilot models (updated January 2026)
        // Note: In GitHub Copilot, vision is NOT supported for: gpt-4.1, gpt-4o, all gemini models
        val COPILOT_MODELS = listOf(
            // OpenAI models - NO vision in Copilot for gpt-4.1 and gpt-4o
            ModelInfo("gpt-4.1", "GPT-4.1", "OpenAI baseline", supportsVision = false),
            ModelInfo("gpt-4o", "GPT-4o", "OpenAI multimodal", supportsVision = false), // No vision in Copilot!
            ModelInfo("gpt-5", "GPT-5", "OpenAI flagship", supportsVision = true),
            ModelInfo("gpt-5-mini", "GPT-5 Mini", "Fast OpenAI", supportsVision = true),
            ModelInfo("gpt-5.1", "GPT-5.1", "Latest OpenAI", supportsVision = true),
            ModelInfo("gpt-5.2", "GPT-5.2", "Newest OpenAI", supportsVision = true),
            ModelInfo("gpt-5.1-codex", "GPT-5.1 Codex", "Code-optimized", supportsVision = false),
            ModelInfo("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "Best for code", supportsVision = false),
            // Anthropic Claude models - full vision support
            ModelInfo("claude-haiku-4.5", "Claude Haiku 4.5", "Fast Claude", supportsVision = true),
            ModelInfo("claude-sonnet-4", "Claude Sonnet 4", "Balanced Claude", supportsVision = true),
            ModelInfo("claude-sonnet-4.5", "Claude Sonnet 4.5", "Latest Sonnet", supportsVision = true),
            ModelInfo("claude-opus-4.5", "Claude Opus 4.5", "Most capable", supportsVision = true),
            // Google Gemini models - NO vision in Copilot!
            ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", "Google advanced", supportsVision = false),
            ModelInfo("gemini-3-flash", "Gemini 3 Flash", "Fast Google", supportsVision = false),
            ModelInfo("gemini-3-pro", "Gemini 3 Pro", "Google flagship", supportsVision = false),
            // Other
            ModelInfo("grok-code-fast-1", "Grok Code Fast", "xAI fast coding", supportsVision = false),
        )
        
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_COPILOT_MODEL = "gpt-5-mini"
        const val DEFAULT_LANGUAGE = "en"
        
        val DEFAULT_SYSTEM_PROMPT = """You are a helpful AI assistant on GrapheneOS.
- Keep responses concise for mobile
- Use markdown formatting: **bold**, *italic*, `code`, [links](url)
- When comparing items, use markdown tables for clarity:
  | Feature | Option A | Option B |
  |---------|----------|----------|
  | Price   | $100     | $150     |
- Use bullet points and numbered lists for organized info
- If unsure, say so
- Include URLs when citing sources
- You can analyze screenshots and PDF documents when shared
- To open a link for the user, write: [OPEN_URL:https://example.com]
- Respond in the same language as the user"""
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
