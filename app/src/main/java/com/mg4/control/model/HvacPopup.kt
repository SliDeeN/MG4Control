package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus

/**
 * Pop-up HVAC (issues #120 et #125) — ce que fait le joystick droit, sans vue ni véhicule.
 *
 * Ici le joystick ne déplace aucun focus, contrairement aux popups de profils : chaque direction
 * agit directement, comme dans la fenêtre clim d'origine de la voiture.
 *
 * |          | Action           |
 * |----------|------------------|
 * | Haut     | température + 1  |
 * | Bas      | température − 1  |
 * | Droite   | ventilation + 1  |
 * | Gauche   | ventilation − 1  |
 * | Clic     | ventilation AUTO |
 *
 * La fenêtre se ferme au doigt, par le raccourci qui l'a ouverte, ou seule après son délai.
 */
object HvacPopup {

    sealed class Action {
        data class Temperature(val cible: Int) : Action()
        data class Ventilation(val cible: Int) : Action()
        /** Ventilation automatique : la case centrale de la croix. */
        data class Auto(val actif: Boolean) : Action()
    }

    /** Ce qui est lu sur la voiture ; les bornes en viennent aussi. null = illisible. */
    data class Reglages(
        val temp: Int?, val tempMin: Int, val tempMax: Int,
        val fan: Int?, val fanMin: Int, val fanMax: Int,
        /** Ventilation AUTO ; null = illisible. */
        val auto: Boolean? = null,
    ) {
        /**
         * Niveau de ventilation à montrer et à régler, ou null s'il n'y en a pas : en AUTO la
         * voiture annonce 15, qui n'est pas un cran (constaté sur SWI133 le 2026-10-09). Tout ce
         * qui dépasse le maximum est traité de même — ce 15 peut traîner un instant après AUTO.
         */
        val ventilationReelle: Int? get() = fan?.takeIf { auto != true && it <= fanMax }
    }

    /** Action portée par [commande], ou null s'il n'y a rien à écrire (butée, valeur illisible). */
    fun action(commande: JoystickFocus.Commande, reglages: Reglages): Action? = when (commande) {
        JoystickFocus.Commande.HAUT    -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, +1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.BAS     -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, -1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.DROITE  -> ventilation(reglages, +1)
        JoystickFocus.Commande.GAUCHE  -> ventilation(reglages, -1)
        // Sans état lisible on ne bascule pas : c'est l'inverse de l'intention qui pourrait partir.
        JoystickFocus.Commande.VALIDER -> reglages.auto?.let { Action.Auto(!it) }
    }

    /**
     * En AUTO, un geste sur la ventilation en SORT sans écrire de niveau : il n'y a pas de cran
     * d'où partir, et partir du 15 annoncé écrirait le maximum même pour un « moins ». La voiture
     * annonce ensuite son vrai niveau, et c'est le geste suivant qui le règle.
     */
    private fun ventilation(reglages: Reglages, sens: Int): Action? =
        if (reglages.auto == true) Action.Auto(false)
        else pas(reglages.ventilationReelle, reglages.fanMin, reglages.fanMax, sens)?.let { Action.Ventilation(it) }

    // ── Taille de la fenêtre ─────────────────────────────────────────────────
    private const val LARGEUR_DP = 780f      // la carte de overlay_hvac_popup.xml
    private const val HAUTEUR_DP = 390f      // sa hauteur en texte « Standard »
    private const val PART_LARGEUR = 0.56f
    private const val PART_HAUTEUR = 0.72f

    /**
     * Agrandissement à appliquer à la fenêtre, dessinée pour [LARGEUR_DP] × [HAUTEUR_DP] dp, pour
     * qu'elle prenne la même part de l'ÉCRAN quelle que soit sa densité : au plus 56 % de la
     * largeur et 72 % de la hauteur. Le reste de la hauteur laisse passer la barre d'état, le
     * texte « Très grand » et la ligne d'avertissement sans déborder.
     *
     * On raisonne en part d'écran, pas en dp fixes : la voiture affiche 1920 × 720 px à 1 px par
     * dp (SWI133, mesuré sur une photo le 2026-10-09 — une première fenêtre de 600 dp n'y
     * couvrait que 31 % de la largeur), mais la densité des firmwares A9 n'est pas connue.
     */
    fun echelle(largeurPx: Int, hauteurPx: Int, densite: Float): Float {
        if (largeurPx <= 0 || hauteurPx <= 0 || !(densite > 0f)) return 1f
        val parLargeur = PART_LARGEUR * largeurPx / (LARGEUR_DP * densite)
        val parHauteur = PART_HAUTEUR * hauteurPx / (HAUTEUR_DP * densite)
        return minOf(parLargeur, parHauteur).coerceIn(0.75f, 2f)
    }

    /**
     * On CLAMPE, on ne boucle pas : arriver à 32 °C et repartir à 16 en poussant sur le volant
     * serait une très mauvaise surprise. Une cible égale à la valeur lue ne s'écrit pas.
     */
    private fun pas(actuel: Int?, min: Int, max: Int, sens: Int): Int? {
        actuel ?: return null
        return (actuel + sens).coerceIn(min, max).takeIf { it != actuel }
    }
}
