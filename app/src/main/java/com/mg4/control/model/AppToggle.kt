package com.mg4.control.model

/**
 * Raccourci qui ouvre une appli, en bascule (2026-11-19, sa demande) : si l'appli est déjà à l'écran,
 * l'appui ramène à l'écran d'avant au lieu de la rouvrir — comme le raccourci Android Auto / CarPlay
 * (#47). On ne la ferme pas : la musique, le guidage… continuent en arrière-plan.
 *
 * Vaut pour les appuis (volant, raccourcis avancés, bulles, API externe), pas pour une automatisation
 * qui ouvre une appli : elle la veut à l'écran. Les règles, sans Android ; [tasks] = (identifiant,
 * paquet de son activité du haut), la plus récente d'abord.
 */
object AppToggle {

    /** Emplacement d'une automatisation ([com.mg4.control.automation.CustomRules]) : pas de bascule. */
    fun appliesTo(pressKey: String): Boolean = !pressKey.startsWith("rule:")

    /** [pkg] est l'appli au premier plan. */
    fun isFront(tasks: List<Pair<Int, String?>>, pkg: String?): Boolean =
        pkg != null && tasks.firstOrNull()?.second == pkg

    /** Tâche où revenir : la plus récente qui n'est pas [pkg] ; `null` = l'accueil. */
    fun returnTask(tasks: List<Pair<Int, String?>>, pkg: String): Int? =
        tasks.firstOrNull { (_, p) -> p != null && p != pkg }?.first
}
