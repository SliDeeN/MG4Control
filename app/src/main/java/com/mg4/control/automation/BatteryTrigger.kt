package com.mg4.control.automation

/**
 * Décision de l'automatisation « profil selon la batterie » (issue #112). Pure, donc testée.
 *
 * Contrairement à la température, lue une fois au contact, le niveau de batterie BAISSE en
 * roulant : on surveille, et on agit au **franchissement** du seuil. Trois règles :
 *
 *  1. **Une seule action par épisode.** Sans ça, chaque relevé sous le seuil réappliquerait le
 *     profil et écraserait tout ce que le conducteur change ensuite à la main.
 *  2. **Réarmement avec marge.** Le pourcentage remonte seul de quelques dixièmes quand la
 *     batterie se détend après un effort (relevé le 2026-09-20) : sans marge, il oscillerait
 *     autour du seuil et redéclencherait en boucle. Il faut donc une vraie remontée — une
 *     recharge — ou un nouveau démarrage ([rearm]).
 *  3. **Attendre plutôt que perdre.** Quand la sécurité conduite interdit les écritures à la
 *     vitesse du moment ([onSoc] `canAct` faux), l'épisode reste en attente et s'exécute à la
 *     première occasion autorisée — en pratique le prochain arrêt.
 */
class BatteryTrigger(private val rearmMarginPercent: Float = REARM_MARGIN_PERCENT) {

    private var armed = true
    private var pending = false

    /** Seuil franchi, action pas encore faite (bloquée par la sécurité conduite). */
    val isPending: Boolean get() = pending

    /** Nouveau démarrage : un nouvel épisode peut commencer, même si la batterie reste basse. */
    fun rearm() {
        armed = true
        pending = false
    }

    /**
     * Avale un relevé ; rend vrai quand il faut agir MAINTENANT.
     *
     * [soc] null (lecture ratée) ne change rien : on ne conclut pas sur une absence.
     */
    fun onSoc(soc: Float?, threshold: Int, canAct: Boolean): Boolean {
        if (soc == null) return false
        if (armed && soc < threshold) {
            armed = false
            pending = true
        } else if (!armed && soc >= threshold + rearmMarginPercent) {
            armed = true
            pending = false
        }
        if (pending && canAct) {
            pending = false
            return true
        }
        return false
    }

    companion object {
        /** Remontée minimale au-dessus du seuil pour rouvrir un épisode sans redémarrer. */
        const val REARM_MARGIN_PERCENT = 2f
    }
}
