package com.mg4.control.profile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.RegenLevel

/**
 * One Pedal vérifié au passage en READY (discussion #219, 2026-11-12).
 *
 * Le profil s'applique à l'allumage (IGNITION_RUN + 500 ms), souvent quelques centaines de
 * millisecondes AVANT le READY. Après un pré-chauffage lancé depuis iSmart, la voiture garde le
 * mode de conduite écrit mais ressort sans One Pedal, alors que l'écriture avait été acceptée :
 * tout porte à croire qu'elle le remet à zéro en passant en READY. Un profil qui écrit One Pedal
 * arme donc cette garde : 3 s après le passage en READY (ou après l'application si la voiture y
 * est déjà), One Pedal est relu et, s'il manque, réécrit puis relu encore. Le journal dit ce qui
 * s'est passé — de quoi confirmer la cause au prochain diagnostic.
 *
 * Surveillance courte (issue #277, 2026-11-25, SWI68) : au démarrage à chaud, la voiture annule
 * One Pedal juste après l'écriture, SANS passer en READY. L'armement lance donc aussi une
 * relecture chaque seconde pendant [WATCH_MS] ; One Pedal relu coupé est réécrit, au plus
 * [MAX_WATCH_REWRITES] fois — au-delà, c'est peut-être le conducteur qui l'a coupé depuis l'écran
 * de la voiture : on le laisse faire. La relecture au READY reste en plus.
 */
object OnePedalGuard {

    private const val TAG = "MG4_PROFILE"
    /** Un allumage par le pré-chauffage peut précéder de loin l'arrivée du conducteur. */
    const val WINDOW_MS = 30 * 60_000L
    private const val CHECK_DELAY_MS = 3_000L
    private const val RECHECK_DELAY_MS = 2_000L
    /** Surveillance courte après l'armement (issue #277) : durée, cadence, réécritures permises. */
    const val WATCH_MS = 30_000L
    private const val WATCH_TICK_MS = 1_000L
    const val MAX_WATCH_REWRITES = 2

    /** Ce que fait la surveillance courte à une relecture, sans Android. */
    enum class Watch { CONTINUE, REWRITE, GIVE_UP, END }

    /**
     * [on] l'état relu (`null` : illisible, on attend la relecture suivante), [ageMs] le temps
     * depuis l'armement, [rewrites] les réécritures déjà faites par la surveillance.
     */
    fun watch(on: Boolean?, ageMs: Long, rewrites: Int): Watch = when {
        ageMs > WATCH_MS -> Watch.END
        on != false -> Watch.CONTINUE
        rewrites >= MAX_WATCH_REWRITES -> Watch.GIVE_UP
        else -> Watch.REWRITE
    }

    /** Ce qu'il faut faire à la relecture, sans Android. */
    enum class Decision { OK, REWRITE, EXPIRED }

    /**
     * [on] l'état relu (`null` : illisible), [ageMs] le temps écoulé depuis l'armement. Illisible :
     * on réécrit — écrire One Pedal alors qu'il est déjà actif ne change rien.
     */
    fun decide(on: Boolean?, ageMs: Long, windowMs: Long = WINDOW_MS): Decision = when {
        ageMs > windowMs -> Decision.EXPIRED
        on == true -> Decision.OK
        else -> Decision.REWRITE
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var armedAt: Long? = null
    @Volatile private var profileName: String = ""
    private var listening = false
    private var watchRewrites = 0
    /** Surveillance courte en cours (Handler.hasCallbacks n'existe qu'à partir de l'API 29). */
    private var watching = false

    private val listener = ReadyWatcher.Listener { ready, _ ->
        if (ready && armedAt != null) planCheck("passage en READY")
    }

    /** Un profil vient d'écrire One Pedal : vérifier au prochain READY. */
    fun arm(profile: String) {
        main.post {
            armedAt = SystemClock.elapsedRealtime()
            profileName = profile
            AppLogger.i(TAG, "  One Pedal : vérification armée — relecture chaque seconde pendant ${WATCH_MS / 1000} s, " +
                "puis au passage en READY (profil '$profile')")
            if (!listening) { listening = true; ReadyWatcher.add(listener) }
            // Surveillance courte (issue #277) : la voiture peut l'annuler sans aucun READY.
            watchRewrites = 0
            watching = true
            main.removeCallbacks(watchTick)
            main.postDelayed(watchTick, WATCH_TICK_MS)
            // Déjà en READY (profil choisi en roulant, ou READY lu avant) : vérifier tout de suite.
            if (ReadyWatcher.ready == true) planCheck("déjà en READY")
        }
    }

    /** Un profil sans One Pedal a pris la main : plus rien à vérifier. */
    fun disarm(reason: String) {
        main.post {
            if (armedAt == null) return@post
            AppLogger.i(TAG, "  One Pedal : vérification annulée ($reason)")
            stop()
        }
    }

    /** Contact coupé : la surveillance courte s'arrête (le prochain profil la relancera). */
    fun stopWatch(reason: String) {
        main.post {
            if (!watching) return@post
            watching = false
            main.removeCallbacks(watchTick)
            AppLogger.i(TAG, "One Pedal : surveillance arrêtée ($reason)")
        }
    }

    private val watchTick: Runnable = object : Runnable {
        override fun run() {
            val depuis = armedAt ?: run { watching = false; return }
            val on = runCatching { MG4Hardware.isOnePedalOn() }.getOrNull()
            when (watch(on, SystemClock.elapsedRealtime() - depuis, watchRewrites)) {
                Watch.END -> {
                    AppLogger.i(TAG, "One Pedal : surveillance de ${WATCH_MS / 1000} s terminée " +
                        "(${watchRewrites} réécriture(s), relu ${on ?: "illisible"}, profil '$profileName')")
                    watching = false
                    return
                }
                Watch.GIVE_UP -> {
                    AppLogger.w(TAG, "One Pedal : encore coupé après $MAX_WATCH_REWRITES réécritures — laissé tel quel " +
                        "(peut-être coupé par le conducteur, profil '$profileName')")
                    watching = false
                    return
                }
                Watch.REWRITE -> {
                    watchRewrites++
                    val ok = runCatching { MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL) }.getOrDefault(false)
                    AppLogger.w(TAG, "One Pedal : coupé ${(SystemClock.elapsedRealtime() - depuis) / 1000} s après " +
                        "l'application sans READY — réécrit → $ok ($watchRewrites/$MAX_WATCH_REWRITES, profil '$profileName')")
                }
                Watch.CONTINUE -> Unit
            }
            main.postDelayed(this, WATCH_TICK_MS)
        }
    }

    private fun planCheck(why: String) {
        main.removeCallbacks(check)
        AppLogger.i(TAG, "One Pedal : relecture dans ${CHECK_DELAY_MS / 1000} s ($why)")
        main.postDelayed(check, CHECK_DELAY_MS)
    }

    private val check = Runnable {
        val depuis = armedAt ?: return@Runnable
        val on = runCatching { MG4Hardware.isOnePedalOn() }.getOrNull()
        when (decide(on, SystemClock.elapsedRealtime() - depuis)) {
            Decision.EXPIRED -> AppLogger.i(TAG, "One Pedal : vérification expirée sans READY")
            Decision.OK -> AppLogger.i(TAG, "One Pedal : actif après le READY — rien à faire (profil '$profileName')")
            Decision.REWRITE -> {
                AppLogger.w(TAG, "One Pedal : ${if (on == null) "illisible" else "DÉSACTIVÉ"} après le READY alors que le " +
                    "profil '$profileName' le demande — réécriture")
                val ok = runCatching { MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL) }.getOrDefault(false)
                AppLogger.i(TAG, "One Pedal : réécrit → $ok")
                main.postDelayed({
                    val apres = runCatching { MG4Hardware.isOnePedalOn() }.getOrNull()
                    AppLogger.i(TAG, "One Pedal : relu après réécriture → ${apres ?: "illisible"}")
                }, RECHECK_DELAY_MS)
            }
        }
        stop()
    }

    private fun stop() {
        main.removeCallbacks(check)
        main.removeCallbacks(watchTick)
        watching = false
        armedAt = null
        if (listening) { listening = false; ReadyWatcher.remove(listener) }
    }
}
