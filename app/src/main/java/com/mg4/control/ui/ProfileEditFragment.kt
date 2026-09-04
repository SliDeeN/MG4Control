package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.AebSensitivity
import com.mg4.control.hardware.MG4Hardware.ElkMode
import com.mg4.control.hardware.MG4Hardware.ElkSensitivity
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Création / édition d'un profil, en plein écran (auparavant un AlertDialog trop à l'étroit).
 *
 * Les options sont réparties en trois catégories sélectionnées par le rail de gauche ; le nom du
 * profil et le réglage « par défaut » restent visibles quelle que soit la catégorie, car ils
 * n'appartiennent à aucune d'elles.
 */
class ProfileEditFragment : Fragment() {

    companion object {
        /** Bornes des curseurs de climatisation — les VRAIES limites sont lues sur le véhicule
         *  au moment d'appliquer, et la consigne y est clampée. Celles-ci ne servent qu'à la
         *  saisie, et doivent couvrir tous les firmwares (A9 accepte 17–33 °C et 1–11). */
        const val HVAC_TEMP_MIN = 15
        const val HVAC_TEMP_MAX = 33
        const val HVAC_FAN_MIN  = 1
        const val HVAC_FAN_MAX  = 11

        /**
         * Passage de données depuis [ProfileFragment]. Un profil complet ne tient pas
         * confortablement dans un Bundle (enums + une vingtaine de champs) et l'écran n'est ouvert
         * que depuis la liste, dans le même processus.
         *
         * `pendingData` = valeurs à afficher (profil existant, ou pré-remplissage lu sur la
         * voiture) ; `pendingExisting` = profil édité, ou null en création.
         */
        @Volatile var pendingData: DrivingProfile? = null
        @Volatile var pendingExisting: DrivingProfile? = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Après une mort du processus, le fragment est recréé sans les données transmises :
        // on retourne à la liste plutôt que d'afficher un formulaire vide.
        val data = pendingData
        if (data == null) {
            findNavController().popBackStack()
            return
        }
        val existing = pendingExisting
        val manager = ProfileManager(requireContext())
        val ctx = requireContext()
        val gen = FirmwareInfo.getGeneration()

        // ── Couleurs ─────────────────────────────────────────────────────────
        fun activateBtn(btn: MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent_dim else R.color.dash_btn))
            btn.setTextColor(ctx.getColor(
                if (active) R.color.dash_accent else R.color.text_secondary))
            btn.strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent else R.color.dash_border))
        }

        /** Lie un groupe de boutons : un seul actif à la fois. */
        fun <T> bindGroup(pairs: List<Pair<MaterialButton, T>>, initial: T, onSelect: (T) -> Unit) {
            pairs.forEach { (btn, value) -> activateBtn(btn, value == initial) }
            pairs.forEach { (btn, value) ->
                btn.setOnClickListener {
                    pairs.forEach { (b, v) -> activateBtn(b, v == value) }
                    onSelect(value)
                }
            }
        }

        /** Grise un groupe de boutons sans les masquer (l'utilisateur voit ce qui est configuré). */
        fun setBtnsEnabled(btns: List<MaterialButton?>, enabled: Boolean) {
            btns.forEach { btn ->
                btn?.isEnabled = enabled
                btn?.alpha = if (enabled) 1f else 0.35f
            }
        }

        // ── Variables de sélection ───────────────────────────────────────────
        var selectedBtMac: String? = data.btDeviceMac   // [BT-PROFILES]
        var selectedDrive   = data.driveMode
        var selectedRegen   = data.regenLevel
        var steeringOn      = data.steeringHeat
        var steeringEnabled = data.appliesSteeringHeat
        var seatHeatEnabled = data.appliesSeatHeat
        var seatLeft        = data.seatHeatLeft
        var seatRight       = data.seatHeatRight
        var hvacEnabledSel  = data.hvacEnabled
        var hvacPowerSel    = data.hvacPower
        var hvacAcSel       = data.hvacAc
        var hvacAutoSel     = data.hvacAuto
        // ⚠️ CLAMPÉS À LA LECTURE, et ce n'est pas de la prudence gratuite : Gson n'appelle pas
        // le constructeur Kotlin, donc un profil enregistré avant cette fonctionnalité rend 0
        // pour ces deux champs. Le curseur Material LÈVE une exception si on lui pose une valeur
        // hors de [valueFrom, valueTo] — l'éditeur planterait à l'ouverture d'un ancien profil.
        var hvacTempSel     = data.hvacTemp.coerceIn(HVAC_TEMP_MIN, HVAC_TEMP_MAX)
        var hvacFanSel      = data.hvacFan.coerceIn(HVAC_FAN_MIN, HVAC_FAN_MAX)
        var hvacDfSel       = data.hvacDefrostFront
        var hvacDrSel       = data.hvacDefrostRear
        var hvacLoopSel     = data.hvacLoopMode
        // Index 0/1/2, ou null quand le profil ne s'est jamais prononcé (créé avant la
        // fonctionnalité). L'éditeur propose alors Normal, mais rien n'est enregistré tant que
        // l'utilisateur n'a pas sauvegardé — un ancien profil Personnalisé ne se met donc pas à
        // écrire ces réglages du seul fait qu'on l'a ouvert.
        var customPowerSel  = data.customPower
        var customSteerSel  = data.customSteering
        var customPedalSel  = data.customPedal
        var adasMode        = data.adasMode
        var swi68Mode       = data.swi68AdasMode
        var swi132SasMode   = data.swi132SasMode                       // 0=Off, 2=Manuel, 3=Intelligent
        var swi132LimiterConfigured = data.swi132LimiterConfigured
        var aebEnabledSel   = data.aebEnabled
        var aebModeSel      = data.aebMode
        var aebSenSel       = data.aebSensitivity.let { if (it == 0) AebSensitivity.STANDARD else it }
        // Accesseurs et non champs bruts : ils rendent les défauts (ON / ON / Standard) pour un
        // profil enregistré avant la fonctionnalité, dont les champs valent null.
        var escSel          = data.appliesEsc
        var dmsSel          = data.appliesDrowsiness
        var dmsSenSel       = data.appliesDrowsinessSensitivity
        var elkModeSel      = data.elkMode.let { if (it == 0) ElkMode.EMERGENCY else it }
        var elkSenSel       = data.elkSensitivity.let { if (it == 0) ElkSensitivity.STANDARD else it }
        var elkEnabledSel   = elkModeSel != ElkMode.OFF
        /** Dernier mode ELK actif pour restauration après toggle ON */
        var lastActiveElkModeD = if (elkModeSel != ElkMode.OFF) elkModeSel else ElkMode.EMERGENCY
        var lasAudibleWarningSel    = data.lasAudibleWarning
        var lasVibrationReminderSel = data.lasVibrationReminder
        var energySavingSel = data.energySaving
        var tsrEnabledSel   = data.tsrEnabled

        // ── Bouton Éco énergie — déclaré tôt pour le binding du mode de conduite ─
        val btnEnergy = view.findViewById<MaterialButton>(R.id.btn_energy_saving_d)

        // ── Mode de conduite ─────────────────────────────────────────────────
        val drivePairs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_drive_eco_d)    to DriveMode.ECO,
            view.findViewById<MaterialButton>(R.id.btn_drive_normal_d) to DriveMode.NORMAL,
            view.findViewById<MaterialButton>(R.id.btn_drive_sport_d)  to DriveMode.SPORT,
            view.findViewById<MaterialButton>(R.id.btn_drive_snow_d)   to DriveMode.SNOW,
            view.findViewById<MaterialButton>(R.id.btn_drive_custom_d) to DriveMode.CUSTOM
        )
        val regenBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_regen_off_d),
            view.findViewById<MaterialButton>(R.id.btn_regen_low_d),
            view.findViewById<MaterialButton>(R.id.btn_regen_medium_d),
            view.findViewById<MaterialButton>(R.id.btn_regen_high_d),
            view.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d),
            view.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d)
        )
        val btnOnePedal = view.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d)

        fun setRegenEnabled(enabled: Boolean) {
            val isSnow = selectedDrive == DriveMode.SNOW
            regenBtns.forEach { btn ->
                // ONE_PEDAL reste accessible même quand Éco énergie est actif,
                // sauf en mode SNOW où tous les niveaux de regen sont indisponibles.
                val btnEnabled = enabled || (btn == btnOnePedal && !isSnow)
                btn.isEnabled = btnEnabled
                btn.alpha = if (btnEnabled) 1f else 0.35f
            }
        }

        /**
         * La carte suit le mode CHOISI dans le formulaire, pas l'état du véhicule : c'est un
         * profil qu'on édite, il peut très bien être composé voiture éteinte.
         */
        fun majCustomDrive() {
            view.findViewById<View>(R.id.section_custom_drive_d)?.visibility =
                if (selectedDrive == DriveMode.CUSTOM) View.VISIBLE else View.GONE
        }

        bindGroup(drivePairs, selectedDrive) { mode ->
            selectedDrive = mode
            val isSnow = mode == DriveMode.SNOW
            setRegenEnabled(!isSnow && !energySavingSel)
            if (gen != FirmwareInfo.Gen.UNKNOWN) {
                btnEnergy.isEnabled = !isSnow
                btnEnergy.alpha = if (isSnow) 0.35f else 1f
            }
            majCustomDrive()
        }
        setRegenEnabled(data.driveMode != DriveMode.SNOW && !energySavingSel)
        majCustomDrive()

        // ── Régénération ─────────────────────────────────────────────────────
        val regenPairs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_regen_off_d)       to RegenLevel.OFF,
            view.findViewById<MaterialButton>(R.id.btn_regen_low_d)       to RegenLevel.LOW,
            view.findViewById<MaterialButton>(R.id.btn_regen_medium_d)    to RegenLevel.MEDIUM,
            view.findViewById<MaterialButton>(R.id.btn_regen_high_d)      to RegenLevel.HIGH,
            view.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d)  to RegenLevel.ADAPTIVE,
            view.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d) to RegenLevel.ONE_PEDAL
        )
        bindGroup(regenPairs, selectedRegen) { selectedRegen = it }

        // ── Mode Personnalisé : puissance, direction, pédale ─────────────────
        val cdPower = listOf(
            view.findViewById<MaterialButton>(R.id.btn_cd_power_eco_d)    to 0,
            view.findViewById<MaterialButton>(R.id.btn_cd_power_normal_d) to 1,
            view.findViewById<MaterialButton>(R.id.btn_cd_power_sport_d)  to 2
        )
        val cdSteer = listOf(
            view.findViewById<MaterialButton>(R.id.btn_cd_steer_comfort_d) to 0,
            view.findViewById<MaterialButton>(R.id.btn_cd_steer_normal_d)  to 1,
            view.findViewById<MaterialButton>(R.id.btn_cd_steer_sport_d)   to 2
        )
        val cdPedal = listOf(
            view.findViewById<MaterialButton>(R.id.btn_cd_pedal_comfort_d) to 0,
            view.findViewById<MaterialButton>(R.id.btn_cd_pedal_normal_d)  to 1,
            view.findViewById<MaterialButton>(R.id.btn_cd_pedal_sport_d)   to 2
        )
        // Normal proposé par défaut : la carte n'apparaît que sur Personnalisé, il faut donc
        // qu'elle montre ce qui sera appliqué plutôt qu'une rangée vide.
        bindGroup(cdPower, customPowerSel ?: 1) { customPowerSel = it }
        bindGroup(cdSteer, customSteerSel ?: 1) { customSteerSel = it }
        bindGroup(cdPedal, customPedalSel ?: 1) { customPedalSel = it }

        // ── Volant chauffant + prise en compte ───────────────────────────────
        val steerBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_steer_off_d),
            view.findViewById<MaterialButton>(R.id.btn_steer_on_d)
        )
        bindGroup(listOf(steerBtns[0] to false, steerBtns[1] to true), steeringOn) { steeringOn = it }

        val swSteering = view.findViewById<Switch>(R.id.sw_steering_enabled)
        swSteering.isChecked = steeringEnabled
        setBtnsEnabled(steerBtns, steeringEnabled)
        swSteering.setOnCheckedChangeListener { _, checked ->
            steeringEnabled = checked
            setBtnsEnabled(steerBtns, checked)
        }

        // ── Sièges chauffants + prise en compte ──────────────────────────────
        val seatLeftBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_sl_0_d),
            view.findViewById<MaterialButton>(R.id.btn_sl_1_d),
            view.findViewById<MaterialButton>(R.id.btn_sl_2_d),
            view.findViewById<MaterialButton>(R.id.btn_sl_3_d)
        )
        val seatRightBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_sr_0_d),
            view.findViewById<MaterialButton>(R.id.btn_sr_1_d),
            view.findViewById<MaterialButton>(R.id.btn_sr_2_d),
            view.findViewById<MaterialButton>(R.id.btn_sr_3_d)
        )
        bindGroup(seatLeftBtns.mapIndexed  { i, b -> b to i }, seatLeft)  { seatLeft = it }
        bindGroup(seatRightBtns.mapIndexed { i, b -> b to i }, seatRight) { seatRight = it }

        val swSeatHeat = view.findViewById<Switch>(R.id.sw_seat_heat_enabled)
        swSeatHeat.isChecked = seatHeatEnabled
        setBtnsEnabled(seatLeftBtns + seatRightBtns, seatHeatEnabled)
        swSeatHeat.setOnCheckedChangeListener { _, checked ->
            seatHeatEnabled = checked
            setBtnsEnabled(seatLeftBtns + seatRightBtns, checked)
        }

        // ── Climatisation — bloc facultatif du profil ────────────────────────
        val swHvac    = view.findViewById<Switch>(R.id.sw_hvac_enabled)
        val btnPower  = view.findViewById<MaterialButton>(R.id.btn_hvac_power)
        val btnAc     = view.findViewById<MaterialButton>(R.id.btn_hvac_ac)
        val btnAuto   = view.findViewById<MaterialButton>(R.id.btn_hvac_auto)
        val sldTemp   = view.findViewById<Slider>(R.id.sld_hvac_temp)
        val tvTemp    = view.findViewById<TextView>(R.id.tv_hvac_temp)
        val sldFan    = view.findViewById<Slider>(R.id.sld_hvac_fan)
        val tvFan     = view.findViewById<TextView>(R.id.tv_hvac_fan)
        val loopBtns  = listOf(
            view.findViewById<MaterialButton>(R.id.btn_hvac_loop_in),
            view.findViewById<MaterialButton>(R.id.btn_hvac_loop_out),
            view.findViewById<MaterialButton>(R.id.btn_hvac_loop_auto),
            view.findViewById<MaterialButton>(R.id.btn_hvac_loop_none)
        )
        val dfBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_hvac_df_off),
            view.findViewById<MaterialButton>(R.id.btn_hvac_df_on),
            view.findViewById<MaterialButton>(R.id.btn_hvac_df_none)
        )
        val drBtns = listOf(
            view.findViewById<MaterialButton>(R.id.btn_hvac_dr_off),
            view.findViewById<MaterialButton>(R.id.btn_hvac_dr_on),
            view.findViewById<MaterialButton>(R.id.btn_hvac_dr_none)
        )

        /**
         * Trois grisages en cascade, qui reproduisent des règles du véhicule et non des choix
         * d'interface :
         *  • interrupteur sur OFF → le profil ne touche pas à la clim, rien n'est modifiable ;
         *  • clim éteinte → consigne, ventilation et le reste n'ont plus de sens ;
         *  • mode AUTO → la ventilation manuelle est exclue, régler une vitesse ferait sortir
         *    du mode auto (voir MG4Hardware.applyProfileClimate).
         */
        fun majHvac() {
            val actif  = hvacEnabledSel
            val allume = actif && hvacPowerSel
            setBtnsEnabled(listOf(btnPower), actif)
            setBtnsEnabled(listOf(btnAc, btnAuto) + loopBtns + dfBtns + drBtns, allume)
            listOf(sldTemp, sldFan).forEach { it.isEnabled = allume }
            sldFan.isEnabled = allume && !hvacAutoSel
            sldTemp.alpha = if (allume) 1f else 0.35f
            sldFan.alpha  = if (allume && !hvacAutoSel) 1f else 0.35f
            tvTemp.alpha  = sldTemp.alpha
            tvFan.alpha   = sldFan.alpha
            activateBtn(btnPower, hvacPowerSel)
            activateBtn(btnAc,    hvacAcSel)
            activateBtn(btnAuto,  hvacAutoSel)
        }

        bindGroup(listOf(
            loopBtns[0] to 0, loopBtns[1] to 1, loopBtns[2] to 2, loopBtns[3] to null
        ), hvacLoopSel) { hvacLoopSel = it }
        bindGroup(listOf(
            dfBtns[0] to false, dfBtns[1] to true, dfBtns[2] to null
        ), hvacDfSel) { hvacDfSel = it }
        bindGroup(listOf(
            drBtns[0] to false, drBtns[1] to true, drBtns[2] to null
        ), hvacDrSel) { hvacDrSel = it }

        // Bascules et non groupes : ce sont les mêmes commandes que sur le Dashboard, où un
        // bouton unique s'allume quand le réglage est actif.
        btnPower.setOnClickListener { hvacPowerSel = !hvacPowerSel; majHvac() }
        btnAc.setOnClickListener    { hvacAcSel    = !hvacAcSel;    majHvac() }
        btnAuto.setOnClickListener  { hvacAutoSel  = !hvacAutoSel;  majHvac() }

        sldTemp.value = hvacTempSel.toFloat()
        tvTemp.text = getString(R.string.profile_hvac_temp_value, hvacTempSel)
        sldTemp.addOnChangeListener { _, v, _ ->
            hvacTempSel = v.toInt()
            tvTemp.text = getString(R.string.profile_hvac_temp_value, hvacTempSel)
        }
        sldFan.value = hvacFanSel.toFloat()
        tvFan.text = hvacFanSel.toString()
        sldFan.addOnChangeListener { _, v, _ ->
            hvacFanSel = v.toInt()
            tvFan.text = hvacFanSel.toString()
        }

        swHvac.isChecked = hvacEnabledSel
        swHvac.setOnCheckedChangeListener { _, checked ->
            hvacEnabledSel = checked
            majHvac()
        }
        majHvac()

        // Firmware sans clim pilotable : la section entière disparaît, comme la page Clim du
        // Dashboard. Proposer des réglages qui n'écriraient rien serait pire que rien.
        view.findViewById<View>(R.id.section_hvac)?.visibility =
            if (MG4Hardware.hasClimateControl()) View.VISIBLE else View.GONE

        // ── Sections Climat — masquées si pas de chauffage (SWI69/SWI131) ────
        val hasHeat = FirmwareInfo.hasHeatFeatures()
        val heatVis = if (hasHeat) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.section_steering_dialog)?.visibility = heatVis
        // section_seats_dialog est desormais A L'INTERIEUR de section_seats_header : masquer
        // l'entete suffit. On garde les deux pour rester robuste a un futur deplacement.
        view.findViewById<View>(R.id.section_seats_header)?.visibility    = heatVis
        view.findViewById<View>(R.id.section_seats_dialog)?.visibility    = heatVis

        // ── Section AEB (commune SWI133 + SWI68 + SWI69) ─────────────────────
        val sectionAeb = view.findViewById<View>(R.id.adas_section_aeb)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionAeb.visibility = View.VISIBLE
            val swAeb        = view.findViewById<Switch>(R.id.sw_aeb_enabled)
            val btnAebAlarmD = view.findViewById<MaterialButton>(R.id.btn_aeb_alarm_d)
            val btnAebBrakeD = view.findViewById<MaterialButton>(R.id.btn_aeb_alarm_brake_d)

            val aebSenSectionD = view.findViewById<View>(R.id.aeb_sen_section_d)
            val btnAebSenLowD  = view.findViewById<MaterialButton>(R.id.btn_aeb_sen_low_d)
            val btnAebSenStdD  = view.findViewById<MaterialButton>(R.id.btn_aeb_sen_standard_d)
            val btnAebSenHighD = view.findViewById<MaterialButton>(R.id.btn_aeb_sen_high_d)

            aebSenSectionD.visibility = View.VISIBLE
            val aebBtns = listOf(btnAebAlarmD, btnAebBrakeD, btnAebSenLowD, btnAebSenStdD, btnAebSenHighD)

            swAeb.isChecked = aebEnabledSel
            setBtnsEnabled(aebBtns, aebEnabledSel)
            swAeb.setOnCheckedChangeListener { _, checked ->
                aebEnabledSel = checked
                setBtnsEnabled(aebBtns, checked)
            }

            bindGroup(listOf(btnAebAlarmD to AebMode.ALARM, btnAebBrakeD to AebMode.ALARM_BRAKE), aebModeSel) {
                aebModeSel = it
            }
            bindGroup(listOf(
                btnAebSenLowD  to AebSensitivity.LOW,
                btnAebSenStdD  to AebSensitivity.STANDARD,
                btnAebSenHighD to AebSensitivity.HIGH
            ), aebSenSel) { aebSenSel = it }
        }

        // ── Section ESC + somnolence + sensibilité ───────────────────────────
        // Même présentation que la carte de l'écran principal, pour que l'utilisateur retrouve
        // les mêmes contrôles au même endroit logique. Masquée si le firmware ne l'expose pas.
        val sectionDmsEsc = view.findViewById<View>(R.id.safety_dms_esc_section_d)
        if (MG4Hardware.hasDrowsinessAndEsc()) {
            sectionDmsEsc.visibility = View.VISIBLE
            bindGroup(listOf(
                view.findViewById<MaterialButton>(R.id.btn_esc_on_d)  to true,
                view.findViewById<MaterialButton>(R.id.btn_esc_off_d) to false
            ), escSel) { escSel = it }
            bindGroup(listOf(
                view.findViewById<MaterialButton>(R.id.btn_dms_on_d)  to true,
                view.findViewById<MaterialButton>(R.id.btn_dms_off_d) to false
            ), dmsSel) { dmsSel = it }
            bindGroup(listOf(
                view.findViewById<MaterialButton>(R.id.btn_dms_sen_low_d)    to 1,
                view.findViewById<MaterialButton>(R.id.btn_dms_sen_medium_d) to 2,
                view.findViewById<MaterialButton>(R.id.btn_dms_sen_high_d)   to 3
            ), dmsSenSel) { dmsSenSel = it }
        }

        // ── Section ELK (tous firmwares connus) ──────────────────────────────
        val sectionElk = view.findViewById<View>(R.id.elk_section_dialog)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionElk.visibility = View.VISIBLE
            val isSWI132elk = gen == FirmwareInfo.Gen.SWI132

            val swElk          = view.findViewById<Switch>(R.id.sw_elk_enabled)
            val btnElkAlertD   = view.findViewById<MaterialButton>(R.id.btn_elk_alert_d)
            val btnElkAssistD  = view.findViewById<MaterialButton>(R.id.btn_elk_assist_d)
            val btnElkEmergD   = view.findViewById<MaterialButton>(R.id.btn_elk_emergency_d)
            val btnElkSenLowD  = view.findViewById<MaterialButton>(R.id.btn_elk_sen_low_d)
            val btnElkSenStdD  = view.findViewById<MaterialButton>(R.id.btn_elk_sen_standard_d)
            val btnElkSenHighD = view.findViewById<MaterialButton>(R.id.btn_elk_sen_high_d)

            // SWI132 : pas de mode Emergency + 2 switches supplémentaires
            if (isSWI132elk) {
                btnElkEmergD?.visibility = View.GONE
                view.findViewById<View>(R.id.elk_sound_row_d)?.visibility = View.VISIBLE
                view.findViewById<View>(R.id.elk_vibration_row_d)?.visibility = View.VISIBLE
                if (elkModeSel == ElkMode.EMERGENCY) {
                    elkModeSel = ElkMode.ALERT
                    lastActiveElkModeD = ElkMode.ALERT
                    elkEnabledSel = true
                }
            }

            val elkModeBtns = if (isSWI132elk) listOf(btnElkAlertD, btnElkAssistD)
                              else listOf(btnElkAlertD, btnElkAssistD, btnElkEmergD)
            val elkSenBtns  = listOf(btnElkSenLowD, btnElkSenStdD, btnElkSenHighD)

            val swElkSound     = view.findViewById<Switch?>(R.id.sw_elk_sound_d)
            val swElkVibration = view.findViewById<Switch?>(R.id.sw_elk_vibration_d)

            fun setElkEnabled(enabled: Boolean) {
                setBtnsEnabled(elkModeBtns + elkSenBtns, enabled)
                if (isSWI132elk) {
                    swElkSound?.isEnabled = enabled
                    swElkSound?.alpha     = if (enabled) 1f else 0.35f
                    swElkVibration?.isEnabled = enabled
                    swElkVibration?.alpha     = if (enabled) 1f else 0.35f
                }
            }

            swElk.isChecked = elkEnabledSel
            setElkEnabled(elkEnabledSel)

            if (isSWI132elk) {
                swElkSound?.isChecked     = lasAudibleWarningSel
                swElkVibration?.isChecked = lasVibrationReminderSel
                swElkSound?.setOnCheckedChangeListener { _, checked -> lasAudibleWarningSel = checked }
                swElkVibration?.setOnCheckedChangeListener { _, checked -> lasVibrationReminderSel = checked }
            }

            swElk.setOnCheckedChangeListener { _, checked ->
                elkEnabledSel = checked
                elkModeSel = if (checked) lastActiveElkModeD else ElkMode.OFF
                setElkEnabled(checked)
            }

            val initialElkMode = if (isSWI132elk && elkModeSel == ElkMode.EMERGENCY) ElkMode.ALERT
                                 else if (elkEnabledSel) elkModeSel else ElkMode.ALERT
            val elkModePairs = if (isSWI132elk)
                listOf(btnElkAlertD to ElkMode.ALERT, btnElkAssistD to ElkMode.ASSIST)
            else
                listOf(btnElkAlertD to ElkMode.ALERT, btnElkAssistD to ElkMode.ASSIST, btnElkEmergD to ElkMode.EMERGENCY)
            bindGroup(elkModePairs, initialElkMode) { mode ->
                elkModeSel = mode
                lastActiveElkModeD = mode
            }

            bindGroup(listOf(
                btnElkSenLowD  to ElkSensitivity.LOW,
                btnElkSenStdD  to ElkSensitivity.STANDARD,
                btnElkSenHighD to ElkSensitivity.HIGH
            ), elkSenSel) { elkSenSel = it }
        }

        // ── Sections ADAS ────────────────────────────────────────────────────
        val sectionSwi133 = view.findViewById<View>(R.id.adas_section_swi133)
        val sectionSwi68  = view.findViewById<View>(R.id.adas_section_swi68)
        val isSWI132Profile = gen == FirmwareInfo.Gen.SWI132

        /** Sélecteur ADAS unique : mode ACC/TJA et limiteur sont deux réglages indépendants
         *  côté voiture, l'exclusivité est imposée ici pour rester compréhensible. */
        fun applyAdasIndex(idx: Int) {
            swi132LimiterConfigured = true
            when (idx) {
                1 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = MG4Hardware.SasMode.MANUEL }
                2 -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = MG4Hardware.SasMode.INTELLIGENT }
                3 -> { swi68Mode = Swi68Mode.ACC; swi132SasMode = MG4Hardware.SasMode.OFF }
                4 -> { swi68Mode = Swi68Mode.TJA; swi132SasMode = MG4Hardware.SasMode.OFF }
                else -> { swi68Mode = Swi68Mode.OFF; swi132SasMode = MG4Hardware.SasMode.OFF }
            }
        }
        val initialAdasIdx = when {
            data.swi132SasMode == MG4Hardware.SasMode.MANUEL      -> 1
            data.swi132SasMode == MG4Hardware.SasMode.INTELLIGENT -> 2
            data.swi68AdasMode == Swi68Mode.ACC                   -> 3
            data.swi68AdasMode == Swi68Mode.TJA                   -> 4
            else                                                  -> 0
        }

        when {
            isSWI132Profile -> {
                sectionSwi133.visibility = View.VISIBLE
                sectionSwi68.visibility  = View.GONE
                view.findViewById<View>(R.id.btn_adas_auto_d)?.visibility = View.VISIBLE
                view.findViewById<Switch>(R.id.sw_overspeed_alarm).isChecked  = data.overspeedAlarm
                view.findViewById<Switch>(R.id.sw_speed_limit_tone).isChecked = data.speedLimitTone
                bindGroup(listOf(
                    view.findViewById<MaterialButton>(R.id.btn_adas_off_d)  to 0,
                    view.findViewById<MaterialButton>(R.id.btn_adas_lim_d)  to 1,
                    view.findViewById<MaterialButton>(R.id.btn_adas_auto_d) to 2,
                    view.findViewById<MaterialButton>(R.id.btn_adas_acc_d)  to 3,
                    view.findViewById<MaterialButton>(R.id.btn_adas_ica_d)  to 4
                ), initialAdasIdx) { applyAdasIndex(it) }
            }
            FirmwareInfo.isVsmBased() -> {
                sectionSwi68.visibility  = View.VISIBLE
                sectionSwi133.visibility = View.GONE
                view.findViewById<Switch>(R.id.sw_sound_warning).isChecked = data.soundWarning
                bindGroup(listOf(
                    view.findViewById<MaterialButton>(R.id.btn_adas_swi68_off_d)  to 0,
                    view.findViewById<MaterialButton>(R.id.btn_adas_swi68_lim_d)  to 1,
                    view.findViewById<MaterialButton>(R.id.btn_adas_swi68_auto_d) to 2,
                    view.findViewById<MaterialButton>(R.id.btn_adas_swi68_acc_d)  to 3,
                    view.findViewById<MaterialButton>(R.id.btn_adas_swi68_tja_d)  to 4
                ), initialAdasIdx) { applyAdasIndex(it) }
            }
            else -> {
                sectionSwi133.visibility = View.VISIBLE
                sectionSwi68.visibility  = View.GONE
                view.findViewById<Switch>(R.id.sw_overspeed_alarm).isChecked  = data.overspeedAlarm
                view.findViewById<Switch>(R.id.sw_speed_limit_tone).isChecked = data.speedLimitTone
                bindGroup(listOf(
                    view.findViewById<MaterialButton>(R.id.btn_adas_off_d)  to 0,
                    view.findViewById<MaterialButton>(R.id.btn_adas_lim_d)  to 1,
                    view.findViewById<MaterialButton>(R.id.btn_adas_auto_d) to 2,
                    view.findViewById<MaterialButton>(R.id.btn_adas_acc_d)  to 3,
                    view.findViewById<MaterialButton>(R.id.btn_adas_ica_d)  to 4
                ), adasMode) { adasMode = it }
            }
        }

        // ── Économie d'énergie + TSR (tous firmwares connus) ─────────────────
        val sectionTsr = view.findViewById<View>(R.id.section_tsr_dialog)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            btnEnergy.visibility = View.VISIBLE
            val initSnow = data.driveMode == DriveMode.SNOW
            btnEnergy.isEnabled = !initSnow
            btnEnergy.alpha = if (initSnow) 0.35f else 1f
            activateBtn(btnEnergy, energySavingSel)
            btnEnergy.setOnClickListener {
                energySavingSel = !energySavingSel
                activateBtn(btnEnergy, energySavingSel)
                setRegenEnabled(!energySavingSel && selectedDrive != DriveMode.SNOW)
            }

            sectionTsr.visibility = View.VISIBLE
            val swTsr = view.findViewById<Switch>(R.id.sw_tsr_d)
            swTsr.isChecked = tsrEnabledSel
            swTsr.setOnCheckedChangeListener { _, checked -> tsrEnabledSel = checked }
        }

        // ── [BT-PROFILES] Spinner appareil Bluetooth ─────────────────────────
        val spinnerBt = view.findViewById<Spinner>(R.id.spinner_bt_device)
        data class BtEntry(val label: String, val mac: String?)
        val noneLabel = getString(R.string.profile_bt_none)

        CoroutineScope(Dispatchers.IO).launch {
            val bonded = BluetoothProfileManager.getBondedDevices(requireContext())
            val entries = mutableListOf(BtEntry(noneLabel, null))
            entries.addAll(bonded.map { BtEntry("${it.name}  (${it.mac})", it.mac) })

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, entries.map { it.label })
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerBt.adapter = adapter

                val selIdx = entries.indexOfFirst { it.mac.equals(data.btDeviceMac, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: 0
                spinnerBt.setSelection(selIdx)

                spinnerBt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        selectedBtMac = entries.getOrNull(pos)?.mac
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) { selectedBtMac = null }
                }
                selectedBtMac = entries.getOrNull(selIdx)?.mac
            }
        }

        // ── Bandeau persistant : titre, nom, profil par défaut ───────────────
        view.findViewById<TextView>(R.id.tv_dialog_title).text =
            if (existing != null) getString(R.string.profile_edit) else getString(R.string.profile_add)

        val swDefault = view.findViewById<Switch>(R.id.sw_set_default)
        swDefault.isChecked = existing?.id == manager.getDefaultId()

        val etName = view.findViewById<EditText>(R.id.et_profile_name)
        if (existing != null) etName.setText(existing.name)

        // ── Rail de catégories ───────────────────────────────────────────────
        bindCategoryRail(view) { btn, on -> activateBtn(btn, on) }

        // ── Annuler ──────────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_dialog_cancel).setOnClickListener {
            findNavController().popBackStack()
        }

        // ── Enregistrer : ne quitte PAS si le nom est vide ───────────────────
        view.findViewById<MaterialButton>(R.id.btn_dialog_save).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = getString(R.string.profile_name_required)
                etName.requestFocus()
                return@setOnClickListener
            }

            val overspeedAlarm = view.findViewById<Switch?>(R.id.sw_overspeed_alarm)?.isChecked ?: false
            val speedLimitTone = view.findViewById<Switch?>(R.id.sw_speed_limit_tone)?.isChecked ?: false
            val soundWarning   = view.findViewById<Switch?>(R.id.sw_sound_warning)?.isChecked ?: false

            val profile = DrivingProfile(
                id             = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name           = name,
                driveMode      = selectedDrive,
                regenLevel     = selectedRegen,
                steeringHeat   = steeringOn,
                seatHeatLeft   = seatLeft,
                seatHeatRight  = seatRight,
                steeringHeatEnabled = steeringEnabled,
                seatHeatEnabled     = seatHeatEnabled,
                overspeedAlarm = overspeedAlarm,
                speedLimitTone = speedLimitTone,
                adasMode       = adasMode,
                soundWarning   = soundWarning,
                swi68AdasMode  = swi68Mode,
                swi132LimiterConfigured = swi132LimiterConfigured,
                swi132SasMode  = swi132SasMode,
                aebEnabled     = aebEnabledSel,
                aebMode        = aebModeSel,
                aebSensitivity = aebSenSel,
                escEnabled            = escSel,
                drowsinessEnabled     = dmsSel,
                drowsinessSensitivity = dmsSenSel,
                elkMode        = elkModeSel,
                elkSensitivity = elkSenSel,
                lasAudibleWarning    = lasAudibleWarningSel,
                lasVibrationReminder = lasVibrationReminderSel,
                energySaving   = energySavingSel,
                tsrEnabled     = tsrEnabledSel,
                customPower      = if (selectedDrive == DriveMode.CUSTOM) (customPowerSel ?: 1) else customPowerSel,
                customSteering   = if (selectedDrive == DriveMode.CUSTOM) (customSteerSel ?: 1) else customSteerSel,
                customPedal      = if (selectedDrive == DriveMode.CUSTOM) (customPedalSel ?: 1) else customPedalSel,
                hvacEnabled      = hvacEnabledSel,
                hvacPower        = hvacPowerSel,
                hvacAc           = hvacAcSel,
                hvacAuto         = hvacAutoSel,
                hvacTemp         = hvacTempSel,
                hvacFan          = hvacFanSel,
                hvacDefrostFront = hvacDfSel,
                hvacDefrostRear  = hvacDrSel,
                hvacLoopMode     = hvacLoopSel,
                btDeviceMac    = selectedBtMac   // [BT-PROFILES]
            )
            manager.save(profile)
            if (swDefault.isChecked) manager.setDefault(profile.id)
            // La liste se rafraîchit dans ProfileFragment.onResume
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingData = null
        pendingExisting = null
    }

    /**
     * Rail de gauche : une catégorie visible à la fois.
     *
     * Un onglet dont la page n'a plus aucune section visible sur ce firmware est masqué — mieux
     * vaut pas d'onglet qu'un onglet qui ouvre une page blanche. Appelé APRÈS les décisions de
     * visibilité, sinon le décompte serait faux.
     */
    private fun bindCategoryRail(view: View, activate: (MaterialButton, Boolean) -> Unit) {
        val tabs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_cat_drive)   to view.findViewById<ViewGroup>(R.id.page_cat_drive),
            view.findViewById<MaterialButton>(R.id.btn_cat_safety)  to view.findViewById<ViewGroup>(R.id.page_cat_safety),
            view.findViewById<MaterialButton>(R.id.btn_cat_comfort) to view.findViewById<ViewGroup>(R.id.page_cat_comfort)
        )
        val scroll = view.findViewById<ScrollView>(R.id.scroll_profile_edit)

        fun hasVisibleContent(page: ViewGroup): Boolean =
            (0 until page.childCount).any { page.getChildAt(it).visibility == View.VISIBLE }

        val usable = tabs.filter { (_, page) -> hasVisibleContent(page) }
        tabs.forEach { (btn, page) ->
            val ok = usable.any { it.second === page }
            btn.visibility = if (ok) View.VISIBLE else View.GONE
        }
        if (usable.isEmpty()) return

        fun select(target: ViewGroup) {
            tabs.forEach { (btn, page) ->
                val on = page === target
                page.visibility = if (on) View.VISIBLE else View.GONE
                activate(btn, on)
            }
            scroll?.scrollTo(0, 0)   // changer d'onglet en gardant le scroll précédent désoriente
        }
        usable.forEach { (btn, page) -> btn.setOnClickListener { select(page) } }
        select(usable.first().second)
    }
}
