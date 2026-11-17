package com.mg4.control.model

import kotlin.math.roundToInt

/**
 * Puissance tirée de la batterie, lissée pour l'affichage (issue #210) : moyenne des [taille]
 * derniers relevés (un par seconde sur les écrans de pré-climatisation), arrondie à 10 W.
 *
 * Un relevé illisible n'entre pas dans la moyenne ; une valeur négative (la batterie reçoit, la
 * voiture charge) non plus — l'écran parle de ce que la voiture consomme. Sans aucun relevé
 * valable dans la fenêtre : null, et l'écran n'affiche rien plutôt qu'un « 0 W » trompeur.
 */
class PowerSmoother(private val taille: Int = 5) {

    private val releves = ArrayDeque<Float?>()

    /** Ajoute un relevé, en kW positifs en décharge (null = illisible). */
    fun add(kw: Float?) {
        releves.addLast(kw?.takeIf { it.isFinite() && it >= 0f })
        while (releves.size > taille) releves.removeFirst()
    }

    /** Moyenne de la fenêtre en watts, arrondie à 10 W ; null sans relevé valable. */
    fun watts(): Int? {
        val bons = releves.filterNotNull()
        if (bons.isEmpty()) return null
        return (bons.average() * 100.0).roundToInt() * 10
    }

    fun reset() = releves.clear()
}
