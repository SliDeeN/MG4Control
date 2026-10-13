package com.mg4.control.model

/**
 * Sélecteur de profils (pop-up) — ce qui se calcule sans vue ni véhicule.
 *
 * Les profils y sont une rangée de cartes (proposition « B », choisie sur maquette le
 * 2026-10-13). Les cotes sont celles de overlay_profile_picker.xml, en dp de base : le pop-up
 * les agrandit d'un bloc pour l'écran de la voiture.
 */
object ProfilePicker {

    /** Largeur de la rangée : celle de la carte (1108 dp) moins ses marges. */
    const val RANGEE_DP = 1076f
    const val ECART_DP = 10f

    /** Au-delà, la rangée défile. */
    const val VISIBLES = 5

    /** Plafond : un ou deux profils ne font pas des cartes de la largeur de l'écran. */
    const val CARTE_MAX_DP = 340f

    /** Largeur de carte quand la rangée défile. */
    private const val CARTE_DEFILANTE_DP = 196f

    /**
     * Largeur d'une carte pour [nombre] profils. Jusqu'à [VISIBLES], elles se partagent la
     * rangée (sans dépasser [CARTE_MAX_DP]) ; au-delà, une largeur fixe choisie pour que la
     * sixième dépasse du bord — c'est ce qui montre qu'on peut faire défiler.
     */
    fun largeurCarteDp(nombre: Int): Float = when {
        nombre > VISIBLES -> CARTE_DEFILANTE_DP
        nombre <= 0       -> CARTE_MAX_DP
        else              -> minOf(CARTE_MAX_DP, (RANGEE_DP - (nombre - 1) * ECART_DP) / nombre)
    }
}
