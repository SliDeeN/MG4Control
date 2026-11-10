package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.StatsCollector
import com.mg4.control.model.ChargeEnergySource
import com.mg4.control.model.ChargeSession
import com.mg4.control.model.ChargeTimes
import com.mg4.control.model.ChargeType
import com.mg4.control.model.DistanceUnit
import com.mg4.control.hardware.VehicleDistanceUnit
import com.mg4.control.util.DistanceSetting
import com.mg4.control.model.MoneyFormat
import com.mg4.control.model.StatsCsv
import com.mg4.control.model.StatsHistory
import com.mg4.control.model.StatsSettings
import com.mg4.control.model.StatsSummary
import com.mg4.control.model.Trip
import com.mg4.control.model.TripSegment
import com.mg4.control.model.isMixed
import com.mg4.control.model.portion
import com.mg4.control.stats.StatsStore
import com.mg4.control.ui.widget.setCategoryTabActive
import java.text.DateFormat
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
        var pageRetenue = PAGE_GENERAL
        /** Période courte du résumé, en jours. L'autre choix est « tout l'historique ». */
        const val SHORT_PERIOD_DAYS = 30
        const val TAG = "MG4_STATS"
        /** Attente après le dernier pas de l'état de santé avant de recalculer les recharges. */
        const val RECALCUL_SOH_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Vrai tant qu'un recalcul des recharges attend la fin d'un réglage de l'état de santé. */
    private var recalculEnAttente = false

    private lateinit var store: StatsStore

    /**
     * Champs de la carte des prix, gardés entre deux rendus.
     *
     * Les reconstruire à chaque rendu ferait disparaître celui qui a le focus au moment où
     * l'utilisateur passe au suivant — et Android plante au dessin d'après, faute de pouvoir
     * faire défiler jusqu'à une vue qui n'est plus dans l'arbre.
     */
    private val priceFields = mutableListOf<Pair<EditText, () -> String>>()

    /**
     * Unité de distance générale (2026-11-10), relue à chaque rendu : distances, vitesses,
     * consommations et coût aux 100 s'y affichent. L'historique et l'export CSV restent en km.
     */
    private var unite = DistanceUnit.KM
    /** Unité des lignes de réglage déjà posées (leur libellé la nomme) ; `null` : pas encore posées. */
    private var uniteReglages: DistanceUnit? = null
    /** Sous-onglet ouvert, retenu le temps de la session : revenir sur Statistiques rouvre le même (2026-11-07). */
    private var page: Int
        get() = pageRetenue
        set(v) { pageRetenue = v }
    private var shortPeriod = true
    /** Ligne dépliée d'une liste, repérée par l'horodatage de son enregistrement. */
    private var expanded: Long? = null
    /** Filtre « Profil » de l'onglet Trajets (2026-11-05) ; null = tous. */
    private var profilFiltre: String? = null
    /** Profils de l'appli, lus une fois par affichage : noms et couleurs des trajets. */
    private var profilsActuels: List<com.mg4.control.model.DrivingProfile> = emptyList()

    /** Export CSV (issue #206) : la fenêtre ouverte, et le sélecteur de dossier d'Android qui lui répond. */
    private var exportDialog: ExportDialog? = null
    private val choisirDossier = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { arbre ->
        exportDialog?.surDossier(arbre)
    }

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
            view.findViewById<MaterialButton>(id).setOnClickListener {
                // Changer de sous-onglet repart du haut : garder le défilement de l'autre page désoriente.
                if (page != index) view.findViewById<ScrollView>(R.id.scroll_stats)?.scrollTo(0, 0)
                page = index
                render()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_stats_clear).setOnClickListener { confirmClear() }
        view.findViewById<MaterialButton>(R.id.btn_stats_export).setOnClickListener {
            ouvrirExport()
        }

        view.findViewById<Switch>(R.id.switch_stats_skip_short).apply {
            isChecked = store.settings().skipShortTrips
            setOnCheckedChangeListener { _, on ->
                store.saveSettings(store.settings().copy(skipShortTrips = on))
                render()
            }
        }
        bindBattery(view)
        bindStateOfHealth(view)
        // Un menu ne permet pas de « choisir » la valeur déjà sélectionnée : le bandeau offre donc
        // les deux gestes, confirmer la batterie affichée ou aller la changer.
        view.findViewById<MaterialButton>(R.id.btn_stats_battery_ok).setOnClickListener {
            store.markCapacityUserSet()
            render()
        }
        view.findViewById<MaterialButton>(R.id.btn_stats_battery_change)
            .setOnClickListener { showBatterySetting() }

        render()
    }

    override fun onPause() {
        // On quitte l'écran avant la fin de l'attente : le recalcul se fait tout de suite, il ne
        // doit pas se perdre.
        if (recalculEnAttente) {
            handler.removeCallbacks(recalculSoh)
            recalculSoh.run()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Un trajet a pu se terminer pendant qu'on regardait un autre écran.
        if (view != null) render()
        // En Auto, l'unité de la voiture se relit hors du fil principal (binder) ; changée, on redessine.
        if (DistanceSetting.choice(context) == null) Thread {
            val avant = VehicleDistanceUnit.connue()
            val lue = VehicleDistanceUnit.actualiser()
            if (lue != avant) view?.post { if (isAdded && view != null) render() }
        }.start()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rendu
    // ═════════════════════════════════════════════════════════════════════════

    private fun render() {
        val v = view ?: return
        val ctx = v.context
        unite = DistanceSetting.unit(ctx)
        val settings = store.settings()
        val history = store.history()

        // Rail + pages
        listOf(
            Triple(R.id.btn_stats_cat_general, R.id.page_stats_general, PAGE_GENERAL),
            Triple(R.id.btn_stats_cat_trips, R.id.page_stats_trips, PAGE_TRIPS),
            Triple(R.id.btn_stats_cat_charges, R.id.page_stats_charges, PAGE_CHARGES),
        ).forEach { (btnId, pageId, index) ->
            val on = index == page
            v.findViewById<MaterialButton>(btnId).setCategoryTabActive(on)
            v.findViewById<View>(pageId).visibility = if (on) View.VISIBLE else View.GONE
        }
        v.findViewById<View>(R.id.stats_general_config).visibility =
            if (settings.enabled) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.stats_disabled_note).visibility =
            if (settings.enabled) View.GONE else View.VISIBLE
        // L'énergie d'une recharge se déduit du pourcentage et de la batterie : tant que celle-ci
        // n'a été ni choisie ni confirmée, l'onglet Recharge le rappelle.
        v.findViewById<View>(R.id.stats_battery_banner).visibility =
            if (settings.enabled && !store.capacityIsUserSet()) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.stats_battery_banner_text).text =
            getString(R.string.stats_battery_banner, "${settings.battery.nominalKwh} kWh")

        val trips = filtered(history.trips.sortedByDescending { it.endMs }) { it.endMs }
        val charges = filtered(history.charges.sortedByDescending { it.endMs }) { it.endMs }
        val summary = StatsSummary.of(trips, charges, settings)

        // Filtre par profil (2026-11-05) : chaque trajet ramené à la part roulée sous ce profil.
        profilsActuels = com.mg4.control.profile.ProfileManager(ctx).getAll()
        val profils = profilsDes(trips)
        if (profils.none { it.first == profilFiltre }) profilFiltre = null
        val affiches = profilFiltre?.let { id -> trips.mapNotNull { t -> t.portion(id)?.let { it to t } } }
            ?: trips.map { it to it }
        val sommeTrajets = if (profilFiltre == null) summary else StatsSummary.of(affiches.map { it.first }, charges, settings)

        renderGeneral(v, settings, summary, history)
        renderTrips(v, settings, sommeTrajets, affiches, profils)
        renderCharges(v, settings, summary, charges)
    }

    /** Menu des trois batteries. En choisir une recalcule aussitôt les recharges enregistrées. */
    private fun bindBattery(view: View) {
        val batteries = StatsSettings.Battery.entries
        val menu = view.findViewById<Spinner>(R.id.spinner_stats_battery)
        menu.adapter = ArrayAdapter(
            view.context, android.R.layout.simple_spinner_item,
            batteries.map { getString(R.string.stats_battery_option, it.nominalKwh, fmt(it.usableKwh), it.chemistry) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        menu.setSelection(store.settings().battery.ordinal)
        menu.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                val choisie = batteries[position]
                // Android appelle aussi cet écouteur à la mise en place du menu : seul un vrai
                // changement compte.
                if (choisie == store.settings().battery) return
                store.saveSettings(store.settings().copy(capacityKwh = choisie.usableKwh))
                store.markCapacityUserSet()
                store.recalculateCharges(store.settings().effectiveCapacityKwh)
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * État de santé de la batterie, sous le choix de la batterie : − et +, par 0,5 %, maintenus
     * pour aller vite. Le réglage est enregistré à chaque pas ; les recharges, elles, ne sont
     * recalculées qu'une fois le doigt levé ([RECALCUL_SOH_MS]) — un appui maintenu traverse
     * des dizaines de valeurs, inutile de réécrire l'historique pour chacune.
     */
    private fun bindStateOfHealth(view: View) {
        val ctx = view.context
        listOf(R.id.btn_stats_soh_minus, R.id.btn_stats_soh_plus).forEach {
            view.findViewById<ImageView>(it).imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        }
        fun pas(direction: Int) {
            val s = store.settings()
            val soh = StatsSettings.stepSoh(s.stateOfHealthPercent, direction)
            if (soh == s.stateOfHealthPercent) return
            store.saveSettings(s.copy(stateOfHealthPercent = soh))
            renderStateOfHealth(view, store.settings())
            handler.removeCallbacks(recalculSoh)
            handler.postDelayed(recalculSoh, RECALCUL_SOH_MS)
            recalculEnAttente = true
        }
        AppDialog.repeter(view.findViewById(R.id.btn_stats_soh_minus)) { pas(-1) }
        AppDialog.repeter(view.findViewById(R.id.btn_stats_soh_plus)) { pas(1) }
        renderStateOfHealth(view, store.settings())
    }

    /** Valeur, et la capacité qu'elle donne avec la batterie choisie. */
    private fun renderStateOfHealth(v: View, s: StatsSettings) {
        v.findViewById<TextView>(R.id.tv_stats_soh).text = getString(R.string.stats_soh_value, fmt(s.stateOfHealthPercent))
        v.findViewById<TextView>(R.id.tv_stats_soh_effective).text = getString(
            R.string.stats_soh_effective, fmt(s.effectiveCapacityKwh), fmt(s.capacityKwh), fmt(s.stateOfHealthPercent))
    }

    /** Recalcul des recharges enregistrées après un changement d'état de santé, une fois. */
    private val recalculSoh = Runnable {
        recalculEnAttente = false
        AppLogger.i(TAG, "état de santé de la batterie → ${store.settings().stateOfHealthPercent} %")
        store.recalculateCharges(store.settings().effectiveCapacityKwh)
        if (isAdded) render()
    }

    /** Depuis le bandeau de l'onglet Recharge : mène au réglage, là où il se trouve. */
    private fun showBatterySetting() {
        page = PAGE_GENERAL
        render()
        val v = view ?: return
        val scroll = v.findViewById<ScrollView>(R.id.scroll_stats)
        val carte = v.findViewById<View>(R.id.card_stats_battery)
        scroll.post {
            val zone = Rect()
            carte.getDrawingRect(zone)
            scroll.offsetDescendantRectToMyCoords(carte, zone)
            scroll.smoothScrollTo(0, zone.top)
        }
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
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
        // La capacité retenue suit aussi le choix de la batterie.
        renderStateOfHealth(v, s)

        val tiles = v.findViewById<LinearLayout>(R.id.stats_summary_tiles)
        tiles
        vider(tiles)
        tiles.addView(periodRow(ctx))
        grid(ctx, tiles, listOf(
            getString(R.string.stats_tile_distance) to km(sum.distanceKm),
            getString(R.string.stats_tile_consumption) to
                (conso(sum.consumptionPer100, sum.consumptionApproximate) ?: "—"),
            getString(R.string.stats_tile_speed) to
                (sum.averageSpeedKmh?.let { vitesse(it) } ?: "—"),
            // Aucun trajet mesuré sur la période : un tiret, pas un zéro (issue #117).
            getString(R.string.stats_tile_energy) to
                (if (sum.energyUnknown) "—" else kwh(sum.energyKwh)),
            getString(R.string.stats_tile_regen) to
                (if (sum.energyUnknown) "—" else kwh(sum.regenKwh)),
            getString(R.string.stats_tile_charged) to kwh(sum.chargedKwh),
            getString(R.string.stats_tile_cost) to money(sum.drivingCost, s),
            getString(R.string.stats_tile_cost_per100, unite.short) to
                money(sum.costPer100?.let { unite.per100FromKm(it) }, s),
        ))

        val prices = v.findViewById<LinearLayout>(R.id.stats_price_rows)
        val lignesMinimum = v.findViewById<LinearLayout>(R.id.stats_min_trip_row)
        // L'unité a changé : la distance minimale se repose avec son nouveau libellé.
        if (prices.childCount > 0 && uniteReglages != unite) {
            prices.removeAllViews()
            lignesMinimum.removeAllViews()
        }
        if (prices.childCount == 0) {
            uniteReglages = unite
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
            // Enregistrée en km ; saisie et montrée dans l'unité choisie.
            val u = unite
            lignesMinimum.addView(numberRow(ctx,
                getString(R.string.stats_min_trip_label, u.short), { fmt(u.fromKm(store.settings().minTripKm)) }) { value ->
                store.saveSettings(store.settings().copy(minTripKm = StatsSettings.clampMinTrip(value * u.factor)))
            })
        } else {
            // Un champ en cours de saisie garde ce que l'utilisateur est en train d'écrire.
            priceFields.forEach { (champ, valeur) -> if (!champ.hasFocus()) champ.setText(valeur()) }
        }

        // Sans le filtre, la distance minimale ne sert à rien : grisée, comme les réglages d'une
        // automatisation coupée.
        v.findViewById<Switch>(R.id.switch_stats_skip_short).let { sw ->
            if (sw.isChecked != s.skipShortTrips) sw.isChecked = s.skipShortTrips
        }
        v.findViewById<LinearLayout>(R.id.stats_min_trip_row).let { ligne ->
            ligne.alpha = if (s.skipShortTrips) 1f else 0.4f
            setEnabledDeep(ligne, s.skipShortTrips)
        }
        v.findViewById<Spinner>(R.id.spinner_stats_battery).let { menu ->
            if (menu.selectedItemPosition != s.battery.ordinal) menu.setSelection(s.battery.ordinal)
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

    /**
     * Coût d'un trajet, au prix réellement payé sur la période — la même règle que le total de
     * l'écran. Sans aucune charge enregistrée, on retombe sur le tarif alternatif, qui est une
     * estimation et non un relevé.
     */
    private fun tripCost(trip: Trip, sum: StatsSummary, s: StatsSettings): Float? =
        if (trip.energyKnown) trip.netEnergyKwh * (sum.averagePricePerKwh ?: s.priceAc) else null

    /**
     * [trips] : (ce qui est affiché, le trajet entier) — les deux sont le même trajet sans filtre ;
     * avec le filtre « Profil », le premier n'est que la part de ce profil ([portion]).
     */
    private fun renderTrips(v: View, s: StatsSettings, sum: StatsSummary, trips: List<Pair<Trip, Trip>>,
                            profils: List<Pair<String, String>>) {
        val ctx = v.context
        val tiles = v.findViewById<LinearLayout>(R.id.stats_trips_tiles)
        tiles
        vider(tiles)
        tiles.addView(periodRow(ctx))
        if (profils.isNotEmpty()) tiles.addView(profileRow(ctx, profils))
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
        trips.forEach { (trip, entier) ->
            // Avec le filtre, un trajet mixte annonce sa part : « 24 km sur 61 km ».
            val distance = if (trip !== entier && entier.isMixed)
                getString(R.string.stats_portion, km(trip.distance), km(entier.distance)) else km(trip.distance)
            list.addView(row(ctx, when1 = dateLine(entier.startMs, entier.endMs),
                when2 = listOfNotNull(
                    duration(trip.durationMs),
                    distance,
                    money(tripCost(trip, sum, s), s).takeIf { it != "—" },
                ).joinToString(" · "),
                value1 = conso(trip.consumptionPer100, trip.consumptionApproximate) ?: "—",
                value2 = listOfNotNull(
                    trip.averageSpeedKmh?.let { vitesse(it) },
                    kwh(trip.netEnergyKwh).takeIf { trip.energyKnown },
                ).joinToString(" · "),
                badge = pastille(ctx, entier),
                onClick = { expanded = if (expanded == entier.startMs) null else entier.startMs; render() }))
            if (expanded == entier.startMs) {
                if (entier.isMixed) list.addView(repartition(ctx, entier))
                list.addView(courbe(ctx, entier))
                list.addView(tripDetail(ctx, entier))
            }
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

    /**
     * D'où vient l'énergie de la charge (issue #156) : mesurée, en partie mesurée, estimée d'après
     * les charges déjà vues, ou d'après le niveau de batterie. Pour une charge reconstituée au
     * pourcentage, la ligne « Origine » le dit déjà : pas de doublon.
     */
    private fun energySourceLine(session: ChargeSession): Pair<String, String>? {
        val source = session.energySource ?: ChargeEnergySource.PERCENT
        if (session.reconstructed && source == ChargeEnergySource.PERCENT) return null
        return getString(R.string.stats_detail_energy_source) to getString(when (source) {
            ChargeEnergySource.MEASURED -> R.string.stats_energy_measured
            ChargeEnergySource.MIXED -> R.string.stats_energy_mixed
            ChargeEnergySource.LEARNED -> R.string.stats_energy_learned
            ChargeEnergySource.PERCENT -> R.string.stats_energy_percent
        })
    }

    private fun tripDetail(ctx: Context, trip: Trip): View = detailBox(ctx, tripEnergyLines(trip) + listOfNotNull(
        soc(trip.socStart, trip.socEnd)?.let { getString(R.string.stats_detail_battery) to it },
        trip.outsideTempC?.let { getString(R.string.stats_detail_temp) to "${fmt(it)} °C" },
    ))

    private fun tripEnergyLines(trip: Trip): List<Pair<String, String>> = if (!trip.energyKnown) listOf(
        // Ni total ni poste à détailler : des lignes à 0,0 kWh passeraient pour des mesures.
        getString(R.string.stats_detail_consumption) to getString(R.string.stats_detail_energy_unknown),
    ) else listOfNotNull(
        // Compteurs du véhicule muets (issue #117) : l'énergie est calculée, et l'écran le dit.
        if (trip.energyIntegrated)
            getString(R.string.stats_detail_origin) to getString(R.string.stats_detail_origin_integrated)
        else null,
        // L'énergie du moteur n'est pas publiée : c'est le total moins les postes annexes. Sur un
        // trajet calculé, le poste annexe est au kWh entier : le moteur ne peut pas être plus fin.
        trip.motorKwh?.let {
            getString(R.string.stats_detail_motor) to (if (trip.energyIntegrated) kwhEntier(it) else kwh(it))
        },
        trip.climateKwh?.let { getString(R.string.stats_detail_climate) to kwh(it) },
        trip.accessoriesKwh?.let { getString(R.string.stats_detail_accessories) to kwh(it) },
        trip.auxiliaryKwh?.let { getString(R.string.stats_detail_auxiliary) to kwhEntier(it) },
        // Signe négatif : dans un détail de consommation, la régénération RETRANCHE. Moteur +
        // climatisation + accessoires − récupération donne bien le total affiché sur la ligne.
        trip.regenKwh?.let { getString(R.string.stats_detail_regen) to "− ${kwh(it)}" },
        // Sous la distance plancher on dit POURQUOI il n'y a pas de ratio, plutôt qu'un tiret muet.
        getString(R.string.stats_detail_consumption) to
            (conso(trip.consumptionPer100, trip.consumptionApproximate)
                ?: getString(R.string.stats_detail_consumption_short)),
    )

    private fun chargeDetail(ctx: Context, session: ChargeSession, s: StatsSettings): View {
        val box = detailBox(ctx, listOfNotNull(
            energySourceLine(session),
            // Ni puissance mesurée ni durée vraie quand l'application n'a pas vu la charge :
            // la seule chose honnête à montrer est d'où sort le chiffre.
            // Seulement si l'énergie vient du pourcentage : mesurée en partie, c'est la ligne
            // « Énergie » qui le dit, et « l'application ne tournait pas » serait faux.
            if (session.reconstructed) (getString(R.string.stats_detail_origin) to getString(
                    if (session.timesKnown) R.string.stats_detail_origin_completed
                    else R.string.stats_detail_origin_estimated
                )).takeIf { (session.energySource ?: ChargeEnergySource.PERCENT) == ChargeEnergySource.PERCENT }
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
     * Seules les heures sont demandées, jamais les dates : [ChargeTimes.resolve] retrouve seule
     * la bonne.
     */
    private fun askCompletion(session: ChargeSession) {
        StatsDialogs.completer(requireContext(), session) { type, debut, fin ->
            val (d, f) = ChargeTimes.resolve(debut, fin, session.startMs, session.endMs)
            store.completeCharge(session.startMs, type, d, f)
            render()
        }
    }

    private fun askTariff(session: ChargeSession, s: StatsSettings) {
        val defaut = s.priceFor(session.type)
        val prix = session.tariffOverride ?: defaut
        StatsDialogs.tarif(
            requireContext(),
            prix = prix,
            // Le plus grand des deux : un tarif par défaut à zéro (charge gratuite) ne doit pas
            // réduire les pas à ceux d'un prix nul.
            reference = maxOf(prix, defaut),
            unite = "${s.currency}/kWh",
            onEnregistrer = { valeur ->
                store.overrideTariff(session.startMs, StatsSettings.clampPrice(valeur))
                render()
            },
            onDefaut = {
                store.overrideTariff(session.startMs, null)
                render()
            },
        )
    }

    /** Noms actuels des profils, pour l'export (un profil supprimé garde le nom noté sur le trajet). */
    private fun nomsProfils(): Map<String, String> =
        com.mg4.control.profile.ProfileManager(requireContext()).getAll().associate { it.id to it.name }

    /** Export CSV (issue #206) : trajets, morceaux de trajet par profil et recharges, à l'endroit choisi. */
    private fun ouvrirExport() {
        val ctx = requireContext()
        val h = store.history()
        if (h.trips.isEmpty() && h.charges.isEmpty()) {
            android.widget.Toast.makeText(ctx, R.string.stats_export_empty, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val virgule = ctx.resources.configuration.locales[0].language != "en"
        exportDialog = ExportDialog(ctx,
            titre = getString(R.string.stats_export_title),
            sousTitre = getString(R.string.stats_export_subtitle, h.trips.size, h.charges.size),
            bouton = getString(R.string.stats_export),
            titreFait = getString(R.string.stats_export_done),
            fichiers = {
                val maintenant = System.currentTimeMillis()
                val tout = store.history()
                listOf(
                    StatsCsv.fileName("trips", maintenant) to StatsCsv.trips(tout.trips, virgule, noms = nomsProfils()),
                    StatsCsv.fileName("trip_segments", maintenant) to StatsCsv.segments(tout.trips, virgule, noms = nomsProfils()),
                    StatsCsv.fileName("charges", maintenant) to StatsCsv.charges(tout.charges, store.settings(), virgule),
                )
            },
            parcourir = { choisirDossier.launch(null) },
        ).also { it.ouvrir() }
    }

    private fun confirmClear() {
        val ctx = requireContext()
        val h = store.history()
        AppDialog.confirmer(
            ctx,
            titre = getString(R.string.stats_clear),
            message = getString(R.string.stats_clear_confirm, h.trips.size, h.charges.size),
            ton = AppDialog.Ton.DANGER,
            icone = R.drawable.ic_dialog_trash,
            oui = getString(R.string.stats_clear),
            non = getString(R.string.nav_close),
            onOui = {
                store.clear()
                expanded = null
                render()
            },
        )
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
            styleChoix(this, on)
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52)
            ).also { it.marginEnd = dp(ctx, 8) }
            setOnClickListener { onClick() }
        }

    /** Couleurs d'un bouton de choix, allumé ou non : repeint sur place par les boutons de la courbe. */
    private fun styleChoix(b: MaterialButton, on: Boolean) {
        val ctx = b.context
        b.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
        b.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
        b.strokeColor = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
    }

    // ── Courbe du trajet (2026-11-05, maquette B) ───────────────────────────

    /**
     * Le graphique du détail : barres de consommation par tronçon de 500 m, et deux boutons qui
     * ajoutent la vitesse et l'altitude en bandes sous les barres. Leur choix vaut pour tous les
     * trajets et se garde. Un trajet sans courbe (d'avant la fonction) le dit en une ligne.
     */
    private fun courbe(ctx: Context, trip: Trip): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section_rounded)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(ctx, 8) }
        }
        val releves = com.mg4.control.model.TripCurve.decode(trip.curve)
        val barres = com.mg4.control.model.TripCurve.bars(releves)
        if (barres == null || barres.bars.isEmpty()) {
            box.addView(label(ctx, getString(R.string.stats_curve_none), 13f, R.color.dash_text_lo))
            return box
        }
        val vue = TripCurveView(ctx).apply {
            curve = barres
            distance = unite
            showSpeed = store.curveSpeedShown()
            showAltitude = store.curveAltitudeShown()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(ctx, 6) }
        }
        val maxi = barres.bars.maxOf { it.kwhPer100 }
        val tete = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tete.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(label(ctx, getString(R.string.stats_curve_title), 13f, R.color.text_secondary))
            addView(label(ctx, getString(R.string.stats_curve_summary,
                trip.consumptionPer100?.let { fmt(unite.per100FromKm(it)) } ?: "—",
                Math.round(unite.per100FromKm(maxi)).toString()), 12f, R.color.dash_text_lo))
        })
        // Dénivelé : sous le graphique, seulement avec la bande d'altitude.
        val denivele = label(ctx, "", 12f, R.color.text_secondary).apply { setPadding(0, dp(ctx, 4), 0, 0) }
        val legende = label(ctx, "", 12f, R.color.dash_text_lo)
        fun peindreBas() {
            legende.text = legendeCourbe(ctx, vue.showSpeed, vue.showAltitude)
            val d = com.mg4.control.model.TripCurve.elevation(releves)
            denivele.text = if (d == null) getString(R.string.stats_curve_no_altitude)
                else getString(R.string.stats_curve_elevation_value, getString(R.string.stats_curve_elevation), d.first, d.second)
            denivele.visibility = if (vue.showAltitude) View.VISIBLE else View.GONE
        }
        fun bouton(texte: String, on: () -> Boolean, basculer: (Boolean) -> Unit) = MaterialButton(ctx).apply {
            text = texte
            textSize = 14f
            isAllCaps = false
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 8)
            styleChoix(this, on())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52))
                .also { it.marginStart = dp(ctx, 8) }
            setOnClickListener { basculer(!on()); styleChoix(this, on()); peindreBas() }
        }
        tete.addView(bouton(getString(R.string.stats_curve_speed), { vue.showSpeed }) {
            vue.showSpeed = it; store.setCurveSpeedShown(it)
        })
        tete.addView(bouton(getString(R.string.stats_curve_elevation), { vue.showAltitude }) {
            vue.showAltitude = it; store.setCurveAltitudeShown(it)
        })
        box.addView(tete)
        box.addView(vue)
        box.addView(legende)
        box.addView(denivele)
        peindreBas()
        return box
    }

    /** Légende des couleurs : un carré coloré par entrée, le texte reste dans la couleur du texte. */
    private fun legendeCourbe(ctx: Context, vitesse: Boolean, altitude: Boolean): CharSequence {
        val sobre = unite.per100FromKm(TripCurveView.SOBRE).roundToInt()
        val normal = unite.per100FromKm(TripCurveView.NORMAL).roundToInt()
        val entrees = mutableListOf(
            // Seuils des couleurs, dans l'unité affichée : 14 et 22 kWh/100 km, 23 et 35 kWh/100 mi.
            "< $sobre" to ctx.getColor(R.color.dash_eco),
            "$sobre–$normal" to ctx.getColor(R.color.dash_accent),
            "> $normal kWh/100" to ctx.getColor(R.color.dash_warn),
            getString(R.string.stats_curve_legend_regen) to assombrir(ctx.getColor(R.color.dash_eco)),
        )
        if (vitesse) entrees += getString(R.string.stats_curve_legend_speed) to ctx.getColor(R.color.text_secondary)
        if (altitude) entrees += getString(R.string.stats_curve_legend_altitude) to TripCurveView.C_ALTITUDE
        val sb = android.text.SpannableStringBuilder()
        entrees.forEach { (texte, couleur) ->
            if (sb.isNotEmpty()) sb.append("    ")
            val debut = sb.length
            sb.append("■ ")
            sb.setSpan(android.text.style.ForegroundColorSpan(couleur), debut, debut + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(texte)
        }
        return sb
    }

    private fun assombrir(c: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[2] *= 0.62f
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ── Trajets par profil (2026-11-05) ─────────────────────────────────────

    /** Profils présents dans [trips] (id, nom) : le nom actuel s'il existe encore, sinon celui du trajet. */
    private fun profilsDes(trips: List<Trip>): List<Pair<String, String>> {
        val actuels = profilsActuels.associate { it.id to it.name }
        val vus = LinkedHashMap<String, String>()
        trips.forEach { t -> t.segments.orEmpty().forEach { seg ->
            val id = seg.profileId ?: return@forEach
            if (id !in vus) vus[id] = actuels[id] ?: seg.profileName ?: "?"
        } }
        // Dans l'ordre des profils de l'appli, les profils supprimés à la fin.
        val ordre = actuels.keys.toList()
        return vus.entries.sortedBy { ordre.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { it.key to it.value }
    }

    /** Couleur d'un profil : la pastille de sa place dans la liste des profils. */
    private fun couleurProfil(ctx: Context, id: String?): Int {
        if (id == null) return ctx.getColor(R.color.text_secondary)
        val i = profilsActuels.indexOfFirst { it.id == id }
        val palette = com.mg4.control.model.BubbleColors.PALETTE
        return if (i < 0) ctx.getColor(R.color.text_secondary) else palette[i % palette.size]
    }

    private fun nomProfil(id: String?, nom: String?): String =
        if (id == null) "—" else profilsActuels.firstOrNull { it.id == id }?.name ?: nom ?: "?"

    private fun profileRow(ctx: Context, profils: List<Pair<String, String>>): View =
        android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(ctx, getString(R.string.stats_filter_profile), 14f, R.color.text_secondary).apply {
                    setPadding(0, 0, dp(ctx, 10), 0)
                })
                addView(choice(ctx, getString(R.string.stats_filter_all), profilFiltre == null) { profilFiltre = null; expanded = null; render() })
                profils.forEach { (id, nom) ->
                    addView(choice(ctx, nom, profilFiltre == id) { profilFiltre = id; expanded = null; render() })
                }
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(ctx, 6) }
        }

    /** « Paul » pour un seul profil, « Mixte » pour plusieurs, rien pour un trajet d'avant la fonction. */
    private fun pastille(ctx: Context, t: Trip): Pair<String, Int>? {
        val segs = t.segments ?: return null
        return if (t.isMixed) getString(R.string.stats_trip_mixed) to ctx.getColor(R.color.dash_warn)
        else segs.firstOrNull()?.let { nomProfil(it.profileId, it.profileName) to couleurProfil(ctx, it.profileId) }
    }

    /** Détail d'un trajet mixte : la barre de répartition, une ligne par profil, l'ordre réel. */
    private fun repartition(ctx: Context, t: Trip): View {
        val segs = t.segments.orEmpty()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_section_rounded)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(ctx, 8) }
        }
        box.addView(label(ctx, getString(R.string.stats_split_title), 13f, R.color.text_secondary))
        val barre = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8))
                .also { it.topMargin = dp(ctx, 6); it.bottomMargin = dp(ctx, 6) }
        }
        segs.forEach { seg ->
            barre.addView(View(ctx).apply { setBackgroundColor(couleurProfil(ctx, seg.profileId)) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, seg.distanceKm.coerceAtLeast(0.01f)))
        }
        box.addView(barre)
        // Une ligne par profil : ses morceaux additionnés.
        segs.groupBy { it.profileId }.forEach { (id, ms) ->
            val km = ms.sumOf { it.distanceKm.toDouble() }.toFloat()
            val net = ms.sumOf { it.netEnergyKwh.toDouble() }.toFloat()
            val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            ligne.addView(label(ctx, "● ", 13f, R.color.text_primary).apply { setTextColor(couleurProfil(ctx, id)) })
            ligne.addView(label(ctx, nomProfil(id, ms.first().profileName), 13f, R.color.text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            ligne.addView(label(ctx, listOfNotNull(km(km), kwh(net),
                if (km >= 1f) conso(net * 100f / km, false) else null).joinToString(" · "), 13f, R.color.text_primary, mono = true))
            box.addView(ligne)
        }
        box.addView(label(ctx, getString(R.string.stats_split_order,
            segs.joinToString(" → ") { "${nomProfil(it.profileId, it.profileName)} ${km(it.distanceKm)}" }), 12f, R.color.dash_text_lo)
            .apply { setPadding(0, dp(ctx, 6), 0, 0) })
        return box
    }

    private fun row(
        ctx: Context, when1: String, when2: String, value1: String, value2: String,
        badge: Pair<String, Int>? = null, onClick: () -> Unit
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
        if (badge == null) gauche.addView(label(ctx, when1, 16f, R.color.text_primary))
        else gauche.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(ctx, when1, 16f, R.color.text_primary))
            // Profil du trajet (2026-11-05) : son nom, ou « Mixte ».
            addView(label(ctx, badge.first, 12f, R.color.text_primary).apply {
                setTextColor(badge.second)
                setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
                com.mg4.control.service.OverlayKit.fond(this, ctx.getColor(R.color.dash_btn), badge.second, dp(ctx, 1), dp(ctx, 10).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = dp(ctx, 8) }
            })
        })
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

    /** Prix d'un kWh, sans séparateur de milliers : il sert aussi à préremplir les champs. */
    private fun fmt3(value: Float): String = MoneyFormat.price(value, Locale.getDefault())

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
        // [value] en kWh/100 km, montrée dans l'unité choisie (2026-11-10).
        val v = unite.per100FromKm(value)
        val libelle = "kWh/100 ${unite.short}"
        return if (approche) "≈ ${v.roundToInt()} $libelle" else "${fmt(v)} $libelle"
    }

    /** [value] en km, montrée dans l'unité choisie ; en miles, le dixième n'a plus de sens « mesuré ». */
    private fun km(value: Float): String {
        val d = unite.fromKm(value)
        val decimale = if (unite == DistanceUnit.KM) value % 1f != 0f else true
        return if (d < 100f && decimale) "${fmt(d)} ${unite.short}" else "${d.roundToInt()} ${unite.short}"
    }

    /** [kmh] en km/h, montrée dans l'unité choisie : « 52 km/h », « 32 mph ». */
    private fun vitesse(kmh: Float): String = "${unite.speedFromKmh(kmh).toInt()} ${unite.speedShort}"

    private fun kwh(value: Float): String = "${fmt(value)} kWh"

    /**
     * Valeur connue au kWh entier seulement — le compteur de l'écran d'origine, et le moteur qui
     * s'en déduit : un ordre de grandeur, annoncé comme tel — et « moins d'un » plutôt qu'un zéro
     * qui passerait pour une mesure.
     */
    private fun kwhEntier(value: Float): String =
        if (value < 1f) "< 1 kWh" else "≈ ${value.roundToInt()} kWh"

    private fun money(value: Float?, s: StatsSettings): String =
        value?.let { "${MoneyFormat.amount(it, Locale.getDefault())} ${s.currency}" } ?: "—"

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
