package com.mg4.control.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.PowerWindows
import com.mg4.control.model.PowerWindow
import com.mg4.control.model.WindowCalibration
import com.mg4.control.model.WindowCommand.Direction
import java.util.Locale

/**
 * Carte « Calibration des vitres » de l'onglet Vitres : état de chaque vitre et assistant en
 * quatre étapes — fermeture auto, descente chronométrée au doigt, montée chronométrée au doigt,
 * résultat à enregistrer. Les vitres avec capteur (conducteur) n'en ont pas besoin.
 *
 * Mesure au doigt : le chrono part au toucher et s'arrête au relâché, pendant que l'app envoie la
 * commande manuelle répétée. Les délais de transmission, identiques au départ et à l'arrêt,
 * s'annulent ; reste le temps de réaction de l'utilisateur, visible au résultat.
 */
class WindowCalibrationPanel {

    private enum class Step(val number: Int) { CLOSE(1), CLOSING(1), MEASURE_DOWN(2), MEASURE_UP(3), RESULT(4) }

    private class Row(val window: PowerWindow, val status: TextView, val start: MaterialButton)

    private val rows = mutableListOf<Row>()
    private var root: View? = null
    private var wizard: View? = null
    private var title: TextView? = null
    private var stepText: TextView? = null
    private var instruction: TextView? = null
    private var warning: TextView? = null
    private var timer: TextView? = null
    private var action: MaterialButton? = null
    private var cancel: MaterialButton? = null
    private var redo: MaterialButton? = null
    private var save: MaterialButton? = null

    /** Prévenu après un enregistrement : la fermeture automatique en dépend pour se déverrouiller. */
    var onCalibrationSaved: (() -> Unit)? = null

    private var window: PowerWindow? = null
    private var step = Step.CLOSE
    private var downMs = 0L
    private var upMs = 0L
    private var pressStartMs = 0L
    private var measuring = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            timer?.text = seconds(SystemClock.elapsedRealtime() - pressStartMs)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 100L
    }

    fun bind(view: View) {
        rows.clear()
        root = view
        listOf(
            R.id.win_cal_row_front_left  to PowerWindow.FRONT_LEFT,
            R.id.win_cal_row_front_right to PowerWindow.FRONT_RIGHT,
            R.id.win_cal_row_rear_left   to PowerWindow.REAR_LEFT,
            R.id.win_cal_row_rear_right  to PowerWindow.REAR_RIGHT,
        ).forEach { (id, w) ->
            // Ids répétés dans les 4 lignes : toujours chercher DANS la ligne.
            val r = view.findViewById<View>(id)
            r.findViewById<TextView>(R.id.win_cal_row_name).setText(WindowsPanel.nameRes(w))
            val row = Row(w, r.findViewById(R.id.win_cal_row_status), r.findViewById(R.id.btn_win_cal_row_start))
            row.start.setOnClickListener { startWizard(w) }
            rows += row
        }

        wizard = view.findViewById(R.id.win_cal_wizard)
        title = view.findViewById(R.id.win_cal_wizard_title)
        stepText = view.findViewById(R.id.win_cal_wizard_step)
        instruction = view.findViewById(R.id.win_cal_wizard_instruction)
        warning = view.findViewById(R.id.win_cal_wizard_warning)
        timer = view.findViewById(R.id.win_cal_wizard_timer)
        cancel = view.findViewById<MaterialButton>(R.id.btn_win_cal_cancel).apply { setOnClickListener { closeWizard("annulée") } }
        redo = view.findViewById<MaterialButton>(R.id.btn_win_cal_redo).apply { setOnClickListener { restart(null) } }
        save = view.findViewById<MaterialButton>(R.id.btn_win_cal_save).apply { setOnClickListener { saveResult() } }
        action = view.findViewById<MaterialButton>(R.id.btn_win_cal_action).apply {
            setOnClickListener { onAction() }
            bindMeasureTouch(this)
        }
        refreshRows()
    }

    fun refreshRows() {
        val ctx = root?.context ?: return
        rows.forEach { row ->
            if (row.window.hasPositionSensor) {
                row.status.setText(R.string.win_cal_sensor)
                row.start.visibility = View.GONE
                return@forEach
            }
            val cal = PowerWindows.calibration(ctx, row.window)
            row.status.text = cal?.let { ctx.getString(R.string.win_cal_done, seconds(it.downMs), seconds(it.upMs)) }
                ?: ctx.getString(R.string.win_cal_none)
            row.start.setText(if (cal != null) R.string.win_cal_redo_row else R.string.win_cal_start)
        }
    }

    /** Onglet quitté : une mesure en cours n'a plus de sens, l'assistant est refermé. */
    fun onHidden() {
        if (window != null) closeWizard("onglet quitté")
    }

    // ── Assistant ───────────────────────────────────────────────────────────

    private fun startWizard(w: PowerWindow) {
        if (measuring) window?.let { stopMeasure(it) }
        window = w
        AppLogger.i(PowerWindows.TAG, "calibration ${w.shortName} : début")
        restart(null)
    }

    private fun restart(notice: String?) {
        downMs = 0L
        upMs = 0L
        step = Step.CLOSE
        render(notice)
    }

    private fun onAction() {
        val w = window ?: return
        when (step) {
            Step.CLOSE -> {
                PowerWindows.closeForCalibration(w)
                step = Step.CLOSING
                render(null)
            }
            Step.CLOSING -> {
                step = Step.MEASURE_DOWN
                render(null)
            }
            // Les mesures se font au toucher (bindMeasureTouch) ; le résultat n'a pas de bouton d'action.
            Step.MEASURE_DOWN, Step.MEASURE_UP, Step.RESULT -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")   // performClick() appelé au relâché
    private fun bindMeasureTouch(btn: MaterialButton) {
        btn.setOnTouchListener { v, event ->
            val direction = when (step) {
                Step.MEASURE_DOWN -> Direction.DOWN
                Step.MEASURE_UP   -> Direction.UP
                else              -> return@setOnTouchListener false   // clic normal du bouton
            }
            val w = window ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Le ScrollView ne doit pas voler le geste : la mesure serait coupée.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    pressStartMs = SystemClock.elapsedRealtime()
                    measuring = true
                    PowerWindows.startHold(w, direction)
                    handler.post(tick)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!measuring) return@setOnTouchListener true
                    v.isPressed = false
                    val ms = stopMeasure(w)
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    onMeasured(w, direction, ms)
                }
            }
            true
        }
    }

    /** Arrête la vitre et le chrono ; rend la durée mesurée. */
    private fun stopMeasure(w: PowerWindow): Long {
        measuring = false
        handler.removeCallbacks(tick)
        PowerWindows.stopHold(w)
        return SystemClock.elapsedRealtime() - pressStartMs
    }

    private fun onMeasured(w: PowerWindow, direction: Direction, ms: Long) {
        val ctx = root?.context ?: return
        AppLogger.i(PowerWindows.TAG, "calibration ${w.shortName} : ${direction.name} mesurée $ms ms")
        if (!WindowCalibration.isValidMeasure(ms)) {
            // La vitre est à mi-course : on repart d'une fermeture complète.
            val res = if (ms < WindowCalibration.MIN_MS) R.string.win_cal_too_short else R.string.win_cal_too_long
            restart(ctx.getString(res, seconds(ms)))
            return
        }
        if (direction == Direction.DOWN) {
            downMs = ms
            step = Step.MEASURE_UP
        } else {
            upMs = ms
            step = Step.RESULT
        }
        render(null)
    }

    private fun saveResult() {
        val ctx = root?.context ?: return
        val w = window ?: return
        PowerWindows.saveCalibration(ctx, w, WindowCalibration(downMs, upMs))
        Toast.makeText(ctx, R.string.win_cal_saved, Toast.LENGTH_SHORT).show()
        closeWizard("enregistrée")
        refreshRows()
        onCalibrationSaved?.invoke()
    }

    private fun closeWizard(reason: String) {
        val w = window
        if (measuring && w != null) stopMeasure(w)
        if (w != null && reason != "enregistrée") AppLogger.i(PowerWindows.TAG, "calibration ${w.shortName} : $reason")
        window = null
        wizard?.visibility = View.GONE
    }

    private fun render(notice: String?) {
        val ctx = root?.context ?: return
        val w = window ?: return
        wizard?.visibility = View.VISIBLE
        title?.text = ctx.getString(R.string.win_cal_wizard_title, ctx.getString(WindowsPanel.nameRes(w)))
        stepText?.text = ctx.getString(R.string.win_cal_step, step.number)

        val body = when (step) {
            Step.CLOSE, Step.CLOSING -> ctx.getString(R.string.win_cal_close_instruction)
            Step.MEASURE_DOWN        -> ctx.getString(R.string.win_cal_down_instruction)
            Step.MEASURE_UP          -> ctx.getString(R.string.win_cal_up_instruction)
            Step.RESULT              -> ctx.getString(R.string.win_cal_result, seconds(downMs), seconds(upMs))
        }
        instruction?.text = listOfNotNull(notice, body).joinToString("\n")

        val measure = step == Step.MEASURE_DOWN || step == Step.MEASURE_UP
        warning?.visibility = if (step == Step.RESULT) View.GONE else View.VISIBLE
        timer?.visibility = if (measure) View.VISIBLE else View.GONE
        timer?.text = seconds(0)

        action?.visibility = if (step == Step.RESULT) View.GONE else View.VISIBLE
        when (step) {
            Step.CLOSE        -> setAction(R.string.win_cal_close_action, R.drawable.ic_window_all_up)
            Step.CLOSING      -> setAction(R.string.win_cal_closed_action, null)
            Step.MEASURE_DOWN -> setAction(R.string.win_cal_down_action, R.drawable.ic_window_down)
            Step.MEASURE_UP   -> setAction(R.string.win_cal_up_action, R.drawable.ic_window_up)
            Step.RESULT       -> Unit
        }
        redo?.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
        save?.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
    }

    private fun setAction(text: Int, icon: Int?) {
        action?.setText(text)
        if (icon != null) action?.setIconResource(icon) else action?.icon = null
    }

    private fun seconds(ms: Long): String {
        val ctx = root?.context ?: return ""
        return ctx.getString(R.string.win_cal_seconds, String.format(Locale.getDefault(), "%.1f", ms / 1000f))
    }
}
