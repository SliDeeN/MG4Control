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
import com.mg4.control.model.WindowAutoCloseTrigger.Arming
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode

/**
 * Option « Fermer les vitres en quittant la voiture » : branche [WindowAutoCloseTrigger] (logique
 * de winclose) sur les signaux du véhicule, et lance [PowerWindows.closeAllAutomatically].
 *
 * Signaux, sur le fil principal (l'état du déclencheur n'a donc qu'un fil) : état READY via
 * l'observateur partagé [ReadyWatcher] (6 firmwares — la porte conducteur, elle, ne l'est pas),
 * vitesse lue chaque seconde tant que non armé, rapport P (seulement à la sortie de READY et pendant
 * le délai : sa lecture journalise).
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
    private const val KEY_BEEP = "win_autoclose_beep"
    private const val KEY_BEEP_VOLUME = "win_autoclose_beep_volume"

    private const val TICK_MS = 1_000L

    /** Bip d'avertissement, repris de winclose : un par seconde pendant le délai. */
    private const val BEEP_MS = 150
    /** Volume du [ToneGenerator], en pour cent. Le minimum n'est pas 0 : couper, c'est décocher. */
    const val BEEP_VOLUME_MIN = 10
    const val BEEP_VOLUME_MAX = 100
    const val BEEP_VOLUME_STEP = 5
    const val BEEP_VOLUME_DEFAULT = 80
    /** Bip d'essai hors surveillance : on rend la piste audio peu après. */
    private const val PREVIEW_RELEASE_MS = 2_000L
    /** Marge du verrou de réveil au-dessus du délai réglé (le tic qui échoit vaut une seconde). */
    private const val PENDING_WAKE_MARGIN_MS = 5_000L

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
        val beep: Boolean,
        val beepVolume: Int,
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
    private var lastResult: Result? = null
    private var lastResultAt = 0L
    /** Recopié depuis les réglages : le tic ne doit pas relire les préférences chaque seconde. */
    private var beepOn = false
    private var beepVolume = BEEP_VOLUME_DEFAULT
    private var tone: ToneGenerator? = null

    /**
     * Le délai compte lui aussi : il se déroule après la sortie de READY, et une suspension
     * pendant l'attente repousserait la fermeture au prochain réveil — c'est-à-dire trop tard,
     * l'utilisateur étant parti. Rendu dès que l'attente se termine, quelle qu'en soit l'issue.
     */
    private val pendingWake = WindowWakeLock("MG4Control:vitres-delai")
    /** Volume du générateur en place : il se fixe à la construction, pas à l'appel. */
    private var toneVolume = -1

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
            // Décoché par défaut : le bip est une gêne pour qui n'en veut pas, et la fermeture
            // automatique est déjà une option qu'on active en connaissance de cause.
            beep = p.getBoolean(KEY_BEEP, false),
            beepVolume = p.getInt(KEY_BEEP_VOLUME, BEEP_VOLUME_DEFAULT)
                .coerceIn(BEEP_VOLUME_MIN, BEEP_VOLUME_MAX),
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
            putBoolean(KEY_BEEP, s.beep)
            putInt(KEY_BEEP_VOLUME, s.beepVolume)
        }
        main.post { apply(s) }
        AppLogger.i(TAG, "fermeture auto : réglages = ${describe(s)}")
    }

    /** Service, au démarrage : ne s'active que si l'utilisateur a coché l'option. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context)
    }

    fun status(context: Context): Status =
        Status(isEnabled(context), trigger.isArmed, ReadyWatcher.ready, lastResult, lastResultAt)

    private fun apply(s: Settings) {
        trigger.arming = s.toArming()
        trigger.delayMs = s.delayS * 1000L
        beepOn = s.beep
        beepVolume = s.beepVolume
        if (!beepOn) releaseTone()
    }

    private fun describe(s: Settings): String {
        val parts = listOfNotNull(
            if (s.speedOn) "vitesse ≥ ${s.speedKmh} km/h" else null,
            if (s.timeOn) "READY ≥ ${s.timeMin} min" else null,
        )
        val arming = if (parts.isEmpty()) "aucune condition (jamais armée)"
                     else parts.joinToString(if (s.requireBoth) " ET " else " OU ")
        val bip = if (s.beep) "oui à ${s.beepVolume} %" else "non"
        return "armement $arming, délai ${s.delayS} s, bip $bip"
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
            PowerWindows.prepare()
            ReadyWatcher.add(readyListener)
            main.post(tick)
            AppLogger.i(TAG, "fermeture auto : surveillance active sur ${FirmwareInfo.getGeneration()} (${describe(s)})")
        }
    }

    private fun stop() {
        main.post {
            if (!running) return@post
            running = false
            main.removeCallbacks(tick)
            ReadyWatcher.remove(readyListener)
            releaseTone()
            pendingWake.release()
            if (trigger.cancelPending()) {
                setResult(Result.CANCELLED)
                AppLogger.i(TAG, "fermeture auto : attente abandonnée (option désactivée)")
            }
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val wasArmed = trigger.isArmed
        // Vitesse inutile une fois armé ; rapport lu seulement pendant l'attente (sa lecture journalise).
        val speed = if (wasArmed) null else MG4Hardware.getVehicleSpeedKmh()
        val inPark = if (trigger.isPending) MG4Hardware.isVehicleInPark() else null
        val outcome = trigger.onTick(now, speed, inPark)
        if (!wasArmed && trigger.isArmed) AppLogger.i(TAG, "fermeture auto : armée (${trigger.armReason})")
        handle(outcome)
    }

    /** Changements de READY (valeur brute journalisée par [ReadyWatcher], tag MG4_READY). */
    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        if (!running) return@Listener
        val now = SystemClock.elapsedRealtime()
        when {
            ready -> {
                val outcome = trigger.onReady(now)
                AppLogger.i(TAG, "fermeture auto : passage en READY → ${outcome.action} (${outcome.reason})")
                handle(outcome)
            }
            // Premier état connu hors READY (option activée voiture éteinte) : pas une sortie de READY.
            firstRead -> AppLogger.i(TAG, "fermeture auto : voiture hors READY à l'activation")
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
            Action.SCHEDULE -> {
                setResult(Result.PENDING)
                // Marge sur le délai réglé : l'attente ne doit pas être coupée par une suspension.
                pendingWake.acquire(trigger.delayMs + PENDING_WAKE_MARGIN_MS)
                beep()
            }
            Action.CANCEL   -> {
                setResult(Result.CANCELLED)
                releaseTone()
                pendingWake.release()
                AppLogger.i(TAG, "fermeture auto : annulée (${outcome.reason})")
            }
            Action.CLOSE    -> {
                releaseTone()
                // Les courses prennent le relais avec leur propre verrou.
                pendingWake.release()
                closeAll(outcome.reason)
            }
            // Rien à faire, sauf le bip de chaque seconde d'attente.
            Action.NONE     -> if (trigger.isPending) beep()
        }
    }

    /**
     * Un bip par seconde pendant le délai, comme winclose : le seul avertissement possible pour
     * qui est à côté de la voiture, l'écran ne se voyant pas de l'extérieur. Décoché par défaut.
     * Un échec (piste audio occupée, flux refusé) ne doit jamais empêcher la fermeture.
     */
    private fun beep() {
        if (beepOn) playBeep(beepVolume)
    }

    /**
     * Bip d'essai pendant que l'utilisateur déplace le curseur : régler un volume à l'aveugle
     * n'aurait pas de sens. Muet si l'avertissement est décoché.
     */
    fun previewBeep(context: Context) {
        val s = settings(context)
        if (!s.beep) return
        playBeep(s.beepVolume)
        // Hors surveillance, personne ne viendra rendre la piste audio : on s'en charge.
        if (!running) {
            main.removeCallbacks(releasePreview)
            main.postDelayed(releasePreview, PREVIEW_RELEASE_MS)
        }
    }

    private val releasePreview = Runnable { if (!running) releaseTone() }

    private fun playBeep(volume: Int) {
        runCatching {
            // Le volume est figé à la construction : un réglage déplacé impose un nouveau générateur.
            if (toneVolume != volume) releaseTone()
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
                .also { tone = it; toneVolume = volume }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        }.onFailure { AppLogger.w(TAG, "fermeture auto : bip impossible (${it.javaClass.simpleName}: ${it.message})") }
    }

    /** Le générateur tient une piste audio : on le rend dès que l'attente est finie. */
    private fun releaseTone() {
        tone?.release()
        tone = null
        toneVolume = -1
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
