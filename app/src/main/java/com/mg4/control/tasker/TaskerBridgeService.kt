package com.mg4.control.tasker

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.mg4.hardware.AppLogger
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.model.DriveMode
import com.mg4.hardware.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.hardware.FirmwareInfo
import com.mg4.control.bluetooth.BluetoothProfileManager

/**
 * Pont IPC pour MG4Tasker (voir [ITaskerBridge]).
 *
 * Principe : MG4Tasker ne touche JAMAIS le véhicule. Il lit un instantané et demande des
 * actions nommées ; l'écriture réelle se fait ici, dans le processus qui détient
 * android.uid.system et [VehicleWriteGate]. Conséquences voulues :
 *   • un seul processus écrit → pas d'entrelacement de commandes ADAS
 *   • le verrou 0 km/h reste appliqué à un seul endroit
 *   • MG4Tasker n'a pas besoin de privilèges véhicule
 *
 * Le catalogue d'actions est une liste fermée. Il n'existe volontairement aucune méthode
 * « écris la propriété 0xNNNN » : un appelant compromis ne peut solliciter que ce que
 * l'utilisateur peut déjà faire depuis l'UI de MG4Control.
 *
 * [VEHICLE_POWER_OFF] est délibérément ABSENT du catalogue. Couper le véhicule est une
 * action irréversible pour le conducteur ; elle reste réservée à un geste humain explicite
 * (raccourci volant), jamais à une règle automatique.
 */
class TaskerBridgeService : Service() {

    companion object {
        private const val TAG = "MG4_TASKER_BRIDGE"


        /** Permission signature exigée du récepteur du broadcast ci-dessus. */
        const val PERMISSION_BRIDGE = "com.mg4.control.permission.TASKER_BRIDGE"

        // ── Clés de l'instantané ────────────────────────────────────────────
        // Une clé ABSENTE signifie « illisible ». Ne jamais écrire de valeur
        // sentinelle (-1) : le tasker la prendrait pour une vraie mesure.
        const val KEY_SPEED_KMH        = "speedKmh"          // float
        const val KEY_SPEED_READABLE   = "speedReadable"     // boolean (toujours présent)
        const val KEY_IGNITION         = "ignition"          // int
        const val KEY_IN_PARK          = "inPark"            // boolean
        const val KEY_OUTSIDE_TEMP     = "outsideTempC"      // float
        const val KEY_DRIVE_MODE       = "driveMode"         // int (DriveMode.value)
        const val KEY_REGEN_LEVEL      = "regenLevel"        // int (RegenLevel.value)
        const val KEY_SEAT_HEAT_L      = "seatHeatLeft"      // int
        const val KEY_SEAT_HEAT_R      = "seatHeatRight"     // int
        const val KEY_STEERING_HEAT    = "steeringHeat"      // boolean
        const val KEY_MEDIA_VOLUME     = "mediaVolume"       // int
        const val KEY_MEDIA_VOLUME_MAX = "mediaVolumeMax"    // int
        const val KEY_BRIGHTNESS       = "brightnessPct"     // int
        const val KEY_OVERSPEED_ALARM  = "overspeedAlarm"    // boolean
        const val KEY_SPEED_LIMIT_TONE = "speedLimitTone"    // boolean
        const val KEY_SOUND_WARNING    = "soundWarning"      // boolean
        const val KEY_AEB_ENABLED      = "aebEnabled"        // boolean
        const val KEY_AEB_MODE         = "aebMode"           // int
        const val KEY_AEB_SENSITIVITY  = "aebSensitivity"    // int
        const val KEY_ELK_MODE         = "elkMode"           // int
        const val KEY_ELK_SENSITIVITY  = "elkSensitivity"    // int
        const val KEY_TSR              = "tsr"               // boolean
        const val KEY_ENERGY_SAVING    = "energySaving"      // boolean
        const val KEY_ACC_TJA_MODE     = "accTjaMode"        // int
        const val KEY_LIMITER_MODE     = "limiterMode"       // int
        // Climate + windows — read only, unverified (see MG4Hardware). Absent = unreadable.
        const val KEY_AC_ON            = "acOn"              // boolean
        const val KEY_HVAC_AUTO        = "hvacAuto"          // boolean
        const val KEY_RECIRC           = "recirc"            // boolean
        const val KEY_FAN_SPEED        = "fanSpeed"          // int
        const val KEY_TEMPERATURE_SET  = "temperatureSetC"   // float
        const val KEY_WINDOW_OPEN      = "windowOpen"        // boolean
        const val KEY_FIRMWARE_GEN     = "firmwareGen"       // String
        const val KEY_BT_MACS          = "btConnectedMacs"   // String[]
        const val KEY_HAS_AUDIO        = "hasAudioControl"   // boolean
        const val KEY_HAS_BRIGHTNESS   = "hasBrightness"     // boolean

        // ── Clés du résultat ────────────────────────────────────────────────
        const val KEY_OK      = "ok"
        const val KEY_VERDICT = "verdict"
        const val KEY_DETAIL  = "detail"

        const val VERDICT_ALLOWED       = "ALLOWED"
        const val VERDICT_MOVING        = "REFUSED_MOVING"
        const val VERDICT_UNKNOWN_SPEED = "REFUSED_UNKNOWN_SPEED"
        const val VERDICT_UNSUPPORTED   = "UNSUPPORTED"
        const val VERDICT_ERROR         = "ERROR"

        /** Clé unique des paramètres d'action (int, boolean ou String selon l'action). */
        const val PARAM_VALUE = "value"

        /**
         * Traduit la décision du verrou en verdict transmissible au tasker.
         *
         * Fonction pure et exhaustive : si [VehicleWriteGate.Decision] gagne un cas, la
         * compilation casse ici plutôt que d'envoyer un verdict inventé au tasker, qui
         * l'afficherait à l'utilisateur comme un motif de refus.
         */
        fun verdictOf(decision: VehicleWriteGate.Decision): String = when (decision) {
            VehicleWriteGate.Decision.ALLOWED               -> VERDICT_ALLOWED
            VehicleWriteGate.Decision.REFUSED_MOVING        -> VERDICT_MOVING
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> VERDICT_UNKNOWN_SPEED
        }
    }

    private val profileManager by lazy { ProfileManager(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ITaskerBridge.Stub() {

        override fun readSnapshot(): Bundle = Bundle().apply {
            val speed = MG4Hardware.getVehicleSpeedKmh()
            putBoolean(KEY_SPEED_READABLE, speed != null)
            speed?.let { putFloat(KEY_SPEED_KMH, it) }

            MG4Hardware.getCurrentIgnitionState().takeIf { it > 0 }?.let { putInt(KEY_IGNITION, it) }
            MG4Hardware.isVehicleInPark()?.let { putBoolean(KEY_IN_PARK, it) }
            MG4Hardware.getOutsideTempCelsius()?.let { putFloat(KEY_OUTSIDE_TEMP, it) }

            MG4Hardware.getDriveMode()?.let { putInt(KEY_DRIVE_MODE, it.value) }
            MG4Hardware.getRegenLevel()?.let { putInt(KEY_REGEN_LEVEL, it.value) }

            putIfReadable(KEY_SEAT_HEAT_L, MG4Hardware.getSeatHeatLeft())
            putIfReadable(KEY_SEAT_HEAT_R, MG4Hardware.getSeatHeatRight())
            putBoolean(KEY_STEERING_HEAT, MG4Hardware.isSteeringHeatOn())

            putIfReadable(KEY_MEDIA_VOLUME, MG4Hardware.getMediaVolume())
            putIfReadable(KEY_MEDIA_VOLUME_MAX, MG4Hardware.getMediaVolumeMax())

            putBoolean(KEY_HAS_BRIGHTNESS, MG4Hardware.hasBrightnessControl())
            if (MG4Hardware.hasBrightnessControl()) {
                putIfReadable(KEY_BRIGHTNESS, MG4Hardware.getScreenBrightnessPercent())
            }

            putBoolean(KEY_OVERSPEED_ALARM, MG4Hardware.isOverspeedAlarmOn())
            putBoolean(KEY_SPEED_LIMIT_TONE, MG4Hardware.isSpeedLimitToneOn())
            putBoolean(KEY_SOUND_WARNING, MG4Hardware.isSoundWarningOn())
            putBoolean(KEY_AEB_ENABLED, MG4Hardware.isAebEnabled())
            putIfReadable(KEY_AEB_MODE, MG4Hardware.getAebMode())
            putIfReadable(KEY_AEB_SENSITIVITY, MG4Hardware.getAebSensitivity())
            putIfReadable(KEY_ELK_MODE, MG4Hardware.getElkMode())
            putIfReadable(KEY_ELK_SENSITIVITY, MG4Hardware.getElkSensitivity())
            putBoolean(KEY_TSR, MG4Hardware.isTsrOn())
            putBoolean(KEY_ENERGY_SAVING, MG4Hardware.isEnergySavingOn())
            putIfReadable(KEY_ACC_TJA_MODE, MG4Hardware.getAccTjaMode())
            putIfReadable(KEY_LIMITER_MODE, MG4Hardware.getSpeedLimiterMode())

            MG4Hardware.getAcOn()?.let { putBoolean(KEY_AC_ON, it) }
            MG4Hardware.getHvacAutoOn()?.let { putBoolean(KEY_HVAC_AUTO, it) }
            MG4Hardware.getRecircOn()?.let { putBoolean(KEY_RECIRC, it) }
            MG4Hardware.getFanSpeed()?.let { putInt(KEY_FAN_SPEED, it) }
            MG4Hardware.getTemperatureSetCelsius()?.let { putFloat(KEY_TEMPERATURE_SET, it) }
            MG4Hardware.isAnyWindowOpen()?.let { putBoolean(KEY_WINDOW_OPEN, it) }

            putString(KEY_FIRMWARE_GEN, FirmwareInfo.getGeneration().name)
            putBoolean(KEY_HAS_AUDIO, MG4Hardware.hasAudioControl())
            putStringArray(KEY_BT_MACS, BluetoothProfileManager.getConnectedMacs().toTypedArray())
        }

        override fun listProfiles(): Bundle {
            val profiles = profileManager.getAll()
            return Bundle().apply {
                putStringArray("ids", profiles.map { it.id }.toTypedArray())
                putStringArray("names", profiles.map { it.name }.toTypedArray())
                profileManager.getDefaultId()?.let { putString("defaultId", it) }
            }
        }

        override fun applyProfile(profileId: String?): Bundle {
            val profile = profileId?.let { profileManager.getById(it) }
                ?: return result(false, VERDICT_UNSUPPORTED, "profil introuvable: $profileId")

            // Le gate est ré-évalué réglage par réglage dans ProfileApplier ; on renvoie
            // ici le verdict courant pour que le tasker sache s'il faut s'attendre à des
            // refus partiels. L'application elle-même est asynchrone.
            val verdict = gateVerdict()
            ProfileApplier.apply(profile, autoStart = true)
            AppLogger.i(TAG, "applyProfile(${profile.name}) verdict=$verdict")
            return result(true, verdict, profile.name)
        }

        override fun applyAction(actionType: String?, params: Bundle?): Bundle {
            val type = actionType ?: return result(false, VERDICT_UNSUPPORTED, "action nulle")
            val args = params ?: Bundle()
            return try {
                dispatch(type, args)
            } catch (e: Exception) {
                AppLogger.w(TAG, "applyAction($type) exception: ${e.message}")
                result(false, VERDICT_ERROR, e.message)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Catalogue d'actions
    // -------------------------------------------------------------------------

    /**
     * [gated] = écriture qui change le comportement routier → soumise au verrou 0 km/h.
     * On calcule le verdict AVANT d'appeler MG4Hardware, uniquement pour pouvoir le
     * rapporter au tasker. Le refus effectif reste celui de MG4Hardware/VehicleWriteGate :
     * cette pré-lecture n'est pas la garde, elle l'observe.
     */
    private fun dispatch(type: String, args: Bundle): Bundle {
        val i = { args.getInt(PARAM_VALUE) }
        val b = { args.getBoolean(PARAM_VALUE) }

        return when (type) {
            // ── Confort : pas de gate (n'altère pas le comportement routier) ──
            "SET_SEAT_HEAT_LEFT"       -> ungated { MG4Hardware.setSeatHeatLeft(i()) }
            "SET_SEAT_HEAT_RIGHT"      -> ungated { MG4Hardware.setSeatHeatRight(i()) }
            "SET_STEERING_HEAT"        -> ungated { MG4Hardware.setSteeringHeat(b()) }
            "SET_MEDIA_VOLUME"         -> ungated { MG4Hardware.setMediaVolume(i()) }
            "SET_SCREEN_BRIGHTNESS"    -> ungated { MG4Hardware.setScreenBrightnessPercent(i()) }
            "SET_AUDIO_BALANCE"        -> ungated { MG4Hardware.setAudioBalance(i()) }
            "SET_AUDIO_FADER"          -> ungated { MG4Hardware.setAudioFader(i()) }
            "SET_BOSE_SOUND_TYPE"      -> ungated { MG4Hardware.setBoseSoundType(i()) }
            "SET_3D_EFFECT"            -> ungated { MG4Hardware.set3dEffectType(i()) }
            "SET_TONE_CONTROL"         -> ungated { MG4Hardware.setToneControl(i()) }
            "SET_SOUND_FIELD"          -> ungated { MG4Hardware.setSoundFieldType(i()) }
            "SET_SPEED_VOLUME"         -> ungated { MG4Hardware.setSpeedVolumeLevel(i()) }
            "SET_LAS_WARNING_SOUND"    -> ungated { MG4Hardware.setLasWarningSound(b()) }
            "SET_LAS_WARNING_VIBRATION"-> ungated { MG4Hardware.setLasWarningVibration(b()) }

            // ── Comportement routier : gated ──────────────────────────────────
            "SET_DRIVE_MODE"      -> gated { MG4Hardware.setDriveMode(DriveMode.fromValue(i())) }
            "SET_REGEN_LEVEL"     -> gated { MG4Hardware.setRegenLevel(RegenLevel.fromValue(i())) }
            "SET_ONE_PEDAL"       -> gated { MG4Hardware.setOnePedal(b()) }
            "SET_OVERSPEED_ALARM" -> gated { MG4Hardware.setOverspeedAlarm(b()) }
            "SET_SPEED_LIMIT_TONE"-> gated { MG4Hardware.setSpeedLimitTone(b()) }
            "SET_SOUND_WARNING"   -> gated { MG4Hardware.setSoundWarning(b()) }
            "SET_AEB_ENABLED"     -> gated { MG4Hardware.setAebEnabled(b()) }
            "SET_AEB_MODE"        -> gated { MG4Hardware.setAebMode(i()) }
            "SET_AEB_SENSITIVITY" -> gated { MG4Hardware.setAebSensitivity(i()) }
            "SET_ELK_MODE"        -> gated { MG4Hardware.setElkMode(i()) }
            "SET_ELK_SENSITIVITY" -> gated { MG4Hardware.setElkSensitivity(i()) }
            "SET_TSR"             -> gated { MG4Hardware.setTsrMode(b()) }
            "SET_ENERGY_SAVING"   -> gated { MG4Hardware.setEnergySavingMode(b()) }
            "SET_ACC_TJA_MODE"    -> gated { MG4Hardware.setAccTjaMode(i()) }
            "SET_LIMITER_MODE"    -> gated { MG4Hardware.setSpeedLimiterMode(i()) }
            "SET_INTELLIGENT_DRIVE"-> gated { MG4Hardware.setMixedIntelligentDrive(i()) }

            else -> result(false, VERDICT_UNSUPPORTED, "action inconnue: $type")
        }
    }

    private inline fun ungated(write: () -> Boolean): Bundle {
        val ok = write()
        return result(ok, if (ok) VERDICT_ALLOWED else VERDICT_ERROR)
    }

    private inline fun gated(write: () -> Boolean): Bundle {
        val verdict = gateVerdict()
        if (verdict != VERDICT_ALLOWED) return result(false, verdict)
        val ok = write()
        return result(ok, if (ok) VERDICT_ALLOWED else VERDICT_ERROR)
    }

    private fun gateVerdict(): String =
        verdictOf(VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh()))

    private fun result(ok: Boolean, verdict: String, detail: String? = null) = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putString(KEY_VERDICT, verdict)
        detail?.let { putString(KEY_DETAIL, it) }
    }

    /** Les getters MG4Hardware renvoient -1 quand la couche n'est pas prête : on n'écrit rien. */
    private fun Bundle.putIfReadable(key: String, value: Int) {
        if (value >= 0) putInt(key, value)
    }
}
