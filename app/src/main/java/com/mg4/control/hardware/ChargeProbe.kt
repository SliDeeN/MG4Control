package com.mg4.control.hardware

import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeDisplay

/**
 * Sonde de la charge (2026-10-16) : de quoi vérifier sur la voiture ce que le module « charge » du
 * combiné suppose.
 *  - le TYPE de borne : doute de l'utilisateur sur le continu. Levé le 2026-10-16 sur borne
 *    rapide : état 10 (et non 1), alternatif = 4, continu = 1 ; la trappe et la validité du continu
 *    restent relevées avec ;
 *  - l'unité du temps restant (minutes ?) et le codage de la cible (1..7 → 40..100 % ?).
 *
 * Peu bavarde à dessein : une ligne quand un signal d'état change (statut, prise, alternatif,
 * continu, cible), et pendant une charge un point toutes les [POINT_MS] avec le temps restant et la
 * puissance — de quoi voir le temps baisser d'une minute par minute, ou pas. Rien hors charge tant
 * que rien ne bouge.
 */
object ChargeProbe {

    private const val TAG = "MG4_CHARGE"
    private const val POINT_MS = 5 * 60_000L

    private var dernier: EnergyReader.ChargeRaw? = null
    private var dernierPointMs = 0L

    /** [kw] : puissance qui entre dans la batterie, si connue. Appelable de plusieurs fils. */
    @Synchronized
    fun observer(r: EnergyReader.ChargeRaw, kw: Float?) {
        val avant = dernier
        val etatChange = avant == null || avant.statut != r.statut || avant.prise != r.prise ||
            avant.ac != r.ac || avant.dc != r.dc || avant.cible != r.cible ||
            avant.trappe != r.trappe || avant.dcInvalide != r.dcInvalide
        val maintenant = SystemClock.elapsedRealtime()
        val enCharge = ChargeDisplay.enCharge(r.statut) == true
        if (etatChange) {
            AppLogger.i(TAG, "état : statut=${r.statut} prise=${r.prise} alternatif(CCU_ONBD)=${r.ac} " +
                "continu(CCU_OFFBD)=${r.dc} (V=${r.dcInvalide}) trappe=${r.trappe} → type ${ChargeDisplay.type(r.ac, r.dc)?.name ?: "aucun"} ; " +
                "cible brute=${r.cible} (${ChargeDisplay.ciblePourcent(r.cible)?.let { "$it %" } ?: "hors codes"}) ; " +
                "restant brut=${r.restant}")
            dernierPointMs = maintenant
        } else if (enCharge && maintenant - dernierPointMs >= POINT_MS) {
            AppLogger.i(TAG, "en charge : restant brut=${r.restant} " +
                "(${ChargeDisplay.minutesRestantes(r.restant)?.let { "$it min ?" } ?: "pas de valeur"}) ; " +
                "puissance=${kw?.let { "%.1f".format(java.util.Locale.US, it) } ?: "?"} kW")
            dernierPointMs = maintenant
        }
        dernier = r
    }
}
