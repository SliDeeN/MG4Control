package com.mg4.control.shortcut

/**
 * État d'une bascule de raccourci (One Pedal, économie d'énergie, alertes…) : dit quel état
 * inverser à chaque appui.
 *
 * Avant, seule la dernière consigne comptait, supposée « inactif » au démarrage : un réglage
 * activé autrement — par le profil appliqué au démarrage, l'écran d'origine, le Dashboard — et le
 * premier appui le réactivait au lieu de le couper (issue #114).
 *
 * L'état vient maintenant du véhicule, avec une réserve : selon le firmware, une lecture
 * « inactif » peut aussi vouloir dire « illisible », et rien ne garantit qu'une lecture suive nos
 * consignes partout. D'où trois règles, qui ne font jamais pire que l'ancienne alternance :
 *  - une lecture « actif » fait foi ;
 *  - une lecture « inactif » ne fait foi qu'après qu'une activation a été confirmée par la
 *    relecture ; sinon c'est la dernière consigne qui compte ;
 *  - une relecture qui contredit la consigne retire cette confiance : la lecture ne suit pas, on
 *    retombe sur l'alternance.
 *
 * Aucun accès au véhicule ici : le service lit, commande et relit, cette classe décide.
 */
class ToggleTracker {

    private enum class Trust { UNKNOWN, RELIABLE, UNRELIABLE }

    private var commanded: Boolean? = null
    private var commandedAtMs = 0L
    private var token = 0
    private var trust = Trust.UNKNOWN

    /**
     * État à inverser. [read] : état lu sur le véhicule, `null` s'il est illisible.
     *
     * Pendant [SETTLE_MS] après une consigne, c'est elle qui compte : le véhicule n'a pas forcément
     * suivi, et deux appuis rapprochés doivent s'annuler comme ils l'ont toujours fait.
     */
    fun current(read: Boolean?, nowMs: Long): Boolean {
        val last = commanded
        if (last != null && nowMs - commandedAtMs < SETTLE_MS) return last
        if (read == null) return last ?: false
        return when (trust) {
            Trust.RELIABLE   -> read
            Trust.UNRELIABLE -> last ?: read
            Trust.UNKNOWN    -> read || (last ?: false)
        }
    }

    /** Consigne envoyée au véhicule. Rend le jeton à présenter à [onReadBack]. */
    fun onCommand(state: Boolean, nowMs: Long): Int {
        commanded = state
        commandedAtMs = nowMs
        return ++token
    }

    /**
     * Relecture faite [SETTLE_MS] après la consigne [token]. Ignorée si une consigne plus récente
     * est partie entre-temps : elle ne dirait rien de celle en cours.
     */
    fun onReadBack(token: Int, read: Boolean?) {
        if (token != this.token || read == null) return
        when {
            read != commanded -> trust = Trust.UNRELIABLE
            read              -> trust = Trust.RELIABLE
        }
    }

    companion object {
        /** Temps laissé au véhicule pour refléter une consigne avant de le relire. */
        const val SETTLE_MS = 2_000L
    }
}
