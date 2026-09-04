package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.AebSensitivity
import com.mg4.control.hardware.MG4Hardware.ElkMode
import com.mg4.control.hardware.MG4Hardware.ElkSensitivity
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment principal du dashboard — rail de categories a gauche, contenu defilant.
 *   Page 0 : paramètres de conduite, climat, alertes, AEB
 *   Page 1 : Assistant de sortie de voie (ELK)
 */
class DashboardFragment : Fragment() {

    // ── ViewPager ────────────────────────────────────────────────────────────
    /** Onglet courant du rail : 0=Conduite, 1=Securite, 2=Confort. Remplace pager.currentItem. */
    private var currentTab = 0

    // ── Page 0 — Drive mode ─────────────────────────────────────────────────
    private val driveModeButtons = mutableMapOf<DriveMode, Button>()

    // ── Page 0 — Régénération ───────────────────────────────────────────────
    private val regenButtons = mutableMapOf<RegenLevel, Button>()

    // ── Page 0 — ADAS SWI133 ────────────────────────────────────────────────
    private var btnAdasOff: Button?     = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAuto: Button?    = null
    private var btnAdasAcc: Button?     = null
    private var btnAdasIca: Button?     = null
    private val swi133AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnAdasOff, 1 to btnAdasLimiteur, 2 to btnAdasAuto, 3 to btnAdasAcc, 4 to btnAdasIca)

    // ── Page 0 — ADAS SWI68/69/131/165 (5 boutons index 0-4) ────────────────
    // Off / Lim.Manuel / Lim.Auto / ACC / TJA
    private var btnSwi68Off: Button? = null
    private var btnSwi68Lim: Button? = null
    private var btnSwi68Auto: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null
    private val swi68AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnSwi68Off, 1 to btnSwi68Lim, 2 to btnSwi68Auto, 3 to btnSwi68Acc, 4 to btnSwi68Tja)

    // ── Page 0 — Climat ─────────────────────────────────────────────────────
    private var switchSteering: Switch? = null
    private var seatLeftButtons: List<Button>? = null
    private var seatRightButtons: List<Button>? = null

    // ── Page 0 — Alertes ────────────────────────────────────────────────────
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var switchSoundWarning: Switch? = null
    private var alertsGroupSwi133: View? = null

    // ── Page 0 — TSR + Économie d'énergie ───────────────────────────────────
    private var switchTsr: Switch? = null
    private var btnEnergySaving: Button? = null
    private var energySavingOn = false
    /** Dernier mode de conduite connu — nécessaire pour arbitrer les exclusions SNOW / Éco énergie. */
    private var currentDriveMode: DriveMode? = null

    // ── Mode Personnalisé — puissance / direction / pédale ───────────────────
    // Une entrée par ligne : le bouton, et l'index qu'il représente (0/1/2).
    private val customPowerButtons  = mutableMapOf<Int, Button>()
    private val customSteerButtons  = mutableMapOf<Int, Button>()
    private val customPedalButtons  = mutableMapOf<Int, Button>()

    // ── AEB : page 0 pour VSM-based, page 1 (SWI133) pour les autres ───────────
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null
    // Somnolence + sensibilité + ESC (SWI133) — carte masquée ailleurs.
    private var safetyDmsEscCard: View? = null
    private var btnEscOn: Button? = null
    private var btnEscOff: Button? = null
    private var btnDmsOn: Button? = null
    private var btnDmsOff: Button? = null
    private var btnDmsSenLow: Button? = null
    private var btnDmsSenMedium: Button? = null
    private var btnDmsSenHigh: Button? = null

    private var btnAebSenLow: Button? = null
    private var btnAebSenStandard: Button? = null
    private var btnAebSenHigh: Button? = null
    private val aebSenMap: Map<Int, Button?>
        get() = mapOf(AebSensitivity.LOW to btnAebSenLow, AebSensitivity.STANDARD to btnAebSenStandard, AebSensitivity.HIGH to btnAebSenHigh)

    // ── Page 1 — ELK ────────────────────────────────────────────────────────
    private var switchElk: Switch? = null
    private var btnElkAlert: Button? = null
    private var btnElkAssist: Button? = null
    private var btnElkEmergency: Button? = null
    private var btnElkSenLow: Button? = null
    private var btnElkSenStandard: Button? = null
    private var btnElkSenHigh: Button? = null
    // SWI132 ELK extra
    private var switchElkSound: Switch? = null
    private var switchElkVibration: Switch? = null
    private val elkModeMap: Map<Int, Button?>
        get() = mapOf(ElkMode.ALERT to btnElkAlert, ElkMode.ASSIST to btnElkAssist, ElkMode.EMERGENCY to btnElkEmergency)
    private val elkSenMap: Map<Int, Button?>
        get() = mapOf(ElkSensitivity.LOW to btnElkSenLow, ElkSensitivity.STANDARD to btnElkSenStandard, ElkSensitivity.HIGH to btnElkSenHigh)

    /** True pendant les mises à jour programmatiques des Switch — bloque les listeners. */
    private var isRefreshing = false
    /** Dernier mode ELK actif connu (pour restaurer le mode lors du toggle ON). */
    private var lastActiveElkMode = ElkMode.EMERGENCY

    // ── Couleurs (lazy pour contexte disponible) ─────────────────────────────
    private val colorActive   by lazy { requireContext().getColor(R.color.dash_accent_dim) }
    private val colorInactive by lazy { requireContext().getColor(R.color.dash_btn) }
    private val colorTextActive   by lazy { requireContext().getColor(R.color.dash_accent) }
    private val colorTextInactive by lazy { requireContext().getColor(R.color.text_secondary) }
    private val colorEcoBg   by lazy { requireContext().getColor(R.color.dash_eco_dim) }
    private val colorEcoText by lazy { requireContext().getColor(R.color.dash_eco) }
    private val colorWarnBg  by lazy { requireContext().getColor(R.color.dash_warn_dim) }
    private val colorWarnText by lazy { requireContext().getColor(R.color.dash_warn) }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDashboard(view)
    }

    override fun onResume() {
        super.onResume()
        refreshDriveRegen()
        refreshClimate()
        refreshClimatePage(force = true)   // page 2 — no-op si elle n'a pas été créée (A9)
        if (currentTab == TAB_COMFORT) startClimatePolling()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()  // ELK utilise sVsm sur ces firmwares
            }
        }
        refreshElk()  // SWI133 — sVsm133 indépendant de Katman4
        MG4Hardware.whenKatman4Ready { if (isAdded) refreshSafetyDmsEsc() }
    }

    override fun onPause() {
        super.onPause()
        stopClimatePolling()   // pas de sondage binder quand l'écran n'est plus visible
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Rail de categories + binding des trois sections
    // ═════════════════════════════════════════════════════════════════════════

    private companion object {
        const val TAB_DRIVE = 0
        const val TAB_SAFETY = 1
        const val TAB_COMFORT = 2
    }

    /**
     * Le ViewPager2 a ete remplace par un rail : les trois anciennes pages vivent desormais
     * dans le MEME arbre de vues, on peut donc toutes les lier d'un coup sur la racine.
     * C'est sûr parce que leurs ids ne se recouvrent pas (verifie a la refonte).
     *
     * Consequence a ne pas perdre de vue : les visibilites conditionnelles au firmware sont
     * appliquees par bindMainPage/bindElkPage comme avant — le rail passe APRES, pour que sa
     * garde d'onglet vide voie l'etat final.
     */
    private fun setupDashboard(root: View) {
        bindMainPage(root)
        bindElkPage(root)

        // La clim n'est liee QUE si le firmware l'expose : avant, la page n'etait meme pas
        // creee par l'adapter. Ici elle existe toujours dans l'arbre, il faut donc la masquer.
        val hasClim = MG4Hardware.hasClimateControl()
        root.findViewById<View>(R.id.climate_page_section)?.visibility =
            if (hasClim) View.VISIBLE else View.GONE
        if (hasClim) bindClimatePage(root)

        bindCategoryRail(root)
    }

    /**
     * Rail de gauche — meme motif que les autres ecrans refondus. Un onglet dont la page n'a
     * plus aucune section visible sur ce firmware est masque.
     */
    private fun bindCategoryRail(root: View) {
        val tabs = listOf(
            root.findViewById<MaterialButton>(R.id.btn_dash_cat_drive)   to root.findViewById<ViewGroup>(R.id.page_dash_drive),
            root.findViewById<MaterialButton>(R.id.btn_dash_cat_safety)  to root.findViewById<ViewGroup>(R.id.page_dash_safety),
            root.findViewById<MaterialButton>(R.id.btn_dash_cat_comfort) to root.findViewById<ViewGroup>(R.id.page_dash_comfort)
        )
        val scroll   = root.findViewById<ScrollView>(R.id.scroll_dashboard)
        val dimColor = requireContext().getColor(R.color.dash_accent_dim)
        val accent   = requireContext().getColor(R.color.dash_accent)
        val offBg    = requireContext().getColor(R.color.dash_btn)
        val border   = requireContext().getColor(R.color.dash_border)
        val textOff  = requireContext().getColor(R.color.text_secondary)

        fun hasVisibleContent(page: ViewGroup): Boolean =
            (0 until page.childCount).any { page.getChildAt(it).visibility == View.VISIBLE }

        val usable = tabs.filterIndexed { _, (_, page) -> hasVisibleContent(page) }
        tabs.forEach { (btn, page) ->
            btn.visibility = if (usable.any { it.second === page }) View.VISIBLE else View.GONE
        }
        if (usable.isEmpty()) return

        fun select(index: Int) {
            currentTab = index
            tabs.forEachIndexed { i, (btn, page) ->
                val on = i == index
                page.visibility = if (on) View.VISIBLE else View.GONE
                btn.backgroundTintList = ColorStateList.valueOf(if (on) dimColor else offBg)
                btn.setTextColor(if (on) accent else textOff)
                btn.strokeColor = ColorStateList.valueOf(if (on) accent else border)
            }
            scroll?.scrollTo(0, 0)
            // Rafraichissements qui etaient declenches par onPageSelected du pager.
            if (index == TAB_SAFETY) refreshElk()
            if (index == TAB_COMFORT && MG4Hardware.hasClimateControl()) {
                refreshClimatePage(force = true)
                startClimatePolling()
            } else {
                stopClimatePolling()
            }
        }

        tabs.forEachIndexed { i, (btn, _) -> btn.setOnClickListener { select(i) } }
        select(tabs.indexOfFirst { it.second === usable.first().second })
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 0 — Binding, visibility, listeners  (code existant)
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindMainPage(view: View) {
        bindMainViews(view)
        applyFirmwareVisibility(view)
        setupMainListeners()
        // Refresh immédiat (la page vient d'être créée)
        refreshDriveRegen()
        refreshClimate()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()
            }
        }
    }

    private fun bindMainViews(view: View) {
        // Drive
        driveModeButtons[DriveMode.ECO]    = view.findViewById(R.id.btn_eco)
        driveModeButtons[DriveMode.NORMAL] = view.findViewById(R.id.btn_normal)
        driveModeButtons[DriveMode.SPORT]  = view.findViewById(R.id.btn_sport)
        driveModeButtons[DriveMode.SNOW]   = view.findViewById(R.id.btn_snow)
        driveModeButtons[DriveMode.CUSTOM] = view.findViewById(R.id.btn_custom)

        // Regen
        regenButtons[RegenLevel.OFF]       = view.findViewById(R.id.btn_regen_off)
        regenButtons[RegenLevel.LOW]       = view.findViewById(R.id.btn_regen_low)
        regenButtons[RegenLevel.MEDIUM]    = view.findViewById(R.id.btn_regen_medium)
        regenButtons[RegenLevel.HIGH]      = view.findViewById(R.id.btn_regen_high)
        regenButtons[RegenLevel.ADAPTIVE]  = view.findViewById(R.id.btn_regen_adaptive)
        regenButtons[RegenLevel.ONE_PEDAL] = view.findViewById(R.id.btn_regen_one_pedal)

        // ADAS SWI133
        btnAdasOff      = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAuto     = view.findViewById(R.id.btn_adas_auto)
        btnAdasAcc      = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca      = view.findViewById(R.id.btn_adas_ica)

        // ADAS SWI68
        btnSwi68Off  = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Lim  = view.findViewById(R.id.btn_swi68_lim)
        btnSwi68Auto = view.findViewById(R.id.btn_swi68_auto)
        btnSwi68Acc  = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja  = view.findViewById(R.id.btn_swi68_tja)

        // Climat
        switchSteering   = view.findViewById(R.id.switch_steering_heat)
        seatLeftButtons  = listOf(
            R.id.btn_seat_left_0, R.id.btn_seat_left_1,
            R.id.btn_seat_left_2, R.id.btn_seat_left_3
        ).map { view.findViewById(it) }
        seatRightButtons = listOf(
            R.id.btn_seat_right_0, R.id.btn_seat_right_1,
            R.id.btn_seat_right_2, R.id.btn_seat_right_3
        ).map { view.findViewById(it) }

        // Alertes
        switchOverspeed    = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone    = view.findViewById(R.id.switch_speed_tone)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
        alertsGroupSwi133  = view.findViewById(R.id.alerts_group_swi133)

        // Mode Personnalisé — trois lignes de trois boutons
        customPowerButtons[0] = view.findViewById(R.id.btn_cd_power_eco)
        customPowerButtons[1] = view.findViewById(R.id.btn_cd_power_normal)
        customPowerButtons[2] = view.findViewById(R.id.btn_cd_power_sport)
        customSteerButtons[0] = view.findViewById(R.id.btn_cd_steer_comfort)
        customSteerButtons[1] = view.findViewById(R.id.btn_cd_steer_normal)
        customSteerButtons[2] = view.findViewById(R.id.btn_cd_steer_sport)
        customPedalButtons[0] = view.findViewById(R.id.btn_cd_pedal_comfort)
        customPedalButtons[1] = view.findViewById(R.id.btn_cd_pedal_normal)
        customPedalButtons[2] = view.findViewById(R.id.btn_cd_pedal_sport)

        // TSR + Économie d'énergie
        switchTsr       = view.findViewById(R.id.switch_tsr)
        btnEnergySaving = view.findViewById(R.id.btn_energy_saving)

        // AEB déplacé sur page 1 pour tous les firmwares — pas de binding ici
    }

    private fun applyFirmwareVisibility(view: View) {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

        // SWI132 utilise les 4 boutons Off/Lim/ACC/ICA (même groupe que SWI133), pas Off/ACC/TJA
        view.findViewById<View>(R.id.adas_group_swi133).visibility   = if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.adas_group_swi68).visibility    = if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // SWI132 utilise deux alertes séparées (survitesse + ton) comme SWI133, pas soundWarning
        view.findViewById<View>(R.id.alerts_group_swi133).visibility = if (!isVsmBased || isSWI132) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi68).visibility  = if (isVsmBased && !isSWI132) View.VISIBLE else View.GONE
        // AEB déplacé sur page 1 pour tous les firmwares
        view.findViewById<View>(R.id.aeb_group).visibility           = View.GONE
        view.findViewById<View>(R.id.climate_card).visibility        = if (hasClimate) View.VISIBLE else View.GONE
        // TSR + Économie d'énergie — tous firmwares connus
        view.findViewById<View>(R.id.section_tsr).visibility    = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.btn_energy_saving).visibility = if (isKnown) View.VISIBLE else View.GONE
    }

    private fun setupMainListeners() {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

        // Drive mode
        driveModeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                applyDriveModeUI(mode)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setDriveMode(mode) }
            }
        }

        // Mode Personnalisé — chaque ligne écrit son réglage et rien d'autre.
        listOf(
            Triple(customPowerButtons, MG4Hardware::setCustomPower,    "puissance"),
            Triple(customSteerButtons, MG4Hardware::setCustomSteering, "direction"),
            Triple(customPedalButtons, MG4Hardware::setCustomPedal,    "pédale")
        ).forEach { (boutons, ecrire, nom) ->
            boutons.forEach { (index, btn) ->
                btn.setOnClickListener {
                    highlightCustomRow(boutons, index)
                    CoroutineScope(Dispatchers.IO).launch {
                        AppLogger.i("MG4_DASH", "mode personnalisé — $nom = $index → ${ecrire(index)}")
                    }
                }
            }
        }

        // Regen
        regenButtons.forEach { (level, btn) ->
            btn.setOnClickListener {
                applyRegenUI(level)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setRegenLevel(level) }
            }
        }

        // ADAS
        // SWI133/UNKNOWN : setMixedIntelligentDrive (VPM) — indices 0/1/3/4 → Off/Lim/ACC/ICA
        // SWI132          : setAccTjaMode (VSM) — valeurs 0x4/0x3/0x1/0x2 → Off/Lim/ACC/ICA
        // SWI68/SWI69/SWI131/SWI165 : setAccTjaMode (VSM) — valeurs 0x4/0x1/0x2 → Off/ACC/TJA
        if (!isVsmBased || isSWI132) {
            swi133AdasMap.forEach { (modeIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (isSWI132) {
                            // SWI132 : mode ACC/TJA (setAccTjaMode) et limiteur de vitesse (setSasMode)
                            // sont deux réglages indépendants ; le sélecteur unique impose l'exclusivité.
                            applyVsmAdasMode(modeIndex)
                        } else {
                            MG4Hardware.setMixedIntelligentDrive(modeIndex)
                        }
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133AdasUI(modeIndex) }
                    }
                }
            }
        } else {
            // SWI68/SWI69/SWI131/SWI165 : sélecteur 5 modes (index 0-4), même logique que SWI132
            // (mode ACC/TJA + limiteur de vitesse indépendants, exclusivité via le sélecteur unique).
            swi68AdasMap.forEach { (modeIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        applyVsmAdasMode(modeIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68AdasUI(modeIndex) }
                    }
                }
            }
        }

        // Climat
        if (hasClimate) {
            switchSteering?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSteeringHeat(checked) }
            }
            seatLeftButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatLeft(level) }
            } }
            seatRightButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatRight(level) }
            } }
        }

        // Alertes SWI133 + SWI132 (deux toggles indépendants : survitesse + ton limite)
        if (!isVsmBased || isSWI132) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
        }

        // Alerte sonore SWI68/SWI69/SWI131/SWI165 (un seul toggle)
        if (isVsmBased && !isSWI132) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    MG4Hardware.whenKatman4Ready { MG4Hardware.setSoundWarning(checked) }
            }
        }

        // TSR — tous firmwares connus
        if (isKnown) {
            switchTsr?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing) {
                    val gen = FirmwareInfo.getGeneration()
                    val hasTwoAlerts = gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.SWI132
                    // Mise à jour UI immédiate — pas besoin d'attendre le hardware :
                    // TSR OFF → alertes forcées à OFF et section grisée (non modifiable)
                    // TSR ON  → section ré-activée ; les valeurs réelles sont lues après le SET
                    if (hasTwoAlerts) {
                        if (!checked) {
                            isRefreshing = true
                            switchOverspeed?.isChecked = false
                            switchSpeedTone?.isChecked = false
                            isRefreshing = false
                        }
                        setAlertsSwi133Enabled(checked)
                    }
                    MG4Hardware.whenKatman4Ready {
                        CoroutineScope(Dispatchers.IO).launch {
                            MG4Hardware.setTsrMode(checked)
                            if (checked && hasTwoAlerts) {
                                when (gen) {
                                    FirmwareInfo.Gen.SWI133 -> {
                                        // Le firmware SWI133 remet overspeed/speedTone à ON dès que
                                        // le TSR est activé. setTsrMode() les restaure ensuite via VPM,
                                        // mais les écritures VPM ont une latence de propagation pouvant
                                        // dépasser 500ms : lire le hardware ici renverrait encore ON.
                                        // → On utilise directement les valeurs sauvegardées en prefs,
                                        //   qui sont exactement ce que setTsrMode() vient de restaurer.
                                        val (overspeed, speedTone) = MG4Hardware.savedTsrAlerts()
                                        withContext(Dispatchers.Main) {
                                            if (!isAdded) return@withContext
                                            isRefreshing = true
                                            switchOverspeed?.isChecked = overspeed
                                            switchSpeedTone?.isChecked = speedTone
                                            isRefreshing = false
                                        }
                                    }
                                    FirmwareInfo.Gen.SWI132 -> {
                                        // SWI132 : activer le TSR réinitialise overspeed/speedTone à ON
                                        // dans la voiture. Forcer les toggles à ON dans l'UI directement.
                                        withContext(Dispatchers.Main) {
                                            if (!isAdded) return@withContext
                                            isRefreshing = true
                                            switchOverspeed?.isChecked = true
                                            switchSpeedTone?.isChecked = true
                                            isRefreshing = false
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }

        // Économie d'énergie — tous firmwares connus
        if (isKnown) {
            btnEnergySaving?.setOnClickListener {
                energySavingOn = !energySavingOn
                applyEnergySavingUI(energySavingOn)
                MG4Hardware.whenKatman4Ready {
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setEnergySavingMode(energySavingOn) }
                }
            }
        }

        // AEB : listeners sur page 1 via setupAebPage2Listeners() — rien ici
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 1 — ELK : Binding + Listeners
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindElkPage(view: View) {
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        // ELK — switch principal : IDs différents selon le firmware
        if (isSWI132elk) {
            // SWI132 : layout multi-lignes dans elk_activation_swi132
            view.findViewById<View>(R.id.elk_activation_simple).visibility = View.GONE
            view.findViewById<View>(R.id.elk_activation_swi132).visibility = View.VISIBLE
            switchElk         = view.findViewById(R.id.switch_elk_s132)
            switchElkSound    = view.findViewById(R.id.switch_elk_sound)
            switchElkVibration= view.findViewById(R.id.switch_elk_vibration)
            // SWI132 : pas de mode Emergency — masqué
            btnElkEmergency   = view.findViewById(R.id.btn_elk_emergency)
            btnElkEmergency?.visibility = View.GONE
            // Défaut SWI132 : Alerte (mode 2), pas Emergency
            lastActiveElkMode = ElkMode.ALERT
        } else {
            switchElk         = view.findViewById(R.id.switch_elk)
            btnElkEmergency   = view.findViewById(R.id.btn_elk_emergency)
        }
        btnElkAlert       = view.findViewById(R.id.btn_elk_alert)
        btnElkAssist      = view.findViewById(R.id.btn_elk_assist)
        btnElkSenLow      = view.findViewById(R.id.btn_elk_sen_low)
        btnElkSenStandard = view.findViewById(R.id.btn_elk_sen_standard)
        btnElkSenHigh     = view.findViewById(R.id.btn_elk_sen_high)

        // AEB — page 1 pour tous les firmwares connus
        val aebCard = view.findViewById<View>(R.id.aeb_card_page2)
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) {
            aebCard.visibility    = View.VISIBLE
            switchAeb             = view.findViewById(R.id.switch_aeb_p2)
            btnAebAlarm           = view.findViewById(R.id.btn_aeb_alarm_p2)
            btnAebAlarmBrake      = view.findViewById(R.id.btn_aeb_alarm_brake_p2)
            btnAebSenLow          = view.findViewById(R.id.btn_aeb_sen_low)
            btnAebSenStandard     = view.findViewById(R.id.btn_aeb_sen_standard)
            btnAebSenHigh         = view.findViewById(R.id.btn_aeb_sen_high)
            setupAebPage2Listeners()
            MG4Hardware.whenKatman4Ready { if (isAdded) refreshAebPage2() }
        }

        // Somnolence / sensibilité / ESC — la carte reste masquée si le firmware n'expose pas
        // ces propriétés, plutôt que d'afficher des boutons qui échoueraient en silence.
        if (MG4Hardware.hasDrowsinessAndEsc()) {
            safetyDmsEscCard = view.findViewById(R.id.safety_dms_esc_card)
            btnEscOn         = view.findViewById(R.id.btn_esc_on)
            btnEscOff        = view.findViewById(R.id.btn_esc_off)
            btnDmsOn         = view.findViewById(R.id.btn_dms_on)
            btnDmsOff        = view.findViewById(R.id.btn_dms_off)
            btnDmsSenLow     = view.findViewById(R.id.btn_dms_sen_low)
            btnDmsSenMedium  = view.findViewById(R.id.btn_dms_sen_medium)
            btnDmsSenHigh    = view.findViewById(R.id.btn_dms_sen_high)
            safetyDmsEscCard?.visibility = View.VISIBLE
            setupSafetyDmsEscListeners()
            // sVpm est lié de façon ASYNCHRONE : rafraîchir tout de suite lisait -1 partout et
            // n'allumait donc aucun bouton à l'ouverture de l'écran.
            MG4Hardware.whenKatman4Ready { if (isAdded) refreshSafetyDmsEsc() }
        }

        setupElkListeners()
        refreshElk()
    }

    private fun setupElkListeners() {
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        // Toggle ON/OFF
        switchElk?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                val mode = if (checked) lastActiveElkMode else ElkMode.OFF
                // SWI132 : grise les 2 switches supplémentaires si désactivé
                if (isSWI132elk) applyElkSoundVibEnabled(checked)
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkMode(mode)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            applyElkModeUI(mode)
                            applyElkButtonsEnabled(checked)
                        }
                    }
                }
            }
        }

        // SWI132 — Alerte sonore
        if (isSWI132elk) {
            switchElkSound?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setLasWarningSound(checked) }
            }
            switchElkVibration?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setLasWarningVibration(checked) }
            }
        }

        // Mode buttons
        elkModeMap.forEach { (mode, btn) ->
            btn?.setOnClickListener {
                lastActiveElkMode = mode
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkMode(mode)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkModeUI(mode) }
                }
            }
        }

        // Sensitivity buttons
        elkSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkSensitivityUI(level) }
                }
            }
        }
    }

    private fun setupAebPage2Listeners() {
        // Toggle ON/OFF
        switchAeb?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebEnabled(checked)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                }
            }
        }
        // Mode
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
        // Sensibilité
        aebSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebSensitivityUI(level) }
                }
            }
        }
    }

    private fun refreshAebPage2() {
        if (switchAeb == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val aebOn  = MG4Hardware.isAebEnabled()
            val aebMode = MG4Hardware.getAebMode()
            val aebSen  = MG4Hardware.getAebSensitivity()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                isRefreshing = true
                switchAeb?.isChecked = aebOn
                isRefreshing = false
                applyAebModeButtonsEnabled(aebOn)
                if (aebMode > 0) applyAebModeUI(aebMode)
                if (aebSen > 0) applyAebSensitivityUI(aebSen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * SWI132 : applique l'index du sélecteur ADAS en distinguant le mode ACC/TJA (setAccTjaMode)
     * du limiteur de vitesse (setSasMode), deux réglages indépendants sur la voiture. Le sélecteur
     * unique impose l'exclusivité : choisir un mode désactive l'autre sous-système.
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

    /**
     * SWI132 : convertit l'état lu (mode ACC/TJA + limiteur SAS) en index de bouton (0-4).
     * Manuel→1 (Lim.Manuel), Intelligent→2 (Lim.Auto), ACC→3, TJA→4, sinon Off→0.
     */
    private fun vsmStateToIndex(accTja: Int, sas: Int): Int = when {
        sas == MG4Hardware.SasMode.MANUEL      -> 1
        sas == MG4Hardware.SasMode.INTELLIGENT -> 2
        accTja == Swi68Mode.ACC                -> 3
        accTja == Swi68Mode.TJA                -> 4
        else                                   -> 0
    }

    /**
     * SWI133 / SWI132 : active ou grise la section des 2 alertes sonores.
     * Quand le TSR (RECO. PANNEAUX) est OFF, les alertes sont désactivées et non modifiables.
     * L'alpha est appliqué sur le conteneur entier (labels + switches) pour un rendu cohérent.
     */
    private fun setAlertsSwi133Enabled(enabled: Boolean) {
        alertsGroupSwi133?.alpha    = if (enabled) 1f else 0.4f
        switchOverspeed?.isEnabled  = enabled
        switchSpeedTone?.isEnabled  = enabled
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Refresh depuis le hardware
    // ═════════════════════════════════════════════════════════════════════════

    private fun refreshDriveRegen() {
        if (driveModeButtons.isEmpty()) return  // page pas encore créée
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = MG4Hardware.getDriveMode()
            val regen = MG4Hardware.getRegenLevel()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                mode?.let  { applyDriveModeUI(it) }
                regen?.let { applyRegenUI(it) }
                if (mode == null && regen == null)
                    view?.postDelayed({ if (isAdded) refreshDriveRegen() }, 3_000)
            }
        }
    }

    private fun refreshClimate() {
        if (!FirmwareInfo.hasHeatFeatures() || switchSteering == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val steeringOn = MG4Hardware.isSteeringHeatOn()
            val leftLevel  = MG4Hardware.getSeatHeatLeft()
            val rightLevel = MG4Hardware.getSeatHeatRight()
            val ready = MG4Hardware.getIntPropertyHvac(MG4Hardware.PROP_SEAT_HEAT_L, 0x75) >= 0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ready) {
                    isRefreshing = true
                    switchSteering?.isChecked = steeringOn
                    isRefreshing = false
                    seatLeftButtons?.let  { applySeatUI(it, leftLevel) }
                    seatRightButtons?.let { applySeatUI(it, rightLevel) }
                } else {
                    view?.postDelayed({ if (isAdded) refreshClimate() }, 3_000)
                }
            }
        }
    }

    private fun refreshAdas() {
        // Vérifie que les boutons ADAS de page 0 sont créés (AEB est sur page 1)
        // SWI132 utilise les boutons swi133 (Off/Lim/ACC/ICA), pas les boutons swi68
        val isSWI132forGuard = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        if (!FirmwareInfo.isVsmBased() || isSWI132forGuard) { if (btnAdasOff == null) return }
        else { if (btnSwi68Off == null) return }
        CoroutineScope(Dispatchers.IO).launch {
            when {
                FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> refreshSwi132Adas()
                FirmwareInfo.isVsmBased()                               -> refreshSwi68Adas()
                else                                                    -> refreshSwi133Adas()
            }
        }
    }

    private suspend fun refreshSwi133Adas() {
        val adasMode     = MG4Hardware.getMixedIntelligentDrive()
        val overspeed    = MG4Hardware.isOverspeedAlarmOn()
        val speedTone    = MG4Hardware.isSpeedLimitToneOn()
        val aebOn        = MG4Hardware.isAebEnabled()
        val aebMode      = MG4Hardware.getAebMode()
        val tsrOn        = MG4Hardware.isTsrOn()
        val energySaving = MG4Hardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchOverspeed?.isChecked    = overspeed
            switchSpeedTone?.isChecked    = speedTone
            switchAeb?.isChecked          = aebOn
            switchTsr?.isChecked          = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            setAlertsSwi133Enabled(tsrOn)   // grise les alertes si TSR est OFF
            applySwi133AdasUI(adasMode)
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    /**
     * SWI132 : mode ACC/TJA (CarVehicleSettingClient) + alertes binder direct
     * (overspeed TX 0x129, speedTone TX 0x12b) + TSR binder direct + AEB + économie.
     */
    private suspend fun refreshSwi132Adas() {
        val mode         = MG4Hardware.getAccTjaMode()
        val sas          = MG4Hardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val overspeed    = MG4Hardware.isOverspeedAlarmOn()
        val speedTone    = MG4Hardware.isSpeedLimitToneOn()
        val aebOn        = MG4Hardware.isAebEnabled()
        val aebMode      = MG4Hardware.getAebMode()
        val tsrOn        = MG4Hardware.isTsrOn()
        val energySaving = MG4Hardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            switchAeb?.isChecked       = aebOn
            switchTsr?.isChecked       = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            setAlertsSwi133Enabled(tsrOn)   // grise les alertes si TSR est OFF
            applySwi133AdasUI(vsmStateToIndex(mode, sas))  // SWI132 : Off/Limiteur/ACC/ICA
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68Adas() {
        val mode         = MG4Hardware.getAccTjaMode()
        val sas          = MG4Hardware.getSpeedLimiterMode()   // limiteur : 0=Off, 2=Manuel, 3=Intelligent
        val sound        = MG4Hardware.isSoundWarningOn()
        val aebOn        = MG4Hardware.isAebEnabled()
        val aebMode      = MG4Hardware.getAebMode()
        val tsrOn        = MG4Hardware.isTsrOn()
        val energySaving = MG4Hardware.isEnergySavingOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchSoundWarning?.isChecked = sound
            switchAeb?.isChecked          = aebOn
            switchTsr?.isChecked          = tsrOn
            applyEnergySavingUI(energySaving)
            isRefreshing = false
            applySwi68AdasUI(vsmStateToIndex(mode, sas))
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun refreshElk() {
        if (switchElk == null) return  // page pas encore créée
        val isSWI132elk = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = MG4Hardware.getElkMode()
            val sen   = MG4Hardware.getElkSensitivity()
            val sound = if (isSWI132elk) MG4Hardware.getLasWarningSound() else -1
            val vibr  = if (isSWI132elk) MG4Hardware.getLasWarningVibration() else -1
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val enabled = mode > 0 && mode != ElkMode.OFF
                if (enabled) lastActiveElkMode = mode
                isRefreshing = true
                switchElk?.isChecked = enabled
                if (isSWI132elk) {
                    if (sound >= 0) switchElkSound?.isChecked = sound == 1
                    if (vibr  >= 0) switchElkVibration?.isChecked = vibr == 1
                }
                isRefreshing = false
                applyElkModeUI(mode)
                applyElkButtonsEnabled(enabled)
                applyElkSoundVibEnabled(enabled)
                if (sen > 0) applyElkSensitivityUI(sen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 0
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyEnergySavingUI(active: Boolean) {
        energySavingOn = active
        btnEnergySaving?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
        btnEnergySaving?.setTextColor(if (active) colorTextActive else colorTextInactive)
        // Regen : indisponible si Éco actif OU si SNOW sélectionné
        setRegenEnabled(!active && currentDriveMode != DriveMode.SNOW)
    }

    /** Surligne l'option retenue d'une ligne du mode Personnalisé. */
    private fun highlightCustomRow(boutons: Map<Int, Button>, actif: Int?) {
        boutons.forEach { (index, btn) ->
            val on = index == actif
            btn.backgroundTintList = ColorStateList.valueOf(if (on) colorActive else colorInactive)
            btn.setTextColor(if (on) colorTextActive else colorTextInactive)
        }
    }

    /**
     * Révèle la carte du mode Personnalisé et y reporte l'état du véhicule.
     *
     * Deux niveaux de masquage, et ils ne disent pas la même chose :
     *  • la CARTE n'apparaît que si la voiture est réellement en mode Personnalisé — ces trois
     *    réglages n'ont aucun effet ailleurs ;
     *  • une LIGNE disparaît si le véhicule ne rend pas son état. Les six firmwares exposent la
     *    commande, mais rien ne garantit que la finition porte l'équipement — une direction à
     *    assistance variable, par exemple. Un bouton qui n'écrirait nulle part vaut moins que
     *    pas de bouton du tout.
     */
    private fun refreshCustomDrive(mode: DriveMode?) {
        val vue = view ?: return
        val carte = vue.findViewById<View>(R.id.section_custom_drive) ?: return
        if (mode != DriveMode.CUSTOM) {
            carte.visibility = View.GONE
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val puissance = MG4Hardware.getCustomPower()
            val direction = MG4Hardware.getCustomSteering()
            val pedale    = MG4Hardware.getCustomPedal()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // L'état a pu changer pendant la lecture (l'utilisateur quitte le mode) : on
                // revérifie avant d'afficher, sinon la carte réapparaîtrait toute seule.
                if (currentDriveMode != DriveMode.CUSTOM) { carte.visibility = View.GONE; return@withContext }
                val lignes = listOf(
                    Triple(customPowerButtons, puissance, R.id.row_cd_power),
                    Triple(customSteerButtons, direction, R.id.row_cd_steer),
                    Triple(customPedalButtons, pedale,    R.id.row_cd_pedal)
                )
                lignes.forEach { (boutons, valeur, rowId) ->
                    vue.findViewById<View>(rowId)?.visibility =
                        if (valeur == null) View.GONE else View.VISIBLE
                    highlightCustomRow(boutons, valeur)
                }
                // Aucune ligne lisible : la carte n'aurait qu'un titre à montrer.
                val lisible = lignes.any { it.second != null }
                carte.visibility = if (lisible) View.VISIBLE else View.GONE
                // Trois lectures nulles d'un coup, c'est plus vraisemblablement une couche
                // véhicule pas encore prête qu'une voiture dépourvue des trois équipements.
                // Même repli que refreshDriveRegen, qui réessaie pour la même raison.
                if (!lisible) view?.postDelayed({
                    if (isAdded && currentDriveMode == DriveMode.CUSTOM) refreshCustomDrive(currentDriveMode)
                }, 3_000)
            }
        }
    }

    private fun applyDriveModeUI(mode: DriveMode) {
        currentDriveMode = mode
        refreshCustomDrive(mode)
        driveModeButtons.forEach { (m, btn) ->
            val (bg, text) = when {
                m != mode            -> colorInactive to colorTextInactive
                m == DriveMode.ECO   -> colorEcoBg   to colorEcoText
                m == DriveMode.SPORT -> colorWarnBg  to colorWarnText
                else                 -> colorActive   to colorTextActive
            }
            btn.backgroundTintList = ColorStateList.valueOf(bg)
            btn.setTextColor(text)
        }
        // Regen : indisponible si SNOW OU si Éco énergie actif
        setRegenEnabled(mode != DriveMode.SNOW && !energySavingOn)
        // Bouton Éco énergie : indisponible en mode SNOW (modes exclusifs)
        val isSnow = mode == DriveMode.SNOW
        btnEnergySaving?.isEnabled = !isSnow
        btnEnergySaving?.alpha = if (isSnow) 0.35f else 1f
    }

    private fun applyRegenUI(level: RegenLevel) {
        regenButtons.forEach { (l, btn) ->
            val active = l == level
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun setRegenEnabled(enabled: Boolean) {
        val isSnow = currentDriveMode == DriveMode.SNOW
        regenButtons.forEach { (level, btn) ->
            // ONE_PEDAL reste accessible même quand Éco énergie est actif,
            // sauf en mode SNOW où tous les niveaux de regen sont indisponibles.
            val btnEnabled = enabled || (level == RegenLevel.ONE_PEDAL && !isSnow)
            btn.isEnabled = btnEnabled
            btn.alpha = if (btnEnabled) 1f else 0.35f
        }
    }

    private fun applySwi133AdasUI(activeMode: Int) {
        swi133AdasMap.forEach { (modeIndex, btn) ->
            val active = modeIndex == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applySwi68AdasUI(activeMode: Int) {
        swi68AdasMap.forEach { (modeValue, btn) ->
            val active = modeValue == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun setupSeatButtons(buttons: List<Button>, onLevel: (Int) -> Unit) {
        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                applySeatUI(buttons, index)
                onLevel(index)
            }
        }
    }

    private fun applyAebModeUI(activeMode: Int) {
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM)       colorActive else colorInactive)
        btnAebAlarm?.setTextColor(                                    if (activeMode == AebMode.ALARM)       colorTextActive else colorTextInactive)
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) colorActive else colorInactive)
        btnAebAlarmBrake?.setTextColor(                               if (activeMode == AebMode.ALARM_BRAKE) colorTextActive else colorTextInactive)
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Somnolence (DMS) + sensibilité + ESC — SWI133
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Boutons ON/OFF distincts plutôt qu'un interrupteur : sur une bascule matérielle, un
     * Switch affiche un état qu'on n'a pas toujours pu relire, et l'utilisateur croit avoir
     * agi alors que rien n'a bougé. Deux boutons expriment une INTENTION, et c'est la
     * relecture qui décide lequel s'allume.
     */
    private fun setupSafetyDmsEscListeners() {
        val sens = listOfNotNull(btnDmsSenLow, btnDmsSenMedium, btnDmsSenHigh)
        btnEscOn?.setOnClickListener {
            applyPairUI(btnEscOn, btnEscOff, true);  writeSafety { MG4Hardware.setEsc(true) }
        }
        btnEscOff?.setOnClickListener {
            applyPairUI(btnEscOn, btnEscOff, false); writeSafety { MG4Hardware.setEsc(false) }
        }
        btnDmsOn?.setOnClickListener {
            applyPairUI(btnDmsOn, btnDmsOff, true);  writeSafety { MG4Hardware.setDrowsiness(true) }
        }
        btnDmsOff?.setOnClickListener {
            applyPairUI(btnDmsOn, btnDmsOff, false); writeSafety { MG4Hardware.setDrowsiness(false) }
        }
        listOf(
            btnDmsSenLow    to MG4Hardware.DrowsinessSensitivity.LOW,
            btnDmsSenMedium to MG4Hardware.DrowsinessSensitivity.MEDIUM,
            btnDmsSenHigh   to MG4Hardware.DrowsinessSensitivity.HIGH
        ).forEach { (btn, level) ->
            btn?.setOnClickListener {
                applySeatUI(sens, level - 1)
                writeSafety { MG4Hardware.setDrowsinessSensitivity(level) }
            }
        }
    }

    /**
     * Écrit hors du thread UI puis RELIT pour rafraîchir l'affichage.
     *
     * On ne présume jamais du résultat : l'écriture peut être refusée par le verrou de vitesse,
     * et l'ESC est une bascule dont l'effet doit être constaté. Afficher l'état demandé plutôt
     * que l'état obtenu ferait mentir l'écran.
     */
    private fun writeSafety(action: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            action()
            // ⚠️ Relire IMMÉDIATEMENT après l'écriture rend l'ancienne valeur : le calculateur
            // n'a pas encore appliqué la consigne. C'est ce qui faisait rester l'affichage sur
            // OFF alors que le réglage venait bien de passer à ON. L'UI d'origine ne relit pas
            // non plus — elle peint l'état demandé et attend le callback de changement.
            // Ici : peinture optimiste au clic (déjà faite), puis relecture qui fait autorité.
            delay(700)
            withContext(Dispatchers.Main) { if (isAdded) refreshSafetyDmsEsc() }
        }
    }

    private fun refreshSafetyDmsEsc() {
        if (!MG4Hardware.hasDrowsinessAndEsc()) return
        CoroutineScope(Dispatchers.IO).launch {
            val esc = MG4Hardware.isEscOn()
            val dms = MG4Hardware.isDrowsinessOn()
            val sen = MG4Hardware.getDrowsinessSensitivity()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                applyPairUI(btnEscOn, btnEscOff, esc)
                applyPairUI(btnDmsOn, btnDmsOff, dms)
                applySeatUI(
                    listOfNotNull(btnDmsSenLow, btnDmsSenMedium, btnDmsSenHigh),
                    // sen vaut 1..3 ; -1 (illisible) donne -1 donc aucun bouton allumé,
                    // ce qui vaut mieux qu'en allumer un au hasard.
                    sen - 1
                )
            }
        }
    }

    /**
     * Allume ON ou OFF selon [state]. `null` = état illisible : on n'allume RIEN, pour ne pas
     * laisser croire qu'on connaît un état qu'on n'a pas pu lire.
     */
    private fun applyPairUI(on: Button?, off: Button?, state: Boolean?) {
        listOf(on to (state == true), off to (state == false)).forEach { (btn, active) ->
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applySeatUI(buttons: List<Button>, activeIndex: Int) {
        buttons.forEachIndexed { i, btn ->
            val active = i == activeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 1 (ELK)
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyElkModeUI(activeMode: Int) {
        elkModeMap.forEach { (mode, btn) ->
            val active = mode == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkSensitivityUI(activeLevel: Int) {
        elkSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyAebSensitivityUI(activeLevel: Int) {
        aebSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkButtonsEnabled(enabled: Boolean) {
        (elkModeMap.values + elkSenMap.values).forEach { btn ->
            btn?.isEnabled = enabled
            btn?.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun applyElkSoundVibEnabled(enabled: Boolean) {
        switchElkSound?.isEnabled = enabled
        switchElkSound?.alpha = if (enabled) 1f else 0.35f
        switchElkVibration?.isEnabled = enabled
        switchElkVibration?.alpha = if (enabled) 1f else 0.35f
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 2 — Climatisation (old-SDK : voie AirConditionManager)
    //  Confort : ces écritures ne passent PAS par le verrou de vitesse (T-904).
    // ═════════════════════════════════════════════════════════════════════════

    /** Délai laissé au véhicule pour propager une écriture avant de relire son état. */
    private val CLIM_SETTLE_MS = 700L
    /** Période de suivi tant que la page clim est affichée. */
    private val CLIM_POLL_MS = 2_000L

    private var climTempSlider: Slider? = null
    private var climFanSlider: Slider? = null
    private var climTempValue: TextView? = null
    private var climFanValue: TextView? = null
    private var climBtnPower: MaterialButton? = null
    private var climBtnAc: MaterialButton? = null
    private var climBtnAuto: MaterialButton? = null
    private var climBtnDefFront: MaterialButton? = null
    private var climBtnDefRear: MaterialButton? = null
    private var climLoopButtons: Map<Int, MaterialButton?> = emptyMap()
    /** Dernier état connu — sert à savoir vers quoi basculer au clic d'un bouton. */
    private var climLastState: MG4Hardware.ClimateState? = null

    /** Anti-rebond des sliders : un glissement enverrait sinon des dizaines d'écritures binder. */
    private val climHandler = Handler(Looper.getMainLooper())
    private var climPendingWrite: Runnable? = null
    /** Vrai entre l'envoi d'une écriture et la relecture : bloque tout rafraîchissement. */
    @Volatile private var climWriteInFlight = false
    /** Vrai pendant qu'un doigt tient un slider : ne pas lui réimposer une valeur. */
    private var climSliderTouched = false

    /** Suivi périodique tant que la page clim est affichée (changements faits sur l'écran voiture). */
    private val climPollRunnable = object : Runnable {
        override fun run() {
            refreshClimatePage()
            climHandler.postDelayed(this, CLIM_POLL_MS)
        }
    }

    private fun startClimatePolling() {
        stopClimatePolling()
        climHandler.postDelayed(climPollRunnable, CLIM_POLL_MS)
    }

    private fun stopClimatePolling() = climHandler.removeCallbacks(climPollRunnable)

    private fun bindClimatePage(view: View) {
        climTempSlider     = view.findViewById(R.id.clim_temp_slider)
        climFanSlider      = view.findViewById(R.id.clim_fan_slider)
        climTempValue      = view.findViewById(R.id.clim_temp_value)
        climFanValue       = view.findViewById(R.id.clim_fan_value)
        climBtnPower       = view.findViewById(R.id.clim_btn_power)
        climBtnAc          = view.findViewById(R.id.clim_btn_ac)
        climBtnAuto        = view.findViewById(R.id.clim_btn_auto)
        climBtnDefFront    = view.findViewById(R.id.clim_btn_defrost_front)
        climBtnDefRear     = view.findViewById(R.id.clim_btn_defrost_rear)
        climLoopButtons = mapOf(
            MG4Hardware.LoopMode.INNER   to view.findViewById<MaterialButton>(R.id.clim_btn_loop_inner),
            MG4Hardware.LoopMode.OUTSIDE to view.findViewById<MaterialButton>(R.id.clim_btn_loop_outside),
            MG4Hardware.LoopMode.AUTO    to view.findViewById<MaterialButton>(R.id.clim_btn_loop_auto)
        )
        setupClimateListeners()
        refreshClimatePage()
    }

    /**
     * Écrit en IO puis rafraîchit — mais **après un délai**. Le véhicule met un instant à
     * propager la nouvelle valeur : relire immédiatement renvoyait l'ANCIENNE et la
     * réappliquait à l'UI, d'où le slider qui revenait en arrière avec un cran de retard.
     */
    private fun climateWrite(action: () -> Boolean) {
        climWriteInFlight = true
        CoroutineScope(Dispatchers.IO).launch {
            val ok = action()
            withContext(Dispatchers.Main) {
                if (isAdded && !ok)
                    Toast.makeText(requireContext(), R.string.clim_write_failed, Toast.LENGTH_SHORT).show()
            }
            delay(CLIM_SETTLE_MS)
            climWriteInFlight = false
            refreshClimatePage(force = true)
        }
    }

    /** Écriture différée (anti-rebond) — une seule requête part à la fin du glissement. */
    private fun climateWriteDebounced(action: () -> Boolean) {
        climPendingWrite?.let { climHandler.removeCallbacks(it) }
        val r = Runnable { climateWrite(action) }
        climPendingWrite = r
        climHandler.postDelayed(r, 200L)
    }

    private fun setupClimateListeners() {
        // Pendant qu'un doigt tient le slider, aucun rafraîchissement ne doit le déplacer.
        val touchGuard = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { climSliderTouched = true }
            override fun onStopTrackingTouch(s: Slider) { climSliderTouched = false }
        }
        climTempSlider?.addOnSliderTouchListener(touchGuard)
        climFanSlider?.addOnSliderTouchListener(touchGuard)

        climTempSlider?.addOnChangeListener { _, value, fromUser ->
            climTempValue?.text = "${value.toInt()}°"
            if (fromUser && !isRefreshing) {
                val target = value.toInt()
                climateWriteDebounced { MG4Hardware.setClimateTemp(target) }
            }
        }
        climFanSlider?.addOnChangeListener { _, value, fromUser ->
            climFanValue?.text = value.toInt().toString()
            if (fromUser && !isRefreshing) {
                val target = value.toInt()
                climateWriteDebounced { MG4Hardware.setClimateFan(target) }
            }
        }
        // Boutons bascule : on vise l'inverse du dernier état lu. Un état inconnu (null)
        // signifie « non lisible sur ce firmware » → le bouton est déjà désactivé.
        climBtnPower?.setOnClickListener {
            climLastState?.powerOn?.let { cur -> climateWrite { MG4Hardware.setClimatePower(!cur) } }
        }
        climBtnAc?.setOnClickListener {
            climLastState?.acOn?.let { cur -> climateWrite { MG4Hardware.setClimateAc(!cur) } }
        }
        climBtnAuto?.setOnClickListener {
            climLastState?.autoOn?.let { cur -> climateWrite { MG4Hardware.setClimateAuto(!cur) } }
        }
        climBtnDefFront?.setOnClickListener {
            climLastState?.defrostFront?.let { cur -> climateWrite { MG4Hardware.setClimateDefrostFront(!cur) } }
        }
        climBtnDefRear?.setOnClickListener {
            climLastState?.defrostRear?.let { cur -> climateWrite { MG4Hardware.setClimateDefrostRear(!cur) } }
        }
        climLoopButtons.forEach { (mode, btn) ->
            btn?.setOnClickListener { climateWrite { MG4Hardware.setClimateLoopMode(mode) } }
        }
    }

    /**
     * [force] = rafraîchissement explicite (arrivée sur la page, fin d'écriture). Sinon on
     * s'abstient si une écriture est en vol ou si l'utilisateur tient un slider — lui
     * réimposer une valeur pendant qu'il agit serait le pire des comportements.
     */
    private fun refreshClimatePage(force: Boolean = false) {
        if (climTempSlider == null) return
        if (!force && (climWriteInFlight || climSliderTouched)) return
        CoroutineScope(Dispatchers.IO).launch {
            val s = MG4Hardware.getClimateState()
            withContext(Dispatchers.Main) {
                if (!isAdded || s == null) return@withContext
                isRefreshing = true

                // Bornes lues sur le véhicule — jamais celles du XML.
                climTempSlider?.apply {
                    valueFrom = s.tempMin.toFloat()
                    valueTo   = s.tempMax.toFloat()
                    s.tempC?.let { value = it.coerceIn(s.tempMin, s.tempMax).toFloat() }
                    isEnabled = s.tempC != null
                }
                climTempValue?.text = s.tempC?.let { "$it°" } ?: "--°"

                climFanSlider?.apply {
                    valueFrom = s.fanMin.toFloat()
                    valueTo   = s.fanMax.toFloat()
                    s.fanLevel?.let { value = it.coerceIn(s.fanMin, s.fanMax).toFloat() }
                    isEnabled = s.fanLevel != null
                }
                climFanValue?.text = s.fanLevel?.toString() ?: "--"

                climLastState = s
                bindClimToggle(climBtnPower, s.powerOn)
                bindClimToggle(climBtnAc, s.acOn)
                bindClimToggle(climBtnAuto, s.autoOn)
                bindClimToggle(climBtnDefFront, s.defrostFront)
                bindClimToggle(climBtnDefRear, s.defrostRear)

                climLoopButtons.forEach { (mode, btn) ->
                    val active = s.loopMode == mode
                    btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
                    btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
                }

                isRefreshing = false
            }
        }
    }

    /**
     * Bouton bascule : accentué quand actif, comme les boutons de recirculation.
     * Un état null = non lisible sur ce firmware → bouton grisé et inerte, plutôt que menteur.
     */
    private fun bindClimToggle(btn: MaterialButton?, state: Boolean?) {
        val active = state == true
        btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
        btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        btn?.isEnabled = state != null
        btn?.alpha = if (state != null) 1f else 0.35f
    }
}
