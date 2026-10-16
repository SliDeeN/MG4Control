package com.mg4.control

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.MG4ControlService
import com.mg4.control.ui.AppDialog
import com.mg4.control.ui.RestoreProfilesDialog
import com.mg4.control.ui.SettingsFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateDialogManager
import com.mg4.control.update.UpdateNotifier
import com.mg4.control.util.ChargePopupSettings
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.ThemeHelper

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    /**
     * [THEME-AUTO] Re-resout le theme a chaque retour au premier plan.
     *
     * Indispensable sur old-SDK : le launcher change bien le night mode (UiModeManager le reflete)
     * mais le systeme ne met PAS a jour Configuration.uiMode, donc AUCUN changement de
     * configuration n'est delivre et l'activite n'est jamais recreee toute seule. Le retour au
     * premier plan est le bon moment : on ne peut pas changer le theme du launcher sans quitter
     * MG4Control.
     */
    override fun onResume() {
        super.onResume()
        val target = ThemeHelper.resolveNightMode(this)
        if (target != AppCompatDelegate.getDefaultNightMode()) {
            AppLogger.i("MG4_THEME", "onResume : theme change -> application du mode $target")
            // Recree les activites vivantes : pas de recreate() manuel a ajouter.
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init firmware EN PREMIER — avant toute inflation de fragment
        // Charge le mode forcé éventuel depuis les prefs
        FirmwareInfo.initWithContext(this)

        // [THEME-AUTO] Recrée l'activité quand le launcher MG change de thème en mode "auto"
        // (voie A9 : broadcast com.saicmotor.changeSkin reçu par le service)
        ThemeHelper.onThemeChanged = { recreate() }

        // Premier lancement : choix de la langue avant tout
        if (LocaleHelper.isFirstLaunch(this)) {
            showLanguagePicker()
            return
        }

        setContentView(R.layout.activity_main)

        startForegroundService(Intent(this, MG4ControlService::class.java))
        MG4Hardware.initAudio(applicationContext)  // connecte le helper audio vendor (A9 uniquement, no-op ailleurs)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        setupNavButtons()
        setupDiagnosticUnlock()
        checkUnknownFirmware()
        navigateToDefaultScreen(savedInstanceState)
        // Ouverture venue du popup véhicule : la release est déjà connue, on affiche le
        // dialogue tout de suite plutôt que de refaire l'aller-retour réseau.
        val depuisPopup = UpdateNotifier.readFrom(intent)
        if (depuisPopup != null) UpdateDialogManager.show(this, depuisPopup)
        else checkForUpdates()
        checkProfileRestore()
    }

    /**
     * L'application était déjà au premier plan quand le popup véhicule a été touché.
     *
     * L'intent est lancé en FLAG_ACTIVITY_SINGLE_TOP : dans ce cas Android ne recrée pas
     * l'activité et [onCreate] n'est jamais rappelé — sans cette surcharge, « Installer la MAJ »
     * ramènerait l'application au premier plan sans rien ouvrir du tout.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val info = UpdateNotifier.readFrom(intent) ?: return
        if (!isFinishing && !isDestroyed) UpdateDialogManager.show(this, info)
    }

    // ── Restauration des profils depuis la sauvegarde (après réinstallation) ──────
    // Ne se déclenche QUE si l'app n'a aucun profil local (vraie désinstallation /
    // effacement de données — pas une simple mise à jour qui conserve les données).
    private fun checkProfileRestore() {
        val pm = ProfileManager(this)
        if (pm.getAll().isNotEmpty()) return
        val settings = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (settings.getBoolean("restore_prompt_dismissed", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            val backup = pm.readBackup()
            if (backup == null || backup.profiles.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                RestoreProfilesDialog.show(
                    context = this@MainActivity,
                    profils = backup.profiles,
                    onIgnorer = {
                        settings.edit().putBoolean("restore_prompt_dismissed", true).apply()
                    },
                    onRestaurer = {
                        CoroutineScope(Dispatchers.IO).launch {
                            val n = pm.restoreFrom(backup)
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.profile_restore_done, n),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    // ── Déblocage du bouton Diagnostic (5 clics sur le logo) ────────────────

    private var logoClickCount = 0

    private fun setupDiagnosticUnlock() {
        findViewById<View>(R.id.topbar_logo)?.setOnClickListener {
            // Les mêmes cinq appuis font aussi apparaître l'option « Pop-up de charge »
            // (2026-10-16) — elle, pour de bon : le Diagnostic seul est oublié au redémarrage.
            if (diagnosticUnlocked && ChargePopupSettings.isUnlocked(this)) return@setOnClickListener
            logoClickCount++
            if (logoClickCount >= 5) {
                logoClickCount = 0
                diagnosticUnlocked = true
                // Révèle immédiatement le bouton si l'onglet Réglages est déjà affiché
                findViewById<View>(R.id.btn_diagnostic)?.visibility = View.VISIBLE
                val pourLaPremiereFois = ChargePopupSettings.unlock(this)
                findViewById<View>(R.id.card_charge_popup)?.visibility = View.VISIBLE
                Toast.makeText(this, getString(
                    if (pourLaPremiereFois) R.string.charge_popup_unlocked else R.string.diagnostic_unlocked),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Navigation vers l'écran par défaut au démarrage ─────────────────────

    private fun navigateToDefaultScreen(savedInstanceState: Bundle?) {
        // Ne naviguer que si c'est un vrai démarrage (pas une rotation / recreate)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        when (prefs.getString("default_screen", "dashboard")) {
            "profiles"  -> navController.navigate(R.id.profileFragment)
            "shortcuts" -> navController.navigate(R.id.shortcutsFragment)
            // "dashboard" → rien à faire, c'est déjà le startDestination
        }
    }

    // ── Vérification de mise à jour au démarrage ──────────────────────────────

    private fun checkForUpdates() {
        if (BuildConfig.OFFLINE) return  // build offline : aucune vérif réseau
        val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_check_update", true)) return

        UpdateChecker.check(
            context = this,
            onUpdateAvailable = { updateInfo ->
                if (!isFinishing && !isDestroyed) {
                    UpdateDialogManager.show(this, updateInfo)
                }
            }
            // onNoUpdate et onError ignorés au démarrage — silencieux si tout va bien
        )
    }

    // ── Dialog firmware non reconnu ───────────────────────────────────────────

    private fun checkUnknownFirmware() {
        // Ne montre le dialog que si le firmware est inconnu ET pas encore de choix forcé
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) return
        if (FirmwareInfo.isForced(this)) return

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_unknown_firmware, null)

        dialogView.findViewById<ImageView>(R.id.iv_fw_unknown_icon).imageTintList =
            ColorStateList.valueOf(getColor(R.color.dash_warn))
        // Le libellé porte un « ⚠ » en tête, hérité de l'ancienne fenêtre : ici c'est le
        // pictogramme qui le dit.
        dialogView.findViewById<TextView>(R.id.tv_fw_unknown_title).text =
            getString(R.string.fw_unknown_title).removePrefix("⚠").trim()
        // Affiche la chaîne firmware brute dans le badge (ex: "SWI69-12345")
        dialogView.findViewById<TextView>(R.id.tv_fw_detected_badge).text =
            FirmwareInfo.getDetectedString()
        // La consigne nomme l'écran par ses libellés : elle reste juste dans toutes les langues.
        dialogView.findViewById<TextView>(R.id.tv_fw_unknown_hint).text = getString(
            R.string.fw_unknown_hint_settings,
            getString(R.string.nav_settings), getString(R.string.settings_cat_info))

        // On n'en sort que par l'un des deux boutons : sans mode choisi, l'application ne sait
        // pas quelles commandes envoyer à la voiture.
        val dialog = AppDialog.afficher(this, dialogView, 700f, annulable = false)

        dialogView.findViewById<View>(R.id.btn_fw_close_app).setOnClickListener {
            finishAffinity()
        }

        // Droit là où le firmware se choisit : Réglages › Infos, sur ses pastilles.
        dialogView.findViewById<View>(R.id.btn_fw_continue).setOnClickListener {
            dialog.dismiss()
            navController.navigate(
                R.id.settingsFragment,
                Bundle().apply { putBoolean(SettingsFragment.ARG_SHOW_FIRMWARE, true) })
        }
    }

    // ── Boutons de navigation dans la top-bar ─────────────────────────────────

    private fun setupNavButtons() {
        val btnAutomation = findViewById<MaterialButton>(R.id.btn_nav_automation)
        val btnStats     = findViewById<MaterialButton>(R.id.btn_nav_stats)
        val btnShortcuts = findViewById<MaterialButton>(R.id.btn_nav_shortcuts)
        val btnProfiles  = findViewById<MaterialButton>(R.id.btn_nav_profiles)
        val btnSettings  = findViewById<MaterialButton>(R.id.btn_nav_settings)

        btnAutomation.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.automationFragment -> navController.popBackStack(R.id.dashboardFragment, false)
                else                    -> navController.navigate(R.id.automationFragment)
            }
        }

        btnStats.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.statsFragment -> navController.popBackStack(R.id.dashboardFragment, false)
                else               -> navController.navigate(R.id.statsFragment)
            }
        }

        btnShortcuts.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.shortcutsFragment -> navController.popBackStack(R.id.dashboardFragment, false)
                else                   -> navController.navigate(R.id.shortcutsFragment)
            }
        }

        btnProfiles.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.profileFragment -> navController.popBackStack(R.id.dashboardFragment, false)
                else                 -> navController.navigate(R.id.profileFragment)
            }
        }

        btnSettings.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.settingsFragment -> navController.popBackStack(R.id.dashboardFragment, false)
                else                  -> navController.navigate(R.id.settingsFragment)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val accent   = getColor(R.color.dash_accent_dim)
            val inactive = getColor(R.color.dash_btn)
            btnAutomation.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.automationFragment) accent else inactive
            )
            btnStats.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.statsFragment) accent else inactive
            )
            btnShortcuts.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.shortcutsFragment) accent else inactive
            )
            btnProfiles.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.profileFragment) accent else inactive
            )
            btnSettings.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.settingsFragment) accent else inactive
            )
        }
    }

    // ── Dialogue de choix de langue au premier lancement ─────────────────────

    private fun showLanguagePicker() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_language_picker, null)

        dialogView.findViewById<ImageView>(R.id.iv_language_icon).imageTintList =
            ColorStateList.valueOf(getColor(R.color.dash_accent))
        dialogView.findViewById<TextView>(R.id.tv_language_subtitle).apply {
            if (text.toString() == getString(R.string.settings_language_pick_title)) visibility = View.GONE
        }
        val dialog = AppDialog.afficher(this, dialogView, 820f, annulable = false)

        val buttons = mapOf(
            R.id.btn_pick_fr to "fr",
            R.id.btn_pick_en to "en",
            R.id.btn_pick_de to "de",
            R.id.btn_pick_es to "es",
            R.id.btn_pick_pt to "pt",
            R.id.btn_pick_it to "it",
            R.id.btn_pick_tr to "tr"
        )
        buttons.forEach { (viewId, code) ->
            dialogView.findViewById<View>(viewId).setOnClickListener {
                dialog.dismiss()
                LocaleHelper.setLanguage(this, code)
                recreate()
            }
        }
    }

    companion object {
        /**
         * Débloqué via 5 clics sur le logo en haut à gauche. En mémoire uniquement :
         * réinitialisé au redémarrage du process (le bouton Diagnostic reste masqué par défaut).
         */
        @Volatile var diagnosticUnlocked = false
    }
}
