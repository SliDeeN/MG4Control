package com.mg4.control.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.mg4.control.model.CustomShortcuts
import com.mg4.control.model.DashcamContract

/**
 * Appli caméra 360° / dashcam pour la MG4 (issue #180). Le bouton Surveillance est visible
 * pour tous depuis le 2026-11-08, mais la surveillance ne démarre qu'avec une appli dashcam ;
 * pilotable, MG4Control lance et arrête l'enregistrement lui-même.
 *
 * - [Detection.Pilotable] : une appli déclare START/STOP_CONTINUOUS pour MG4Control (contrat de
 *   jamakr4/MG4-360-Camera-App, `com.drivehub.kamera`, ou d'un fork qui garde ce code).
 * - [Detection.Ancienne] : une appli caméra connue est là, mais sans ces commandes —
 *   [Detection.Ancienne.aMettreAJour] pour l'appli d'origine dans une version trop ancienne,
 *   sinon une appli qui ne sait pas être pilotée (le fork de Tommasov `com.tommasov.mg4dashcam`) :
 *   l'enregistrement se lance alors à la main.
 * - [Detection.Absente] : rien — la surveillance ne démarre pas (sa décision, 2026-11-08).
 */
object DashcamApp {

    /** L'appli d'origine, celle qui a ajouté le pilotage (2026-11-08). */
    const val ORIGINE = "com.drivehub.kamera"

    /** Applis caméra connues : sans elles, pas de surveillance. */
    val PAQUETS = listOf(ORIGINE, "com.tommasov.mg4dashcam")

    sealed class Detection {
        data class Pilotable(val commandes: DashcamContract.Commands, val nom: String) : Detection()
        data class Ancienne(val paquet: String, val nom: String) : Detection() {
            /** L'appli d'origine sans le pilotage : une mise à jour suffit. */
            val aMettreAJour: Boolean get() = paquet == ORIGINE
        }
        object Absente : Detection()

        val pilotable: Boolean get() = this is Pilotable
        /** Une appli caméra est là, pilotable ou non : la surveillance peut démarrer. */
        val presente: Boolean get() = this !is Absente
    }

    fun detecter(ctx: Context): Detection {
        val pm = ctx.packageManager
        val recepteurs = runCatching {
            pm.queryBroadcastReceivers(Intent(CustomShortcuts.DISCOVERY_ACTION), PackageManager.GET_RESOLVED_FILTER)
                .filter { estAppliUtilisateur(it.activityInfo.applicationInfo) }
                .map { ri ->
                    val f = ri.filter
                    DashcamContract.Receiver(ri.activityInfo.packageName, ri.activityInfo.name,
                        if (f == null) emptyList() else (0 until f.countActions()).map { f.getAction(it) })
                }
        }.getOrDefault(emptyList())
        DashcamContract.find(recepteurs)?.let { return Detection.Pilotable(it, nom(ctx, it.packageName)) }
        val connue = PAQUETS.firstOrNull { p ->
            runCatching { pm.getPackageInfo(p, 0) }.isSuccess
        } ?: return Detection.Absente
        return Detection.Ancienne(connue, nom(ctx, connue))
    }

    private fun nom(ctx: Context, paquet: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(paquet, 0)).toString()
    }.getOrDefault(paquet)

    /**
     * MG4Control tourne avec les droits du système : on ne pilote qu'une appli installée par
     * l'utilisateur, jamais une appli système qui déclarerait les mêmes actions.
     */
    private fun estAppliUtilisateur(ai: ApplicationInfo): Boolean =
        ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
}
