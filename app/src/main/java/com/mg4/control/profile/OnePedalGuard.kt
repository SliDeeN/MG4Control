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
 */
object OnePedalGuard {

    private const val TAG = "MG4_PROFILE"
    /** Un allumage par le pré-chauffage peut précéder de loin l'arrivée du conducteur. */
    const val WINDOW_MS = 30 * 60_000L
    private const val CHECK_DELAY_MS = 3_000L
    private const val RECHECK_DELAY_MS = 2_000L

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

    private val listener = ReadyWatcher.Listener { ready, _ ->
        if (ready && armedAt != null) planCheck("passage en READY")
    }

    /** Un profil vient d'écrire One Pedal : vérifier au prochain READY. */
    fun arm(profile: String) {
        main.post {
            armedAt = SystemClock.elapsedRealtime()
            profileName = profile
            AppLogger.i(TAG, "  One Pedal : vérification armée pour le passage en READY (profil '$profile')")
            if (!listening) { listening = true; ReadyWatcher.add(listener) }
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
        armedAt = null
        if (listening) { listening = false; ReadyWatcher.remove(listener) }
    }
}
