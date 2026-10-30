package com.mg4.control.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger

/**
 * Ouvre les paramètres Android (bouton de Réglages › Infos et raccourci « Paramètres Android »,
 * 2026-10-31 — repris du fork sensoyg). Le launcher SAIC n'y donne pas accès ; selon le firmware,
 * l'écran est celui d'Android ou celui d'Android Automotive : on essaie les deux, dans l'ordre.
 */
object AndroidSettings {

    private const val TAG = "MG4_SETTINGS"

    private fun candidats(): List<Intent> = listOf(
        Intent(Settings.ACTION_SETTINGS),
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings")),
        Intent().setComponent(ComponentName("com.android.car.settings", "com.android.car.settings.Settings_Standard")),
        Intent().setComponent(ComponentName("com.android.car.settings", "com.android.car.settings.HomepageActivity")),
        Intent().setComponent(ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities")),
    )

    /** Rend vrai si un écran s'est ouvert ; sinon un message le dit. N'importe quel thread. */
    fun open(context: Context): Boolean {
        for (intent in candidats()) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) {
                AppLogger.i(TAG, "paramètres Android ouverts : ${intent.component?.flattenToShortString() ?: intent.action}")
                return true
            }
        }
        AppLogger.w(TAG, "paramètres Android : aucun écran trouvé")
        val ctx = LocaleHelper.applyLocale(context)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, R.string.android_settings_open_failed, Toast.LENGTH_SHORT).show()
        }
        return false
    }
}
