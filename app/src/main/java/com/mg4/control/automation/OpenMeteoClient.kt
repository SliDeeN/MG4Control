package com.mg4.control.automation

import com.google.gson.Gson
import com.mg4.control.BuildConfig
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.OpenMeteoResponse
import com.mg4.control.model.SolarForecast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Prévision d'ensoleillement Open-Meteo (open-meteo.com) : gratuite sans clé pour un usage non
 * commercial, données sous licence CC BY 4.0 — d'où la mention dans la carte et le README.
 *
 * On ne demande que le rayonnement global horaire sur trois jours, en temps Unix (aucun fuseau
 * à interpréter). La position envoyée est arrondie au dixième de degré (~10 km) : assez pour la
 * météo, pas plus.
 */
object OpenMeteoClient {

    private const val TAG = "MG4_AUTOBRI"
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val JOURS = 3
    private const val TIMEOUT_MS = 8_000

    fun arrondi(x: Double): Double = (x * 10.0).roundToLong() / 10.0

    /** URL de la requête, coordonnées au point décimal quelle que soit la langue de l'appareil. */
    fun url(lat: Double, lon: Double): String = String.format(
        Locale.ROOT,
        "%s?latitude=%.1f&longitude=%.1f&hourly=shortwave_radiation&forecast_days=%d" +
            "&timeformat=unixtime&timezone=GMT",
        BASE, arrondi(lat), arrondi(lon), JOURS,
    )

    /**
     * Télécharge la prévision ; null en cas d'échec (journalisé). Bloquant : hors du fil principal.
     * Jamais dans la variante hors ligne, qui n'a pas la permission réseau et ne doit rien envoyer.
     */
    fun fetch(lat: Double, lon: Double, nowMs: Long): SolarForecast? {
        if (BuildConfig.OFFLINE) return null
        val la = arrondi(lat)
        val lo = arrondi(lon)
        return try {
            val conn = (URL(url(la, lo)).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (conn.responseCode != 200) {
                    AppLogger.w(TAG, "Open-Meteo : réponse ${conn.responseCode}")
                    return null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                SolarForecast.fromResponse(Gson().fromJson(texte, OpenMeteoResponse::class.java), la, lo, nowMs)
                    .also { if (it == null) AppLogger.w(TAG, "Open-Meteo : réponse incomplète") }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Open-Meteo : ${e.javaClass.simpleName} ${e.message ?: ""}")
            null
        }
    }
}
