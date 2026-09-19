package com.mg4.control.hardware

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.StatsTracker
import com.mg4.control.model.PendingState
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

    /**
     * Un trajet se termine à l'instant où la voiture quitte READY — et c'est aussi l'instant où le
     * boîtier s'apprête à couper l'application. Attendre le tic suivant, trente secondes plus tard,
     * revenait à jouer le trajet à pile ou face : on relève donc **immédiatement** au changement.
     */
    private val readyListener = ReadyWatcher.Listener { _, _ -> worker.post { sample() } }

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
            val t = StatsTracker(s.settings().capacityKwh)
            tracker = t
            // Ce qui restait ouvert au démarrage précédent se referme ici, avec son dernier relevé
            // connu : c'est ce qui sauve les trajets dont la fin coïncide avec l'extinction.
            val repris = t.recover(s.pending())
            if (repris.isNotEmpty()) {
                AppLogger.i(TAG, "reprise au démarrage : ${repris.size} enregistrement(s) en attente")
                repris.forEach { enregistrer(s, it) }
                s.savePending(null)
            }
            // La voiture annonce sa capacité : meilleure valeur par défaut qu'un nombre écrit
            // en dur, et sans effet si l'utilisateur a déjà réglé la sienne.
            EnergyReader.batteryCapacityKwh()?.let { s.adoptVehicleCapacity(it) }
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
            t.onSnapshot(snapshot, ReadyWatcher.ready).forEach { enregistrer(s, it) }
            // L'état courant est réécrit après CHAQUE relevé : si le boîtier coupe entre deux,
            // le démarrage suivant retrouve le trajet et le clôt à son dernier point connu.
            s.savePending(t.pendingState().takeIf { !it.isEmpty })
        }.onFailure { AppLogger.w(TAG, "relevé manqué : ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun enregistrer(s: StatsStore, event: StatsTracker.Event) = when (event) {
        is StatsTracker.Event.TripEnded   -> s.addTrip(event.trip)
        is StatsTracker.Event.ChargeEnded -> s.addCharge(event.session)
    }
}
