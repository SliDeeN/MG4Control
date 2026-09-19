package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.hardware.StatsCollector
import com.mg4.control.model.ChargeSession
import com.mg4.control.model.ChargeType
import com.mg4.control.model.StatsSettings
import com.mg4.control.model.StatsSummary
import com.mg4.control.model.Trip
import com.mg4.control.stats.StatsStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Onglet Statistiques : trois sous-onglets dans le même écran (Général, Trajets, Recharge).
 *
 * Les tuiles et les lignes sont construites ici plutôt qu'en XML : leur nombre dépend de ce que la
 * voiture a réellement rendu, et une valeur absente ne doit pas laisser une tuile vide derrière
 * elle. Le tiret d'une valeur manquante est volontaire — mieux vaut avouer un trou qu'afficher un
 * zéro qui passerait pour une mesure.
 */
class StatsFragment : Fragment() {

    private companion object {
        const val PAGE_GENERAL = 0
        const val PAGE_TRIPS = 1
        const val PAGE_CHARGES = 2
        /** Période courte du résumé, en jours. L'autre choix est « tout l'historique ». */
        const val SHORT_PERIOD_DAYS = 30
    }

    private lateinit var store: StatsStore
    private var page = PAGE_GENERAL
    private var shortPeriod = true
    /** Ligne dépliée d'une liste, repérée par l'horodatage de son enregistrement. */
    private var expanded: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = StatsStore(requireContext())

        view.findViewById<Switch>(R.id.switch_stats_enabled).apply {
            isChecked = store.settings().enabled
            setOnCheckedChangeListener { sw, on ->
                StatsCollector.setEnabled(sw.context, on)
                render()
            }
        }
        listOf(
            R.id.btn_stats_cat_general to PAGE_GENERAL,
            R.id.btn_stats_cat_trips to PAGE_TRIPS,
            R.id.btn_stats_cat_charges to PAGE_CHARGES,
        ).forEach { (id, index) ->
            view.findViewById<MaterialButton>(id).setOnClickListener { page = index; render() }
        }
        view.findViewById<MaterialButton>(R.id.btn_stats_clear).setOnClickListener { confirmClear() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // Un trajet a pu se terminer pendant qu'on regardait un autre écran.
        if (view != null) render()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rendu
    // ═════════════════════════════════════════════════════════════════════════

    private fun render() {
        val v = view ?: return
        val ctx = v.context
        val settings = store.settings()
        val history = store.history()

        // Rail + pages
        listOf(
            Triple(R.id.btn_stats_cat_general, R.id.page_stats_general, PAGE_GENERAL),
            Triple(R.id.btn_stats_cat_trips, R.id.page_stats_trips, PAGE_TRIPS),
            Triple(R.id.btn_stats_cat_charges, R.id.page_stats_charges, PAGE_CHARGES),
        ).forEach { (btnId, pageId, index) ->
            val on = index == page
            v.findViewById<MaterialButton>(btnId).apply {
                backgroundTintList = ColorStateList.valueOf(
                    ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
                setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
                strokeColor = ColorStateList.valueOf(
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
            }
            v.findViewById<View>(pageId).visibility = if (on) View.VISIBLE else View.GONE
        }
        v.findViewById<View>(R.id.stats_general_config).visibility =
            if (settings.enabled) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.stats_disabled_note).visibility =
            if (settings.enabled) View.GONE else View.VISIBLE

        val trips = filtered(history.trips.sortedByDescending { it.endMs }) { it.endMs }
        val charges = filtered(history.charges.sortedByDescending { it.endMs }) { it.endMs }
        val summary = StatsSummary.of(trips, charges, settings)

        renderGeneral(v, settings, summary, history)
        renderTrips(v, settings, summary, trips)
        renderCharges(v, settings, summary, charges)
    }

    private fun <T> filtered(items: List<T>, stamp: (T) -> Long): List<T> {
        if (!shortPeriod) return items
        val limite = System.currentTimeMillis() - SHORT_PERIOD_DAYS * 86_400_000L
        return items.filter { stamp(it) >= limite }
    }

    private fun renderGeneral(
        v: View, s: StatsSettings, sum: StatsSummary, history: StatsStore.History
    ) {
        if (!s.enabled) return
        val ctx = v.context

        val tiles = v.findViewById<LinearLayout>(R.id.stats_summary_tiles)
        tiles.removeAllViews()
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_distance) to km(sum.distanceKm),
            getString(R.string.stats_tile_consumption) to
                (sum.consumptionPer100?.let { fmt(it) + " " + getString(R.string.stats_unit_per100) } ?: "—"),
            getString(R.string.stats_tile_speed) to
                (sum.averageSpeedKmh?.let { "${it.toInt()} km/h" } ?: "—"),
            getString(R.string.stats_tile_energy) to kwh(sum.energyKwh),
            getString(R.string.stats_tile_regen) to kwh(sum.regenKwh),
            getString(R.string.stats_tile_charged) to kwh(sum.chargedKwh),
            getString(R.string.stats_tile_cost) to money(sum.drivingCost, s),
            getString(R.string.stats_tile_cost_per100) to money(sum.costPer100, s),
        ))

        val prices = v.findViewById<LinearLayout>(R.id.stats_price_rows)
        prices.removeAllViews()
        prices.addView(textRow(ctx, getString(R.string.stats_currency), s.currency) { saisi ->
            store.saveSettings(store.settings().copy(currency = saisi.take(3).ifBlank { "€" }))
        })
        prices.addView(numberRow(ctx, getString(R.string.stats_price_ac), s.priceAc, 3) { value ->
            store.saveSettings(store.settings().copy(priceAc = StatsSettings.clampPrice(value)))
        })
        prices.addView(numberRow(ctx, getString(R.string.stats_price_dc), s.priceDc, 3) { value ->
            store.saveSettings(store.settings().copy(priceDc = StatsSettings.clampPrice(value)))
        })
        prices.addView(numberRow(ctx, getString(R.string.stats_capacity), s.capacityKwh, 1) { value ->
            store.saveSettings(store.settings().copy(capacityKwh = StatsSettings.clampCapacity(value)))
        })

        val retention = v.findViewById<LinearLayout>(R.id.stats_retention_row)
        retention.removeAllViews()
        StatsSettings.Retention.entries.forEach { r ->
            retention.addView(choice(ctx, retentionLabel(r), r == s.retention) {
                store.saveSettings(store.settings().copy(retention = r))
                render()
            })
        }

        v.findViewById<TextView>(R.id.stats_history_size).text = getString(
            R.string.stats_history_size,
            history.trips.size, history.charges.size, store.sizeBytes() / 1024
        )
    }

    private fun renderTrips(v: View, s: StatsSettings, sum: StatsSummary, trips: List<Trip>) {
        val ctx = v.context
        val tiles = v.findViewById<LinearLayout>(R.id.stats_trips_tiles)
        tiles.removeAllViews()
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_trips) to sum.tripCount.toString(),
            getString(R.string.stats_tile_distance) to km(sum.distanceKm),
            getString(R.string.stats_tile_longest) to km(sum.longestTripKm),
            getString(R.string.stats_tile_cost) to money(sum.drivingCost, s),
        ))

        val list = v.findViewById<LinearLayout>(R.id.stats_trips_list)
        list.removeAllViews()
        if (trips.isEmpty()) {
            list.addView(emptyNote(ctx, R.string.stats_no_trip))
            return
        }
        trips.forEach { trip ->
            list.addView(row(ctx, when1 = dateLine(trip.startMs, trip.endMs),
                when2 = "${duration(trip.durationMs)} · ${km(trip.distanceKm)}",
                value1 = trip.consumptionPer100?.let { fmt(it) + " " + getString(R.string.stats_unit_per100) } ?: "—",
                value2 = listOfNotNull(
                    trip.averageSpeedKmh?.let { "${it.toInt()} km/h" },
                    kwh(trip.energyKwh),
                ).joinToString(" · "),
                onClick = { expanded = if (expanded == trip.startMs) null else trip.startMs; render() }))
            if (expanded == trip.startMs) list.addView(tripDetail(ctx, trip))
        }
    }

    private fun renderCharges(
        v: View, s: StatsSettings, sum: StatsSummary, charges: List<ChargeSession>
    ) {
        val ctx = v.context
        val tiles = v.findViewById<LinearLayout>(R.id.stats_charges_tiles)
        tiles.removeAllViews()
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_charged) to kwh(sum.chargedKwh),
            getString(R.string.stats_tile_cost_total) to money(sum.chargeCost, s),
            getString(R.string.stats_tile_avg_price) to
                (sum.averagePricePerKwh?.let { "${fmt3(it)} ${s.currency}" } ?: "—"),
            getString(R.string.stats_tile_sessions) to "${sum.chargeCount} · ${sum.acCount} AC / ${sum.dcCount} DC",
        ))

        val list = v.findViewById<LinearLayout>(R.id.stats_charges_list)
        list.removeAllViews()
        if (charges.isEmpty()) {
            list.addView(emptyNote(ctx, R.string.stats_no_charge))
            return
        }
        charges.forEach { session ->
            val type = when (session.type) {
                ChargeType.DC -> getString(R.string.stats_charge_dc)
                ChargeType.AC -> getString(R.string.stats_charge_ac)
                null          -> getString(R.string.stats_charge_unknown)
            }
            val corrige = if (session.tariffOverride != null)
                " · " + getString(R.string.stats_tariff_fixed) else ""
            list.addView(row(ctx,
                when1 = dateLine(session.startMs, session.endMs) + corrige,
                when2 = "$type · ${duration(session.durationMs)}",
                value1 = session.energyKwh?.let { "+ ${kwh(it)}" } ?: "—",
                value2 = listOfNotNull(
                    session.powerKw?.let { fmt(it) + " kW" },
                    soc(session.socStart, session.socEnd),
                    money(session.cost(s), s).takeIf { it != "—" },
                ).joinToString(" · "),
                onClick = { expanded = if (expanded == session.startMs) null else session.startMs; render() }))
            if (expanded == session.startMs) list.addView(chargeDetail(ctx, session, s))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Détails
    // ═════════════════════════════════════════════════════════════════════════

    private fun tripDetail(ctx: Context, trip: Trip): View = detailBox(ctx, listOfNotNull(
        trip.climateKwh?.let { getString(R.string.stats_detail_climate) to kwh(it) },
        trip.accessoriesKwh?.let { getString(R.string.stats_detail_accessories) to kwh(it) },
        trip.regenKwh?.let { getString(R.string.stats_detail_regen) to "+ ${kwh(it)}" },
        soc(trip.socStart, trip.socEnd)?.let { getString(R.string.stats_detail_battery) to it },
        trip.outsideTempC?.let { getString(R.string.stats_detail_temp) to "${fmt(it)} °C" },
    ))

    private fun chargeDetail(ctx: Context, session: ChargeSession, s: StatsSettings): View {
        val box = detailBox(ctx, listOfNotNull(
            session.measuredPowerKw?.let { getString(R.string.stats_detail_power_measured) to "${fmt(it)} kW" }
                ?: session.powerKw?.let { getString(R.string.stats_detail_power_computed) to "${fmt(it)} kW" },
            soc(session.socStart, session.socEnd)?.let { getString(R.string.stats_detail_battery) to it },
            session.outsideTempC?.let { getString(R.string.stats_detail_temp) to "${fmt(it)} °C" },
            (getString(R.string.stats_detail_tariff) to tariffLine(session, s)),
        ))
        box.addView(MaterialButton(ctx).apply {
            text = getString(R.string.stats_fix_tariff)
            setTextColor(ctx.getColor(R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.dash_btn))
            strokeColor = ColorStateList.valueOf(ctx.getColor(R.color.dash_border))
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52)
            ).also { it.topMargin = dp(ctx, 10) }
            setOnClickListener { askTariff(session, s) }
        })
        return box
    }

    /** Tarif appliqué, en disant d'où il vient : corrigé à la main, ou tarif du type de prise. */
    private fun tariffLine(session: ChargeSession, s: StatsSettings): String {
        val defaut = s.priceFor(session.type)
        val prix = session.tariffOverride ?: defaut
        val origine = if (session.tariffOverride != null)
            getString(R.string.stats_tariff_instead, fmt3(defaut))
        else if (session.type == ChargeType.DC) getString(R.string.stats_charge_dc)
        else getString(R.string.stats_charge_ac)
        return "${fmt3(prix)} ${s.currency}/kWh · $origine"
    }

    private fun askTariff(session: ChargeSession, s: StatsSettings) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmt3(session.tariffOverride ?: s.priceFor(session.type)))
            setTextColor(ctx.getColor(R.color.text_primary))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.stats_fix_tariff)
            .setMessage(getString(R.string.stats_fix_tariff_note))
            .setView(input)
            .setPositiveButton(R.string.stats_save) { _, _ ->
                val value = input.text.toString().replace(',', '.').toFloatOrNull()
                if (value != null) {
                    store.overrideTariff(session.startMs, StatsSettings.clampPrice(value))
                    render()
                }
            }
            .setNeutralButton(R.string.stats_tariff_default) { _, _ ->
                store.overrideTariff(session.startMs, null)
                render()
            }
            .setNegativeButton(R.string.nav_close, null)
            .show()
    }

    private fun confirmClear() {
        val ctx = requireContext()
        val h = store.history()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.stats_clear)
            .setMessage(getString(R.string.stats_clear_confirm, h.trips.size, h.charges.size))
            .setPositiveButton(R.string.stats_clear) { _, _ ->
                store.clear()
                expanded = null
                render()
            }
            .setNegativeButton(R.string.nav_close, null)
            .show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Fabrique de vues
    // ═════════════════════════════════════════════════════════════════════════

    /** Deux tuiles par ligne : au-delà, les nombres deviennent illisibles sur cet écran. */
    private fun grid(ctx: Context, parent: LinearLayout, tiles: List<Pair<String, String>>) {
        tiles.chunked(2).forEach { paire ->
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(ctx, 8) }
            }
            paire.forEachIndexed { index, (label, value) ->
                ligne.addView(tile(ctx, label, value, marginEnd = if (index == 0) dp(ctx, 8) else 0))
            }
            if (paire.size == 1) ligne.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            parent.addView(ligne)
        }
    }

    private fun tile(ctx: Context, label: String, value: String, marginEnd: Int): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section_rounded)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = marginEnd }
        }
        box.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
        })
        box.addView(TextView(ctx).apply {
            text = value
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(ctx.getColor(R.color.text_primary))
        })
        return box
    }

    private fun periodRow(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(choice(ctx, getString(R.string.stats_period_short, SHORT_PERIOD_DAYS), shortPeriod) {
            shortPeriod = true; render()
        })
        addView(choice(ctx, getString(R.string.stats_period_all), !shortPeriod) {
            shortPeriod = false; render()
        })
    }

    private fun choice(ctx: Context, label: String, on: Boolean, onClick: () -> Unit): View =
        MaterialButton(ctx).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
            backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
            strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52)
            ).also { it.marginEnd = dp(ctx, 8) }
            setOnClickListener { onClick() }
        }

    private fun row(
        ctx: Context, when1: String, when2: String, value1: String, value2: String, onClick: () -> Unit
    ): View {
        val ligne = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val gauche = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        gauche.addView(label(ctx, when1, 16f, R.color.text_primary))
        gauche.addView(label(ctx, when2, 13f, R.color.text_secondary))
        val droite = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        droite.addView(label(ctx, value1, 16f, R.color.text_primary, mono = true))
        droite.addView(label(ctx, value2, 13f, R.color.text_secondary))
        ligne.addView(gauche)
        ligne.addView(droite)
        return ligne
    }

    private fun detailBox(ctx: Context, lines: List<Pair<String, String>>): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section_rounded)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 8) }
        }
        lines.forEach { (label, value) ->
            val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ligne.addView(label(ctx, label, 13f, R.color.text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            ligne.addView(label(ctx, value, 13f, R.color.text_primary, mono = true))
            box.addView(ligne)
        }
        return box
    }

    private fun emptyNote(ctx: Context, res: Int): View =
        label(ctx, getString(res), 16f, R.color.dash_text_lo)

    private fun label(
        ctx: Context, text: String, size: Float, color: Int, mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(ctx.getColor(color))
        if (mono) typeface = Typeface.MONOSPACE
    }

    /** Ligne « libellé + champ » : la saisie est enregistrée à la validation et au départ du focus. */
    private fun textRow(
        ctx: Context, label: String, value: String, onCommit: (String) -> Unit
    ): View = fieldRow(ctx, label, value, InputType.TYPE_CLASS_TEXT) { onCommit(it) }

    private fun numberRow(
        ctx: Context, label: String, value: Float, decimals: Int, onCommit: (Float) -> Unit
    ): View = fieldRow(
        ctx, label,
        if (decimals == 3) fmt3(value) else fmt(value),
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    ) { saisi ->
        saisi.replace(',', '.').toFloatOrNull()?.let { onCommit(it) }
    }

    private fun fieldRow(
        ctx: Context, labelText: String, value: String, type: Int, onCommit: (String) -> Unit
    ): View {
        val ligne = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        ligne.addView(label(ctx, labelText, 16f, R.color.text_primary).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val champ = EditText(ctx).apply {
            setText(value)
            inputType = type
            textSize = 16f
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 140), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun commit() {
            onCommit(champ.text.toString())
            render()
        }
        champ.setOnFocusChangeListener { _, focus -> if (!focus) commit() }
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) commit()
            false
        }
        ligne.addView(champ)
        return ligne
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Mise en forme
    // ═════════════════════════════════════════════════════════════════════════

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun fmt(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

    private fun fmt3(value: Float): String = String.format(Locale.getDefault(), "%.3f", value)

    private fun km(value: Int): String = "$value km"

    private fun kwh(value: Float): String = "${fmt(value)} kWh"

    private fun money(value: Float?, s: StatsSettings): String =
        value?.let { "${fmt(it)} ${s.currency}" } ?: "—"

    private fun soc(start: Float?, end: Float?): String? {
        if (start == null && end == null) return null
        val a = start?.let { "${it.toInt()} %" } ?: "?"
        val b = end?.let { "${it.toInt()} %" } ?: "?"
        return "$a → $b"
    }

    private fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}"
               else "$minutes min"
    }

    private fun dateLine(startMs: Long, endMs: Long): String {
        val ctx = context ?: return ""
        val heure = android.text.format.DateFormat.getTimeFormat(ctx)
        val jour = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
        return "${jour.format(Date(startMs))} · ${heure.format(Date(startMs))} → ${heure.format(Date(endMs))}"
    }

    private fun retentionLabel(r: StatsSettings.Retention): String = when (r) {
        StatsSettings.Retention.DAYS_30  -> getString(R.string.stats_retention_30d)
        StatsSettings.Retention.MONTHS_3 -> getString(R.string.stats_retention_3m)
        StatsSettings.Retention.MONTHS_6 -> getString(R.string.stats_retention_6m)
        StatsSettings.Retention.YEAR     -> getString(R.string.stats_retention_1y)
    }
}
