package com.satory.graphenosai.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.content.Context
import android.content.Intent
import android.util.Log
import com.satory.graphenosai.ui.CompactAssistantActivity

/**
 * Session service for voice interaction.
 */
class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceInteractionSession(this)
    }
}

/**
 * Voice interaction session handling.
 * This is invoked when the assistant is triggered via system mechanisms
 * (long-press home, assistant gesture, etc.)
 */
class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AssistantVoiceSession"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        try {
            // A VoiceInteractionSession is allowed to surface UI for the active
            // assistant. Start it through the session rather than from the
            // background service; recent OxygenOS/Android builds can reject the
            // latter and previously leave the user with a brief flash.
            val serviceIntent = Intent(context, AssistantService::class.java).apply {
                action = AssistantService.ACTION_ACTIVATE
                putExtra(AssistantService.EXTRA_TRIGGER, "voice_interaction")
                putExtra(AssistantService.EXTRA_LAUNCH_OVERLAY, false)
            }
            context.startForegroundService(serviceIntent)

            val overlayIntent = Intent(context, CompactAssistantActivity::class.java)
            startVoiceActivity(overlayIntent)
        } catch (error: RuntimeException) {
            // Never let a system-assistant callback crash the process. The error
            // remains in logcat for device-specific policy diagnosis.
            Log.e(TAG, "Could not launch assistant from voice interaction", error)
        } finally {
            // The app's compact activity owns the visible UI once it has launched.
            hide()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: android.app.assist.AssistStructure?,
        content: android.app.assist.AssistContent?
    ) {
        // Handle assist data if available
        @Suppress("DEPRECATION")
        super.onHandleAssist(data, structure, content)
    }
}
