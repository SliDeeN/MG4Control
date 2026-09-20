package com.mg4.control.stats

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeSession
import com.mg4.control.model.LastReading
import com.mg4.control.model.PendingState
import com.mg4.control.model.StatsHistory
import com.mg4.control.model.StatsSettings
import com.mg4.control.model.Trip
import java.io.File

/**
 * Historique des trajets et des charges.
 *
 * Contrairement aux profils, ça se compte en centaines d'entrées : le blob dans les préférences
 * partagées ne convient pas, d'où un fichier dans l'espace privé de l'application. L'écriture passe
 * par un fichier temporaire renommé, pour qu'une coupure d'alimentation en pleine sauvegarde — cas
 * ordinaire dans une voiture — ne laisse jamais un historique tronqué.
 *
 * La purge se fait à la lecture : c'est ce qui rend la durée de conservation réellement effective
 * sans tâche de fond, et le bouton « tout supprimer » n'est alors qu'une suppression de fichier.
 */
class StatsStore(private val context: Context) {

    companion object {
        private const val TAG = "MG4_STATS"
        private const val FILE_NAME = "stats_history.json"
        private const val PREFS = "mg4_stats"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_RETENTION = "retention"
        private const val KEY_PRICE_AC = "price_ac"
        private const val KEY_PRICE_DC = "price_dc"
        private const val KEY_CURRENCY = "currency"
        private const val KEY_CAPACITY = "capacity_kwh"
        private const val KEY_LAST_MS = "last_reading_ms"
        private const val KEY_LAST_SOC = "last_reading_soc"
        private const val KEY_PENDING = "pending_state"
        private const val KEY_CAPACITY_USER = "capacity_user_set"

        /** Verrou de processus : le service écrit pendant que l'écran lit. */
        private val LOCK = Any()
        private val gson = Gson()

        /**
         * Types explicites pour la relecture des deux listes.
         *
         * Indispensable en release : R8 efface le type générique des champs, et Gson rend alors
         * une liste de `LinkedTreeMap` qui explose au premier parcours. Les `TypeToken` anonymes,
         * eux, sont conservés par `proguard-rules.pro` — c'est le motif déjà éprouvé par les
         * profils, et le plantage du 2026-09-19 a montré ce qu'il en coûte de s'en écarter.
         */
        private val TRIPS: java.lang.reflect.Type = object : TypeToken<List<Trip>>() {}.type
        private val CHARGES: java.lang.reflect.Type = object : TypeToken<List<ChargeSession>>() {}.type

        private const val FIELD_TRIPS = "trips"
        private const val FIELD_CHARGES = "charges"

    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val file: File get() = File(context.filesDir, FILE_NAME)

    // ── Réglages ────────────────────────────────────────────────────────────

    fun settings(): StatsSettings = StatsSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        retention = runCatching {
            StatsSettings.Retention.valueOf(
                prefs.getString(KEY_RETENTION, null) ?: StatsSettings.Retention.MONTHS_3.name
            )
        }.getOrDefault(StatsSettings.Retention.MONTHS_3),
        priceAc = prefs.getFloat(KEY_PRICE_AC, 0.187f),
        priceDc = prefs.getFloat(KEY_PRICE_DC, 0.45f),
        currency = prefs.getString(KEY_CURRENCY, "€") ?: "€",
        capacityKwh = prefs.getFloat(KEY_CAPACITY, StatsSettings.DEFAULT_CAPACITY_KWH),
    )

    fun saveSettings(s: StatsSettings) {
        prefs.edit {
            putBoolean(KEY_ENABLED, s.enabled)
            putString(KEY_RETENTION, s.retention.name)
            putFloat(KEY_PRICE_AC, s.priceAc)
            putFloat(KEY_PRICE_DC, s.priceDc)
            putString(KEY_CURRENCY, s.currency)
            putFloat(KEY_CAPACITY, s.capacityKwh)
        }
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Capacité fixée à la main par l'utilisateur : la valeur lue sur le véhicule ne l'écrase alors
     * plus. Sans ce drapeau, une correction faite à l'écran serait effacée au démarrage suivant.
     */
    fun capacityIsUserSet(): Boolean = prefs.getBoolean(KEY_CAPACITY_USER, false)

    fun markCapacityUserSet() = prefs.edit { putBoolean(KEY_CAPACITY_USER, true) }

    /** Adopte la capacité annoncée par le véhicule, sauf si l'utilisateur en a choisi une. */
    fun adoptVehicleCapacity(kwh: Float): Boolean {
        if (capacityIsUserSet()) return false
        val actuelle = settings().capacityKwh
        if (kotlin.math.abs(actuelle - kwh) < 0.05f) return false
        saveSettings(settings().copy(capacityKwh = kwh))
        AppLogger.i(TAG, "capacité batterie lue sur le véhicule : $kwh kWh")
        return true
    }

    // ── Trajet ou charge en cours ───────────────────────────────────────────

    /**
     * L'état en cours va dans les préférences et non dans le fichier d'historique : il est réécrit
     * à chaque relevé, alors que l'historique ne bouge qu'à la fin d'un trajet.
     */
    fun savePending(state: PendingState?) {
        prefs.edit {
            if (state == null || state.isEmpty) remove(KEY_PENDING)
            else putString(KEY_PENDING, gson.toJson(state))
        }
    }

    /**
     * Dernier relevé connu. Deux valeurs simples plutôt qu'un JSON : elles sont écrites après
     * chaque échantillon, autant que ce soit le moins cher possible.
     */
    fun saveLastReading(last: LastReading?) {
        prefs.edit {
            if (last == null) {
                remove(KEY_LAST_MS)
                remove(KEY_LAST_SOC)
            } else {
                putLong(KEY_LAST_MS, last.timestampMs)
                putFloat(KEY_LAST_SOC, last.socPercent)
            }
        }
    }

    fun lastReading(): LastReading? {
        val ms = prefs.getLong(KEY_LAST_MS, 0L)
        val soc = prefs.getFloat(KEY_LAST_SOC, -1f)
        return if (ms > 0L && soc >= 0f) LastReading(ms, soc) else null
    }

    fun pending(): PendingState? = runCatching {
        prefs.getString(KEY_PENDING, null)?.let { gson.fromJson(it, PendingState::class.java) }
    }.getOrNull()

    // ── Historique ──────────────────────────────────────────────────────────

    /** Historique purgé de ce qui dépasse la durée de conservation. */
    fun history(): StatsHistory = synchronized(LOCK) { purged(read()) }

    fun addTrip(trip: Trip) = synchronized(LOCK) {
        val h = read()
        write(h.copy(trips = h.trips + trip))
        // Les deux distances sont journalisées séparément : sur un trajet dont l'odomètre tombe
        // rond, c'est le seul moyen de dire si l'intégration de la vitesse a bien travaillé.
        AppLogger.i(TAG, "trajet enregistré : ${trip.distance} km " +
            "(odomètre ${trip.distanceKm}, intégré ${trip.integratedKm ?: "aucun"}) · " +
            "${trip.netEnergyKwh} kWh · ${trip.outsideTempC ?: "?"} °C")
    }

    fun addCharge(session: ChargeSession) = synchronized(LOCK) {
        val h = read()
        write(h.copy(charges = h.charges + session))
        AppLogger.i(TAG, "charge enregistrée : ${session.energyKwh ?: "?"} kWh · " +
            "${session.socStart ?: "?"} % → ${session.socEnd ?: "?"} %" +
            if (session.reconstructed) " · reconstituée" else "")
    }

    /** Corrige le prix d'une session précise, ou rétablit le tarif par défaut avec `null`. */
    fun overrideTariff(startMs: Long, price: Float?) = synchronized(LOCK) {
        val h = read()
        write(h.copy(charges = h.charges.map {
            if (it.startMs == startMs) it.copy(tariffOverride = price) else it
        }))
    }

    fun clear() = synchronized(LOCK) {
        runCatching { file.delete() }
        AppLogger.i(TAG, "historique supprimé")
    }

    fun sizeBytes(): Long = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)

    // ── Fichier ─────────────────────────────────────────────────────────────

    private fun read(): StatsHistory = runCatching {
        if (!file.exists()) return StatsHistory()
        val racine = JsonParser.parseString(file.readText()).asJsonObject
        StatsHistory(
            trips = gson.fromJson(racine.get(FIELD_TRIPS), TRIPS) ?: emptyList(),
            charges = gson.fromJson(racine.get(FIELD_CHARGES), CHARGES) ?: emptyList(),
        )
    }.getOrElse {
        // Fichier illisible : on repart d'un historique vide plutôt que de planter l'écran.
        AppLogger.w(TAG, "historique illisible (${it.javaClass.simpleName}) — repart à vide")
        StatsHistory()
    }

    private fun write(h: StatsHistory) {
        runCatching {
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(gson.toJson(purged(h)))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { AppLogger.w(TAG, "écriture de l'historique impossible : ${it.message}") }
    }

    private fun purged(h: StatsHistory): StatsHistory {
        val limite = System.currentTimeMillis() - settings().retention.days * 86_400_000L
        val trips = h.trips.filter { it.endMs >= limite }
        val charges = h.charges.filter { it.endMs >= limite }
        return if (trips.size == h.trips.size && charges.size == h.charges.size) h
               else StatsHistory(trips, charges)
    }
}
