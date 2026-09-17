package com.mg4.control.model

/**
 * Fermeture automatique des vitres en quittant la voiture : quand déclencher.
 *
 * Logique reprise du projet winclose (validée par ses utilisateurs sur SWI69) :
 * - **armement** : après avoir roulé — vitesse ≥ [armSpeedKmh] ou voiture en marche depuis
 *   [armAfterMs] ; un nouveau démarrage désarme (nouveau trajet) ;
 * - **déclenchement** : ouverture de la porte conducteur, levier en P, armé, hors pause de
 *   [cooldownMs] depuis le précédent → fermeture programmée dans [delayMs] ;
 * - **annulation** : porte rouverte pendant le délai (l'utilisateur revient) ou levier sorti de P.
 *
 * L'extinction ne désarme pas : sur un VE, le conducteur éteint AVANT d'ouvrir la porte.
 * Contrairement à winclose, elle n'annule pas non plus une fermeture en attente : le but est
 * justement de fermer en quittant la voiture éteinte.
 *
 * Aucune dépendance Android : les horloges et lectures véhicule sont passées en paramètre.
 */
class WindowAutoCloseTrigger(
    private val armSpeedKmh: Float = ARM_SPEED_KMH,
    private val armAfterMs: Long = ARM_AFTER_MS,
    val delayMs: Long = DELAY_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {

    enum class Action { NONE, SCHEDULE, CANCEL, CLOSE }

    /** Décision et sa raison, pour les journaux. */
    data class Outcome(val action: Action, val reason: String)

    var isArmed = false
        private set
    private var runSinceMs: Long? = null
    private var pendingSinceMs: Long? = null
    private var lastTriggerMs: Long? = null

    val isPending: Boolean get() = pendingSinceMs != null

    /** Démarrage (RUN) : nouveau trajet, il faudra rouler pour réarmer. */
    fun onIgnitionRun(nowMs: Long) {
        isArmed = false
        runSinceMs = nowMs
        pendingSinceMs = null
    }

    /** Option désactivée pendant l'attente : la fermeture programmée est abandonnée. Vrai s'il y en avait une. */
    fun cancelPending(): Boolean {
        val had = pendingSinceMs != null
        pendingSinceMs = null
        return had
    }

    /** Extinction : la durée de marche s'arrête, l'armement est conservé. */
    fun onIgnitionOff() {
        runSinceMs = null
    }

    /** Appelé régulièrement : armement, puis échéance ou annulation d'une fermeture en attente. */
    fun onTick(nowMs: Long, speedKmh: Float?, inPark: Boolean?): Outcome {
        if (!isArmed) {
            val since = runSinceMs
            when {
                speedKmh != null && speedKmh >= armSpeedKmh -> arm("vitesse $speedKmh km/h")
                since != null && nowMs - since >= armAfterMs -> arm("en marche depuis ${(nowMs - since) / 1000} s")
            }
        }
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

    /** Front d'ouverture de la porte conducteur. */
    fun onDriverDoorOpened(nowMs: Long, inPark: Boolean?): Outcome {
        if (pendingSinceMs != null) {
            pendingSinceMs = null
            return Outcome(Action.CANCEL, "porte rouverte pendant le délai")
        }
        if (inPark != true) return Outcome(Action.NONE, "levier pas en P (${inPark ?: "illisible"})")
        if (!isArmed) return Outcome(Action.NONE, "pas armé (pas encore roulé)")
        lastTriggerMs?.let {
            if (nowMs - it < cooldownMs) return Outcome(Action.NONE, "pause de ${cooldownMs / 1000} s après le précédent")
        }
        isArmed = false
        lastTriggerMs = nowMs
        pendingSinceMs = nowMs
        // Toujours en marche : le réarmement par la durée repart de maintenant, pas du démarrage.
        if (runSinceMs != null) runSinceMs = nowMs
        return Outcome(Action.SCHEDULE, "porte conducteur ouverte en P après avoir roulé")
    }

    private var lastArmReason = ""

    /** Raison du dernier armement, pour l'affichage d'état. */
    val armReason: String get() = lastArmReason

    private fun arm(reason: String) {
        isArmed = true
        lastArmReason = reason
    }

    companion object {
        // Valeurs par défaut de winclose.
        const val ARM_SPEED_KMH = 20f
        const val ARM_AFTER_MS = 5 * 60_000L
        const val DELAY_MS = 5_000L
        const val COOLDOWN_MS = 60_000L
    }
}
