package com.satory.graphenosai.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.content.Context
import android.content.Intent

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

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // Start the assistant service and overlay
        val intent = Intent(context, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
            putExtra(AssistantService.EXTRA_TRIGGER, "voice_interaction")
        }
        context.startForegroundService(intent)
        
        // Close this session as we're using our own UI
        hide()
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
