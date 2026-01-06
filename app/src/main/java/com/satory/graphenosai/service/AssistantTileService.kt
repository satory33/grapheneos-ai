package com.satory.graphenosai.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Intent

/**
 * Quick Settings tile for fast assistant activation.
 */
class AssistantTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "AI Assistant"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        
        // Collapse quick settings panel
        collapsePanels()
        
        // Start assistant service
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
        }
        startForegroundService(intent)
    }

    @Suppress("DEPRECATION")
    private fun collapsePanels() {
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val collapse = statusBarManager.getMethod("collapsePanels")
            collapse.invoke(statusBarService)
        } catch (e: Exception) {
            // Ignore - panels may not collapse on all devices
        }
    }
}
