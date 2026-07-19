package com.satory.graphenosai.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume

/**
 * TTS Manager using Android's built-in TextToSpeech engine.
 * Falls back to on-device synthesis when available.
 * Supports user-selectable language locales.
 */
class TTSManager(context: Context) {

    companion object {
        private const val TAG = "TTSManager"
        private const val UTTERANCE_ID_PREFIX = "assistant_tts_"
        private const val TTS_TIMEOUT_MS = 30_000L

        /**
         * Commonly supported Android TTS locales with display names.
         * Available on most devices with Google TTS or device TTS engine.
         */
        val AVAILABLE_TTS_LOCALES = listOf(
            LocaleInfo(Locale.US, "English (US)"),
            LocaleInfo(Locale.UK, "English (UK)"),
            LocaleInfo(Locale.CANADA, "English (Canada)"),
            LocaleInfo(Locale("en", "AU"), "English (Australia)"),
            LocaleInfo(Locale("en", "IN"), "English (India)"),
            LocaleInfo(Locale.FRANCE, "Français (France)"),
            LocaleInfo(Locale("fr", "CA"), "Français (Canada)"),
            LocaleInfo(Locale.GERMANY, "Deutsch (Deutschland)"),
            LocaleInfo(Locale.ITALY, "Italiano (Italia)"),
            LocaleInfo(Locale.JAPAN, "日本語"),
            LocaleInfo(Locale.KOREA, "한국어"),
            LocaleInfo(Locale.CHINA, "中文 (中国)"),
            LocaleInfo(Locale.TAIWAN, "中文 (台湾)"),
            LocaleInfo(Locale("es", "ES"), "Español (España)"),
            LocaleInfo(Locale("es", "MX"), "Español (México)"),
            LocaleInfo(Locale("pt", "BR"), "Português (Brasil)"),
            LocaleInfo(Locale("pt", "PT"), "Português (Portugal)"),
            LocaleInfo(Locale("ru", "RU"), "Русский"),
            LocaleInfo(Locale("ar", "SA"), "العربية (السعودية)"),
            LocaleInfo(Locale("hi", "IN"), "हिन्दी (भारत)"),
            LocaleInfo(Locale("nl", "NL"), "Nederlands"),
            LocaleInfo(Locale("pl", "PL"), "Polski"),
            LocaleInfo(Locale("tr", "TR"), "Türkçe"),
            LocaleInfo(Locale("sv", "SE"), "Svenska"),
            LocaleInfo(Locale("da", "DK"), "Dansk"),
            LocaleInfo(Locale("fi", "FI"), "Suomi"),
            LocaleInfo(Locale("nb", "NO"), "Norsk Bokmål"),
            LocaleInfo(Locale("th", "TH"), "ไทย"),
            LocaleInfo(Locale("cs", "CZ"), "Čeština"),
            LocaleInfo(Locale("el", "GR"), "Ελληνικά"),
            LocaleInfo(Locale("ro", "RO"), "Română"),
            LocaleInfo(Locale("hu", "HU"), "Magyar"),
            LocaleInfo(Locale("vi", "VN"), "Tiếng Việt"),
            LocaleInfo(Locale("uk", "UA"), "Українська"),
            LocaleInfo(Locale("ms", "MY"), "Bahasa Melayu"),
            LocaleInfo(Locale("id", "ID"), "Bahasa Indonesia"),
            LocaleInfo(Locale("fil", "PH"), "Filipino"),
        )

        /** Default locale tag used when the device locale is not in our list */
        private const val DEFAULT_LOCALE_TAG = "en-US"

        /**
         * Resolve a locale from a BCP-47 language tag, falling back to US English.
         */
        private fun localeFromTag(tag: String): Locale {
            return try {
                val parts = tag.split("-", limit = 2)
                if (parts.size == 2) {
                    Locale(parts[0], parts[1].uppercase(Locale.ROOT))
                } else {
                    Locale(tag)
                }
            } catch (_: Exception) {
                Locale.US
            }
        }

        /**
         * Get a LocaleInfo from an AVAILABLE_TTS_LOCALES entry by its BCP-47 tag.
         * Falls back to US English.
         */
        fun getLocaleByTag(tag: String): LocaleInfo {
            return AVAILABLE_TTS_LOCALES.find { it.localeTag() == tag }
                ?: LocaleInfo(Locale.US, "English (US)")
        }

        /**
         * Resolve best matching entry for the device default locale.
         */
        fun resolveDeviceLocale(): String {
            val deviceLocale = Locale.getDefault()
            val tag = "${deviceLocale.language}-${deviceLocale.country}"
            return if (AVAILABLE_TTS_LOCALES.any { it.localeTag() == tag }) {
                tag
            } else {
                // Try matching only language
                val match = AVAILABLE_TTS_LOCALES.find {
                    it.locale.language == deviceLocale.language
                }
                match?.localeTag() ?: DEFAULT_LOCALE_TAG
            }
        }

        /**
         * Check if TTS is available on this device without initializing it.
         * Uses PackageManager to query for TTS service providers rather than
         * instantiating a TextToSpeech object (which initializes asynchronously
         * and would return empty results if queried immediately).
         */
        fun isTTSAvailable(context: Context): Boolean {
            return try {
                val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
                val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
                resolveInfos.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check TTS availability", e)
                false
            }
        }
    }

    /** Represents a selectable TTS locale with display metadata. */
    data class LocaleInfo(
        val locale: Locale,
        val displayName: String
    ) {
        /** BCP-47 language tag, e.g. "en-US", "fr-FR" */
        fun localeTag(): String = "${locale.language}-${locale.country}"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var utteranceCounter = 0
    private var currentLocale: Locale = Locale.US
    private var currentLocaleTag: String = DEFAULT_LOCALE_TAG

    init {
        // Default to device locale; user can override via setLanguage()
        val deviceTag = resolveDeviceLocale()
        currentLocale = getLocaleByTag(deviceTag).locale
        currentLocaleTag = deviceTag

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLanguage(currentLocale)

                if (isInitialized) {
                    // Configure for optimal speech
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    Log.i(TAG, "TTS initialized successfully with locale: $currentLocaleTag")
                } else {
                    // Fallback to US English
                    Log.w(TAG, "Locale $currentLocaleTag not supported, falling back to US English")
                    applyLanguage(Locale.US)
                    currentLocaleTag = DEFAULT_LOCALE_TAG

                    if (isInitialized) {
                        Log.i(TAG, "TTS initialized with US English fallback")
                    } else {
                        Log.w(TAG, "TTS language not supported even with US English fallback")
                    }
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Apply a locale to the TTS engine and update initialization state.
     */
    private fun applyLanguage(locale: Locale) {
        val result = tts?.setLanguage(locale)
        isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Change TTS language at runtime.
     * Returns true if the locale is supported by the current TTS engine.
     */
    fun setLanguage(tag: String): Boolean {
        val localeInfo = getLocaleByTag(tag)
        val locale = localeInfo.locale

        if (!isInitialized && tts == null) {
            // TTS not yet initialized; save for later
            currentLocale = locale
            currentLocaleTag = tag
            return false
        }

        val result = tts?.setLanguage(locale)
        val supported = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED

        if (supported) {
            currentLocale = locale
            currentLocaleTag = tag
            Log.i(TAG, "TTS language changed to: $tag")
        } else {
            Log.w(TAG, "TTS language $tag not supported by current engine")
        }

        return supported
    }

    /**
     * Get current TTS language tag (BCP-47).
     */
    fun getCurrentLanguageTag(): String = currentLocaleTag

    /**
     * Check if TTS is initialized and ready to use.
     */
    fun isAvailable(): Boolean = isInitialized

    /**
     * Speak text asynchronously.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCounter++}"

        // Split long text into chunks to avoid TTS limits
        val chunks = splitIntoChunks(text, 4000)

        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) queueMode else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, "$utteranceId-$index")
        }
    }

    /**
     * Speak text and suspend until complete.
     */
    suspend fun speakAndWait(text: String): Boolean =
        withTimeout(TTS_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
        if (!isInitialized) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCounter++}"
        val chunks = splitIntoChunks(text, 4000)
        val totalChunks = chunks.size
        var completedChunks = 0

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS started: $id")
            }

            override fun onDone(id: String?) {
                if (id?.startsWith(utteranceId) == true) {
                    completedChunks++
                    if (completedChunks >= totalChunks) {
                        cont.resume(true)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id?.startsWith(utteranceId) == true) {
                    Log.e(TAG, "TTS error: $id")
                    cont.resume(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.startsWith(UTTERANCE_ID_PREFIX) == true) {
                    Log.e(TAG, "TTS error $errorCode: $utteranceId")
                    cont.resume(false)
                }
            }
        })

        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, "$utteranceId-$index")
        }

        cont.invokeOnCancellation {
            stop()
        }
    }
    }

    /**
     * Stop ongoing speech.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Set speech rate (0.5 to 2.0, 1.0 is normal).
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Set pitch (0.5 to 2.0, 1.0 is normal).
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    /**
     * Get available TTS engines.
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        return tts?.engines ?: emptyList()
    }

    /**
     * Check if a specific language is available on the current engine.
     */
    fun isLanguageAvailable(locale: Locale): Boolean {
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun splitIntoChunks(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            // Try to split at sentence boundary
            var splitIndex = remaining.lastIndexOf(". ", maxLength)
            if (splitIndex < maxLength / 2) {
                splitIndex = remaining.lastIndexOf(" ", maxLength)
            }
            if (splitIndex < maxLength / 2) {
                splitIndex = maxLength
            }

            chunks.add(remaining.substring(0, splitIndex + 1).trim())
            remaining = remaining.substring(splitIndex + 1).trim()
        }

        return chunks
    }
}