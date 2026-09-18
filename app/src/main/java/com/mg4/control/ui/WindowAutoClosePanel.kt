package com.mg4.control.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.hardware.PowerWindows
import com.mg4.control.hardware.WindowAutoClose
import com.mg4.control.model.WindowAutoCloseTrigger

/**
 * Carte « Fermeture automatique » de l'onglet Vitres : activation, conditions d'armement
 * (vitesse et/ou durée, combinées « l'une ou l'autre » ou « les deux »), délai après la sortie
 * de READY et avertissement sonore.
 * Chaque modification est enregistrée et appliquée aussitôt au déclencheur ([WindowAutoClose]).
 */
class WindowAutoClosePanel {

    private var root: View? = null
    private var enableSwitch: Switch? = null
    private var calNotice: TextView? = null
    private var paramsGroup: View? = null
    private var speedSwitch: Switch? = null
    private var timeSwitch: Switch? = null
    private var beepSwitch: Switch? = null
    private var speedSlider: Slider? = null
    private var timeSlider: Slider? = null
    private var delaySlider: Slider? = null
    private var beepSlider: Slider? = null
    private var beepValue: TextView? = null
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
        speedSwitch = view.findViewById(R.id.switch_win_auto_speed)
        timeSwitch = view.findViewById(R.id.switch_win_auto_time)
        speedSlider = view.findViewById(R.id.slider_win_auto_speed)
        timeSlider = view.findViewById(R.id.slider_win_auto_time)
        delaySlider = view.findViewById(R.id.slider_win_auto_delay)
        delayValue = view.findViewById(R.id.win_auto_delay_value)
        speedValue = view.findViewById(R.id.win_auto_speed_value)
        timeValue = view.findViewById(R.id.win_auto_time_value)
        beepSwitch = view.findViewById(R.id.switch_win_auto_beep)
        beepSlider = view.findViewById(R.id.slider_win_auto_beep)
        beepValue = view.findViewById(R.id.win_auto_beep_value)
        combine = view.findViewById(R.id.win_auto_combine)
        anyButton = view.findViewById(R.id.btn_win_auto_any)
        allButton = view.findViewById(R.id.btn_win_auto_all)

        calNotice = view.findViewById(R.id.win_auto_cal_notice)
        paramsGroup = view.findViewById(R.id.row_win_auto_config)
        enableSwitch = view.findViewById<Switch>(R.id.switch_win_autoclose).apply {
            isChecked = WindowAutoClose.isEnabled(context)
            setOnCheckedChangeListener { sw, on ->
                WindowAutoClose.setEnabled(sw.context, on)
                // Option relâchée sans calibration complète : le verrou reprend la main.
                refreshCalibrationGate()
                render()
            }
        }
        refreshCalibrationGate()

        val s = WindowAutoClose.settings(view.context)
        requireBoth = s.requireBoth
        updating = true
        speedSwitch?.isChecked = s.speedOn
        timeSwitch?.isChecked = s.timeOn
        beepSwitch?.isChecked = s.beep
        // La valeur d'un Slider doit tomber sur un pas, sinon il lève une exception à l'affichage.
        speedSlider?.value = (s.speedKmh / WindowAutoCloseTrigger.SPEED_STEP_KMH * WindowAutoCloseTrigger.SPEED_STEP_KMH)
            .coerceIn(WindowAutoCloseTrigger.SPEED_MIN_KMH, WindowAutoCloseTrigger.SPEED_MAX_KMH).toFloat()
        timeSlider?.value = s.timeMin.toFloat()
        delaySlider?.value = s.delayS.toFloat()
        beepSlider?.value = beepStep(s.beepVolume).toFloat()
        updating = false

        speedSwitch?.setOnCheckedChangeListener { sw, on -> onConditionToggled(sw, on, other = timeSwitch) }
        timeSwitch?.setOnCheckedChangeListener { sw, on -> onConditionToggled(sw, on, other = speedSwitch) }
        beepSwitch?.setOnCheckedChangeListener { _, on ->
            if (updating) return@setOnCheckedChangeListener
            save()
            if (on) WindowAutoClose.previewBeep(view.context)
        }
        // Bip d'essai à chaque cran : c'est le seul moyen de juger le volume choisi.
        beepSlider?.addOnChangeListener { sl, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            save()
            WindowAutoClose.previewBeep(sl.context)
        }
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
        beep = beepSwitch?.isChecked == true,
        beepVolume = beepSlider?.value?.toInt() ?: WindowAutoClose.BEEP_VOLUME_DEFAULT,
    )

    /**
     * Verrouille l'activation tant que les vitres sans capteur ne sont pas calibrées : la
     * fermeture en quittant ne connaît la durée de course que par la calibration.
     *
     * Le verrou ne porte que sur l'ACTIVATION. Une option déjà active reste débrayable — sinon
     * un utilisateur l'ayant activée avant ce garde-fou se retrouverait sans moyen de l'arrêter.
     */
    fun refreshCalibrationGate() {
        val ctx = root?.context ?: return
        val manquantes = PowerWindows.uncalibrated(ctx)
        calNotice?.visibility = if (manquantes.isEmpty()) View.GONE else View.VISIBLE
        if (manquantes.isNotEmpty()) calNotice?.text = ctx.getString(
            R.string.win_auto_need_cal,
            manquantes.joinToString(", ") { ctx.getString(WindowsPanel.nameRes(it)) })
        val sw = enableSwitch ?: return
        sw.isEnabled = manquantes.isEmpty() || sw.isChecked
        sw.alpha = if (sw.isEnabled) 1f else 0.4f
    }

    /** Un Slider refuse une valeur qui ne tombe pas sur un pas : on l'y ramène. */
    private fun beepStep(volume: Int): Int =
        (volume / WindowAutoClose.BEEP_VOLUME_STEP * WindowAutoClose.BEEP_VOLUME_STEP)
            .coerceIn(WindowAutoClose.BEEP_VOLUME_MIN, WindowAutoClose.BEEP_VOLUME_MAX)

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
        beepValue?.text = ctx.getString(R.string.win_auto_beep_value, s.beepVolume)
        // Option éteinte : tout le paramétrage est grisé d'un bloc et redevient intouchable —
        // il reste lisible, pour qu'on sache ce qui se passera une fois l'option rallumée.
        val actif = enableSwitch?.isChecked == true
        paramsGroup?.alpha = if (actif) 1f else 0.4f
        listOfNotNull<View>(speedSwitch, timeSwitch, beepSwitch, anyButton, allButton, delaySlider)
            .forEach { it.isEnabled = actif }
        // Condition (ou avertissement) désactivée : réglage gardé mais grisé, pour le retrouver
        // en la réactivant. Inutile de l'assombrir une seconde fois quand le bloc l'est déjà.
        grise(speedSlider, s.speedOn, actif)
        grise(timeSlider, s.timeOn, actif)
        grise(beepSlider, s.beep, actif)
        combine?.visibility = if (s.speedOn && s.timeOn) View.VISIBLE else View.GONE
        highlight(anyButton, !requireBoth)
        highlight(allButton, requireBoth)
    }

    private fun grise(slider: Slider?, propre: Boolean, blocActif: Boolean) {
        slider ?: return
        slider.isEnabled = blocActif && propre
        slider.alpha = if (!blocActif || propre) 1f else 0.4f
    }

    private fun highlight(btn: MaterialButton?, on: Boolean) {
        val ctx = btn?.context ?: return
        btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
        btn.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
        btn.strokeColor = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
    }
}
