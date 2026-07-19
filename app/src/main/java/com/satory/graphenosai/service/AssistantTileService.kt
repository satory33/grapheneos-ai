package com.satory.graphenosai.service

import android.os.Build
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

        // Collapse quick settings panel (best-effort)
        requestCollapse()

        // Start assistant service
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
        }
        startForegroundService(intent)
    }

    /**
     * Attempt to collapse the Quick Settings panel.
     * Uses reflection on API < 33 as a best-effort approach.
     * On API 33+ the system typically auto-dismisses after tile click.
     */
    @Suppress("DEPRECATION")
    private fun requestCollapse() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                val statusBarService = getSystemService("statusbar")
                if (statusBarService != null) {
                    val statusBarManager = Class.forName("android.app.StatusBarManager")
                    val collapse = statusBarManager.getMethod("collapsePanels")
                    collapse.invoke(statusBarService)
                }
            } catch (e: Exception) {
                // Collapse is best-effort — the service still activates
            }
        }
    }
}
