package com.mg4.control.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.mg4.control.debug.AppLogger

/**
 * Redémarrage de l'application après l'import des réglages (issue #269) : tout ce qui a été lu en
 * mémoire (profils, bulles, écran conducteur, automatisations) repart des nouveaux réglages.
 * L'écran d'accueil de MG4Control revient une seconde plus tard et relance le service.
 */
object AppRestart {

    fun relancer(ctx: Context) {
        val app = ctx.applicationContext
        val ouvrir = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (ouvrir != null) {
            val pi = PendingIntent.getActivity(app, 2690, ouvrir, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1_000L, pi)
        }
        AppLogger.i("MG4_BACKUP", "redémarrage de l'application après l'import")
        Process.killProcess(Process.myPid())
    }
}
