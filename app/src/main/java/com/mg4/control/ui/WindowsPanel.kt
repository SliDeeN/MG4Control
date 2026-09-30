package com.mg4.control.ui

import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.hardware.PowerWindows
import com.mg4.control.model.PowerWindow
import com.mg4.control.model.WindowCommand
import com.mg4.control.model.WindowCommand.Direction
import java.util.Locale

/**
 * Carte « Vitres » de l'onglet Automatisation. Le fragment appelle [bind] une fois, puis
 * [onShown] / [onHidden] selon que la carte est dépliée et l'écran au premier plan.
 *
 * Deux boutons seulement : toutes les vitres à la fois. La commande vitre par vitre et la position
 * estimée ont été retirées le 2026-09-30 — l'estimation devenait fausse dès qu'on touchait un
 * interrupteur physique, et plus rien n'affiche de position à côté de laquelle se tromper.
 *
 * Les vitres sans capteur bougent pendant une **durée de course** réglable ici, cinq secondes par
 * défaut. Le calibrage vitre par vitre, plus précis, est devenu une option avancée dont
 * [WindowCalibrationPanel] porte l'assistant.
 */
class WindowsPanel {

    private val calibration = WindowCalibrationPanel()
    private val autoClose = WindowAutoClosePanel()
    private var root: View? = null
    private var advancedRows: View? = null
    private var courseValue: TextView? = null
    private var shown = false

    companion object {
        fun nameRes(window: PowerWindow): Int = when (window) {
            PowerWindow.FRONT_LEFT  -> R.string.win_front_left
            PowerWindow.FRONT_RIGHT -> R.string.win_front_right
            PowerWindow.REAR_LEFT   -> R.string.win_rear_left
            PowerWindow.REAR_RIGHT  -> R.string.win_rear_right
        }
    }

    fun bind(view: View) {
        root = view
        view.findViewById<MaterialButton>(R.id.btn_win_all_close).setOnClickListener { PowerWindows.autoAll(Direction.UP) }
        view.findViewById<MaterialButton>(R.id.btn_win_all_open).setOnClickListener { PowerWindows.autoAll(Direction.DOWN) }

        bindCourse(view)
        bindAdvanced(view)

        autoClose.bind(view)
        calibration.bind(view)
    }

    fun onShown() {
        if (shown) return
        shown = true
        calibration.refreshRows()
    }

    /** Carte repliée ou écran en pause : aucune fermeture émulée ne continue sans témoin. */
    fun onHidden() {
        if (!shown) return
        shown = false
        calibration.onHidden()
        PowerWindows.stopUnattendedMoves()
    }

    // ── Durée de course ─────────────────────────────────────────────────────

    /** Le curseur travaille en secondes, le modèle en millisecondes. */
    private fun bindCourse(view: View) {
        courseValue = view.findViewById(R.id.win_course_value)
        view.findViewById<Slider>(R.id.slider_win_course).apply {
            valueFrom = WindowCommand.MIN_COURSE_MS / 1000f
            valueTo = WindowCommand.MAX_COURSE_MS / 1000f
            stepSize = WindowCommand.COURSE_STEP_MS / 1000f
            value = (PowerWindows.courseMs(context) / 1000f)
                .coerceIn(valueFrom, valueTo)
            showCourse(value)
            addOnChangeListener { slider, seconds, fromUser ->
                showCourse(seconds)
                if (fromUser) PowerWindows.setCourseMs(slider.context, (seconds * 1000f).toLong())
            }
        }
    }

    private fun showCourse(seconds: Float) {
        val ctx = root?.context ?: return
        courseValue?.text = ctx.getString(
            R.string.win_cal_seconds, String.format(Locale.getDefault(), "%.1f", seconds))
    }

    // ── Calibrage par vitre, option avancée ─────────────────────────────────

    /**
     * L'interrupteur ne fait pas que montrer la section : éteint, les vitres repassent à la durée
     * de course générale. Les mesures, elles, restent enregistrées — voir
     * [PowerWindows.advancedCalibration].
     */
    private fun bindAdvanced(view: View) {
        advancedRows = view.findViewById(R.id.row_win_cal_advanced)
        view.findViewById<Switch>(R.id.switch_win_cal_advanced).apply {
            isChecked = PowerWindows.advancedCalibration(context)
            showAdvanced(isChecked)
            setOnCheckedChangeListener { sw, on ->
                PowerWindows.setAdvancedCalibration(sw.context, on)
                showAdvanced(on)
                calibration.refreshRows()
            }
        }
    }

    private fun showAdvanced(on: Boolean) {
        advancedRows?.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) calibration.onHidden()
    }
}
