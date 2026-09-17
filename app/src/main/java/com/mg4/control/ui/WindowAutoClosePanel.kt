package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.hardware.WindowAutoClose
import com.mg4.control.model.WindowAutoCloseTrigger
import java.util.Date

/**
 * Carte « Fermeture automatique » de l'onglet Vitres : activation, conditions d'armement
 * (vitesse et/ou durée, combinées « l'une ou l'autre » ou « les deux »), délai après la sortie
 * de READY et état en direct.
 * Chaque modification est enregistrée et appliquée aussitôt au déclencheur ([WindowAutoClose]).
 */
class WindowAutoClosePanel {

    private var root: View? = null
    private var status: TextView? = null
    private var speedSwitch: Switch? = null
    private var timeSwitch: Switch? = null
    private var speedSlider: Slider? = null
    private var timeSlider: Slider? = null
    private var delaySlider: Slider? = null
    private var delayValue: TextView? = null
    private var speedValue: TextView? = null
    private var timeValue: TextView? = null
    private var combine: View? = null
    private var anyButton: MaterialButton? = null
    private var allButton: MaterialButton? = null
    private var requireBoth = false
    /** Vrai pendant les mises à jour faites par le code : les écouteurs ne doivent pas réenregistrer. */
    private var updating = false

    fun bind(view: View) {
        root = view
        status = view.findViewById(R.id.win_auto_status)
        speedSwitch = view.findViewById(R.id.switch_win_auto_speed)
        timeSwitch = view.findViewById(R.id.switch_win_auto_time)
        speedSlider = view.findViewById(R.id.slider_win_auto_speed)
        timeSlider = view.findViewById(R.id.slider_win_auto_time)
        delaySlider = view.findViewById(R.id.slider_win_auto_delay)
        delayValue = view.findViewById(R.id.win_auto_delay_value)
        speedValue = view.findViewById(R.id.win_auto_speed_value)
        timeValue = view.findViewById(R.id.win_auto_time_value)
        combine = view.findViewById(R.id.win_auto_combine)
        anyButton = view.findViewById(R.id.btn_win_auto_any)
        allButton = view.findViewById(R.id.btn_win_auto_all)

        view.findViewById<Switch>(R.id.switch_win_autoclose).apply {
            isChecked = WindowAutoClose.isEnabled(context)
            setOnCheckedChangeListener { sw, on ->
                WindowAutoClose.setEnabled(sw.context, on)
                refreshStatus(sw.context)
            }
        }

        val s = WindowAutoClose.settings(view.context)
        requireBoth = s.requireBoth
        updating = true
        speedSwitch?.isChecked = s.speedOn
        timeSwitch?.isChecked = s.timeOn
        // La valeur d'un Slider doit tomber sur un pas, sinon il lève une exception à l'affichage.
        speedSlider?.value = (s.speedKmh / WindowAutoCloseTrigger.SPEED_STEP_KMH * WindowAutoCloseTrigger.SPEED_STEP_KMH)
            .coerceIn(WindowAutoCloseTrigger.SPEED_MIN_KMH, WindowAutoCloseTrigger.SPEED_MAX_KMH).toFloat()
        timeSlider?.value = s.timeMin.toFloat()
        delaySlider?.value = s.delayS.toFloat()
        updating = false

        speedSwitch?.setOnCheckedChangeListener { sw, on -> onConditionToggled(sw, on, other = timeSwitch) }
        timeSwitch?.setOnCheckedChangeListener { sw, on -> onConditionToggled(sw, on, other = speedSwitch) }
        speedSlider?.addOnChangeListener { _, _, fromUser -> if (fromUser) save() }
        timeSlider?.addOnChangeListener { _, _, fromUser -> if (fromUser) save() }
        delaySlider?.addOnChangeListener { _, _, fromUser -> if (fromUser) save() }
        anyButton?.setOnClickListener { requireBoth = false; save() }
        allButton?.setOnClickListener { requireBoth = true; save() }

        render()
    }

    /** Une condition au moins doit rester active : sinon la fermeture ne s'armerait jamais. */
    private fun onConditionToggled(sw: android.widget.CompoundButton, on: Boolean, other: Switch?) {
        if (updating) return
        if (!on && other?.isChecked != true) {
            updating = true
            sw.isChecked = true
            updating = false
            Toast.makeText(sw.context, R.string.win_auto_need_one, Toast.LENGTH_SHORT).show()
            return
        }
        save()
    }

    private fun current() = WindowAutoClose.Settings(
        speedOn = speedSwitch?.isChecked == true,
        speedKmh = speedSlider?.value?.toInt() ?: WindowAutoCloseTrigger.SPEED_MIN_KMH,
        timeOn = timeSwitch?.isChecked == true,
        timeMin = timeSlider?.value?.toInt() ?: WindowAutoCloseTrigger.TIME_MIN_MIN,
        requireBoth = requireBoth,
        delayS = delaySlider?.value?.toInt() ?: WindowAutoCloseTrigger.DELAY_DEFAULT_S,
    )

    private fun save() {
        val ctx = root?.context ?: return
        WindowAutoClose.saveSettings(ctx, current())
        render()
    }

    private fun render() {
        val ctx = root?.context ?: return
        val s = current()
        speedValue?.text = ctx.getString(R.string.win_auto_speed_value, s.speedKmh)
        timeValue?.text = ctx.getString(R.string.win_auto_time_value, s.timeMin)
        delayValue?.text = ctx.getString(R.string.win_auto_delay_value, s.delayS)
        // Condition désactivée : réglage gardé mais grisé, pour le retrouver en la réactivant.
        speedSlider?.isEnabled = s.speedOn
        speedSlider?.alpha = if (s.speedOn) 1f else 0.4f
        timeSlider?.isEnabled = s.timeOn
        timeSlider?.alpha = if (s.timeOn) 1f else 0.4f
        combine?.visibility = if (s.speedOn && s.timeOn) View.VISIBLE else View.GONE
        highlight(anyButton, !requireBoth)
        highlight(allButton, requireBoth)
    }

    private fun highlight(btn: MaterialButton?, on: Boolean) {
        val ctx = btn?.context ?: return
        btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
        btn.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
        btn.strokeColor = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
    }

    /** État en direct : armement, état READY lu, dernière fermeture. */
    fun refreshStatus(ctx: Context) {
        val st = WindowAutoClose.status(ctx)
        if (!st.enabled) {
            status?.setText(R.string.win_auto_off)
            return
        }
        val ready = when (st.ready) {
            true  -> R.string.win_yes
            false -> R.string.win_no
            null  -> R.string.win_auto_ready_unknown
        }
        val at = DateFormat.getTimeFormat(ctx).format(Date(st.lastResultAt))
        val last = when (st.lastResult) {
            null                             -> ctx.getString(R.string.win_auto_last_none)
            WindowAutoClose.Result.PENDING   -> ctx.getString(R.string.win_auto_last_pending)
            WindowAutoClose.Result.DONE      -> ctx.getString(R.string.win_auto_last_done, at)
            WindowAutoClose.Result.PARTIAL   -> ctx.getString(R.string.win_auto_last_partial, at)
            WindowAutoClose.Result.CANCELLED -> ctx.getString(R.string.win_auto_last_cancelled, at)
        }
        status?.text = ctx.getString(R.string.win_auto_status,
            ctx.getString(if (st.armed) R.string.win_yes else R.string.win_no), ctx.getString(ready), last)
    }
}
