package com.mg4.control.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4.hardware.AppLogger
import com.mg4.control.service.MG4ControlService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MG4_BOOT"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        AppLogger.i(TAG, "onReceive: $action — démarrage MG4ControlService")

        // Pas de retry différé ici : startForegroundService revient normalement dans les
        // cas d'échec qui comptent, donc les anciennes relances à 3 s et 8 s ne se
        // déclenchaient jamais — et un postDelayed survivrait de toute façon à la durée
        // de vie légitime du receiver. Le service se relance seul (START_STICKY).
        val started = tryStart(context, Intent(context, MG4ControlService::class.java))
        AppLogger.i(TAG, "startForegroundService → $started")
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "startForegroundService error: ${e.message}")
            try { context.startService(intent); true } catch (_: Exception) { false }
        }
    }
}
