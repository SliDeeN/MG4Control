package com.mg4.control.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bulle d'information (issue #236, 2026-11-10, maquette validée) : au lieu de son titre, une bulle
 * affiche une valeur — celles que lisent les automatisations ([RuleSignal]), plus l'heure — en
 * « valeur », « libellé + valeur » ou « icône + valeur » ; au toucher, son action ou rien. Une
 * valeur oui / non colore la bulle comme une bascule. Ici ce qui se décide sans Android.
 */
object BubbleInfo {

    /** L'heure, en plus des signaux du véhicule. */
    const val TIME = "TIME"

    const val STYLE_VALUE = "value"
    const val STYLE_LABEL = "label"
    const val STYLE_ICON = "icon"

    /** Relecture : toutes les secondes pour ce qui bouge vite, toutes les [SLOW_EVERY] secondes sinon. */
    const val TICK_MS = 1_000L
    const val SLOW_EVERY = 5
    private val RAPIDES = setOf(TIME, RuleSignal.SPEED.name)

    /** Ce qu'affiche une valeur illisible : jamais un chiffre inventé. */
    const val UNKNOWN = "—"

    /**
     * Ordre du choix : les plus utiles d'abord, puis le reste dans l'ordre des signaux. Ce qui
     * n'a pas de sens sur une bulle (firmware, Wi-Fi par son nom…) n'est pas proposé.
     */
    val FIRST = listOf(RuleSignal.SOC.name, RuleSignal.RANGE.name, RuleSignal.OUTSIDE_TEMP.name, TIME,
        RuleSignal.SPEED.name, RuleSignal.HVAC_TEMP.name, RuleSignal.DRIVE_MODE.name, RuleSignal.REGEN.name,
        RuleSignal.PROFILE.name, RuleSignal.VOLUME.name, RuleSignal.CHARGE_LIMIT.name)
    private val EXCLUS = setOf(RuleSignal.FIRMWARE, RuleSignal.WIFI_SSID, RuleSignal.BT_DEVICE, RuleSignal.DATA_TODAY_MB,
        RuleSignal.DATA_MONTH_MB, RuleSignal.SUN_UP,
        // Retirée des automatisations (lecture non fiable) : nulle part ailleurs non plus (2026-11-12).
        RuleSignal.KEY_AWAY)

    /** Les choix, dans l'ordre ; [available] écarte ce que la voiture ne sait pas lire. */
    fun choices(available: (RuleSignal) -> Boolean = { true }): List<String> {
        val reste = RuleSignal.entries.filter { it !in EXCLUS && it.name !in FIRST }.map { it.name }
        return (FIRST + reste).filter { k -> k == TIME || signal(k)?.let(available) == true }
    }

    fun isInfo(b: FloatingButton): Boolean = b.info != null
    fun signal(key: String?): RuleSignal? = RuleSignal.entries.firstOrNull { it.name == key }
    fun style(b: FloatingButton): String = b.infoStyle?.takeIf { it == STYLE_VALUE || it == STYLE_ICON } ?: STYLE_LABEL
    /** Au toucher, l'action (vrai pour toute bulle d'avant la fonction). */
    fun tapRuns(b: FloatingButton): Boolean = b.tapAction != false
    fun fast(key: String?): Boolean = key in RAPIDES

    /** Unités que la liste des automatisations n'écrit pas, mais qu'une bulle doit montrer. */
    fun extraUnit(s: RuleSignal): String = when (s) {
        RuleSignal.HVAC_TEMP -> " °C"
        RuleSignal.BRIGHTNESS, RuleSignal.CHARGE_LIMIT -> " %"
        RuleSignal.DRIVE_MINUTES -> " min"
        else -> ""
    }

    /**
     * Texte d'une valeur lue : oui / non en [on] / [off], nombre entier ou à une décimale suivi de
     * [unit], texte passé par [name] (mode de conduite, profil…), [UNKNOWN] si illisible.
     */
    fun format(value: Any?, unit: String, on: String, off: String, name: (String) -> String = { it },
               locale: Locale = Locale.getDefault()): String = when (value) {
        null -> UNKNOWN
        is Boolean -> if (value) on else off
        // Décimale dans la langue de l'écran (« 21,5 °C ») : c'est un affichage, pas une donnée machine.
        is Number -> value.toDouble().let { n ->
            if (n.isNaN()) UNKNOWN
            else (if (n % 1.0 == 0.0) n.toLong().toString() else String.format(locale, "%.1f", n)) + unit
        }
        is Collection<*> -> value.joinToString(", ") { name(it.toString()) }.ifEmpty { UNKNOWN }
        else -> value.toString().takeIf { it.isNotBlank() }?.let(name) ?: UNKNOWN
    }

    /**
     * Vitesse et autonomie dans l'unité de distance générale (2026-11-10) : la valeur (en miles,
     * arrondie à l'entier) et son unité ; `null` pour les autres informations. En km, rien ne change.
     */
    fun inDistanceUnit(s: RuleSignal, value: Any?, unit: DistanceUnit): Pair<Any?, String>? {
        val n = (value as? Number)?.toFloat()?.takeUnless { it.isNaN() }
        return when (s) {
            RuleSignal.SPEED ->
                (if (unit == DistanceUnit.KM || n == null) value else unit.speedFromKmh(n).roundToInt()) to " ${unit.speedShort}"
            RuleSignal.RANGE ->
                (if (unit == DistanceUnit.KM || n == null) value else unit.fromKm(n).roundToInt()) to " ${unit.short}"
            else -> null
        }
    }

    /** Une valeur oui / non colore la bulle comme une bascule (choix du 2026-11-10) ; sinon rien. */
    fun toggleState(value: Any?): Boolean? = value as? Boolean
}
