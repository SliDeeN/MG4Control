package com.mg4control.viewmodel
import com.mg4control.R

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mg4control.model.VehicleProfile
import com.mg4control.repository.ProfileRepository
import com.mg4control.repository.VehiclePropertyIds
import com.mg4control.repository.VehicleRepository
import com.mg4control.ui.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repository = VehicleRepository(app)
    val profileRepository = ProfileRepository(app)

    // ── États UI ──────────────────────────────────────────────────────────
    val connectionState = repository.connectionState

    private val _drivingMode = MutableStateFlow(-1)
    val drivingMode: StateFlow<Int> = _drivingMode.asStateFlow()

    private val _regenLevel = MutableStateFlow(-1)
    val regenLevel: StateFlow<Int> = _regenLevel.asStateFlow()

    private val _onePedal = MutableStateFlow(false)
    val onePedal: StateFlow<Boolean> = _onePedal.asStateFlow()

    private val _overspeedAlarm = MutableStateFlow(false)
    val overspeedAlarm: StateFlow<Boolean> = _overspeedAlarm.asStateFlow()

    private val _speedLimitChangeTone = MutableStateFlow(false)
    val speedLimitChangeTone: StateFlow<Boolean> = _speedLimitChangeTone.asStateFlow()

    private val _adasMode = MutableStateFlow(0)
    val adasMode: StateFlow<Int> = _adasMode.asStateFlow()

    private val _lastAction = MutableStateFlow("")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    private val _profiles = MutableStateFlow<List<VehicleProfile>>(emptyList())
    val profiles: StateFlow<List<VehicleProfile>> = _profiles.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────
    init {
        _profiles.value = profileRepository.getAll()

        // Premier refresh quand Connected est émis
        connectionState.onEach { state ->
            if (state == VehicleRepository.ConnectionState.Connected) {
                refreshAll()
            }
        }.launchIn(viewModelScope)

        // Second refresh quand onServiceConnected est vraiment reçu (SDK prêt)
        // Résout le bug de boutons grisés au démarrage
        repository.onServiceConnectedCallback = {
            viewModelScope.launch {
                AppLogger.i("MainViewModel", "onServiceConnected — refreshAll tardif")
                refreshAll()
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────
    fun connect() = repository.connect()

    fun refreshAll() {
        viewModelScope.launch {
            val mode   = repository.getDrivingMode()
            val regen  = repository.getRegenLevel()
            val pedal  = repository.getOnePedal()
            val slif   = repository.getOverspeedAlarm()
            val tone   = repository.getSpeedLimitChangeTone()
            val adas   = repository.getAdasMode()  // getIntProperty(0x32)
            Log.d(TAG, "refreshAll → drive=$mode regen=$regen pedal=$pedal slif=$slif tone=$tone adas=$adas")
            _drivingMode.value          = mode
            _regenLevel.value           = regen
            _onePedal.value             = (pedal == VehiclePropertyIds.SignalPedal.ON)
            _overspeedAlarm.value       = (slif == VehiclePropertyIds.AlertSwitch.ON)
            _speedLimitChangeTone.value = (tone == VehiclePropertyIds.AlertSwitch.ON)
            _adasMode.value             = if (adas >= 0) adas else 0        }
    }

    // ── Mode de conduite ─────────────────────────────────────────────────
    fun setEco()    = applyDrivingMode(VehiclePropertyIds.DrivingMode.ECO,    "ECO")
    fun setNormal() = applyDrivingMode(VehiclePropertyIds.DrivingMode.NORMAL, "NORMAL")
    fun setSport()  = applyDrivingMode(VehiclePropertyIds.DrivingMode.SPORT,  "SPORT")
    fun setSnow()   = applyDrivingMode(VehiclePropertyIds.DrivingMode.SNOW,   "NEIGE")

    private fun applyDrivingMode(mode: Int, label: String) {
        viewModelScope.launch {
            val ok = repository.setDrivingMode(mode)
            if (ok) {
                _drivingMode.value = mode
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_drive_mode_applied, label))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    // ── Régénération ─────────────────────────────────────────────────────
    fun setRegenLow()      = applyRegen(VehiclePropertyIds.RegenLevel.LOW,      "Faible")
    fun setRegenStandard() = applyRegen(VehiclePropertyIds.RegenLevel.STANDARD, "Standard")
    fun setRegenHigh()     = applyRegen(VehiclePropertyIds.RegenLevel.HIGH,     "Fort")
    fun setRegenAuto()     = applyRegen(VehiclePropertyIds.RegenLevel.AUTO,     "Adaptatif")

    private fun applyRegen(level: Int, label: String) {
        viewModelScope.launch {
            val ok = repository.setRegenLevel(level)
            if (ok) {
                _regenLevel.value = level
                if (_onePedal.value) {
                    repository.setOnePedal(false)
                    _onePedal.value = false
                }
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_regen_applied, label))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    // ── One Pedal ────────────────────────────────────────────────────────
    fun toggleOnePedal() {
        viewModelScope.launch {
            val newState = !_onePedal.value
            val ok = repository.setOnePedal(newState)
            if (ok) {
                _onePedal.value = newState
                feedback(getApplication<android.app.Application>().getString(
                    if (newState) R.string.feedback_one_pedal_on else R.string.feedback_one_pedal_off))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    // ── ADAS ─────────────────────────────────────────────────────────────
    fun setAdasOff()        = applyAdas(VehiclePropertyIds.AdasMode.OFF,        "OFF")
    fun setAdasLimitateur() = applyAdas(VehiclePropertyIds.AdasMode.SPEED_LIMI, "Limitateur")
    fun setAdasAcc()        = applyAdas(VehiclePropertyIds.AdasMode.ACC,        "ACC")
    fun setAdasIca()        = applyAdas(VehiclePropertyIds.AdasMode.ICA,        "ICA")
    private fun applyAdas(mode: Int, label: String) {
        viewModelScope.launch {
            val ok = repository.setAdasMode(mode)
            if (ok) {
                _adasMode.value = mode
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_adas_applied, label))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    /** Diagnostic ADAS — lit le mode via getMixProperty (méthode correcte confirmée par smali) */
    fun diagAdas() {
        viewModelScope.launch {
            AppLogger.i("DIAG_ADAS", "=== Diagnostic ADAS ===")
            val vMix = repository.getMixInt(VehiclePropertyIds.MIXED_INTELLIGENT_DRIVE)
            AppLogger.i("DIAG_ADAS", "getMixProperty(0x32) = $vMix")
            val vInt = repository.getIntProperty(VehiclePropertyIds.MIXED_INTELLIGENT_DRIVE)
            AppLogger.i("DIAG_ADAS", "getIntProperty(0x32) = $vInt")
            AppLogger.i("DIAG_ADAS", "Valeurs attendues : 0=OFF 1=Limitateur 2=Auto 3=ACC 4=ICA")
            AppLogger.i("DIAG_ADAS", "=== Fin ===")
            feedback("ADAS : getMix=$vMix getInt=$vInt")
        }
    }

    // ── Alertes vitesse ──────────────────────────────────────────────────
    fun toggleOverspeedAlarm() {
        viewModelScope.launch {
            val newState = !_overspeedAlarm.value
            val ok = repository.setOverspeedAlarm(newState)
            if (ok) {
                _overspeedAlarm.value = newState
                feedback(getApplication<android.app.Application>().getString(
                    if (newState) R.string.feedback_overspeed_on else R.string.feedback_overspeed_off))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    /** Lit et loggue l'état de tous les IDs liés aux alertes vitesse — pour debug */
    fun diagSpeedAlerts() {
        viewModelScope.launch {
            AppLogger.i("DIAG", "=== Lecture IDs alertes vitesse ===")
            val ids = mapOf(
                "SLIF_WARNING        0x5030049" to 0x5030049,
                "SLIF_WARNING_VALID  0x503004a" to 0x503004a,
                "SPEED_ASST_MODE     0x503004b" to 0x503004b,
                "SPEED_ASST_FLAG     0x503004c" to 0x503004c,
                "OVERSPEED_ALARM_OLD 0x503004e" to 0x503004e,
                "SPEED_LIMIT_TONE    0x503004f" to 0x503004f,
                "OVERSPEED_ALARM_ISA 0x503005b" to 0x503005b,
                "SPEED_LIMIT_ISA     0x503005c" to 0x503005c,
                "OVERSPD_HIST        0x503003a" to 0x503003a
            )
            ids.forEach { (name, id) ->
                val v = repository.getIntProperty(id)
                AppLogger.i("DIAG", "$name = $v")
            }
            AppLogger.i("DIAG", "=== Fin lecture ===")
            feedback("Valeurs lues — ouvre les logs")
        }
    }

    fun toggleSpeedLimitChangeTone() {
        viewModelScope.launch {
            val newState = !_speedLimitChangeTone.value
            val ok = repository.setSpeedLimitChangeTone(newState)
            if (ok) {
                _speedLimitChangeTone.value = newState
                feedback(getApplication<android.app.Application>().getString(
                    if (newState) R.string.feedback_speed_tone_on else R.string.feedback_speed_tone_off))
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_write))
            }
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────
    private fun feedback(msg: String) {
        Log.i(TAG, msg)
        viewModelScope.launch {
            _lastAction.value = msg
            delay(3000)
            if (_lastAction.value == msg) _lastAction.value = ""
        }
    }

    private fun refreshProfiles() {
        _profiles.value = profileRepository.getAll()
    }

    // ── Profils ───────────────────────────────────────────────────────────

    /** Sauvegarder l'état courant du véhicule dans le profil id */
    fun saveCurrentToProfile(id: Int) {
        viewModelScope.launch {
            profileRepository.saveCurrentState(
                id                   = id,
                driveMode            = _drivingMode.value,
                regenLevel           = _regenLevel.value,
                onePedal             = _onePedal.value,
                overspeedAlarm       = _overspeedAlarm.value,
                speedLimitChangeTone = _speedLimitChangeTone.value
            )
            refreshProfiles()
            val name = profileRepository.getById(id).name
            feedback("✓ État sauvegardé dans '$name'")
            AppLogger.i(TAG, "État sauvegardé dans profil $id '$name'")
        }
    }

    /** Appliquer un profil au véhicule */
    fun applyProfile(id: Int) {
        viewModelScope.launch {
            val profile = profileRepository.getById(id)
            AppLogger.i(TAG, "Application profil '${profile.name}'...")

            var ok = true
            ok = ok && repository.setDrivingMode(profile.driveMode)
            ok = ok && repository.setRegenLevel(profile.regenLevel)
            ok = ok && repository.setOnePedal(profile.onePedal)
            ok = ok && repository.setIntProperty(
                VehiclePropertyIds.OVERSPEED_SOUND_ALARM,
                if (profile.overspeedAlarm) 1 else 0
            )
            ok = ok && repository.setIntProperty(
                VehiclePropertyIds.SPEED_LIMIT_CHANGE_TONE,
                if (profile.speedLimitChangeTone) 1 else 0
            )

            if (ok) {
                // Mettre à jour les états UI
                _drivingMode.value          = profile.driveMode
                _regenLevel.value           = profile.regenLevel
                _onePedal.value             = profile.onePedal
                _overspeedAlarm.value       = profile.overspeedAlarm
                _speedLimitChangeTone.value = profile.speedLimitChangeTone
                feedback("✓ Profil '${profile.name}' appliqué")
            } else {
                feedback(getApplication<android.app.Application>().getString(R.string.feedback_error_apply))
            }
        }
    }

    /** Définir un profil comme favori */
    fun setFavorite(id: Int) {
        profileRepository.setFavorite(id)
        refreshProfiles()
        val name = profileRepository.getById(id).name
        feedback("⭐ '$name' défini comme profil favori")
        AppLogger.i(TAG, "Profil favori : $id '$name'")
    }

    /** Renommer un profil */
    fun renameProfile(id: Int, newName: String) {
        profileRepository.rename(id, newName)
        refreshProfiles()
        AppLogger.i(TAG, "Profil $id renommé : '$newName'")
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}

