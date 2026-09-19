package com.mg4.control.hardware

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.StatsTracker
import com.mg4.control.stats.StatsStore

/**
 * Collecteur de l'onglet Statistiques : relève le véhicule à intervalle régulier, confie les
 * instantanés à [StatsTracker] et enregistre ce qui se termine.
 *
 * Tourne dans le service, donc tant que l'application vit — et **seulement si l'utilisateur a
 * activé l'enregistrement**. Sans ce garde-fou, une fonctionnalité de confort se mettrait à écrire
 * l'historique de déplacements de quelqu'un qui ne l'a pas demandé.
 *
 * Aucune écriture véhicule, aucun verrou de réveil : si le boîtier s'endort pendant une charge de
 * nuit, les relevés s'arrêtent avec lui. Ce n'est pas un défaut à corriger ici — l'énergie d'une
 * charge se calcule sur la différence de pourcentage, qui survit au sommeil.
 */
object StatsCollector {

    private const val TAG = "MG4_STATS"

    /** Assez fin pour dater un trajet à la demi-minute, assez lâche pour ne rien coûter. */
    private const val TICK_MS = 30_000L

    private val thread = HandlerThread("mg4-stats").apply { start() }
    private val worker = Handler(thread.looper)

    private var store: StatsStore? = null
    private var tracker: StatsTracker? = null

    @Volatile
    private var running = false

    /** Écoute passive : le relevé lit `ReadyWatcher.ready`, encore faut-il qu'il soit alimenté. */
    private val readyListener = ReadyWatcher.Listener { _, _ -> }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            worker.postDelayed(this, TICK_MS)
        }
    }

    fun startIfEnabled(context: Context) {
        if (StatsStore(context).isEnabled()) start(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        val s = StatsStore(context)
        s.saveSettings(s.settings().copy(enabled = on))
        AppLogger.i(TAG, "enregistrement ${if (on) "activé" else "désactivé"}")
        if (on) start(context) else stop()
    }

    private fun start(context: Context) {
        val app = context.applicationContext
        worker.post {
            if (running) return@post
            running = true
            val s = StatsStore(app)
            store = s
            tracker = StatsTracker(s.settings().capacityKwh)
            ReadyWatcher.add(readyListener)
            worker.post(tick)
            AppLogger.i(TAG, "collecte active (capacité ${s.settings().capacityKwh} kWh)")
        }
    }

    private fun stop() {
        worker.post {
            if (!running) return@post
            running = false
            worker.removeCallbacks(tick)
            ReadyWatcher.remove(readyListener)
            tracker = null
            store = null
            AppLogger.i(TAG, "collecte arrêtée")
        }
    }

    /** Un relevé, sur le fil du collecteur. Toute erreur reste sans conséquence : on réessaie. */
    private fun sample() {
        val s = store ?: return
        val t = tracker ?: return
        runCatching {
            val snapshot = EnergyReader.read()
            t.onSnapshot(snapshot, ReadyWatcher.ready).forEach { event ->
                when (event) {
                    is StatsTracker.Event.TripEnded   -> s.addTrip(event.trip)
                    is StatsTracker.Event.ChargeEnded -> s.addCharge(event.session)
                }
            }
        }.onFailure { AppLogger.w(TAG, "relevé manqué : ${it.javaClass.simpleName} ${it.message}") }
    }
}
