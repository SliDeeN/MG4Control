package com.mg4.control.model

/**
 * Réglage d'origine « Lock when leaving » (`COMFORT_LOCK`, 0x2140d710) — la partie sans véhicule
 * de l'écran d'essai ([com.mg4.control.hardware.LockProbe]). PROVISOIRE, comme la sonde.
 *
 * Les écrans de réglages SWI133/68/165 portent un interrupteur « verrouiller en s'éloignant avec
 * la clé », qu'ils masquent quand la voiture répond 3. Le service d'origine ne filtre rien : ce
 * 3 vient de la voiture. L'essai envoie quand même la consigne de l'interrupteur et lit la
 * réponse — d'où [verdict], qui ne doit pas prendre un refus pour un succès.
 */
object LeaveLock {

    /** Consigne de l'interrupteur d'origine : l'état voulu, pas une bascule. */
    const val SET_OFF = 0
    const val SET_ON = 1

    /** La voiture dit ne pas avoir la fonction : l'écran d'origine masque alors la ligne. */
    private const val UNAVAILABLE = 3
    /** État du chargeur de téléphone sans fil quand la clé n'est plus détectée (SWI133, 2026-10-11). */
    private const val CHARGER_KEY_AWAY = 8

    /** Une valeur suivie par l'écran d'essai, telle qu'il l'affiche. */
    data class Reading(val name: String, val value: String)

    /** Suite d'une consigne, d'après la relecture par la voie d'origine. */
    enum class Verdict(val text: String) {
        ACCEPTED_ON("Consigne acceptée : la voiture répond « actif ». Reste à s\u2019éloigner avec la clé, " +
            "portes fermées, pour voir si elle verrouille."),
        ACCEPTED_OFF("Consigne acceptée : la voiture répond « coupé »."),
        IGNORED("Consigne ignorée : la voiture répond toujours « indisponible »."),
        NOT_REFLECTED("Consigne non reflétée : la voiture ne répond pas l\u2019état demandé."),
        UNREADABLE("Relecture impossible : rien à conclure."),
    }

    /** Valeur rendue par une voie d'origine, ou null quand elle signale une lecture impossible. */
    fun raw(value: Int): Int? = value.takeIf { it != -1 }

    /** Sens d'un état lu, d'après l'écran de réglages d'origine (`updateLeaveAutoLock`). */
    fun describe(value: Int?): String = when (value) {
        null        -> "illisible"
        SET_OFF     -> "0 = coupé"
        SET_ON      -> "1 = actif"
        UNAVAILABLE -> "3 = indisponible (ligne masquée par l\u2019origine)"
        else        -> "$value = valeur inconnue du code d\u2019origine"
    }

    /** Ce que la relecture [after] dit de la consigne [on]. */
    fun verdict(on: Boolean, after: Int?): Verdict = when (after) {
        null        -> Verdict.UNREADABLE
        UNAVAILABLE -> Verdict.IGNORED
        SET_ON      -> if (on) Verdict.ACCEPTED_ON else Verdict.NOT_REFLECTED
        SET_OFF     -> if (on) Verdict.NOT_REFLECTED else Verdict.ACCEPTED_OFF
        else        -> Verdict.NOT_REFLECTED
    }

    /** Verrouillage central (`VEHICLE_LOCK`) : la tuile d'origine est allumée dès que la valeur dépasse 0. */
    fun lockState(value: Int?): String = when {
        value == null -> "illisible"
        value > 0     -> "$value = verrouillé"
        else          -> "$value = déverrouillé"
    }

    /** État du chargeur de téléphone sans fil, seul témoin connu de la présence de la clé. */
    fun keyState(value: Int?): String = when (value) {
        null             -> "illisible"
        CHARGER_KEY_AWAY -> "$value = clé non détectée"
        else             -> value.toString()
    }

    /**
     * Valeurs qui diffèrent entre deux lectures, dans l'ordre de l'écran. Une valeur absente de
     * la lecture précédente est ignorée : une première lecture n'est pas un changement.
     */
    fun changes(before: List<Reading>, after: List<Reading>): List<String> {
        val avant = before.associate { it.name to it.value }
        return after.mapNotNull { lecture ->
            val ancienne = avant[lecture.name] ?: return@mapNotNull null
            if (ancienne == lecture.value) null else "${lecture.name} : $ancienne \u2192 ${lecture.value}"
        }
    }
}
