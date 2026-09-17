package com.mg4.control.hardware

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.WindowAutoCloseTrigger
import com.mg4.control.model.WindowAutoCloseTrigger.Action
import com.mg4.control.model.WindowAutoCloseTrigger.Arming
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode

/**
 * Option « Fermer les vitres en quittant la voiture » : branche [WindowAutoCloseTrigger] (logique
 * de winclose) sur les signaux du véhicule, et lance [PowerWindows.closeAllAutomatically].
 *
 * Signaux, lus chaque seconde sur le fil principal (l'état du déclencheur n'a donc qu'un fil) :
 * état READY ([MG4Hardware.readEptReadyRaw], disponible sur les 6 firmwares — la porte conducteur,
 * elle, ne l'est pas), vitesse (tant que non armé), rapport P (seulement à la sortie de READY et
 * pendant le délai : sa lecture journalise).
 *
 * Démarré par le service au boot si l'option est active, ou par l'onglet Vitres à l'activation.
 * Respecte le Mode Garage. Journal : tag [PowerWindows.TAG].
 */
object WindowAutoClose {

    private const val TAG = PowerWindows.TAG
    private const val PREFS_NAME = "mg4_settings"
    private const val KEY_ENABLED = "win_autoclose_enabled"
    private const val KEY_SPEED_ON = "win_autoclose_speed_on"
    private const val KEY_SPEED_KMH = "win_autoclose_speed_kmh"
    private const val KEY_TIME_ON = "win_autoclose_time_on"
    private const val KEY_TIME_MIN = "win_autoclose_time_min"
    private const val KEY_BOTH = "win_autoclose_both"
    private const val KEY_DELAY_S = "win_autoclose_delay_s"

    private const val TICK_MS = 1_000L

    enum class Result { PENDING, DONE, PARTIAL, CANCELLED }

    data class Status(
        val enabled: Boolean,
        val armed: Boolean,
        /** null = état READY illisible (ou pas encore lu). */
        val ready: Boolean?,
        val lastResult: Result?,
        /** Heure murale du dernier résultat (System.currentTimeMillis). */
        val lastResultAt: Long,
    )

    /** Réglages tels qu'affichés : valeurs gardées même quand une condition est désactivée. */
    data class Settings(
        val speedOn: Boolean,
        val speedKmh: Int,
        val timeOn: Boolean,
        val timeMin: Int,
        val requireBoth: Boolean,
        val delayS: Int,
    ) {
        fun toArming() = Arming(
            speedKmh = if (speedOn) speedKmh.toFloat() else null,
            afterMs = if (timeOn) timeMin * 60_000L else null,
            requireBoth = requireBoth,
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val trigger = WindowAutoCloseTrigger()
    private var running = false
    private var lastReadyRaw: Int? = null
    private var ready: Boolean? = null
    private var lastResult: Result? = null
    private var lastResultAt = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
        AppLogger.i(TAG, "fermeture auto en quittant : option ${if (on) "activée" else "désactivée"}")
        if (on) start(context) else stop()
    }

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = Arming.DEFAULT
        return Settings(
            speedOn = p.getBoolean(KEY_SPEED_ON, true),
            speedKmh = p.getInt(KEY_SPEED_KMH, default.speedKmh?.toInt() ?: 20)
                .coerceIn(WindowAutoCloseTrigger.SPEED_MIN_KMH, WindowAutoCloseTrigger.SPEED_MAX_KMH),
            timeOn = p.getBoolean(KEY_TIME_ON, true),
            timeMin = p.getInt(KEY_TIME_MIN, ((default.afterMs ?: 300_000L) / 60_000L).toInt())
                .coerceIn(WindowAutoCloseTrigger.TIME_MIN_MIN, WindowAutoCloseTrigger.TIME_MAX_MIN),
            requireBoth = p.getBoolean(KEY_BOTH, false),
            delayS = p.getInt(KEY_DELAY_S, WindowAutoCloseTrigger.DELAY_DEFAULT_S)
                .coerceIn(WindowAutoCloseTrigger.DELAY_MIN_S, WindowAutoCloseTrigger.DELAY_MAX_S),
        )
    }

    /** Enregistre les réglages et les applique au déclencheur (pris en compte au tic suivant). */
    fun saveSettings(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SPEED_ON, s.speedOn)
            putInt(KEY_SPEED_KMH, s.speedKmh)
            putBoolean(KEY_TIME_ON, s.timeOn)
            putInt(KEY_TIME_MIN, s.timeMin)
            putBoolean(KEY_BOTH, s.requireBoth)
            putInt(KEY_DELAY_S, s.delayS)
        }
        main.post { apply(s) }
        AppLogger.i(TAG, "fermeture auto : réglages = ${describe(s)}")
    }

    /** Service, au démarrage : ne s'active que si l'utilisateur a coché l'option. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context)
    }

    fun status(context: Context): Status =
        Status(isEnabled(context), trigger.isArmed, ready, lastResult, lastResultAt)

    private fun apply(s: Settings) {
        trigger.arming = s.toArming()
        trigger.delayMs = s.delayS * 1000L
    }

    private fun describe(s: Settings): String {
        val parts = listOfNotNull(
            if (s.speedOn) "vitesse ≥ ${s.speedKmh} km/h" else null,
            if (s.timeOn) "READY ≥ ${s.timeMin} min" else null,
        )
        val arming = if (parts.isEmpty()) "aucune condition (jamais armée)"
                     else parts.joinToString(if (s.requireBoth) " ET " else " OU ")
        return "armement $arming, délai ${s.delayS} s"
    }

    // ── Surveillance ────────────────────────────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun start(context: Context) {
        val s = settings(context)
        main.post {
            if (running) return@post
            running = true
            apply(s)
            lastReadyRaw = null
            ready = null
            PowerWindows.prepare()
            main.post(tick)
            AppLogger.i(TAG, "fermeture auto : surveillance active sur ${FirmwareInfo.getGeneration()} (${describe(s)})")
        }
    }

    private fun stop() {
        main.post {
            if (!running) return@post
            running = false
            main.removeCallbacks(tick)
            if (trigger.cancelPending()) {
                setResult(Result.CANCELLED)
                AppLogger.i(TAG, "fermeture auto : attente abandonnée (option désactivée)")
            }
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        readReady(now)
        val wasArmed = trigger.isArmed
        // Vitesse inutile une fois armé ; rapport lu seulement pendant l'attente (sa lecture journalise).
        val speed = if (wasArmed) null else MG4Hardware.getVehicleSpeedKmh()
        val inPark = if (trigger.isPending) MG4Hardware.isVehicleInPark() else null
        val outcome = trigger.onTick(now, speed, inPark)
        if (!wasArmed && trigger.isArmed) AppLogger.i(TAG, "fermeture auto : armée (${trigger.armReason})")
        handle(outcome)
    }

    /** Lit READY et transmet ses changements au déclencheur ; la valeur brute est journalisée à chaque changement. */
    private fun readReady(now: Long) {
        val raw = MG4Hardware.readEptReadyRaw()
        if (raw != lastReadyRaw) {
            AppLogger.i(TAG, "fermeture auto : READY brut ${lastReadyRaw ?: "?"} → ${raw ?: "illisible"}")
            lastReadyRaw = raw
        }
        val newReady = WindowAutoCloseTrigger.readyFromRaw(raw) ?: return   // illisible / invalide : on garde l'état connu
        val previous = ready
        ready = newReady
        if (previous == newReady) return
        when {
            newReady -> {
                val outcome = trigger.onReady(now)
                AppLogger.i(TAG, "fermeture auto : passage en READY → ${outcome.action} (${outcome.reason})")
                handle(outcome)
            }
            // Première lecture hors READY (option activée voiture éteinte) : pas une sortie de READY.
            previous == null -> AppLogger.i(TAG, "fermeture auto : voiture hors READY à l'activation")
            else -> onReadyLost(now)
        }
    }

    private fun onReadyLost(now: Long) {
        val context = MG4Hardware.appContext() ?: return
        if (GarageMode.isOn(context)) {
            AppLogger.i(TAG, "fermeture auto : sortie de READY, Mode Garage actif → rien")
            return
        }
        val outcome = trigger.onReadyLost(now, MG4Hardware.isVehicleInPark())
        AppLogger.i(TAG, "fermeture auto : sortie de READY → ${outcome.action} (${outcome.reason})")
        handle(outcome)
    }

    private fun handle(outcome: WindowAutoCloseTrigger.Outcome) {
        when (outcome.action) {
            Action.SCHEDULE -> setResult(Result.PENDING)
            Action.CANCEL   -> {
                setResult(Result.CANCELLED)
                AppLogger.i(TAG, "fermeture auto : annulée (${outcome.reason})")
            }
            Action.CLOSE    -> closeAll(outcome.reason)
            Action.NONE     -> Unit
        }
    }

    private fun closeAll(reason: String) {
        val context = MG4Hardware.appContext()
        if (context != null && GarageMode.isOn(context)) {
            setResult(Result.CANCELLED)
            AppLogger.i(TAG, "fermeture auto : annulée (Mode Garage activé pendant le délai)")
            return
        }
        AppLogger.i(TAG, "fermeture auto : fermeture de toutes les vitres ($reason)")
        PowerWindows.closeAllAutomatically { ok -> setResult(if (ok) Result.DONE else Result.PARTIAL) }
    }

    private fun setResult(result: Result) {
        lastResult = result
        lastResultAt = System.currentTimeMillis()
    }
}
