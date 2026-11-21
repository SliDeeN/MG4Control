package com.mg4.control.profile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import java.util.Locale

/**
 * Relevé des alertes de vitesse SWI133 (2026-11-21) : survitesse, changement de limitation et TSR,
 * lus chaque seconde pendant [WINDOW_MS] après l'application d'un profil, fenêtre relancée au
 * passage en READY qui suit. Seuls les changements sont écrits au journal, avec la vitesse et le
 * temps écoulé : de quoi voir quand la voiture rallume d'elle-même la survitesse (diagnostic du
 * 21/11 : relue ON trois minutes après un profil qui l'avait coupée, sans écriture entre-temps).
 * Relevé seulement, aucune écriture.
 */
object SpeedAlertWatch {

    private const val TAG = "MG4_PROFILE"
    const val WINDOW_MS = 5 * 60_000L
    private const val TICK_MS = 1_000L

    /** Un relevé ; null = illisible. */
    data class Snapshot(val overspeed: Boolean?, val tone: Boolean?, val tsr: Boolean?)

    /** Libellés des valeurs qui ont changé entre [before] et [after] (vide : rien). Sans Android. */
    fun changes(before: Snapshot, after: Snapshot): List<String> = buildList {
        if (before.overspeed != after.overspeed) add("survitesse ${txt(before.overspeed)} → ${txt(after.overspeed)}")
        if (before.tone != after.tone) add("changement de limitation ${txt(before.tone)} → ${txt(after.tone)}")
        if (before.tsr != after.tsr) add("TSR ${txt(before.tsr)} → ${txt(after.tsr)}")
    }

    private fun txt(v: Boolean?) = when (v) { true -> "ON"; false -> "OFF"; null -> "illisible" }

    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var last: Snapshot? = null
    private var running = false
    private var listening = false

    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        if (ready && !firstRead) restart("passage en READY")
    }

    /** Lance (ou relance) la fenêtre de relevé. */
    fun start(why: String) { main.post { restart(why) } }

    private fun restart(why: String) {
        startedAt = SystemClock.elapsedRealtime()
        if (!listening) { listening = true; ReadyWatcher.add(readyListener) }
        val s = read()
        AppLogger.i(TAG, "Alertes de vitesse : relevé ${WINDOW_MS / 60_000} min ($why) — " +
            "survitesse ${txt(s.overspeed)} · changement de limitation ${txt(s.tone)} · TSR ${txt(s.tsr)}")
        last = s
        if (!running) { running = true; main.postDelayed(tick, TICK_MS) }
    }

    private fun read() = Snapshot(
        overspeed = runCatching { MG4Hardware.isOverspeedAlarmOn() }.getOrNull(),
        tone = runCatching { MG4Hardware.isSpeedLimitToneOn() }.getOrNull(),
        tsr = runCatching { MG4Hardware.isTsrOn() }.getOrNull(),
    )

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val age = SystemClock.elapsedRealtime() - startedAt
            val now = read()
            val diff = last?.let { changes(it, now) }.orEmpty()
            if (diff.isNotEmpty()) {
                val v = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()
                val vitesse = v?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "vitesse illisible"
                AppLogger.w(TAG, "Alertes de vitesse : ${diff.joinToString(" · ")} " +
                    "(${age / 1000} s après le début du relevé, $vitesse, READY=${ReadyWatcher.ready})")
            }
            last = now
            if (age >= WINDOW_MS) {
                running = false
                if (listening) { listening = false; ReadyWatcher.remove(readyListener) }
                AppLogger.i(TAG, "Alertes de vitesse : fin du relevé — survitesse ${txt(now.overspeed)} · " +
                    "changement de limitation ${txt(now.tone)} · TSR ${txt(now.tsr)}")
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }
}
