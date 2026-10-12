package com.mg4.control.automation

/**
 * Ce que l'automatisation climatisation a déjà fait depuis le démarrage de l'application :
 * pris une décision, envoyé une règle, et quand. Le mode de déclenchement
 * ([ClimateAutomationDecision.allowed]) et l'anti-rebond le consultent.
 *
 * Tout est noté à la FIN d'un envoi, et seulement s'il a pu partir (issue #121). Sur les
 * firmwares A9, la climatisation n'est joignable qu'un instant après le reste au démarrage à
 * froid : noté d'avance, un envoi abandonné consommait l'unique occasion des modes « au
 * démarrage seulement » et « une fois par démarrage », et plus rien ne réessayait.
 *
 * En mémoire seulement : repart de zéro au redémarrage de l'application, donc au démarrage de
 * la voiture, pas à un simple retour en READY.
 */
class ClimateAutomationRun {

    /** Une décision a été prise : règle envoyée, seuil non atteint, ou profil prioritaire. */
    @Volatile var evaluated = false
        private set

    /** Une règle a été envoyée à la climatisation. */
    @Volatile var triggered = false
        private set

    private var lastSentMs = 0L
    private var sending = false

    /**
     * Décision prise sans rien envoyer : température lisible mais aucun seuil atteint, ou
     * climatisation laissée au profil actif.
     */
    @Synchronized fun decidedNothing() {
        evaluated = true
    }

    /**
     * Réserve l'envoi d'une règle ; faux si un envoi est déjà en cours. Les commandes de clim sont
     * des bascules : deux envois entrelacés s'annuleraient, et le démarrage du service et la mise
     * du contact se suivent à quelques secondes.
     */
    @Synchronized fun begin(): Boolean {
        if (sending) return false
        sending = true
        return true
    }

    /**
     * Fin de l'envoi réservé par [begin]. [sent] faux — la climatisation n'a pas pu être jointe —
     * ne note rien : ni décision, ni anti-rebond, le prochain évènement réessaiera.
     */
    @Synchronized fun end(sent: Boolean, nowMs: Long) {
        sending = false
        if (!sent) return
        evaluated = true
        triggered = true
        lastSentMs = nowMs
    }

    /** Temps écoulé depuis le dernier envoi s'il date de moins de [windowMs], sinon null. */
    @Synchronized fun debouncedFor(nowMs: Long, windowMs: Long): Long? {
        if (lastSentMs == 0L) return null
        return (nowMs - lastSentMs).takeIf { it < windowMs }
    }

    companion object {
        /**
         * Attend que [ready] réponde vrai : [attempts] essais au plus, espacés de [pauseMs].
         * Bloquant — à appeler hors du fil principal.
         */
        fun awaitReady(
            attempts: Int, pauseMs: Long,
            sleep: (Long) -> Unit = { Thread.sleep(it) },
            ready: () -> Boolean
        ): Boolean {
            repeat(attempts) { i ->
                if (ready()) return true
                if (i < attempts - 1) sleep(pauseMs)
            }
            return false
        }
    }
}
