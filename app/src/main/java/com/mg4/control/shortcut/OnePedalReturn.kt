package com.mg4.control.shortcut

import com.mg4.control.model.RegenLevel

/**
 * Raccourci One Pedal, second appui (issue #226, 2026-11-08) : que devient la régénération quand
 * on coupe le One Pedal. Par défaut [PREVIOUS] : la voiture remet d'elle-même le niveau qu'elle
 * avait avant (vérifié en voiture) ; sinon un niveau fixe choisi dans Raccourcis › 1 Pédale.
 *
 * Nouvelle clé : l'ancienne (`shortcut_one_pedal_fallback`, Fort par défaut, Off possible) n'est
 * plus lue, pour que tout le monde reparte sur « Comme avant ».
 */
object OnePedalReturn {

    const val KEY = "shortcut_one_pedal_return"
    const val PREVIOUS = -1

    /** Niveaux proposés (pas de Off). */
    val CHOICES = listOf(RegenLevel.LOW, RegenLevel.MEDIUM, RegenLevel.HIGH, RegenLevel.ADAPTIVE)

    /** Niveau à imposer pour la valeur enregistrée [stored], ou `null` = laisser la voiture faire. */
    fun levelFor(stored: Int): RegenLevel? = CHOICES.firstOrNull { it.value == stored }
}
