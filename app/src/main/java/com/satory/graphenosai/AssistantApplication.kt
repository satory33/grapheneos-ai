package com.satory.graphenosai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.satory.graphenosai.security.SecureKeyManager

/**
 * Application class for the AI Assistant.
 * Initializes secure key storage and notification channels.
 */
class AssistantApplication : Application() {

    lateinit var secureKeyManager: SecureKeyManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize secure key manager with Android Keystore
        secureKeyManager = SecureKeyManager(this)
        
        // Create notification channels
        createNotificationChannels()
        
        // Load native libraries
        loadNativeLibraries()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Foreground service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for assistant background processing"
                setShowBadge(false)
            }

            // Interaction channel
            val interactionChannel = NotificationChannel(
                CHANNEL_INTERACTION,
                "Assistant Interactions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for assistant responses"
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(
                listOf(serviceChannel, interactionChannel)
            )
        }
    }

    private fun loadNativeLibraries() {
        // Using Vosk for on-device speech recognition
        nativeLibsLoaded = false
    }

    companion object {
        const val CHANNEL_SERVICE = "assistant_service"
        const val CHANNEL_INTERACTION = "assistant_interaction"
        
        @Volatile
        var nativeLibsLoaded = false
            private set
            
        // Flag indicating Vosk is available for speech recognition
        @Volatile
        var voskAvailable = true

        @Volatile
        private var instance: AssistantApplication? = null

        fun getInstance(): AssistantApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
