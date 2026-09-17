package com.mg4.control.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.PowerWindows
import com.mg4.control.model.PowerWindow
import com.mg4.control.model.WindowCommand
import com.mg4.control.model.WindowCommand.Direction
import java.util.Locale

/**
 * Onglet « Vitres » du tableau de bord (V0 de test), sorti de DashboardFragment pour ne pas
 * l'alourdir. Le fragment appelle [bind] une fois, puis [onShown] / [onHidden] selon l'onglet.
 *
 * Boutons de vitre pilotés au toucher : relâché avant [WindowCommand.HOLD_DELAY_MS] = appui
 * court (course auto, ou stop si une course est en cours) ; au-delà = commande manuelle répétée
 * jusqu'au relâché. Le clic (y compris d'accessibilité) porte l'appui court.
 */
class WindowsPanel {

    private class Tile(
        val window: PowerWindow,
        val value: TextView,
        val up: MaterialButton,
        val down: MaterialButton,
        val locked: TextView,
    )

    private val tiles = mutableListOf<Tile>()
    private val rawWindowButtons = linkedMapOf<PowerWindow, MaterialButton>()
    private var rawWindow = PowerWindow.FRONT_RIGHT
    private var root: View? = null
    private var lockStatus: TextView? = null
    private var diagLive: TextView? = null
    private var diagProbe: TextView? = null
    private var shown = false
    private var lastSubscribeTry = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val SUBSCRIBE_RETRY_MS = 10_000L
        const val DIM_ALPHA = 0.35f
    }

    fun bind(view: View) {
        // Le panneau survit à la vue du fragment : une vue recréée ne doit pas s'ajouter à l'ancienne.
        tiles.clear()
        rawWindowButtons.clear()
        root = view
        listOf(
            R.id.win_tile_front_left  to PowerWindow.FRONT_LEFT,
            R.id.win_tile_front_right to PowerWindow.FRONT_RIGHT,
            R.id.win_tile_rear_left   to PowerWindow.REAR_LEFT,
            R.id.win_tile_rear_right  to PowerWindow.REAR_RIGHT,
        ).forEach { (id, window) ->
            // Ids répétés dans les 4 tuiles : toujours chercher DANS la tuile.
            val t = view.findViewById<View>(id)
            t.findViewById<TextView>(R.id.win_tile_name).setText(nameRes(window))
            val tile = Tile(
                window,
                t.findViewById(R.id.win_tile_value),
                t.findViewById(R.id.btn_win_up),
                t.findViewById(R.id.btn_win_down),
                t.findViewById(R.id.win_tile_locked),
            )
            bindPress(tile.up, window, Direction.UP)
            bindPress(tile.down, window, Direction.DOWN)
            tiles += tile
        }

        view.findViewById<MaterialButton>(R.id.btn_win_all_close).setOnClickListener { all(Direction.UP) }
        view.findViewById<MaterialButton>(R.id.btn_win_all_open).setOnClickListener { all(Direction.DOWN) }

        lockStatus = view.findViewById(R.id.win_child_lock_status)
        view.findViewById<Switch>(R.id.switch_win_child_lock).apply {
            isChecked = PowerWindows.isChildLockOn(context)
            setOnCheckedChangeListener { sw, on ->
                lockStatus?.visibility = View.VISIBLE
                lockStatus?.setText(R.string.win_probe_running)
                PowerWindows.setChildLock(sw.context, on) { result -> lockStatus?.text = result }
                applyLockVisuals()
            }
        }

        diagLive = view.findViewById(R.id.win_diag_live)
        diagProbe = view.findViewById(R.id.win_diag_probe)
        view.findViewById<MaterialButton>(R.id.btn_win_probe).setOnClickListener { runProbe("bouton") }

        listOf(
            R.id.btn_win_raw_fl to PowerWindow.FRONT_LEFT,
            R.id.btn_win_raw_fr to PowerWindow.FRONT_RIGHT,
            R.id.btn_win_raw_rl to PowerWindow.REAR_LEFT,
            R.id.btn_win_raw_rr to PowerWindow.REAR_RIGHT,
        ).forEach { (id, window) ->
            val btn = view.findViewById<MaterialButton>(id)
            rawWindowButtons[window] = btn
            btn.setOnClickListener {
                rawWindow = window
                highlightRawWindow()
            }
        }
        listOf(
            R.id.btn_win_raw_0, R.id.btn_win_raw_1, R.id.btn_win_raw_2, R.id.btn_win_raw_3,
            R.id.btn_win_raw_4, R.id.btn_win_raw_5, R.id.btn_win_raw_6, R.id.btn_win_raw_7,
        ).forEachIndexed { value, id ->
            view.findViewById<MaterialButton>(id).setOnClickListener {
                if (refuseIfBlocked(rawWindow)) return@setOnClickListener
                PowerWindows.sendRaw(rawWindow, value)
            }
        }

        highlightRawWindow()
        applyLockVisuals()
    }

    fun onShown() {
        if (shown) return
        shown = true
        applyLockVisuals()
        lastSubscribeTry = SystemClock.elapsedRealtime()
        PowerWindows.ensureSubscribed()
        if (diagProbe?.text.isNullOrEmpty()) runProbe("ouverture onglet")
        refresh()
        handler.postDelayed(poll, POLL_MS)
    }

    /** Onglet quitté ou écran en pause : plus de sondage, et aucune vitre ne reste en mouvement manuel. */
    fun onHidden() {
        if (!shown) return
        shown = false
        handler.removeCallbacksAndMessages(null)
        PowerWindows.stopAllHolds()
    }

    // ── Appui court / long ──────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")   // performClick() appelé au relâché d'un appui court
    private fun bindPress(btn: MaterialButton, window: PowerWindow, direction: Direction) {
        var holdStarted = false
        val startHold = Runnable {
            holdStarted = true
            setHoldHighlight(btn, true)
            PowerWindows.startHold(window, direction)
        }
        btn.setOnClickListener {
            if (refuseIfBlocked(window)) return@setOnClickListener
            PowerWindows.shortPress(window, direction)
        }
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Le ScrollView ne doit pas voler le geste : un maintien annulé coupe la vitre.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    holdStarted = false
                    // Vitre bloquée : pas de maintien ; le relâché passera par le clic, qui explique le refus.
                    if (!isBlocked(window)) handler.postDelayed(startHold, WindowCommand.HOLD_DELAY_MS)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    handler.removeCallbacks(startHold)
                    if (holdStarted) endHold(btn, window) else v.performClick()
                    holdStarted = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(startHold)
                    if (holdStarted) endHold(btn, window)
                    holdStarted = false
                }
            }
            true
        }
    }

    private fun endHold(btn: MaterialButton, window: PowerWindow) {
        setHoldHighlight(btn, false)
        PowerWindows.stopHold(window)
    }

    private fun all(direction: Direction) {
        val ctx = root?.context ?: return
        val lock = PowerWindows.isChildLockOn(ctx)
        val targets = WindowCommand.targetsForAll(lock)
        if (lock) AppLogger.i(PowerWindows.TAG, "tout ${direction.name} : vitres arrière ignorées (sécurité enfant)")
        PowerWindows.autoAll(targets, direction)
    }

    private fun isBlocked(window: PowerWindow): Boolean {
        val ctx = root?.context ?: return true
        return WindowCommand.isBlocked(window, PowerWindows.isChildLockOn(ctx))
    }

    /** Vrai (et l'utilisateur est prévenu) si la sécurité enfant bloque cette vitre. */
    private fun refuseIfBlocked(window: PowerWindow): Boolean {
        if (!isBlocked(window)) return false
        val ctx = root?.context ?: return true
        AppLogger.i(PowerWindows.TAG, "${window.shortName} : commande refusée, sécurité enfant active")
        Toast.makeText(ctx, R.string.win_refused_child_lock, Toast.LENGTH_SHORT).show()
        return true
    }

    // ── Affichage ───────────────────────────────────────────────────────────

    private fun refresh() {
        PowerWindows.refresh { snap ->
            if (!shown) return@refresh
            val ctx = root?.context ?: return@refresh
            tiles.forEach { t ->
                val v = snap.values[t.window]
                val txt = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ctx.getString(R.string.win_value_unknown)
                t.value.text = ctx.getString(R.string.win_value, txt)
            }
            val yes = ctx.getString(R.string.win_yes)
            val no = ctx.getString(R.string.win_no)
            diagLive?.text = listOf(
                ctx.getString(R.string.win_diag_link, if (snap.cpmReady) yes else no, if (snap.subscribed) yes else no),
                ctx.getString(R.string.win_diag_last, snap.lastCommand),
            ).joinToString("\n")
            // Liaison véhicule arrivée après l'ouverture de l'onglet : on retente l'abonnement, sans insister.
            val now = SystemClock.elapsedRealtime()
            if (snap.cpmReady && !snap.subscribed && now - lastSubscribeTry > SUBSCRIBE_RETRY_MS) {
                lastSubscribeTry = now
                PowerWindows.ensureSubscribed()
            }
        }
    }

    private fun runProbe(origin: String) {
        diagProbe?.setText(R.string.win_probe_running)
        PowerWindows.probe(origin) { text -> diagProbe?.text = text }
    }

    private fun applyLockVisuals() {
        val ctx = root?.context ?: return
        val lock = PowerWindows.isChildLockOn(ctx)
        tiles.forEach { t ->
            val blocked = WindowCommand.isBlocked(t.window, lock)
            // Boutons laissés actifs : un appui sur une vitre bloquée doit expliquer le refus.
            t.up.alpha = if (blocked) DIM_ALPHA else 1f
            t.down.alpha = if (blocked) DIM_ALPHA else 1f
            t.locked.visibility = if (blocked) View.VISIBLE else View.GONE
        }
    }

    private fun setHoldHighlight(btn: MaterialButton, on: Boolean) {
        val ctx = btn.context
        btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
        btn.iconTint = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.text_primary))
    }

    private fun highlightRawWindow() {
        rawWindowButtons.forEach { (window, btn) ->
            val on = window == rawWindow
            val ctx = btn.context
            btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
            btn.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
            btn.strokeColor = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
        }
    }

    private fun nameRes(window: PowerWindow): Int = when (window) {
        PowerWindow.FRONT_LEFT  -> R.string.win_front_left
        PowerWindow.FRONT_RIGHT -> R.string.win_front_right
        PowerWindow.REAR_LEFT   -> R.string.win_rear_left
        PowerWindow.REAR_RIGHT  -> R.string.win_rear_right
    }
}
