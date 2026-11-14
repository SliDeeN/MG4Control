package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ClusterTab

/**
 * Signal « image prête » du combiné (issue #245, 2026-11-14).
 *
 * Le combiné ne montre l'image de l'écran central (donc notre module) sur Musique ou Navigation
 * qu'après ce signal. L'appli SAIC du combiné (`clusterprojection`, dump SWI68,
 * `McuDataRepository.sendFicmReady`) l'envoie :
 * - 500 ms après chaque changement d'onglet ;
 * - une seule fois au démarrage, quand sa page s'affiche, avec l'onglet mémorisé et sa liaison à
 *   la voiture. Si la page s'affiche AVANT que cette liaison soit établie, rien ne part (liaison
 *   absente, onglet encore à 0) ; la liaison arrivée, elle relit l'onglet mais ne renvoie pas le
 *   signal → page d'origine sur le combiné jusqu'au premier changement d'onglet.
 *
 * Valeurs (`FicmType`) : 0x21407b01 média, 0x21407b16 navigation ; 1 = prête, 0 = pas prête.
 * Pour un onglet, l'appli SAIC écrit les DEUX à 1, celui de l'onglet en dernier : on fait pareil.
 *
 * ⚠️ Relecture peu fiable : sur SWI133 (2026-10-15), navigation lue à 0 alors que le combiné
 * montrait l'image. Les valeurs lues ne servent qu'au journal.
 */
object ClusterReadySignal {

    private const val TAG = "MG4_CLUSTER"

    const val PROP_MEDIA_READY = 0x21407b01
    const val PROP_NAVI_READY = 0x21407b16
    const val READY = 1

    /** Zone 0 d'abord, celle du service du combiné ; la zone globale des propriétés vendeur sinon. */
    private val ZONES = intArrayOf(0, 0x1000000)

    /**
     * Signaux à écrire pour [tab], dans l'ordre de l'appli SAIC ; `null` hors Musique / Navigation
     * (onglets dessinés par le combiné lui-même).
     */
    fun signauxPour(tab: Int?): List<Int>? = when (tab) {
        ClusterTab.MEDIA.value -> listOf(PROP_NAVI_READY, PROP_MEDIA_READY)
        ClusterTab.NAVIGATION.value -> listOf(PROP_MEDIA_READY, PROP_NAVI_READY)
        else -> null
    }

    /** Pour le journal : « média=1, navigation=0 » (« ? » si illisible). Hors du fil principal. */
    fun releve(): String = "média=${lire(PROP_MEDIA_READY) ?: "?"}, navigation=${lire(PROP_NAVI_READY) ?: "?"}"

    /**
     * Envoie « image prête » pour [tab], comme l'appli SAIC à un changement d'onglet. Vrai si
     * chaque écriture a été acceptée. Hors du fil principal : appels binder.
     *
     * Pas de [VehicleWriteGate] : simple signal d'affichage du combiné, que l'appli de la voiture
     * envoie elle-même à chaque changement d'onglet, en roulant compris.
     */
    fun envoyer(tab: Int, origine: String): Boolean {
        val signaux = signauxPour(tab) ?: return false
        val cpm = MG4Hardware.carPropertyManager() ?: run {
            AppLogger.w(TAG, "$origine : « image prête » non envoyé, liaison véhicule absente")
            return false
        }
        return signaux.all { id -> ecrire(cpm, id, origine) }
    }

    private fun ecrire(cpm: Any, id: Int, origine: String): Boolean {
        for (zone in ZONES) {
            try {
                cpm.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                    .invoke(cpm, id, zone, READY)
                AppLogger.i(TAG, "$origine : 0x${Integer.toHexString(id)} ← $READY (zone 0x${Integer.toHexString(zone)}) accepté")
                return true
            } catch (e: Exception) {
                val cause = e.cause ?: e
                AppLogger.w(TAG, "$origine : 0x${Integer.toHexString(id)} ← $READY (zone 0x${Integer.toHexString(zone)}) " +
                    "refusé — ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
        return false
    }

    private fun lire(id: Int): Int? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        for (zone in ZONES) {
            runCatching {
                cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                    .invoke(cpm, id, zone) as? Int
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
