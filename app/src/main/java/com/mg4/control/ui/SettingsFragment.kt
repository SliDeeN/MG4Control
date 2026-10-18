package com.mg4.control.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatDelegate
import com.mg4.control.MainActivity
import com.mg4.control.util.ThemeHelper
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
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
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.PopupDelay
import com.mg4.control.util.ChargePopupSettings
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.service.ChargeMonitor
import com.mg4.control.service.ClusterModuleOverlay
import com.mg4.control.util.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val githubUrl = "https://github.com/SliDeeN/MG4Control"
    private val gitlabUrl = "https://gitlab.com/SliDeeN/mg4control"

    /** Index, dans le rail, de l'onglet ouvert — sauvegardé pour survivre à un recreate(). */
    private var selectedCategory = 0

    /** Aperçu du combiné (carte Écran conducteur) : animé seulement quand l'écran est au premier plan. */
    private var apercuCombine: ClusterSettingsPreview? = null
    /** Panneau « Avancé » de la carte Écran conducteur : replié à l'ouverture des Réglages. */
    private var avanceOuvert = false

    companion object {
        private const val STATE_CATEGORY = "settings_category"

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
            "tr" to view.findViewById(R.id.btn_lang_tr)
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

        // ── Pop-up de charge (2026-10-16) : activé par défaut. Le suivi démarre ou s'arrête aussitôt.
        view.findViewById<Switch>(R.id.switch_charge_popup).apply {
            isChecked = ChargePopupSettings.isEnabled(requireContext())
            setOnCheckedChangeListener { _, checked ->
                ChargePopupSettings.setEnabled(requireContext(), checked)
                AppLogger.i(ChargeMonitor.TAG, "Pop-up de charge ${if (checked) "activé" else "désactivé"}")
                ChargeMonitor.refresh(requireContext())
            }
        }

        // ── Module sur le combiné : interrupteur et un module par onglet ──────
        // Désactivé par défaut ; activer déplie le choix. Le service relit l'interrupteur aussitôt
        // (ClusterModuleOverlay.refresh), et les modules à chaque relevé : rien à redémarrer.
        // Réservé aux firmwares où le combiné montre l'écran central : SWI133, SWI68, SWI165.
        view.findViewById<View>(R.id.card_cluster_module).visibility =
            if (ClusterModuleSettings.isSupported()) View.VISIBLE else View.GONE
        val switchCluster = view.findViewById<Switch>(R.id.switch_cluster_module)
        val rowClusterChoice = view.findViewById<View>(R.id.row_cluster_choice)
        val rowClusterRefresh = view.findViewById<View>(R.id.row_cluster_refresh)
        // Refonte A (2026-10-17) : aperçu animé à gauche, « Avancé » replié (actualisation, animation).
        val apercu = ClusterSettingsPreview(view.findViewById(R.id.frame_cluster_preview),
            view.findViewById(R.id.tv_cluster_preview_none))
        apercuCombine = apercu
        val ongletsApercu: List<Pair<ClusterTab, MaterialButton>> = listOf(
            ClusterTab.NAVIGATION to view.findViewById(R.id.btn_cluster_preview_nav),
            ClusterTab.MEDIA to view.findViewById(R.id.btn_cluster_preview_media),
        )
        val tvAvance = view.findViewById<TextView>(R.id.tv_cluster_advanced)
        val tvChevron = view.findViewById<TextView>(R.id.tv_cluster_advanced_chevron)
        // Le nombre est écrit dans la langue de l'application (0,5 s / 0.5 s), jamais Locale.ROOT.
        val langue = requireContext().resources.configuration.locales[0]
        fun texteActualisation(ms: Int) = String.format(langue, "%.1f s", ms / 1000f)
        fun resumeAvance() {
            val ctx = requireContext()
            tvAvance.text = getString(R.string.settings_cluster_advanced,
                texteActualisation(ClusterModuleSettings.refreshMs(ctx)), texteActualisation(ClusterModuleSettings.animationMs(ctx)))
            tvChevron.text = if (avanceOuvert) "▴" else "▾"
        }
        // Pour chaque onglet : « Aucun » (null), Consommation, Puissance.
        val clusterBtns: List<Triple<ClusterTab, ClusterModule?, MaterialButton>> = listOf(
            Triple(ClusterTab.NAVIGATION, null, view.findViewById(R.id.btn_cluster_nav_none)),
            Triple(ClusterTab.NAVIGATION, ClusterModule.CONSUMPTION, view.findViewById(R.id.btn_cluster_nav_consumption)),
            Triple(ClusterTab.NAVIGATION, ClusterModule.POWER, view.findViewById(R.id.btn_cluster_nav_power)),
            Triple(ClusterTab.MEDIA, null, view.findViewById(R.id.btn_cluster_media_none)),
            Triple(ClusterTab.MEDIA, ClusterModule.CONSUMPTION, view.findViewById(R.id.btn_cluster_media_consumption)),
            Triple(ClusterTab.MEDIA, ClusterModule.POWER, view.findViewById(R.id.btn_cluster_media_power)),
        )
        // Unité de la consommation : Wh/km ou kWh/100 km (2026-10-15).
        val unitBtns: List<Pair<ConsumptionUnit, MaterialButton>> = listOf(
            ConsumptionUnit.WH_PER_KM to view.findViewById(R.id.btn_cluster_unit_wh),
            ConsumptionUnit.KWH_PER_100KM to view.findViewById(R.id.btn_cluster_unit_kwh),
        )
        // Style des modules (issue #153) : dynamique ou discret.
        val styleBtns: List<Pair<ClusterGaugeStyle, MaterialButton>> = listOf(
            ClusterGaugeStyle.DYNAMIC to view.findViewById(R.id.btn_cluster_style_dynamic),
            ClusterGaugeStyle.DISCREET to view.findViewById(R.id.btn_cluster_style_discreet),
        )
        // Unité de la moyenne du trajet, à part de celle de la jauge (2026-10-17).
        val avgBtns: List<Pair<AverageUnit, MaterialButton>> = listOf(
            AverageUnit.WH_PER_KM to view.findViewById(R.id.btn_cluster_avg_wh),
            AverageUnit.KWH_PER_100KM to view.findViewById(R.id.btn_cluster_avg_kwh),
            AverageUnit.KM_PER_KWH to view.findViewById(R.id.btn_cluster_avg_km),
        )
        // Pastille en haut à gauche : mode de conduite, profil actif ou rien (issue #152).
        val pillBtns: List<Pair<ClusterPill, MaterialButton>> = listOf(
            ClusterPill.DRIVE_MODE to view.findViewById(R.id.btn_cluster_pill_mode),
            ClusterPill.PROFILE to view.findViewById(R.id.btn_cluster_pill_profile),
            ClusterPill.NONE to view.findViewById(R.id.btn_cluster_pill_none),
        )
        fun updateClusterCard() {
            val ctx = requireContext()
            // Le combiné suit tout de suite, sans attendre son relevé suivant.
            ClusterModuleOverlay.relire()
            val actif = ClusterModuleSettings.isEnabled(ctx)
            rowClusterChoice.visibility = if (actif) View.VISIBLE else View.GONE
            rowClusterRefresh.visibility = if (actif && avanceOuvert) View.VISIBLE else View.GONE
            resumeAvance()
            if (actif && isResumed) apercu.demarrer() else if (!actif) apercu.arreter()
            apercu.rafraichir()
            fun peindre(btn: MaterialButton, active: Boolean) {
                btn.backgroundTintList = ColorStateList.valueOf(if (active) accentDim else inactiveColor)
                btn.setTextColor(if (active) textActive else textInactive)
                btn.strokeColor = ColorStateList.valueOf(
                    if (active) accentColor else ctx.getColor(R.color.dash_border)
                )
            }
            clusterBtns.forEach { (onglet, module, btn) ->
                peindre(btn, module == ClusterModuleSettings.moduleFor(ctx, onglet))
            }
            val unite = ClusterModuleSettings.consumptionUnit(ctx)
            unitBtns.forEach { (u, btn) -> peindre(btn, u == unite) }
            val pastille = ClusterModuleSettings.pill(ctx)
            pillBtns.forEach { (p, btn) -> peindre(btn, p == pastille) }
            val style = ClusterModuleSettings.style(ctx)
            styleBtns.forEach { (st, btn) -> peindre(btn, st == style) }
            val moyenne = ClusterModuleSettings.averageUnit(ctx)
            avgBtns.forEach { (u, btn) -> peindre(btn, u == moyenne) }
            ongletsApercu.forEach { (o, btn) -> peindre(btn, o == apercu.onglet) }
        }
        ongletsApercu.forEach { (o, btn) ->
            btn.setOnClickListener {
                apercu.onglet = o
                updateClusterCard()
            }
        }
        view.findViewById<View>(R.id.row_cluster_advanced_toggle).setOnClickListener {
            avanceOuvert = !avanceOuvert
            updateClusterCard()
        }
        switchCluster.isChecked = ClusterModuleSettings.isEnabled(requireContext())
        updateClusterCard()
        switchCluster.setOnCheckedChangeListener { _, checked ->
            ClusterModuleSettings.setEnabled(requireContext(), checked)
            AppLogger.i(ClusterModuleOverlay.TAG, "Module sur le combiné ${if (checked) "activé" else "désactivé"}")
            ClusterModuleOverlay.refresh(requireContext())
            updateClusterCard()
        }
        clusterBtns.forEach { (onglet, module, btn) ->
            btn.setOnClickListener {
                if (module == ClusterModuleSettings.moduleFor(requireContext(), onglet)) return@setOnClickListener
                ClusterModuleSettings.setModuleFor(requireContext(), onglet, module)
                AppLogger.i(ClusterModuleOverlay.TAG, "Module du combiné, onglet ${onglet.name} → ${module?.key ?: "aucun"}")
                // L'aperçu montre l'onglet qu'on vient de régler.
                apercu.onglet = onglet
                updateClusterCard()
            }
        }
        unitBtns.forEach { (u, btn) ->
            btn.setOnClickListener {
                if (u == ClusterModuleSettings.consumptionUnit(requireContext())) return@setOnClickListener
                ClusterModuleSettings.setConsumptionUnit(requireContext(), u)
                AppLogger.i(ClusterModuleOverlay.TAG, "Unité de consommation du combiné → ${u.label}")
                updateClusterCard()
            }
        }
        styleBtns.forEach { (st, btn) ->
            btn.setOnClickListener {
                if (st == ClusterModuleSettings.style(requireContext())) return@setOnClickListener
                ClusterModuleSettings.setStyle(requireContext(), st)
                AppLogger.i(ClusterModuleOverlay.TAG, "Style des modules du combiné → ${st.key}")
                updateClusterCard()
            }
        }
        avgBtns.forEach { (u, btn) ->
            btn.setOnClickListener {
                if (u == ClusterModuleSettings.averageUnit(requireContext())) return@setOnClickListener
                ClusterModuleSettings.setAverageUnit(requireContext(), u)
                AppLogger.i(ClusterModuleOverlay.TAG, "Unité de la moyenne du trajet → ${u.label}")
                updateClusterCard()
            }
        }
        pillBtns.forEach { (p, btn) ->
            btn.setOnClickListener {
                if (p == ClusterModuleSettings.pill(requireContext())) return@setOnClickListener
                ClusterModuleSettings.setPill(requireContext(), p)
                AppLogger.i(ClusterModuleOverlay.TAG, "Pastille du combiné → ${p.key}")
                updateClusterCard()
            }
        }
        // Actualisation de la jauge, 0,1 à 1 s : relue à chaque relevé, rien à redémarrer.
        val tvClusterRefresh = view.findViewById<TextView>(R.id.tv_cluster_refresh)
        view.findViewById<Slider>(R.id.slider_cluster_refresh).apply {
            valueFrom = ClusterModuleSettings.refreshToSlider(ClusterModuleSettings.MIN_REFRESH_MS)
            valueTo   = ClusterModuleSettings.refreshToSlider(ClusterModuleSettings.MAX_REFRESH_MS)
            val actuel = ClusterModuleSettings.refreshMs(requireContext())
            value = ClusterModuleSettings.refreshToSlider(actuel)
            tvClusterRefresh.text = texteActualisation(actuel)
            addOnChangeListener { _, v, _ ->
                val ms = ClusterModuleSettings.refreshFromSlider(v)
                tvClusterRefresh.text = texteActualisation(ms)
                ClusterModuleSettings.setRefreshMs(requireContext(), ms)
                resumeAvance()
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    AppLogger.i(ClusterModuleOverlay.TAG,
                        "Actualisation de la jauge → ${ClusterModuleSettings.refreshFromSlider(slider.value)} ms")
                }
            })
        }
        // Durée de l'animation, 0,2 à 2 s, à part de l'actualisation (issue #152) : relue à chaque
        // relevé, comme elle.
        val tvClusterAnimation = view.findViewById<TextView>(R.id.tv_cluster_animation)
        view.findViewById<Slider>(R.id.slider_cluster_animation).apply {
            valueFrom = ClusterModuleSettings.animationToSlider(ClusterModuleSettings.MIN_ANIMATION_MS)
            valueTo   = ClusterModuleSettings.animationToSlider(ClusterModuleSettings.MAX_ANIMATION_MS)
            val actuel = ClusterModuleSettings.animationMs(requireContext())
            value = ClusterModuleSettings.animationToSlider(actuel)
            tvClusterAnimation.text = texteActualisation(actuel)
            addOnChangeListener { _, v, _ ->
                val ms = ClusterModuleSettings.animationFromSlider(v)
                tvClusterAnimation.text = texteActualisation(ms)
                ClusterModuleSettings.setAnimationMs(requireContext(), ms)
                resumeAvance()
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    AppLogger.i(ClusterModuleOverlay.TAG,
                        "Animation de la jauge → ${ClusterModuleSettings.animationFromSlider(slider.value)} ms")
                }
            })
        }

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

        // Onglet du combiné : outil de relevé, débloqué avec le Diagnostic (même principe) — remis
        // le 2026-10-18 pour les relevés d'un testeur SWI165.
        val btnClusterTab = view.findViewById<MaterialButton>(R.id.btn_cluster_tab)
        btnClusterTab.visibility = if (MainActivity.diagnosticUnlocked) View.VISIBLE else View.GONE
        btnClusterTab.setOnClickListener { ClusterTabDialog.afficher(requireContext()) }

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
        view.findViewById<MaterialButton>(R.id.btn_infos).setOnClickListener {
            showInfosDialog()
        }

        // ── Bouton Fermer ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_close_settings).setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }

        // En dernier : le rail masque un onglet dont la page n'a plus rien de visible, il doit
        // donc être câblé APRÈS toutes les décisions de visibilité ci-dessus (build offline,
        // firmware sans extinction véhicule…), sinon le décompte serait faux.
        setupFirmwareChips(view)
        setupDataUsage(view)
        bindCategoryRail(view, savedInstanceState, accentDim, inactiveColor, accentColor, textActive, textInactive)
    }

    override fun onResume() {
        super.onResume()
        if (ClusterModuleSettings.isEnabled(requireContext())) apercuCombine?.demarrer()
    }

    override fun onPause() {
        apercuCombine?.arreter()
        super.onPause()
    }

    override fun onDestroyView() {
        apercuCombine?.arreter()
        apercuCombine = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CATEGORY, selectedCategory)
    }

    /**
     * Rail de gauche : une catégorie visible à la fois — même motif que l'éditeur de profil.
     *
     * Le bouton Diagnostic reste dans l'arbre même sur une page masquée : [MainActivity] peut donc
     * continuer à le révéler en direct après les 5 clics sur le logo, quel que soit l'onglet ouvert.
     *
     * L'onglet ouvert survit à un `recreate()` : langue, thème et taille du texte recréent
     * l'activité, et retomber à chaque fois sur le premier onglet obligeait à y retourner.
     */
    private fun bindCategoryRail(
        view: View, savedInstanceState: Bundle?,
        accentDim: Int, inactive: Int, accent: Int, textOn: Int, textOff: Int
    ) {
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
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accentDim else inactive)
                btn.setTextColor(if (on) textOn else textOff)
                btn.strokeColor = ColorStateList.valueOf(
                    if (on) accent else requireContext().getColor(R.color.dash_border))
            }
            scroll?.scrollTo(0, 0)   // changer d'onglet en gardant le scroll précédent désoriente
        }
        usable.forEach { (btn, page) -> btn.setOnClickListener { select(page) } }
        // Un onglet mémorisé peut ne plus être disponible (page vidée entre-temps) : repli sur le premier.
        val restored = savedInstanceState?.getInt(STATE_CATEGORY, -1)?.let { tabs.getOrNull(it) }
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

        // Rapport court (30 dernières lignes) pour le presse-papier ; rapport complet pour le fichier.
        fun buildReport(fullLog: Boolean) = buildString {
            if (crashLog != null) { appendLine(crashLog); appendLine() }
            appendLine(tvReport.text)
            appendLine()
            if (fullLog) {
                val toutes = AppLogger.entries
                appendLine("─── AppLogger (${toutes.size} entrées) ───")
                toutes.forEach { e -> appendLine("${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}") }
            } else appendLine(tvLogs.text)
        }

        // « Copier » — copie tout sans fermer la fenêtre
        val btnCopier = vue.findViewById<TextView>(R.id.diag_copy)
        btnCopier.setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MG4Control Diagnostic", buildReport(false)))
            btnCopier.text = getString(R.string.diag_copied)
        }
        // « Télécharger » — écrit le rapport complet dans le dossier Download de la voiture
        vue.findViewById<View>(R.id.diag_download).setOnClickListener {
            downloadDiagnostic(ctx, buildReport(true))
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
            // Sonde lumière/position : un relevé instantané, à prendre en plein jour, au crépuscule
            // et dans un parking couvert. Ceux pris automatiquement au READY sont déjà gardés.
            com.mg4.control.hardware.LightProbe.run("diagnostic")
            val report = MG4Hardware.buildDiagnosticReport(appVersion)
            withContext(Dispatchers.Main) {
                if (isAdded) tvReport.text = report
            }
        }
    }

    /** Écrit le rapport de diagnostic dans le dossier Download de la voiture (fichier .txt horodaté). */
    private fun downloadDiagnostic(ctx: Context, report: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "MG4Control_diag_$ts.txt")
            file.writeText(report)
            Toast.makeText(ctx, getString(R.string.diag_downloaded, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.diag_download_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
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
