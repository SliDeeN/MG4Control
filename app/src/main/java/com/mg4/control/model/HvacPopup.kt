package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus

/**
 * Pop-up HVAC (issues #120 et #125) — ce que fait le joystick droit, sans vue ni véhicule.
 *
 * Ici le joystick ne déplace aucun focus, contrairement aux popups de profils : chaque direction
 * agit directement, comme dans la fenêtre clim d'origine de la voiture.
 *
 * |          | Action                                   |
 * |----------|------------------------------------------|
 * | Haut     | température + 1                          |
 * | Bas      | température − 1                          |
 * | Droite   | ventilation + 1                          |
 * | Gauche   | ventilation − 1                          |
 * | Clic     | mode AUTO, ou retour au réglage manuel   |
 *
 * La fenêtre se ferme au doigt, par le raccourci qui l'a ouverte, ou seule après son délai.
 *
 * ## Le mode AUTO
 *
 * En AUTO la voiture règle elle-même la ventilation (et le sens de l'air), et n'annonce plus un
 * cran mais **15** : c'est à ce 15 que les écrans d'origine reconnaissent AUTO et l'affichent
 * (`HvacControlDialog` et `AcCardView` de SWI133, `HvacView` de SWI165). Ces mêmes écrans ne
 * « coupent » jamais AUTO : ils en SORTENT en écrivant un niveau de ventilation — le dernier
 * réglé à la main, plus ou moins un cran. C'est la règle reprise ici, et partagée avec la page
 * Clim et les raccourcis « ventilation ± ».
 */
object HvacPopup {

    sealed class Action {
        data class Temperature(val cible: Int) : Action()

        /** Un niveau de ventilation écrit à la main — c'est aussi ce qui fait sortir d'AUTO. */
        data class Ventilation(val cible: Int) : Action()

        /** Passer en mode AUTO : la case centrale de la croix. */
        object Auto : Action()
    }

    /** Ce qui est lu sur la voiture ; les bornes en viennent aussi. null = illisible. */
    data class Reglages(
        val temp: Int?, val tempMin: Int, val tempMax: Int,
        val fan: Int?, val fanMin: Int, val fanMax: Int,
        /** Mode AUTO annoncé par la voiture ; null = illisible. */
        val auto: Boolean? = null,
        /** Dernier niveau de ventilation réglé à la main, retenu avant AUTO ; null = jamais vu. */
        val dernierNiveau: Int? = null,
    ) {
        /**
         * AUTO actif : la voiture le dit, ou son niveau de ventilation le trahit — au-delà du
         * maximum ce n'est plus un cran (15), et c'est à cela que les écrans d'origine s'y fient.
         */
        val enAuto: Boolean get() = auto == true || (fan != null && fan > fanMax)

        /** Niveau de ventilation à montrer et à régler ; null en AUTO ou s'il est illisible. */
        val ventilationReelle: Int? get() = fan?.takeIf { !enAuto }
    }

    /** Action portée par [commande], ou null s'il n'y a rien à écrire (butée, valeur illisible). */
    fun action(commande: JoystickFocus.Commande, reglages: Reglages): Action? = when (commande) {
        JoystickFocus.Commande.HAUT    -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, +1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.BAS     -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, -1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.DROITE  -> pasVentilation(reglages, +1)?.let { Action.Ventilation(it) }
        JoystickFocus.Commande.GAUCHE  -> pasVentilation(reglages, -1)?.let { Action.Ventilation(it) }
        JoystickFocus.Commande.VALIDER -> when {
            // Rendre la main : le dernier niveau, tel quel.
            reglages.enAuto        -> Action.Ventilation(niveauDeSortie(reglages))
            reglages.auto == false -> Action.Auto
            // Sans état lisible on n'appuie pas : sur l'ancien SDK la commande est un APPUI, et
            // rien ne dit qu'un second ne couperait pas le mode qu'on voulait activer.
            else                   -> null
        }
    }

    /**
     * Niveau de ventilation à écrire pour un cran de plus ([sens] = +1) ou de moins (−1) ; null
     * s'il n'y a rien à écrire (butée, niveau illisible).
     *
     * En AUTO on repart du dernier niveau réglé à la main — surtout pas du 15 annoncé, d'où un
     * « moins » écrirait le MAXIMUM — et on écrit MÊME en butée : c'est cette écriture qui rend
     * la main, et l'utilisateur vient de demander à régler lui-même.
     */
    fun pasVentilation(reglages: Reglages, sens: Int): Int? =
        if (reglages.enAuto) (niveauDeSortie(reglages) + sens).coerceIn(reglages.fanMin, reglages.fanMax)
        else pas(reglages.fan, reglages.fanMin, reglages.fanMax, sens)

    /**
     * Niveau à écrire pour quitter AUTO sans rien changer d'autre : le dernier réglé à la main.
     * Faute de l'avoir jamais vu (application neuve, voiture en AUTO depuis toujours), le milieu
     * de l'échelle — ni le minimum ni le maximum ne feraient un point de départ raisonnable.
     */
    fun niveauDeSortie(reglages: Reglages): Int =
        (reglages.dernierNiveau ?: ((reglages.fanMin + reglages.fanMax) / 2))
            .coerceIn(reglages.fanMin, reglages.fanMax)

    // ── Taille de la fenêtre ─────────────────────────────────────────────────
    private const val LARGEUR_DP = 900f      // la carte de overlay_hvac_popup.xml
    private const val HAUTEUR_DP = 390f      // sa hauteur en texte « Standard »
    private const val PART_LARGEUR = 0.63f
    private const val PART_HAUTEUR = 0.72f

    /**
     * Agrandissement à appliquer à la fenêtre, dessinée pour [LARGEUR_DP] × [HAUTEUR_DP] dp, pour
     * qu'elle prenne la même part de l'ÉCRAN quelle que soit sa densité : au plus 63 % de la
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
