package com.mg4.control.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.BatteryHeatingCountdown
import com.mg4.control.model.BatteryHeatingCountdown.Decision
import com.mg4.control.util.LocaleHelper

/**
 * Coupure automatique du chauffage intelligent de la batterie : une fois activé, il ne se coupe
 * jamais de lui-même et coûte de l'autonomie à chaque trajet.
 *
 * L'état est relu toutes les [TICK_MS], en READY ou non, d'où qu'ait été activé le chauffage
 * (écran d'origine, fenêtre « température basse » de SystemUI, MG4Control, raccourci). Le décompte
 * part de l'activation, en roulant ou à l'arrêt, ou du démarrage si le chauffage l'était déjà ; au
 * bout du temps choisi, il est coupé et un message le dit. Le décompte ne vaut que pour le trajet
 * en cours : READY perdu, il est annulé — un chauffage laissé activé attend le démarrage suivant.
 * Règles : [BatteryHeatingCountdown].
 *
 * Pas de verrou « Sécurité conduite » : couper ce chauffage ne change pas le comportement routier,
 * comme pour les sièges chauffants. Journal : [TAG].
 */
object BatteryHeatingAutomation {

    const val TAG = "MG4_BATHEAT"

    /** Rythme de relecture de l'état. */
    private const val TICK_MS = 5_000L
    /** Laisse les services véhicule se lier au démarrage du service. */
    private const val PREMIER_TICK_MS = 4_000L

    @Volatile private var appContext: Context? = null
    /** Échéance du décompte en cours (horloge monotone), pour la carte du tableau de bord. */
    @Volatile private var echeanceMs: Long? = null

    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-batheat").also { it.start() }.looper)
    }
    private val TICK = Any()
    /** Fil de l'automatisme uniquement. */
    private val decompte = BatteryHeatingCountdown()

    private val readyListener = ReadyWatcher.Listener { ready, _ ->
        handler.post {
            if (ready) {
                decompte.startTrip()
            } else {
                if (decompte.endTrip()) AppLogger.i(TAG, "fin du trajet : décompte annulé")
                echeanceMs = null
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        ReadyWatcher.add(readyListener)
        handler.removeCallbacksAndMessages(TICK)
        handler.postDelayed(tick, TICK, PREMIER_TICK_MS)
    }

    /**
     * Une consigne vient de partir (tableau de bord, raccourci) : relecture tout de suite plutôt
     * qu'au prochain passage, pour que le décompte parte de l'activation même.
     */
    fun onCommand() {
        handler.post { verifier() }
    }

    /** Minutes restantes avant la coupure (arrondies au-dessus), ou null sans décompte en cours. */
    fun remainingMinutes(): Int? {
        val fin = echeanceMs ?: return null
        val reste = (fin - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return ((reste + 59_999L) / 60_000L).toInt()
    }

    private val tick = object : Runnable {
        override fun run() {
            verifier()
            handler.postDelayed(this, TICK, TICK_MS)
        }
    }

    /** Un passage, sur le fil de l'automatisme. */
    private fun verifier() {
        val ctx = appContext ?: return
        val cfg = BatteryHeatingSettings.read(ctx)
        if (!cfg.enabled || !MG4Hardware.hasBatteryHeating()) {
            // Option coupée : on ne coupe rien. Réactivée, un chauffage activé repart de zéro.
            if (decompte.reset()) AppLogger.i(TAG, "automatisme désactivé : décompte annulé")
            echeanceMs = null
            return
        }
        val dureeMs = cfg.minutes * 60_000L
        when (decompte.tick(SystemClock.elapsedRealtime(), MG4Hardware.isBatteryHeatingOn(), dureeMs)) {
            Decision.DEBUT  -> AppLogger.i(TAG, "chauffage activé : coupure dans ${cfg.minutes} min")
            Decision.ANNULE -> AppLogger.i(TAG, "chauffage coupé avant l'échéance : décompte annulé")
            Decision.COUPER -> couper(ctx, cfg.minutes)
            Decision.RIEN   -> Unit
        }
        echeanceMs = decompte.echeanceMs(dureeMs)
    }

    /**
     * Coupe, et le dit. Écriture refusée : pas de message ; l'état relu restera « activé » et un
     * nouveau décompte commencera après le délai de grâce.
     */
    private fun couper(ctx: Context, minutes: Int) {
        val ok = MG4Hardware.setBatteryHeating(false)
        AppLogger.i(TAG, "coupure après $minutes min → $ok")
        if (!ok) return
        val texte = LocaleHelper.applyLocale(ctx).getString(R.string.batheat_auto_toast, minutes)
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, texte, Toast.LENGTH_LONG).show() }
        }
    }
}
