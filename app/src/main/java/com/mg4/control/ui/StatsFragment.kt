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
import com.mg4.control.model.StatsHistory
import com.mg4.control.model.StatsSettings
import com.mg4.control.model.StatsSummary
import com.mg4.control.model.Trip
import com.mg4.control.stats.StatsStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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

    /**
     * Champs de la carte des prix, gardés entre deux rendus.
     *
     * Les reconstruire à chaque rendu ferait disparaître celui qui a le focus au moment où
     * l'utilisateur passe au suivant — et Android plante au dessin d'après, faute de pouvoir
     * faire défiler jusqu'à une vue qui n'est plus dans l'arbre.
     */
    private val priceFields = mutableListOf<Pair<EditText, () -> String>>()
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
        v: View, s: StatsSettings, sum: StatsSummary, history: StatsHistory
    ) {
        if (!s.enabled) return
        val ctx = v.context

        val tiles = v.findViewById<LinearLayout>(R.id.stats_summary_tiles)
        tiles
        vider(tiles)
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_distance) to km(sum.distanceKm),
            getString(R.string.stats_tile_consumption) to
                (conso(sum.consumptionPer100, sum.consumptionApproximate) ?: "—"),
            getString(R.string.stats_tile_speed) to
                (sum.averageSpeedKmh?.let { "${it.toInt()} km/h" } ?: "—"),
            getString(R.string.stats_tile_energy) to kwh(sum.energyKwh),
            getString(R.string.stats_tile_regen) to kwh(sum.regenKwh),
            getString(R.string.stats_tile_charged) to kwh(sum.chargedKwh),
            getString(R.string.stats_tile_cost) to money(sum.drivingCost, s),
            getString(R.string.stats_tile_cost_per100) to money(sum.costPer100, s),
        ))

        val prices = v.findViewById<LinearLayout>(R.id.stats_price_rows)
        if (prices.childCount == 0) {
            // Vue recréée : les anciens champs ne doivent pas rester dans la liste.
            priceFields.clear()
            prices.addView(textRow(ctx, getString(R.string.stats_currency), { store.settings().currency }) { saisi ->
                store.saveSettings(store.settings().copy(currency = saisi.take(3).ifBlank { "€" }))
            })
            prices.addView(numberRow(ctx, getString(R.string.stats_price_ac),
                { fmt3(store.settings().priceAc) }) { value ->
                store.saveSettings(store.settings().copy(priceAc = StatsSettings.clampPrice(value)))
            })
            prices.addView(numberRow(ctx, getString(R.string.stats_price_dc),
                { fmt3(store.settings().priceDc) }) { value ->
                store.saveSettings(store.settings().copy(priceDc = StatsSettings.clampPrice(value)))
            })
            prices.addView(numberRow(ctx, getString(R.string.stats_capacity),
                { fmt(store.settings().capacityKwh) }) { value ->
                store.saveSettings(store.settings().copy(capacityKwh = StatsSettings.clampCapacity(value)))
                store.markCapacityUserSet()
            })
        } else {
            // Un champ en cours de saisie garde ce que l'utilisateur est en train d'écrire.
            priceFields.forEach { (champ, valeur) -> if (!champ.hasFocus()) champ.setText(valeur()) }
        }

        val retention = v.findViewById<LinearLayout>(R.id.stats_retention_row)
        vider(retention)
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
        tiles
        vider(tiles)
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_trips) to sum.tripCount.toString(),
            getString(R.string.stats_tile_distance) to km(sum.distanceKm),
            getString(R.string.stats_tile_longest) to km(sum.longestTripKm),
            getString(R.string.stats_tile_cost) to money(sum.drivingCost, s),
        ))

        val list = v.findViewById<LinearLayout>(R.id.stats_trips_list)
        list
        vider(list)
        if (trips.isEmpty()) {
            list.addView(emptyNote(ctx, R.string.stats_no_trip))
            return
        }
        trips.forEach { trip ->
            list.addView(row(ctx, when1 = dateLine(trip.startMs, trip.endMs),
                when2 = "${duration(trip.durationMs)} · ${km(trip.distance)}",
                value1 = conso(trip.consumptionPer100, trip.consumptionApproximate) ?: "—",
                value2 = listOfNotNull(
                    trip.averageSpeedKmh?.let { "${it.toInt()} km/h" },
                    kwh(trip.netEnergyKwh),
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
        tiles
        vider(tiles)
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_charged) to kwh(sum.chargedKwh),
            getString(R.string.stats_tile_cost_total) to money(sum.chargeCost, s),
            getString(R.string.stats_tile_avg_price) to
                (sum.averagePricePerKwh?.let { "${fmt3(it)} ${s.currency}" } ?: "—"),
            getString(R.string.stats_tile_sessions) to "${sum.chargeCount} · ${sum.acCount} AC / ${sum.dcCount} DC",
        ))

        val list = v.findViewById<LinearLayout>(R.id.stats_charges_list)
        list
        vider(list)
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
                when1 = dateLine(session.displayStartMs, session.displayEndMs) + corrige,
                // Sans horaires, la « durée » d'une charge reconstituée serait l'intervalle entre
                // deux réveils et non celle de la charge : on annonce l'origine à la place.
                when2 = if (session.reconstructed && !session.timesKnown)
                    "$type · ${getString(R.string.stats_charge_reconstructed)}"
                else "$type · ${duration(session.durationMs)}",
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
        // L'énergie du moteur n'est pas publiée : c'est le total moins les postes annexes.
        trip.motorKwh?.let { getString(R.string.stats_detail_motor) to kwh(it) },
        trip.climateKwh?.let { getString(R.string.stats_detail_climate) to kwh(it) },
        trip.accessoriesKwh?.let { getString(R.string.stats_detail_accessories) to kwh(it) },
        // Signe négatif : dans un détail de consommation, la régénération RETRANCHE. Moteur +
        // climatisation + accessoires − récupération donne bien le total affiché sur la ligne.
        trip.regenKwh?.let { getString(R.string.stats_detail_regen) to "− ${kwh(it)}" },
        // Sous la distance plancher on dit POURQUOI il n'y a pas de ratio, plutôt qu'un tiret muet.
        getString(R.string.stats_detail_consumption) to
            (conso(trip.consumptionPer100, trip.consumptionApproximate)
                ?: getString(R.string.stats_detail_consumption_short)),
        soc(trip.socStart, trip.socEnd)?.let { getString(R.string.stats_detail_battery) to it },
        trip.outsideTempC?.let { getString(R.string.stats_detail_temp) to "${fmt(it)} °C" },
    ))

    private fun chargeDetail(ctx: Context, session: ChargeSession, s: StatsSettings): View {
        val box = detailBox(ctx, listOfNotNull(
            // Ni puissance mesurée ni durée vraie quand l'application n'a pas vu la charge :
            // la seule chose honnête à montrer est d'où sort le chiffre.
            if (session.reconstructed)
                getString(R.string.stats_detail_origin) to getString(
                    if (session.timesKnown) R.string.stats_detail_origin_completed
                    else R.string.stats_detail_origin_estimated
                )
            else session.measuredPowerKw?.let { getString(R.string.stats_detail_power_measured) to "${fmt(it)} kW" }
                ?: session.powerKw?.let { getString(R.string.stats_detail_power_computed) to "${fmt(it)} kW" },
            // Reconstituée mais datée à la main : la puissance se calcule de nouveau, et reste
            // annoncée comme une déduction.
            session.takeIf { it.reconstructed }?.powerKw
                ?.let { getString(R.string.stats_detail_power_computed) to "${fmt(it)} kW" },
            soc(session.socStart, session.socEnd)?.let { getString(R.string.stats_detail_battery) to it },
            session.outsideTempC?.let { getString(R.string.stats_detail_temp) to "${fmt(it)} °C" },
            (getString(R.string.stats_detail_tariff) to tariffLine(session, s)),
        ))
        // Une charge que l'application n'a pas vue peut être complétée : elle seule a des trous.
        if (session.reconstructed) box.addView(dialogButton(ctx, R.string.stats_complete_session) {
            askCompletion(session)
        })
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

    /** Bouton d'action d'un détail, au même gabarit que celui du tarif. */
    private fun dialogButton(ctx: Context, texte: Int, action: () -> Unit): MaterialButton =
        MaterialButton(ctx).apply {
            text = getString(texte)
            setTextColor(ctx.getColor(R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.dash_btn))
            strokeColor = ColorStateList.valueOf(ctx.getColor(R.color.dash_border))
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52)
            ).also { it.topMargin = dp(ctx, 10) }
            setOnClickListener { action() }
        }

    /**
     * Complète une charge que l'application n'a pas vue : type de prise et horaires réels.
     *
     * Seules les heures sont demandées, jamais les dates : une charge de nuit enjambe deux jours,
     * et [heureProche] retrouve seule la bonne en se calant sur le relevé qui encadre la charge.
     */
    private fun askCompletion(session: ChargeSession) {
        val ctx = requireContext()
        var type = session.type
        val boutons = mutableListOf<Pair<MaterialButton, ChargeType?>>()

        fun peindre() = boutons.forEach { (b, valeur) ->
            val on = valeur == type
            b.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
            b.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
            b.strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
        }

        fun champ(valeur: Long?) = EditText(ctx).apply {
            // Vingt-quatre heures, quelle que soit la langue : le champ est relu tel quel, et un
            // « 10:30 PM » relu comme 10 h 30 fausserait la durée de douze heures.
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(valeur?.let { hhmm(it) } ?: "")
            setTextColor(ctx.getColor(R.color.text_primary))
        }
        val debut = champ(session.userStartMs)
        val fin = champ(session.userEndMs)

        val corps = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), 0)
            addView(label(ctx, getString(R.string.stats_complete_type), 13f, R.color.text_secondary))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(
                    ChargeType.AC to R.string.stats_charge_ac,
                    ChargeType.DC to R.string.stats_charge_dc,
                    null to R.string.stats_charge_unknown,
                ).forEach { (valeur, libelle) ->
                    addView(MaterialButton(ctx).apply {
                        text = getString(libelle)
                        textSize = 13f
                        isAllCaps = false
                        strokeWidth = dp(ctx, 1)
                        cornerRadius = dp(ctx, 8)
                        layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 52), 1f)
                            .also { it.marginEnd = dp(ctx, 6) }
                        setOnClickListener { type = valeur; peindre() }
                        boutons += this to valeur
                    })
                }
            })
            addView(label(ctx, getString(R.string.stats_complete_start), 13f, R.color.text_secondary))
            addView(debut)
            addView(label(ctx, getString(R.string.stats_complete_end), 13f, R.color.text_secondary))
            addView(fin)
        }
        peindre()

        AlertDialog.Builder(ctx)
            .setTitle(R.string.stats_complete_session)
            .setMessage(getString(R.string.stats_complete_note))
            .setView(corps)
            .setPositiveButton(R.string.stats_save) { _, _ ->
                val d = heureProche(debut.text.toString(), session.startMs)
                // Une fin antérieure au début n'a pas de sens : plutôt que d'inventer une durée
                // négative, on ne retient rien et la puissance reste masquée.
                val f = heureProche(fin.text.toString(), session.endMs)?.takeIf { d == null || it > d }
                store.completeCharge(session.startMs, type, d, f)
                render()
            }
            .setNegativeButton(R.string.nav_close, null)
            .show()
    }

    /** Heure sur vingt-quatre heures, indépendante de la langue, pour remplir et relire un champ. */
    private fun hhmm(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /**
     * Transforme une heure saisie en instant réel, en la plaçant à la date qui va bien.
     *
     * Une charge de nuit enjambe deux jours : demander la date en plus serait une corvée pour rien,
     * alors que l'occurrence la plus proche du relevé qui encadre la charge est toujours la bonne.
     * « 02:10 » saisi en face d'un relevé du matin désigne donc bien cette nuit-là, et « 22:30 » en
     * face d'un relevé du soir désigne la veille au soir.
     */
    private fun heureProche(saisie: String, ancre: Long): Long? {
        val m = Regex("^\\D*(\\d{1,2})\\D+(\\d{2})\\D*$").find(saisie.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ancre
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val douzeHeures = 12 * 3_600_000L
        if (cal.timeInMillis - ancre > douzeHeures) cal.add(Calendar.DAY_OF_MONTH, -1)
        else if (ancre - cal.timeInMillis > douzeHeures) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
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

    /**
     * Vide un conteneur **sans laisser derrière lui une vue qui a le focus**.
     *
     * Retirer la vue focalisée fait planter le système au dessin suivant — « parameter must be a
     * descendant of this view » : il cherche à faire défiler jusqu'à une vue qui n'appartient plus
     * à l'arbre. Constaté le 2026-09-19 en changeant un tarif.
     */
    private fun vider(container: LinearLayout) {
        if (container.findFocus() != null) container.clearFocus()
        container.removeAllViews()
    }

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
        ctx: Context, label: String, value: () -> String, onCommit: (String) -> Unit
    ): View = fieldRow(ctx, label, value, InputType.TYPE_CLASS_TEXT) { onCommit(it) }

    private fun numberRow(
        ctx: Context, label: String, value: () -> String, onCommit: (Float) -> Unit
    ): View = fieldRow(
        ctx, label, value, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    ) { saisi ->
        saisi.replace(',', '.').toFloatOrNull()?.let { onCommit(it) }
    }

    private fun fieldRow(
        ctx: Context, labelText: String, value: () -> String, type: Int, onCommit: (String) -> Unit
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
            setText(value())
            inputType = type
            textSize = 16f
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 140), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        priceFields += champ to value
        fun commit() {
            onCommit(champ.text.toString())
            // Rendu différé : redessiner pendant le transfert de focus retirerait la vue que le
            // système est en train de suivre.
            champ.post { if (isAdded) render() }
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

    /**
     * Distance. Le dixième n'est montré que sous cent kilomètres et seulement s'il a été mesuré :
     * au-delà il n'apporte rien, et l'odomètre du véhicule, lui, reste au kilomètre entier.
     */
    /**
     * Consommation mise en forme. Le signe « ≈ » et la décimale qui disparaît disent ensemble que
     * la résolution des compteurs du véhicule ne garantit pas mieux que ± 10 % : sur un trajet de
     * deux kilomètres, un seul pas de 0,1 kWh vaut cinq kWh/100 km.
     */
    private fun conso(value: Float?, approche: Boolean): String? {
        value ?: return null
        val unite = getString(R.string.stats_unit_per100)
        return if (approche) "≈ ${value.roundToInt()} $unite" else "${fmt(value)} $unite"
    }

    private fun km(value: Float): String =
        if (value < 100f && value % 1f != 0f) "${fmt(value)} km" else "${value.roundToInt()} km"

    private fun kwh(value: Float): String = "${fmt(value)} kWh"

    private fun money(value: Float?, s: StatsSettings): String =
        value?.let { "${fmt(it)} ${s.currency}" } ?: "—"

    /**
     * Le véhicule publie le pourcentage au dixième (69,4 et non 69) : l'arrondir à l'entier jetait
     * une précision déjà acquise, et rendait invérifiable un trajet court — 1 % vaut plus d'un demi
     * kilowattheure sur cette batterie.
     */
    private fun soc(start: Float?, end: Float?): String? {
        if (start == null && end == null) return null
        val a = start?.let { "${fmt(it)} %" } ?: "?"
        val b = end?.let { "${fmt(it)} %" } ?: "?"
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
