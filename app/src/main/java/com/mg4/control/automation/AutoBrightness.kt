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
import kotlin.math.min

/**
 * Luminosité automatique : l'écran prend la luminosité que la courbe de l'utilisateur associe à
 * la lumière extérieure estimée.
 *
 * **Quand :**
 *  - au passage en READY, une fois (4 s après ; démarrage à froid : 15 s) ;
 *  - option « Suivre la lumière en roulant » : à chaque bascule confirmée des feux de position
 *    (tunnel, garage, tombée de la nuit), et dès que la lumière estimée — recalculée chaque minute,
 *    sans réseau — s'écarte de [SEUIL_POINTS] points du dernier réglage ;
 *  - sans ce suivi mais avec les feux : une fois à la sortie du garage, si le READY s'est fait
 *    feux allumés (dans les [SURVEILLANCE_GARAGE_MS] qui suivent) ;
 *  - bouton « Tester maintenant ».
 * Un réglage à la main suspend tout jusqu'au prochain READY.
 *
 * **Comment :** la voiture ne mesure pas la lumière (sonde du 2026-10-01 : `OUTSIDE_AMBIENT_LIGHT`
 * vaut 0 au garage comme au soleil). On l'ESTIME : hauteur du soleil (position GPS + heure), et
 * rayonnement prévu par Open-Meteo, mis en cache trois jours — sans prévision, ciel dégagé. Option
 * « feux » : feux de position allumés = point Nuit, seul signal du véhicule qui suive la lumière.
 * Chaque changement se fait en fondu d'environ une seconde.
 *
 * Journal : [TAG], sans coordonnées, une ligne par changement d'écran.
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
    /**
     * Âge au-delà duquel la prévision est rafraîchie, après le réglage : trois requêtes par jour
     * au plus (~2 Ko chacune). Elle couvre trois jours, rien ne presse.
     */
    private const val RAFRAICHIR_APRES_MS = 8 * 3_600_000L
    /**
     * Après une requête, réussie ou non, pas de nouvelle avant ce délai : un Wi-Fi de garage qui
     * échoue ne doit pas relancer un essai à chaque READY et à chaque bascule des feux.
     */
    private const val REESSAI_APRES_MS = 15 * 60_000L
    private const val JOUR_MS = 24 * 3_600_000L

    /** Rythme du suivi : feux et réglage à la main relus chaque seconde. */
    private const val TICK_MS = 1_000L
    /** Lecture identique exigée deux fois de suite avant d'agir sur une bascule des feux. */
    private const val FEUX_CONFIRMATIONS = 2
    /** La lumière estimée est recalculée chaque minute : calcul local, aucune requête. */
    private const val ESTIMATION_MS = 60_000L
    /**
     * Écart à partir duquel le suivi réajuste l'écran. Simulation du 2026-10-01 (Paris, ciel
     * dégagé) : 8 réglages entre 17 h 30 et 20 h, rapprochés au coucher du soleil, là où un
     * minuteur aurait fait des réglages inutiles l'après-midi et un saut de 24 points à 19 h 15.
     */
    const val SEUIL_POINTS = 10
    /** Sans suivi : fenêtre de la seule sortie de garage. */
    private const val SURVEILLANCE_GARAGE_MS = 10 * 60_000L
    /** Écart au-delà duquel on considère que l'utilisateur a réglé l'écran lui-même. */
    private const val TOLERANCE_MANUELLE = 2
    /** Fondu : durée totale et nombre maximal de pas. */
    private const val FONDU_MS = 1_000L
    private const val FONDU_PAS_MAX = 10

    enum class Source { LIGHTS, FORECAST, SUN }

    data class Result(val source: Source, val lux: Double, val percent: Int)

    /** Résultat, de quoi entretenir le cache, et le détail du calcul pour le journal. */
    private class Calcul(
        val result: Result,
        val lat: Double?,
        val lon: Double?,
        val maintenant: Long?,
        val telechargee: Boolean,
        val detail: String,
    )

    @Volatile private var appContext: Context? = null

    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-autobri").also { it.start() }.looper)
    }
    private val CYCLE = Any()
    private val SUIVI = Any()

    // ── État du trajet : fil de l'automatisme uniquement ─────────────────────
    /** Dernière valeur écrite par l'automatisme, pour reconnaître un réglage à la main. */
    private var dernierApplique: Int? = null
    private var feuxConfirmes: Boolean? = null
    private var feuxLus: Boolean? = null
    private var feuxLectures = 0
    private var prochaineEstimation = 0L
    /** Sans suivi : fin de la surveillance de sortie de garage (0 = aucune). */
    private var finGarage = 0L

    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        // READY perdu ou nouveau READY : le trajet précédent est terminé, réglage en attente compris.
        handler.removeCallbacksAndMessages(SUIVI)
        handler.removeCallbacksAndMessages(CYCLE)
        if (!ready) return@Listener
        // READY déjà là à la première lecture : démarrage à froid (le conducteur a été plus
        // rapide que le boîtier) si celui-ci vient de démarrer — on règle, une fois les services
        // liés. Sinon c'est l'application qui redémarre en roulant (mise à jour) : on ne change
        // pas l'écran sous les yeux du conducteur.
        if (firstRead && SystemClock.elapsedRealtime() > DEMARRAGE_RECENT_MS) return@Listener
        handler.postDelayed({ cycle(test = false) }, CYCLE,
            if (firstRead) DELAI_DEMARRAGE_FROID_MS else DELAI_APRES_READY_MS)
    }

    /**
     * Version en ligne uniquement : la version hors ligne n'a pas accès à Internet, et c'est voulu
     * — la fonctionnalité n'y existe pas du tout, pas même en mode « soleil seul ».
     */
    fun start(context: Context) {
        if (BuildConfig.OFFLINE) return
        appContext = context.applicationContext
        ReadyWatcher.add(readyListener)
    }

    /**
     * Bouton « Tester maintenant » : le même calcul, appliqué tout de suite, option activée ou non.
     * Il lève aussi une pause due à un réglage à la main, et relance le suivi si on roule.
     */
    fun testNow(context: Context, done: (Result?) -> Unit) {
        if (BuildConfig.OFFLINE) {
            done(null)
            return
        }
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
        if (!test) {
            dernierApplique = null
            finGarage = 0L
        }
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AutoBrightnessSettings.KEY_PAUSED, false).apply()

        val calcul = calculer(ctx, cfg.curve, ignorerFeux = !cfg.useLights, attendreReseau = test)
            ?: return null
        regler(ctx, calcul, origine)
        entretenirCache(ctx, calcul)
        if (!test || (cfg.enabled && ReadyWatcher.ready == true)) demarrerSuivi(ctx, cfg, calcul)
        return calcul.result
    }

    // ── Suivi en roulant ─────────────────────────────────────────────────────

    private fun demarrerSuivi(ctx: Context, cfg: AutoBrightnessSettings.Config, calcul: Calcul) {
        handler.removeCallbacksAndMessages(SUIVI)
        feuxConfirmes = if (cfg.useLights) MG4Hardware.isSideLightOn() else null
        feuxLus = feuxConfirmes
        feuxLectures = FEUX_CONFIRMATIONS
        prochaineEstimation = SystemClock.elapsedRealtime() + ESTIMATION_MS
        finGarage = if (!cfg.follow && cfg.useLights && calcul.result.source == Source.LIGHTS)
            SystemClock.elapsedRealtime() + SURVEILLANCE_GARAGE_MS else 0L
        if (cfg.follow || finGarage > 0L) handler.postDelayed(suivi, SUIVI, TICK_MS)
    }

    private val suivi = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val cfg = AutoBrightnessSettings.read(ctx)
            val maintenant = SystemClock.elapsedRealtime()
            val garageSeul = !cfg.follow && finGarage > 0L
            // L'option a pu être coupée en route, ou le Mode Garage activé.
            if (!cfg.enabled || (!cfg.follow && !garageSeul) || GarageMode.isOn(ctx)) return
            if (garageSeul && maintenant > finGarage) {
                AppLogger.i(TAG, "feux toujours allumés après ${SURVEILLANCE_GARAGE_MS / 60_000} min : " +
                    "réglage Nuit conservé")
                return
            }
            if (reglageManuel(ctx)) return

            val bascule = if (cfg.useLights) basculeFeux() else null
            when {
                bascule == true && !garageSeul -> regler(ctx, nuit(cfg.curve), "feux allumés")
                bascule == false -> {
                    calculer(ctx, cfg.curve, ignorerFeux = true, attendreReseau = false)?.let {
                        regler(ctx, it, "feux éteints")
                        entretenirCache(ctx, it)
                    }
                    if (garageSeul) return   // la sortie du garage était la seule chose attendue
                }
                cfg.follow && maintenant >= prochaineEstimation && !(cfg.useLights && feuxConfirmes == true) -> {
                    prochaineEstimation = maintenant + ESTIMATION_MS
                    calculer(ctx, cfg.curve, ignorerFeux = true, attendreReseau = false)?.let { c ->
                        val dernier = dernierApplique
                        if (dernier == null || abs(c.result.percent - dernier) >= SEUIL_POINTS) {
                            regler(ctx, c, "suivi")
                        }
                        entretenirCache(ctx, c)
                    }
                }
            }
            handler.postDelayed(this, SUIVI, TICK_MS)
        }
    }

    /** Bascule CONFIRMÉE des feux depuis la précédente : true = allumés, false = éteints, null = rien. */
    private fun basculeFeux(): Boolean? {
        val lu = MG4Hardware.isSideLightOn() ?: return null
        if (lu != feuxLus) {
            feuxLus = lu
            feuxLectures = 1
        } else {
            feuxLectures++
        }
        if (feuxLectures < FEUX_CONFIRMATIONS || lu == feuxConfirmes) return null
        feuxConfirmes = lu
        return lu
    }

    /**
     * Vrai si l'écran ne montre plus la valeur que l'automatisme a écrite : l'utilisateur a pris
     * la main (popup, réglages d'origine…). Tout s'arrête alors jusqu'au prochain READY.
     */
    private fun reglageManuel(ctx: Context): Boolean {
        val attendu = dernierApplique ?: return false
        val actuel = MG4Hardware.getScreenBrightnessPercent(journal = false)
        if (actuel < 0 || abs(actuel - attendu) <= TOLERANCE_MANUELLE) return false
        AppLogger.i(TAG, "réglage à la main ($attendu → $actuel %) : automatisme suspendu jusqu'au prochain READY")
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AutoBrightnessSettings.KEY_PAUSED, true).apply()
        return true
    }

    // ── Calcul et application ────────────────────────────────────────────────

    private fun nuit(curve: BrightnessCurve) = Calcul(
        Result(Source.LIGHTS, BrightnessCurve.LUX_NIGHT, curve.night),
        lat = null, lon = null, maintenant = null, telechargee = false,
        detail = "feux de position allumés → point Nuit",
    )

    private fun calculer(
        ctx: Context, curve: BrightnessCurve, ignorerFeux: Boolean, attendreReseau: Boolean,
    ): Calcul? {
        val feux = if (ignorerFeux) null else MG4Hardware.isSideLightOn()
        val cache = lireCache(ctx)
        val loc = position(ctx)
        // Sans position, celle de la dernière prévision : la voiture n'a pas bougé depuis l'arrêt.
        val lat = loc?.latitude ?: cache?.latitude
        val lon = loc?.longitude ?: cache?.longitude
        val maintenant = heure(loc)

        if (feux == true) {
            return Calcul(nuit(curve).result, lat, lon, maintenant, telechargee = false,
                detail = "feux de position allumés → point Nuit")
        }
        if (lat == null || lon == null) {
            AppLogger.w(TAG, "position inconnue — aucun réglage")
            return null
        }
        if (maintenant == null) {
            AppLogger.w(TAG, "horloge non synchronisée et aucune heure GPS — aucun réglage")
            return null
        }

        val hauteur = SunPosition.elevationDeg(lat, lon, maintenant)
        // Jamais d'attente réseau avant de régler l'écran : le cache s'il couvre l'instant, sinon
        // le ciel dégagé, et la prévision se télécharge APRÈS, pour la fois suivante. Relevé du
        // 2026-10-01 : 8 s de délai dépassé sur le Wi-Fi d'un garage, pendant lesquelles l'écran
        // restait sombre en plein jour. Seul le bouton « Tester » attend : on l'a demandé.
        var prevision = cache?.takeIf { it.covers(lat, lon, maintenant) }
        var telechargee = false
        if (prevision == null && attendreReseau && reseauDisponible(ctx)) {
            prevision = telecharger(ctx, lat, lon, maintenant)?.also { telechargee = true }
        }
        val ghi = prevision?.ghiAt(maintenant)
        val lux = OutdoorLight.estimateLux(hauteur, ghi)
        val meteo = if (ghi == null) "sans prévision (ciel dégagé supposé)"
                    else String.format(Locale.ROOT, "prévision %.0f W/m²", ghi)
        return Calcul(
            Result(if (ghi != null) Source.FORECAST else Source.SUN, lux, curve.percentFor(lux)),
            lat, lon, maintenant, telechargee,
            String.format(Locale.ROOT, "soleil %.1f° · %s · ≈ %.0f lx", hauteur, meteo, lux),
        )
    }

    /** Écrit le réglage en fondu, puis le mémorise pour la ligne d'état et la détection manuelle. */
    private fun regler(ctx: Context, calcul: Calcul, origine: String) {
        val r = calcul.result
        val de = MG4Hardware.getScreenBrightnessPercent(journal = false)
        val ok = fondu(de, r.percent)
        dernierApplique = r.percent
        prochaineEstimation = SystemClock.elapsedRealtime() + ESTIMATION_MS
        AppLogger.i(TAG, "[$origine] ${calcul.detail} → " +
            (if (de >= 0) "$de → " else "") + "${r.percent} % (${r.source}) → $ok")
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AutoBrightnessSettings.KEY_LAST_AT, calcul.maintenant ?: System.currentTimeMillis())
            .putString(AutoBrightnessSettings.KEY_LAST_SOURCE, r.source.name)
            .putFloat(AutoBrightnessSettings.KEY_LAST_LUX, r.lux.toFloat())
            .putInt(AutoBrightnessSettings.KEY_LAST_PERCENT, r.percent)
            .apply()
    }

    /**
     * De [de] à [vers] en ~1 s, par petits pas : passer de 90 à 15 % d'un coup en entrant dans un
     * tunnel surprend. Les pas intermédiaires ne sont pas journalisés ; la valeur finale l'est.
     */
    private fun fondu(de: Int, vers: Int): Boolean {
        if (de < 0 || de == vers) return MG4Hardware.setScreenBrightnessPercent(vers)
        val ecart = vers - de
        val pas = min(FONDU_PAS_MAX, abs(ecart))
        for (i in 1 until pas) {
            MG4Hardware.setScreenBrightnessPercent(de + ecart * i / pas, journal = false)
            SystemClock.sleep(FONDU_MS / pas)
        }
        return MG4Hardware.setScreenBrightnessPercent(vers)
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
     * Rafraîchit la prévision APRÈS le réglage, pour la fois suivante : quand elle a plus de
     * [RAFRAICHIR_APRES_MS], qu'elle ne couvre plus demain, ou qu'elle vaut pour un autre endroit
     * — et jamais moins de [REESSAI_APRES_MS] après la requête précédente.
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
        if (aJour) return
        val dernierEssai = ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE)
            .getLong(AutoBrightnessSettings.KEY_LAST_FETCH_ATTEMPT, 0L)
        // Écart négatif = horloge revenue en arrière depuis : on ne s'interdit rien.
        if (maintenant - dernierEssai in 0 until REESSAI_APRES_MS) return
        if (!reseauDisponible(ctx)) return
        telecharger(ctx, lat, lon, maintenant)?.let {
            AppLogger.i(TAG, "prévision rafraîchie pour les prochains réglages")
        }
    }

    /** Une requête Open-Meteo, notée réussie ou non pour espacer la suivante. */
    private fun telecharger(ctx: Context, lat: Double, lon: Double, maintenant: Long): SolarForecast? {
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AutoBrightnessSettings.KEY_LAST_FETCH_ATTEMPT, maintenant)
            .apply()
        return OpenMeteoClient.fetch(lat, lon, maintenant)?.also { ecrireCache(ctx, it) }
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
