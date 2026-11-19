package com.mg4.control.model

/**
 * Clignotants (2026-11-19) : propriété `0x21409326` du groupe des feux — 0 éteint, 1 gauche,
 * 2 droit, 3 warnings. Lue par l'appli caméra 360 de jamakr4 (`TurnSignal.java`) ; présente dans le
 * service de la voiture des 5 firmwares extraits (SWI133, SWI69, SWI131, SWI132, SWI173).
 */
object TurnSignal {

    const val PROP = 0x21409326

    const val OFF = "OFF"
    const val LEFT = "LEFT"
    const val RIGHT = "RIGHT"
    const val HAZARD = "HAZARD"

    /** Valeurs des automatisations, dans l'ordre du choix. */
    val TEXTES = listOf(LEFT, RIGHT, HAZARD, OFF)

    /** Relecture pendant qu'une automatisation s'en sert : la réaction est quasi immédiate. */
    const val SONDE_MS = 250L

    /**
     * Extinction : comptée tout de suite (2026-11-19). La valeur reste à 1 / 2 tant que le commodo est
     * actionné, elle ne suit pas la lampe : vu en voiture avec l'appli caméra de jamakr4, dont la
     * vue reste à l'écran sans couper à chaque clignotement (son délai de masquage vaut 0 par défaut).
     */
    const val EXTINCTION_MS = 0L

    /** Valeur brute → texte, `null` si illisible ou inconnue. */
    fun texte(brut: Int?): String? = when (brut) {
        0 -> OFF
        1 -> LEFT
        2 -> RIGHT
        3 -> HAZARD
        else -> null
    }
}

/**
 * Valeur stable du clignotant, à partir des relevés bruts : un clignotant allumé (ou qui change de
 * côté) compte tout de suite ; « éteint » après [TurnSignal.EXTINCTION_MS] sans aucun relevé
 * allumé (0 aujourd'hui : tout de suite ; le délai reste réglable si un firmware fait clignoter
 * la valeur). Un relevé illisible ne change rien.
 */
class TurnSignalDebounce(private val extinctionMs: Long = TurnSignal.EXTINCTION_MS) {

    /** Valeur stable ; `null` tant que rien n'a été lu. */
    var stable: String? = null
        private set
    private var eteintDepuis: Long? = null

    /** Un relevé [texte] à [maintenant] ; vrai si la valeur stable vient de changer. */
    fun onSample(texte: String?, maintenant: Long): Boolean {
        texte ?: return false
        if (texte != TurnSignal.OFF) {
            eteintDepuis = null
            if (texte == stable) return false
            stable = texte
            return true
        }
        if (stable == null) {
            stable = TurnSignal.OFF
            return true
        }
        if (stable == TurnSignal.OFF) return false
        val depuis = eteintDepuis ?: maintenant.also { eteintDepuis = it }
        if (maintenant - depuis < extinctionMs) return false
        stable = TurnSignal.OFF
        eteintDepuis = null
        return true
    }
}
