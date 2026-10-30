package com.mg4.control.model

/**
 * Fin de l'échelle du module Puissance de l'écran conducteur (issue #165, proposition B du
 * 2026-10-30) : la puissance maxi CHOISIE dans les réglages, ou — Auto, le défaut — un plafond
 * APPRIS en roulant. Jusque-là l'échelle allait à 320 kW pour toutes les MG4, la puissance de la
 * XPower ; une MG4 de 150 kW n'en voyait jamais la moitié haute.
 *
 * La voiture ne dit pas sa puissance moteur (aucune propriété trouvée) : on apprend des pointes.
 *  • Une pointe au-delà du plafond le fait monter TOUT DE SUITE, au palier qui la contient avec 5 %
 *    de marge : la jauge n'est jamais coincée en butée.
 *  • Il ne redescend qu'à la fin d'un trajet, après [TRAJETS_POUR_BAISSER] trajets tous restés sous
 *    [SEUIL_BAISSE] du plafond, d'un palier à la fois, jamais sous 100 kW.
 * Les paliers sont des kW entiers : les crans de la jauge n'ont jamais de virgule.
 */
object PowerScale {

    val PALIERS_KW = listOf(100, 125, 150, 180, 200, 250, 320)
    /** Ce que propose le réglage, en plus d'Auto. */
    val CHOIX_KW = listOf(125, 150, 180, 320)
    /** Plafond de départ, tant que rien n'est appris. */
    const val DEPART_KW = 150
    const val MARGE = 1.05f
    const val TRAJETS_POUR_BAISSER = 5
    const val SEUIL_BAISSE = 0.7f
    /** Pointes gardées, une par trajet. */
    const val MEMOIRE_TRAJETS = 10

    /** Plus petit palier qui contient [kw] avec sa marge ; 320 au-delà. */
    fun palier(kw: Float): Int = PALIERS_KW.firstOrNull { it >= kw * MARGE } ?: PALIERS_KW.last()

    /** Plafond après une valeur lue en roulant : il ne fait que monter. */
    fun apresReleve(plafond: Int, kw: Float): Int = if (kw > plafond) maxOf(plafond, palier(kw)) else plafond

    /** Plafond à la fin d'un trajet ; [pointes] : une par trajet, la dernière est celle qui finit. */
    fun apresTrajet(plafond: Int, pointes: List<Float>): Int {
        val p = pointes.lastOrNull()?.let { apresReleve(plafond, it) } ?: plafond
        val recentes = pointes.takeLast(TRAJETS_POUR_BAISSER)
        if (recentes.size < TRAJETS_POUR_BAISSER || recentes.any { it >= p * SEUIL_BAISSE }) return p
        val dessous = PALIERS_KW.lastOrNull { it < p } ?: PALIERS_KW.first()
        return maxOf(dessous, palier(recentes.max()))
    }

    /** Fin de l'échelle : le choix s'il en est un, sinon le plafond appris. */
    fun pleineEchelle(choix: Int?, appris: Int): Float = (choix?.takeIf { it in CHOIX_KW } ?: appris).toFloat()
}
