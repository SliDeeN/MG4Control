package com.mg4.control.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.util.LocaleHelper

/**
 * Résultat de la session d'installation ([SilentInstaller]). Un succès tue MG4Control avant
 * d'arriver ici le plus souvent — la suite est dans [com.mg4.control.receiver.PackageReplacedReceiver].
 * Un échec revient à l'ancienne voie : le dossier Téléchargements, où l'APK attend.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SilentInstaller.ACTION_RESULT) return
        val statut = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        AppLogger.i(TAG, "résultat de l'installation : statut $statut ${message.orEmpty()}")
        when (statut) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Ne devrait pas arriver sous l'uid système ; l'installeur d'Android prend le relais.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmer = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmer?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { confirmer?.let(context::startActivity) }
                    .onFailure { AppLogger.w(TAG, "confirmation impossible : ${it.message}") }
            }
            else -> {
                SilentInstaller.oublier(context)
                val loc = LocaleHelper.applyLocale(context)
                Toast.makeText(loc, loc.getString(R.string.update_install_failed, message ?: statut.toString()),
                    Toast.LENGTH_LONG).show()
                runCatching {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private companion object {
        const val TAG = "MG4_UPDATE"
    }
}
