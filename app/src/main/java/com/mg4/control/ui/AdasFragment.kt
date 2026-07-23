package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.MG4Hardware.AebMode
import com.mg4.hardware.MG4Hardware.Swi68Mode
import com.mg4.hardware.FirmwareInfo
import kotlinx.coroutines.*

class AdasFragment : Fragment() {

    // SWI133 views
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var btnAdasOff: Button? = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAuto: Button? = null
    private var btnAdasAcc: Button? = null
    private var btnAdasIca: Button? = null

    // SWI68/69/131/165 views — 5 boutons (index 0-4) : Off / Lim.Manuel / Lim.Auto / ACC / TJA
    private var switchSoundWarning: Switch? = null
    private var btnSwi68Off: Button? = null
    private var btnSwi68Lim: Button? = null
    private var btnSwi68Auto: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null

    // AEB views (communes SWI133 + SWI68)
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null

    private val swi133Buttons get() = listOfNotNull(
        btnAdasOff, btnAdasLimiteur, btnAdasAuto, btnAdasAcc, btnAdasIca
    )

    // index 0-4 → button (Off / Lim.Manuel / Lim.Auto / ACC / TJA)
    private val swi68Buttons: Map<Int, Button?> get() = mapOf(
        0 to btnSwi68Off,
        1 to btnSwi68Lim,
        2 to btnSwi68Auto,
        3 to btnSwi68Acc,
        4 to btnSwi68Tja
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_adas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ── Références views ────────────────────────────────────────────────
        switchOverspeed   = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone   = view.findViewById(R.id.switch_speed_tone)
        btnAdasOff        = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur   = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAuto       = view.findViewById(R.id.btn_adas_auto)
        btnAdasAcc        = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca        = view.findViewById(R.id.btn_adas_ica)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
        btnSwi68Off       = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Lim       = view.findViewById(R.id.btn_swi68_lim)
        btnSwi68Auto      = view.findViewById(R.id.btn_swi68_auto)
        btnSwi68Acc       = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja       = view.findViewById(R.id.btn_swi68_tja)
        switchAeb         = view.findViewById(R.id.switch_aeb)
        btnAebAlarm       = view.findViewById(R.id.btn_aeb_alarm)
        btnAebAlarmBrake  = view.findViewById(R.id.btn_aeb_alarm_brake)

        // ── Afficher la bonne section selon le firmware ──────────────────────
        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        // SWI133 : 5 boutons ADAS (Off/Limiteur/Auto/ACC/ICA)
        // SWI132 : 4 boutons ADAS (Off/Lim/ACC/ICA) — même section que SWI133, bouton Auto masqué
        // SWI68/SWI69/SWI131/SWI165 : 3 boutons ADAS (Off/ACC/TJA)
        view.findViewById<View>(R.id.section_swi133).visibility =
            if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.section_swi68).visibility =
            if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // Ligne du bas (AEB + alertes) — disponible si firmware connu
        view.findViewById<View>(R.id.section_bottom_row).visibility =
            if (isKnown) View.VISIBLE else View.GONE
        // Alertes : colonne droite — SWI133 et SWI132 ont 2 alertes séparées (survitesse + ton)
        //                          — SWI68/SWI69/SWI131/SWI165 ont une seule alerte sonore VSM
        view.findViewById<View>(R.id.alerts_swi133).visibility =
            if (gen == FirmwareInfo.Gen.SWI133 || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_swi68).visibility =
            if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE

        // ── Listeners SWI133 ─────────────────────────────────────────────────
        if (!isVsmBased) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
            swi133Buttons.forEachIndexed { index, btn ->
                btn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setMixedIntelligentDrive(index)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133ModeUI(index) }
                    }
                }
            }
        }

        // ── Listeners SWI132 — alertes + ADAS 5 modes ─────────────────────────
        // Off / Lim.Manuel / Lim.Auto(Intelligent) / ACC / ICA.
        // Le mode ACC/TJA (setAccTjaMode) et le limiteur de vitesse (setSasMode) sont deux
        // réglages indépendants : le sélecteur unique impose l'exclusivité.
        if (isSWI132) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
            // Le bouton Auto est disponible sur SWI132 : il sélectionne le limiteur Intelligent.
            btnAdasAuto?.visibility = View.VISIBLE
            swi133Buttons.forEachIndexed { btnIndex, btn ->
                btn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(btnIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133ModeUI(btnIndex) }
                    }
                }
            }
        }

        // ── Listeners AEB (communs SWI133 + SWI68) ──────────────────────────
        if (isKnown) {
            switchAeb?.setOnCheckedChangeListener { _, checked ->
                if (switchAeb?.isPressed == true) {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setAebEnabled(checked)
                        withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                    }
                }
            }
            btnAebAlarm?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebMode(AebMode.ALARM)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM) }
                }
            }
            btnAebAlarmBrake?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebMode(AebMode.ALARM_BRAKE)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM_BRAKE) }
                }
            }
        }

        // ── Listeners SWI68 / SWI69 / SWI131 / SWI165 — sélecteur 5 modes (index 0-4) ─
        // Off / Lim.Manuel / Lim.Auto / ACC / TJA. Mode ACC/TJA + limiteur indépendants,
        // exclusivité via le sélecteur unique (même logique que SWI132).
        if (isVsmBased && !isSWI132) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (switchSoundWarning?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSoundWarning(checked) }
            }
            swi68Buttons.forEach { (btnIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(btnIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68ModeUI(btnIndex) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) refreshState()
        }
    }

    /**
     * SWI132 : applique l'index du sélecteur ADAS (0-4) en distinguant le mode ACC/TJA
     * (setAccTjaMode) du limiteur de vitesse (setSasMode). Le sélecteur unique impose
     * l'exclusivité : choisir un mode désactive l'autre sous-système.
     *   0=Off, 1=Lim.Manuel(SAS 2), 2=Lim.Auto/Intelligent(SAS 3), 3=ACC, 4=ICA
     */
    private fun applyVsmAdasMode(index: Int) {
        when (index) {
            1 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.MANUEL);      MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            2 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.INTELLIGENT); MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            3 -> { MG4Hardware.setAccTjaMode(Swi68Mode.ACC); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            4 -> { MG4Hardware.setAccTjaMode(Swi68Mode.TJA); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            else -> { MG4Hardware.setAccTjaMode(Swi68Mode.OFF); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
        }
    }

    /** SWI132 : état lu (mode ACC/TJA + limiteur SAS) → index de bouton (0-4). */
    private fun vsmStateToIndex(accTja: Int, sas: Int): Int = when {
        sas == MG4Hardware.SasMode.MANUEL      -> 1
        sas == MG4Hardware.SasMode.INTELLIGENT -> 2
        accTja == Swi68Mode.ACC                -> 3
        accTja == Swi68Mode.TJA                -> 4
        else                                   -> 0
    }

    private fun refreshState() {
        CoroutineScope(Dispatchers.IO).launch {
            when {
                FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> refreshSwi132()
                FirmwareInfo.isVsmBased()                               -> refreshSwi68()
                else                                                    -> refreshSwi133()
            }
        }
    }

    /**
     * SWI132 : rafraîchit l'état ADAS (mode Off/Lim/ACC/ICA via CarVehicleSettingClient),
     * les alertes sonores (via binder getter TX 0x129/0x12b) et l'AEB.
     */
    private suspend fun refreshSwi132() {
        val mode      = MG4Hardware.getAccTjaMode()
        val sas       = MG4Hardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val overspeed = MG4Hardware.isOverspeedAlarmOn()
        val speedTone = MG4Hardware.isSpeedLimitToneOn()
        val aebOn     = MG4Hardware.isAebEnabled()
        val aebMode   = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            // SWI132 : mode ACC/TJA + limiteur SAS → index bouton (0-4)
            applySwi133ModeUI(vsmStateToIndex(mode, sas))
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi133() {
        val adasMode  = MG4Hardware.getMixedIntelligentDrive()
        val overspeed = MG4Hardware.isOverspeedAlarmOn()
        val speedTone = MG4Hardware.isSpeedLimitToneOn()
        val aebOn     = MG4Hardware.isAebEnabled()
        val aebMode   = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            applySwi133ModeUI(adasMode)
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68() {
        val mode    = MG4Hardware.getAccTjaMode()
        val sas     = MG4Hardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val sound   = MG4Hardware.isSoundWarningOn()
        val aebOn   = MG4Hardware.isAebEnabled()
        val aebMode = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchSoundWarning?.isChecked = sound
            applySwi68ModeUI(vsmStateToIndex(mode, sas))
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun applySwi133ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi133Buttons.forEachIndexed { i, btn ->
            btn.backgroundTintList = ColorStateList.valueOf(if (i == activeMode) accent else def)
        }
    }

    private fun applySwi68ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi68Buttons.forEach { (modeValue, btn) ->
            btn?.backgroundTintList = ColorStateList.valueOf(if (modeValue == activeMode) accent else def)
        }
    }

    private fun applyAebModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM) accent else def)
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) accent else def)
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }
}
