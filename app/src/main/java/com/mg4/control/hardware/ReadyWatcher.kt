package com.mg4.control.hardware

import android.os.Handler
import android.os.Looper
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.WindowAutoCloseTrigger
import com.mg4.control.util.FirmwareInfo

/**
 * Observateur partagé de l'état READY (`SENSOR_EPTRDY`), lisible sur les 6 firmwares
 * ([MG4Hardware.readEptReadyRaw]). Sur MG4, la voiture sort de READY quand la porte conducteur
 * s'ouvre en P (et à l'extinction) : c'est le signal de « départ du conducteur » là où la porte
 * elle-même n'est pas lisible.
 *
 * Une seule lecture par seconde, quel que soit le nombre d'abonnés (fermeture auto des vitres,
 * baisse de volume) ; la lecture s'arrête sans abonné. Tout se passe sur le fil principal.
 * Journal : tag [TAG], valeur brute à chaque changement.
 */
object ReadyWatcher {

    const val TAG = "MG4_READY"
    private const val TICK_MS = 1_000L

    fun interface Listener {
        /**
         * [firstRead] vrai pour le premier état connu (lecture initiale, ou abonnement alors que l'état
         * est déjà connu) : ce n'est pas une transition, l'abonné décide s'il en tient compte.
         */
        fun onReadyChanged(ready: Boolean, firstRead: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var rawRead = false
    private var lastRaw: Int? = null

    /** Dernier état connu ; null tant que READY n'a pas été lu (ou si la surveillance est arrêtée). */
    @Volatile var ready: Boolean? = null
        private set

    fun add(listener: Listener) {
        main.post {
            if (!listeners.add(listener)) return@post
            ready?.let { listener.onReadyChanged(it, firstRead = true) }
            if (listeners.size == 1) {
                main.post(tick)
                AppLogger.i(TAG, "surveillance READY active (${FirmwareInfo.getGeneration()})")
            }
        }
    }

    fun remove(listener: Listener) {
        main.post {
            if (!listeners.remove(listener) || listeners.isNotEmpty()) return@post
            main.removeCallbacks(tick)
            ready = null
            rawRead = false
            lastRaw = null
            AppLogger.i(TAG, "surveillance READY arrêtée (plus d'abonné)")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) return
            poll()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun poll() {
        val raw = MG4Hardware.readEptReadyRaw()
        if (!rawRead || raw != lastRaw) {
            AppLogger.i(TAG, "READY brut ${if (rawRead) lastRaw ?: "illisible" else "?"} → ${raw ?: "illisible"}")
            rawRead = true
            lastRaw = raw
        }
        // Illisible ou hors 0/1 : on garde le dernier état connu, sans fausse transition.
        val newReady = WindowAutoCloseTrigger.readyFromRaw(raw) ?: return
        val previous = ready
        if (previous == newReady) return
        ready = newReady
        listeners.toList().forEach { it.onReadyChanged(newReady, firstRead = previous == null) }
    }
}
