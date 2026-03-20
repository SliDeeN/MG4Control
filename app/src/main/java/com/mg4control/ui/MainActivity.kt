package com.mg4control.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mg4control.R
import com.mg4control.databinding.ActivityMainBinding
import com.mg4control.databinding.DialogLogsBinding
import com.mg4control.databinding.DialogProfilesBinding
import com.mg4control.databinding.DialogSettingsBinding
import com.mg4control.databinding.ItemProfileBinding
import com.mg4control.model.VehicleProfile
import com.mg4control.repository.VehiclePropertyIds
import com.mg4control.repository.VehicleRepository
import com.mg4control.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val drivingButtons by lazy {
        listOf(binding.btnEco, binding.btnNormal, binding.btnSport, binding.btnSnow)
    }
    private val regenButtons by lazy {
        listOf(binding.btnRegenLow, binding.btnRegenStd, binding.btnRegenHigh, binding.btnRegenAuto)
    }

    private var logsDialog: Dialog? = null
    private var logsBinding: DialogLogsBinding? = null
    private val logsHandler = Handler(Looper.getMainLooper())
    private val logsRunnable = object : Runnable {
        override fun run() {
            logsBinding?.let { lb ->
                lb.tvLogs.text = AppLogger.getAll()
                lb.scrollLogs.post { lb.scrollLogs.fullScroll(View.FOCUS_DOWN) }
            }
            logsHandler.postDelayed(this, 1000)
        }
    }

    // ── Langue ────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageManager.getSavedLanguage(newBase)
        super.attachBaseContext(LanguageManager.applyLanguage(newBase, lang))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)

        // Dialogue de choix de langue au premier lancement
        if (LanguageManager.isFirstLaunch(this)) {
            showFirstLaunchLanguagePicker()
            return
        }

        init()
    }

    private fun init() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AppLogger.i("MainActivity", "onCreate — langue: ${LanguageManager.getSavedLanguage(this)}")
        setupClickListeners()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        if (::binding.isInitialized) vm.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        logsHandler.removeCallbacks(logsRunnable)
        logsDialog?.dismiss()
    }

    // ── Choix langue premier lancement ────────────────────────────────────

    private fun showFirstLaunchLanguagePicker() {
        AlertDialog.Builder(this)
            .setTitle("Langue / Language")
            .setItems(arrayOf("🇫🇷 Français", "🇬🇧 English")) { _, which ->
                val lang = if (which == 0) LanguageManager.LANG_FR else LanguageManager.LANG_EN
                LanguageManager.saveLanguage(this, lang)
                recreate() // recrée l'activité avec la bonne langue
            }
            .setCancelable(false)
            .show()
    }

    // ── Listeners ─────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnEco.setOnClickListener    { vm.setEco() }
        binding.btnNormal.setOnClickListener { vm.setNormal() }
        binding.btnSport.setOnClickListener  { vm.setSport() }
        binding.btnSnow.setOnClickListener   { vm.setSnow() }
        binding.btnRegenLow.setOnClickListener  { vm.setRegenLow() }
        binding.btnRegenStd.setOnClickListener  { vm.setRegenStandard() }
        binding.btnRegenHigh.setOnClickListener { vm.setRegenHigh() }
        binding.btnRegenAuto.setOnClickListener { vm.setRegenAuto() }
        binding.btnOnePedal.setOnClickListener              { vm.toggleOnePedal() }
        binding.btnRefresh.setOnClickListener               { vm.refreshAll() }
        binding.btnConnect.setOnClickListener               { vm.connect() }
        binding.btnLogs.setOnClickListener                  { showLogsDialog() }
        binding.btnProfiles.setOnClickListener              { showProfilesDialog() }
        binding.btnSettings.setOnClickListener              { showSettingsDialog() }
        binding.btnSlifWarning.setOnClickListener           { vm.toggleOverspeedAlarm() }
        binding.btnSpeedLimitChangeTone.setOnClickListener  { vm.toggleSpeedLimitChangeTone() }
    }

    // ── Observation ───────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.connectionState.collect { updateConnectionUi(it) } }
                launch { vm.drivingMode.collect     { highlightDrivingMode(it) } }
                launch { vm.regenLevel.collect      { highlightRegenLevel(it) } }
                launch { vm.onePedal.collect        { updateOnePedalButton(it) } }
                launch { vm.overspeedAlarm.collect  { updateAlertButton(binding.btnSlifWarning, it,
                    getString(if (it) R.string.btn_overspeed_on else R.string.btn_overspeed_off)) } }
                launch { vm.speedLimitChangeTone.collect { updateAlertButton(binding.btnSpeedLimitChangeTone, it,
                    getString(if (it) R.string.btn_speed_tone_on else R.string.btn_speed_tone_off)) } }
                launch {
                    vm.lastAction.collect { msg ->
                        binding.tvLastAction.text = msg
                        binding.tvLastAction.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    // ── Dialog Réglages ───────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val sb = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sb.root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val currentLang = LanguageManager.getSavedLanguage(this)
        sb.btnLangFr.isSelected = (currentLang == LanguageManager.LANG_FR)
        sb.btnLangEn.isSelected = (currentLang == LanguageManager.LANG_EN)
        sb.tvCurrentLang.text = if (currentLang == LanguageManager.LANG_FR)
            getString(R.string.lang_french) else getString(R.string.lang_english)

        sb.btnLangFr.setOnClickListener {
            LanguageManager.saveLanguage(this, LanguageManager.LANG_FR)
            dialog.dismiss()
            recreate()
        }
        sb.btnLangEn.setOnClickListener {
            LanguageManager.saveLanguage(this, LanguageManager.LANG_EN)
            dialog.dismiss()
            recreate()
        }
        sb.btnCloseSettings.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ── Dialog Profils ────────────────────────────────────────────────────

    private fun showProfilesDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val pb = DialogProfilesBinding.inflate(layoutInflater)
        dialog.setContentView(pb.root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        pb.btnCloseProfiles.setOnClickListener { dialog.dismiss() }
        pb.btnSaveCurrentProfile.setOnClickListener {
            showSaveToProfilePicker { profileId ->
                vm.saveCurrentToProfile(profileId)
                dialog.dismiss()
                showProfilesDialog()
            }
        }

        val profiles = vm.profiles.value
        bindProfileItem(pb.profile1, profiles.getOrNull(0) ?: VehicleProfile.empty(1), dialog)
        bindProfileItem(pb.profile2, profiles.getOrNull(1) ?: VehicleProfile.empty(2), dialog)
        bindProfileItem(pb.profile3, profiles.getOrNull(2) ?: VehicleProfile.empty(3), dialog)

        lifecycleScope.launch {
            vm.profiles.collect { updatedProfiles ->
                if (dialog.isShowing) {
                    bindProfileItem(pb.profile1, updatedProfiles.getOrNull(0) ?: VehicleProfile.empty(1), dialog)
                    bindProfileItem(pb.profile2, updatedProfiles.getOrNull(1) ?: VehicleProfile.empty(2), dialog)
                    bindProfileItem(pb.profile3, updatedProfiles.getOrNull(2) ?: VehicleProfile.empty(3), dialog)
                }
            }
        }
        dialog.show()
    }

    private fun bindProfileItem(ib: ItemProfileBinding, profile: VehicleProfile, parentDialog: Dialog) {
        val starPrefix = if (profile.isFavorite) "⭐ " else ""
        ib.tvProfileName.text = "$starPrefix${profile.name}"
        val onePedalStr = if (profile.onePedal) " • One Pedal ON" else ""
        ib.tvProfileSummary.text = "${profile.driveModeLabel()} • ${getString(R.string.section_regen)} ${profile.regenLabel()}$onePedalStr"
        ib.btnSetFavorite.text = if (profile.isFavorite) getString(R.string.btn_set_favorite_on) else getString(R.string.btn_set_favorite_off)
        ib.btnSetFavorite.setOnClickListener {
            if (profile.isFavorite) vm.renameProfile(profile.id, profile.name)
            else vm.setFavorite(profile.id)
        }
        ib.btnApplyProfile.setOnClickListener {
            vm.applyProfile(profile.id)
            parentDialog.dismiss()
        }
        ib.tvProfileName.setOnClickListener {
            showRenameDialog(profile) { newName -> vm.renameProfile(profile.id, newName) }
        }
    }

    private fun showSaveToProfilePicker(onSelected: (Int) -> Unit) {
        val profiles = vm.profiles.value
        val items = profiles.map { p -> "${if (p.isFavorite) "⭐ " else ""}${p.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_save_to))
            .setItems(items) { _, which -> onSelected(which + 1) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showRenameDialog(profile: VehicleProfile, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(profile.name)
            selectAll()
            hint = getString(R.string.dialog_rename_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) onConfirm(newName)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── Dialog Logs ───────────────────────────────────────────────────────

    private fun showLogsDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val lb = DialogLogsBinding.inflate(layoutInflater)
        dialog.setContentView(lb.root)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        logsDialog  = dialog
        logsBinding = lb
        lb.btnClearLogs.setOnClickListener { AppLogger.clear(); lb.tvLogs.text = "" }
        lb.btnCloseLogs.setOnClickListener {
            logsHandler.removeCallbacks(logsRunnable)
            dialog.dismiss()
            logsDialog = null; logsBinding = null
        }
        lb.tvLogs.text = AppLogger.getAll()
        lb.scrollLogs.post { lb.scrollLogs.fullScroll(View.FOCUS_DOWN) }
        logsHandler.post(logsRunnable)
        dialog.show()
    }

    // ── Mise à jour UI ────────────────────────────────────────────────────

    private fun updateConnectionUi(state: VehicleRepository.ConnectionState) {
        when (state) {
            is VehicleRepository.ConnectionState.Disconnected -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
                binding.btnConnect.visibility = View.VISIBLE
                setControlsEnabled(false)
            }
            is VehicleRepository.ConnectionState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting))
                binding.btnConnect.visibility = View.GONE
                setControlsEnabled(false)
            }
            is VehicleRepository.ConnectionState.Connected -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                binding.btnConnect.visibility = View.GONE
                setControlsEnabled(true)
            }
            is VehicleRepository.ConnectionState.Error -> {
                val shortMsg = state.message.lines().first()
                binding.tvStatus.text = "🔴 $shortMsg"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                binding.btnConnect.visibility = View.VISIBLE
                setControlsEnabled(false)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        (drivingButtons + regenButtons + listOf(
            binding.btnOnePedal, binding.btnRefresh,
            binding.btnSlifWarning, binding.btnSpeedLimitChangeTone
        )).forEach { it.isEnabled = enabled }
    }

    private fun highlightDrivingMode(mode: Int) {
        val active = when (mode) {
            VehiclePropertyIds.DrivingMode.ECO    -> binding.btnEco
            VehiclePropertyIds.DrivingMode.NORMAL -> binding.btnNormal
            VehiclePropertyIds.DrivingMode.SPORT  -> binding.btnSport
            VehiclePropertyIds.DrivingMode.SNOW   -> binding.btnSnow
            else -> null
        }
        drivingButtons.forEach { it.isSelected = (it == active) }
    }

    private fun highlightRegenLevel(level: Int) {
        val active = when (level) {
            VehiclePropertyIds.RegenLevel.LOW      -> binding.btnRegenLow
            VehiclePropertyIds.RegenLevel.STANDARD -> binding.btnRegenStd
            VehiclePropertyIds.RegenLevel.HIGH     -> binding.btnRegenHigh
            VehiclePropertyIds.RegenLevel.AUTO     -> binding.btnRegenAuto
            else -> null
        }
        regenButtons.forEach { it.isSelected = (it == active) }
    }

    private fun updateOnePedalButton(active: Boolean) {
        binding.btnOnePedal.isSelected = active
        binding.btnOnePedal.text = getString(
            if (active) R.string.btn_one_pedal_on else R.string.btn_one_pedal_off
        )
    }

    private fun updateAlertButton(btn: android.widget.Button, active: Boolean, label: String) {
        btn.isSelected = active
        btn.text = label
    }
}
