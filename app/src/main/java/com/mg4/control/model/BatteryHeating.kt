package com.mg4.control.model

/**
 * Chauffage intelligent de la batterie (`DRVNG_PTC_HEAT`) : encodage de la consigne et lecture de
 * l'état. Pur, pour être testé hors du véhicule.
 */
object BatteryHeating {

    /** Consigne, identique sur les 6 firmwares : vraie consigne, pas une bascule. */
    const val SET_ON = 1
    const val SET_OFF = 2

    /**
     * État lu ; null = illisible.
     *
     * ⚠️ Les écrans d'origine se contredisent. L'ancienne plateforme lit 0 = activé, 1 = désactivé :
     * constantes `DRIVING_BATTERY_GET_ON = 0` des Réglages SWI68/165, et SystemUI SWI133/165 qui
     * propose l'activation quand l'état vaut 1. Les Réglages A9 cochent l'option quand il vaut 1.
     * On suit chaque écran d'origine ; la sonde du Diagnostic (MG4_BATHEAT) doit trancher.
     */
    fun stateFromRaw(raw: Int, a9: Boolean): Boolean? = if (a9) {
        when (raw) { 1 -> true; 0, 2 -> false; else -> null }
    } else {
        when (raw) { 0 -> true; 1, 2 -> false; else -> null }
    }
}

/**
 * Décompte de la coupure automatique du chauffage de la batterie, sans Android : l'état relu à
 * chaque passage décide de tout. Le moteur
 * ([com.mg4.control.automation.BatteryHeatingAutomation]) l'appelle toutes les quelques secondes,
 * en READY ou non, et lui signale début et fin de trajet.
 *
 * Le décompte part de l'**activation** — passage vu de « désactivé » à « activé », en roulant ou à
 * l'arrêt (décision du 2026-10-02 : « dès l'activation du chauffage, pas au début du trajet ») —,
 * ou du **démarrage** si le chauffage l'était déjà. Il ne survit pas à la fin du trajet.
 */
class BatteryHeatingCountdown {

    enum class Decision { RIEN, DEBUT, ANNULE, COUPER }

    /** Début du décompte en cours (horloge monotone), null sans décompte. */
    private var debutMs: Long? = null
    /** Juste après une coupure, l'état relu peut rester « activé » le temps que le véhicule l'applique. */
    private var graceJusquaMs = Long.MIN_VALUE
    /** Dernier état relu ; null = rien de lu depuis la fin du trajet. */
    private var dernierEtat: Boolean? = null
    /**
     * Un chauffage vu activé lancera le décompte même sans activation observée : début de trajet,
     * automatisme (ré)activé, ou coupure toujours pas reflétée à l'issue de la grâce.
     */
    private var demarrerSiActif = false

    /** Début de trajet (READY) : un chauffage déjà activé lance son décompte ; un décompte en cours (activé juste avant) continue. */
    fun startTrip() {
        demarrerSiActif = true
    }

    /**
     * Fin de trajet : le décompte ne survit pas, et à l'arrêt seule une vraie activation en relance
     * un — un chauffage laissé activé attend le démarrage suivant. Vrai si un décompte tournait.
     */
    fun endTrip(): Boolean {
        val tournait = debutMs != null
        debutMs = null
        graceJusquaMs = Long.MIN_VALUE
        dernierEtat = null
        demarrerSiActif = false
        return tournait
    }

    /** Automatisme désactivé : plus de décompte ; réactivé, un chauffage activé en relance un aussitôt. Vrai si un décompte tournait. */
    fun reset(): Boolean {
        val tournait = debutMs != null
        debutMs = null
        graceJusquaMs = Long.MIN_VALUE
        demarrerSiActif = true
        return tournait
    }

    /**
     * Un passage, [actif] étant l'état relu (null = illisible : rien ne change). Rend
     * [Decision.COUPER] une fois [dureeMs] écoulée depuis le début du décompte. La durée est relue
     * à chaque passage : la changer en route déplace l'échéance.
     */
    fun tick(nowMs: Long, actif: Boolean?, dureeMs: Long): Decision {
        if (actif == null) return Decision.RIEN
        val avant = dernierEtat
        dernierEtat = actif
        if (!actif) {
            graceJusquaMs = Long.MIN_VALUE
            demarrerSiActif = false   // la prochaine activation sera vue comme telle
            if (debutMs == null) return Decision.RIEN
            debutMs = null
            return Decision.ANNULE
        }
        if (nowMs < graceJusquaMs) return Decision.RIEN
        val debut = debutMs
        if (debut == null) {
            if (avant != false && !demarrerSiActif) return Decision.RIEN
            demarrerSiActif = false
            debutMs = nowMs
            return Decision.DEBUT
        }
        if (nowMs - debut < dureeMs) return Decision.RIEN
        debutMs = null
        graceJusquaMs = nowMs + GRACE_MS
        // Toujours activé après la grâce : coupure refusée (ou réactivée aussitôt) — nouveau décompte.
        demarrerSiActif = true
        return Decision.COUPER
    }

    /** Échéance du décompte en cours, ou null sans décompte. */
    fun echeanceMs(dureeMs: Long): Long? = debutMs?.let { it + dureeMs }

    companion object {
        /** Après une coupure, un « activé » relu pendant ce délai n'est pas une réactivation. */
        const val GRACE_MS = 15_000L
    }
}
