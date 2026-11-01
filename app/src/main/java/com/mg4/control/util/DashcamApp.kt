package com.mg4.control.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * Appli caméra 360° / dashcam pour la MG4 (issue #180) : le mode Surveillance de la
 * pré-climatisation n'est proposé que si elle est installée — sans elle, rien n'enregistre.
 *
 * Deux paquets : le fork de Tommasov (github.com/Tommasov/MG4_Dashcam, celui de l'issue #172) et
 * l'appli d'origine, jamakr4/MG4-360-Camera-App, dont il garde le code (`com.drivehub.kamera`).
 */
object DashcamApp {

    val PAQUETS = listOf("com.tommasov.mg4dashcam", "com.drivehub.kamera")

    /** Le paquet installé, ou `null` si aucune des deux n'est là. */
    fun installee(ctx: Context): String? = PAQUETS.firstOrNull { p ->
        try {
            ctx.packageManager.getPackageInfo(p, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
