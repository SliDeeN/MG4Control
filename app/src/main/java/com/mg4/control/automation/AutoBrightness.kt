package com.mg4.control.automation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import com.mg4.control.BuildConfig
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.BrightnessCurve
import com.mg4.control.model.OutdoorLight
import com.mg4.control.model.SolarForecast
import com.mg4.control.model.StatsTracker
import com.mg4.control.model.SunPosition
import com.mg4.control.util.GarageMode
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Luminosité automatique au démarrage : au passage en READY, l'écran prend la luminosité que la
 * courbe de l'utilisateur associe à la lumière extérieure estimée.
 *
 * La voiture ne mesure pas la lumière (sonde du 2026-10-01 : `OUTSIDE_AMBIENT_LIGHT` vaut 0 au
 * garage comme au soleil). On l'ESTIME donc :
 *  - hauteur du soleil, calculée sans réseau d'après la position GPS et l'heure ;
 *  - rayonnement prévu par Open-Meteo, mis en cache trois jours, le réseau n'étant pas toujours
 *    monté au READY. Sans prévision : ciel dégagé supposé.
 *
 * Garde-fou : les feux de position allumés au READY (garage, tunnel, nuit — seul signal du
 * véhicule qui suive la lumière) imposent le point « Nuit ». S'ils s'éteignent dans les
 * [SURVEILLANCE_MS] qui suivent (sortie du garage), le réglage est refait d'après la lumière, une
 * fois — sauf si l'utilisateur a entre-temps changé la luminosité à la main.
 *
 * En dehors de ça, une seule action par démarrage : ensuite la luminosité reste celle de
 * l'utilisateur. Journal : [TAG], sans coordonnées.
 */
object AutoBrightness {

    const val TAG = "MG4_AUTOBRI"

    /** Laisse passer la chaîne de démarrage (profil, popups) avant de toucher à l'écran. */
    private const val DELAI_APRES_READY_MS = 4_000L
    /** Boîtier démarré depuis moins longtemps que ça : un READY déjà présent est un démarrage. */
    private const val DEMARRAGE_RECENT_MS = 3 * 60_000L
    /** Démarrage à froid : le temps que les services véhicule et la luminosité soient liés. */
    private const val DELAI_DEMARRAGE_FROID_MS = 15_000L
    /** Attente maximale d'une position fraîche quand aucune n'est connue. */
    private const val ATTENTE_POSITION_MS = 5_000L
    /** Au-delà, la prévision est rafraîchie après le réglage, pour le démarrage suivant. */
    private const val RAFRAICHIR_APRES_MS = 3 * 3_600_000L
    private const val JOUR_MS = 24 * 3_600_000L
    /** Fenêtre de surveillance des feux après un réglage « feux allumés ». */
    private const val SURVEILLANCE_MS = 10 * 60_000L
    private const val SONDAGE_FEUX_MS = 2_000L
    /** Lectures « feux éteints » consécutives exigées, contre un clignotement des feux auto. */
    private const val FEUX_ETEINTS_CONFIRMES = 2
    /** Écart au-delà duquel on considère que l'utilisateur a réglé l'écran lui-même. */
    private const val TOLERANCE_MANUELLE = 2

    enum class Source { LIGHTS, FORECAST, SUN }

    data class Result(val source: Source, val lux: Double, val percent: Int)

    /** Résultat + de quoi entretenir le cache une fois l'écran réglé. */
    private class Calcul(
        val result: Result,
        val lat: Double?,
        val lon: Double?,
        val maintenant: Long?,
        val telechargee: Boolean,
    )

    @Volatile private var appContext: Context? = null

    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-autobri").also { it.start() }.looper)
    }
    private val CYCLE = Any()
    private val SURVEILLANCE = Any()

    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        handler.removeCallbacksAndMessages(SURVEILLANCE)
        if (!ready) return@Listener
        // READY déjà là à la première lecture : démarrage à froid (le conducteur a été plus
        // rapide que le boîtier) si celui-ci vient de démarrer — on règle, une fois les services
        // liés. Sinon c'est l'application qui redémarre en roulant (mise à jour) : on ne change
        // pas l'écran sous les yeux du conducteur.
        if (firstRead && SystemClock.elapsedRealtime() > DEMARRAGE_RECENT_MS) return@Listener
        handler.removeCallbacksAndMessages(CYCLE)
        handler.postDelayed({ cycle(test = false) }, CYCLE,
            if (firstRead) DELAI_DEMARRAGE_FROID_MS else DELAI_APRES_READY_MS)
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        ReadyWatcher.add(readyListener)
    }

    /** Bouton « Tester maintenant » : le même calcul, appliqué tout de suite, option activée ou non. */
    fun testNow(context: Context, done: (Result?) -> Unit) {
        appContext = context.applicationContext
        handler.post {
            val r = cycle(test = true)
            Handler(Looper.getMainLooper()).post { done(r) }
        }
    }

    private fun cycle(test: Boolean): Result? {
        val ctx = appContext ?: return null
        val cfg = AutoBrightnessSettings.read(ctx)
        if (!test && !cfg.enabled) return null
        if (!MG4Hardware.hasBrightnessControl()) return null
        if (!test && GarageMode.isOn(ctx)) {
            AppLogger.i(TAG, "Mode Garage : aucun réglage")
            return null
        }
        val origine = if (test) "test" else "READY"
        val calcul = calculer(ctx, cfg.curve, origine, ignorerFeux = false) ?: return null
        appliquer(ctx, calcul, origine)
        entretenirCache(ctx, calcul)
        if (calcul.result.source == Source.LIGHTS && !test) surveillerFeux(ctx, cfg.curve, calcul.result.percent)
        return calcul.result
    }

    private fun calculer(ctx: Context, curve: BrightnessCurve, origine: String, ignorerFeux: Boolean): Calcul? {
        val feux = MG4Hardware.isSideLightOn()
        val cache = lireCache(ctx)
        val loc = position(ctx)
        // Sans position, celle de la dernière prévision : la voiture n'a pas bougé depuis l'arrêt.
        val lat = loc?.latitude ?: cache?.latitude
        val lon = loc?.longitude ?: cache?.longitude
        val maintenant = heure(loc)

        if (feux == true && !ignorerFeux) {
            AppLogger.i(TAG, "[$origine] feux de position allumés → point Nuit (${curve.night} %)")
            return Calcul(Result(Source.LIGHTS, BrightnessCurve.LUX_NIGHT, curve.night),
                lat, lon, maintenant, telechargee = false)
        }
        if (lat == null || lon == null) {
            AppLogger.w(TAG, "[$origine] position inconnue — aucun réglage")
            return null
        }
        if (maintenant == null) {
            AppLogger.w(TAG, "[$origine] horloge non synchronisée et aucune heure GPS — aucun réglage")
            return null
        }

        val hauteur = SunPosition.elevationDeg(lat, lon, maintenant)
        var prevision = cache?.takeIf { it.covers(lat, lon, maintenant) }
        var telechargee = false
        if (prevision == null && reseauDisponible(ctx)) {
            prevision = OpenMeteoClient.fetch(lat, lon, maintenant)?.also {
                ecrireCache(ctx, it)
                telechargee = true
            }
        }
        val ghi = prevision?.ghiAt(maintenant)
        val lux = OutdoorLight.estimateLux(hauteur, ghi)
        val result = Result(if (ghi != null) Source.FORECAST else Source.SUN, lux, curve.percentFor(lux))
        val meteo = if (ghi == null) "sans prévision (ciel dégagé supposé)"
                    else String.format(Locale.ROOT, "prévision %.0f W/m² (%s)", ghi,
                        if (telechargee) "téléchargée" else "cache")
        AppLogger.i(TAG, String.format(Locale.ROOT, "[%s] soleil %.1f° · %s · ≈ %.0f lx → %d %%",
            origine, hauteur, meteo, lux, result.percent))
        return Calcul(result, lat, lon, maintenant, telechargee)
    }

    private fun appliquer(ctx: Context, calcul: Calcul, origine: String) {
        val r = calcul.result
        val ok = MG4Hardware.setScreenBrightnessPercent(r.percent)
        AppLogger.i(TAG, "[$origine] luminosité ${r.percent} % (${r.source}) → $ok")
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AutoBrightnessSettings.KEY_LAST_AT, calcul.maintenant ?: System.currentTimeMillis())
            .putString(AutoBrightnessSettings.KEY_LAST_SOURCE, r.source.name)
            .putFloat(AutoBrightnessSettings.KEY_LAST_LUX, r.lux.toFloat())
            .putInt(AutoBrightnessSettings.KEY_LAST_PERCENT, r.percent)
            .apply()
    }

    /**
     * Feux allumés au READY : dès qu'ils s'éteignent (sortie du garage), le réglage est refait
     * d'après la lumière — une seule fois, et pas si l'utilisateur a touché à l'écran entre-temps.
     */
    private fun surveillerFeux(ctx: Context, curve: BrightnessCurve, applique: Int) {
        val fin = SystemClock.elapsedRealtime() + SURVEILLANCE_MS
        val sonde = object : Runnable {
            var eteints = 0
            override fun run() {
                if (SystemClock.elapsedRealtime() > fin) {
                    AppLogger.i(TAG, "feux toujours allumés après ${SURVEILLANCE_MS / 60_000} min : réglage Nuit conservé")
                    return
                }
                eteints = if (MG4Hardware.isSideLightOn() == false) eteints + 1 else 0
                if (eteints < FEUX_ETEINTS_CONFIRMES) {
                    handler.postDelayed(this, SURVEILLANCE, SONDAGE_FEUX_MS)
                    return
                }
                val actuel = MG4Hardware.getScreenBrightnessPercent()
                if (actuel >= 0 && abs(actuel - applique) > TOLERANCE_MANUELLE) {
                    AppLogger.i(TAG, "feux éteints, mais luminosité réglée à la main ($applique → $actuel %) : inchangée")
                    return
                }
                val calcul = calculer(ctx, curve, "sortie", ignorerFeux = true) ?: return
                appliquer(ctx, calcul, "sortie")
            }
        }
        handler.postDelayed(sonde, SURVEILLANCE, SONDAGE_FEUX_MS)
    }

    // ── Position et heure ────────────────────────────────────────────────────

    /**
     * Dernière position connue, sinon une position fraîche. L'application tourne en
     * `android.uid.system` : le contrôle de permission passe sans déclaration au manifeste.
     */
    @SuppressLint("MissingPermission")
    private fun position(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // Toute dernière position convient : à la précision de la météo, une position de la veille
        // reste juste — la voiture n'a pas bougé depuis l'arrêt.
        for (p in listOf("fused", "gps")) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { return it }
        }
        // Aucune : une position fraîche (0,3 s sur SWI133, sonde du 2026-10-01).
        val latch = CountDownLatch(1)
        var recue: Location? = null
        // Les quatre méthodes : sur le framework de la voiture (API 28) elles sont abstraites.
        val ecoute = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recue = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate("gps", ecoute, Looper.getMainLooper())
            latch.await(ATTENTE_POSITION_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "position : ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            runCatching { lm.removeUpdates(ecoute) }
        }
        return recue
    }

    /**
     * L'heure du calcul. Au réveil, le boîtier peut tourner sur son horloge d'usine (2019) le
     * temps de se synchroniser : on prend alors l'heure GPS de la position, avancée du temps écoulé.
     */
    private fun heure(loc: Location?): Long? {
        val horloge = System.currentTimeMillis()
        if (StatsTracker.clockPlausible(horloge)) return horloge
        val l = loc ?: return null
        if (!StatsTracker.clockPlausible(l.time)) return null
        return l.time + (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
    }

    // ── Prévision ────────────────────────────────────────────────────────────

    private fun reseauDisponible(ctx: Context): Boolean {
        if (BuildConfig.OFFLINE) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching {
            val reseau = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(reseau)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }

    /**
     * Rafraîchit la prévision APRÈS le réglage, pour le démarrage suivant : quand elle a plus de
     * trois heures, qu'elle ne couvre plus demain, ou qu'elle vaut pour un autre endroit.
     */
    private fun entretenirCache(ctx: Context, calcul: Calcul) {
        if (BuildConfig.OFFLINE || calcul.telechargee) return
        val lat = calcul.lat ?: return
        val lon = calcul.lon ?: return
        val maintenant = calcul.maintenant ?: return
        val cache = lireCache(ctx)
        val aJour = cache != null &&
            cache.distanceKm(lat, lon) <= SolarForecast.MAX_DISTANCE_KM &&
            maintenant - cache.fetchedAtMs < RAFRAICHIR_APRES_MS &&
            cache.ghiAt(maintenant + JOUR_MS) != null
        if (aJour || !reseauDisponible(ctx)) return
        OpenMeteoClient.fetch(lat, lon, maintenant)?.let {
            ecrireCache(ctx, it)
            AppLogger.i(TAG, "prévision rafraîchie pour les prochains démarrages")
        }
    }

    private fun lireCache(ctx: Context): SolarForecast? {
        val json = ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE)
            .getString(AutoBrightnessSettings.KEY_FORECAST, null) ?: return null
        val prevision = runCatching { Gson().fromJson(json, SolarForecast::class.java) }.getOrNull()
            ?: return null
        // Gson n'appelle pas le constructeur : une entrée abîmée laisserait des listes nulles
        // malgré le typage non nul.
        @Suppress("SENSELESS_COMPARISON")
        val complete = prevision.hoursS != null && prevision.ghi != null
        return prevision.takeIf { complete }
    }

    private fun ecrireCache(ctx: Context, prevision: SolarForecast) {
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AutoBrightnessSettings.KEY_FORECAST, Gson().toJson(prevision))
            .apply()
    }
}
