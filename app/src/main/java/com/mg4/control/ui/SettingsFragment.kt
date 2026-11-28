package com.mg4.control.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatDelegate
import com.mg4.control.MainActivity
import com.mg4.control.ui.widget.setCategoryTabActive
import com.mg4.control.util.DistanceSetting
import com.mg4.control.util.NavSide
import com.mg4.control.util.ThemeHelper
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.BuildConfig
import com.mg4.control.api.ExternalApi
import com.mg4.control.R
import com.mg4.control.util.QrCode
import com.mg4.control.debug.AppLogger
import com.mg4.control.util.DataUsage
import com.mg4.control.debug.CrashLogger
import com.mg4.control.hardware.EnergyProbe
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.VehicleDistanceUnit
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.profile.ReadyProfilePolicy
import com.mg4.control.update.ApkCleanup
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateNotifier
import com.mg4.control.update.UpdateDialogManager
import java.io.File
import com.mg4.control.util.FirmwareHelper
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.service.MG4ControlService
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.GarageMode
import com.mg4.control.util.ClimateAutoScope
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.FullScreen
import com.mg4.control.util.FloatingButtonSettings
import com.mg4.control.service.ProjectionHvacZone
import com.mg4.control.service.SaicHvacPanel
import com.mg4.control.util.PopupDelay
import com.mg4.control.util.ChargePopupSettings
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.ClusterProfiles
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterConfigs
import com.mg4.control.model.DrivingProfile
import com.mg4.control.profile.ProfileManager
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterModuleDefs
import com.mg4.control.model.ClusterModuleDef
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DistanceUnit
import com.mg4.control.service.ChargeMonitor
import com.mg4.control.service.ClusterModuleOverlay
import com.mg4.control.util.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    /** « Enregistrer… » du diagnostic : la fenêtre ouverte, et le sélecteur de dossier qui lui répond. */
    private var exportDialog: ExportDialog? = null
    /** Import de la sauvegarde des réglages (issue #269) : la fenêtre ouverte, et le sélecteur de fichier. */
    private var importDialog: ImportDialog? = null
    private val choisirFichier = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { doc -> importDialog?.surFichier(doc) }
    private val choisirDossier = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { arbre ->
        exportDialog?.surDossier(arbre)
    }

    private val githubUrl = "https://github.com/SliDeeN/MG4Control"
    private val gitlabUrl = "https://gitlab.com/SliDeeN/mg4control"

    /** Index, dans le rail, de l'onglet ouvert — sauvegardé pour survivre à un recreate(). */
    private var selectedCategory = 0
        set(v) { field = v; ongletRetenu = v }

    /** Aperçu du combiné (carte Écran conducteur) : animé seulement quand l'écran est au premier plan. */
    private var apercuCombine: ClusterSettingsPreview? = null
    /** dp → px, pour les listes et cartes de l'écran conducteur posées par le code. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    /** Panneau « Avancé » de la carte Écran conducteur : replié à l'ouverture des Réglages. */
    private var avanceOuvert = false
    /** Profils réglés par la carte Écran conducteur (« Réglage de : »), par identifiant. */
    private var selectionCombine: Set<String> = emptySet()
    /** Réglages de la carte dépliés (un profil sélectionné au moins a le module) : l'aperçu tourne. */
    private var combineDeplie = false
    /** Refait la rangée de profils et repeint la carte, au retour sur l'écran. */
    private var majCarteCombine: (() -> Unit)? = null
    /** Liste des applications de l'API Profils (#288), refaite au retour sur l'écran. */
    private var listeApiProfils: LinearLayout? = null

    companion object {
        private const val STATE_CATEGORY = "settings_category"
        /** Sous-onglet rouvert en revenant sur Réglages, le temps de la session (2026-11-07). */
        private var ongletRetenu = 0

        /**
         * Argument : ouvrir l'écran sur le choix du firmware (onglet Infos, pastilles en vue).
         * Posé par la fenêtre « Firmware non reconnu » de [com.mg4.control.MainActivity].
         */
        const val ARG_SHOW_FIRMWARE = "show_firmware"

        /** En-tête du diagnostic quand un crash a été enregistré. */
        private const val CRASH_DETECTE = "CRASH DÉTECTÉ"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("mg4_settings", Context.MODE_PRIVATE)
        val accentColor  = requireContext().getColor(R.color.dash_accent)
        val accentDim    = requireContext().getColor(R.color.dash_accent_dim)
        val inactiveColor = requireContext().getColor(R.color.dash_btn)
        val textActive    = requireContext().getColor(R.color.dash_accent)
        val textInactive  = requireContext().getColor(R.color.text_secondary)

        // ── Langue ───────────────────────────────────────────────────────────
        val langButtons = listOf(
            "fr" to view.findViewById<MaterialButton>(R.id.btn_lang_fr),
            "en" to view.findViewById(R.id.btn_lang_en),
            "de" to view.findViewById(R.id.btn_lang_de),
            "es" to view.findViewById(R.id.btn_lang_es),
            "pt" to view.findViewById(R.id.btn_lang_pt),
            "it" to view.findViewById(R.id.btn_lang_it),
            "tr" to view.findViewById(R.id.btn_lang_tr),
            "pl" to view.findViewById(R.id.btn_lang_pl),
            "nb" to view.findViewById(R.id.btn_lang_nb),
            "cs" to view.findViewById(R.id.btn_lang_cs)
        )

        fun updateLangButtons(lang: String) {
            langButtons.forEach { (code, btn) ->
                val active = lang == code
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
            }
        }

        updateLangButtons(LocaleHelper.getLanguage(requireContext()))

        langButtons.forEach { (code, btn) ->
            btn.setOnClickListener {
                if (LocaleHelper.getLanguage(requireContext()) != code) {
                    LocaleHelper.setLanguage(requireContext(), code)
                    requireActivity().recreate()
                }
            }
        }

        // ── Écran par défaut ─────────────────────────────────────────────────
        val btnDefDashboard  = view.findViewById<MaterialButton>(R.id.btn_default_dashboard)
        val btnDefProfiles   = view.findViewById<MaterialButton>(R.id.btn_default_profiles)
        val btnDefShortcuts  = view.findViewById<MaterialButton>(R.id.btn_default_shortcuts)
        val defaultScreenBtns = listOf(
            "dashboard" to btnDefDashboard,
            "profiles"  to btnDefProfiles,
            "shortcuts" to btnDefShortcuts
        )

        fun updateDefaultScreenButtons(selected: String) {
            defaultScreenBtns.forEach { (key, btn) ->
                val active = key == selected
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
            }
        }

        val currentDefault = prefs.getString("default_screen", "dashboard") ?: "dashboard"
        updateDefaultScreenButtons(currentDefault)

        defaultScreenBtns.forEach { (key, btn) ->
            btn.setOnClickListener {
                prefs.edit().putString("default_screen", key).apply()
                updateDefaultScreenButtons(key)
            }
        }

        // ── Thème : Auto / Sombre / Clair ───────────────────────────────────
        val btnThemeAuto  = view.findViewById<MaterialButton>(R.id.btn_theme_auto)
        val btnThemeDark  = view.findViewById<MaterialButton>(R.id.btn_theme_dark)
        val btnThemeLight = view.findViewById<MaterialButton>(R.id.btn_theme_light)

        val themeBtns = listOf("auto" to btnThemeAuto, "dark" to btnThemeDark, "light" to btnThemeLight)

        fun updateThemeButtons(mode: String) {
            themeBtns.forEach { (key, btn) ->
                val active = key == mode
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.strokeColor = ColorStateList.valueOf(
                    if (active) accentColor else requireContext().getColor(R.color.dash_border)
                )
            }
        }

        val currentMode = prefs.getString(ThemeHelper.PREF_THEME_MODE, "auto") ?: "auto"
        updateThemeButtons(currentMode)

        fun applyThemeMode(mode: String) {
            if (prefs.getString(ThemeHelper.PREF_THEME_MODE, null) == mode) return
            prefs.edit().putString(ThemeHelper.PREF_THEME_MODE, mode).apply()
            updateThemeButtons(mode)
            AppCompatDelegate.setDefaultNightMode(ThemeHelper.resolveNightMode(requireContext()))
            requireActivity().recreate()
        }

        btnThemeAuto.setOnClickListener  { applyThemeMode("auto")  }
        btnThemeDark.setOnClickListener  { applyThemeMode("dark")  }
        btnThemeLight.setOnClickListener { applyThemeMode("light") }

        // ── Côté du menu : Auto / Gauche / Droite (2026-11-02) ──────────────
        // L'activité se recrée pour reposer le menu ; le sous-onglet ouvert est conservé.
        val navSideBtns = listOf(
            NavSide.Mode.AUTO  to view.findViewById<MaterialButton>(R.id.btn_nav_side_auto),
            NavSide.Mode.LEFT  to view.findViewById(R.id.btn_nav_side_left),
            NavSide.Mode.RIGHT to view.findViewById(R.id.btn_nav_side_right)
        )
        val navSide = NavSide.mode(requireContext())
        navSideBtns.forEach { (mode, btn) ->
            val active = mode == navSide
            btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
            btn.setTextColor(if (active) textActive else textInactive)
            btn.strokeColor = ColorStateList.valueOf(
                if (active) accentColor else requireContext().getColor(R.color.dash_border)
            )
            btn.setOnClickListener {
                if (mode == NavSide.mode(requireContext())) return@setOnClickListener
                NavSide.setMode(requireContext(), mode)
                AppLogger.i("MG4_SETTINGS", "Côté du menu → ${mode.key}")
                requireActivity().recreate()
            }
        }
        view.findViewById<TextView>(R.id.tv_nav_side_desc).text = getString(R.string.settings_nav_side_desc,
            getString(when (NavSide.detected(requireContext())) {
                com.mg4.control.hardware.DriverSide.Side.LEFT -> R.string.settings_nav_side_detected_left
                com.mg4.control.hardware.DriverSide.Side.RIGHT -> R.string.settings_nav_side_detected_right
                null -> R.string.settings_nav_side_detected_unknown
            }))

        // ── Unité de distance : Auto / km / miles (2026-11-10) ──────────────
        // Réglage général (Statistiques, écran conducteur, bulles), qui remplace la ligne
        // « Distance » de l'écran conducteur et en reprend le choix. Rien à recréer : chaque écran
        // relit l'unité quand il se dessine.
        val distBtns: List<Pair<DistanceUnit?, MaterialButton>> = listOf(
            null to view.findViewById(R.id.btn_distance_auto),
            DistanceUnit.KM to view.findViewById(R.id.btn_distance_km),
            DistanceUnit.MILES to view.findViewById(R.id.btn_distance_mi),
        )
        // La carte de l'écran conducteur se règle plus bas : elle se branche ici une fois posée.
        var rafraichirCombine: () -> Unit = {}
        fun peindreDistance() {
            val ctx = context ?: return
            val choix = DistanceSetting.choice(ctx)
            distBtns.forEach { (d, btn) ->
                val active = d == choix
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.strokeColor = ColorStateList.valueOf(
                    if (active) accentColor else ctx.getColor(R.color.dash_border)
                )
            }
            // « Auto (miles) » : ce que la voiture dit, dès qu'on l'a lu.
            distBtns[0].second.text = VehicleDistanceUnit.connue()?.let {
                getString(R.string.cluster_distance_auto_value, getString(
                    if (it == DistanceUnit.MILES) R.string.cluster_distance_miles else R.string.cluster_distance_km))
            } ?: getString(R.string.cluster_distance_auto)
        }
        peindreDistance()
        // Unité de pression des pneus (2026-11-21) : sa propre carte, à côté de l'unité de distance —
        // dans les réglages communs de l'écran conducteur, elle restait cachée sans le module.
        val tyreBtns = listOf(
            com.mg4.control.model.TyrePressure.PressureUnit.BAR to view.findViewById<MaterialButton>(R.id.btn_tyre_unit_bar),
            com.mg4.control.model.TyrePressure.PressureUnit.PSI to view.findViewById(R.id.btn_tyre_unit_psi),
            com.mg4.control.model.TyrePressure.PressureUnit.KPA to view.findViewById(R.id.btn_tyre_unit_kpa),
        )
        fun peindrePression() {
            val ctx = context ?: return
            val choix = com.mg4.control.util.TyreUnitSetting.unit(ctx)
            tyreBtns.forEach { (u, btn) ->
                val active = u == choix
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.strokeColor = ColorStateList.valueOf(if (active) accentColor else ctx.getColor(R.color.dash_border))
            }
        }
        peindrePression()
        tyreBtns.forEach { (u, btn) ->
            btn.setOnClickListener {
                com.mg4.control.util.TyreUnitSetting.set(requireContext(), u)
                AppLogger.i(ClusterModuleOverlay.TAG, "unité de pression → ${u.label}")
                peindrePression()
            }
        }
        distBtns.forEach { (d, btn) ->
            btn.setOnClickListener {
                DistanceSetting.set(requireContext(), d)
                AppLogger.i("MG4_SETTINGS", "Unité de distance → ${d?.key ?: DistanceUnit.AUTO}")
                peindreDistance()
                rafraichirCombine()
            }
        }
        // L'unité de la voiture, lue hors du fil principal (binder) : le bouton Auto la montre,
        // et l'aperçu du combiné la suit si Auto est choisi.
        Thread {
            val avant = VehicleDistanceUnit.connue()
            val lue = VehicleDistanceUnit.actualiser()
            if (lue != avant) view.post { if (isAdded) { peindreDistance(); rafraichirCombine() } }
        }.start()

        // ── Taille du texte : Standard / Grand / Très grand ─────────────────
        // Appliquée par LocaleHelper au contexte de l'activité : il faut la recréer pour que les
        // écrans se regonflent à la nouvelle échelle. Le sous-onglet ouvert est conservé (voir
        // bindCategoryRail), sinon chaque essai renverrait sur « Langues ».
        val textSizeBtns = listOf(
            TextSize.STANDARD to view.findViewById<MaterialButton>(R.id.btn_text_size_standard),
            TextSize.LARGE    to view.findViewById(R.id.btn_text_size_large),
            TextSize.XLARGE   to view.findViewById(R.id.btn_text_size_xlarge)
        )
        val currentTextSize = TextSize.get(requireContext())
        textSizeBtns.forEach { (size, btn) ->
            val active = size == currentTextSize
            btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
            btn.setTextColor(if (active) textActive else textInactive)
            btn.strokeColor = ColorStateList.valueOf(
                if (active) accentColor else requireContext().getColor(R.color.dash_border)
            )
            btn.setOnClickListener {
                if (size == TextSize.get(requireContext())) return@setOnClickListener
                TextSize.set(requireContext(), size)
                AppLogger.i("MG4_SETTINGS", "Taille du texte → ${size.key} (×${size.scale})")
                requireActivity().recreate()
            }
        }

        // ── Délai avant fermeture des pop-ups : 1 à 60 s ─────────────────────
        // Commun au sélecteur de profils, à la confirmation d'automatisation et au pop-up HVAC,
        // qui le lisent chacun à leur ouverture : rien à redémarrer.
        val tvPopupDelay = view.findViewById<TextView>(R.id.tv_popup_delay)
        view.findViewById<Slider>(R.id.slider_popup_delay).apply {
            valueFrom = PopupDelay.MIN_SECONDS.toFloat()
            valueTo   = PopupDelay.MAX_SECONDS.toFloat()
            // Dans la plage par construction : hors de [valueFrom, valueTo], le curseur lève une exception.
            value = PopupDelay.seconds(requireContext()).toFloat()
            tvPopupDelay.text = getString(R.string.win_auto_delay_value, value.toInt())
            addOnChangeListener { _, v, _ ->
                val secondes = PopupDelay.fromSlider(v)
                tvPopupDelay.text = getString(R.string.win_auto_delay_value, secondes)
                PopupDelay.set(requireContext(), secondes)
            }
            // Une ligne au journal par réglage, au relâchement, et non une par cran traversé.
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    AppLogger.i("MG4_SETTINGS", "Délai de fermeture des pop-ups → ${PopupDelay.fromSlider(slider.value)} s")
                }
            })
        }

        // ── Plein écran (2026-10-23) : désactivé par défaut, appliqué aussitôt.
        view.findViewById<Switch>(R.id.switch_fullscreen).apply {
            isChecked = FullScreen.isEnabled(requireContext())
            setOnCheckedChangeListener { _, checked ->
                FullScreen.setEnabled(requireContext(), checked)
                FullScreen.apply(requireActivity().window, checked)
                AppLogger.i("MG4_SETTINGS", "Plein écran ${if (checked) "activé" else "désactivé"}")
            }
        }

        // ── Barre clim pendant Android Auto / CarPlay (2026-10-24) : activée par défaut, la zone est
        // posée ou retirée aussitôt. Ici depuis le 2026-10-25 (avant : page des boutons flottants).
        view.findViewById<Switch>(R.id.switch_hvac_zone).apply {
            isChecked = FloatingButtonSettings.isHvacZoneEnabled(requireContext())
            setOnCheckedChangeListener { _, checked ->
                FloatingButtonSettings.setHvacZoneEnabled(requireContext(), checked)
                ProjectionHvacZone.refresh(requireContext())
                AppLogger.i("MG4_SETTINGS", "Barre clim pendant la projection ${if (checked) "activée" else "désactivée"}")
            }
        }

        setupBubbleHideApps(view)

        // ── Pop-up de charge (2026-10-16) : activé par défaut. Le suivi démarre ou s'arrête aussitôt.
        view.findViewById<Switch>(R.id.switch_charge_popup).apply {
            isChecked = ChargePopupSettings.isEnabled(requireContext())
            setOnCheckedChangeListener { _, checked ->
                ChargePopupSettings.setEnabled(requireContext(), checked)
                AppLogger.i(ChargeMonitor.TAG, "Pop-up de charge ${if (checked) "activé" else "désactivé"}")
                ChargeMonitor.refresh(requireContext())
            }
        }

        // ── Écran conducteur (refonte du 2026-11-20, prototype validé) ──────────
        // Une bibliothèque de modules commune à tous les profils (« Mes modules ») ; pour les profils
        // choisis dans « Réglage de : », l'interrupteur et une liste ordonnée de modules par onglet
        // (le n° 1 s'affiche, le raccourci « Module suivant » passe au suivant). Puissance maxi,
        // actualisation et animation sont communes (repliées). Plus d'aperçu sur la carte : il
        // n'existe que dans la personnalisation d'un module. Réservé aux firmwares où le combiné
        // montre l'écran central : SWI133, SWI68, SWI165.
        view.findViewById<View>(R.id.card_cluster_module).visibility =
            if (ClusterModuleSettings.isSupported()) View.VISIBLE else View.GONE
        val switchCluster = view.findViewById<Switch>(R.id.switch_cluster_module)
        val rowClusterChoice = view.findViewById<View>(R.id.row_cluster_choice)
        val rowClusterRefresh = view.findViewById<View>(R.id.row_cluster_refresh)
        apercuCombine = null
        val tvAvance = view.findViewById<TextView>(R.id.tv_cluster_advanced)
        val tvChevron = view.findViewById<TextView>(R.id.tv_cluster_advanced_chevron)
        val tvClusterRefresh = view.findViewById<TextView>(R.id.tv_cluster_refresh)
        val tvClusterAnimation = view.findViewById<TextView>(R.id.tv_cluster_animation)
        val sliderRefresh = view.findViewById<Slider>(R.id.slider_cluster_refresh).apply {
            valueFrom = ClusterModuleSettings.refreshToSlider(ClusterModuleSettings.MIN_REFRESH_MS)
            valueTo   = ClusterModuleSettings.refreshToSlider(ClusterModuleSettings.MAX_REFRESH_MS)
        }
        val sliderAnimation = view.findViewById<Slider>(R.id.slider_cluster_animation).apply {
            valueFrom = ClusterModuleSettings.animationToSlider(ClusterModuleSettings.MIN_ANIMATION_MS)
            valueTo   = ClusterModuleSettings.animationToSlider(ClusterModuleSettings.MAX_ANIMATION_MS)
        }
        val rowProfils = view.findViewById<View>(R.id.row_cluster_profiles)
        val grilleProfils = view.findViewById<LinearLayout>(R.id.grid_cluster_profiles)
        val tvProfilsInfo = view.findViewById<TextView>(R.id.tv_cluster_profiles_info)
        val tvModuleMixte = view.findViewById<TextView>(R.id.tv_cluster_module_mixed)
        val conteneurOnglets = view.findViewById<LinearLayout>(R.id.cluster_tabs)
        val conteneurBiblio = view.findViewById<LinearLayout>(R.id.cluster_library)
        val tvOngletsTitre = view.findViewById<TextView>(R.id.tv_cluster_tabs_title)
        val mixte = getString(R.string.settings_cluster_mixed)
        val couleurMixte = requireContext().getColor(R.color.dash_warn)
        // Le nombre est écrit dans la langue de l'application (0,5 s / 0.5 s), jamais Locale.ROOT.
        val langue = requireContext().resources.configuration.locales[0]
        fun texteActualisation(ms: Int) = String.format(langue, "%.1f s", ms / 1000f)
        // Puissance maxi de la jauge (issue #165) : Auto (`null`, apprise) ou une puissance choisie.
        val powerBtns: List<Pair<Int?, MaterialButton>> = listOf(
            null to view.findViewById(R.id.btn_cluster_power_auto),
            125 to view.findViewById(R.id.btn_cluster_power_125),
            150 to view.findViewById(R.id.btn_cluster_power_150),
            180 to view.findViewById(R.id.btn_cluster_power_180),
            320 to view.findViewById(R.id.btn_cluster_power_320),
        )
        val tvPowerDesc = view.findViewById<TextView>(R.id.tv_cluster_power_desc)
        // Halo de freinage (issue #278) : réglage commun, l'intensité grisée tant qu'il est coupé.
        val switchFrein = view.findViewById<Switch>(R.id.switch_cluster_brake_glow)
        val rowIntensite = view.findViewById<View>(R.id.row_cluster_brake_intensity)
        val intensiteBtns: List<Pair<com.mg4.control.model.BrakeGlow.Intensity, MaterialButton>> = listOf(
            com.mg4.control.model.BrakeGlow.Intensity.SOFT to view.findViewById(R.id.btn_cluster_brake_soft),
            com.mg4.control.model.BrakeGlow.Intensity.MEDIUM to view.findViewById(R.id.btn_cluster_brake_medium),
            com.mg4.control.model.BrakeGlow.Intensity.STRONG to view.findViewById(R.id.btn_cluster_brake_strong),
        )
        var majFrein = false

        selectionCombine = emptySet()
        var profils: List<DrivingProfile> = emptyList()
        var puces: List<Pair<String, MaterialButton>> = emptyList()
        // L'interrupteur est remis à l'état des profils sélectionnés par le code : ce n'est pas un choix.
        var majInterrupteur = false

        fun peindre(btn: MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
            btn.setTextColor(if (active) textActive else textInactive)
            btn.strokeColor = ColorStateList.valueOf(
                if (active) accentColor else requireContext().getColor(R.color.dash_border)
            )
        }
        fun reglagesChoisis(): List<ClusterConfig> = ClusterProfiles.configs(requireContext(), selectionCombine)
        fun nomsChoisis(): String =
            profils.filter { it.id in selectionCombine }.joinToString { it.name }.ifEmpty { "réglages communs" }
        fun modifier(quoi: String, transform: (ClusterConfig) -> ClusterConfig) {
            if (ClusterProfiles.update(requireContext(), selectionCombine, transform)) {
                AppLogger.i(ClusterModuleOverlay.TAG, "$quoi (${nomsChoisis()})")
            }
        }
        /** Réglages communs, pour l'aperçu de la personnalisation. */
        fun commun(): ClusterConfig = ClusterConfig(
            refreshMs = ClusterModuleSettings.refreshMs(requireContext()),
            animationMs = ClusterModuleSettings.animationMs(requireContext()),
            powerMax = ClusterModuleSettings.powerMaxChoice(requireContext()),
        )
        fun nomType(m: ClusterModule): String = getString(when (m) {
            ClusterModule.CONSUMPTION -> R.string.settings_cluster_consumption
            ClusterModule.POWER -> R.string.settings_cluster_power
            ClusterModule.TYRES -> R.string.settings_cluster_tyres
            ClusterModule.GRAPH -> R.string.settings_cluster_graph
        })
        fun nomOnglet(t: ClusterTab) = getString(if (t == ClusterTab.MEDIA) R.string.settings_cluster_row_media else R.string.settings_cluster_row_nav)
        /** Où un module est utilisé : « Paul · Navigation 1 »… pour tous les profils (ou les réglages communs). */
        fun utilisations(id: String): List<String> {
            val ctx = requireContext()
            val sources: List<Pair<String?, ClusterConfig>> = if (profils.isEmpty()) listOf(null to ClusterModuleSettings.legacy(ctx))
                else profils.map { it.name to ClusterConfigs.of(it) }
            return sources.flatMap { (nom, c) ->
                listOf(ClusterTab.NAVIGATION, ClusterTab.MEDIA).mapNotNull { t ->
                    val i = ClusterModuleDefs.ids(c, t).indexOf(id)
                    if (i < 0) null else listOfNotNull(nom, "${nomOnglet(t)} ${i + 1}").joinToString(" · ")
                }
            }
        }
        lateinit var updateClusterCard: () -> Unit
        fun ouvrirModule(def: ClusterModuleDef, nouveau: Boolean) {
            val ctx = requireContext()
            ClusterLayoutEditor(ctx, def, nouveau, utilisations(def.id).joinToString(" · "), null, commun(),
                surEnregistre = { fini ->
                    val biblio = ClusterModuleSettings.library(ctx)
                    ClusterModuleSettings.saveLibrary(ctx,
                        if (biblio.any { it.id == fini.id }) biblio.map { if (it.id == fini.id) fini else it } else biblio + fini)
                    ClusterModuleOverlay.relire()
                    if (isAdded) updateClusterCard()
                },
                surDupliquer = { source ->
                    val copie = source.copy(id = java.util.UUID.randomUUID().toString().take(12),
                        name = getString(R.string.cluster_module_copy, source.name).take(28))
                    val biblio = ClusterModuleSettings.library(ctx)
                    val i = biblio.indexOfFirst { it.id == source.id }
                    ClusterModuleSettings.saveLibrary(ctx, biblio.toMutableList().apply { add(if (i < 0) size else i + 1, copie) })
                    AppLogger.i(ClusterModuleOverlay.TAG, "module « ${source.name} » dupliqué")
                    if (isAdded) { updateClusterCard(); ouvrirModule(copie, false) }
                },
                surSupprimer = {
                    ClusterModuleSettings.saveLibrary(ctx, ClusterModuleSettings.library(ctx).filter { it.id != def.id })
                    // Retiré des onglets de TOUS les profils (et des réglages communs).
                    ClusterProfiles.update(ctx, profils.map { it.id }) { ClusterModuleDefs.without(it, def.id) }
                    AppLogger.i(ClusterModuleOverlay.TAG, "module « ${def.name} » supprimé")
                    ClusterModuleOverlay.relire()
                    if (isAdded) updateClusterCard()
                },
            ).ouvrir()
        }
        /** Les deux onglets : la liste des profils choisis (celle du premier ; « différent selon les profils » sinon). */
        fun construireOnglets(choisis: List<ClusterConfig>) {
            val ctx = requireContext()
            conteneurOnglets.removeAllViews()
            val biblio = ClusterModuleSettings.library(ctx)
            listOf(ClusterTab.NAVIGATION, ClusterTab.MEDIA).forEachIndexed { rang, t ->
                val listes = choisis.map { ClusterModuleDefs.ids(it, t) }
                val ids = listes.first().filter { id -> biblio.any { it.id == id } }
                val colonne = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_dialog_tile)
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                }
                colonne.addView(TextView(ctx).apply {
                    text = if (listes.distinct().size > 1) SpannableString("${nomOnglet(t)} — $mixte").apply {
                        setSpan(ForegroundColorSpan(couleurMixte), nomOnglet(t).length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else nomOnglet(t)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    textSize = 20f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                val rangee = FlowRow(ctx)
                fun ecrire(nouvelle: List<String>, quoi: String) {
                    modifier("Écran conducteur, onglet ${t.name} : $quoi") { ClusterModuleDefs.withIds(it, t, nouvelle) }
                    ClusterModuleOverlay.relire()
                    updateClusterCard()
                }
                ids.forEachIndexed { i, id ->
                    val m = biblio.first { it.id == id }
                    val puce = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.bg_dialog_tile)
                        setPadding(dp(6), dp(4), dp(2), dp(4))
                    }
                    puce.addView(TextView(ctx).apply {
                        text = "${i + 1}"
                        gravity = Gravity.CENTER
                        setTextColor(requireContext().getColor(R.color.dash_on_accent))
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(accentColor)
                        }
                    }, LinearLayout.LayoutParams(dp(30), dp(30)))
                    puce.addView(TextView(ctx).apply {
                        text = m.name
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        textSize = 18f
                        setPadding(dp(10), 0, dp(4), 0)
                    })
                    fun petit(texte: String, desc: Int, action: () -> Unit) = TextView(ctx).apply {
                        text = texte
                        contentDescription = getString(desc)
                        gravity = Gravity.CENTER
                        setTextColor(requireContext().getColor(R.color.text_secondary))
                        textSize = 20f
                        minWidth = dp(44); minHeight = dp(44)
                        isClickable = true
                        setOnClickListener { action() }
                    }
                    if (i > 0) puce.addView(petit("‹", R.string.cluster_tab_move_up) { ecrire(ClusterModuleDefs.move(ids, i, -1), "« ${m.name} » avancé") })
                    if (i < ids.size - 1) puce.addView(petit("›", R.string.cluster_tab_move_down) { ecrire(ClusterModuleDefs.move(ids, i, 1), "« ${m.name} » reculé") })
                    puce.addView(petit("✕", R.string.cluster_tab_remove) { ecrire(ids.filterIndexed { j, _ -> j != i }, "« ${m.name} » retiré") })
                    rangee.addView(puce)
                }
                rangee.addView(MaterialButton(ctx).apply {
                    text = getString(R.string.cluster_tab_add)
                    isAllCaps = false
                    textSize = 18f
                    setTextColor(accentColor)
                    backgroundTintList = ColorStateList.valueOf(inactiveColor)
                    strokeColor = ColorStateList.valueOf(requireContext().getColor(R.color.dash_border))
                    strokeWidth = dp(1)
                    cornerRadius = dp(14)
                    insetTop = 0; insetBottom = 0
                    setOnClickListener {
                        val dispo = biblio.filter { it.id !in ids }
                        if (dispo.isEmpty()) {
                            android.widget.Toast.makeText(ctx, R.string.cluster_tab_pick_empty, android.widget.Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        AppDialog.choisir(ctx, titre = getString(R.string.cluster_tab_pick_title),
                            choix = dispo.map { AppDialog.Choix("${it.name} · ${nomType(ClusterModuleDefs.module(it))}") },
                            annuler = getString(R.string.profile_cancel), choisi = -1) { r ->
                            dispo.getOrNull(r)?.let { ecrire(ids + it.id, "« ${it.name} » ajouté") }
                        }
                    }
                })
                colonne.addView(rangee, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = dp(8) })
                colonne.addView(TextView(ctx).apply {
                    text = getString(when {
                        ids.size > 1 -> R.string.cluster_tab_note_many
                        ids.size == 1 -> R.string.cluster_tab_note_one
                        else -> R.string.cluster_tab_note_none
                    })
                    setTextColor(requireContext().getColor(R.color.dash_text_lo))
                    textSize = 16f
                    setPadding(0, dp(6), 0, 0)
                })
                conteneurOnglets.addView(colonne, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { if (rang > 0) it.marginStart = dp(12) })
            }
        }
        /** « Mes modules » : une carte par module (nom, type, cases, utilisations) et « Nouveau module ». */
        fun construireBiblio() {
            val ctx = requireContext()
            conteneurBiblio.removeAllViews()
            val rangee = FlowRow(ctx, 12f)
            ClusterModuleSettings.library(ctx).forEach { m ->
                val type = ClusterModuleDefs.module(m)
                val carte = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_dialog_tile)
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    minimumWidth = dp(260)
                    isClickable = true
                    setOnClickListener { ouvrirModule(m, false) }
                }
                carte.addView(TextView(ctx).apply {
                    text = m.name
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    textSize = 19f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxWidth = dp(320); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val cases = ClusterModuleDefs.layout(m).slots.orEmpty().count { it != null }
                carte.addView(TextView(ctx).apply {
                    text = listOfNotNull(nomType(type),
                        getString(if (ClusterGaugeStyle.fromKey(m.style) == ClusterGaugeStyle.DISCREET) R.string.settings_cluster_style_discreet
                            else R.string.settings_cluster_style_dynamic).takeIf { type != ClusterModule.TYRES },
                        getString(R.string.cluster_library_slots, cases)).joinToString(" · ")
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 16f
                })
                val ou = utilisations(m.id)
                carte.addView(TextView(ctx).apply {
                    text = if (ou.isEmpty()) getString(R.string.cluster_library_unused) else ou.joinToString("\n")
                    setTextColor(if (ou.isEmpty()) requireContext().getColor(R.color.dash_text_lo) else accentColor)
                    textSize = 15f
                    setPadding(0, dp(4), 0, 0)
                })
                rangee.addView(carte)
            }
            rangee.addView(MaterialButton(ctx).apply {
                text = getString(R.string.cluster_library_new)
                isAllCaps = false
                textSize = 18f
                setTextColor(accentColor)
                backgroundTintList = ColorStateList.valueOf(inactiveColor)
                strokeColor = ColorStateList.valueOf(requireContext().getColor(R.color.dash_border))
                strokeWidth = dp(1)
                cornerRadius = dp(16)
                minHeight = dp(96); minimumHeight = dp(96)
                insetTop = 0; insetBottom = 0
                setOnClickListener {
                    // Directement dans la personnalisation : le centre s'y choisit (retour du 2026-11-20).
                    val n = ClusterModuleSettings.library(ctx).size + 1
                    ouvrirModule(ClusterModuleDef(java.util.UUID.randomUUID().toString().take(12),
                        getString(R.string.cluster_new_module_name, n)), true)
                }
            })
            conteneurBiblio.addView(rangee, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        updateClusterCard = {
            val ctx = requireContext()
            // Le combiné suit tout de suite, sans attendre son relevé suivant.
            ClusterModuleOverlay.relire()
            val choisis = reglagesChoisis()
            val etats = choisis.map { ClusterModuleSettings.isEnabled(it) }.distinct()
            // Déplié dès qu'un des profils sélectionnés a le module : il y a quelque chose à régler.
            val actif = true in etats
            combineDeplie = actif
            majInterrupteur = true
            switchCluster.isChecked = etats == listOf(true)
            majInterrupteur = false
            tvModuleMixte.visibility = if (etats.size > 1) View.VISIBLE else View.GONE
            rowClusterChoice.visibility = if (actif) View.VISIBLE else View.GONE
            rowClusterRefresh.visibility = if (avanceOuvert) View.VISIBLE else View.GONE

            // « Réglage de : »
            puces.forEach { (id, btn) -> ProfileChips.peindre(btn, id in selectionCombine) }
            val noms = profils.filter { it.id in selectionCombine }
            tvProfilsInfo.text = if (noms.size == 1) getString(R.string.settings_cluster_profiles_one, noms[0].name)
                else getString(R.string.settings_cluster_profiles_many, noms.size)
            tvOngletsTitre.text = getString(R.string.cluster_tabs_title)

            if (actif) construireOnglets(choisis)
            construireBiblio()

            val refresh = ClusterModuleSettings.refreshMs(ctx)
            val animation = ClusterModuleSettings.animationMs(ctx)
            val puissance = ClusterModuleSettings.powerMaxChoice(ctx)
            tvAvance.text = getString(R.string.cluster_common_summary, texteActualisation(refresh), texteActualisation(animation))
            tvChevron.text = if (avanceOuvert) "▴" else "▾"
            sliderRefresh.value = ClusterModuleSettings.refreshToSlider(refresh)
            tvClusterRefresh.text = texteActualisation(refresh)
            sliderAnimation.value = ClusterModuleSettings.animationToSlider(animation)
            tvClusterAnimation.text = texteActualisation(animation)
            powerBtns.forEach { (kw, btn) -> peindre(btn, kw == puissance) }
            tvPowerDesc.text = getString(R.string.settings_cluster_power_desc, ClusterModuleSettings.learnedPowerKw(ctx))
            val frein = ClusterModuleSettings.brakeGlowOn(ctx)
            majFrein = true
            switchFrein.isChecked = frein
            majFrein = false
            val intensite = ClusterModuleSettings.brakeIntensity(ctx)
            intensiteBtns.forEach { (i, btn) -> peindre(btn, i == intensite); btn.isEnabled = frein }
            rowIntensite.alpha = if (frein) 1f else 0.45f
        }
        /** Rangées de cinq puces, refaites à chaque retour sur l'écran : un profil a pu changer. */
        fun construirePuces() {
            val ctx = requireContext()
            profils = ProfileManager(ctx).getAll()
            val montre = ClusterProfiles.shownProfileId(ctx)
            selectionCombine = ClusterConfigs.keepSelection(selectionCombine, profils.map { it.id }, montre)
            rowProfils.visibility = if (profils.isEmpty()) View.GONE else View.VISIBLE
            val enCours = getString(R.string.settings_cluster_profile_current)
            puces = ProfileChips.construire(grilleProfils, profils,
                texte = { p -> ProfileChips.texte(p.name, enCours.takeIf { p.id == montre }) }) { id ->
                selectionCombine = when {
                    id !in selectionCombine -> selectionCombine + id
                    selectionCombine.size > 1 -> selectionCombine - id
                    else -> selectionCombine   // il en reste toujours un
                }
                updateClusterCard()
            }
        }
        view.findViewById<View>(R.id.btn_cluster_profiles_all).setOnClickListener {
            selectionCombine = profils.map { it.id }.toSet()
            updateClusterCard()
        }
        view.findViewById<View>(R.id.btn_cluster_profiles_current).setOnClickListener {
            selectionCombine = setOfNotNull(ClusterProfiles.shownProfileId(requireContext()) ?: profils.firstOrNull()?.id)
            updateClusterCard()
        }
        majCarteCombine = {
            construirePuces()
            updateClusterCard()
        }
        construirePuces()
        updateClusterCard()

        view.findViewById<View>(R.id.row_cluster_advanced_toggle).setOnClickListener {
            avanceOuvert = !avanceOuvert
            updateClusterCard()
        }
        switchCluster.setOnCheckedChangeListener { _, checked ->
            if (majInterrupteur) return@setOnCheckedChangeListener
            modifier("Module sur le combiné ${if (checked) "activé" else "désactivé"}") { it.copy(enabled = checked) }
            ClusterModuleOverlay.refresh(requireContext())
            updateClusterCard()
        }
        rafraichirCombine = { updateClusterCard() }
        powerBtns.forEach { (kw, btn) ->
            btn.setOnClickListener {
                ClusterModuleSettings.setPowerMax(requireContext(), kw)
                AppLogger.i(ClusterModuleOverlay.TAG, "Puissance maxi de la jauge → ${kw?.let { "$it kW" } ?: "Auto"} (commune)")
                updateClusterCard()
            }
        }
        fun reglerFrein(on: Boolean) {
            ClusterModuleSettings.setBrakeGlowOn(requireContext(), on)
            AppLogger.i(ClusterModuleOverlay.TAG, "Halo de freinage ${if (on) "activé" else "désactivé"} (commun)")
            updateClusterCard()
        }
        // Activation : avertissement à chaque fois (2026-11-25) — le halo est une estimation, pas
        // l'état réel des feux stop. Annuler, ou fermer sans répondre, remet l'interrupteur sur Non.
        switchFrein.setOnCheckedChangeListener { _, checked ->
            if (majFrein) return@setOnCheckedChangeListener
            if (!checked) { reglerFrein(false); return@setOnCheckedChangeListener }
            AppDialog.confirmer(
                requireContext(),
                titre = getString(R.string.settings_cluster_brake_glow),
                message = getString(R.string.settings_cluster_brake_warn_msg),
                ton = AppDialog.Ton.ALERTE,
                oui = getString(R.string.settings_cluster_brake_warn_ok),
                non = getString(R.string.profile_cancel),
                onOui = { if (isAdded) reglerFrein(true) },
                onNon = { if (isAdded) { majFrein = true; switchFrein.isChecked = false; majFrein = false } },
            )
        }
        intensiteBtns.forEach { (i, btn) ->
            btn.setOnClickListener {
                ClusterModuleSettings.setBrakeIntensity(requireContext(), i)
                AppLogger.i(ClusterModuleOverlay.TAG, "Halo de freinage : intensité ${i.key} (commune)")
                updateClusterCard()
            }
        }
        // Actualisation de la jauge, 0,1 à 1 s, et durée de l'animation, 0,2 à 2 s (issue #152) :
        // communes depuis le 2026-11-20, relues à chaque relevé. Le chiffre suit le doigt ;
        // l'enregistrement se fait au relâchement.
        sliderRefresh.addOnChangeListener { _, v, fromUser ->
            if (fromUser) tvClusterRefresh.text = texteActualisation(ClusterModuleSettings.refreshFromSlider(v))
        }
        sliderRefresh.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val ms = ClusterModuleSettings.refreshFromSlider(slider.value)
                ClusterModuleSettings.setRefreshMs(requireContext(), ms)
                AppLogger.i(ClusterModuleOverlay.TAG, "Actualisation de la jauge → $ms ms (commune)")
                updateClusterCard()
            }
        })
        sliderAnimation.addOnChangeListener { _, v, fromUser ->
            if (fromUser) tvClusterAnimation.text = texteActualisation(ClusterModuleSettings.animationFromSlider(v))
        }
        sliderAnimation.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val ms = ClusterModuleSettings.animationFromSlider(slider.value)
                ClusterModuleSettings.setAnimationMs(requireContext(), ms)
                AppLogger.i(ClusterModuleOverlay.TAG, "Animation de la jauge → $ms ms (commune)")
                updateClusterCard()
            }
        })

        // ── Canal de mise a jour beta ────────────────────────────────────────
        // Aucun avertissement bloquant : une beta ne donne pas le controle du vehicule a un
        // tiers, contrairement a l'API externe. Le texte sous l'interrupteur suffit, et il
        // mentionne l'absence de retour arriere, qui est la vraie contrainte.
        val switchBeta = view.findViewById<Switch>(R.id.switch_beta_channel)
        switchBeta.isChecked = prefs.getBoolean(UpdateChecker.KEY_BETA_CHANNEL, false)
        switchBeta.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(UpdateChecker.KEY_BETA_CHANNEL, checked).apply()
            AppLogger.i("MG4_UPDATE", "Canal beta ${if (checked) "ACTIVE" else "desactive"}")
        }

        // ── API externe (issue #79) ──────────────────────────────────────────
        // Le seul verrou de cette API : tant qu'il est off, le receiver et le provider refusent.
        // L'ACTIVATION passe par une confirmation explicite ; la désactivation est immédiate —
        // on ne met jamais d'obstacle devant un retour à l'état sûr.
        listeApiProfils = view.findViewById(R.id.profile_api_list)
        majApiProfils()
        val switchExternalApi = view.findViewById<Switch>(R.id.switch_external_api)
        switchExternalApi.isChecked = prefs.getBoolean(ExternalApi.KEY_ENABLED, false)
        // Drapeau plutôt que retrait/remise de l'écouteur : les remises à zéro programmatiques
        // ci-dessous rappellent l'écouteur, et sans garde on boucle.
        var apiSwitchProgrammatic = false
        switchExternalApi.setOnCheckedChangeListener { _, checked ->
            if (apiSwitchProgrammatic) return@setOnCheckedChangeListener

            if (!checked) {
                prefs.edit().putBoolean(ExternalApi.KEY_ENABLED, false).apply()
                AppLogger.i(ExternalApi.LOG_TAG, "API externe désactivée par l'utilisateur")
                return@setOnCheckedChangeListener
            }
            // Repasse à off le temps de la question : l'interrupteur ne montre « activé »
            // qu'après confirmation, jamais avant.
            apiSwitchProgrammatic = true
            switchExternalApi.isChecked = false
            apiSwitchProgrammatic = false

            showExternalApiConfirm { confirmed ->
                if (!confirmed) return@showExternalApiConfirm
                prefs.edit().putBoolean(ExternalApi.KEY_ENABLED, true).apply()
                apiSwitchProgrammatic = true
                switchExternalApi.isChecked = true
                apiSwitchProgrammatic = false
                AppLogger.i(ExternalApi.LOG_TAG, "API externe ACTIVÉE par l'utilisateur (confirmée)")
            }
        }

        // ── Garder l'A/C coupé quand la température change (issue #208), désactivé par défaut ──
        view.findViewById<View>(R.id.card_hvac_keep_ac).visibility =
            if (MG4Hardware.hasClimateControl()) View.VISIBLE else View.GONE
        view.findViewById<Switch>(R.id.switch_keep_ac).apply {
            isChecked = com.mg4.control.automation.AcKeepOff.isOn(requireContext())
            setOnCheckedChangeListener { _, checked -> com.mg4.control.automation.AcKeepOff.setOn(requireContext(), checked) }
        }
        view.findViewById<View>(R.id.row_hide_hvac_startup).visibility =
            if (SaicHvacPanel.supported()) View.VISIBLE else View.GONE
        val remplacementHvac = view.findViewById<View>(R.id.row_replace_hvac_panel)
        remplacementHvac.visibility =
            if (SaicHvacPanel.supported() && SaicHvacPanel.isOn(requireContext())) View.VISIBLE else View.GONE
        view.findViewById<Switch>(R.id.switch_hide_hvac_startup).apply {
            isChecked = SaicHvacPanel.isOn(requireContext())
            setOnCheckedChangeListener { _, checked ->
                SaicHvacPanel.setOn(requireContext(), checked)
                remplacementHvac.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        view.findViewById<Switch>(R.id.switch_replace_hvac_panel).apply {
            isChecked = SaicHvacPanel.isPopupOn(requireContext())
            setOnCheckedChangeListener { _, checked -> SaicHvacPanel.setPopupOn(requireContext(), checked) }
        }

        // ── Applis en plein écran (issue #248), désactivé par défaut ──
        setupImmersive(view)

        // ── Relancer la détection du téléphone branché (issue #251), désactivé par défaut ──
        view.findViewById<View>(R.id.card_projection_kick).visibility =
            if (runCatching { requireContext().packageManager.getPackageInfo("com.allgo.rui", 0) }.isSuccess)
                View.VISIBLE else View.GONE
        view.findViewById<Switch>(R.id.switch_projection_kick).apply {
            isChecked = com.mg4.control.service.ProjectionKick.isOn(requireContext())
            setOnCheckedChangeListener { _, checked -> com.mg4.control.service.ProjectionKick.setOn(requireContext(), checked) }
        }

        // ── Ce que règle AUTO (issue #160) : coché = la voiture choisit (défaut) ──
        view.findViewById<View>(R.id.card_hvac_auto_scope).visibility =
            if (MG4Hardware.hasClimateControl()) View.VISIBLE else View.GONE
        fun portee(sw: Int, tv: Int, lit: (android.content.Context) -> Boolean,
                   ecrit: (android.content.Context, Boolean) -> Unit, voiture: Int, garde: Int, nom: String) {
            val texte = view.findViewById<TextView>(tv)
            view.findViewById<Switch>(sw).apply {
                isChecked = lit(requireContext())
                texte.setText(if (isChecked) voiture else garde)
                setOnCheckedChangeListener { _, checked ->
                    ecrit(requireContext(), checked)
                    texte.setText(if (checked) voiture else garde)
                    AppLogger.i("MG4_SETTINGS", "AUTO : $nom ${if (checked) "laissé à la voiture" else "gardé à la main"}")
                }
            }
        }
        portee(R.id.switch_auto_scope_air, R.id.tv_auto_scope_air, ClimateAutoScope::carSetsAir, ClimateAutoScope::setCarSetsAir,
            R.string.settings_auto_scope_air_car, R.string.settings_auto_scope_air_kept, "sens de l'air")
        portee(R.id.switch_auto_scope_loop, R.id.tv_auto_scope_loop, ClimateAutoScope::carSetsLoop, ClimateAutoScope::setCarSetsLoop,
            R.string.settings_auto_scope_loop_car, R.string.settings_auto_scope_loop_kept, "recirculation")

        // ── Raccourci de secours : clic central du joystick droit tenu 5 s → MG4Control ──
        view.findViewById<Switch>(R.id.switch_rescue_shortcut).apply {
            isChecked = GarageMode.isRescueOn(requireContext())
            setOnCheckedChangeListener { _, checked -> GarageMode.setRescueOn(requireContext(), checked) }
        }

        // ── Mode Garage — MG4Control en veille complète ──────────────────────
        val switchGarage = view.findViewById<Switch>(R.id.switch_garage_mode)
        switchGarage.isChecked = GarageMode.isOn(requireContext())
        switchGarage.setOnCheckedChangeListener { _, checked ->
            GarageMode.setOn(requireContext(), checked)
            // La notification persistante est le seul repere permanent : elle doit basculer
            // tout de suite, sans attendre un redemarrage du service.
            runCatching {
                requireContext().startForegroundService(
                    Intent(requireContext(), MG4ControlService::class.java)
                        .setAction(MG4ControlService.ACTION_GARAGE_CHANGED)
                )
            }
        }

        // Passage en READY : oublier le profil choisi à la main et reprendre la chaîne habituelle
        // (température, Bluetooth, profil par défaut). Lu par le service à chaque passage en
        // READY : rien à lui signaler, le réglage vaut dès le prochain.
        val switchRetour = view.findViewById<Switch>(R.id.switch_ready_default)
        switchRetour.isChecked = ReadyProfilePolicy.isEnabled(requireContext())
        switchRetour.setOnCheckedChangeListener { _, checked ->
            ReadyProfilePolicy.setEnabled(requireContext(), checked)
        }

        // ── Sécurité conduite (verrou d'écriture par vitesse) ────────────────
        val switchSpeedGate = view.findViewById<Switch>(R.id.switch_speed_gate)
        val rowSpeedGateMax = view.findViewById<View>(R.id.row_speed_gate_max)
        val inputSpeedGateMax = view.findViewById<EditText>(R.id.input_speed_gate_max)

        val gateEnabled = prefs.getBoolean(VehicleWriteGate.KEY_ENABLED, false)
        switchSpeedGate.isChecked = gateEnabled
        rowSpeedGateMax.visibility = if (gateEnabled) View.VISIBLE else View.GONE
        inputSpeedGateMax.setText(prefs.getInt(VehicleWriteGate.KEY_MAX_KMH, 0).toString())

        switchSpeedGate.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(VehicleWriteGate.KEY_ENABLED, checked).apply()
            rowSpeedGateMax.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Valide + persiste la vitesse : clamp 0–250, réaffiche la valeur retenue.
        fun commitSpeedGateMax() {
            val clamped = VehicleWriteGate.clampSpeed(inputSpeedGateMax.text.toString().toIntOrNull())
            prefs.edit().putInt(VehicleWriteGate.KEY_MAX_KMH, clamped).apply()
            val clampedText = clamped.toString()
            if (inputSpeedGateMax.text.toString() != clampedText) {
                inputSpeedGateMax.setText(clampedText)
            }
        }
        inputSpeedGateMax.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitSpeedGateMax() }
        inputSpeedGateMax.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { commitSpeedGateMax() }
            false
        }

        // ── Vérification auto des mises à jour ───────────────────────────────
        // Build offline : pas de réseau → on masque toute l'UI de mise à jour.
        if (BuildConfig.OFFLINE) {
            view.findViewById<View>(R.id.row_auto_update).visibility = View.GONE
            view.findViewById<View>(R.id.row_update_overlay).visibility = View.GONE
            view.findViewById<View>(R.id.row_beta_channel).visibility = View.GONE
            view.findViewById<View>(R.id.row_update_buttons).visibility = View.GONE
        } else {
            val switchAutoUpdate = view.findViewById<Switch>(R.id.switch_auto_update)
            switchAutoUpdate.isChecked = prefs.getBoolean("auto_check_update", true)
            switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_check_update", checked).apply()
            }

            // Seul chemin de retour après « Ne plus me prévenir » sur le popup véhicule :
            // c'est aussi pour ça que le popup dit où le retrouver.
            val switchOverlay = view.findViewById<Switch>(R.id.switch_update_overlay)
            switchOverlay.isChecked = UpdateNotifier.isEnabled(requireContext())
            switchOverlay.setOnCheckedChangeListener { _, checked ->
                UpdateNotifier.setEnabled(requireContext(), checked)
            }
        }

        // ── Alimentation véhicule (SWI133) — éteint la voiture, garde l'écran ──
        val rowVehiclePower = view.findViewById<View>(R.id.row_vehicle_power)
        val dividerVehiclePower = view.findViewById<View>(R.id.row_vehicle_power_divider)
        val btnVehiclePower = view.findViewById<MaterialButton>(R.id.btn_vehicle_power_off)
        if (!MG4Hardware.hasVehiclePowerOff()) {
            rowVehiclePower.visibility = View.GONE
            dividerVehiclePower.visibility = View.GONE
        } else {
            btnVehiclePower.setOnClickListener {
                // Sécurité : on ne propose l'extinction que si le levier est confirmé en P.
                CoroutineScope(Dispatchers.IO).launch {
                    val inPark = MG4Hardware.isVehicleInPark()
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (inPark == true) {
                            AppDialog.confirmer(
                                requireContext(),
                                titre = getString(R.string.vehicle_power_dialog_title),
                                message = getString(R.string.vehicle_power_dialog_msg),
                                ton = AppDialog.Ton.DANGER,
                                icone = R.drawable.ic_overlay_power,
                                oui = getString(R.string.vehicle_power_dialog_confirm),
                                non = getString(R.string.vehicle_power_dialog_cancel),
                                onOui = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val ok = MG4Hardware.vehiclePowerOff()
                                        AppLogger.i("MG4_SETTINGS", "Vehicle power off → $ok")
                                    }
                                },
                            )
                        } else {
                            Toast.makeText(requireContext(), R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // ── Bouton Vérifier mise à jour ──────────────────────────────────────
        val btnUpdate     = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val btnDiagnostic = view.findViewById<MaterialButton>(R.id.btn_diagnostic)
        val originalUpdateText = getString(R.string.btn_check_update)

        // Bouton Diagnostic débloqué via 5 clics sur le logo (cf. MainActivity)
        btnDiagnostic.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE

        // État de charge : outil de relevé, débloqué avec le Diagnostic (même principe) — à la
        // place de « Onglet du combiné » depuis le 2026-10-23.
        val btnChargeState = view.findViewById<MaterialButton>(R.id.btn_charge_state)
        btnChargeState.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE
        btnChargeState.setOnClickListener { ChargeStateDialog.afficher(requireContext()) }

        // Pneus : outil de relevé (2026-11-20), débloqué avec le Diagnostic, sous lui.
        view.findViewById<MaterialButton>(R.id.btn_tyre_debug).apply {
            visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE
            setOnClickListener { TyreDebugDialog.afficher(requireContext()) }
        }

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false

            UpdateChecker.check(
                context = requireContext(),
                onUpdateAvailable = { updateInfo ->
                    if (isAdded) {
                        btnUpdate.isEnabled = true
                        UpdateDialogManager.show(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            updateInfo
                        )
                    }
                },
                onNoUpdate = {
                    if (isAdded) showUpToDate(btnUpdate, originalUpdateText)
                },
                onError = {
                    if (isAdded) showUpdateError(btnUpdate, originalUpdateText)
                }
            )
        }

        // ── Bouton Nettoyer APK ──────────────────────────────────────────────
        val btnClean = view.findViewById<MaterialButton>(R.id.btn_clean_apk)
        val originalCleanText = getString(R.string.btn_clean_apk)

        btnClean.setOnClickListener {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val apkFiles = downloadsDir.listFiles { _, name ->
                ApkCleanup.isAppApk(name)
            } ?: emptyArray()

            val count = apkFiles.count { it.delete() }

            btnClean.isEnabled = false
            if (count > 0) {
                btnClean.text = getString(R.string.clean_apk_done, count)
                btnClean.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco_dim))
                btnClean.strokeColor = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco))
                btnClean.setTextColor(requireContext().getColor(R.color.dash_eco))
            } else {
                btnClean.text = getString(R.string.clean_apk_none)
            }

            btnClean.postDelayed({
                if (isAdded) {
                    btnClean.text = originalCleanText
                    btnClean.backgroundTintList = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_btn))
                    btnClean.strokeColor = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_border))
                    btnClean.setTextColor(requireContext().getColor(R.color.text_secondary))
                    btnClean.isEnabled = true
                }
            }, 3_000)
        }

        // ── Bouton Diagnostic (caché par défaut — débloqué par 5 clics sur MAJ) ──
        btnDiagnostic.setOnClickListener {
            showDiagnosticDialog()
        }

        // ── Bouton Infos ─────────────────────────────────────────────────────
        brancherSauvegarde(view)
        view.findViewById<MaterialButton>(R.id.btn_infos).setOnClickListener {
            showInfosDialog()
        }

        // ── Bouton Paramètres Android (2026-10-31) ───────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_android_settings).setOnClickListener {
            com.mg4.control.util.AndroidSettings.open(requireContext())
        }

        // En dernier : le rail masque un onglet dont la page n'a plus rien de visible, il doit
        // donc être câblé APRÈS toutes les décisions de visibilité ci-dessus (build offline,
        // firmware sans extinction véhicule…), sinon le décompte serait faux.
        setupFirmwareChips(view)
        setupDataUsage(view)
        bindCategoryRail(view, savedInstanceState)
    }

    // ── Sauvegarde des réglages (issue #269, 2026-11-23) ──────────────────────────

    private fun brancherSauvegarde(view: View) {
        majDernierExport(view)
        view.findViewById<MaterialButton>(R.id.btn_backup_export).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val stats = ExportDialog.Case(getString(R.string.backup_include_stats), false)
            exportDialog = ExportDialog(ctx,
                titre = getString(R.string.backup_export_title),
                sousTitre = getString(R.string.backup_export_subtitle),
                bouton = getString(R.string.backup_export),
                titreFait = getString(R.string.backup_export_done),
                fichiers = {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
                    val texte = com.mg4.control.backup.SettingsBackup.export(ctx, stats.coche, BuildConfig.VERSION_NAME,
                        com.mg4.control.util.FirmwareInfo.getGeneration().name, date)
                    ctx.getSharedPreferences("mg4_settings", android.content.Context.MODE_PRIVATE).edit()
                        .putLong(com.mg4.control.backup.SettingsBackup.KEY_LAST_EXPORT, System.currentTimeMillis()).apply()
                    view.post { majDernierExport(view) }
                    listOf(com.mg4.control.backup.SettingsBackup.FILE_NAME to texte)
                },
                parcourir = { choisirDossier.launch(null) },
                case = stats,
            ).also { it.ouvrir() }
        }
        view.findViewById<MaterialButton>(R.id.btn_backup_import).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            importDialog = ImportDialog(ctx, com.mg4.control.backup.SettingsBackup.FILE_NAME,
                parcourir = { choisirFichier.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                titre = getString(R.string.backup_import_title_from),
            ) { texte, ou -> confirmerImport(texte, ou) }.also { it.ouvrir() }
        }
    }

    private fun majDernierExport(view: View) {
        val ms = view.context.getSharedPreferences("mg4_settings", android.content.Context.MODE_PRIVATE)
            .getLong(com.mg4.control.backup.SettingsBackup.KEY_LAST_EXPORT, 0L)
        view.findViewById<TextView>(R.id.tv_backup_last)?.apply {
            visibility = if (ms > 0L) View.VISIBLE else View.GONE
            if (ms > 0L) text = getString(R.string.backup_last_export,
                android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(ms)) + " " +
                    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms)))
        }
    }

    /** Le fichier lu : son contenu en clair, puis « Remplacer et redémarrer ». */
    private fun confirmerImport(texte: String?, ou: String) {
        val ctx = context ?: return
        val r = com.mg4.control.backup.SettingsBackup.lire(texte)
        if (r == null) {
            AppDialog.confirmer(ctx, titre = getString(R.string.backup_import_title),
                message = getString(if (texte == null) R.string.backup_import_not_found else R.string.backup_import_unreadable, ou),
                ton = AppDialog.Ton.ALERTE, oui = getString(R.string.nav_close))
            return
        }
        // Famille de firmware (2026-11-23) : refusé d'une famille à l'autre, signalé dans la même.
        val actuel = com.mg4.control.util.FirmwareInfo.getGeneration().name
        val compat = com.mg4.control.backup.SettingsBackup.compat(r.firmware.ifEmpty { null }, actuel)
        if (compat == com.mg4.control.backup.SettingsBackup.Compat.OTHER_FAMILY) {
            AppDialog.confirmer(ctx, titre = getString(R.string.backup_import_title),
                message = getString(R.string.backup_import_other_family, r.firmware, actuel),
                ton = AppDialog.Ton.ALERTE, oui = getString(R.string.nav_close))
            return
        }
        val lignes = buildList {
            add(getString(R.string.backup_import_from, r.date, r.app, r.firmware))
            when (compat) {
                com.mg4.control.backup.SettingsBackup.Compat.SAME_FAMILY -> add(getString(R.string.backup_import_same_family, r.firmware, actuel))
                com.mg4.control.backup.SettingsBackup.Compat.UNKNOWN -> add(getString(R.string.backup_import_unknown_fw))
                else -> Unit
            }
            add("• " + getString(R.string.backup_import_profiles, r.profils) + (r.defaut?.let { " — " + getString(R.string.backup_import_default, it) } ?: ""))
            add("• " + getString(R.string.backup_import_rules, r.automatisations))
            add("• " + getString(R.string.backup_import_custom, r.raccourcis))
            add("• " + getString(R.string.backup_import_settings, r.reglages))
            if (r.stats) add("• " + getString(R.string.backup_import_stats))
            if (r.plusRecent) add(getString(R.string.backup_import_newer))
            add("")
            add(getString(R.string.backup_import_warning))
        }
        AppDialog.confirmer(ctx, titre = getString(R.string.backup_import_title), message = lignes.joinToString("\n"),
            ton = AppDialog.Ton.ALERTE, oui = getString(R.string.backup_import_ok), non = getString(R.string.profile_cancel),
            onOui = {
                val app = ctx.applicationContext
                Thread {
                    val ok = texte != null && com.mg4.control.backup.SettingsBackup.import(app, texte, actuel)
                    if (ok) com.mg4.control.backup.AppRestart.relancer(app)
                    else view?.post {
                        AppDialog.confirmer(ctx, titre = getString(R.string.backup_import_title),
                            message = getString(R.string.backup_import_unreadable, ou), ton = AppDialog.Ton.ALERTE,
                            oui = getString(R.string.nav_close))
                    }
                }.start()
            })
    }

    override fun onResume() {
        super.onResume()
        majApiProfils()
        majCarteCombine?.invoke()
        if (combineDeplie && pageInterfaceVisible()) apercuCombine?.demarrer()
    }

    /**
     * API Profils (issue #288) : une ligne par application qui a demandé l'accès — son nom, sa
     * réponse (autorisée, refusée, signature modifiée) et « Retirer », qui oublie la réponse
     * (l'application redemandera). Vide : une phrase le dit.
     */
    private fun majApiProfils() {
        val liste = listeApiProfils ?: return
        val ctx = liste.context
        fun dp(v: Float) = com.mg4.control.service.OverlayKit.dp(ctx, v)
        liste.removeAllViews()
        val apps = com.mg4.control.api.ProfileApiAccess.entries(ctx)
        if (apps.isEmpty()) {
            liste.addView(TextView(ctx).apply {
                text = getString(R.string.profile_api_empty)
                setTextColor(ctx.getColor(R.color.dash_text_lo))
                textSize = 16f
            })
            return
        }
        apps.forEach { e ->
            val st = com.mg4.control.api.ProfileApiAccess.status(ctx, e.pkg)
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, dp(6f))
            }
            ligne.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = com.mg4.control.api.ProfileApiAccess.label(ctx, e.pkg).takeIf { it != e.pkg } ?: e.label ?: e.pkg
                    setTextColor(ctx.getColor(R.color.text_primary))
                    textSize = 18f
                })
                addView(TextView(ctx).apply {
                    text = getString(when (st) {
                        com.mg4.control.api.ProfileApiAccess.Status.ALLOWED -> R.string.profile_api_allowed
                        com.mg4.control.api.ProfileApiAccess.Status.CHANGED -> R.string.profile_api_changed
                        else -> R.string.profile_api_denied
                    }) + " · " + e.pkg
                    setTextColor(ctx.getColor(when (st) {
                        com.mg4.control.api.ProfileApiAccess.Status.ALLOWED -> R.color.dash_eco
                        com.mg4.control.api.ProfileApiAccess.Status.CHANGED -> R.color.dash_warn
                        else -> R.color.text_secondary
                    }))
                    textSize = 15f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            ligne.addView(MaterialButton(ctx).apply {
                text = getString(R.string.profile_api_remove)
                isAllCaps = false
                textSize = 17f
                insetTop = 0; insetBottom = 0
                cornerRadius = dp(14f)
                strokeWidth = dp(1f)
                ProfileChips.peindre(this, false)
                setOnClickListener {
                    com.mg4.control.api.ProfileApiAccess.remove(ctx, e.pkg)
                    majApiProfils()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52f)).also { it.marginStart = dp(12f) })
            liste.addView(ligne)
        }
    }

    /** L'aperçu de l'écran conducteur ne tourne que sur l'onglet Interface (2026-11-14). */
    private fun pageInterfaceVisible(): Boolean = view?.findViewById<View>(R.id.page_set_ui)?.visibility == View.VISIBLE

    override fun onPause() {
        apercuCombine?.arreter()
        super.onPause()
    }

    override fun onDestroyView() {
        apercuCombine?.arreter()
        apercuCombine = null
        majCarteCombine = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CATEGORY, selectedCategory)
    }

    /**
     * Sous-onglets en pastilles : une catégorie visible à la fois — même motif que l'éditeur de profil.
     *
     * Le bouton Diagnostic reste dans l'arbre même sur une page masquée : [MainActivity] peut donc
     * continuer à le révéler en direct après les 5 clics sur le logo, quel que soit l'onglet ouvert.
     *
     * L'onglet ouvert survit à un `recreate()` : langue, thème et taille du texte recréent
     * l'activité, et retomber à chaque fois sur le premier onglet obligeait à y retourner.
     */
    private fun bindCategoryRail(view: View, savedInstanceState: Bundle?) {
        val tabs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_set_cat_lang)     to view.findViewById<ViewGroup>(R.id.page_set_lang),
            view.findViewById<MaterialButton>(R.id.btn_set_cat_ui)       to view.findViewById<ViewGroup>(R.id.page_set_ui),
            view.findViewById<MaterialButton>(R.id.btn_set_cat_advanced) to view.findViewById<ViewGroup>(R.id.page_set_advanced),
            view.findViewById<MaterialButton>(R.id.btn_set_cat_info)     to view.findViewById<ViewGroup>(R.id.page_set_info)
        )
        val scroll = view.findViewById<ScrollView>(R.id.scroll_settings)

        fun hasVisibleContent(page: ViewGroup): Boolean =
            (0 until page.childCount).any { page.getChildAt(it).visibility == View.VISIBLE }

        val usable = tabs.filter { (_, page) -> hasVisibleContent(page) }
        tabs.forEach { (btn, page) ->
            btn.visibility = if (usable.any { it.second === page }) View.VISIBLE else View.GONE
        }
        if (usable.isEmpty()) return

        fun select(target: ViewGroup) {
            selectedCategory = tabs.indexOfFirst { it.second === target }
            tabs.forEach { (btn, page) ->
                val on = page === target
                page.visibility = if (on) View.VISIBLE else View.GONE
                btn.setCategoryTabActive(on)
            }
            scroll?.scrollTo(0, 0)   // changer d'onglet en gardant le scroll précédent désoriente
            // Aperçu de l'écran conducteur : arrêté hors de l'onglet Interface — son trajet simulé
            // tournait sur le fil principal tant que Réglages restait ouvert (2026-11-14).
            if (target.id == R.id.page_set_ui && combineDeplie && isResumed) apercuCombine?.demarrer()
            else apercuCombine?.arreter()
        }
        usable.forEach { (btn, page) -> btn.setOnClickListener { select(page) } }
        // Un onglet mémorisé peut ne plus être disponible (page vidée entre-temps) : repli sur le premier.
        val restored = tabs.getOrNull(savedInstanceState?.getInt(STATE_CATEGORY, -1) ?: ongletRetenu)
        // Arrivée depuis « Firmware non reconnu » : droit sur l'onglet où il se choisit. À la
        // première création seulement — après le choix l'activité se recrée, et c'est alors
        // l'onglet mémorisé qui compte.
        val versLeFirmware = savedInstanceState == null && arguments?.getBoolean(ARG_SHOW_FIRMWARE) == true
        val demande = if (versLeFirmware) tabs.firstOrNull { it.second.id == R.id.page_set_info } else restored
        select((demande?.takeIf { it in usable } ?: usable.first()).second)
        if (versLeFirmware) scroll?.post {
            // Les pastilles sont au milieu de la page : on les amène en vue, un peu sous le bord.
            val pastille = view.findViewById<View>(R.id.chip_swi133) ?: return@post
            val place = android.graphics.Rect()
            pastille.getDrawingRect(place)
            scroll.offsetDescendantRectToMyCoords(pastille, place)
            scroll.smoothScrollTo(0, (place.top - scroll.height / 3).coerceAtLeast(0))
        }
    }


    // ── Feedback "application à jour" sur le bouton ──────────────────────────

    /**
     * Applis en plein écran (issue #248) : interrupteur, résumé des applis choisies et leur mode,
     * bouton vers la fenêtre de choix. En pause tant qu'un MG4Dash qui ne fusionne pas est installé.
     */
    /**
     * Boutons flottants masqués dans des applications choisies (issue #261) : interrupteur, puis
     * « Applications choisies » et Choisir (n), grisés quand l'option est coupée. Les bulles
     * suivent aussitôt (FloatingButtonsOverlay.refresh).
     */
    private fun setupBubbleHideApps(view: View) {
        val ctx = requireContext()
        val resume = view.findViewById<TextView>(R.id.tv_bubble_hide_apps_summary)
        val bouton = view.findViewById<MaterialButton>(R.id.btn_bubble_hide_apps_choose)
        val rangee = view.findViewById<View>(R.id.row_bubble_hide_apps)
        fun maj() {
            val choisies = FloatingButtonSettings.hideApps(ctx)
            val noms = BubbleHideAppsDialog.candidates(ctx).filter { it.first in choisies }.map { it.second }
            resume.text = noms.joinToString(", ").ifEmpty { getString(R.string.immersive_apps_none) }
            bouton.text = getString(R.string.immersive_choose, noms.size)
            val on = FloatingButtonSettings.isHideInAppsOn(ctx)
            rangee.alpha = if (on) 1f else 0.4f
            bouton.isEnabled = on
        }
        maj()
        view.findViewById<Switch>(R.id.switch_bubble_hide_apps).apply {
            isChecked = FloatingButtonSettings.isHideInAppsOn(ctx)
            setOnCheckedChangeListener { _, checked ->
                FloatingButtonSettings.setHideInAppsOn(ctx, checked)
                AppLogger.i("MG4_SETTINGS", "Boutons flottants masqués dans des applications ${if (checked) "activé" else "désactivé"}")
                com.mg4.control.service.FloatingButtonsOverlay.refresh(ctx)
                maj()
            }
        }
        bouton.setOnClickListener {
            BubbleHideAppsDialog.show(ctx) {
                AppLogger.i("MG4_SETTINGS", "Boutons flottants masqués dans : ${FloatingButtonSettings.hideApps(ctx).sorted().joinToString()}")
                com.mg4.control.service.FloatingButtonsOverlay.refresh(ctx)
                if (isAdded) maj()
            }
        }
    }

    private fun setupImmersive(view: View) {
        val ctx = requireContext()
        val resume = view.findViewById<TextView>(R.id.tv_immersive_summary)
        val bouton = view.findViewById<MaterialButton>(R.id.btn_immersive_choose)
        view.findViewById<View>(R.id.tv_immersive_outdated).visibility =
            if (com.mg4.control.service.ImmersiveApps.mg4Dash(ctx) == com.mg4.control.service.ImmersiveApps.Mg4Dash.OUTDATED)
                View.VISIBLE else View.GONE
        fun maj() {
            val choix = com.mg4.control.service.ImmersiveApps.selection(ctx)
            val pm = ctx.packageManager
            resume.text = choix.mapNotNull { (paquet, mode) ->
                val nom = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(paquet, 0)).toString() }.getOrNull()
                    ?: return@mapNotNull null
                "$nom (" + getString(when (mode) {
                    com.mg4.control.model.ImmersiveMode.STATUS -> R.string.immersive_mode_status
                    com.mg4.control.model.ImmersiveMode.NAVIGATION -> R.string.immersive_mode_nav
                    com.mg4.control.model.ImmersiveMode.FULL -> R.string.immersive_mode_full
                }) + ")"
            }.joinToString(", ").ifEmpty { getString(R.string.immersive_apps_none) }
            bouton.text = getString(R.string.immersive_choose, choix.size)
        }
        maj()
        val inter = view.findViewById<Switch>(R.id.switch_immersive)
        inter.isChecked = com.mg4.control.service.ImmersiveApps.isOn(ctx)
        // À chaque activation : avertissement (conflits possibles). Refus ou fenêtre fermée :
        // l'interrupteur revient sur OFF sans rien écrire. L'astuce du glissement depuis le bord
        // est retirée (2026-11-20) : SAIC verrouille ce glissement, il ne fait rien.
        var silence = false
        inter.setOnCheckedChangeListener { _, checked ->
            if (silence) return@setOnCheckedChangeListener
            if (!checked) {
                com.mg4.control.service.ImmersiveApps.setOn(ctx, false)
                return@setOnCheckedChangeListener
            }
            AppDialog.confirmer(
                ctx,
                titre = getString(R.string.immersive_title),
                message = getString(R.string.immersive_confirm_msg),
                ton = AppDialog.Ton.ALERTE,
                oui = getString(R.string.immersive_confirm_ok),
                non = getString(R.string.profile_cancel),
                onOui = { com.mg4.control.service.ImmersiveApps.setOn(ctx, true) },
                onNon = {
                    if (isAdded) { silence = true; inter.isChecked = false; silence = false }
                },
            )
        }
        bouton.setOnClickListener { ImmersiveAppsDialog.show(ctx) { if (isAdded) maj() } }
    }

    private fun showUpToDate(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val ecoDim    = ctx.getColor(R.color.dash_eco_dim)
        val eco       = ctx.getColor(R.color.dash_eco)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        // Passe le bouton en vert "à jour"
        btn.text = getString(R.string.update_up_to_date)
        btn.backgroundTintList = ColorStateList.valueOf(ecoDim)
        btn.strokeColor        = ColorStateList.valueOf(eco)
        btn.setTextColor(eco)
        btn.isEnabled = false

        // Revient à l'état normal après 3 secondes
        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = ColorStateList.valueOf(accentDim)
                btn.strokeColor        = ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Feedback "erreur réseau" sur le bouton ────────────────────────────────

    private fun showUpdateError(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val dangerDim = ctx.getColor(R.color.dash_danger_dim)
        val danger    = ctx.getColor(R.color.dash_danger)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        btn.text = getString(R.string.update_network_error)
        btn.backgroundTintList = ColorStateList.valueOf(dangerDim)
        btn.strokeColor        = ColorStateList.valueOf(danger)
        btn.setTextColor(danger)
        btn.isEnabled = false

        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = ColorStateList.valueOf(accentDim)
                btn.strokeColor        = ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Dialog Diagnostic ────────────────────────────────────────────────────

    private fun showDiagnosticDialog() {
        val ctx = requireContext()

        // Sonde diagnostic : logge volume + état des portes AVANT de rendre les logs,
        // pour que le rapport les contienne (indépendant du toggle / de l'onglet Audio).
        MG4Hardware.runDoorVolumeDiag()
        // Sonde média : qui joue, et surtout quelles sessions média existent — c'est ce qui
        // décide si une touche « piste suivante » peut aboutir quelque part.
        MG4Hardware.runMediaDiag()
        // Sonde somnolence / sensibilité / ESC : lecture seule (elle ne bascule RIEN — un
        // rapport de diagnostic ne doit pas toucher à un organe de sécurité active).
        MG4Hardware.runSafetyDiag()
        // Sonde feux de route automatiques : lecture seule, toutes les voies du firmware.
        MG4Hardware.runHighBeamDiag()
        MG4Hardware.runBatteryHeatingDiag()
        // Sonde accessibilité : service connecté ou non, et la liste brute des Réglages — sa
        // forme courte faisait dire « inactif » à tort après une MAJ ou un redémarrage.
        com.mg4.control.accessibility.KeyCaptureService.journaliserEtat(ctx)
        // Sonde type de batterie : décide du curseur de limite du pop-up de charge sur A9
        // (Standard LFP sans, Confort NMC avec — même firmware SWI69).
        com.mg4.control.hardware.BatteryTypeConfig.journaliser()
        com.mg4.control.hardware.DriverSide.journaliser()
        // Énergie par tranche de 1 % apprise pendant les charges vues (issue #156).
        com.mg4.control.service.ChargeMonitor.journaliserApprentissage(ctx)

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        // ── Le journal : un crash enregistré d'abord, puis le rapport matériel et le journal ──
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_diagnostic, null)
        val crashLog = CrashLogger.read(ctx)
        val tvReport = vue.findViewById<TextView>(R.id.diag_report)

        // AppLogger en temps réel (30 dernières lignes)
        val tvLogs = vue.findViewById<TextView>(R.id.diag_logs)
        val entries = AppLogger.entries
        tvLogs.text = if (entries.isEmpty()) "─── AppLogger vide ───"
            else "─── AppLogger (${entries.size} entrées) ───\n" +
                entries.takeLast(30).joinToString("\n") { e ->
                    "${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}"
                }

        // Un crash enregistré s'annonce en rouge, dès l'en-tête, et passe devant tout le reste.
        val crash = crashLog != null
        OverlayKit.fond(
            vue.findViewById(R.id.diag_badge),
            ctx.getColor(if (crash) R.color.dash_danger_dim else R.color.dash_accent_dim), ovale = true)
        vue.findViewById<ImageView>(R.id.diag_icon).imageTintList = ColorStateList.valueOf(
            ctx.getColor(if (crash) R.color.dash_danger else R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.diag_close).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        vue.findViewById<TextView>(R.id.diag_crash_flag).apply {
            text = CRASH_DETECTE
            visibility = if (crash) View.VISIBLE else View.GONE
        }
        vue.findViewById<TextView>(R.id.diag_crash).apply {
            text = crashLog
            visibility = if (crash) View.VISIBLE else View.GONE
        }

        val dialog = AppDialog.afficher(ctx, vue, 1320f)

        // Le journal n'apparaît qu'une fois : tout pour le fichier, les 400 dernières lignes pour le
        // presse-papier (ce qu'il contenait avant 2026-11-12 — le presse-papier passe par un
        // échange entre processus limité à ~1 Mo).
        fun buildReport(fullLog: Boolean) = buildString {
            if (crashLog != null) { appendLine(crashLog); appendLine() }
            appendLine(tvReport.text)
            appendLine()
            val toutes = AppLogger.entries
            val gardees = if (fullLog) toutes else toutes.takeLast(400)
            appendLine("─── AppLogger (${gardees.size} / ${toutes.size} entrées) ───")
            gardees.forEach { e -> appendLine("${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}") }
        }

        // « Copier » — copie tout sans fermer la fenêtre
        val btnCopier = vue.findViewById<TextView>(R.id.diag_copy)
        btnCopier.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MG4Control Diagnostic", buildReport(false)))
            btnCopier.text = getString(R.string.diag_copied)
        }
        // « Enregistrer… » (maquette B du 2026-11-03) : le rapport complet, à l'endroit choisi —
        // Téléchargements, clé USB ou autre dossier, comme l'export des Statistiques. Le rapport
        // est pris au moment de l'appui : il lit les vues de la fenêtre.
        vue.findViewById<View>(R.id.diag_download).setOnClickListener {
            val rapport = buildReport(true)
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            exportDialog = ExportDialog(ctx,
                titre = getString(R.string.diag_save_title),
                sousTitre = getString(R.string.diag_save_subtitle),
                bouton = getString(R.string.diag_save_go),
                titreFait = getString(R.string.diag_saved),
                fichiers = { listOf("MG4Control_diag_$ts.txt" to rapport) },
                parcourir = { choisirDossier.launch(null) },
            ).also { it.ouvrir() }
        }
        // « Effacer le crash » — supprime le fichier et ferme la fenêtre
        vue.findViewById<View>(R.id.diag_clear_crash).apply {
            visibility = if (crash) View.VISIBLE else View.GONE
            setOnClickListener {
                CrashLogger.clear(ctx)
                dialog.dismiss()
            }
        }
        vue.findViewById<View>(R.id.diag_close).setOnClickListener { dialog.dismiss() }

        // Génération du rapport matériel sur le thread IO
        CoroutineScope(Dispatchers.IO).launch {
            // Sonde énergie : un relevé par ouverture du diagnostic — c'est ce qui permet à un
            // testeur d'en prendre plusieurs (avant un trajet, après, pendant une charge) sans
            // autre manipulation. Sur le fil IO : une trentaine de propriétés, plusieurs voies
            // chacune, ça ne passe pas sur le fil principal.
            EnergyProbe.run("diagnostic")
            // Sonde pneus (2026-11-20) : un relevé par ouverture, pneus froids puis après un trajet.
            com.mg4.control.hardware.TyreProbe.run("diagnostic")
            // Sonde lumière/position : un relevé instantané, à prendre en plein jour, au crépuscule
            // et dans un parking couvert. Ceux pris automatiquement au READY sont déjà gardés.
            com.mg4.control.hardware.LightProbe.run("diagnostic")
            val report = MG4Hardware.buildDiagnosticReport(appVersion)
            withContext(Dispatchers.Main) {
                if (isAdded) tvReport.text = report
            }
        }
    }

    // ── Dialog À propos ──────────────────────────────────────────────────────

    private fun showInfosDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_info, null)

        // Version de l'app
        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        dialogView.findViewById<TextView>(R.id.tv_app_version).text = versionName

        // Version firmware (lecture asynchrone)
        val tvFirmware = dialogView.findViewById<TextView>(R.id.tv_firmware_info)
        FirmwareHelper.getMpuVersion { version ->
            requireActivity().runOnUiThread {
                if (isAdded) tvFirmware.text = version ?: "N/A"
            }
        }

        // QR Code GitHub
        val ivQrGithub = dialogView.findViewById<ImageView>(R.id.iv_qr_code_github)
        QrCode.generate(githubUrl, 400)?.let { ivQrGithub.setImageBitmap(it) }

        // QR Code GitLab
        val ivQrGitlab = dialogView.findViewById<ImageView>(R.id.iv_qr_code_gitlab)
        QrCode.generate(gitlabUrl, 400)?.let { ivQrGitlab.setImageBitmap(it) }

        // Le nom de chaque dépôt, sous son QR code, l'ouvre dans le navigateur de la voiture
        dialogView.findViewById<TextView>(R.id.tv_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }
        dialogView.findViewById<TextView>(R.id.tv_gitlab_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gitlabUrl)))
        }

        dialogView.findViewById<ImageView>(R.id.iv_info_icon).imageTintList =
            ColorStateList.valueOf(requireContext().getColor(R.color.dash_accent))
        val dialog = AppDialog.afficher(requireContext(), dialogView, 1000f)
        dialogView.findViewById<View>(R.id.btn_info_close).setOnClickListener {
            dialog.dismiss()
        }
    }
    // ── Indicateur firmware (deplace depuis la barre du haut) ────────────────
    // Les pastilles affichent la generation detectee et, quand la voiture n'est pas reconnue,
    // permettent d'en forcer une. Elles vivent desormais dans Reglages > Infos.
    /**
     * Consommation de données — Réglages → Infos.
     *
     * Lecture hors thread UI : elle traverse un binder vers NetworkStatsService, ce n'est pas
     * un simple champ. Affiche « Indisponible » plutôt qu'un zéro quand la lecture échoue :
     * « rien consommé » et « impossible de lire » ne doivent pas se ressembler à l'écran.
     */
    private fun setupDataUsage(view: View) {
        val ctx = requireContext().applicationContext
        val fin = System.currentTimeMillis()
        // Une seule liste : ajouter une période ne demande qu'une ligne ici et une au layout.
        val periodes = listOf(
            R.id.tv_data_today to DataUsage.startOfDay(),
            R.id.tv_data_week  to DataUsage.startOfWeek(),
            R.id.tv_data_month to DataUsage.startOfMonth(),
            R.id.tv_data_30d   to fin - 30L * 24 * 3600 * 1000
        ).map { (id, debut) -> view.findViewById<TextView>(id) to debut }

        CoroutineScope(Dispatchers.IO).launch {
            val lues = periodes.map { (tv, debut) -> tv to DataUsage.ethernet(ctx, debut, fin) }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                lues.forEach { (tv, usage) -> usage?.let { tv.text = DataUsage.format(it.total) } }
            }
        }
    }

    private fun setupFirmwareChips(view: View) {
        val chip133 = view.findViewById<TextView>(R.id.chip_swi133)
        val chip132 = view.findViewById<TextView>(R.id.chip_swi132)
        val chip68  = view.findViewById<TextView>(R.id.chip_swi68)
        val chip69  = view.findViewById<TextView>(R.id.chip_swi69)
        val chip131 = view.findViewById<TextView>(R.id.chip_swi131)
        val chip165 = view.findViewById<TextView>(R.id.chip_swi165)
        val gen     = FirmwareInfo.getGeneration()
        val forced  = FirmwareInfo.isForced(requireContext())

        fun styleChipActive(tv: TextView) {
            tv.setBackgroundResource(R.drawable.bg_chip_active)
            tv.setTextColor(requireContext().getColor(R.color.dash_accent))
            tv.alpha = 1f
            tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        fun styleChipInactive(tv: TextView) {
            tv.setBackgroundResource(R.drawable.bg_chip_inactive)
            tv.setTextColor(requireContext().getColor(R.color.dash_text_lo))
            tv.alpha = 0.4f
            tv.paintFlags = tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }

        fun styleChipSelectable(tv: TextView) {
            // Firmware inconnu sans choix forcé : chip cliquable, surlignée en rouge
            tv.setBackgroundResource(R.drawable.bg_chip_inactive)
            tv.setTextColor(requireContext().getColor(R.color.dash_danger))
            tv.alpha = 0.75f
            tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        val isNaturalUnknown = gen == FirmwareInfo.Gen.UNKNOWN && !forced
        val allChips = listOf(chip133, chip132, chip68, chip69, chip131, chip165)

        when {
            isNaturalUnknown -> {
                // Les six chips en mode "à choisir" (rouge dim, aucune barrée)
                allChips.forEach { styleChipSelectable(it) }
            }
            gen == FirmwareInfo.Gen.SWI165 -> {
                styleChipActive(chip165)
                listOf(chip133, chip132, chip68, chip69, chip131).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI131 -> {
                styleChipActive(chip131)
                listOf(chip133, chip132, chip68, chip69, chip165).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI69 -> {
                styleChipActive(chip69)
                listOf(chip133, chip132, chip68, chip131, chip165).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI68 -> {
                styleChipActive(chip68)
                listOf(chip133, chip132, chip69, chip131, chip165).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI132 -> {
                styleChipActive(chip132)
                listOf(chip133, chip68, chip69, chip131, chip165).forEach { styleChipInactive(it) }
            }
            else -> { // SWI133 ou forcé SWI133
                styleChipActive(chip133)
                listOf(chip132, chip68, chip69, chip131, chip165).forEach { styleChipInactive(it) }
            }
        }

        // Chips cliquables si firmware inconnu (naturel ou forcé) pour changer de mode
        if (gen == FirmwareInfo.Gen.UNKNOWN || forced) {
            chip133.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI133)
                requireActivity().recreate()
            }
            chip132.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI132)
                requireActivity().recreate()
            }
            chip68.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI68)
                requireActivity().recreate()
            }
            chip69.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI69)
                requireActivity().recreate()
            }
            chip131.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI131)
                requireActivity().recreate()
            }
            chip165.setOnClickListener {
                FirmwareInfo.forceGeneration(requireContext(), FirmwareInfo.Gen.SWI165)
                requireActivity().recreate()
            }
        }
    }


    /**
     * Confirmation avant d'ouvrir l'API externe (issue #79).
     *
     * L'avertissement est construit en code plutôt que dans une chaîne : le premier paragraphe
     * doit être rouge ET gras, ce qu'un `setMessage` sur une chaîne plate ne permet pas. C'est
     * la seule information qui compte vraiment ici, elle ne doit pas se fondre dans le reste.
     *
     * [onResult] reçoit false sur Annuler comme sur une fermeture par l'extérieur : dans le
     * doute on ne suppose jamais l'accord.
     */
    private fun showExternalApiConfirm(onResult: (Boolean) -> Unit) {
        val danger = requireContext().getColor(R.color.dash_danger)
        val warn = getString(R.string.external_api_confirm_warn)
        val body = getString(R.string.external_api_confirm_msg)
        // getText et non getString : la ressource contient un <b> autour de « à vos risques et
        // périls ». Le gras vient donc du fichier de chaînes, ce qui reste juste dans les six
        // langues — le localiser en code aurait supposé de connaître la sous-chaîne traduite.
        val risk = getText(R.string.external_api_confirm_risk)

        val text = android.text.SpannableStringBuilder("$warn\n\n$body\n\n")
        val riskStart = text.length
        text.append(risk)

        // Rouge + gras sur l'ouverture, rouge seul sur la clôture : l'avertissement encadre
        // l'explication, et le gras reste réservé à la phrase la plus forte.
        text.setSpan(android.text.style.ForegroundColorSpan(danger),
            0, warn.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.StyleSpan(Typeface.BOLD),
            0, warn.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.ForegroundColorSpan(danger),
            riskStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Le refus couvre aussi la fenêtre fermée sans répondre (voir AppDialog.confirmer).
        AppDialog.confirmer(
            requireContext(),
            titre = getString(R.string.external_api_confirm_title),
            message = text,
            ton = AppDialog.Ton.DANGER,
            oui = getString(R.string.external_api_confirm_ok),
            non = getString(R.string.profile_cancel),
            onOui = { onResult(true) },
            onNon = { onResult(false) },
        )
    }

}
