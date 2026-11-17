package com.mg4.control.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mg4.control.MainActivity
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.service.MG4ControlService
import com.mg4.control.update.ApkCleanup
import com.mg4.control.update.SilentInstaller
import com.mg4.control.util.LocaleHelper

/**
 * MG4Control vient d'être mis à jour (2026-11-04) : l'installation a tué l'ancien processus.
 * Le service repart tout de suite — bulles, écran conducteur, automatisations — sans attendre le
 * prochain démarrage de la voiture ; l'écran se rouvre si la mise à jour a été lancée depuis lui.
 * Vaut aussi pour une mise à jour installée à la main.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        com.mg4.control.util.FirmwareInfo.initWithContext(context)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        AppLogger.i(TAG, "MG4Control mis à jour (${version ?: "?"}) → relance du service")
        runCatching { context.startForegroundService(Intent(context, MG4ControlService::class.java)) }
            .onFailure { AppLogger.w(TAG, "relance du service impossible : ${it.message}") }
        runCatching { ApkCleanup.cleanIfNeeded() }
        if (SilentInstaller.lanceeDepuisEcran(context)) {
            val loc = LocaleHelper.applyLocale(context)
            Toast.makeText(loc, loc.getString(R.string.update_installed, version ?: ""), Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { AppLogger.w(TAG, "réouverture de l'écran impossible : ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "MG4_UPDATE"
    }
}
