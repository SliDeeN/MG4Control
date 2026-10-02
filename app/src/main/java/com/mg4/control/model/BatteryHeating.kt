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
 * ([com.mg4.control.automation.BatteryHeatingAutomation]) l'appelle toutes les quelques secondes
 * pendant READY, et le remet à zéro à chaque trajet.
 */
class BatteryHeatingCountdown {

    enum class Decision { RIEN, DEBUT, ANNULE, COUPER }

    /** Début du décompte en cours (horloge monotone), null sans décompte. */
    private var debutMs: Long? = null
    /** Juste après une coupure, l'état relu peut rester « activé » le temps que le véhicule l'applique. */
    private var graceJusquaMs = Long.MIN_VALUE

    /** Nouveau trajet, ou automatisme désactivé : rien ne survit. Vrai si un décompte tournait. */
    fun reset(): Boolean {
        val tournait = debutMs != null
        debutMs = null
        graceJusquaMs = Long.MIN_VALUE
        return tournait
    }

    /**
     * Un passage, [actif] étant l'état relu (null = illisible : rien ne change). Le décompte part
     * du premier passage où le chauffage est vu activé — au démarrage s'il l'était déjà, sinon au
     * moment où il l'a été — et rend [Decision.COUPER] une fois [dureeMs] écoulée. La durée est
     * relue à chaque passage : la changer en route déplace l'échéance.
     */
    fun tick(nowMs: Long, actif: Boolean?, dureeMs: Long): Decision {
        if (actif == null) return Decision.RIEN
        if (!actif) {
            graceJusquaMs = Long.MIN_VALUE
            if (debutMs == null) return Decision.RIEN
            debutMs = null
            return Decision.ANNULE
        }
        if (nowMs < graceJusquaMs) return Decision.RIEN
        val debut = debutMs ?: run {
            debutMs = nowMs
            return Decision.DEBUT
        }
        if (nowMs - debut < dureeMs) return Decision.RIEN
        debutMs = null
        graceJusquaMs = nowMs + GRACE_MS
        return Decision.COUPER
    }

    /** Échéance du décompte en cours, ou null sans décompte. */
    fun echeanceMs(dureeMs: Long): Long? = debutMs?.let { it + dureeMs }

    companion object {
        /** Après une coupure, un « activé » relu pendant ce délai n'est pas une réactivation. */
        const val GRACE_MS = 15_000L
    }
}
