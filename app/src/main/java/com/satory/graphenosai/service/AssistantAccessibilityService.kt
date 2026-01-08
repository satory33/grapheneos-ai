package com.satory.graphenosai.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service for detecting power button long-press.
 * 
 * On GrapheneOS/AOSP, direct power button interception is restricted.
 * This service uses accessibility key event filtering as a secure alternative.
 * 
 * Activation methods supported:
 * 1. Triple power button press (fast sequence)
 * 2. Volume up + Volume down held together
 * 
 * User must enable this service in Settings > Accessibility.
 */
class AssistantAccessibilityService : AccessibilityService() {

    private var lastPowerPressTime = 0L
    private var powerPressCount = 0
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    companion object {
        private const val TAG = "AssistantA11yService"
        private const val TRIPLE_PRESS_TIMEOUT_MS = 500L
        private const val POWER_PRESS_THRESHOLD = 3
        
        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            // Request key event filtering capability
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        serviceInfo = info
        
        // Register accessibility button callback (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    activateAssistant("accessibility_button")
                }
                
                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.d(TAG, "Accessibility button availability: $available")
                }
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
        }
        
        Log.i(TAG, "Accessibility service connected - assistant activation ready")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        
        // Unregister accessibility button callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && accessibilityButtonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback!!)
        }
        
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not processing accessibility events, only key events
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /**
     * Intercepts key events to detect activation sequences.
     * Returns true if event is consumed, false to pass through.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_POWER -> handlePowerKey(event)
            KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeUp(event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDown(event)
            else -> false
        }
    }

    /**
     * Detects triple power button press sequence.
     * Note: On some devices, power key events may not be delivered to accessibility services.
     * In that case, use volume button combination or accessibility shortcut.
     */
    private fun handlePowerKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val currentTime = SystemClock.elapsedRealtime()
        
        if (currentTime - lastPowerPressTime > TRIPLE_PRESS_TIMEOUT_MS) {
            powerPressCount = 0
        }
        
        powerPressCount++
        lastPowerPressTime = currentTime

        if (powerPressCount >= POWER_PRESS_THRESHOLD) {
            powerPressCount = 0
            activateAssistant("power_triple_press")
            return true // Consume the event
        }

        return false // Let system handle normally
    }

    private fun handleVolumeUp(event: KeyEvent): Boolean {
        volumeUpPressed = event.action == KeyEvent.ACTION_DOWN
        checkVolumeCombo()
        return false // Don't consume volume keys
    }

    private fun handleVolumeDown(event: KeyEvent): Boolean {
        volumeDownPressed = event.action == KeyEvent.ACTION_DOWN
        checkVolumeCombo()
        return false
    }

    /**
     * Volume Up + Volume Down held together activates assistant.
     */
    private fun checkVolumeCombo() {
        if (volumeUpPressed && volumeDownPressed) {
            volumeUpPressed = false
            volumeDownPressed = false
            activateAssistant("volume_combo")
        }
    }

    private fun activateAssistant(trigger: String) {
        Log.i(TAG, "Activating assistant via: $trigger")
        
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
            putExtra(AssistantService.EXTRA_TRIGGER, trigger)
        }
        
        startForegroundService(intent)
    }
}
