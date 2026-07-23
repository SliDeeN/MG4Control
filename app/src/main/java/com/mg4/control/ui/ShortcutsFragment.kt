package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.hardware.model.RegenLevel
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.FirmwareInfo

class ShortcutsFragment : Fragment() {

    private val PREFS = "mg4_shortcuts"

    private lateinit var prefs: SharedPreferences
    private var accentColor = 0
    private var defColor    = 0

    private var switchEnabled:   Switch? = null
    private var shortcutsContent: View?  = null

    /** Éléments disponibles dans les Spinners — calculés une fois selon le firmware. */
    private data class ActionItem(val label: String, val action: ShortcutAction)

    /** Liste de base (sans label custom) — partagée pour tous les spinners. */
    private var baseActionItems: List<ActionItem> = emptyList()

    /** Clés identifiant chaque ligne slot × type de pression. */
    private val slotPressList = listOf(
        "btn1_single", "btn1_long",
        "btn2_single", "btn2_long"
    )

    // ── Par-spinner : label list mutable + adapter + vue ─────────────────
    private val spinnerLabelLists = mutableMapOf<String, MutableList<String>>()
    private val spinnerAdapters   = mutableMapOf<String, ArrayAdapter<String>>()
    private val spinnerViews      = mutableMapOf<String, Spinner>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_shortcuts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs       = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accentColor = requireContext().getColor(R.color.accent_eco)
        defColor    = requireContext().getColor(R.color.bg_button)

        switchEnabled    = view.findViewById(R.id.switch_shortcuts_enabled)
        shortcutsContent = view.findViewById(R.id.shortcuts_content)

        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132

        // ── Construction des items de base selon firmware ─────────────────
        baseActionItems = buildList {
            add(ActionItem(getString(R.string.shortcuts_action_none),           ShortcutAction.NONE))
            add(ActionItem(getString(R.string.shortcuts_action_one_pedal),      ShortcutAction.ONE_PEDAL))
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_aeb),        ShortcutAction.AEB_CYCLE))
            }
            // SWI68/69/131/165 : une seule alerte sonore VSM
            if (isVsmBased && !isSWI132) {
                add(ActionItem(getString(R.string.shortcuts_action_sound),      ShortcutAction.SOUND_WARNING))
            }
            // SWI133 + SWI132 : deux alertes indépendantes (survitesse + ton limite)
            if ((!isVsmBased || isSWI132) && isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_overspeed),  ShortcutAction.OVERSPEED_ALARM))
                add(ActionItem(getString(R.string.shortcuts_action_speed_limit),ShortcutAction.SPEED_LIMIT_TONE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_adas),       ShortcutAction.ADAS_CYCLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_energy_saving), ShortcutAction.ENERGY_SAVING_TOGGLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_tsr), ShortcutAction.TSR_TOGGLE))
            }
            add(ActionItem(getString(R.string.shortcuts_action_apply_profile),   ShortcutAction.APPLY_PROFILE))
            add(ActionItem(getString(R.string.shortcuts_action_profile_picker), ShortcutAction.PROFILE_PICKER))
            add(ActionItem(getString(R.string.shortcuts_action_open_app),       ShortcutAction.OPEN_APP))
            add(ActionItem(getString(R.string.shortcuts_action_open_custom_app),ShortcutAction.OPEN_CUSTOM_APP))
            if (MG4Hardware.hasVehiclePowerOff()) {
                add(ActionItem(getString(R.string.shortcuts_action_vehicle_power_off), ShortcutAction.VEHICLE_POWER_OFF))
            }
        }

        // ── Affichage des sections de config selon firmware ───────────────
        // Tous les firmwares connus utilisent la config 5 modes (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA).
        view.findViewById<View>(R.id.config_adas_section)?.visibility = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi133)?.visibility  = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi68)?.visibility   = View.GONE

        // ── Bouton Fermer ─────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_shortcuts_close)?.setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }

        setupSpinners(view)
        setupConfigListeners(view)
        restoreState()
    }

    // ── Spinners (un adapter par spinner) ────────────────────────────────

    private fun setupSpinners(view: View) {
        for (slotKey in slotPressList) {
            val spinnerId = resources.getIdentifier("spinner_$slotKey", "id", requireContext().packageName)
            val spinner   = view.findViewById<Spinner>(spinnerId) ?: continue

            // Construire la liste de labels pour ce slot (OPEN_CUSTOM_APP peut avoir un label custom)
            val labels = buildLabelsFor(slotKey)
            spinnerLabelLists[slotKey] = labels
            spinnerViews[slotKey]      = spinner

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAdapters[slotKey] = adapter
            spinner.adapter = adapter

            // Sélection initiale
            val savedAction = ShortcutAction.fromId(prefs.getInt("shortcut_$slotKey", 0))
            val position    = baseActionItems.indexOfFirst { it.action == savedAction }.coerceAtLeast(0)
            spinner.setSelection(position)

            // Listener positionné APRÈS pour ignorer le callback auto de setSelection.
            // Le flag `initialized` absorbe le premier onItemSelected automatique (sélection initiale).
            spinner.post {
                var initialized = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        val action = baseActionItems[pos].action
                        saveInt("shortcut_$slotKey", action.id)
                        if (initialized) {
                            when (action) {
                                ShortcutAction.OPEN_CUSTOM_APP -> showAppPickerDialog(slotKey)
                                ShortcutAction.APPLY_PROFILE   -> showProfilePickerDialog(slotKey)
                                else -> {}
                            }
                        }
                        initialized = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
        }
    }

    /**
     * Construit la liste de labels pour un slot.
     * - OPEN_CUSTOM_APP : affiche "Ouvrir [AppName]" si une app est sauvegardée.
     * - APPLY_PROFILE   : affiche "▶ [NomProfil]" si un profil est sauvegardé.
     */
    private fun buildLabelsFor(slotKey: String): MutableList<String> {
        val savedPkg = prefs.getString("shortcut_${slotKey}_custom_app", null)
        val customAppLabel = if (savedPkg != null) {
            resolveAppLabel(savedPkg) ?: getString(R.string.shortcuts_action_open_custom_app)
        } else {
            getString(R.string.shortcuts_action_open_custom_app)
        }

        val savedProfileId = prefs.getString("shortcut_${slotKey}_profile_id", null)
        val profileLabel = if (savedProfileId != null) {
            val profile = ProfileManager(requireContext()).getById(savedProfileId)
            if (profile != null) getString(R.string.shortcuts_profile_prefix) + " " + profile.name
            else getString(R.string.shortcuts_action_apply_profile)
        } else {
            getString(R.string.shortcuts_action_apply_profile)
        }

        return baseActionItems.map { item ->
            when (item.action) {
                ShortcutAction.OPEN_CUSTOM_APP -> customAppLabel
                ShortcutAction.APPLY_PROFILE   -> profileLabel
                else                           -> item.label
            }
        }.toMutableList()
    }

    /** Retourne le label de l'application (packageName) ou null si introuvable. */
    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(info).toString()
            getString(R.string.shortcuts_open_custom_prefix) + " " + appName
        } catch (_: Exception) { null }
    }

    // ── Dialog de sélection d'application ────────────────────────────────

    private fun showAppPickerDialog(slotKey: String) {
        val pm = requireContext().packageManager

        // Récupérer toutes les apps launchables, triées par label
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launchIntent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val labels   = resolveList.map { it.loadLabel(pm).toString() }.toTypedArray()
        val packages = resolveList.map { it.activityInfo.packageName }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_app_title)
            .setItems(labels) { _, which ->
                val pkg      = packages[which]
                val appName  = labels[which]
                val newLabel = getString(R.string.shortcuts_open_custom_prefix) + " " + appName

                prefs.edit().putString("shortcut_${slotKey}_custom_app", pkg).apply()
                updateCustomAppLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Si aucune app n'était sauvegardée → revenir à NONE
                if (prefs.getString("shortcut_${slotKey}_custom_app", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    // ── Dialog de sélection de profil ────────────────────────────────────────

    private fun showProfilePickerDialog(slotKey: String) {
        val profiles = ProfileManager(requireContext()).getAll()

        if (profiles.isEmpty()) {
            // Aucun profil créé → revenir à NONE
            val spinner = spinnerViews[slotKey] ?: return
            spinner.setSelection(0)
            saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.shortcuts_no_profiles)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = profiles.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_profile_title)
            .setItems(labels) { _, which ->
                val profile  = profiles[which]
                val newLabel = getString(R.string.shortcuts_profile_prefix) + " " + profile.name
                prefs.edit().putString("shortcut_${slotKey}_profile_id", profile.id).apply()
                updateProfileLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Annulation sans profil préalablement sauvegardé → revenir à NONE
                if (prefs.getString("shortcut_${slotKey}_profile_id", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    /** Met à jour le label APPLY_PROFILE dans l'adapter du spinner concerné. */
    private fun updateProfileLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.APPLY_PROFILE }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        spinner.setSelection(idx)
    }

    /** Met à jour le label OPEN_CUSTOM_APP dans l'adapter du spinner concerné. */
    private fun updateCustomAppLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.OPEN_CUSTOM_APP }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        // S'assurer que le spinner affiche le bon item sélectionné
        spinner.setSelection(idx)
    }

    // ── Config buttons (1 Pédale / AEB / ADAS) ───────────────────────────

    private fun setupConfigListeners(view: View) {
        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            if (switchEnabled?.isPressed == true) {
                saveBoolean("shortcut_enabled", checked)
                applyEnabledUI(checked)
                if (checked) showShortcutWarning()
            }
        }

        // One Pedal — regen de retour
        setupConfigRow("shortcut_one_pedal_fallback", RegenLevel.HIGH.value, view,
            R.id.sc_fallback_off      to RegenLevel.OFF.value,
            R.id.sc_fallback_low      to RegenLevel.LOW.value,
            R.id.sc_fallback_medium   to RegenLevel.MEDIUM.value,
            R.id.sc_fallback_high     to RegenLevel.HIGH.value,
            R.id.sc_fallback_adaptive to RegenLevel.ADAPTIVE.value
        )

        // ADAS — modes A et B : tous les firmwares connus utilisent les indices 0-4
        // (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA). La conversion index→hardware est faite dans le service.
        setupConfigRow("shortcut_adas_mode_a", 0, view,
            R.id.sc_adas_a_0 to 0, R.id.sc_adas_a_1 to 1, R.id.sc_adas_a_2 to 2,
            R.id.sc_adas_a_3 to 3, R.id.sc_adas_a_4 to 4
        )
        setupConfigRow("shortcut_adas_mode_b", 3, view,
            R.id.sc_adas_b_0 to 0, R.id.sc_adas_b_1 to 1, R.id.sc_adas_b_2 to 2,
            R.id.sc_adas_b_3 to 3, R.id.sc_adas_b_4 to 4
        )
    }

    private fun setupConfigRow(
        prefKey: String,
        defaultValue: Int,
        view: View,
        vararg pairs: Pair<Int, Int>
    ) {
        val buttons = pairs.associate { (resId, value) ->
            value to view.findViewById<MaterialButton>(resId)
        }
        buttons.forEach { (value, btn) ->
            btn?.setOnClickListener {
                saveInt(prefKey, value)
                highlightConfig(buttons, value)
            }
        }
        highlightConfig(buttons, prefs.getInt(prefKey, defaultValue))
    }

    // ── Restauration de l'état ────────────────────────────────────────────

    private fun restoreState() {
        val enabled = prefs.getBoolean("shortcut_enabled", false)
        switchEnabled?.isChecked = enabled
        applyEnabledUI(enabled)
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    private fun applyEnabledUI(enabled: Boolean) {
        shortcutsContent?.alpha = if (enabled) 1f else 0.35f
        setChildrenEnabled(shortcutsContent, enabled)
    }

    private fun setChildrenEnabled(v: View?, enabled: Boolean) {
        if (v == null) return
        v.isEnabled = enabled
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setChildrenEnabled(v.getChildAt(i), enabled)
        }
    }

    private fun highlightConfig(map: Map<Int, MaterialButton?>, active: Int) {
        val activeTextColor   = requireContext().getColor(R.color.text_active)
        val inactiveTextColor = requireContext().getColor(R.color.text_secondary)
        map.forEach { (value, btn) ->
            val isActive = value == active
            btn?.backgroundTintList = ColorStateList.valueOf(if (isActive) accentColor else defColor)
            btn?.setTextColor(if (isActive) activeTextColor else inactiveTextColor)
        }
    }

    private fun showShortcutWarning() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_warning_title)
            .setMessage(R.string.shortcuts_warning_message)
            .setPositiveButton(R.string.shortcuts_warning_ok, null)
            .show()
    }

    // ── Prefs helpers ────────────────────────────────────────────────────

    private fun saveInt(key: String, value: Int)          = prefs.edit().putInt(key, value).apply()
    private fun saveBoolean(key: String, value: Boolean)  = prefs.edit().putBoolean(key, value).apply()
}
