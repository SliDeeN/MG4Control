package com.mg4.control.automation

import com.google.gson.Gson
import com.mg4.control.BuildConfig
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ForecastGrid
import com.mg4.control.model.OpenMeteoResponse
import com.mg4.control.model.SolarForecast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prévision d'ensoleillement Open-Meteo (open-meteo.com) : gratuite sans clé pour un usage non
 * commercial, données sous licence CC BY 4.0 — d'où la mention dans la carte et le README.
 *
 * On ne demande que le rayonnement global horaire sur trois jours, en temps Unix (aucun fuseau
 * à interpréter), pour les 25 points d'une [ForecastGrid] centrée sur la position arrondie au
 * dixième de degré (~10 km) : assez pour la météo, pas plus. Une seule requête, ~3,5 Ko de
 * réponse compressée (HttpURLConnection demande gzip de lui-même), mais ~10 Ko en tout : la
 * connexion sécurisée est refaite à chaque fois, les requêtes étant espacées d'au moins 15 min
 * (mesuré le 2026-10-02 en TLS 1.2 comme sous Android 9 : 4,5 Ko de poignée de main, certificats
 * compris, plus les en-têtes TCP/IP). Open-Meteo pondère son quota par points × jours / 14 ×
 * variables / 10, soit environ un appel.
 */
object OpenMeteoClient {

    private const val TAG = "MG4_AUTOBRI"
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val JOURS = 3
    private const val TIMEOUT_MS = 8_000

    /**
     * URL de la requête : les coordonnées des points, séparées par des virgules — les seules
     * permises. Chacune est écrite par `Double.toString`, jamais par un formateur : sur la voiture,
     * `String.format(Locale.ROOT, "%.1f")` rend une VIRGULE (repli sur la langue de l'appareil —
     * relevé du 2026-10-01), qui ferait d'une coordonnée deux points. `toString` ne dépend d'aucune
     * langue, et l'arrondi au centième exclut l'écriture scientifique.
     */
    fun url(grid: ForecastGrid): String {
        val points = grid.points()
        return "$BASE?latitude=${points.joinToString(",") { it.first.toString() }}" +
            "&longitude=${points.joinToString(",") { it.second.toString() }}" +
            "&hourly=shortwave_radiation&forecast_days=$JOURS&timeformat=unixtime&timezone=GMT"
    }

    /**
     * Télécharge la prévision de la grille autour de ([lat], [lon]) ; null en cas d'échec
     * (journalisé). Bloquant : hors du fil principal. Jamais dans la variante hors ligne, qui n'a
     * pas la permission réseau et ne doit rien envoyer.
     */
    fun fetch(lat: Double, lon: Double, nowMs: Long): SolarForecast? {
        if (BuildConfig.OFFLINE) return null
        val grille = ForecastGrid.around(lat, lon)
        return try {
            val conn = (URL(url(grille)).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (conn.responseCode != 200) {
                    // Open-Meteo dit pourquoi (quota dépassé, paramètre refusé…) : on le garde.
                    val raison = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
                        .getOrNull()?.take(200) ?: ""
                    AppLogger.w(TAG, "Open-Meteo : réponse ${conn.responseCode} $raison")
                    return null
                }
                val texte = conn.inputStream.bufferedReader().use { it.readText() }
                SolarForecast.fromResponses(lire(texte), grille, nowMs)
                    .also { if (it == null) AppLogger.w(TAG, "Open-Meteo : réponse incomplète") }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Open-Meteo : ${e.javaClass.simpleName} ${e.message ?: ""}")
            null
        }
    }

    /** Plusieurs points : un tableau, une réponse par point dans l'ordre de la requête. */
    fun lire(texte: String): List<OpenMeteoResponse?>? =
        Gson().fromJson(texte, Array<OpenMeteoResponse>::class.java)?.toList()
}
