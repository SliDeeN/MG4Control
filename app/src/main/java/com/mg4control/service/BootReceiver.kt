package com.mg4control.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4control.ui.AppLogger

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context.applicationContext)
        AppLogger.i(TAG, "BootReceiver.onReceive : action=${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.car.intent.action.CAR_USER_SWITCHED",
            "android.intent.action.USER_UNLOCKED",
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                AppLogger.i(TAG, "Boot/unlock détecté — démarrage AutoStartService")
                val serviceIntent = Intent(context, AutoStartService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
