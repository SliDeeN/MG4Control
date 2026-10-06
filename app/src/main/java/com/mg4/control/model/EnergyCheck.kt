package com.mg4.control.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pour un trajet, l'énergie obtenue par trois voies : l'intégration de la puissance batterie
 * ([PowerIntegrator]), les compteurs du véhicule, et le pourcentage de batterie.
 *
 * Sert à valider l'intégration là où les compteurs fonctionnent (SWI133, SWI132) avant de s'y fier
 * là où ils restent à zéro (SWI68, issue #117). C'est le **net** qui se compare : voir
 * [PowerIntegrator] pour ce qui distingue les répartitions brut / récupération.
 */
class EnergyCheck(
    private val trip: Trip,
    private val integrated: PowerIntegrator.Result,
    private val capacityKwh: Float,
) {
    /** Net des compteurs, sans l'arrondi ni le plancher de [Trip.netEnergyKwh] : on compare des mesures. */
    val counterNetKwh: Float get() = trip.energyKwh - (trip.regenKwh ?: 0f)

    /**
     * Net intégré moins net des compteurs ; `null` si rien n'a pu être intégré — ou si les
     * compteurs sont muets : l'énergie du trajet est alors l'énergie intégrée elle-même, et la
     * comparer à elle-même donnerait un écart nul qui ne prouverait rien.
     */
    val deltaKwh: Float?
        get() = if (integrated.samples == 0 || trip.energyIntegrated) null
        else integrated.netKwh - counterNetKwh

    /** Énergie déduite de la baisse du pourcentage de batterie — grossière sur un trajet court. */
    val socKwh: Float?
        get() {
            val debut = trip.socStart ?: return null
            val fin = trip.socEnd ?: return null
            return (debut - fin) / 100f * capacityKwh
        }

    /** Part de la durée du trajet réellement intégrée. */
    val coveragePercent: Int
        get() = if (trip.durationMs <= 0L) 0
        else (integrated.coveredMs * 100.0 / trip.durationMs).roundToInt().coerceIn(0, 100)

    /** Une ligne lisible telle quelle dans le rapport de diagnostic. */
    fun line(): String {
        val quand = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(trip.endMs))
        val minutes = (trip.durationMs / 60_000.0).roundToInt()
        val mesure = if (integrated.samples == 0) "intégré : aucune mesure" else
            "intégré : brut ${n(integrated.consumedKwh)} − récup ${n(integrated.regenKwh)} = " +
                "net ${n(integrated.netKwh)} kWh (${integrated.samples} relevés, $coveragePercent % du trajet, " +
                "${integrated.gaps} trou(s), ${integrated.missed} illisible(s))"
        // Compteurs muets : restent le pourcentage de batterie et le compteur d'origine, au kWh
        // entier, pour juger la mesure intégrée.
        val compteurs = if (trip.energyIntegrated)
            "compteurs : muets | climatisation et autres : " +
                (trip.auxiliaryKwh?.let { "${it.roundToInt()} kWh (compteur d'origine, au kWh entier)" } ?: "?")
        else "compteurs : brut ${n(trip.energyKwh)} − récup ${trip.regenKwh?.let(::n) ?: "?"} = " +
            "net ${n(counterNetKwh)} kWh"
        val ecart = if (trip.energyIntegrated) "écart sans objet"
            else deltaKwh?.let { "écart ${n(it)} kWh" } ?: "écart ?"
        val batterie = socKwh?.let {
            "batterie ${pourcent(trip.socStart)} → ${pourcent(trip.socEnd)} % ≈ ${n(it)} kWh"
        } ?: "batterie ?"
        return "$quand · ${trip.distance} km · $minutes min | $mesure | $compteurs | $ecart | $batterie"
    }

    // Locale.US : un point décimal quelle que soit la langue du boîtier, la ligne est relue par des outils.
    private fun n(v: Float) = String.format(Locale.US, "%.2f", v)

    /** Au dixième, la finesse du véhicule : un flottant écrit tel quel donnait « 78.200005 ». */
    private fun pourcent(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "?"
}
