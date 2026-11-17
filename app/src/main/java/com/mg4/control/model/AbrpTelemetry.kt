package com.mg4.control.model

import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Envoi des données à ABRP (A Better Routeplanner, issue #188) — la partie sans Android :
 * comptes, contenu d'un relevé, cadence, fichier de comptes. L'envoi lui-même est
 * [com.mg4.control.automation.AbrpStream].
 *
 * API (d'après lkernan/ABRP_Uploader et malys/EVABRPUploader) : un POST vers
 * `https://api.iternio.com/1/tlm/send`, le corps en formulaire — `api_key` (la clé de
 * l'application), `token` (celui de l'utilisateur) et `tlm` (un objet JSON). Seuls `utc` (en
 * SECONDES) et `soc` sont obligatoires ; une valeur illisible est OMISE, jamais envoyée à 0 : un
 * « soc 0 » ou « 0 °C dehors » fausse l'itinéraire d'ABRP.
 */

/**
 * Un compte ABRP : un nom, le jeton de l'utilisateur, et la clé de l'application — vide = la clé de
 * MG4Control, injectée au build. [off] : groupes de données à ne PAS envoyer ([AbrpField]).
 * Enregistré en JSON (Gson) : valeurs par défaut pour relire les anciennes sauvegardes.
 */
data class AbrpAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val token: String = "",
    val apiKey: String = "",
    val off: List<String> = AbrpField.DEFAULT_OFF,
) {
    fun sends(f: AbrpField): Boolean = f == AbrpField.SOC || f.name !in off
}

/** Les groupes de données, une puce chacun dans la fenêtre du compte. Le niveau de batterie est toujours envoyé. */
enum class AbrpField {
    SOC, POWER, SPEED, CHARGING, PARKED, OUTSIDE_TEMP, RANGE, ODOMETER, CAPACITY, HVAC, POSITION, ALTITUDE_HEADING;

    companion object {
        /** Altitude et cap coupés par défaut : ABRP s'en passe, et la position suffit à suivre la voiture. */
        val DEFAULT_OFF = listOf(ALTITUDE_HEADING.name)
    }
}

/** Ce qui a pu être lu sur la voiture pour un relevé. `null` = illisible, donc omis. */
data class AbrpSample(
    val utcSeconds: Long,
    val soc: Float? = null,
    val speedKmh: Float? = null,
    /** kW qui sortent de la batterie (négatif en charge et en récupération), signe d'ABRP. */
    val powerKw: Float? = null,
    val charging: Boolean? = null,
    val dcfc: Boolean? = null,
    val parked: Boolean? = null,
    val outsideTempC: Float? = null,
    val rangeKm: Int? = null,
    val odometerKm: Int? = null,
    val capacityKwh: Float? = null,
    val kwhCharged: Float? = null,
    val hvacSetpointC: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val elevationM: Double? = null,
    val heading: Float? = null,
) {
    /** ABRP refuse (HTTP 400) un relevé sans niveau de batterie : inutile de l'envoyer. */
    val canSend: Boolean get() = soc != null

    /**
     * L'objet `tlm`, en JSON écrit à la main : chiffres en [Locale.US] (une virgule décimale casserait
     * le JSON sur une voiture en français), champs du compte seulement, invraisemblables omis.
     */
    fun json(a: AbrpAccount): String {
        val p = LinkedHashMap<String, String>()
        p["utc"] = utcSeconds.toString()
        soc?.let { p["soc"] = num1(it.toDouble()) }
        if (a.sends(AbrpField.SPEED)) speedKmh?.takeIf { it in 0f..300f }?.let { p["speed"] = it.roundToInt().toString() }
        if (a.sends(AbrpField.POWER)) powerKw?.takeIf { it in -400f..400f }?.let { p["power"] = num2(it.toDouble()) }
        if (a.sends(AbrpField.CHARGING)) {
            charging?.let { p["is_charging"] = if (it) "1" else "0" }
            if (charging == true) dcfc?.let { p["is_dcfc"] = if (it) "1" else "0" }
            if (charging == true) kwhCharged?.takeIf { it >= 0f }?.let { p["kwh_charged"] = num2(it.toDouble()) }
        }
        if (a.sends(AbrpField.PARKED)) parked?.let { p["is_parked"] = if (it) "1" else "0" }
        // 0,0 °C exactement : propriété non renseignée, pas une vraie mesure (voir EVABRPUploader).
        if (a.sends(AbrpField.OUTSIDE_TEMP)) outsideTempC?.takeIf { plausibleTemp(it) }?.let { p["ext_temp"] = it.roundToInt().toString() }
        if (a.sends(AbrpField.RANGE)) rangeKm?.takeIf { it in 1..1500 }?.let { p["est_battery_range"] = it.toString() }
        if (a.sends(AbrpField.ODOMETER)) odometerKm?.takeIf { it > 0 }?.let { p["odometer"] = it.toString() }
        if (a.sends(AbrpField.CAPACITY)) capacityKwh?.takeIf { it in 20f..120f }?.let { cap ->
            p["capacity"] = num2(cap.toDouble())
            soc?.let { s -> p["soe"] = num2(cap * s / 100.0) }
        }
        if (a.sends(AbrpField.HVAC)) hvacSetpointC?.takeIf { plausibleTemp(it) }?.let { p["hvac_setpoint"] = it.roundToInt().toString() }
        if (a.sends(AbrpField.POSITION) && lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
            p["lat"] = String.format(Locale.US, "%.6f", lat)
            p["lon"] = String.format(Locale.US, "%.6f", lon)
            if (a.sends(AbrpField.ALTITUDE_HEADING)) {
                elevationM?.let { p["elevation"] = it.roundToInt().toString() }
                heading?.takeIf { it in 0f..360f }?.let { p["heading"] = it.roundToInt().toString() }
            }
        }
        return p.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    companion object {
        fun plausibleTemp(c: Float): Boolean = c > -50f && c < 80f && c != 0f
        private fun num1(v: Double) = String.format(Locale.US, "%.1f", v)
        private fun num2(v: Double) = String.format(Locale.US, "%.2f", v)
    }
}

/**
 * Cadence de l'envoi continu : rapide en roulant, plus lente en charge, un relevé de maintien à
 * l'arrêt hors charge (0 = aucun). Une transition (passage en P, début ou fin de charge) part tout
 * de suite : c'est exactement ce qu'ABRP doit apprendre sans attendre.
 */
data class AbrpCadence(
    val drivingSeconds: Int = DEFAULT_DRIVING_S,
    val chargingSeconds: Int = DEFAULT_CHARGING_S,
    val parkedMinutes: Int = DEFAULT_PARKED_MIN,
) {
    /** Intervalle dans l'état lu, en ms ; `null` = pas de relevé (à l'arrêt, maintien coupé). État inconnu = en marche. */
    fun intervalMs(parked: Boolean?, charging: Boolean?): Long? = when {
        charging == true -> chargingSeconds * 1000L
        parked == true -> if (parkedMinutes > 0) parkedMinutes * 60_000L else null
        else -> drivingSeconds * 1000L
    }

    fun due(nowMs: Long, lastMs: Long, parked: Boolean?, charging: Boolean?, changed: Boolean): Boolean {
        if (lastMs <= 0L || changed) return true
        val i = intervalMs(parked, charging) ?: return false
        return nowMs - lastMs >= i
    }

    companion object {
        const val DEFAULT_DRIVING_S = 30
        const val DEFAULT_CHARGING_S = 60
        const val DEFAULT_PARKED_MIN = 15
        val DRIVING_CHOICES = listOf(10, 30, 60)
        val CHARGING_CHOICES = listOf(30, 60, 300)
        /** 1 et 5 min ajoutées le 2026-11-09 (issue #232) ; 15 reste le défaut, 0 = jamais. */
        val PARKED_CHOICES = listOf(1, 5, 15, 0)

        /** Un relevé ≈ 1,2 Ko avec l'en-tête HTTPS (mesure d'EVABRPUploader : environ 1 Ko). */
        const val REPORT_BYTES = 1_200

        /** Données par heure, en Ko, à un relevé toutes les [seconds] secondes ; 0 sans relevé. */
        fun kbPerHour(seconds: Int): Int = if (seconds <= 0) 0 else (3600 / seconds) * REPORT_BYTES / 1000
    }
}

/**
 * Fichier de comptes `mg4control_abrp.txt`, pour ne pas taper un long jeton au clavier de la
 * voiture. Une section par compte, `clé = valeur` dessous ; `#` commente ; clés sans casse :
 *
 * ```
 * [Paul]
 * token = 1234abcd-…
 * # api_key = …   (facultatif : sinon la clé de MG4Control)
 * ```
 *
 * Un fichier sans section (celui d'EVABRPUploader) donne un seul compte, nommé [defaultName].
 *
 * Clé API (2026-11-07) : une `api_key` seule avant les sections vaut pour tous les comptes qui
 * n'ont pas la leur ; `api_key = mg4control` remet la clé de MG4Control ([USE_APP_KEY]) ; vide ou
 * absente = le choix actuel du compte est gardé (`null`). Une section sans jeton mais avec une clé
 * met seulement à jour la clé d'un compte existant du même nom.
 */
object AbrpConfigFile {
    const val FILE_NAME = "mg4control_abrp.txt"

    data class Entry(val name: String, val token: String?, val apiKey: String?)

    /** [Entry.apiKey] « reprendre la clé de MG4Control » (`api_key = mg4control`). */
    const val USE_APP_KEY = ""
    private const val MOT_CLE_APPLI = "mg4control"

    fun parse(text: String, defaultName: String = "ABRP"): List<Entry> {
        val out = mutableListOf<Entry>()
        var name: String? = null
        var token: String? = null
        var key: String? = null
        var vu = false
        var commune: String? = null
        fun fermer() {
            // Avant toute section, une clé sans jeton n'est pas un compte : c'est la clé de tous.
            if (name == null && token == null && key != null) commune = key
            else if (vu && (token != null || key != null || (name != null && commune != null)))
                out += Entry((name ?: defaultName).take(MAX_NAME), token, key ?: commune)
            token = null; key = null; vu = false
        }
        for (brute in text.replace("﻿", "").split('\n')) {
            val l = brute.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue
            if (l.startsWith("[") && l.endsWith("]")) {
                fermer()
                name = l.substring(1, l.length - 1).trim().ifEmpty { null }
                vu = true
                continue
            }
            val i = l.indexOf('=')
            if (i <= 0) continue
            val k = l.substring(0, i).trim().lowercase(Locale.US)
            val v = l.substring(i + 1).trim().trim('"').ifEmpty { null } ?: continue
            when (k) {
                "token", "user_token" -> { token = v; vu = true }
                "api_key", "apikey" -> { key = if (v.equals(MOT_CLE_APPLI, ignoreCase = true)) USE_APP_KEY else v; vu = true }
                "name", "nom" -> { name = v; vu = true }
            }
        }
        fermer()
        return out
    }

    /** Le modèle à remplir, écrit sur la clé USB ou dans Téléchargements ([comment] : lignes d'explication, traduites). */
    fun template(comment: List<String>, names: List<String>): String = buildString {
        comment.forEach { append("# ").append(it).append('\n') }
        append('\n')
        append("api_key = \n\n")
        (names.ifEmpty { listOf("ABRP") }).forEach { n ->
            append('[').append(n).append("]\n")
            append("token = \n")
            append("api_key = \n\n")
        }
    }

    const val MAX_NAME = 24
}

/** Ce qu'on peut montrer d'un jeton : ses 4 derniers caractères. */
/**
 * Quels envois écrire dans le journal : une ligne toutes les 30 s noierait le journal (2000 entrées)
 * sur un long trajet. On écrit le premier envoi, chaque passage réussite ↔ échec, chaque changement
 * d'état (garé, en charge), chaque nouvelle erreur, puis un bilan toutes les [SUMMARY_MS].
 * Les durées sont en temps écoulé depuis le démarrage (horloge murale non fiable au réveil).
 */
object AbrpLogPolicy {
    const val SUMMARY_MS = 10 * 60_000L

    fun shouldLog(
        nowMs: Long, lastLogMs: Long?, ok: Boolean, lastOk: Boolean?,
        error: String?, lastError: String?, stateChanged: Boolean,
    ): Boolean = when {
        lastLogMs == null || lastOk == null -> true
        ok != lastOk || stateChanged -> true
        !ok && error != lastError -> true
        else -> nowMs - lastLogMs !in 0 until SUMMARY_MS
    }
}

fun maskSecret(s: String): String =if (s.length <= 4) "••••" else "••••" + s.takeLast(4)
