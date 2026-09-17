package com.mg4.control.hardware

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.WindowAutoCloseTrigger
import com.mg4.control.model.WindowAutoCloseTrigger.Action
import com.mg4.control.util.GarageMode

/**
 * Option « Fermer les vitres en quittant la voiture » : branche [WindowAutoCloseTrigger] (logique
 * de winclose) sur les signaux du véhicule, et lance [PowerWindows.closeAllAutomatically].
 *
 * Signaux : allumage (écouteur Katman5, changements seulement), vitesse (lue chaque seconde tant
 * que le déclencheur n'est pas armé), porte conducteur (watcher DLOCK_DOOR_OPEN_STS, zone 0x1 =
 * avant gauche, conduite à gauche), rapport P (lu seulement à l'ouverture de porte et pendant le
 * délai : sa lecture journalise). Bips pendant le délai, comme winclose.
 *
 * Tout tourne sur le fil principal (écouteurs et tic), l'état du déclencheur n'a donc qu'un fil.
 * Démarré par le service au boot si l'option est active, ou par l'onglet Vitres à l'activation.
 * Respecte le Mode Garage. Journal : tag [PowerWindows.TAG].
 */
object WindowAutoClose {

    private const val TAG = PowerWindows.TAG
    private const val PREFS_NAME = "mg4_settings"
    private const val KEY_ENABLED = "win_autoclose_enabled"

    /** Porte conducteur : avant gauche (DLOCK_DOOR_OPEN_STS areaId 0x1). Conduite à droite non gérée. */
    private const val DRIVER_DOOR_AREA = 0x1
    private const val DOOR_OPEN = 1
    private const val TICK_MS = 1_000L
    private const val BEEP_VOLUME = 80
    private const val BEEP_MS = 150

    enum class Result { PENDING, DONE, PARTIAL, CANCELLED }

    data class Status(
        val enabled: Boolean,
        val armed: Boolean,
        /** null = porte conducteur jamais lue. */
        val driverDoorOpen: Boolean?,
        val lastResult: Result?,
        /** Heure murale du dernier résultat (System.currentTimeMillis). */
        val lastResultAt: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val trigger = WindowAutoCloseTrigger()
    private var running = false
    private var driverDoor: Int? = null
    private var lastResult: Result? = null
    private var lastResultAt = 0L
    private var tone: ToneGenerator? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
        AppLogger.i(TAG, "fermeture auto en quittant : option ${if (on) "activée" else "désactivée"}")
        if (on) start() else stop()
    }

    /** Service, au démarrage : ne s'active que si l'utilisateur a coché l'option. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start()
    }

    fun status(context: Context): Status =
        Status(isEnabled(context), trigger.isArmed, driverDoor?.let { it == DOOR_OPEN }, lastResult, lastResultAt)

    // ── Branchement ─────────────────────────────────────────────────────────

    private val ignitionListener: (Int) -> Unit = { state ->
        when (state) {
            MG4Hardware.CarIgnitionItem.RUN -> {
                trigger.onIgnitionRun(SystemClock.elapsedRealtime())
                AppLogger.i(TAG, "fermeture auto : démarrage → désarmée (il faut rouler pour armer)")
            }
            MG4Hardware.CarIgnitionItem.OFF -> {
                trigger.onIgnitionOff()
                AppLogger.i(TAG, "fermeture auto : extinction (armée=${trigger.isArmed}, attente=${trigger.isPending})")
            }
        }
    }

    private val doorListener: (Int, Int?, Int) -> Unit = { area, previous, value ->
        if (area == DRIVER_DOOR_AREA) {
            driverDoor = value
            // previous null = première lecture, pas un front d'ouverture.
            if (previous != null && previous != DOOR_OPEN && value == DOOR_OPEN) onDriverDoorOpened()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun start() {
        main.post {
            if (running) return@post
            running = true
            PowerWindows.prepare()
            MG4Hardware.registerVehicleConditionListener(ignitionListener)
            MG4Hardware.addDoorListener(doorListener)
            // Les écouteurs ne voient que les changements : voiture déjà en marche = trajet en cours.
            if (MG4Hardware.lastVehicleIgnitionState() == MG4Hardware.CarIgnitionItem.RUN) {
                trigger.onIgnitionRun(SystemClock.elapsedRealtime())
            }
            main.post(tick)
            AppLogger.i(TAG, "fermeture auto : surveillance active (armement ${WindowAutoCloseTrigger.ARM_SPEED_KMH} km/h " +
                "ou ${WindowAutoCloseTrigger.ARM_AFTER_MS / 60_000} min, délai ${trigger.delayMs / 1000} s)")
        }
    }

    private fun stop() {
        main.post {
            if (!running) return@post
            running = false
            main.removeCallbacks(tick)
            MG4Hardware.unregisterVehicleConditionListener(ignitionListener)
            MG4Hardware.removeDoorListener(doorListener)
            if (trigger.cancelPending()) {
                setResult(Result.CANCELLED)
                AppLogger.i(TAG, "fermeture auto : attente abandonnée (option désactivée)")
            }
            stopBeep()
        }
    }

    // ── Décisions ───────────────────────────────────────────────────────────

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val wasArmed = trigger.isArmed
        // Vitesse inutile une fois armé ; rapport lu seulement pendant l'attente (sa lecture journalise).
        val speed = if (wasArmed) null else MG4Hardware.getVehicleSpeedKmh()
        val inPark = if (trigger.isPending) MG4Hardware.isVehicleInPark() else null
        val outcome = trigger.onTick(now, speed, inPark)
        if (!wasArmed && trigger.isArmed) AppLogger.i(TAG, "fermeture auto : armée (${trigger.armReason})")
        when (outcome.action) {
            Action.CLOSE  -> closeAll(outcome.reason)
            Action.CANCEL -> cancelled(outcome.reason)
            else          -> if (trigger.isPending) beep()
        }
    }

    private fun onDriverDoorOpened() {
        val context = MG4Hardware.appContext() ?: return
        if (GarageMode.isOn(context)) {
            AppLogger.i(TAG, "fermeture auto : porte conducteur ouverte, Mode Garage actif → rien")
            return
        }
        val outcome = trigger.onDriverDoorOpened(SystemClock.elapsedRealtime(), MG4Hardware.isVehicleInPark())
        AppLogger.i(TAG, "fermeture auto : porte conducteur ouverte → ${outcome.action} (${outcome.reason})")
        when (outcome.action) {
            Action.SCHEDULE -> {
                setResult(Result.PENDING)
                beep()
            }
            Action.CANCEL -> cancelled(outcome.reason)
            else -> Unit
        }
    }

    private fun cancelled(reason: String) {
        stopBeep()
        setResult(Result.CANCELLED)
        AppLogger.i(TAG, "fermeture auto : annulée ($reason)")
    }

    private fun closeAll(reason: String) {
        stopBeep()
        val context = MG4Hardware.appContext()
        if (context != null && GarageMode.isOn(context)) {
            cancelled("Mode Garage activé pendant le délai")
            return
        }
        AppLogger.i(TAG, "fermeture auto : fermeture de toutes les vitres ($reason)")
        PowerWindows.closeAllAutomatically { ok -> setResult(if (ok) Result.DONE else Result.PARTIAL) }
    }

    private fun setResult(result: Result) {
        lastResult = result
        lastResultAt = System.currentTimeMillis()
    }

    // ── Bips du délai ───────────────────────────────────────────────────────

    private fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        }.onFailure { AppLogger.w(TAG, "fermeture auto : bip impossible (${it.javaClass.simpleName}: ${it.message})") }
    }

    private fun stopBeep() {
        tone?.release()
        tone = null
    }
}
