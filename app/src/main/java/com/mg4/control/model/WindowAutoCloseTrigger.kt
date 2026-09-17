package com.mg4.control.model

/**
 * Fermeture automatique des vitres en quittant la voiture : quand déclencher.
 *
 * Logique du projet winclose (validée par ses utilisateurs sur SWI69), dont le « capteur de porte »
 * est en réalité l'état READY du groupe motopropulseur (`SENSOR_EPTRDY`) : sur MG4, ouvrir la porte
 * conducteur en P fait sortir la voiture de READY.
 * - **trajet** : il commence au passage en READY, qui désarme (il faut rouler de nouveau) ;
 * - **armement** ([arming], réglable) : vitesse atteinte et/ou READY depuis une durée ;
 * - **déclenchement** : sortie de READY, levier en P, armé, hors pause de [cooldownMs] depuis le
 *   précédent → fermeture programmée dans [delayMs] (réglable) ;
 * - **annulation** : retour en READY pendant le délai, ou levier sorti de P.
 *
 * READY tombe aussi quand on éteint la voiture en restant assis : les vitres se ferment alors
 * aussi (comportement de winclose, accepté).
 *
 * Aucune dépendance Android : les horloges et lectures véhicule sont passées en paramètre.
 */
class WindowAutoCloseTrigger(
    /** Modifiable à chaud (réglages) : pris en compte au tic suivant. */
    var arming: Arming = Arming.DEFAULT,
    /** Modifiable à chaud (réglages) : vaut pour la prochaine attente comme pour celle en cours. */
    var delayMs: Long = DELAY_DEFAULT_S * 1000L,
    private val cooldownMs: Long = COOLDOWN_MS,
) {

    /**
     * Conditions d'armement. null = condition désactivée. Avec les deux actives, [requireBoth]
     * choisit entre « l'une ou l'autre » (winclose) et « les deux ». Sans aucune, jamais armé.
     */
    data class Arming(val speedKmh: Float?, val afterMs: Long?, val requireBoth: Boolean) {
        val isValid: Boolean get() = speedKmh != null || afterMs != null

        companion object {
            // Valeurs par défaut de winclose.
            val DEFAULT = Arming(speedKmh = 20f, afterMs = 5 * 60_000L, requireBoth = false)
        }
    }

    enum class Action { NONE, SCHEDULE, CANCEL, CLOSE }

    /** Décision et sa raison, pour les journaux. */
    data class Outcome(val action: Action, val reason: String)

    var isArmed = false
        private set

    /** Raison du dernier armement, pour les journaux. */
    var armReason = ""
        private set

    private var readySinceMs: Long? = null
    /** Vitesse atteinte depuis le début du trajet : retenue pour « les deux ». */
    private var speedReached = false
    private var pendingSinceMs: Long? = null
    private var lastTriggerMs: Long? = null

    val isPending: Boolean get() = pendingSinceMs != null

    /** Passage en READY : nouveau trajet (désarmé) ; une fermeture en attente est annulée. */
    fun onReady(nowMs: Long): Outcome {
        isArmed = false
        speedReached = false
        readySinceMs = nowMs
        if (pendingSinceMs == null) return Outcome(Action.NONE, "nouveau trajet")
        pendingSinceMs = null
        return Outcome(Action.CANCEL, "voiture repassée en READY")
    }

    /** Sortie de READY (sur MG4 : porte conducteur ouverte en P, ou extinction). */
    fun onReadyLost(nowMs: Long, inPark: Boolean?): Outcome {
        readySinceMs = null
        if (pendingSinceMs != null) return Outcome(Action.NONE, "fermeture déjà en attente")
        if (inPark != true) return Outcome(Action.NONE, "levier pas en P (${inPark ?: "illisible"})")
        if (!isArmed) return Outcome(Action.NONE, "pas armé (conditions de roulage non atteintes)")
        lastTriggerMs?.let {
            if (nowMs - it < cooldownMs) return Outcome(Action.NONE, "pause de ${cooldownMs / 1000} s après le précédent")
        }
        isArmed = false
        speedReached = false
        lastTriggerMs = nowMs
        pendingSinceMs = nowMs
        return Outcome(Action.SCHEDULE, "sortie de READY en P après avoir roulé")
    }

    /** Option désactivée pendant l'attente : la fermeture programmée est abandonnée. Vrai s'il y en avait une. */
    fun cancelPending(): Boolean {
        val had = pendingSinceMs != null
        pendingSinceMs = null
        return had
    }

    /** Appelé régulièrement : armement, puis échéance ou annulation d'une fermeture en attente. */
    fun onTick(nowMs: Long, speedKmh: Float?, inPark: Boolean?): Outcome {
        if (!isArmed) updateArming(nowMs, speedKmh)
        val pending = pendingSinceMs ?: return Outcome(Action.NONE, "")
        // Rapport illisible (null) : on ne l'interprète pas comme une sortie de P.
        if (inPark == false) {
            pendingSinceMs = null
            return Outcome(Action.CANCEL, "levier sorti de P")
        }
        if (nowMs - pending >= delayMs) {
            pendingSinceMs = null
            return Outcome(Action.CLOSE, "délai de ${delayMs / 1000} s écoulé")
        }
        return Outcome(Action.NONE, "")
    }

    private fun updateArming(nowMs: Long, speedKmh: Float?) {
        val a = arming
        if (!a.isValid) return
        val speedLimit = a.speedKmh
        if (speedLimit != null && speedKmh != null && speedKmh >= speedLimit) speedReached = true
        val readyMs = readySinceMs?.let { nowMs - it }
        val durationReached = a.afterMs != null && readyMs != null && readyMs >= a.afterMs
        val speedOk = speedLimit != null && speedReached
        val armedNow = if (a.requireBoth && speedLimit != null && a.afterMs != null) speedOk && durationReached
                       else speedOk || durationReached
        if (!armedNow) return
        isArmed = true
        armReason = listOfNotNull(
            if (speedOk) "vitesse ≥ ${speedLimit?.toInt()} km/h" else null,
            if (durationReached) "READY depuis ${(readyMs ?: 0L) / 1000} s" else null,
        ).joinToString(" et ")
    }

    companion object {
        const val COOLDOWN_MS = 60_000L

        // Bornes des réglages (écran) : celles de winclose.
        const val SPEED_MIN_KMH = 5
        const val SPEED_MAX_KMH = 50
        const val SPEED_STEP_KMH = 5
        const val TIME_MIN_MIN = 1
        const val TIME_MAX_MIN = 15
        const val DELAY_MIN_S = 0
        const val DELAY_MAX_S = 30
        const val DELAY_DEFAULT_S = 5

        /**
         * Valeur brute de SENSOR_EPTRDY → READY. 1 = READY, 0 = sorti (valeurs de winclose sur
         * SWI69) ; toute autre valeur (signal invalide, lecture en échec) ne permet pas de conclure.
         */
        fun readyFromRaw(raw: Int?): Boolean? = when (raw) {
            1    -> true
            0    -> false
            else -> null
        }
    }
}
