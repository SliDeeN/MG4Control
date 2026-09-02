package com.mg4.control.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatDelegate
import com.mg4.control.MainActivity
import com.mg4.control.util.ThemeHelper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.mg4.control.BuildConfig
import com.mg4.control.api.ExternalApi
import com.mg4.control.R
import com.mg4.control.util.QrCode
import com.mg4.control.debug.AppLogger
import com.mg4.control.debug.DataUsageProbe
import com.mg4.control.util.DataUsage
import com.mg4.control.debug.CrashLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.update.ApkCleanup
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateNotifier
import com.mg4.control.update.UpdateDialogManager
import java.io.File
import com.mg4.control.util.FirmwareHelper
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.service.MG4ControlService
import com.mg4.control.util.GarageMode
import com.mg4.control.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val githubUrl = "https://github.com/SliDeeN/MG4Control"
    private val gitlabUrl = "https://gitlab.com/SliDeeN/mg4control"

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
            "it" to view.findViewById(R.id.btn_lang_it)
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
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.vehicle_power_dialog_title)
                                .setMessage(R.string.vehicle_power_dialog_msg)
                                .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                                .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val ok = MG4Hardware.vehiclePowerOff()
                                        AppLogger.i("MG4_SETTINGS", "Vehicle power off → $ok")
                                    }
                                }
                                .show()
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

        // [TEST TEMPORAIRE] Appui LONG sur Diagnostic → test d'écriture climatisation.
        // Délibérément pas sur le clic simple : le Diagnostic s'ouvre souvent et ce test
        // modifie brièvement la clim de la voiture (puis restaure l'état d'origine).
        btnDiagnostic.setOnLongClickListener {
            Toast.makeText(
                requireContext(),
                "Test écriture climatisation lancé — voir les logs MG4_CLIM (~4 s)",
                Toast.LENGTH_LONG
            ).show()
            CoroutineScope(Dispatchers.IO).launch { MG4Hardware.runClimateWriteTest() }
            true
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
        bindCategoryRail(view, accentDim, inactiveColor, accentColor, textActive, textInactive)
    }

    /**
     * Rail de gauche : une catégorie visible à la fois — même motif que l'éditeur de profil.
     *
     * Le bouton Diagnostic reste dans l'arbre même sur une page masquée : [MainActivity] peut donc
     * continuer à le révéler en direct après les 5 clics sur le logo, quel que soit l'onglet ouvert.
     */
    private fun bindCategoryRail(
        view: View, accentDim: Int, inactive: Int, accent: Int, textOn: Int, textOff: Int
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
        select(usable.first().second)
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
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(ecoDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(eco)
        btn.setTextColor(eco)
        btn.isEnabled = false

        // Revient à l'état normal après 3 secondes
        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
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
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(dangerDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(danger)
        btn.setTextColor(danger)
        btn.isEnabled = false

        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
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
        // Sonde température : tente de lire temp extérieure + habitacle et logge le brut.
        MG4Hardware.runTemperatureDiag()
        // Sonde vitesse : logge la vitesse brute (validation de l'unité par firmware).
        MG4Hardware.runSpeedDiag()
        // Sonde climatisation : lecture seule, repère ce qui répond avant tout pilotage.
        MG4Hardware.runClimateDiag()
        // Sonde média : qui joue, et surtout quelles sessions média existent — c'est ce qui
        // décide si une touche « piste suivante » peut aboutir quelque part.
        MG4Hardware.runMediaDiag()
        // Sonde consommation de données : lecture seule, aucune API véhicule impliquée.
        DataUsageProbe.run(ctx)
        // Sonde somnolence / sensibilité / ESC : lecture seule (elle ne bascule RIEN — un
        // rapport de diagnostic ne doit pas toucher à un organe de sécurité active).
        MG4Hardware.runSafetyDiag()
        // Chasse à la consigne de température (candidats × zones + voie OEM).
        MG4Hardware.runClimateSetpointHunt()
        // Sonde thème : quelle source de day/night répond sur ce firmware. Contexte d'ACTIVITÉ —
        // c'est sa configuration qui décide des ressources affichées.
        ThemeHelper.runDiagnostic(ctx)

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        // ── Layout : crash banner (optionnel) + rapport matériel ──────────────
        var btnClearCrash: Button? = null
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Section crash log (si un crash a été enregistré)
        val crashLog = CrashLogger.read(ctx)
        if (crashLog != null) {
            val tvCrash = TextView(ctx).apply {
                text = crashLog
                typeface = Typeface.MONOSPACE
                textSize = 9f
                setTextColor(ctx.getColor(R.color.dash_danger))
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(ctx.getColor(R.color.dash_danger_dim))
            }
            container.addView(tvCrash)

            // Séparateur
            val divider = android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                ).also { it.topMargin = 0; it.bottomMargin = 0 }
                setBackgroundColor(ctx.getColor(R.color.dash_border))
            }
            container.addView(divider)

            // "Effacer crash" déplacé dans le contenu (le slot neutre sert au Télécharger)
            btnClearCrash = Button(ctx).apply {
                text = getString(R.string.diag_clear_crash)
            }
            container.addView(btnClearCrash)
        }

        // Section rapport matériel
        val tvReport = TextView(ctx).apply {
            text = getString(R.string.diag_loading)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(ctx.getColor(R.color.text_secondary))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(tvReport)

        // Section AppLogger en temps réel (30 dernières lignes)
        val tvLogs = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(ctx.getColor(R.color.dash_text_lo))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
            val entries = AppLogger.entries
            text = if (entries.isEmpty()) "─── AppLogger vide ───"
                   else "─── AppLogger (${entries.size} entrées) ───\n" +
                        entries.takeLast(30).joinToString("\n") { e ->
                            "${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}"
                        }
        }
        container.addView(tvLogs)

        val scrollView = ScrollView(ctx).apply {
            addView(container)
        }

        val title = if (crashLog != null)
            "⚠ ${getString(R.string.diag_title)} — CRASH DÉTECTÉ"
        else
            getString(R.string.diag_title)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.diag_copy), null)
            .setNeutralButton(getString(R.string.diag_download), null)
            .setNegativeButton(getString(R.string.nav_close), null)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(ctx.getColor(R.color.dash_card)))

        // Rapport court (30 dernières lignes) pour le presse-papier ; rapport complet pour le fichier.
        fun buildReport(fullLog: Boolean) = buildString {
            if (crashLog != null) { appendLine(crashLog); appendLine() }
            appendLine(tvReport.text)
            appendLine()
            if (fullLog) {
                val entries = AppLogger.entries
                appendLine("─── AppLogger (${entries.size} entrées) ───")
                entries.forEach { e -> appendLine("${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}") }
            } else appendLine(tvLogs.text)
        }

        dialog.setOnShowListener {
            // "Copier" — copie tout sans fermer le dialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MG4Control Diagnostic", buildReport(false)))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = getString(R.string.diag_copied)
            }
            // "Télécharger" — écrit le rapport complet dans le dossier Download de la voiture
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                downloadDiagnostic(ctx, buildReport(true))
            }
            // "Effacer crash" (dans le contenu) — supprime le fichier et ferme le dialog
            btnClearCrash?.setOnClickListener {
                CrashLogger.clear(ctx)
                dialog.dismiss()
            }
        }

        dialog.show()

        // Génération du rapport matériel sur le thread IO
        CoroutineScope(Dispatchers.IO).launch {
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
        FirmwareHelper.getMpuVersion(requireContext()) { version ->
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

        // Lien GitHub cliquable
        dialogView.findViewById<TextView>(R.id.tv_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }

        // Lien GitLab cliquable
        dialogView.findViewById<TextView>(R.id.tv_gitlab_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gitlabUrl)))
        }

        // Création du dialog sans chrome Android (fond transparent = layout seul visible)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bouton Fermer intégré dans le layout
        dialogView.findViewById<MaterialButton>(R.id.btn_info_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
        ).map { (id, debut) -> view.findViewById<android.widget.TextView>(id) to debut }

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
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0, warn.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.ForegroundColorSpan(danger),
            riskStart, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        var answered = false
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.external_api_confirm_title)
            .setMessage(text)
            .setNegativeButton(R.string.profile_cancel) { _, _ -> answered = true; onResult(false) }
            .setPositiveButton(R.string.external_api_confirm_ok) { _, _ -> answered = true; onResult(true) }
            .setOnDismissListener { if (!answered) onResult(false) }
            .show()
    }

}
