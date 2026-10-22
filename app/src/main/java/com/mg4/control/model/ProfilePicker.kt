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

    /**
     * Carte d'où part le joystick, et que la rangée montre à l'ouverture : celle du profil actif
     * (demande du 2026-10-15, avec le passage à dix profils). Sauf dans un conflit Bluetooth
     * ([restreint]) : le délai y applique la PREMIÈRE carte, c'est elle qu'il faut voir et viser.
     * Profil actif absent de la liste, ou aucun : la première.
     */
    fun carteDeDepart(rangActif: Int, nombre: Int, restreint: Boolean): Int =
        if (restreint || rangActif !in 0 until nombre) 0 else rangActif

    /**
     * Défilement de la rangée à l'ouverture, en dp, pour que la carte [rang] soit entière à
     * l'écran avec un quart de sa voisine — ce que fait le joystick en s'y déplaçant
     * (OverlayNavigation). Rien à défiler tant que la carte tient dans la rangée ; jamais au-delà
     * de la dernière carte.
     */
    fun defilementInitialDp(rang: Int, nombre: Int): Float {
        if (nombre <= VISIBLES || rang <= 0) return 0f
        val carte = largeurCarteDp(nombre)
        val droite = rang * (carte + ECART_DP) + carte
        if (droite <= RANGEE_DP) return 0f
        val total = nombre * carte + (nombre - 1) * ECART_DP
        return minOf(droite - RANGEE_DP + carte / 4f, total - RANGEE_DP)
    }
}
