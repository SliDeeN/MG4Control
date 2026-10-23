package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.BuildConfig
import com.mg4.control.R
import com.mg4.control.automation.AutoBrightness
import com.mg4.control.automation.AutoBrightnessSettings
import com.mg4.control.automation.AutomationSettings
import com.mg4.control.automation.BatteryAutomationSettings
import com.mg4.control.automation.BatteryHeatingSettings
import com.mg4.control.automation.ClimateAutomationSettings
import com.mg4.control.automation.ClimateRuleEditor
import com.mg4.control.automation.SoundAutomation
import com.mg4.control.automation.SoundAutomationSettings
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.WindowAutoClose
import com.mg4.control.model.DrivingProfile
import com.mg4.control.profile.ProfileManager
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutomationFragment : Fragment() {

    private var profiles: List<DrivingProfile> = emptyList()

    /** Carte des vitres : le panneau survit à la vue, comme au tableau de bord avant le déplacement. */
    private val windowsPanel = WindowsPanel()

    /** Carte des sons (issue #161). */
    private val soundCard = SoundCard()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_automation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(AutomationSettings.PREFS, Context.MODE_PRIVATE)

        val switchAuto = view.findViewById<Switch>(R.id.switch_automation)
        val rowConfig  = view.findViewById<View>(R.id.row_automation_config)
        val inputTemp  = view.findViewById<EditText>(R.id.input_automation_temp)
        val spinner    = view.findViewById<Spinner>(R.id.spinner_automation_profile)
        val checkAuto  = view.findViewById<CheckBox>(R.id.check_auto_execute)

        val enabled = prefs.getBoolean(AutomationSettings.KEY_ENABLED, false)
        switchAuto.isChecked = enabled
        inputTemp.setText(prefs.getInt(AutomationSettings.KEY_THRESHOLD, AutomationSettings.DEFAULT_THRESHOLD).toString())
        checkAuto.isChecked = prefs.getBoolean(AutomationSettings.KEY_AUTO_EXECUTE, false)

        // L'interrupteur active et, au passage, deplie ou replie le parametrage ; le chevron reste
        // libre (voir bindExpander).
        val deplier = bindExpander(view.findViewById(R.id.btn_automation_expand), rowConfig, expanded = enabled)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AutomationSettings.KEY_ENABLED, checked).apply()
            deplier(checked)
        }
        bindWindowsCard(view)

        fun commitTemp() {
            val clamped = AutomationSettings.clampTemp(inputTemp.text.toString().toIntOrNull())
            prefs.edit().putInt(AutomationSettings.KEY_THRESHOLD, clamped).apply()
            val txt = clamped.toString()
            if (inputTemp.text.toString() != txt) inputTemp.setText(txt)
        }
        inputTemp.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTemp() }
        inputTemp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) commitTemp()
            false
        }

        checkAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AutomationSettings.KEY_AUTO_EXECUTE, checked).apply()
        }

        // ── Sens du déclenchement (inférieure / supérieure au seuil) ─────────
        val btnDirBelow = view.findViewById<MaterialButton>(R.id.btn_dir_below)
        val btnDirAbove = view.findViewById<MaterialButton>(R.id.btn_dir_above)
        val accentDim   = requireContext().getColor(R.color.dash_accent_dim)
        val inactive    = requireContext().getColor(R.color.dash_btn)

        fun highlightDirection(dir: AutomationSettings.Direction) {
            btnDirBelow.backgroundTintList = ColorStateList.valueOf(
                if (dir == AutomationSettings.Direction.BELOW) accentDim else inactive)
            btnDirAbove.backgroundTintList = ColorStateList.valueOf(
                if (dir == AutomationSettings.Direction.ABOVE) accentDim else inactive)
        }
        highlightDirection(AutomationSettings.readDirection(requireContext()))

        fun setDirection(dir: AutomationSettings.Direction) {
            prefs.edit().putString(AutomationSettings.KEY_DIRECTION, dir.name).apply()
            highlightDirection(dir)
        }
        btnDirBelow.setOnClickListener { setDirection(AutomationSettings.Direction.BELOW) }
        btnDirAbove.setOnClickListener { setDirection(AutomationSettings.Direction.ABOVE) }

        setupSpinner(spinner, prefs, AutomationSettings.KEY_PROFILE_ID)
        bindBatteryAutomation(view, prefs)
        bindAutoBrightness(view, prefs)
        bindBatteryHeatingAutomation(view, prefs)
        bindClimateAutomation(view, prefs)
        bindDoorVolume(view, prefs)
        bindSounds(view)
    }

    // ══════════ Sons d'automatisation (issue #161) ══════════

    /**
     * Interrupteur général et dépliage ici, comme les autres cartes ; le contenu (deux sons,
     * profils) dans [SoundCard]. Cachée là où le volume de la voiture n'est pas pilotable : le son
     * se joue au volume choisi, réglé sur la voiture le temps du son.
     */
    private fun bindSounds(view: View) {
        val card = view.findViewById<View>(R.id.card_sound)
        if (!MG4Hardware.hasDoorVolumeFeature()) {
            card.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val toggle = view.findViewById<Switch>(R.id.switch_sound)
        val content = view.findViewById<View>(R.id.row_sound_config)
        val enabled = SoundAutomationSettings.isEnabled(ctx)
        toggle.isChecked = enabled
        soundCard.bind(this, view, enabled)
        val deplier = bindExpander(view.findViewById(R.id.btn_sound_expand), content, expanded = enabled)
        toggle.setOnCheckedChangeListener { _, checked ->
            SoundAutomationSettings.setEnabled(ctx, checked)
            soundCard.griser(checked)
            deplier(checked)
            SoundAutomation.refresh(ctx)
        }
    }

    override fun onDestroyView() {
        soundCard.detacher()
        super.onDestroyView()
    }

    // ══════════ Baisse du volume en quittant la voiture ══════════

    /**
     * Venue de l'ancien onglet Audio, dont c'était le seul contenu. Le déclencheur dépend du
     * firmware : une porte avant là où la voiture la signale, la sortie de READY ailleurs — alors
     * sans choix de porte, et la restauration se fait au retour en READY.
     *
     * Option coupée, les réglages restent lisibles au chevron mais grisés, comme sur la carte des
     * vitres : ils n'ont d'effet qu'une fois l'option activée.
     */
    private fun bindDoorVolume(view: View, prefs: android.content.SharedPreferences) {
        val card = view.findViewById<View>(R.id.card_door_volume)
        if (!MG4Hardware.hasDoorVolumeFeature()) {
            card.visibility = View.GONE
            return
        }

        val toggle  = view.findViewById<Switch>(R.id.switch_door_volume)
        val content = view.findViewById<View>(R.id.row_door_volume_config)
        val slider  = view.findViewById<Slider>(R.id.slider_door_volume)
        val valeur  = view.findViewById<TextView>(R.id.door_volume_level_value)
        val restore = view.findViewById<Switch>(R.id.switch_door_restore)
        val cbLeft  = view.findViewById<CheckBox>(R.id.cb_door_left)
        val cbRight = view.findViewById<CheckBox>(R.id.cb_door_right)

        val porte = MG4Hardware.hasDoorDetection()
        if (!porte) {
            view.findViewById<View>(R.id.door_volume_doors_label).visibility = View.GONE
            view.findViewById<View>(R.id.door_volume_doors_row).visibility = View.GONE
        }

        // Baisser le volume ou mettre en pause (2026-10-23) : le niveau ne sert qu'à la baisse,
        // et la description comme l'option de restauration disent ce que fait le mode choisi.
        val btnVolume = view.findViewById<MaterialButton>(R.id.btn_door_mode_volume)
        val btnPause  = view.findViewById<MaterialButton>(R.id.btn_door_mode_pause)
        val rowLevel  = view.findViewById<View>(R.id.row_door_volume_level)
        fun peindreMode() {
            val pause = prefs.getString(MG4Hardware.KEY_DOOR_MODE, null) == MG4Hardware.DOOR_MODE_PAUSE
            listOf(btnVolume to !pause, btnPause to pause).forEach { (b, on) ->
                b.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
                b.strokeColor = ColorStateList.valueOf(requireContext().getColor(if (on) R.color.dash_accent else R.color.dash_border))
                b.setTextColor(requireContext().getColor(if (on) R.color.text_primary else R.color.text_secondary))
            }
            rowLevel.visibility = if (pause) View.GONE else View.VISIBLE
            slider.visibility = if (pause) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.door_volume_desc_text).setText(when {
                pause && porte -> R.string.door_media_pause_desc
                pause -> R.string.door_media_pause_desc_ready
                porte -> R.string.door_volume_desc
                else -> R.string.door_volume_desc_ready
            })
            view.findViewById<TextView>(R.id.door_volume_restore_label).setText(when {
                pause && porte -> R.string.door_media_resume_title
                pause -> R.string.door_media_resume_ready_title
                porte -> R.string.door_volume_restore_title
                else -> R.string.door_volume_restore_ready_title
            })
        }
        btnVolume.setOnClickListener {
            prefs.edit().putString(MG4Hardware.KEY_DOOR_MODE, MG4Hardware.DOOR_MODE_VOLUME).apply()
            peindreMode()
        }
        btnPause.setOnClickListener {
            prefs.edit().putString(MG4Hardware.KEY_DOOR_MODE, MG4Hardware.DOOR_MODE_PAUSE).apply()
            peindreMode()
        }
        peindreMode()

        val enabled = prefs.getBoolean("door_volume_enabled", false)
        toggle.isChecked  = enabled
        restore.isChecked = prefs.getBoolean("door_volume_restore", false)
        cbLeft.isChecked  = prefs.getBoolean("door_volume_left", true)
        cbRight.isChecked = prefs.getBoolean("door_volume_right", true)

        fun griser(actif: Boolean) {
            content.alpha = if (actif) 1f else 0.4f
            slider.isEnabled = actif; restore.isEnabled = actif
            btnVolume.isEnabled = actif; btnPause.isEnabled = actif
            cbLeft.isEnabled = actif; cbRight.isEnabled = actif
        }
        griser(enabled)

        fun afficher(niveau: Int) {
            valeur.text = getString(R.string.door_volume_level_value, niveau, slider.valueTo.toInt())
        }

        // Si déjà activé, (re)démarre le watcher à l'ouverture de l'onglet (idempotent).
        if (enabled) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { MG4Hardware.startDoorVolumeWatcher() }
        }

        // Borne le curseur sur le maximum réel de la voiture (sinon la valeur par défaut du layout).
        afficher(slider.value.toInt())
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val max = MG4Hardware.getMediaVolumeMax()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (max > 0) slider.valueTo = max.toFloat()
                val level = prefs.getInt("door_volume_level", 0)
                slider.value = level.coerceIn(0, slider.valueTo.toInt()).toFloat()
                afficher(slider.value.toInt())
            }
        }

        val deplier = bindExpander(view.findViewById(R.id.btn_door_volume_expand), content, expanded = enabled)
        toggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_enabled", checked).apply()
            griser(checked)
            deplier(checked)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (checked) MG4Hardware.startDoorVolumeWatcher()
                else MG4Hardware.stopDoorVolumeWatcher()
            }
        }

        slider.addOnChangeListener { _, value, fromUser ->
            afficher(value.toInt())
            if (fromUser) prefs.edit().putInt("door_volume_level", value.toInt()).apply()
        }
        restore.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_restore", checked).apply()
        }
        cbLeft.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_left", checked).apply()
        }
        cbRight.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("door_volume_right", checked).apply()
        }
    }

    // ══════════ Coupure automatique du chauffage de la batterie ══════════

    /**
     * Interrupteur + durée au curseur, relus par le moteur à chaque passage : un changement vaut
     * tout de suite, trajet en cours compris (une durée raccourcie rapproche l'échéance).
     */
    private fun bindBatteryHeatingAutomation(view: View, prefs: android.content.SharedPreferences) {
        val card = view.findViewById<View>(R.id.card_batheat_auto)
        if (!MG4Hardware.hasBatteryHeating()) {
            card.visibility = View.GONE
            return
        }
        val cfg = BatteryHeatingSettings.read(requireContext())
        val sw = view.findViewById<Switch>(R.id.switch_batheat_auto)
        sw.isChecked = cfg.enabled
        val deplier = bindExpander(view.findViewById(R.id.btn_batheat_auto_expand),
            view.findViewById(R.id.row_batheat_auto_config), expanded = cfg.enabled)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BatteryHeatingSettings.KEY_ENABLED, on).apply()
            deplier(on)
        }

        val slider = view.findViewById<Slider>(R.id.slider_batheat_auto_minutes)
        val valeur = view.findViewById<TextView>(R.id.batheat_auto_minutes_value)
        val pas = BatteryHeatingSettings.STEP_MINUTES
        slider.valueFrom = BatteryHeatingSettings.MIN_MINUTES.toFloat()
        slider.valueTo   = BatteryHeatingSettings.MAX_MINUTES.toFloat()
        slider.stepSize  = pas.toFloat()
        // Le Slider refuse une valeur hors pas : on aligne une valeur enregistrée qui ne le serait pas.
        val aligne = (Math.round(cfg.minutes / pas.toFloat()) * pas).let(BatteryHeatingSettings::clamp)
        slider.value = aligne.toFloat()
        valeur.text = getString(R.string.batheat_auto_minutes_value, aligne)
        slider.addOnChangeListener { _, v, fromUser ->
            valeur.text = getString(R.string.batheat_auto_minutes_value, v.toInt())
            if (fromUser) prefs.edit()
                .putInt(BatteryHeatingSettings.KEY_MINUTES, BatteryHeatingSettings.clamp(v.toInt()))
                .apply()
        }
    }

    // ══════════ Automatisation « profil selon la batterie » (issue #112) ══════════

    /**
     * Même structure que la carte température, mais le seuil se règle au curseur : la saisie
     * faisait surgir un clavier virtuel qui masquait la moitié de la carte.
     */
    private fun bindBatteryAutomation(view: View, prefs: android.content.SharedPreferences) {
        val cfg     = BatteryAutomationSettings.read(requireContext())
        val sw      = view.findViewById<Switch>(R.id.switch_battery_auto)
        val content = view.findViewById<View>(R.id.row_battery_auto_config)
        val slider  = view.findViewById<Slider>(R.id.slider_battery_auto_threshold)
        val valeur  = view.findViewById<TextView>(R.id.battery_auto_threshold_value)
        val check   = view.findViewById<CheckBox>(R.id.check_battery_auto_execute)

        sw.isChecked = cfg.enabled
        val deplier = bindExpander(view.findViewById(R.id.btn_battery_auto_expand), content, expanded = cfg.enabled)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BatteryAutomationSettings.KEY_ENABLED, on).apply()
            deplier(on)
        }

        slider.valueFrom = BatteryAutomationSettings.MIN_THRESHOLD.toFloat()
        slider.valueTo   = BatteryAutomationSettings.MAX_THRESHOLD.toFloat()
        slider.stepSize  = 1f
        slider.value     = cfg.threshold.toFloat()
        fun afficher(seuil: Int) {
            valeur.text = getString(R.string.battery_auto_threshold_value, seuil)
        }
        afficher(cfg.threshold)
        slider.addOnChangeListener { _, v, fromUser ->
            afficher(v.toInt())
            if (fromUser) prefs.edit()
                .putInt(BatteryAutomationSettings.KEY_THRESHOLD, BatteryAutomationSettings.clamp(v.toInt()))
                .apply()
        }

        check.isChecked = cfg.autoExecute
        check.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(BatteryAutomationSettings.KEY_AUTO_EXECUTE, c).apply()
        }

        setupSpinner(view.findViewById(R.id.spinner_battery_auto_profile), prefs,
            BatteryAutomationSettings.KEY_PROFILE_ID)
    }

    // ══════════ Luminosité automatique au démarrage ══════════

    /**
     * Quatre curseurs pour la courbe « lumière extérieure → luminosité », un bouton pour l'essayer
     * sans redémarrer la voiture, et la ligne d'état du dernier réglage — c'est elle qui permet
     * d'ajuster la courbe en connaissance de cause.
     */
    private fun bindAutoBrightness(view: View, prefs: android.content.SharedPreferences) {
        val card = view.findViewById<View>(R.id.card_autobri)
        if (!MG4Hardware.hasBrightnessControl()) {
            card.visibility = View.GONE
            return
        }
        val cfg = AutoBrightnessSettings.read(requireContext())
        val sw  = view.findViewById<Switch>(R.id.switch_autobri)
        sw.isChecked = cfg.enabled
        val deplier = bindExpander(view.findViewById(R.id.btn_autobri_expand),
            view.findViewById(R.id.row_autobri_config), expanded = cfg.enabled)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(AutoBrightnessSettings.KEY_ENABLED, on).apply()
            deplier(on)
        }

        // Pris en compte au tick suivant, en route compris : le suivi relit ses options chaque seconde.
        view.findViewById<CheckBox>(R.id.check_autobri_follow).apply {
            isChecked = cfg.follow
            setOnCheckedChangeListener { _, c ->
                prefs.edit().putBoolean(AutoBrightnessSettings.KEY_FOLLOW, c).apply()
            }
        }

        // Sources : les feux et/ou la météo, au moins une. Version hors ligne (sans Internet, par
        // choix) : rien que les feux, le choix n'est pas proposé.
        view.findViewById<View>(R.id.section_autobri_sources).visibility =
            if (BuildConfig.OFFLINE) View.GONE else View.VISIBLE
        val feux  = view.findViewById<CheckBox>(R.id.check_autobri_lights)
        val meteo = view.findViewById<CheckBox>(R.id.check_autobri_forecast)
        val libelleFeux = getText(R.string.autobri_lights)
        // L'avertissement « données mobiles » en orange, à la suite du libellé.
        val libelleMeteo = SpannableStringBuilder(getString(R.string.autobri_forecast)).append(' ')
            .append(getString(R.string.autobri_forecast_warning),
                ForegroundColorSpan(requireContext().getColor(R.color.dash_warn)),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        feux.isChecked  = cfg.useLights
        meteo.isChecked = cfg.useForecast

        fun appliquerSources() {
            val f = feux.isChecked
            val m = meteo.isChecked
            // La dernière source cochée ne se décoche pas.
            verrouiller(feux, libelleFeux, f && !m)
            verrouiller(meteo, libelleMeteo, m && !f)
            val (mode, couleur, fond) = when {
                !m   -> Triple(R.string.autobri_mode_lights, R.color.dash_eco, R.color.dash_eco_dim)
                f    -> Triple(R.string.autobri_mode_both, R.color.dash_warn, R.color.dash_warn_dim)
                else -> Triple(R.string.autobri_mode_forecast, R.color.dash_warn, R.color.dash_warn_dim)
            }
            view.findViewById<TextView>(R.id.autobri_mode).apply {
                setText(mode)
                setTextColor(requireContext().getColor(couleur))
                backgroundTintList = ColorStateList.valueOf(requireContext().getColor(fond))
            }
            view.findViewById<TextView>(R.id.autobri_desc).setText(
                if (m) R.string.autobri_desc else R.string.autobri_desc_offline)
            view.findViewById<TextView>(R.id.autobri_lights_desc).setText(when {
                !f   -> R.string.autobri_lights_desc_off
                m    -> R.string.autobri_lights_desc
                else -> R.string.autobri_lights_desc_only
            })
            view.findViewById<TextView>(R.id.autobri_follow_desc).setText(when {
                !m   -> R.string.autobri_follow_desc_offline
                f    -> R.string.autobri_follow_desc_lights
                else -> R.string.autobri_follow_desc
            })
            // Avec la météo, la courbe ; en feux seuls, les deux niveaux. Chacun garde ses valeurs.
            view.findViewById<View>(R.id.section_autobri_curve).visibility =
                if (m) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.section_autobri_levels).visibility =
                if (m) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.autobri_night_label).setText(
                if (f && m) R.string.autobri_night_lights else R.string.autobri_night)
            view.findViewById<TextView>(R.id.autobri_note).setText(
                if (m) R.string.autobri_note else R.string.autobri_note_offline)
        }
        // Les deux cases enregistrées ensemble : l'état affiché est l'état EFFECTIF (feux forcés
        // sans la météo), à ne pas laisser diverger d'un ancien réglage « feux décochés ».
        val enregistrer = CompoundButton.OnCheckedChangeListener { _, _ ->
            prefs.edit()
                .putBoolean(AutoBrightnessSettings.KEY_USE_LIGHTS, feux.isChecked)
                .putBoolean(AutoBrightnessSettings.KEY_USE_FORECAST, meteo.isChecked)
                .apply()
            appliquerSources()
        }
        feux.setOnCheckedChangeListener(enregistrer)
        meteo.setOnCheckedChangeListener(enregistrer)
        appliquerSources()

        bindPercentSlider(view, R.id.slider_autobri_lights_off, R.id.autobri_lights_off_value,
            AutoBrightnessSettings.KEY_LIGHTS_OFF_PERCENT, cfg.lightsOffPercent, prefs)
        bindPercentSlider(view, R.id.slider_autobri_lights_on, R.id.autobri_lights_on_value,
            AutoBrightnessSettings.KEY_LIGHTS_ON_PERCENT, cfg.lightsOnPercent, prefs)
        bindPercentSlider(view, R.id.slider_autobri_night, R.id.autobri_night_value,
            AutoBrightnessSettings.KEY_NIGHT, cfg.curve.night, prefs)
        bindPercentSlider(view, R.id.slider_autobri_twilight, R.id.autobri_twilight_value,
            AutoBrightnessSettings.KEY_TWILIGHT, cfg.curve.twilight, prefs)
        bindPercentSlider(view, R.id.slider_autobri_overcast, R.id.autobri_overcast_value,
            AutoBrightnessSettings.KEY_OVERCAST, cfg.curve.overcast, prefs)
        bindPercentSlider(view, R.id.slider_autobri_sunny, R.id.autobri_sunny_value,
            AutoBrightnessSettings.KEY_SUNNY, cfg.curve.sunny, prefs)

        val etat = view.findViewById<TextView>(R.id.autobri_status)
        afficherEtatLuminosite(etat, prefs)
        view.findViewById<MaterialButton>(R.id.btn_autobri_test).setOnClickListener { btn ->
            btn.isEnabled = false
            AutoBrightness.testNow(requireContext()) { r ->
                btn.isEnabled = true
                if (!isAdded) return@testNow
                if (r == null) etat.setText(
                    if (meteo.isChecked) R.string.autobri_status_failed else R.string.autobri_status_failed_lights)
                else afficherEtatLuminosite(etat, prefs)
            }
        }
    }

    /**
     * Case qu'on ne peut plus décocher (dernière source cochée) : grisée, son libellé précédé d'un
     * cadenas pour qu'on comprenne pourquoi elle ne répond plus.
     */
    private fun verrouiller(box: CheckBox, libelle: CharSequence, verrouillee: Boolean) {
        box.isEnabled = !verrouillee
        if (!verrouillee) {
            box.text = libelle
            return
        }
        val taille = box.textSize.toInt()
        val cadenas = requireContext().getDrawable(R.drawable.ic_lock)?.mutate()?.apply {
            setTint(requireContext().getColor(R.color.text_secondary))
            setBounds(0, 0, taille, taille)
        }
        box.text = if (cadenas == null) libelle else SpannableStringBuilder(" ").apply {
            setSpan(ImageSpan(cadenas, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(' ').append(libelle)
        }
    }

    /** Curseur 5–100 % par pas de 5, aligné sur le pas : le Slider refuse toute autre valeur. */
    private fun bindPercentSlider(
        view: View, sliderId: Int, valueId: Int, key: String, initial: Int,
        prefs: android.content.SharedPreferences,
    ) {
        val slider = view.findViewById<Slider>(sliderId)
        val valeur = view.findViewById<TextView>(valueId)
        val min = AutoBrightnessSettings.MIN_PERCENT
        val pas = AutoBrightnessSettings.STEP_PERCENT
        slider.valueFrom = min.toFloat()
        slider.valueTo   = AutoBrightnessSettings.MAX_PERCENT.toFloat()
        slider.stepSize  = pas.toFloat()
        val aligne = (min + Math.round((initial - min) / pas.toFloat()) * pas)
            .coerceIn(min, AutoBrightnessSettings.MAX_PERCENT)
        slider.value = aligne.toFloat()
        valeur.text = getString(R.string.autobri_percent, aligne)
        slider.addOnChangeListener { _, v, fromUser ->
            valeur.text = getString(R.string.autobri_percent, v.toInt())
            if (fromUser) prefs.edit().putInt(key, AutoBrightnessSettings.clamp(v.toInt())).apply()
        }
    }

    private fun afficherEtatLuminosite(tv: TextView, prefs: android.content.SharedPreferences) {
        val quand = prefs.getLong(AutoBrightnessSettings.KEY_LAST_AT, 0L)
        if (quand <= 0L) {
            tv.setText(R.string.autobri_status_none)
            return
        }
        val derniere = prefs.getString(AutoBrightnessSettings.KEY_LAST_SOURCE, null)
        val source = when (derniere) {
            AutoBrightness.Source.LIGHTS.name     -> getString(R.string.autobri_source_lights)
            AutoBrightness.Source.LIGHTS_OFF.name -> getString(R.string.autobri_source_lights_off)
            AutoBrightness.Source.FORECAST.name   -> getString(R.string.autobri_source_forecast)
            else                                  -> getString(R.string.autobri_source_sun)
        }
        val heure = android.text.format.DateFormat.getTimeFormat(requireContext()).format(Date(quand))
        val pourcent = prefs.getInt(AutoBrightnessSettings.KEY_LAST_PERCENT, 0)
        // Des lux seulement pour une lumière ESTIMÉE (météo, soleil) : un réglage d'après les feux
        // n'en a pas — au mieux la valeur conventionnelle du point Nuit.
        val feux = derniere == AutoBrightness.Source.LIGHTS.name ||
            derniere == AutoBrightness.Source.LIGHTS_OFF.name
        val etat = if (feux) {
            getString(R.string.autobri_status_lights, heure, source, pourcent)
        } else {
            val lux = String.format(Locale.getDefault(), "%,d",
                prefs.getFloat(AutoBrightnessSettings.KEY_LAST_LUX, 0f).toDouble().roundToLong())
            getString(R.string.autobri_status, heure, source, lux, pourcent)
        }
        tv.text = if (prefs.getBoolean(AutoBrightnessSettings.KEY_PAUSED, false))
            etat + "\n" + getString(R.string.autobri_status_paused) else etat
    }

    // ══════════ Automatisation « Déclenchement A/C via la température » ══════════

    /**
     * Les deux règles (chaud / froid) ont exactement la même structure : on les câble via
     * [bindClimateRule] plutôt que de dupliquer leurs écouteurs, sinon une correction sur l'une
     * finit tôt ou tard par manquer sur l'autre.
     *
     * Les réglages partagent le fichier de préférences des profils ([AutomationSettings.PREFS])
     * mais pas leur interrupteur : cette automatisation n'est pas une application de profil.
     */
    private fun bindClimateAutomation(view: View, prefs: android.content.SharedPreferences) {
        val card = view.findViewById<View>(R.id.card_ac_automation)
        // Firmware inconnu = aucune voie clim → afficher des réglages sans effet serait trompeur.
        if (!MG4Hardware.hasClimateControl()) {
            card.visibility = View.GONE
            return
        }

        val switchAc = view.findViewById<Switch>(R.id.switch_ac_auto)
        val rowConfig = view.findViewById<View>(R.id.row_ac_auto_config)

        val enabled = prefs.getBoolean(ClimateAutomationSettings.KEY_ENABLED, false)
        switchAc.isChecked = enabled
        val deplier = bindExpander(view.findViewById(R.id.btn_ac_auto_expand), rowConfig, expanded = enabled)
        switchAc.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ClimateAutomationSettings.KEY_ENABLED, checked).apply()
            deplier(checked)
        }

        // Mode de déclenchement : trois boutons et UNE ligne d'aide, celle du mode choisi. Les
        // trois paragraphes affichés ensemble noyaient le choix sous son mode d'emploi. L'état
        // vient de la lecture des réglages, qui reprend aussi l'ancienne case à cocher.
        val modes = listOf(
            Triple(R.id.btn_ac_trigger_start, ClimateAutomationSettings.Trigger.START_ONLY,
                R.string.ac_auto_trigger_start_hint),
            Triple(R.id.btn_ac_trigger_once, ClimateAutomationSettings.Trigger.ONCE_PER_START,
                R.string.ac_auto_trigger_once_hint),
            Triple(R.id.btn_ac_trigger_ready, ClimateAutomationSettings.Trigger.EVERY_READY,
                R.string.ac_auto_trigger_ready_hint),
        )
        val aide = view.findViewById<TextView>(R.id.tv_ac_trigger_hint)
        fun majMode(actuel: ClimateAutomationSettings.Trigger) {
            modes.forEach { (id, mode, texte) ->
                peindreChoix(view.findViewById(id), mode == actuel)
                if (mode == actuel) aide.setText(texte)
            }
        }
        majMode(ClimateAutomationSettings.read(view.context).trigger)
        modes.forEach { (id, mode, _) ->
            view.findViewById<MaterialButton>(id).setOnClickListener {
                prefs.edit().putString(ClimateAutomationSettings.KEY_TRIGGER, mode.name).apply()
                majMode(mode)
            }
        }

        val chaud = bindClimateRule(view, prefs, hot = true, ids = RegleIds(
            interrupteur = R.id.switch_ac_hot, eteinte = R.id.tv_ac_hot_off, contenu = R.id.row_ac_hot_config,
            seuil = R.id.slider_ac_hot_threshold, seuilValeur = R.id.tv_ac_hot_threshold,
            cible = R.id.slider_ac_hot_target, cibleValeur = R.id.tv_ac_hot_target,
            ventAuto = R.id.btn_ac_hot_fan_auto, ventManuelle = R.id.btn_ac_hot_fan_manual,
            rangeeNiveau = R.id.row_ac_hot_fan, niveau = R.id.slider_ac_hot_fan, niveauValeur = R.id.tv_ac_hot_fan,
            degivrageAvant = R.id.btn_ac_hot_def_front, degivrageArriere = R.id.btn_ac_hot_def_rear,
            rangeeVentilation = R.id.row_ac_hot_fan_mode, rangeeDegivrage = R.id.row_ac_hot_defrost,
            recircLigne1 = R.id.row_ac_hot_recirc_a, recircLigne2 = R.id.row_ac_hot_recirc_b,
            recirc = listOf(R.id.btn_ac_hot_recirc_keep, R.id.btn_ac_hot_recirc_inner,
                R.id.btn_ac_hot_recirc_outside, R.id.btn_ac_hot_recirc_auto)
        ))
        val froid = bindClimateRule(view, prefs, hot = false, ids = RegleIds(
            interrupteur = R.id.switch_ac_cold, eteinte = R.id.tv_ac_cold_off, contenu = R.id.row_ac_cold_config,
            seuil = R.id.slider_ac_cold_threshold, seuilValeur = R.id.tv_ac_cold_threshold,
            cible = R.id.slider_ac_cold_target, cibleValeur = R.id.tv_ac_cold_target,
            ventAuto = R.id.btn_ac_cold_fan_auto, ventManuelle = R.id.btn_ac_cold_fan_manual,
            rangeeNiveau = R.id.row_ac_cold_fan, niveau = R.id.slider_ac_cold_fan, niveauValeur = R.id.tv_ac_cold_fan,
            degivrageAvant = R.id.btn_ac_cold_def_front, degivrageArriere = R.id.btn_ac_cold_def_rear,
            rangeeVentilation = R.id.row_ac_cold_fan_mode, rangeeDegivrage = R.id.row_ac_cold_defrost,
            recircLigne1 = R.id.row_ac_cold_recirc_a, recircLigne2 = R.id.row_ac_cold_recirc_b,
            recirc = listOf(R.id.btn_ac_cold_recirc_keep, R.id.btn_ac_cold_recirc_inner,
                R.id.btn_ac_cold_recirc_outside, R.id.btn_ac_cold_recirc_auto)
        ))
        lierSeuils(prefs, chaud, froid)

        // Consigne et ventilation aux bornes de LA VOITURE (16–32 °C et 7 crans sur l'ancien SDK,
        // 17–33 °C et 11 crans sur A9) : un curseur qui va plus loin que la voiture proposerait
        // des valeurs qu'elle ramènerait en silence. Lecture véhicule → hors du thread principal ;
        // tant qu'elle n'a pas abouti (voiture éteinte, banc), les bornes de saisie restent.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val etat = MG4Hardware.getClimateState() ?: return@launch
            withContext(Dispatchers.Main) {
                listOf(chaud, froid).forEach {
                    borner(it.cible, etat.tempMin, etat.tempMax)
                    borner(it.niveau, etat.fanMin, etat.fanMax)
                }
            }
        }
    }

    /** Identifiants des vues d'une règle : les deux colonnes ont la même structure. */
    private class RegleIds(
        val interrupteur: Int, val eteinte: Int, val contenu: Int,
        val seuil: Int, val seuilValeur: Int,
        val cible: Int, val cibleValeur: Int,
        val ventAuto: Int, val ventManuelle: Int,
        val rangeeNiveau: Int, val niveau: Int, val niveauValeur: Int,
        val degivrageAvant: Int, val degivrageArriere: Int,
        val rangeeVentilation: Int, val rangeeDegivrage: Int,
        val recircLigne1: Int, val recircLigne2: Int,
        /** Dans l'ordre de [ClimateRuleEditor.recircChoice] : inchangée, intérieur, extérieur, auto. */
        val recirc: List<Int>
    )

    /** Ce qu'il faut garder d'une règle une fois câblée : ses seuils sont liés à ceux de l'autre. */
    private class RegleClim(val seuil: Slider, val seuilValeur: TextView, val cible: Slider, val niveau: Slider)

    private fun bindClimateRule(
        view: View, prefs: android.content.SharedPreferences, hot: Boolean, ids: RegleIds
    ): RegleClim {
        // ── Interrupteur de la règle ─────────────────────────────────────────
        val interrupteur = view.findViewById<Switch>(ids.interrupteur)
        val contenu      = view.findViewById<View>(ids.contenu)
        val eteinte      = view.findViewById<View>(ids.eteinte)
        fun majActive(active: Boolean) {
            contenu.visibility = if (active) View.VISIBLE else View.GONE
            eteinte.visibility = if (active) View.GONE else View.VISIBLE
        }
        interrupteur.isChecked = prefs.getBoolean(ClimateAutomationSettings.keyOn(hot), false)
        majActive(interrupteur.isChecked)
        interrupteur.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ClimateAutomationSettings.keyOn(hot), checked).apply()
            majActive(checked)
        }

        // ── Seuil extérieur ──────────────────────────────────────────────────
        // Son écouteur est posé par [lierSeuils] : il dépend du seuil de l'autre règle.
        val seuil       = view.findViewById<Slider>(ids.seuil)
        val seuilValeur = view.findViewById<TextView>(ids.seuilValeur)
        val seuilInitial = prefs.getInt(ClimateAutomationSettings.keyThreshold(hot),
            if (hot) ClimateAutomationSettings.DEFAULT_HOT_THRESHOLD
            else ClimateAutomationSettings.DEFAULT_COLD_THRESHOLD)
        poser(seuil, ClimateAutomationSettings.MIN_TEMP, ClimateAutomationSettings.MAX_TEMP, seuilInitial)
        seuilValeur.text = ClimateRuleEditor.thresholdLabel(hot, seuil.value.toInt())

        // ── Consigne ─────────────────────────────────────────────────────────
        val cible       = view.findViewById<Slider>(ids.cible)
        val cibleValeur = view.findViewById<TextView>(ids.cibleValeur)
        poser(cible, ClimateAutomationSettings.MIN_TARGET, ClimateAutomationSettings.MAX_TARGET,
            prefs.getInt(ClimateAutomationSettings.keyTarget(hot),
                if (hot) ClimateAutomationSettings.DEFAULT_HOT_TARGET
                else ClimateAutomationSettings.DEFAULT_COLD_TARGET))
        cibleValeur.text = getString(R.string.profile_hvac_temp_value, cible.value.toInt())
        cible.addOnChangeListener { _, v, fromUser ->
            cibleValeur.text = getString(R.string.profile_hvac_temp_value, v.toInt())
            if (fromUser) prefs.edit()
                .putInt(ClimateAutomationSettings.keyTarget(hot), ClimateAutomationSettings.clampTarget(v.toInt(), hot))
                .apply()
        }

        // ── Ventilation : automatique ou manuelle ────────────────────────────
        // Régler une vitesse fait sortir du mode auto sur ce véhicule : les deux s'excluent. En
        // auto, le curseur du niveau reste À SA PLACE mais grisé — le masquer décalerait les
        // lignes de cette colonne par rapport à l'autre.
        val ventAuto     = view.findViewById<MaterialButton>(ids.ventAuto)
        val ventManuelle = view.findViewById<MaterialButton>(ids.ventManuelle)
        val rangeeNiveau = view.findViewById<View>(ids.rangeeNiveau)
        val niveau       = view.findViewById<Slider>(ids.niveau)
        val niveauValeur = view.findViewById<TextView>(ids.niveauValeur)
        var auto = prefs.getBoolean(ClimateAutomationSettings.keyAuto(hot), false)
        poser(niveau, ClimateAutomationSettings.MIN_FAN, ClimateAutomationSettings.MAX_FAN,
            prefs.getInt(ClimateAutomationSettings.keyFan(hot), ClimateAutomationSettings.DEFAULT_FAN))
        fun majVentilation() {
            peindreChoix(ventAuto, auto)
            peindreChoix(ventManuelle, !auto)
            rangeeNiveau.alpha = if (auto) 0.4f else 1f
            niveau.isEnabled = !auto
            niveauValeur.text = if (auto) getString(R.string.clim_auto) else niveau.value.toInt().toString()
        }
        majVentilation()
        fun choisirVentilation(automatique: Boolean) {
            auto = automatique
            prefs.edit().putBoolean(ClimateAutomationSettings.keyAuto(hot), automatique).apply()
            majVentilation()
        }
        ventAuto.setOnClickListener { choisirVentilation(true) }
        ventManuelle.setOnClickListener { choisirVentilation(false) }
        niveau.addOnChangeListener { _, v, fromUser ->
            majVentilation()
            if (fromUser) prefs.edit()
                .putInt(ClimateAutomationSettings.keyFan(hot), ClimateAutomationSettings.clampFan(v.toInt()))
                .apply()
        }

        // ── Dégivrages ───────────────────────────────────────────────────────
        fun bascule(id: Int, cle: String) {
            val btn = view.findViewById<MaterialButton>(id)
            var on = prefs.getBoolean(cle, false)
            peindreChoix(btn, on)
            btn.setOnClickListener {
                on = !on
                prefs.edit().putBoolean(cle, on).apply()
                peindreChoix(btn, on)
            }
        }
        bascule(ids.degivrageAvant, ClimateAutomationSettings.keyDefFront(hot))
        bascule(ids.degivrageArriere, ClimateAutomationSettings.keyDefRear(hot))

        // ── Recirculation ────────────────────────────────────────────────────
        // « Inchangé » = on ne touche PAS au recyclage. C'est le défaut, pour qu'une
        // automatisation déjà configurée ne se mette pas à piloter un réglage qu'elle ne
        // pilotait pas. Le mode enregistré est gardé : y revenir retrouve le dernier choisi.
        val recirc = ids.recirc.map { view.findViewById<MaterialButton>(it) }
        fun majRecirc() {
            val choix = ClimateRuleEditor.recircChoice(
                prefs.getBoolean(ClimateAutomationSettings.keyRecircForce(hot), false),
                prefs.getInt(ClimateAutomationSettings.keyRecirc(hot), ClimateAutomationSettings.DEFAULT_LOOP))
            recirc.forEachIndexed { i, b -> peindreChoix(b, i == choix) }
        }
        majRecirc()
        recirc.forEachIndexed { i, b ->
            b.setOnClickListener {
                prefs.edit().apply {
                    putBoolean(ClimateAutomationSettings.keyRecircForce(hot), ClimateRuleEditor.recircForce(i))
                    ClimateRuleEditor.recircMode(i)?.let { putInt(ClimateAutomationSettings.keyRecirc(hot), it) }
                }.apply()
                majRecirc()
            }
        }

        // Quatre boutons sur une ligne quand la place le permet ; sinon trois, et « Auto » passe
        // sur la seconde ligne, sous le premier. Les autres rangées de boutons suivent le même
        // nombre de colonnes : c'est ce qui garde tous les boutons alignés entre eux. La largeur
        // n'est connue qu'une fois la carte dépliée, d'où l'écoute de la mise en page.
        val ligne1  = view.findViewById<LinearLayout>(ids.recircLigne1)
        val ligne2  = view.findViewById<LinearLayout>(ids.recircLigne2)
        val rangees = listOf(ligne1, ligne2,
            view.findViewById(ids.rangeeVentilation), view.findViewById(ids.rangeeDegivrage))
        val dernier = recirc.last()
        var colonnes = 4
        fun ranger(n: Int) {
            if (n == colonnes) return
            colonnes = n
            rangees.forEach { it.weightSum = n.toFloat() }
            (dernier.parent as? ViewGroup)?.removeView(dernier)
            (if (n == 4) ligne1 else ligne2).addView(dernier)
            ligne2.visibility = if (n == 4) View.GONE else View.VISIBLE
        }
        ligne1.addOnLayoutChangeListener { v, gauche, _, droite, _, _, _, _, _ ->
            val largeurDp = (droite - gauche) / v.resources.displayMetrics.density
            val n = ClimateRuleEditor.recircColumns(largeurDp)
            // Jamais pendant la passe de mise en page elle-même : on déplace une vue.
            if (largeurDp > 0f && n != colonnes) v.post { ranger(n) }
        }

        return RegleClim(seuil, seuilValeur, cible, niveau)
    }

    /**
     * Lie les deux seuils : le froid reste sous le chaud ([ClimateRuleEditor.hotThreshold]).
     * Un curseur poussé trop loin revient à la limite, et c'est la limite qui est enregistrée.
     */
    private fun lierSeuils(prefs: android.content.SharedPreferences, chaud: RegleClim, froid: RegleClim) {
        fun lier(regle: RegleClim, autre: RegleClim, hot: Boolean) {
            regle.seuil.addOnChangeListener { curseur, v, fromUser ->
                val voulu  = v.toInt()
                val limite = autre.seuil.value.toInt()
                val retenu = when {
                    !fromUser -> voulu
                    hot       -> ClimateRuleEditor.hotThreshold(voulu, limite)
                    else      -> ClimateRuleEditor.coldThreshold(voulu, limite)
                }
                regle.seuilValeur.text = ClimateRuleEditor.thresholdLabel(hot, retenu)
                if (fromUser) {
                    prefs.edit().putInt(ClimateAutomationSettings.keyThreshold(hot), retenu).apply()
                    if (retenu != voulu) curseur.value = retenu.toFloat()
                }
            }
        }
        lier(chaud, froid, hot = true)
        lier(froid, chaud, hot = false)
    }

    /** Pose bornes et valeur d'un curseur à pas de 1, la valeur ramenée dans les bornes. */
    private fun poser(curseur: Slider, min: Int, max: Int, valeur: Int) {
        curseur.valueFrom = min.toFloat()
        curseur.valueTo   = max.toFloat()
        curseur.stepSize  = 1f
        curseur.value     = ClimateRuleEditor.sliderValue(valeur, min, max).toFloat()
    }

    /** Resserre un curseur sur les bornes lues sur la voiture ; des bornes incohérentes sont ignorées. */
    private fun borner(curseur: Slider, min: Int, max: Int) {
        if (max > min) poser(curseur, min, max, curseur.value.toInt())
    }

    /** Bouton de choix allumé ou éteint — les couleurs du rail des autres écrans. */
    private fun peindreChoix(btn: MaterialButton, on: Boolean) {
        val ctx = btn.context
        btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn))
        btn.setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
        btn.strokeColor = ColorStateList.valueOf(ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border))
    }

    override fun onResume() {
        super.onResume()
        // Les profils peuvent avoir changé dans l'onglet Profils → on recharge la liste.
        val prefs = requireContext().getSharedPreferences(AutomationSettings.PREFS, Context.MODE_PRIVATE)
        view?.findViewById<Spinner>(R.id.spinner_automation_profile)?.let { sp ->
            setupSpinner(sp, prefs, AutomationSettings.KEY_PROFILE_ID)
        }
        view?.findViewById<Spinner>(R.id.spinner_battery_auto_profile)?.let { sp ->
            setupSpinner(sp, prefs, BatteryAutomationSettings.KEY_PROFILE_ID)
        }
        // Un passage en READY a pu régler l'écran pendant qu'on était ailleurs.
        view?.findViewById<TextView>(R.id.autobri_status)?.let { afficherEtatLuminosite(it, prefs) }
        // Carte des vitres : le sondage ne reprend que si elle est restée dépliée.
        if (view?.findViewById<View>(R.id.row_windows_config)?.visibility == View.VISIBLE)
            windowsPanel.onShown()
    }

    /** Liste des profils ; [key] désigne l'automatisation dont on enregistre le choix. */
    private fun setupSpinner(spinner: Spinner, prefs: android.content.SharedPreferences, key: String) {
        profiles = ProfileManager(requireContext()).getAll()
        val labels = if (profiles.isEmpty()) listOf(getString(R.string.automation_no_profile))
                     else profiles.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.isEnabled = profiles.isNotEmpty()

        // Positionne sur le profil déjà configuré.
        val savedId = prefs.getString(key, "") ?: ""
        val idx = profiles.indexOfFirst { it.id == savedId }
        if (idx >= 0) spinner.setSelection(idx)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (profiles.isEmpty()) return
                prefs.edit().putString(key, profiles[position].id).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Chevron de depliage d'une carte d'automatisation, et son lien avec l'interrupteur
     * d'activation : la carte s'ouvre depliee si l'automatisation est active, l'activer la deplie,
     * la desactiver la replie. Entre-temps le chevron reste libre — on peut consulter le
     * parametrage d'une automatisation eteinte, ou replier une automatisation active.
     *
     * Rend la fonction que l'interrupteur appelle avec son nouvel etat.
     */
    private fun bindExpander(
        btn: MaterialButton, content: View, expanded: Boolean, onToggle: ((Boolean) -> Unit)? = null
    ): (Boolean) -> Unit {
        var open = expanded
        fun apply() {
            content.visibility = if (open) View.VISIBLE else View.GONE
            btn.text = if (open) "▾" else "▸"   // chevron bas / droite
            onToggle?.invoke(open)
        }
        apply()
        btn.setOnClickListener { open = !open; apply() }
        // Pour l'interrupteur d'activation : activer déplie, désactiver replie (décision du 2026-10-02).
        return { voulu -> if (voulu != open) { open = voulu; apply() } }
    }

    /**
     * Carte « Fermeture automatique des vitres électriques » : dépliée à l'ouverture quand
     * l'option est activée, comme les autres automatisations.
     *
     * Le sondage des positions (et donc l'abonnement véhicule) ne tourne que carte ouverte ET
     * écran au premier plan — ailleurs il consommerait pour une valeur que personne ne regarde.
     * Dépliée d'office, la carte n'est pas encore au premier plan : [onResume] prend le relais.
     */
    private fun bindWindowsCard(view: View) {
        val content = view.findViewById<View>(R.id.row_windows_config)
        val ouverte = WindowAutoClose.isEnabled(requireContext())
        val deplier = bindExpander(view.findViewById(R.id.btn_windows_expand), content, expanded = ouverte) { open ->
            if (open && isResumed) windowsPanel.onShown() else windowsPanel.onHidden()
        }
        // L'interrupteur vit dans le panneau : il nous rend son nouvel état.
        windowsPanel.bind(view, onAutoCloseToggled = deplier)
    }

    /** Écran quitté : plus de sondage, et aucune vitre ne reste en mouvement sans surveillance. */
    override fun onPause() {
        super.onPause()
        windowsPanel.onHidden()
    }

}
