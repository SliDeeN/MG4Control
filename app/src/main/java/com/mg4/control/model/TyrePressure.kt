package com.mg4.control.model

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pression des pneus (2026-11-20) : unités et lecture des codes d'état, sans Android.
 *
 * Relevé SWI133 du 2026-11-20 : la voiture envoie la pression en **kPa** (252 ↔ 2,5 bar au
 * combiné), la température en °C, et un code d'état par pneu. Le sens exact des codes 2 à 6 n'est
 * pas connu : les Réglages véhicule A9 ont des textes (pression basse, haute, fuite, capteur…)
 * qu'aucun code ne relie à un numéro. On suit donc la voix SAIC (`VehicleTyreService`) : 0 ou 1
 * normal, au-dessus alerte, sauf 6 qui ne compte que sur les deux pneus d'un même essieu.
 */
object TyrePressure {

    /** [code] : celui du combiné (`FICM_TYRE_PRESS_UNIT` 0x21407b49) ; seul 0 = bar est relevé. */
    enum class PressureUnit(val code: Int?, val label: String) {
        BAR(0, "bar"), PSI(null, "PSI"), KPA(null, "kPa");

        companion object {
            /** L'unité du combiné si on la connaît, sinon bar. */
            fun fromCluster(code: Int?): PressureUnit = entries.firstOrNull { it.code != null && it.code == code } ?: BAR
            fun fromName(name: String?): PressureUnit? = entries.firstOrNull { it.name == name }
        }
    }

    private const val PSI_PAR_KPA = 0.1450377

    /** Une pression lue en dehors de ces bornes n'en est pas une (valeur de repli, capteur absent). */
    private const val KPA_MAX = 600

    fun valide(kpa: Int?): Int? = kpa?.takeIf { it in 0..KPA_MAX }

    /** bar au dixième comme le combiné, PSI à l'unité, kPa tel quel ; séparateur de l'écran. */
    fun format(kpa: Int, unit: PressureUnit, locale: Locale): String = when (unit) {
        PressureUnit.BAR -> NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1; maximumFractionDigits = 1
        }.format(kpa / 100.0)
        PressureUnit.PSI -> (kpa * PSI_PAR_KPA).roundToInt().toString()
        PressureUnit.KPA -> kpa.toString()
    }

    /** La pression la plus basse des quatre (kPa), valeurs hors bornes écartées ; null si aucune. */
    fun lowest(kpa: List<Int?>): Int? = kpa.mapNotNull { valide(it) }.minOrNull()

    /** La température la plus haute des quatre (°C) ; null si aucune. */
    fun hottest(celsius: List<Int?>): Int? = celsius.filterNotNull().maxOrNull()

    /**
     * Un pneu au moins en alerte d'après la voiture ([alert]), codes dans l'ordre avant gauche, avant
     * droite, arrière gauche, arrière droite ; null si aucun code n'est lu.
     */
    fun anyAlert(codes: List<Int?>): Boolean? {
        if (codes.size != 4 || codes.all { it == null }) return null
        val autre = intArrayOf(1, 0, 3, 2)
        return codes.indices.any { i -> alert(codes[i], codes[autre[i]], pressionLue = true) == Alert.ALERT }
    }

    enum class Alert { NORMAL, ALERT, LONE_SIX, NO_VALUE }

    /**
     * Ce que montre le module « Pneus » de l'écran conducteur (variante A, 2026-11-20) : les quatre
     * pressions mises en forme ([values], « – » sans valeur) et l'état de chacune, dans l'ordre avant
     * gauche, avant droite, arrière gauche, arrière droite.
     */
    data class Display(val values: List<String>, val unit: String, val alerts: List<Alert>,
                       /** Températures prêtes à écrire (« 16 °C », « – °C »), même ordre. */
                       val temps: List<String> = List(4) { "– °C" }) {
        val anyAlert: Boolean get() = alerts.any { it == Alert.ALERT }
        val noValue: Boolean get() = alerts.all { it == Alert.NO_VALUE }
    }

    fun display(kpa: List<Int?>, codes: List<Int?>, unit: PressureUnit, locale: Locale, celsius: List<Int?> = List(4) { null }): Display {
        val autre = intArrayOf(1, 0, 3, 2)
        return Display(
            values = kpa.map { k -> valide(k)?.let { format(it, unit, locale) } ?: "–" },
            unit = unit.label,
            alerts = kpa.indices.map { i -> alert(codes.getOrNull(i), codes.getOrNull(autre[i]), valide(kpa[i]) != null) },
            temps = kpa.indices.map { i -> (celsius.getOrNull(i)?.toString() ?: "–") + " °C" },
        )
    }

    /**
     * Ce que dit le code d'un pneu. [autre] : le code de l'autre pneu du même essieu. Sans pression
     * ni code : pas de valeur.
     */
    fun alert(code: Int?, autre: Int?, pressionLue: Boolean): Alert = when {
        code == null && !pressionLue -> Alert.NO_VALUE
        code == null || code <= 1 -> Alert.NORMAL
        code == 6 && autre != 6 -> Alert.LONE_SIX
        else -> Alert.ALERT
    }
}
