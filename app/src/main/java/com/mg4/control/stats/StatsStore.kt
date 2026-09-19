package com.mg4.control.stats

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeSession
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

    data class History(
        val trips: List<Trip> = emptyList(),
        val charges: List<ChargeSession> = emptyList(),
    )

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

        /** Verrou de processus : le service écrit pendant que l'écran lit. */
        private val LOCK = Any()
        private val gson = Gson()
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

    // ── Historique ──────────────────────────────────────────────────────────

    /** Historique purgé de ce qui dépasse la durée de conservation. */
    fun history(): History = synchronized(LOCK) { purged(read()) }

    fun addTrip(trip: Trip) = synchronized(LOCK) {
        val h = read()
        write(h.copy(trips = h.trips + trip))
        AppLogger.i(TAG, "trajet enregistré : ${trip.distanceKm} km · ${trip.energyKwh} kWh")
    }

    fun addCharge(session: ChargeSession) = synchronized(LOCK) {
        val h = read()
        write(h.copy(charges = h.charges + session))
        AppLogger.i(TAG, "charge enregistrée : ${session.energyKwh ?: "?"} kWh · " +
            "${session.socStart ?: "?"} % → ${session.socEnd ?: "?"} %")
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

    private fun read(): History = runCatching {
        if (!file.exists()) return History()
        gson.fromJson(file.readText(), History::class.java) ?: History()
    }.getOrElse {
        // Fichier illisible : on repart d'un historique vide plutôt que de planter l'écran.
        AppLogger.w(TAG, "historique illisible (${it.javaClass.simpleName}) — repart à vide")
        History()
    }

    private fun write(h: History) {
        runCatching {
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(gson.toJson(purged(h)))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { AppLogger.w(TAG, "écriture de l'historique impossible : ${it.message}") }
    }

    private fun purged(h: History): History {
        val limite = System.currentTimeMillis() - settings().retention.days * 86_400_000L
        val trips = h.trips.filter { it.endMs >= limite }
        val charges = h.charges.filter { it.endMs >= limite }
        return if (trips.size == h.trips.size && charges.size == h.charges.size) h
               else History(trips, charges)
    }
}
