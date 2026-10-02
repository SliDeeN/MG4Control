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
 * Luminosité automatique : l'écran prend la luminosité qui correspond à l'état des feux ou à la
 * lumière extérieure estimée, selon les sources choisies.
 *
 * **Quand :**
 *  - au passage en READY, une fois (4 s après ; démarrage à froid : 15 s) ;
 *  - option « Ajuster la luminosité pendant la conduite » : à chaque bascule confirmée des feux
 *    (tunnel, garage, tombée de la nuit), et, avec la météo, dès que la lumière estimée —
 *    recalculée chaque minute, sans réseau — s'écarte de [SEUIL_POINTS] points du dernier réglage ;
 *  - sans ce suivi mais avec les feux : une fois à la sortie du garage, si le READY s'est fait
 *    feux allumés (dans les [SURVEILLANCE_GARAGE_MS] qui suivent) ;
 *  - bouton « Tester maintenant ».
 * Un réglage à la main suspend tout jusqu'au prochain READY.
 *
 * **Sources** (au moins une) :
 *  - **Feux seuls** — la version hors ligne, et la version en ligne par défaut : ni position, ni
 *    réseau, ni courbe. Feux éteints = niveau « éteints », feux allumés = niveau « allumés ».
 *  - **Météo** — version en ligne, case à cocher, car elle consomme un peu de données : la voiture
 *    ne mesure pas la lumière (sonde du 2026-10-01 : `OUTSIDE_AMBIENT_LIGHT` vaut 0 au garage comme
 *    au soleil), on l'ESTIME : hauteur du soleil (position GPS + heure), et rayonnement prévu par
 *    Open-Meteo sur une grille de 25 points autour de la voiture (≈ 60 × 60 km), mis en cache trois
 *    jours — en roulant, chaque calcul prend la météo de l'endroit où l'on est ; sans prévision,
 *    ciel dégagé. Avec les feux en plus : feux de position allumés = point Nuit de la courbe.
 * Les feux de position sont le seul signal du véhicule qui suive la lumière. Chaque changement se
 * fait en fondu d'environ une seconde.
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
     * au plus sans grand trajet (~5 Ko chacune). Elle couvre trois jours, rien ne presse.
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

    /** [LIGHTS_OFF] : mode feux seuls, feux éteints. */
    enum class Source { LIGHTS, FORECAST, SUN, LIGHTS_OFF }

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

    /** Les deux variantes : la version hors ligne n'a que les feux (voir l'en-tête). */
    fun start(context: Context) {
        appContext = context.applicationContext
        ReadyWatcher.add(readyListener)
    }

    /**
     * Bouton « Tester maintenant » : le même calcul, appliqué tout de suite, option activée ou non.
     * Il lève aussi une pause due à un réglage à la main, et relance le suivi si on roule.
     */
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
        if (!test) {
            dernierApplique = null
            finGarage = 0L
        }
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AutoBrightnessSettings.KEY_PAUSED, false).apply()

        val calcul = (if (!cfg.useForecast) feuxSeuls(cfg, MG4Hardware.isSideLightOn())
                      else calculer(ctx, cfg.curve, ignorerFeux = !cfg.useLights, attendreReseau = test))
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
                bascule == true && !garageSeul -> regler(ctx, cibleFeuxAllumes(cfg), "feux allumés")
                bascule == false -> {
                    cibleFeuxEteints(ctx, cfg)?.let {
                        regler(ctx, it, "feux éteints")
                        entretenirCache(ctx, it)
                    }
                    if (garageSeul) return   // la sortie du garage était la seule chose attendue
                }
                cfg.useForecast && cfg.follow && maintenant >= prochaineEstimation &&
                    !(cfg.useLights && feuxConfirmes == true) -> {
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

    /** Cible quand les feux s'allument : point Nuit avec la météo, niveau « allumés » en feux seuls. */
    private fun cibleFeuxAllumes(cfg: AutoBrightnessSettings.Config): Calcul =
        if (!cfg.useForecast) feuxSeuls(cfg, allumes = true)!! else nuit(cfg.curve)

    /** Cible quand les feux s'éteignent : lumière estimée avec la météo, niveau « éteints » en feux seuls. */
    private fun cibleFeuxEteints(ctx: Context, cfg: AutoBrightnessSettings.Config): Calcul? =
        if (!cfg.useForecast) feuxSeuls(cfg, allumes = false)
        else calculer(ctx, cfg.curve, ignorerFeux = true, attendreReseau = false)

    /**
     * Mode feux seuls : le niveau choisi pour l'état des feux. Feux illisibles → aucun réglage ;
     * si le suivi tourne, la première lecture confirmée rattrapera.
     */
    private fun feuxSeuls(cfg: AutoBrightnessSettings.Config, allumes: Boolean?): Calcul? {
        if (allumes == null) {
            AppLogger.w(TAG, "feux illisibles — aucun réglage")
            return null
        }
        val pct = if (allumes) cfg.lightsOnPercent else cfg.lightsOffPercent
        return Calcul(
            Result(if (allumes) Source.LIGHTS else Source.LIGHTS_OFF, 0.0, pct),
            lat = null, lon = null, maintenant = null, telechargee = false,
            detail = if (allumes) "feux allumés" else "feux éteints",
        )
    }

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
        // Sans position, le centre de la dernière grille : la voiture n'a pas bougé depuis l'arrêt.
        val lat = loc?.latitude ?: cache?.grid?.latitude
        val lon = loc?.longitude ?: cache?.grid?.longitude
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
        val ghi = prevision?.ghiAt(lat, lon, maintenant)
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
     * Rafraîchit la prévision APRÈS le réglage, pour la fois suivante : quand la voiture sort de
     * la zone (plus de [SolarForecast.MAX_DISTANCE_KM] du centre de la grille), que la prévision a
     * plus de [RAFRAICHIR_APRES_MS] ou ne couvre plus demain — et jamais moins de
     * [REESSAI_APRES_MS] après la requête précédente. La nouvelle grille est centrée sur la voiture.
     */
    private fun entretenirCache(ctx: Context, calcul: Calcul) {
        // Sans la case météo, aucune requête : c'est la promesse « aucune donnée mobile ».
        if (calcul.telechargee || !AutoBrightnessSettings.read(ctx).useForecast) return
        val lat = calcul.lat ?: return
        val lon = calcul.lon ?: return
        val maintenant = calcul.maintenant ?: return
        val cache = lireCache(ctx)
        val raison = when {
            cache == null -> "aucune prévision"
            cache.distanceKm(lat, lon) > SolarForecast.MAX_DISTANCE_KM -> String.format(Locale.ROOT,
                "sortie de la zone, %.0f km du centre", cache.distanceKm(lat, lon))
            maintenant - cache.fetchedAtMs >= RAFRAICHIR_APRES_MS ->
                "plus de ${RAFRAICHIR_APRES_MS / 3_600_000L} h"
            cache.ghiAt(lat, lon, maintenant + JOUR_MS) == null -> "ne couvre plus demain"
            else -> return
        }
        val dernierEssai = ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE)
            .getLong(AutoBrightnessSettings.KEY_LAST_FETCH_ATTEMPT, 0L)
        // Écart négatif = horloge revenue en arrière depuis : on ne s'interdit rien.
        if (maintenant - dernierEssai in 0 until REESSAI_APRES_MS) return
        if (!reseauDisponible(ctx)) return
        AppLogger.i(TAG, "prévision à rafraîchir pour les prochains réglages ($raison)")
        telecharger(ctx, lat, lon, maintenant)
    }

    /**
     * Une requête Open-Meteo, notée réussie ou non pour espacer la suivante. Le journal donne
     * l'écart de rayonnement entre les points de la zone à cet instant : ce que la grille apporte
     * sur un point unique (rien sous un ciel uniforme).
     */
    private fun telecharger(ctx: Context, lat: Double, lon: Double, maintenant: Long): SolarForecast? {
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AutoBrightnessSettings.KEY_LAST_FETCH_ATTEMPT, maintenant)
            .apply()
        val prevision = OpenMeteoClient.fetch(lat, lon, maintenant) ?: return null
        ecrireCache(ctx, prevision)
        val valeurs = prevision.ghiOfPointsAt(maintenant).filterNotNull()
        AppLogger.i(TAG, if (valeurs.isEmpty()) "prévision téléchargée" else String.format(Locale.ROOT,
            "prévision téléchargée : de %.0f à %.0f W/m² selon les %d points de la zone",
            valeurs.min(), valeurs.max(), valeurs.size))
        return prevision
    }

    /** Illisible, incomplète ou de l'ancien format à un seul point : comme absente, la prochaine requête la remplace. */
    private fun lireCache(ctx: Context): SolarForecast? {
        val json = ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE)
            .getString(AutoBrightnessSettings.KEY_FORECAST, null) ?: return null
        return SolarForecast.fromJson(json)
    }

    private fun ecrireCache(ctx: Context, prevision: SolarForecast) {
        ctx.getSharedPreferences(AutoBrightnessSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AutoBrightnessSettings.KEY_FORECAST, prevision.toJson())
            .apply()
    }
}
