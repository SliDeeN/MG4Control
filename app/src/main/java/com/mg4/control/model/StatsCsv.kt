package com.mg4.control.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export des Statistiques (issue #206) : un fichier CSV pour les trajets, un pour les recharges,
 * à ouvrir dans un tableur.
 *
 * Fait pour Excel autant que pour les autres : séparateur « ; » (celui qu'Excel attend dès que la
 * virgule sert de séparateur décimal), BOM UTF-8 pour les accents, dates `aaaa-mm-jj hh:mm` en heure
 * locale, que tous les tableurs reconnaissent. Virgule décimale sauf en anglais ([virgule]).
 * En-têtes en anglais, avec l'unité : des noms de colonne stables, quelle que soit la langue.
 *
 * Une case vide = valeur inconnue (jamais 0, qui serait une mesure). Ordre chronologique.
 */
object StatsCsv {

    const val SEP = ";"
    const val BOM = "﻿"
    private const val FIN = "\r\n"

    val TRIP_HEADERS = listOf(
        "start", "end", "duration_min", "distance_km", "odometer_km", "net_energy_kwh",
        "gross_energy_kwh", "regen_kwh", "climate_kwh", "accessories_kwh", "consumption_kwh_100km",
        "average_speed_kmh", "soc_start_pct", "soc_end_pct", "outside_temp_c", "energy_source",
    )

    val CHARGE_HEADERS = listOf(
        "start", "end", "duration_min", "type", "soc_start_pct", "soc_end_pct", "energy_kwh",
        "energy_source", "average_power_kw", "price_per_kwh", "cost", "currency", "outside_temp_c",
        "times", "reconstructed",
    )

    fun trips(trips: List<Trip>, virgule: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val date = format(zone)
        val lignes = trips.sortedBy { it.startMs }.map { t ->
            listOf(
                date.format(Date(t.startMs)), date.format(Date(t.endMs)),
                minutes(t.durationMs),
                nombre(t.distance, 1, virgule), t.distanceKm.toString(),
                if (t.energyKnown) nombre(t.netEnergyKwh, 1, virgule) else "",
                if (t.energyKnown) nombre(t.energyKwh, 1, virgule) else "",
                nombre(t.regenKwh, 1, virgule), nombre(t.climateKwh, 1, virgule),
                nombre(t.accessoriesKwh, 1, virgule), nombre(t.consumptionPer100, 1, virgule),
                nombre(t.averageSpeedKmh, 0, virgule),
                nombre(t.socStart, 1, virgule), nombre(t.socEnd, 1, virgule),
                nombre(t.outsideTempC, 1, virgule),
                if (t.energyIntegrated) "integrated" else "vehicle",
            )
        }
        return document(TRIP_HEADERS, lignes)
    }

    fun charges(
        charges: List<ChargeSession>, settings: StatsSettings, virgule: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val date = format(zone)
        val lignes = charges.sortedBy { it.displayStartMs }.map { c ->
            val prix = c.tariffOverride ?: settings.priceFor(c.type)
            listOf(
                date.format(Date(c.displayStartMs)), date.format(Date(c.displayEndMs)),
                // Charge reconstituée sans horaires saisis : les dates encadrent la charge sans la
                // mesurer, une durée serait fausse.
                if (c.reconstructed && !c.timesKnown) "" else minutes(c.durationMs),
                c.type?.name ?: "",
                nombre(c.socStart, 1, virgule), nombre(c.socEnd, 1, virgule),
                nombre(c.energyKwh, 2, virgule),
                when (c.energySource) {
                    ChargeEnergySource.MEASURED -> "measured"
                    ChargeEnergySource.MIXED -> "mixed"
                    ChargeEnergySource.LEARNED -> "learned"
                    ChargeEnergySource.PERCENT, null -> "percent"
                },
                nombre(c.powerKw, 1, virgule),
                nombre(prix, 3, virgule), nombre(c.cost(settings), 2, virgule), settings.currency,
                nombre(c.outsideTempC, 1, virgule),
                if (c.timesKnown) "entered" else "recorded",
                if (c.reconstructed) "yes" else "no",
            )
        }
        return document(CHARGE_HEADERS, lignes)
    }

    /** Nom de fichier daté : `mg4control_trips_2026-11-03.csv`. */
    fun fileName(kind: String, ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "mg4control_${kind}_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(ms)) + ".csv"

    private fun document(entetes: List<String>, lignes: List<List<String>>): String = buildString {
        append(BOM)
        append(entetes.joinToString(SEP) { cellule(it) }).append(FIN)
        lignes.forEach { append(it.joinToString(SEP) { c -> cellule(c) }).append(FIN) }
    }

    private fun format(zone: TimeZone) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }

    private fun minutes(ms: Long): String = ((ms + 30_000L) / 60_000L).toString()

    /** Nombre à [decimales] chiffres, virgule décimale si [virgule] ; vide si inconnu ou non fini. */
    fun nombre(v: Float?, decimales: Int, virgule: Boolean): String {
        if (v == null || !v.isFinite()) return ""
        val s = String.format(Locale.US, "%.${decimales}f", v)
        return if (virgule) s.replace('.', ',') else s
    }

    /** Une case entre guillemets seulement si elle contient le séparateur, un guillemet ou un retour. */
    fun cellule(s: String): String =
        if (s.contains(SEP) || s.contains('"') || s.contains('\n') || s.contains('\r'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
