package com.mg4.control.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Courbe d'un trajet (2026-11-05) : le graphique en barres du détail d'un trajet, avec vitesse et
 * dénivelé en option (maquette B).
 *
 * **Enregistrement** : un point par relevé (toutes les 10 s en roulant), en écarts depuis le point
 * précédent — mètres, Wh nets (négatifs en récupération), secondes — plus l'altitude GPS. Plus fin
 * n'aurait pas de sens : l'énergie est intégrée chaque seconde ([PowerIntegrator]), mais la distance
 * n'est connue qu'à chaque relevé (140 m à 50 km/h, 360 m à 130 km/h). Environ 5 Ko par heure.
 *
 * **Affichage** : les barres sont calculées à la lecture ([bars]), d'une largeur choisie selon la
 * longueur du trajet — une centaine de barres au plus, jamais moins de [MIN_STEP_KM] : un trajet
 * de 3 km en a une quinzaine, 300 km d'autoroute une centaine de 3 km. Un relevé à cheval sur deux
 * barres est partagé au prorata de sa distance.
 *
 * Les tout premiers trajets (format 1, le matin du 2026-11-05) gardaient un point tous les 500 m :
 * ils se relisent comme des relevés de 500 m.
 */
data class CurveSample(
    /** Distance parcourue depuis le point précédent, en mètres. */
    val metres: Int,
    /** Énergie nette depuis le point précédent, en Wh ; négative quand la récupération l'emporte. */
    val wh: Int,
    val seconds: Int,
    /** Altitude GPS au relevé, en mètres ; null sans position ou sans altitude. */
    val altitudeM: Int?,
)

/** Une barre du graphique : la consommation d'un tronçon, sa vitesse moyenne et son altitude. */
data class CurveBar(val kwhPer100: Float, val speedKmh: Float?, val altitudeM: Float?)

/** Les barres d'un trajet : [stepKm] chacune, la dernière éventuellement plus courte (jusqu'à [totalKm]). */
data class CurveBars(val bars: List<CurveBar>, val stepKm: Float, val totalKm: Float)

/**
 * Courbe en cours de construction, gardée dans [PendingTrip]. Les cumuls « comptés » excluent les
 * intervalles non mesurés ; les cumuls « émis » sont ceux déjà écrits, arrondis : chaque écart est
 * pris sur les cumuls pour que les arrondis ne s'additionnent pas.
 */
data class CurveState(
    val points: String = "",
    val count: Int = 0,
    val countedM: Double = 0.0,
    val countedWh: Double = 0.0,
    val countedMs: Long = 0L,
    val emittedM: Int = 0,
    val emittedWh: Int = 0,
    val emittedS: Int = 0,
    /** Dernier relevé pris en compte. */
    val lastKm: Float = 0f,
    val lastNetKwh: Float? = null,
    val lastConsumedKwh: Float? = null,
    val lastMs: Long = 0L,
)

object TripCurve {

    /** Préfixe du format actuel ; sans lui, c'est le format 1 (un point tous les 500 m). */
    private const val V2 = "2|"
    private const val V1_STEP_M = 500

    /** Barres d'au moins 200 m : en dessous, elles seraient calculées entre deux relevés. */
    const val MIN_STEP_KM = 0.2f

    /** Au plus une centaine de barres : au-delà, elles deviennent des traits illisibles. */
    const val MAX_BARS = 120

    /** Largeurs de barre proposées, en km. */
    private val PAS = listOf(0.2f, 0.25f, 0.5f, 1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f)

    /** Au-delà (environ 16 h de route), on cesse d'ajouter des points : l'état en cours reste petit. */
    const val MAX_POINTS = 6000

    /** Seuil du dénivelé : sous 5 m, un écart d'altitude GPS est du bruit, pas une côte. */
    const val ELEVATION_THRESHOLD_M = 5f

    fun start(ms: Long) = CurveState(lastMs = ms)

    /**
     * Un relevé de plus. [km] : distance intégrée depuis le début du trajet ; [consumedKwh] et
     * [regenKwh] : énergie intégrée depuis le début du trajet ; [altM] : altitude GPS, ou null ;
     * [measured] : faux quand l'intervalle n'a pas pu être intégré en distance (vitesse manquante,
     * trou) — il est alors laissé de côté plutôt que de gonfler la consommation.
     */
    fun advance(s: CurveState, km: Float, consumedKwh: Float?, regenKwh: Float?, ms: Long, altM: Float?, measured: Boolean): CurveState {
        consumedKwh ?: return s
        val net = consumedKwh - (regenKwh ?: 0f)
        val dernier = s.copy(lastKm = km, lastNetKwh = net, lastConsumedKwh = consumedKwh, lastMs = ms)
        val lastNet = s.lastNetKwh ?: return dernier
        // Intégration repartie de zéro : l'écart ne veut rien dire.
        val repartie = s.lastConsumedKwh != null && consumedKwh < s.lastConsumedKwh - 0.01f
        val dKm = km - s.lastKm
        val dMs = ms - s.lastMs
        if (!measured || repartie || dKm < 0f || dMs <= 0L || s.count >= MAX_POINTS) return dernier
        val m = s.countedM + dKm * 1000.0
        val wh = s.countedWh + (net - lastNet) * 1000.0
        val t = s.countedMs + dMs
        val em = m.roundToInt()
        val ewh = wh.roundToInt()
        val es = (t / 1000.0).roundToInt()
        val point = StringBuilder(if (s.count == 0) V2 else s.points)
            .append(em - s.emittedM).append(',').append(ewh - s.emittedWh).append(',').append(es - s.emittedS).append(',')
        if (altM != null) point.append(altM.roundToInt())
        point.append(';')
        return dernier.copy(points = point.toString(), count = s.count + 1, countedM = m, countedWh = wh, countedMs = t,
            emittedM = em, emittedWh = ewh, emittedS = es)
    }

    /** Ce qui est gardé dans [Trip.curve] : null sans aucun point. */
    fun finish(s: CurveState?): String? = s?.points?.takeIf { s.count > 0 }

    fun decode(encoded: String?): List<CurveSample> {
        if (encoded.isNullOrEmpty()) return emptyList()
        val v2 = encoded.startsWith(V2)
        return encoded.removePrefix(V2).split(';').mapNotNull { p ->
            if (p.isEmpty()) return@mapNotNull null
            val c = p.split(',')
            if (v2) {
                val m = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val wh = c.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val s = c.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                CurveSample(m, wh, s, c.getOrNull(3)?.toIntOrNull())
            } else {
                val wh = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val s = c.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                CurveSample(V1_STEP_M, wh, s, c.getOrNull(2)?.toIntOrNull())
            }
        }
    }

    /** Un point par relevé, au format actuel (pour les tests et une éventuelle réécriture). */
    fun encode(samples: List<CurveSample>): String = V2 + samples.joinToString("") {
        "${it.metres},${it.wh},${it.seconds},${it.altitudeM?.toString() ?: ""};"
    }

    /** Largeur de barre pour [totalKm] : la plus fine qui tienne en [MAX_BARS], jamais sous [minKm]. */
    fun step(totalKm: Float, minKm: Float = MIN_STEP_KM): Float =
        PAS.firstOrNull { it >= minKm - 1e-4f && totalKm / it <= MAX_BARS } ?: ceil(totalKm / MAX_BARS / 50f) * 50f

    /**
     * Les barres du graphique. Énergie et durée d'un relevé sont réparties entre les barres qu'il
     * couvre, au prorata de la distance ; un relevé à l'arrêt va tout entier à la barre où se trouve
     * la voiture. L'altitude d'une barre est celle de sa fin, interpolée entre les deux relevés qui
     * l'encadrent — sauf s'ils sont à plus de 1,5 km l'un de l'autre (tunnel, GPS perdu) : trou.
     * La dernière barre, plus courte, n'est gardée qu'au-delà de 40 % d'une barre entière.
     */
    fun bars(samples: List<CurveSample>): CurveBars? {
        val totalM = samples.sumOf { it.metres }
        if (totalM <= 0) return null
        val total = totalM / 1000f
        // Format 1 : rien de plus fin que ses 500 m.
        val minimum = if (samples.all { it.metres == V1_STEP_M }) V1_STEP_M / 1000f else MIN_STEP_KM
        val pas = step(total, minimum)
        val pasM = Math.round(pas * 1000f).toDouble()
        val n = ceil(totalM / pasM - 1e-6).toInt().coerceAtLeast(1)
        val wh = DoubleArray(n)
        val sec = DoubleArray(n)
        val altitudes = mutableListOf<Pair<Double, Float>>()
        var d = 0.0
        samples.forEach { s ->
            val fin = d + s.metres
            if (s.metres <= 0) {
                val i = (d / pasM).toInt().coerceIn(0, n - 1)
                wh[i] += s.wh.toDouble()
                sec[i] += s.seconds.toDouble()
            } else {
                var a = d
                while (a < fin - 1e-9) {
                    val i = (a / pasM).toInt().coerceIn(0, n - 1)
                    val b = minOf(fin, (i + 1) * pasM).let { if (i == n - 1) fin else it }
                    val part = (b - a) / s.metres
                    wh[i] += s.wh * part
                    sec[i] += s.seconds * part
                    a = b
                }
            }
            d = fin
            s.altitudeM?.let { altitudes += d to it.toFloat() }
        }
        var barres = (0 until n).map { i ->
            val debut = i * pasM
            val longueur = minOf(pasM, totalM - debut)
            CurveBar(
                kwhPer100 = (wh[i] / longueur * 100.0).toFloat(),
                speedKmh = if (sec[i] > 0.0) (longueur / sec[i] * 3.6).toFloat() else null,
                altitudeM = altitudeA(altitudes, debut + longueur),
            )
        }
        var fin = total
        val reste = totalM - (n - 1) * pasM
        if (n > 1 && reste < 0.4 * pasM) { barres = barres.dropLast(1); fin = ((n - 1) * pasM / 1000.0).toFloat() }
        return CurveBars(barres, pas, fin)
    }

    private fun altitudeA(alt: List<Pair<Double, Float>>, d: Double): Float? {
        if (alt.isEmpty()) return null
        val j = alt.indexOfFirst { it.first >= d - 1e-6 }
        if (j == 0) return alt[0].second.takeIf { alt[0].first - d <= MAX_ALT_GAP_M }
        if (j < 0) return alt.last().second.takeIf { d - alt.last().first <= MAX_ALT_GAP_M }
        val (d0, a0) = alt[j - 1]
        val (d1, a1) = alt[j]
        if (d1 - d0 > MAX_ALT_GAP_M) return null
        return if (d1 - d0 < 1e-6) a1 else (a0 + (a1 - a0) * ((d - d0) / (d1 - d0))).toFloat()
    }

    private const val MAX_ALT_GAP_M = 1500.0

    /**
     * Montée et descente cumulées, en mètres, sur toutes les altitudes relevées, avec un seuil :
     * l'altitude GPS bouge de quelques mètres à plat, additionner ces écarts inventerait des côtes.
     * Null sans au moins deux altitudes.
     */
    fun elevation(samples: List<CurveSample>): Pair<Int, Int>? {
        val alts = samples.mapNotNull { it.altitudeM }
        if (alts.size < 2) return null
        var ref = alts.first().toFloat()
        var plus = 0f
        var moins = 0f
        alts.drop(1).forEach { a ->
            val e = a - ref
            if (abs(e) >= ELEVATION_THRESHOLD_M) {
                if (e > 0) plus += e else moins -= e
                ref = a.toFloat()
            }
        }
        return plus.roundToInt() to moins.roundToInt()
    }
}
