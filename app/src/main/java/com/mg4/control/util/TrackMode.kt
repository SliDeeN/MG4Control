package com.mg4.control.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.AppToggle
import com.mg4.control.service.AppToggleRunner

/**
 * Track mode (issue #202, 2026-11-19) : l'écran « circuit » de la MG4 XPower, caché dans
 * l'appli d'entretien SAIC (`com.saic.saicmaintenance`) — chronos, tours, G-mètre, page du
 * combiné. Présent dans les extraits SWI133 et SWI68 (activité exportée), absent de SWI173 et de
 * la famille A9 (SWI69/131/132) : le raccourci n'est proposé que si l'activité existe.
 */
object TrackMode {

    private const val TAG = "MG4_SHORTCUT"
    const val PACKAGE = "com.saic.saicmaintenance"
    private val ACTIVITE = ComponentName(PACKAGE, "com.saicmotor.hmi.trackmode.MainActivity")

    /** L'écran Track mode existe sur cette voiture. */
    fun available(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getActivityInfo(ACTIVITE, 0); true }.getOrDefault(false)

    /**
     * Ouvre le Track mode ; déjà à l'écran, ramène l'écran d'avant (bascule des raccourcis qui
     * ouvrent une appli, sauf depuis une automatisation : [pressKey] `rule:…`).
     */
    fun open(ctx: Context, pressKey: String) {
        if (AppToggle.appliesTo(pressKey) && AppToggleRunner.backIfFront(ctx, PACKAGE)) return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ACTIVITE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess { AppLogger.i(TAG, "Track mode ouvert") }
            .onFailure { AppLogger.w(TAG, "Track mode non ouvert : ${it.javaClass.simpleName} ${it.message}") }
    }
}
