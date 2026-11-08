package com.mg4.control.ui

import android.content.Context
import android.text.format.Formatter
import com.mg4.control.R
import com.mg4.control.model.DashcamContract
import com.mg4.control.service.DashcamRemote

/**
 * Textes du test de la clé USB de l'appli caméra (issue #180), communs à la carte « Caméra de
 * surveillance » du Garde-voiture et à la ligne caméra du pop-up de surveillance.
 */
object DashcamUi {

    /** Sous ce seuil, la clé est « presque pleine » (niveaux very_low / low de l'appli : < 3 Gio). */
    private const val PEU_DE_PLACE = 3L * 1024 * 1024 * 1024

    enum class Niveau { OK, ALERTE }

    data class Texte(val texte: String, val niveau: Niveau)

    private fun taille(loc: Context, octets: Long) = Formatter.formatShortFileSize(loc, octets)

    /** « environ 9 h 12 » : ce que la place libre permet de filmer, d'après le débit annoncé. */
    private fun duree(loc: Context, octets: Long): String {
        val m = DashcamContract.minutesFor(octets) ?: 0
        return if (m >= 60) loc.getString(R.string.cluster_duration_hm, m / 60, m % 60)
        else loc.getString(R.string.cluster_duration_min, m)
    }

    /** Résultat complet, pour la carte des réglages. `null` = l'appli n'a pas répondu. */
    fun reglages(loc: Context, u: DashcamRemote.Usb?): Texte = when {
        u == null -> Texte(loc.getString(R.string.preclim_cam_usb_noanswer), Niveau.ALERTE)
        u.status == "ok" && u.freeBytes in 0 until PEU_DE_PLACE ->
            Texte(loc.getString(R.string.preclim_cam_usb_low, taille(loc, u.freeBytes), duree(loc, u.freeBytes)), Niveau.ALERTE)
        u.status == "ok" ->
            Texte(loc.getString(R.string.preclim_cam_usb_ok, taille(loc, u.freeBytes), taille(loc, u.totalBytes),
                duree(loc, u.freeBytes)), Niveau.OK)
        else -> Texte(probleme(loc, u.status), Niveau.ALERTE)
    }

    private fun probleme(loc: Context, status: String): String = loc.getString(when (status) {
        "no_medium" -> R.string.preclim_cam_usb_none
        "out_of_space" -> R.string.preclim_cam_usb_full
        "multiple_media" -> R.string.preclim_cam_usb_multiple
        else -> R.string.preclim_cam_usb_unwritable
    })

    /** Suffixe court de la ligne du pop-up ; `null` tant que le test n'a pas répondu. */
    fun popup(loc: Context, u: DashcamRemote.Usb?): Texte? = when {
        u == null -> null
        u.status == "ok" && u.freeBytes in 0 until PEU_DE_PLACE ->
            Texte(loc.getString(R.string.preclim_surv_cam_usb_low, taille(loc, u.freeBytes)), Niveau.ALERTE)
        u.status == "ok" -> Texte(loc.getString(R.string.preclim_surv_cam_usb_ok, taille(loc, u.freeBytes)), Niveau.OK)
        u.status == "no_medium" -> Texte(loc.getString(R.string.preclim_surv_cam_usb_none), Niveau.ALERTE)
        else -> Texte(loc.getString(R.string.preclim_surv_cam_usb_problem), Niveau.ALERTE)
    }
}
