package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hardware abstraction layer for MG4 vehicle control.
 * Reconstructed from DriveHub Dort 0.9 smali.
 *
 * Three communication layers (mirrors original exactly):
 *  Katman1 — android.car.Car → CarPropertyManager / CarHvacManager (async ServiceConnection)
 *  Katman2 — ServiceManager.getService("vehiclesetting") → raw IBinder (often SELinux-blocked)
 *  Katman3 — bindService(VehicleService) → IHubService → sub-services (not needed for our use case)
 */
object MG4Hardware {

    private const val TAG = "MG4_HW"

    // Area IDs
    private const val AREA_GLOBAL = 0x1000000
    private const val AREA_HVAC   = 0x75

    // Vehicle property IDs
    private const val PROP_DRIVE_MODE  = 0x2140a17c
    private const val PROP_REGEN_LEVEL = 0x2140a191
    private const val PROP_ONE_PEDAL   = 0x2140a193
    const val PROP_SEAT_HEAT_L         = 0x15402513
    const val PROP_SEAT_HEAT_R         = 0x15402514
    const val PROP_STEERING_HEAT       = 0x1540253a

    // SAIC binder transaction codes
    private const val TX_SET_DRIVE_MODE  = 0x82
    private const val TX_SET_REGEN_LEVEL = 0xa1
    private const val TX_SET_ONE_PEDAL   = 0xa4

    private const val DESCRIPTOR_VEHICLE  = "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService"
    private const val DESCRIPTOR_VSM132   = "com.saicmotor.vehiclesetting.IVehicleSettingService"
    private const val PREFS_NAME          = "drivehub_dort"
    private const val KEY_LAST_DRIVE_MODE = "last_drive_mode"

    // ADAS property IDs — SWI133 (getMixProperty / getIntProperty)
    private const val PROP_OVERSPEED_ALARM       = 0x503004e
    private const val PROP_SPEED_LIMIT_TONE      = 0x503004f
    private const val PROP_MIX_INTELLIGENT_DRIVE = 0x32

    // ELK — Assistant de sortie de voie (SWI133)
    // Accès via IVehicleSettingService binder (sVehicleBinder) — smali IVehicleSettingService$Stub$Proxy
    // getLaneKeepingAsstMode()   → TX 0x53 (synchrone, reply: readException + readInt)
    // setLaneKeepingAsstMode(I)  → TX 0x54 (ONEWAY, data: writeInt(value))
    // getLaneKeepingAsstSen()    → TX 0x55 (synchrone)
    // setLaneKeepingAsstSen(I)   → TX 0x56 (ONEWAY)
    // Mode  : 1=OFF, 2=Alerte(LDW), 3=Aider(LDP), 5=Maintien d'urgence(ELK)
    // Sen   : 1=Faible, 2=Standard, 3=Élevé
    private const val TX_ELK_GET_MODE = 0x53
    private const val TX_ELK_SET_MODE = 0x54
    private const val TX_ELK_GET_SEN  = 0x55
    private const val TX_ELK_SET_SEN  = 0x56

    // SWI132 binder TX codes (IVehicleSettingService — DESCRIPTOR_VSM132, two-way flag=0x0)
    // Setters : 0x057=setSLIFWarningState, 0x128=setOverSpeedSoundMode, 0x12a=setSpeedLimitSoundMode
    // Getters : 0x058=getSLIFWarningState, 0x129=getOverSpeedSoundMode, 0x12b=getSpeedLimitSoundMode
    private const val VSM132_TX_SLIF_WARNING    = 0x057
    private const val VSM132_TX_OVERSPEED_SOUND = 0x128
    private const val VSM132_TX_SPEED_LIMIT     = 0x12a
    private const val VSM132_TX_GET_SLIF        = 0x058
    private const val VSM132_TX_GET_OVERSPEED   = 0x129
    private const val VSM132_TX_GET_SPEED_LIMIT = 0x12b

    /** Valeurs du mode ELK (LaneKeepingAsstMode). */
    object ElkMode {
        const val OFF       = 1   // Désactivé
        const val ALERT     = 2   // Alerte (LDW)
        const val ASSIST    = 3   // Aider (LDP)
        const val EMERGENCY = 5   // Maintien d'urgence (ELK)
    }

    /** Valeurs de sensibilité ELK. */
    object ElkSensitivity {
        const val LOW      = 1   // Faible
        const val STANDARD = 2   // Standard
        const val HIGH     = 3   // Élevé
    }

    // AEB — Système anti-collision avant (SWI133)
    // PROP_AEB_SWITCH    : CarPropertyManager, AREA_GLOBAL, 1=OFF / 2=ON
    private const val PROP_AEB_SWITCH    = 0x2140a108  // AAD_FRONT_COLLISION_ASST_SYS (CPM)
    // PROP_AEB_SYS_MODE  : VPM, ID_AAD_FRONT_COLLISION_ASST_SYS, 1=Alerte / 2=Alerte+Freinage
    // PROP_AEB_MODE      : VPM, ID_AAD_AUTO_EME_BREAK,            1=Alerte / 2=Alerte+Freinage
    // Le smali vehiclesettings écrit toujours les deux simultanément via setIntPropertyRecovery
    private const val PROP_AEB_SYS_MODE    = 0x302000a  // ID_AAD_FRONT_COLLISION_ASST_SYS (VPM)
    private const val PROP_AEB_MODE        = 0x302000b  // ID_AAD_AUTO_EME_BREAK (VPM)
    // PROP_AEB_SENSITIVITY : VPM, ForwardCollisionAsstSentItem, 1=Faible / 2=Standard / 3=Élevé
    private const val PROP_AEB_SENSITIVITY = 0x302000e  // ID_AAD_FRONT_COLLISION_ASST_SEN (VPM)

    // TSR — Reconnaissance des panneaux de vitesse (SLIF Warning)
    // SWI133 : VPM toggle 0/1 ; SWI68/SWI165 : VSM setSpeedAsstSlifWarning ; SWI69/SWI131 : VSM setSLIFWarningState (inversé)
    private const val PROP_TSR_MODE = 0x5030049  // ID_AAD_SLIF_WARNING

    // Économie d'énergie (Endurance Mode / Longer Endurance)
    // SWI133 : VPM PROP_ENERGY_SAVING ; SWI69/SWI131 : VSM setEnduranceMode ; SWI68/SWI165 : VSM setLongerEndurance
    private const val PROP_ENERGY_SAVING = 0x5030007  // ID_LONGER_ENDURANCE_MODE

    // SWI68 : VehicleSettingManager class name (loaded via launcher context)
    private const val VSM_CLASS      = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    private const val LAUNCHER68_PKG = "com.saicmotor.hmi.launcher"

    // Luminosité écran — ancien SDK (SWI133/68/165) : GeneralManager.setBrightness(Int)/getBrightness().
    // Plage native 0..255 (constantes DARKEST_VALUE=0x0 / BRIGHTEST_VALUE=0xff confirmées dans le smali).
    // A9 (SWI132/131/69) = phase 2 (setScreenBrightness(III), params non décodables sans SystemUI).
    private const val GENERAL_MANAGER_CLASS = "com.saicmotor.sdk.systemsettings.GeneralManager"
    private const val BRIGHTNESS_NATIVE_MAX = 255

    // Volume média — ancien SDK (SWI133/68/165) : SmartSoundManager.getVolume/setVolume/getMaxVolume(type).
    // Même SDK systemsettings/BaseManager que GeneralManager (singleton sInstance + init(Context, listener)).
    private const val SMART_SOUND_MANAGER_CLASS = "com.saicmotor.sdk.systemsettings.SmartSoundManager"
    private const val BRIGHTNESS_MIN_PERCENT = 5   // plancher de sécurité : ne jamais éteindre l'écran

    // SWI69/SWI131 : accès via CarAdapterClient → queryClient(0x8) → CarVehicleSettingClient
    // Architecture réelle : CarAdapterClient se connecte à com.saicmotor.caradapter.CarAdapterService,
    // puis queryClient(code) retourne l'IBinder pour chaque service.
    // Code 0x8 = CarVehicleSettingClient (vérifié dans VehicleSettingService.onResult() smali)
    private const val LAUNCHER69_PKG      = "com.saicmotor.launcher"
    private const val CAR_ADAPTER_CLASS   = "com.saicmotor.carapi.CarAdapterClient"
    private const val VSM69_CLIENT_CLASS  = "com.saicmotor.carapi.client.CarVehicleSettingClient"
    private const val VSM_SERVICE_CODE    = 0x8   // queryClient(0x8) → ICarVehicleSettingService
    private const val VEHICLE_SETTING_PKG = "com.saicmotor.vehiclesetting"  // SWI131 : carapi dans VS

    // Katman5 — VehicleConditionManager (IVehicleConditionService via IHubService "vehiclecondition")
    private const val VCM_CLASS          = "com.saicmotor.sdk.vehiclesettings.manager.VehicleConditionManager"
    private const val VCM_LISTENER_CLASS = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionListener"

    // Katman5 SWI69/SWI131 — ICarGeneralService via CarAdapterClient (queryClient(0x1))
    private const val CAR_GENERAL_CLIENT_CLASS = "com.saicmotor.carapi.client.CarGeneralClient"
    private const val BIND_CODE_CAR_GENERAL    = 0x1   // ICarAdapterService.queryClient(0x1)

    // Standard AAOS — VehicleProperty.IGNITION_STATE (compatible tous firmwares via CarPropertyManager)
    private const val PROP_IGNITION_STATE = 0x11400409

    // Standard AAOS — VehicleProperty.PERF_VEHICLE_SPEED (float, m/s). Base du verrou
    // d'écriture à 0 km/h (voir VehicleWriteGate).
    private const val PROP_VEHICLE_SPEED = 0x11600207

    // Standard AAOS — VehicleProperty.ENV_OUTSIDE_TEMPERATURE (float, °C).
    // NON VÉRIFIÉE sur véhicule : tous les firmwares MG4 n'exposent pas cette propriété.
    // Les appelants doivent traiter null comme « donnée indisponible », pas comme 0 °C.
    private const val PROP_OUTSIDE_TEMP = 0x11600703

    /** Valeurs de IGNITION_STATE (VehicleIgnitionState) — propriété AAOS standard. */
    object IgnitionState {
        const val UNDEFINED = 0
        const val LOCK      = 1
        const val OFF       = 2
        const val ACC       = 3
        const val ON        = 4   // Clé détectée + frein appuyé = état READY
        const val START     = 5
    }

    /**
     * Valeurs retournées par IVehicleConditionService.getVehicleIgnition() (Katman5).
     * Source : VehicleConditionConst.smali + CarIgnitionItem.smali (SWI133 launcher).
     */
    object CarIgnitionItem {
        const val OFF       = 0x0   // Voiture éteinte
        const val ACCESSORY = 0x1   // Accessoires uniquement
        const val RUN       = 0x2   // Clé ON / état READY
        const val CRANK     = 0x3   // Démarrage
    }

    /**
     * Limiteur de vitesse (Speed Assist System / SAS) — réglage INDÉPENDANT du mode ACC/TJA.
     * Sur SWI132 il est piloté par setSasMode (et NON par setAccTjaState/SHWA, qui ne l'active pas).
     * Valeurs confirmées dans le smali SWI132 (SasModel / sas_modes) :
     *   0 = Désactivé, 2 = Manuel, 3 = Intelligent  (1 = avert. vitesse, modèles TW uniquement)
     */
    object SasMode {
        const val OFF         = 0
        const val MANUEL      = 2
        const val INTELLIGENT = 3
    }

    /** Valeurs de mode ADAS pour firmware SWI68/SWI132 (CarAccTja constants). */
    object Swi68Mode {
        const val OFF  = 0x4   // Désactiver
        const val SHWA = 0x3   // Speed Limit Mode (Limiteur) — SWI132 uniquement
        const val ACC  = 0x1   // ACC
        const val TJA  = 0x2   // TJA (Traffic Jam Assist) = ICA dans l'UI SWI132
    }

    /** Valeurs du mode AEB (communes SWI133 + SWI68). */
    object AebMode {
        const val ALARM       = 1   // Alerte seule (FCW)
        const val ALARM_BRAKE = 2   // Alerte + Freinage automatique d'urgence
    }

    /** Valeurs de sensibilité AEB — SWI133 uniquement (PROP_AEB_SENSITIVITY = 0x302000e). */
    object AebSensitivity {
        const val LOW      = 1   // Faible
        const val STANDARD = 2   // Standard
        const val HIGH     = 3   // Élevé
    }

    @Volatile private var sAppContext: Context? = null
    @Volatile private var sCar: Any? = null
    @Volatile private var sCarPropertyManager: Any? = null
    @Volatile private var sCarHvacManager: Any? = null
    @Volatile private var sVehicleBinder: IBinder? = null
    @Volatile private var sVpm: Any? = null          // VehiclePropertyManager instance (SWI133, Katman4)
    @Volatile private var sVpmService: Any? = null   // mIVehiclePropertyService field value (SWI133)
    @Volatile private var sVsm: Any? = null          // VehicleSettingManager instance (SWI68, Katman4)
    @Volatile private var sVsmService: Any? = null   // mVehicleSettingService field value (SWI68)
    @Volatile private var sVsm133: Any? = null       // VehicleSettingManager instance (SWI133, pour ELK)
    @Volatile private var sGeneral: Any? = null      // GeneralManager instance (SWI133/68/165, luminosité)
    @Volatile private var sSmartSound: Any? = null   // SmartSoundManager instance (SWI133/68/165, loudness)
    @Volatile private var sCarGeneral: Any? = null   // CarGeneralClient instance (A9 SWI132/131/69, luminosité)
    @Volatile private var sInitialized = false
    @Volatile private var sCarBindAttempted = false
    @Volatile var logEnabled = true

    @Volatile private var sDriveModeListener: DriveModeListener? = null
    @Volatile private var sHvacListener: HvacListener? = null

    // ── Katman5 — IGNITION_STATE via CarPropertyManager (standard AAOS) ──────
    @Volatile private var sIgnitionCallbackProxy: Any? = null
    @Volatile private var sIgnitionCallbackRegistered = false
    private val ignitionCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    // ── Katman5 — VehicleConditionManager / ICarGeneralService ───────────────
    @Volatile private var sVcm: Any? = null
    @Volatile private var sVcmListener: Any? = null
    @Volatile private var sVcmCallbackRegistered = false
    @Volatile private var sLastVcmIgnitionState = -1   // filtre les faux RUN répétés
    private val vehicleConditionCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()
    private val katman5ReadyListeners     = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Listeners notifiés dès que Katman1 (CPM + HVAC) est opérationnel. */
    private val katman1ReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Listeners notifiés dès que Katman4 (mIVehiclePropertyService) est opérationnel. */
    private val katman4ReadyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Exécute [action] dès que CarPropertyManager et CarHvacManager sont disponibles.
     * Si déjà prêt, exécution immédiate. Sinon, mis en file et déclenché à la connexion.
     */
    fun whenKatman1Ready(action: () -> Unit) {
        if (sCarPropertyManager != null && sCarHvacManager != null) {
            action()
        } else {
            katman1ReadyListeners.add(action)
        }
    }

    /**
     * Exécute [action] dès que le service ADAS (Katman4) est disponible.
     * SWI133 → mIVehiclePropertyService ; SWI68/SWI69/SWI131 → mVehicleSettingService
     */
    fun whenKatman4Ready(action: () -> Unit) {
        val ready = if (FirmwareInfo.isVsmBased()) sVsmService != null else sVpmService != null
        if (ready) action() else katman4ReadyListeners.add(action)
    }

    /** Exécute [action] dès que Katman5 (IVehicleConditionService) est opérationnel. */
    fun whenKatman5Ready(action: () -> Unit) {
        if (sVcmCallbackRegistered) action() else katman5ReadyListeners.add(action)
    }

    /** Enregistre un callback invoqué à chaque changement d'état d'allumage (CarIgnitionItem). */
    fun registerVehicleConditionListener(callback: (Int) -> Unit) {
        vehicleConditionCallbacks.add(callback)
    }

    fun unregisterVehicleConditionListener(callback: (Int) -> Unit) {
        vehicleConditionCallbacks.remove(callback)
    }

    /** Enregistre un callback sur IGNITION_STATE via CarPropertyManager (standard AAOS). */
    fun registerIgnitionCallback(callback: (Int) -> Unit) {
        ignitionCallbacks.add(callback)
        if (sCarPropertyManager != null) registerIgnitionPropertyCallback()
    }

    fun unregisterIgnitionCallback(callback: (Int) -> Unit) {
        ignitionCallbacks.remove(callback)
    }

    /** Désenregistre le proxy CarPropertyManager (appeler depuis Service.onDestroy). */
    fun unregisterIgnitionPropertyCallback() {
        val cpm = sCarPropertyManager ?: return
        val proxy = sIgnitionCallbackProxy ?: return
        try {
            val m = cpm.javaClass.methods.firstOrNull {
                it.name == "unregisterCallback" && it.parameterCount == 1
            } ?: return
            m.invoke(cpm, proxy)
            sIgnitionCallbackProxy = null
            sIgnitionCallbackRegistered = false
            AppLogger.i(TAG, "  IGNITION_STATE callback unregistered ✓")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  IGNITION: unregisterCallback error: ${e.message}")
        }
    }

    /**
     * Lit l'état d'allumage courant via CarPropertyManager.
     * Retourne -1 si CPM non prêt, 0 si propriété non supportée.
     */
    fun getCurrentIgnitionState(): Int {
        val v0 = getIntPropertyCPM(PROP_IGNITION_STATE, 0)
        if (v0 > 0) return v0
        return getIntPropertyCPM(PROP_IGNITION_STATE, AREA_GLOBAL)
    }

    /**
     * Vitesse véhicule en km/h, ou null si elle ne peut pas être lue (CPM non prêt,
     * propriété non supportée, exception). [VehicleWriteGate] traite null comme un refus.
     */
    fun getVehicleSpeedKmh(): Float? {
        val mps = getFloatPropertyCPM(PROP_VEHICLE_SPEED, AREA_GLOBAL)
            ?: getFloatPropertyCPM(PROP_VEHICLE_SPEED, 0)
            ?: return null
        // PERF_VEHICLE_SPEED est signée (négative en marche arrière) : c'est la vitesse
        // absolue qui compte pour savoir si le véhicule bouge.
        return kotlin.math.abs(mps) * 3.6f
    }

    /**
     * Température extérieure en °C, ou null si illisible (CPM non prêt, propriété non
     * supportée par le firmware, exception).
     *
     * Le null est significatif : il veut dire « on ne sait pas », pas « il fait 0 °C ».
     * Une règle météo qui reçoit null ne doit PAS se déclencher.
     */
    fun getOutsideTempCelsius(): Float? =
        getFloatPropertyCPM(PROP_OUTSIDE_TEMP, AREA_GLOBAL)
            ?: getFloatPropertyCPM(PROP_OUTSIDE_TEMP, 0)

    // -------------------------------------------------------------------------
    // Climate + windows — READ ONLY, UNVERIFIED on MG4 firmware.
    //
    // Standard AOSP VehicleProperty IDs. The R69 OEM sources expose these names, but the
    // numeric ids and area ids are not confirmed against any MG4 generation. These reads
    // exist so the MG4Tasker diagnostic screen can show whether a signal is actually
    // readable BEFORE any write path is added. Every method returns null when unreadable —
    // callers must treat null as "unknown", never as a value. No write counterpart exists
    // on purpose: writing a wrong id to the vehicle is exactly the risk being deferred.
    // -------------------------------------------------------------------------

    private const val PROP_HVAC_AC_ON          = 0x15200505  // HVAC_AC_ON (bool)
    private const val PROP_HVAC_AUTO_ON        = 0x1520050A  // HVAC_AUTO_ON (bool)
    private const val PROP_HVAC_RECIRC_ON      = 0x15200508  // HVAC_RECIRC_ON (bool)
    private const val PROP_HVAC_FAN_SPEED      = 0x15400500  // HVAC_FAN_SPEED (int)
    private const val PROP_HVAC_TEMPERATURE_SET = 0x15600503 // HVAC_TEMPERATURE_SET (float °C)
    private const val PROP_WINDOW_POS          = 0x13340BC0  // WINDOW_POS (int, per window area)

    // Candidate HVAC area ids: the seat-heat area used elsewhere, plus GLOBAL and 0.
    private val HVAC_AREA_CANDIDATES = intArrayOf(AREA_HVAC, AREA_GLOBAL, 0)
    // VehicleAreaWindow bits: front windshield + the four door windows.
    private val WINDOW_AREA_CANDIDATES = intArrayOf(0x0001, 0x0010, 0x0040, 0x0100, 0x0400)

    fun getAcOn(): Boolean?          = readHvacBool(PROP_HVAC_AC_ON)
    fun getHvacAutoOn(): Boolean?    = readHvacBool(PROP_HVAC_AUTO_ON)
    fun getRecircOn(): Boolean?      = readHvacBool(PROP_HVAC_RECIRC_ON)
    fun getFanSpeed(): Int?          = readHvacInt(PROP_HVAC_FAN_SPEED)
    fun getTemperatureSetCelsius(): Float? = readHvacFloat(PROP_HVAC_TEMPERATURE_SET)

    /** True if any door/windshield window reads as not fully closed; null if none readable. */
    fun isAnyWindowOpen(): Boolean? {
        var readAny = false
        for (area in WINDOW_AREA_CANDIDATES) {
            val pos = readWindowArea(area) ?: continue
            readAny = true
            if (pos > 0) return true
        }
        return if (readAny) false else null
    }

    private fun readHvacBool(propId: Int): Boolean? = readHvacInt(propId)?.let { it != 0 }

    /** Sweeps HVAC-manager then CPM across the candidate areas; null when nothing answers. */
    private fun readHvacInt(propId: Int): Int? {
        for (area in HVAC_AREA_CANDIDATES) {
            getIntPropertyHvac(propId, area).takeIf { it >= 0 }?.let { return it }
            getIntPropertyCPM(propId, area).takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    private fun readHvacFloat(propId: Int): Float? {
        val hvac = sCarHvacManager
        for (area in HVAC_AREA_CANDIDATES) {
            if (hvac != null) {
                runCatching {
                    hvac.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                        .invoke(hvac, propId, area) as? Float
                }.getOrNull()?.let { return it }
            }
            getFloatPropertyCPM(propId, area)?.let { return it }
        }
        return null
    }

    private fun readWindowArea(area: Int): Int? =
        getIntPropertyCPM(PROP_WINDOW_POS, area).takeIf { it >= 0 }

    /** Contexte applicatif, pour les messages utilisateur du verrou d'écriture. */
    internal fun appContext(): Context? = sAppContext

    interface DriveModeListener { fun onDriveModeChanged(mode: DriveMode) }
    interface HvacListener {
        fun onSeatHeatChanged(left: Int, right: Int)
        fun onSteeringHeatChanged(on: Boolean)
    }

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        if (sInitialized) return
        sInitialized = true
        sAppContext = context.applicationContext
        AppLogger.i(TAG, "=== MG4Hardware.init() === uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.DEVICE}")
        bindCarService(context)
        sVehicleBinder = getBinderService("vehiclesetting")
        when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68  -> initKatman4Swi68(context)
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI165 -> initKatman4Swi68(context)  // même SDK que SWI68
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> initKatman4Swi69(context)  // CarVehicleSettingClient, même path que SWI69
            FirmwareInfo.isNewGenVsm()                              -> initKatman4Swi69(context)   // SWI69 + SWI131
            else                                                    -> initKatman4(context)
        }
        // Katman5 — détection IGNITION_STATE push (VehicleConditionManager ou ICarGeneralService)
        // SWI132 utilise ICarGeneralService (même path que SWI69/SWI131) — VehicleConditionManager absent de son smali
        if (FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132)
            initKatman5Swi69(context)
        else
            initKatman5(context)
        if (sVehicleBinder != null)
            AppLogger.i(TAG, "  ✓ Katman2: vehiclesetting binder OK")
        else
            AppLogger.w(TAG, "  ✗ Katman2: vehiclesetting null (SELinux — expected)")

        // Démarrage auto du watcher porte au boot si la feature est activée (tous firmwares).
        startDoorWatcherIfEnabled()
        // Connexion Car établie même si la feature est OFF → la sonde Diagnostic peut lire les portes.
        if (hasDoorVolumeFeature()) connectCarProperty()

        AppLogger.i(TAG, "========================================")
    }

    // -------------------------------------------------------------------------
    // Katman1 — android.car.Car (async, mirrors original bindCarService exactly)
    // -------------------------------------------------------------------------

    private fun bindCarService(context: Context) {
        if (sCarBindAttempted) return
        sCarBindAttempted = true
        val carClass: Class<*>
        try {
            carClass = Class.forName("android.car.Car")
            AppLogger.i(TAG, "  Katman1: android.car.Car class found ✓")
        } catch (e: ClassNotFoundException) {
            AppLogger.w(TAG, "  Katman1: android.car.Car not found — not Automotive?")
            return
        } catch (e: Exception) {
            AppLogger.e(TAG, "  Katman1: forName error: ${e.message}")
            return
        }

        var car: Any? = null

        // Attempt 1: createCar(Context)
        try {
            car = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context) → success")
        } catch (_: Exception) {}

        // Attempt 2: createCar(Context, Handler)
        if (car == null) {
            try {
                car = carClass.getMethod("createCar", Context::class.java, Handler::class.java)
                    .invoke(null, context, null)
                if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context, Handler) → success")
            } catch (_: Exception) {}
        }

        // Attempt 3: createCar(Context, ServiceConnection) — async, callback fires when connected
        var scMethodFound: java.lang.reflect.Method? = null
        try {
            scMethodFound = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
            AppLogger.i(TAG, "  Katman1: createCar(Context, ServiceConnection) method found")
        } catch (_: Exception) {}

        if (car == null && scMethodFound != null) {
            try {
                val sc = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        AppLogger.i(TAG, "  Katman1: ServiceConnection.onServiceConnected")
                        tryGetManagersFromCar(carClass)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        AppLogger.w(TAG, "  Katman1: Car service disconnected")
                        sCarPropertyManager = null
                        sCarHvacManager = null
                    }
                }
                car = scMethodFound.invoke(null, context, sc)
                if (car != null) AppLogger.i(TAG, "  Katman1: createCar(Context, SC) → callback pending")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman1: createCar(Context, SC) error: ${e.message}")
            }
        }

        if (car == null) {
            AppLogger.e(TAG, "  Katman1: all createCar methods failed")
            return
        }

        sCar = car

        // Call car.connect() if available (required on older builds)
        try {
            carClass.getMethod("connect").invoke(car)
            AppLogger.i(TAG, "  Katman1: car.connect() called")
        } catch (_: NoSuchMethodException) {
            // connect() not present on all builds, ignore
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman1: car.connect() error: ${e.message}")
        }

        // Try sync managers immediately
        tryGetManagersFromCar(carClass)

        // Schedule retries — délais étendus pour couvrir le boot lent du Car service SAIC
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 2_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 5_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 10_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 20_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 40_000)
        h.postDelayed({ tryGetManagersFromCar(carClass) }, 60_000)
    }

    private fun tryGetManagersFromCar(carClass: Class<*>) {
        val car = sCar ?: return
        if (sCarPropertyManager != null && sCarHvacManager != null) return // already done
        try {
            val connected = try {
                (carClass.getMethod("isConnected").invoke(car) as? Boolean) ?: true
            } catch (_: Exception) { true }

            AppLogger.i(TAG, "  Katman1: isConnected() → $connected")
            if (!connected) {
                AppLogger.w(TAG, "  Katman1: car not yet connected")
                return
            }

            val getCarManager = carClass.getMethod("getCarManager", String::class.java)

            if (sCarPropertyManager == null) {
                try {
                    val svc = carClass.getField("PROPERTY_SERVICE").get(null) as String
                    sCarPropertyManager = getCarManager.invoke(car, svc)
                    AppLogger.i(TAG, "  Katman1: CarPropertyManager READY ✓")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "  Katman1: CarPropertyManager unavailable: ${e.message}")
                }
            }

            if (sCarHvacManager == null) {
                try {
                    val svc = carClass.getField("HVAC_SERVICE").get(null) as String
                    sCarHvacManager = getCarManager.invoke(car, svc)
                    AppLogger.i(TAG, "  Katman1: CarHvacManager READY ✓")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "  Katman1: CarHvacManager unavailable: ${e.message}")
                }
            }

            // Notifier les abonnés whenKatman1Ready dès que les deux managers sont prêts
            if (sCarPropertyManager != null && sCarHvacManager != null && katman1ReadyListeners.isNotEmpty()) {
                val toNotify = katman1ReadyListeners.toList()
                katman1ReadyListeners.clear()
                Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
            }

            // Tentative d'enregistrement du callback IGNITION_STATE (best-effort)
            if (sCarPropertyManager != null) registerIgnitionPropertyCallback()
        } catch (e: Exception) {
            AppLogger.e(TAG, "  Katman1: tryGetManagersFromCar error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Katman2 — ServiceManager raw binder
    // -------------------------------------------------------------------------

    private fun getBinderService(serviceName: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val method = sm.getMethod("getService", String::class.java)
            (method.invoke(null, serviceName) as? IBinder).also {
                AppLogger.d(TAG, "getService($serviceName) → $it")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getService($serviceName) error: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Katman4 — VehiclePropertyManager via saicmotor.hmi.launcher context
    // -------------------------------------------------------------------------

    private fun initKatman4(context: Context) {
        if (sVpm != null) return
        val launcherCtx: Context
        val vpmClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            vpmClass = launcherCtx.classLoader
                .loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman4: package/class error: ${e.message} — will retry")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context.applicationContext) }, 5_000)
            return
        }

        var vpm: Any? = null

        // ---- Constructeurs (priorité) ----
        // 1) ctor(launcherCtx) — most likely for a class bundled in the launcher APK
        if (vpm == null) vpm = tryInvoke("ctor(launcherCtx)") {
            vpmClass.getConstructor(Context::class.java).newInstance(launcherCtx)
        }
        // 2) ctor(appCtx)
        if (vpm == null) vpm = tryInvoke("ctor(appCtx)") {
            vpmClass.getConstructor(Context::class.java).newInstance(context)
        }
        // 3) ctor() no-arg
        if (vpm == null) vpm = tryInvoke("ctor()") {
            @Suppress("DEPRECATION") vpmClass.newInstance()
        }

        // ---- Méthodes statiques de factory ----
        if (vpm == null) vpm = tryInvoke("getInstance(launcherCtx)") {
            vpmClass.getMethod("getInstance", Context::class.java).invoke(null, launcherCtx)
        }
        if (vpm == null) vpm = tryInvoke("getInstance(appCtx)") {
            vpmClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        }
        if (vpm == null) vpm = tryInvoke("getInstance()") {
            vpmClass.getMethod("getInstance").invoke(null)
        }

        if (vpm == null) {
            AppLogger.w(TAG, "  Katman4: toutes les tentatives ont échoué — will retry")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context.applicationContext) }, 10_000)
            return
        }

        sVpm = vpm

        // 1) bindService() — connecte au service véhicule (async)
        tryInvoke("vpm.bindService()") { vpm!!.javaClass.getMethod("bindService").invoke(vpm) }

        // 2) init(Context, IVehicleServiceListener) via dynamic proxy — reçoit onServiceConnected
        initWithServiceListener(vpm!!, context, launcherCtx)

        // 3) VehicleSettingManager pour SWI133 (ELK) — même singleton que SWI68
        tryInitVsm133(launcherCtx, context)

        // 3b) GeneralManager pour SWI133 (luminosité écran)
        tryInitGeneralManager(launcherCtx, context)

        // 3c) SmartSoundManager pour SWI133 (loudness audio)
        tryInitSmartSoundManager(launcherCtx, context)

        // 4) Retries pour récupérer mIVehiclePropertyService et VSM133 une fois le service connecté
        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (sVpmService == null) tryGetVpmService(sVpm ?: return@postDelayed)
                if (sVsm133 == null) tryInitVsm133(launcherCtx, context)
                if (sGeneral == null) tryInitGeneralManager(launcherCtx, context)
                if (sSmartSound == null) tryInitSmartSoundManager(launcherCtx, context)
                if (doorVolumeEnabled() && sCarPropMgr == null) startDoorVolumeWatcher()
            }, delay)
        }

        tryGetVpmService(vpm!!)
        AppLogger.i(TAG, "  Katman4: VPM prêt — mIVehiclePropertyService=${if (sVpmService != null) "OK ✓" else "null (en attente)"}")
    }

    private fun initWithServiceListener(vpm: Any, context: Context, launcherCtx: Context) {
        val vpmClass = vpm.javaClass

        // Log all methods for diagnostics
        val methodSummary = vpmClass.methods.joinToString(", ") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }
        AppLogger.d(TAG, "  Katman4: VPM methods = $methodSummary")

        // Strategy 1: Inspect the actual init() signature to get the real listener type
        val initMethod2 = vpmClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }

        if (initMethod2 != null) {
            val listenerType = initMethod2.parameterTypes[1]
            AppLogger.i(TAG, "  Katman4: init() trouvé, listener type = ${listenerType.name}")

            // Try dynamic proxy with the actual listener interface type
            if (listenerType.isInterface) {
                try {
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.classLoader, arrayOf(listenerType)
                    ) { _, method, _ ->
                        when (method.name) {
                            "onServiceConnected" -> {
                                AppLogger.i(TAG, "  Katman4: onServiceConnected ✓")
                                tryGetVpmService(vpm)
                            }
                            "onServiceDisconnected" -> {
                                AppLogger.w(TAG, "  Katman4: onServiceDisconnected")
                                sVpmService = null
                            }
                            else -> {}
                        }
                        null
                    }
                    initMethod2.invoke(vpm, context, proxy)
                    AppLogger.i(TAG, "  Katman4: init(Context, proxy) ✓")
                    return
                } catch (e: Exception) {
                    AppLogger.d(TAG, "  Katman4: init(Context, proxy) failed: ${e.message}")
                }
            }

            // Fallback: try init(Context, null) — works if listener is nullable
            try {
                initMethod2.invoke(vpm, context, null)
                AppLogger.i(TAG, "  Katman4: init(Context, null) ✓")
                return
            } catch (e: Exception) {
                AppLogger.d(TAG, "  Katman4: init(Context, null) failed: ${e.message}")
            }
        }

        // Strategy 2: init(Context) single-param
        try {
            vpmClass.getMethod("init", Context::class.java).invoke(vpm, context)
            AppLogger.i(TAG, "  Katman4: init(Context) ✓")
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: init(Context) failed: ${e.message}")
        }

        // Strategy 3: init() no-arg
        try {
            vpmClass.getMethod("init").invoke(vpm)
            AppLogger.i(TAG, "  Katman4: init() ✓")
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: init() failed: ${e.message}")
        }

        AppLogger.w(TAG, "  Katman4: aucun init() fonctionnel — mIVehiclePropertyService restera null")
    }

    /** Exécute [block], retourne le résultat ou null, log le résultat/erreur. */
    private fun tryInvoke(label: String, block: () -> Any?): Any? = try {
        val r = block()
        AppLogger.i(TAG, "  Katman4: $label → ${if (r != null) "OK ($r)" else "null"}")
        r
    } catch (e: Exception) {
        AppLogger.d(TAG, "  Katman4: $label → ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun tryGetVpmService(vpm: Any) {
        if (sVpmService != null) return
        for (cls in generateSequence<Class<*>>(vpm.javaClass) { it.superclass }) {
            try {
                val f = cls.getDeclaredField("mIVehiclePropertyService")
                f.isAccessible = true
                val svc = f.get(vpm)
                if (svc != null) {
                    sVpmService = svc
                    AppLogger.i(TAG, "  Katman4: mIVehiclePropertyService READY ✓")
                    // Notify any pending Katman4 listeners
                    val toNotify = katman4ReadyListeners.toList()
                    katman4ReadyListeners.clear()
                    Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
                }
                return
            } catch (_: NoSuchFieldException) { continue } catch (_: Exception) { return }
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // SWI133 — VehicleSettingManager (ELK : getLaneKeepingAsstMode / setLaneKeepingAsstMode)
    // Même singleton que SWI68 mais initialisé dans le chemin SWI133.
    // -------------------------------------------------------------------------

    private fun tryInitVsm133(launcherCtx: Context, appCtx: Context) {
        if (sVsm133 != null) return
        try {
            val vsmClass = launcherCtx.classLoader.loadClass(VSM_CLASS)

            // Tentative 1 : lire le singleton déjà initialisé par le launcher
            val f = vsmClass.getDeclaredField("sVehicleSettingManager")
            f.isAccessible = true
            val singleton = f.get(null)
            if (singleton != null) {
                sVsm133 = singleton
                AppLogger.i(TAG, "  SWI133: VehicleSettingManager singleton ✓")
                return
            }

            // Tentative 2 : appeler init() nous-mêmes (comme SWI68)
            val initMethod = vsmClass.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 2 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AppLogger.w(TAG, "  SWI133: VSM init() non trouvé, singleton sera null")
                return
            }
            val listenerType = initMethod.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        AppLogger.i(TAG, "  SWI133: VSM onServiceConnected ✓")
                        try {
                            val f2 = vsmClass.getDeclaredField("sVehicleSettingManager")
                            f2.isAccessible = true
                            sVsm133 = f2.get(null)
                            AppLogger.i(TAG, "  SWI133: sVsm133 = ${if (sVsm133 != null) "OK ✓" else "null"}")
                        } catch (_: Exception) {}
                    }
                    null
                }
            } else null
            initMethod.invoke(null, appCtx, listener)
            AppLogger.i(TAG, "  SWI133: VehicleSettingManager.init() called")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI133: tryInitVsm133 exc: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Luminosité écran — GeneralManager (ancien SDK SWI133/68/165)
    // GeneralManager.init(Context, ISettingsServiceListener) — singleton sInstance.
    // Même pattern que tryInitVsm133 ; chargé depuis le launcher com.saicmotor.hmi.launcher.
    // -------------------------------------------------------------------------

    private fun tryInitGeneralManager(launcherCtx: Context, appCtx: Context) {
        if (sGeneral != null) return
        try {
            val cls = launcherCtx.classLoader.loadClass(GENERAL_MANAGER_CLASS)

            // Tentative 1 : singleton déjà initialisé
            val f = cls.getDeclaredField("sInstance")
            f.isAccessible = true
            f.get(null)?.let {
                sGeneral = it
                AppLogger.i(TAG, "  GeneralManager singleton ✓")
                return
            }

            // Tentative 2 : init(Context, ISettingsServiceListener) — proxy dynamique
            val initMethod = cls.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 2 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AppLogger.w(TAG, "  GeneralManager init() non trouvé")
                return
            }
            val listenerType = initMethod.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        try {
                            f.get(null)?.let { sGeneral = it }
                            AppLogger.i(TAG, "  GeneralManager onServiceConnected — sGeneral=${if (sGeneral != null) "OK ✓" else "null"}")
                        } catch (_: Exception) {}
                    }
                    null
                }
            } else null
            initMethod.invoke(null, appCtx, listener)
            // init() crée sInstance immédiatement (service connecté de façon asynchrone ensuite)
            f.get(null)?.let { sGeneral = it }
            AppLogger.i(TAG, "  GeneralManager.init() called — sGeneral=${if (sGeneral != null) "OK ✓" else "null"}")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  tryInitGeneralManager exc: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Loudness — SmartSoundManager (ancien SDK SWI133/68/165)
    // Même SDK/pattern que GeneralManager : singleton sInstance + init(Context, ISettingsServiceListener).
    // -------------------------------------------------------------------------

    private fun tryInitSmartSoundManager(launcherCtx: Context, appCtx: Context) {
        if (sSmartSound != null) return
        try {
            val cls = launcherCtx.classLoader.loadClass(SMART_SOUND_MANAGER_CLASS)

            // Tentative 1 : singleton déjà initialisé
            val f = cls.getDeclaredField("sInstance")
            f.isAccessible = true
            f.get(null)?.let {
                sSmartSound = it
                AppLogger.i(TAG, "  SmartSoundManager singleton ✓")
                return
            }

            // Tentative 2 : init(Context, ISettingsServiceListener) — proxy dynamique
            val initMethod = cls.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 2 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AppLogger.w(TAG, "  SmartSoundManager init() non trouvé")
                return
            }
            val listenerType = initMethod.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        try {
                            f.get(null)?.let { sSmartSound = it }
                            AppLogger.i(TAG, "  SmartSoundManager onServiceConnected — sSmartSound=${if (sSmartSound != null) "OK ✓" else "null"}")
                        } catch (_: Exception) {}
                    }
                    null
                }
            } else null
            initMethod.invoke(null, appCtx, listener)
            f.get(null)?.let { sSmartSound = it }
            AppLogger.i(TAG, "  SmartSoundManager.init() called — sSmartSound=${if (sSmartSound != null) "OK ✓" else "null"}")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  tryInitSmartSoundManager exc: ${e.message}")
        }
    }

    /** A9 (SWI132/131/69) : luminosité via CarGeneralClient.setScreenBrightness(mode,day,night). */
    private fun isA9Brightness(): Boolean =
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132

    /** Luminosité écran disponible : ancien SDK (133/68/165) ou A9 (132/131/69). */
    fun hasBrightnessControl(): Boolean = FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN

    /** Lit la luminosité écran en % (0–100), ou -1 si indisponible. */
    fun getScreenBrightnessPercent(): Int =
        if (isA9Brightness()) getBrightnessA9() else getBrightnessOldSdk()

    /**
     * Règle la luminosité écran en % (0–100). Plancher de sécurité à BRIGHTNESS_MIN_PERCENT
     * pour ne jamais éteindre l'écran.
     */
    fun setScreenBrightnessPercent(pct: Int): Boolean {
        val clamped = pct.coerceIn(BRIGHTNESS_MIN_PERCENT, 100)
        return if (isA9Brightness()) setBrightnessA9(clamped) else setBrightnessOldSdk(clamped)
    }

    // ── Ancien SDK (SWI133/68/165) — GeneralManager.setBrightness(Int), plage native 0..255 ──

    private fun getBrightnessOldSdk(): Int {
        val g = sGeneral ?: return -1
        return try {
            val native = (g.javaClass.getMethod("getBrightness").invoke(g) as? Int) ?: return -1
            if (native < 0) return -1
            val pct = (native * 100 / BRIGHTNESS_NATIVE_MAX).coerceIn(0, 100)
            AppLogger.d(TAG, "  getBrightness native=$native → $pct%")
            pct
        } catch (e: Exception) {
            AppLogger.w(TAG, "  getBrightness exc: ${e.message}")
            -1
        }
    }

    private fun setBrightnessOldSdk(clampedPct: Int): Boolean {
        val g = sGeneral ?: return false
        val native = (clampedPct * BRIGHTNESS_NATIVE_MAX / 100).coerceIn(0, BRIGHTNESS_NATIVE_MAX)
        if (logEnabled) AppLogger.i(TAG, "setBrightness → $clampedPct% (native=$native/255)")
        return try {
            g.javaClass.getMethod("setBrightness", Int::class.javaPrimitiveType).invoke(g, native)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  setBrightness exc: ${e.message}")
            false
        }
    }

    // ── A9 (SWI132/131/69) — luminosité via Settings.System.SCREEN_BRIGHTNESS ──
    // L'app Settings A9 (GeneralModel.setBrightness) pilote réellement la dalle par
    // Settings.System.putInt("screen_brightness", 0..255) + passage en mode manuel.
    // CarGeneralClient.setScreenBrightness(mode,day,night) ne stocke que le jour/nuit
    // et n'a AUCUN effet sur la dalle (confirmé par les logs SWI132 : valeur lue=0,
    // aucun changement visuel). L'app étant uid.system, elle peut écrire Settings.System.
    private const val A9_BRIGHTNESS_NATIVE_MAX = 255

    private fun getBrightnessA9(): Int {
        val resolver = sAppContext?.contentResolver ?: return -1
        val native = try {
            android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  A9 getBrightness Settings.System exc: ${e.message}"); return -1
        }
        if (native < 0) return -1
        return (native * 100 / A9_BRIGHTNESS_NATIVE_MAX).coerceIn(0, 100)
    }

    private fun setBrightnessA9(clampedPct: Int): Boolean {
        val resolver = sAppContext?.contentResolver ?: return false
        val native = (clampedPct.coerceIn(0, 100) * A9_BRIGHTNESS_NATIVE_MAX / 100).coerceIn(1, A9_BRIGHTNESS_NATIVE_MAX)
        return try {
            // Mode manuel, sinon l'auto-luminosité écrase aussitôt la valeur
            android.provider.Settings.System.putInt(resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, native)
            if (logEnabled) AppLogger.i(TAG, "A9 brightness → Settings.System.SCREEN_BRIGHTNESS=$native ($clampedPct%)")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  A9 setBrightness Settings.System exc: ${e.message}")
            false
        }
    }

    /**
     * Appelle une méthode sur sVsm133 par réflexion.
     * Retourne la valeur (Int pour getters, null pour setters void) ou null si erreur.
     */
    private fun callVsm133(methodName: String, vararg args: Any?): Any? {
        val vsm = sVsm133 ?: return null
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            vsm.javaClass.getMethod(methodName, *types).invoke(vsm, *args)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI133/VSM: $methodName() exc: ${e.message}")
            null
        }
    }

    // Katman4 SWI68 — VehicleSettingManager via saicmotor.hmi.launcher context
    // -------------------------------------------------------------------------

    private fun initKatman4Swi68(context: Context) {
        if (sVsm != null) return
        val launcherCtx: Context
        val vsmClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            vsmClass = launcherCtx.classLoader.loadClass(VSM_CLASS)
            AppLogger.i(TAG, "  SWI68: VehicleSettingManager class found ✓")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: class load error: ${e.message} — retry in 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi68(context.applicationContext) }, 5_000)
            return
        }

        // Appel static : VehicleSettingManager.init(Context, IVehicleServiceListener)
        val initMethod = vsmClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }

        if (initMethod != null) {
            val listenerType = initMethod.parameterTypes[1]
            val listenerArg: Any? = if (listenerType.isInterface) {
                try {
                    java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.classLoader, arrayOf(listenerType)
                    ) { _, method, _ ->
                        if (method.name == "onServiceConnected") {
                            AppLogger.i(TAG, "  SWI68: VehicleSettingManager onServiceConnected ✓")
                            sVsm?.let { tryGetVsmService(it, vsmClass) }
                        }
                        null
                    }
                } catch (e: Exception) { null.also { AppLogger.d(TAG, "  SWI68: proxy error: ${e.message}") } }
            } else null

            try {
                initMethod.invoke(null, context, listenerArg)
                AppLogger.i(TAG, "  SWI68: VehicleSettingManager.init() called")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  SWI68: init() error: ${e.message}")
            }
        } else {
            AppLogger.w(TAG, "  SWI68: init(Context, listener) non trouvé")
        }

        // Récupère le singleton depuis le champ statique sVehicleSettingManager
        try {
            val f = vsmClass.getDeclaredField("sVehicleSettingManager")
            f.isAccessible = true
            sVsm = f.get(null)
            AppLogger.i(TAG, "  SWI68: sVehicleSettingManager = ${if (sVsm != null) "OK ✓" else "null"}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: sVehicleSettingManager field error: ${e.message}")
        }

        sVsm?.let { tryGetVsmService(it, vsmClass) }

        // GeneralManager pour SWI68/SWI165 (luminosité écran) — même launcher context
        tryInitGeneralManager(launcherCtx, context)

        // SmartSoundManager pour SWI68/SWI165 (loudness audio) — même launcher context
        tryInitSmartSoundManager(launcherCtx, context)

        // Retries pour récupérer mVehicleSettingService et le singleton si pas encore prêt
        val h = Handler(Looper.getMainLooper())
        listOf(1_000L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L).forEach { delay ->
            h.postDelayed({
                if (sVsm == null) {
                    try {
                        val f = vsmClass.getDeclaredField("sVehicleSettingManager")
                        f.isAccessible = true
                        sVsm = f.get(null)
                        if (sVsm != null) AppLogger.i(TAG, "  SWI68: singleton récupéré @${delay}ms")
                    } catch (_: Exception) {}
                }
                sVsm?.let { if (sVsmService == null) tryGetVsmService(it, vsmClass) }
                if (sGeneral == null) tryInitGeneralManager(launcherCtx, context)
                if (sSmartSound == null) tryInitSmartSoundManager(launcherCtx, context)
            }, delay)
        }
    }

    private fun tryGetVsmService(vsm: Any, vsmClass: Class<*>? = null) {
        if (sVsmService != null) return
        val cls = vsmClass ?: vsm.javaClass
        for (c in generateSequence<Class<*>>(cls) { it.superclass }) {
            try {
                val f = c.getDeclaredField("mVehicleSettingService")
                f.isAccessible = true
                val svc = f.get(vsm)
                if (svc != null) {
                    sVsmService = svc
                    AppLogger.i(TAG, "  SWI68: mVehicleSettingService READY ✓")
                    val toNotify = katman4ReadyListeners.toList()
                    katman4ReadyListeners.clear()
                    Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
                }
                return
            } catch (_: NoSuchFieldException) { continue } catch (_: Exception) { return }
        }
    }

    // -------------------------------------------------------------------------
    // Katman4 SWI69/SWI131 — CarVehicleSettingClient via CarAdapterClient
    //
    // Architecture réelle (vérifiée dans smali) :
    //   CarAdapterClient.getInstance(ctx).start()
    //   → bindService(com.saicmotor.caradapter / CarAdapterService)
    //   → onResult(0=OK) : queryClient(0x8) → IBinder (ICarVehicleSettingService)
    //   → new CarVehicleSettingClient(ibinder)
    //
    // CarVehicleSettingClient expose exactement les mêmes méthodes que VehicleSettingManager
    // (getAccTjaState, setAccTjaState, getLasWarningSound, getFcwState, etc.)
    // -------------------------------------------------------------------------

    private fun initKatman4Swi69(context: Context) {
        if (sVsm != null) return

        val launcherCtx: Context
        val adapterClass: Class<*>
        val clientClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER69_PKG,
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            clientClass  = launcherCtx.classLoader.loadClass(VSM69_CLIENT_CLASS)
            AppLogger.i(TAG, "  SWI69: CarAdapterClient + CarVehicleSettingClient classes found ✓")
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI69: class load error: ${e.message} — retry in 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi69(context.applicationContext) }, 5_000)
            return
        }

        // Obtenir le singleton CarAdapterClient
        val adapter = tryInvoke("SWI69 CarAdapterClient.getInstance(appCtx)") {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
        } ?: tryInvoke("SWI69 CarAdapterClient.getInstance(launcherCtx)") {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, launcherCtx)
        }

        if (adapter == null) {
            AppLogger.w(TAG, "  SWI69: CarAdapterClient.getInstance() failed — retry in 10s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4Swi69(context.applicationContext) }, 10_000)
            return
        }

        // Enregistrer le ServiceConnListener (onResult(0) = connecté)
        val listenerType = adapterClass.declaredClasses
            .firstOrNull { it.simpleName == "ServiceConnListener" }
        if (listenerType != null && listenerType.isInterface) {
            try {
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, method, args ->
                    if (method.name == "onResult") {
                        val code = (args?.getOrNull(0) as? Int) ?: -1
                        AppLogger.i(TAG, "  SWI69: CarAdapterClient.onResult($code)")
                        if (code == 0) tryInitClientFromAdapter(adapter, adapterClass, clientClass)
                    }
                    null
                }
                adapterClass.getMethod("setConnListener", listenerType).invoke(adapter, proxy)
                AppLogger.i(TAG, "  SWI69: ServiceConnListener registered ✓")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  SWI69: setConnListener error: ${e.message}")
            }
        }

        // Démarrer la connexion à CarAdapterService
        tryInvoke("SWI69 adapter.start()") {
            adapterClass.getMethod("start").invoke(adapter)
        }

        // Tentative immédiate si CarAdapterService était déjà connecté
        tryInitClientFromAdapter(adapter, adapterClass, clientClass)

        // Retries échelonnés
        val h = Handler(Looper.getMainLooper())
        listOf(1_000L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (sVsm == null) tryInitClientFromAdapter(adapter, adapterClass, clientClass)
            }, delay)
        }
    }

    /**
     * Tente d'obtenir un CarVehicleSettingClient via queryClient(0x8).
     * Appelée à la connexion (onResult=0) et lors des retries.
     */
    private fun tryInitClientFromAdapter(adapter: Any, adapterClass: Class<*>, clientClass: Class<*>) {
        if (sVsm != null) return
        try {
            val ibinder = adapterClass
                .getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, VSM_SERVICE_CODE) as? IBinder

            if (ibinder == null) {
                AppLogger.d(TAG, "  SWI69: queryClient(0x${VSM_SERVICE_CODE.toString(16)}) → null (pas encore connecté)")
                return
            }

            val client = clientClass
                .getConstructor(IBinder::class.java)
                .newInstance(ibinder)

            sVsm        = client
            sVsmService = ibinder
            AppLogger.i(TAG, "  SWI69: CarVehicleSettingClient READY ✓")

            val toNotify = katman4ReadyListeners.toList()
            katman4ReadyListeners.clear()
            Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI69: tryInitClientFromAdapter error: ${e.message}")
        }
    }

    private fun callVsm(methodName: String, vararg args: Any?): Any? {
        val vsm = sVsm ?: return null
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            vsm.javaClass.getMethod(methodName, *types).invoke(vsm, *args)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  SWI68: $methodName() exc: ${e.message}")
            null
        }
    }

    /**
     * Appelle une méthode void sur sVsm par réflexion.
     * Contrairement à callVsm(), retourne true si la méthode existe et s'exécute sans exception,
     * même si invoke() retourne null (comportement normal pour les méthodes void).
     * Retourne false si sVsm est null ou si une exception est levée (méthode introuvable, etc.).
     */
    private fun callVsmVoid(methodName: String, vararg args: Any?): Boolean {
        // [T-904] Écriture véhicule : autorisée uniquement à l'arrêt, refus si vitesse illisible.
        if (!VehicleWriteGate.allow("VSM $methodName")) return false
        val vsm = sVsm ?: return false
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            vsm.javaClass.getMethod(methodName, *types).invoke(vsm, *args)
            true   // méthode trouvée et appelée sans exception → succès
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VSM: $methodName() exc: ${e.message}")
            false
        }
    }

    private fun getIntPropertyVpm(propId: Int): Int {
        val vpm = sVpm ?: return -1
        return try {
            (vpm.javaClass.getMethod("getIntProperty", Int::class.java)
                .invoke(vpm, propId) as? Int) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun setIntPropertyVpm(propId: Int, value: Int): Boolean {
        // [T-904] Écriture véhicule : autorisée uniquement à l'arrêt, refus si vitesse illisible.
        if (!VehicleWriteGate.allow("VPM 0x${Integer.toHexString(propId)}")) return false
        val vpm = sVpm ?: return false
        return try {
            vpm.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java)
                .invoke(vpm, propId, value)
            true
        } catch (_: Exception) { false }
    }

    /** Variante avec recovery — utilisée par vehiclesettings pour les propriétés FCW/AEB. */
    private fun setIntPropertyVpmRecovery(propId: Int, value: Int): Boolean {
        // [T-904] Écriture véhicule : autorisée uniquement à l'arrêt, refus si vitesse illisible.
        if (!VehicleWriteGate.allow("VPM-recovery 0x${Integer.toHexString(propId)}")) return false
        val vpm = sVpm ?: return false
        return try {
            vpm.javaClass.getMethod("setIntPropertyRecovery", Int::class.java, Int::class.java)
                .invoke(vpm, propId, value)
            if (logEnabled) AppLogger.i(TAG, "  VPM setIntRecovery 0x${propId.toString(16)} value=$value ✓")
            true
        } catch (e: Exception) {
            // Fallback sur setIntProperty si setIntPropertyRecovery absent
            AppLogger.d(TAG, "  VPM setIntRecovery fallback for 0x${propId.toString(16)}: ${e.message}")
            setIntPropertyVpm(propId, value)
        }
    }

    // ── Alimentation véhicule (mise hors tension, infodivertissement maintenu) ─
    // Reproduit le bouton "Vehicle Power → Off" du launcher MG (onglet Safety).
    // Valeur 2 sur les 6 firmwares ; seul le chemin d'accès diffère :
    //   • SWI133        : VehiclePropertyManager.setIntPropertyRecovery(0x6030021, 2)
    //   • SWI68/SWI165  : VehicleSettingManager.setPowerModeSwitch(2)
    //   • A9 (132/131/69): CarAdapterClient.queryClient(0xf) → CarComfortabletClient.setPowerModeSwitch(2)
    // Le véhicule n'exécute la coupure qu'à l'arrêt en position P (garde côté firmware).
    private const val PROP_POWER_MODE_SWITCH    = 0x6030021   // ID_POWER_MODE_SWITCH (SWI133)
    private const val POWER_MODE_OFF            = 2
    private const val COMFORTABLET_CLIENT_CLASS = "com.saicmotor.carapi.client.CarComfortabletClient"
    private const val CAR_ADAPTER_CLIENT_CLASS  = "com.saicmotor.carapi.CarAdapterClient"
    private const val COMFORTABLET_SERVICE_CODE = 0xf

    /**
     * Disponible sur les 6 firmwares : chacun a un check « position P » implémenté (garde de
     * sécurité — le firmware ne garde PAS la commande, donc sans check P une extinction en roulant
     * serait possible). Valeur P = 1 partout (cf. isVehicleInPark) :
     *   • SWI133 : gear VPM 0x5030043 ✓
     *   • A9 (132/131/69) : CarStateClient.getGearState() ✓ (confirmé SWI132)
     *   • SWI68/165 : VehicleConditionManager.getCarGear() poll ✓ (confirmé SWI68, même SDK 165)
     */
    fun hasVehiclePowerOff(): Boolean =
        FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN

    /** Coupe l'alimentation du véhicule tout en gardant l'écran/infodivertissement actif. */
    fun vehiclePowerOff(): Boolean {
        // Garde de sécurité : jamais d'extinction hors position P (re-vérifiée à l'envoi).
        if (isVehicleInPark() != true) {
            AppLogger.w(TAG, "vehiclePowerOff REFUSÉ — levier pas confirmé en P")
            return false
        }
        val gen = FirmwareInfo.getGeneration()
        AppLogger.i(TAG, "vehiclePowerOff (gen=$gen)")
        return when {
            gen == FirmwareInfo.Gen.SWI133 ->
                setIntPropertyVpmRecovery(PROP_POWER_MODE_SWITCH, POWER_MODE_OFF)
            gen == FirmwareInfo.Gen.SWI68 || gen == FirmwareInfo.Gen.SWI165 ->
                // sVsm = VehicleSettingManager (vieux SDK)
                callVsmVoid("setPowerModeSwitch", POWER_MODE_OFF)
            FirmwareInfo.isNewGenVsm() || gen == FirmwareInfo.Gen.SWI132 ->
                vehiclePowerOffA9()
            else -> false
        }
    }

    /** A9 (SWI132/131/69) : obtient CarComfortabletClient via l'adaptateur carapi et coupe l'alim. */
    private fun vehiclePowerOffA9(): Boolean {
        val cl = sVsm?.javaClass?.classLoader ?: run {
            AppLogger.w(TAG, "  A9 power-off: sVsm/classloader null"); return false
        }
        return try {
            val adapterClass = cl.loadClass(CAR_ADAPTER_CLIENT_CLASS)
            val adapter = adapterClass.getMethod("getInstance", Context::class.java).invoke(null, sAppContext)
            val binder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType)
                .invoke(adapter, COMFORTABLET_SERVICE_CODE) as? android.os.IBinder
                ?: run { AppLogger.w(TAG, "  A9 power-off: queryClient(0xf) null"); return false }
            val comfortClass = cl.loadClass(COMFORTABLET_CLIENT_CLASS)
            val comfort = comfortClass.getConstructor(android.os.IBinder::class.java).newInstance(binder)
            comfortClass.getMethod("setPowerModeSwitch", Int::class.javaPrimitiveType).invoke(comfort, POWER_MODE_OFF)
            AppLogger.i(TAG, "  A9 power-off: CarComfortabletClient.setPowerModeSwitch($POWER_MODE_OFF) ✓")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  A9 power-off error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ── Garde de sécurité : levier en position P ? ───────────────────────────
    // L'OEM n'autorise le power-off qu'en P (gear == 1), mais NE garde PAS la commande
    // côté firmware → c'est l'app qui doit vérifier (sinon extinction possible en roulant).
    // Sources gear par firmware (smali) :
    //   • SWI133        : VPM getIntProperty(0x5030043) ; PARK = 1 (CAR_GEAR_PARK_RANGE)
    //   • SWI68/SWI165  : VehicleConditionBean.getCarGear() (signal condition véhicule)
    //   • A9 (132/131/69): CarStateClient.getGearState()
    // Seul SWI133 est implémenté ET vérifiable ici ; ailleurs on renvoie null → power-off bloqué.
    private const val PROP_GEAR_STS   = 0x5030043   // SENSOR_TYPE_GEAR_STS (VPM, SWI133)
    private const val GEAR_PARK_VALUE = 1

    /**
     * Levier en P ? true/false si déterminable, null si on ne sait pas (→ bloquer le power-off).
     * Valeur P = 1 sur tous les firmwares étudiés (SWI133 0x5030043, A9 getGearState confirmé,
     * SWI68/165 getCarGear = CarGearValue.PARK).
     */
    fun isVehicleInPark(): Boolean? {
        val gen = FirmwareInfo.getGeneration()
        val gear = when {
            gen == FirmwareInfo.Gen.SWI133 ->
                if (sVpm == null) Int.MIN_VALUE else getIntPropertyVpm(PROP_GEAR_STS)
            FirmwareInfo.isNewGenVsm() || gen == FirmwareInfo.Gen.SWI132 ->
                readA9GearState()                 // CarStateClient.getGearState()
            gen == FirmwareInfo.Gen.SWI68 || gen == FirmwareInfo.Gen.SWI165 ->
                readVcmCarGear()                  // VehicleConditionManager.getCarGear()
            else -> Int.MIN_VALUE
        }
        AppLogger.i(TAG, "isVehicleInPark — gen=$gen gear=$gear (P=$GEAR_PARK_VALUE)")
        return if (gear < 0) null else gear == GEAR_PARK_VALUE
    }

    // ── Lecture gear SWI68/165 (poll direct) + A9 (CarStateClient) ───────────
    private const val CAR_STATE_CLIENT_CLASS = "com.saicmotor.carapi.client.CarStateClient"
    private const val CAR_STATE_SERVICE_CODE = 0xb
    @Volatile private var sCarState: Any? = null                   // A9 : CarStateClient (lazy)

    /** SWI68/165 : VehicleConditionManager.getCarGear() (poll synchrone via sVcm). */
    private fun readVcmCarGear(): Int {
        val vcm = sVcm ?: return Int.MIN_VALUE
        return try {
            (vcm.javaClass.getMethod("getCarGear").invoke(vcm) as? Int) ?: Int.MIN_VALUE
        } catch (e: Exception) {
            AppLogger.w(TAG, "  getCarGear err: ${e.javaClass.simpleName}: ${e.message}"); Int.MIN_VALUE
        }
    }

    /** A9 : CarStateClient.getGearState() via CarAdapterClient.queryClient(0xb). */
    private fun readA9GearState(): Int {
        val cl = sVsm?.javaClass?.classLoader ?: return Int.MIN_VALUE
        return try {
            if (sCarState == null) {
                val adapterClass = cl.loadClass(CAR_ADAPTER_CLIENT_CLASS)
                val adapter = adapterClass.getMethod("getInstance", Context::class.java).invoke(null, sAppContext)
                val binder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType)
                    .invoke(adapter, CAR_STATE_SERVICE_CODE) as? android.os.IBinder ?: return Int.MIN_VALUE
                val stateClass = cl.loadClass(CAR_STATE_CLIENT_CLASS)
                sCarState = stateClass.getConstructor(android.os.IBinder::class.java).newInstance(binder)
            }
            (sCarState!!.javaClass.getMethod("getGearState").invoke(sCarState) as? Int) ?: Int.MIN_VALUE
        } catch (e: Exception) {
            AppLogger.w(TAG, "  A9 getGearState err: ${e.javaClass.simpleName}: ${e.message}"); Int.MIN_VALUE
        }
    }

    private fun getMixIntProperty(propId: Int): Int {
        val vpm = sVpm ?: return -1
        return try {
            // Méthode réelle sur VPM : getMixProperty(Class, int)
            val result = vpm.javaClass
                .getMethod("getMixProperty", Class::class.java, Int::class.java)
                .invoke(vpm, Int::class.javaObjectType, propId)
            when (result) {
                is Int    -> result
                is Number -> result.toInt()
                null      -> { AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) = null"); -1 }
                else      -> { AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) = $result (${result.javaClass.simpleName})"); -1 }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: getMixProperty(0x${propId.toString(16)}) exc: ${e.message}")
            -1
        }
    }

    private fun setMixIntProperty(propId: Int, value: Int): Boolean {
        val vpm = sVpm ?: return false
        return try {
            // Méthode réelle sur VPM : setMixProperty(Class, int, Object)
            vpm.javaClass
                .getMethod("setMixProperty", Class::class.java, Int::class.java, Any::class.java)
                .invoke(vpm, Int::class.javaObjectType, propId, value)
            AppLogger.i(TAG, "  Katman4: setMixProperty(0x${propId.toString(16)}, $value) ✓")
            true
        } catch (e: Exception) {
            AppLogger.d(TAG, "  Katman4: setMixProperty(0x${propId.toString(16)}, $value) exc: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Low-level property accessors
    // -------------------------------------------------------------------------

    private fun getIntPropertyCPM(propId: Int, areaId: Int): Int {
        val cpm = sCarPropertyManager ?: return -1
        return try {
            (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, propId, areaId) as? Int) ?: -1
        } catch (e: Exception) {
            AppLogger.d(TAG, "  CPM getInt 0x${Integer.toHexString(propId)} exc: ${e.message}")
            -1
        }
    }

    /** Lecture float via CarPropertyManager. null = illisible (à distinguer de 0). */
    private fun getFloatPropertyCPM(propId: Int, areaId: Int): Float? {
        val cpm = sCarPropertyManager ?: return null
        return try {
            cpm.javaClass
                .getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, propId, areaId) as? Float
        } catch (e: Exception) {
            AppLogger.d(TAG, "  CPM getFloat 0x${Integer.toHexString(propId)} exc: ${e.message}")
            null
        }
    }

    private fun setIntPropertyCPM(propId: Int, areaId: Int, value: Int): Boolean {
        // [T-904] Écriture véhicule : autorisée uniquement à l'arrêt, refus si vitesse illisible.
        if (!VehicleWriteGate.allow("CPM 0x${Integer.toHexString(propId)}")) return false
        val cpm = sCarPropertyManager ?: run {
            AppLogger.w(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} — CPM not ready")
            return false
        }
        return try {
            cpm.javaClass
                .getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                .invoke(cpm, propId, areaId, value)
            if (logEnabled) AppLogger.i(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} area=0x${Integer.toHexString(areaId)} value=$value ✓")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  CPM setInt 0x${Integer.toHexString(propId)} error: ${e.message}")
            false
        }
    }

    fun getIntPropertyHvac(propId: Int, areaId: Int): Int {
        val hvac = sCarHvacManager ?: return -1
        return try {
            (hvac.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(hvac, propId, areaId) as? Int) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun setIntPropertyHvac(propId: Int, areaId: Int, value: Int): Boolean {
        val hvac = sCarHvacManager ?: return false
        return try {
            hvac.javaClass
                .getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                .invoke(hvac, propId, areaId, value)
            true
        } catch (_: Exception) { false }
    }

    /**
     * SAIC proprietary binder transact (Katman2 fallback).
     * Parcel layout from smali: [interfaceToken, AREA_GLOBAL, 1, value, float[], byte[]]
     */
    private fun binderTransact(binder: IBinder?, descriptor: String, txCode: Int, value: Int): Boolean {
        // [T-904] Écriture véhicule : autorisée uniquement à l'arrêt, refus si vitesse illisible.
        if (!VehicleWriteGate.allow("binder tx=0x${Integer.toHexString(txCode)}")) return false
        if (binder == null) {
            AppLogger.w(TAG, "  Binder TX=$txCode — binder null")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(AREA_GLOBAL)
            data.writeInt(1)
            data.writeInt(value)
            data.writeFloatArray(FloatArray(0))
            data.writeByteArray(ByteArray(0))
            binder.transact(txCode, data, reply, 0)
            val status = if (reply.dataAvail() > 0) reply.readInt() else 0
            if (logEnabled) AppLogger.i(TAG, "  Binder TX=$txCode value=$value → status=$status ${if (status == 0) "✓" else "✗ REJECTED"}")
            status == 0
        } catch (e: Exception) {
            AppLogger.e(TAG, "  binderTransact error: ${e.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * HVAC toggle — cycles the property by sending value=1 until target is reached.
     * Timeout: 7 seconds (from original smali: 0x1b58 ms = 7000 ms).
     */
    fun setHvacLevelWithToggle(propId: Int, areaId: Int, targetLevel: Int): Boolean {
        val deadline = System.currentTimeMillis() + 7_000L
        var lastClickMs = 0L
        while (System.currentTimeMillis() < deadline) {
            val current = getIntPropertyHvac(propId, areaId)
            if (current == targetLevel) {
                if (logEnabled) AppLogger.i(TAG, "HVAC target reached: $targetLevel")
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastClickMs >= 500L) {
                if (logEnabled) AppLogger.i(TAG, "HVAC click → current=$current target=$targetLevel")
                setIntPropertyHvac(propId, areaId, 1)
                lastClickMs = now
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            } else {
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
            }
        }
        AppLogger.e(TAG, "HVAC timeout! prop=0x${Integer.toHexString(propId)}")
        return false
    }

    // -------------------------------------------------------------------------
    // Public vehicle control API
    // -------------------------------------------------------------------------

    fun setDriveMode(mode: DriveMode): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setDriveMode → ${mode.label} (${mode.value})")
        val ok = setIntPropertyCPM(PROP_DRIVE_MODE, AREA_GLOBAL, mode.value)
        if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_DRIVE_MODE, mode.value)
        sAppContext?.getSharedPreferences(PREFS_NAME, 0)?.edit()
            ?.putInt(KEY_LAST_DRIVE_MODE, mode.value)?.apply()
        return ok
    }

    fun setRegenLevel(level: RegenLevel): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setRegenLevel → ${level.label} (${level.value})")
        return if (level == RegenLevel.ONE_PEDAL) {
            val ok = setOnePedal(true)
            if (!ok) {
                setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value)
                binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value)
            }
            ok
        } else {
            setOnePedal(false)
            val ok = setIntPropertyCPM(PROP_REGEN_LEVEL, AREA_GLOBAL, level.value)
            if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_REGEN_LEVEL, level.value)
            ok
        }
    }

    fun setOnePedal(enabled: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setOnePedal → ${if (enabled) "On" else "Off"}")
        val intVal = if (enabled) 1 else 0
        val ok = setIntPropertyCPM(PROP_ONE_PEDAL, AREA_GLOBAL, intVal)
        if (!ok) binderTransact(sVehicleBinder, DESCRIPTOR_VEHICLE, TX_SET_ONE_PEDAL, intVal)
        return ok
    }

    fun getSeatHeatLeft(): Int  = getIntPropertyHvac(PROP_SEAT_HEAT_L, AREA_HVAC).coerceAtLeast(0)
    fun getSeatHeatRight(): Int = getIntPropertyHvac(PROP_SEAT_HEAT_R, AREA_HVAC).coerceAtLeast(0)
    fun isSteeringHeatOn(): Boolean = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC) > 0

    fun getDriveMode(): DriveMode? {
        val cpm = sCarPropertyManager ?: return null
        return try {
            val raw = (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_DRIVE_MODE, AREA_GLOBAL) as? Int) ?: return null
            DriveMode.fromValue(raw)
        } catch (_: Exception) { null }
    }

    fun getRegenLevel(): RegenLevel? {
        val cpm = sCarPropertyManager ?: return null
        return try {
            val raw = (cpm.javaClass
                .getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_REGEN_LEVEL, AREA_GLOBAL) as? Int) ?: return null
            RegenLevel.fromValue(raw)
        } catch (_: Exception) { null }
    }

    fun setSeatHeatLeft(level: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSeatHeatLeft → $level")
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_L, AREA_HVAC, level)
    }

    fun setSeatHeatRight(level: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSeatHeatRight → $level")
        return setHvacLevelWithToggle(PROP_SEAT_HEAT_R, AREA_HVAC, level)
    }

    fun setSteeringHeat(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSteeringHeat → $on")
        val current = getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC)
        if ((current > 0) == on) return true
        // Send a single click and wait for state confirmation (avoids on/off oscillation)
        setIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC, 1)
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            if ((getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC) > 0) == on) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // ADAS API (Katman4)
    // -------------------------------------------------------------------------

    fun isOverspeedAlarmOn(): Boolean {
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
            // Priorité VSM (CarVehicleSettingClient) — confirmé dans smali SWI132
            // getOverSpeedSoundMode() : 0=OFF, 1/2/3=ON
            val vsm = callVsm("getOverSpeedSoundMode") as? Int
            if (vsm != null) {
                AppLogger.d(TAG, "  SWI132 overspeed GET via VSM → $vsm")
                return vsm > 0
            }
            return swi132BinderGet(VSM132_TX_GET_OVERSPEED) == 1
        }
        return getIntPropertyVpm(PROP_OVERSPEED_ALARM) > 0
    }

    fun setOverspeedAlarm(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setOverspeedAlarm → $on")
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
            // Essai 1 : CarVehicleSettingClient — setOverSpeedSoundMode(I)V est une méthode void ;
            // callVsmVoid() détecte le succès même quand invoke() retourne null (comportement normal).
            if (callVsmVoid("setOverSpeedSoundMode", if (on) 1 else 0)) {
                AppLogger.i(TAG, "  SWI132 overspeed → VSM OK")
                return true
            }
            AppLogger.w(TAG, "  SWI132 overspeed: VSM failed — essai binder")
            // Essai 2 : binder direct (TX 0x128, bloqué SELinux sur certains builds)
            if (swi132BinderSet(VSM132_TX_OVERSPEED_SOUND, if (on) 1 else 0)) return true
            AppLogger.w(TAG, "  SWI132 overspeed: tous les paths ont échoué")
            return false
        }
        return setIntPropertyVpm(PROP_OVERSPEED_ALARM, if (on) 1 else 0)
    }

    fun isSpeedLimitToneOn(): Boolean {
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
            // getSpeedLimitSoundMode() : 0=OFF, valeur positive=ON
            val vsm = callVsm("getSpeedLimitSoundMode") as? Int
            if (vsm != null) {
                AppLogger.d(TAG, "  SWI132 speedLimit GET via VSM → $vsm")
                return vsm > 0
            }
            return swi132BinderGet(VSM132_TX_GET_SPEED_LIMIT) == 1
        }
        return getIntPropertyVpm(PROP_SPEED_LIMIT_TONE) > 0
    }

    fun setSpeedLimitTone(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSpeedLimitTone → $on")
        if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132) {
            // Essai 1 : CarVehicleSettingClient — setSpeedLimitSoundMode(I)V (méthode void)
            if (callVsmVoid("setSpeedLimitSoundMode", if (on) 1 else 0)) {
                AppLogger.i(TAG, "  SWI132 speedLimit → VSM OK")
                return true
            }
            AppLogger.w(TAG, "  SWI132 speedLimit: VSM failed — essai binder")
            // Essai 2 : binder direct (TX 0x12a)
            if (swi132BinderSet(VSM132_TX_SPEED_LIMIT, if (on) 1 else 0)) return true
            AppLogger.w(TAG, "  SWI132 speedLimit: tous les paths ont échoué")
            return false
        }
        return setIntPropertyVpm(PROP_SPEED_LIMIT_TONE, if (on) 1 else 0)
    }

    /** Returns 0–4 (Off/Limiteur/Auto/ACC/ICA), or -1 if Katman4 not ready. */
    fun getMixedIntelligentDrive(): Int = getMixIntProperty(PROP_MIX_INTELLIGENT_DRIVE)
    fun setMixedIntelligentDrive(value: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setMixedIntelligentDrive → $value")
        // Primary: setMixProperty (smali-accurate). Fallback: setIntProperty if method missing.
        if (setMixIntProperty(PROP_MIX_INTELLIGENT_DRIVE, value)) return true
        return setIntPropertyVpm(PROP_MIX_INTELLIGENT_DRIVE, value)
    }

    // ── SWI68 / SWI69 ADAS API — VehicleSettingManager (noms de méthodes différents) ──

    /**
     * Retourne le mode ACC/TJA actuel (0x4=Off, 0x1=ACC, 0x2=TJA), ou -1 si pas prêt.
     * SWI68/SWI165 : getAccTjaMode()   SWI69/SWI131/SWI132 : getAccTjaState()
     */
    fun getAccTjaMode(): Int {
        val useNewApi = FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        val method = if (useNewApi) "getAccTjaState" else "getAccTjaMode"
        if (logEnabled) AppLogger.i(TAG, "$method →")
        return (callVsm(method) as? Int) ?: -1
    }

    /** SWI68/SWI165 : setAccTjaMode(I)V   SWI69/SWI131/SWI132 : setAccTjaState(I)V — void */
    fun setAccTjaMode(mode: Int): Boolean {
        val useNewApi = FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132
        val method = if (useNewApi) "setAccTjaState" else "setAccTjaMode"
        if (logEnabled) AppLogger.i(TAG, "$method → 0x${mode.toString(16)}")
        return callVsmVoid(method, mode)   // void method — callVsmVoid évite le faux-négatif
    }

    /**
     * Limiteur de vitesse — API unifiée pour tous les firmwares VSM.
     * Le limiteur est un réglage INDÉPENDANT du mode ACC/TJA, avec les mêmes valeurs partout
     * (0=Désactivé, 2=Manuel, 3=Intelligent — vérifié dans le smali de chaque firmware),
     * seul le nom de méthode binder diffère :
     *   SWI132/SWI131/SWI69 : getSasMode/setSasMode        (CarVehicleSettingClient)
     *   SWI68/SWI165        : getSpeedAsstMode/setSpeedAsstMode (VehicleSettingManager)
     */
    private fun useSasApi(): Boolean =
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132

    /** Lit le mode du limiteur de vitesse. Retourne 0/2/3, ou -1 si indisponible. */
    fun getSpeedLimiterMode(): Int {
        val method = if (useSasApi()) "getSasMode" else "getSpeedAsstMode"
        if (logEnabled) AppLogger.i(TAG, "$method →")
        return (callVsm(method) as? Int) ?: -1
    }

    /** Configure le mode du limiteur de vitesse. 0=Désactivé, 2=Manuel, 3=Intelligent. */
    fun setSpeedLimiterMode(mode: Int): Boolean {
        val method = if (useSasApi()) "setSasMode" else "setSpeedAsstMode"
        if (logEnabled) AppLogger.i(TAG, "$method → $mode")
        return callVsmVoid(method, mode)
    }

    /**
     * SWI68/SWI165 : getLaneKeepingWarningSound()
     * SWI69/SWI131/SWI132 : getLasWarningSound()   (confirmé dans smali SWI132)
     * Valeurs : 2=ON / 1=OFF
     */
    fun isSoundWarningOn(): Boolean {
        val method = if (FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132)
            "getLasWarningSound" else "getLaneKeepingWarningSound"
        return ((callVsm(method) as? Int) ?: 1) == 2
    }

    /** SWI68 : setLaneKeepingWarningSound(I)   SWI69/SWI131/SWI132 : setLasWarningSound(I) — void */
    fun setSoundWarning(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setSoundWarning → $on")
        val method = if (FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132)
            "setLasWarningSound" else "setLaneKeepingWarningSound"
        return callVsmVoid(method, if (on) 2 else 1)
    }

    // ── AEB — Système anti-collision avant ──────────────────────────────────

    /**
     * Retourne true si le système anti-collision avant est activé.
     * SWI133          : lit PROP_AEB_SWITCH (2=ON, 1=OFF) via CarPropertyManager.
     * SWI68 / SWI165  : getFcwAlarmMode() == 2  (FCW_ALARM_ON=2, FCW_ALARM_OFF=1)
     *                   Vérifié dans SafeSettingsRepository SWI165 — même API que SWI68.
     * SWI69 / SWI131  : getFcwState() — 1=DÉSACTIVÉ, 2=ACTIVÉ
     */
    fun isAebEnabled(): Boolean {
        return when {
            // SWI69 / SWI131 / SWI132 — CarVehicleSettingClient : getFcwState() (1=OFF, 2=ON)
            FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 ->
                (callVsm("getFcwState") as? Int) == 2
            FirmwareInfo.isVsmBased()  -> (callVsm("getFcwAlarmMode") as? Int) == 2  // SWI68 / SWI165
            else                       -> getIntPropertyCPM(PROP_AEB_SWITCH, AREA_GLOBAL) == 0x2  // SWI133
        }
    }

    fun setAebEnabled(on: Boolean): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setAebEnabled → $on")
        return when {
            // SWI69 / SWI131 / SWI132 — CarVehicleSettingClient (même API)
            // setFcwState(I)V et setFcwAutoBrakeMode(I)V sont des méthodes VOID →
            // callVsmVoid() est utilisé pour éviter le faux-négatif de callVsm() != null.
            FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
                // OFF : setFcwState(1) + setFcwAutoBrakeMode(1) + setFcwSensitivity(0)
                // ON  : setFcwState(2) + setFcwAutoBrakeMode(curMode)
                // Le launcher conditionne son affichage à fcwState==1 AND autoBreakState==1
                // → sans setFcwAutoBrakeMode, son switch reste ON même quand l'AEB est désactivé
                if (on) {
                    val sOk = callVsmVoid("setFcwState", 2)
                    val curMode = (callVsm("getFcwAutoBrakeMode") as? Int) ?: 1
                    val mOk = callVsmVoid("setFcwAutoBrakeMode", curMode)
                    sOk || mOk
                } else {
                    callVsmVoid("setFcwState", 1)
                    callVsmVoid("setFcwAutoBrakeMode", 1)
                    callVsmVoid("setFcwSensitivity", 0)
                }
            }
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68 ||
            FirmwareInfo.isSWI165() -> {
                // SWI68 / SWI165 : setFcwAlarmMode(2=ON / 1=OFF) + setFcwAutoBrakeMode(1) si OFF
                // Vérifié dans SafeSettingsRepository SWI165 — même API que SWI68,
                // setAutoEmergencyBraking() n'est jamais utilisé par l'app officielle.
                // setFcwAlarmMode(I)V et setFcwAutoBrakeMode(I)V sont void → callVsmVoid()
                if (on) callVsmVoid("setFcwAlarmMode", 2)
                else { callVsmVoid("setFcwAlarmMode", 1) or callVsmVoid("setFcwAutoBrakeMode", 1) }
            }
            else -> setIntPropertyCPM(PROP_AEB_SWITCH, AREA_GLOBAL, if (on) 0x2 else 0x1)
        }
    }

    /**
     * Retourne le mode AEB courant (1=Alerte, 2=Alerte+Freinage), ou -1 si pas prêt.
     * SWI133          : PROP_AEB_MODE (0x302000b) via VehiclePropertyManager.
     * SWI68/SWI69/SWI131 : getFcwAutoBrakeMode() (1=Alerte, 2=Alerte+Freinage).
     */
    fun getAebMode(): Int {
        return if (FirmwareInfo.isVsmBased()) {
            (callVsm("getFcwAutoBrakeMode") as? Int) ?: AebMode.ALARM
        } else {
            val raw = getIntPropertyVpm(PROP_AEB_MODE)
            if (raw < 1) -1 else raw
        }
    }

    fun setAebMode(mode: Int): Boolean {
        if (logEnabled) AppLogger.i(TAG, "setAebMode → $mode")
        return when {
            // SWI69 / SWI131 / SWI132 — CarVehicleSettingClient : fixer mode puis activer
            // setFcwAutoBrakeMode(I)V et setFcwState(I)V sont void → callVsmVoid()
            FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
                // L'ordre : 1) fixer le mode, 2) activer (commit le mode).
                val modeVal = if (mode == AebMode.ALARM_BRAKE) 2 else 1
                val mOk = callVsmVoid("setFcwAutoBrakeMode", modeVal)
                val sOk = callVsmVoid("setFcwState", 2)
                mOk || sOk
            }
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68 ||
            FirmwareInfo.isSWI165() -> {
                // SWI68 / SWI165 : setFcwAutoBrakeMode uniquement (1=Alerte, 2=Alerte+Freinage)
                callVsm("setFcwAutoBrakeMode", if (mode == AebMode.ALARM_BRAKE) 2 else 1) != null
            }
            else -> {
                // SWI133 smali exact
                if (mode == AebMode.ALARM_BRAKE) {
                    val r1 = setIntPropertyVpmRecovery(PROP_AEB_SYS_MODE, AebMode.ALARM_BRAKE)
                    val r2 = setIntPropertyVpmRecovery(PROP_AEB_MODE, AebMode.ALARM_BRAKE)
                    r1 || r2
                } else {
                    setIntPropertyVpmRecovery(PROP_AEB_MODE, AebMode.ALARM)
                }
            }
        }
    }

    /**
     * Retourne la sensibilité AEB courante (1=Faible, 2=Standard, 3=Élevé), ou -1 si pas prêt.
     * SWI133         : PROP_AEB_SENSITIVITY (0x302000e, VPM)
     * SWI68/SWI165   : VehicleSettingManager.getFcwSensitivity()
     * SWI69/SWI131   : CarVehicleSettingClient.getFcwSensitivity()
     */
    fun getAebSensitivity(): Int {
        return if (FirmwareInfo.isVsmBased()) {
            (callVsm("getFcwSensitivity") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  AEB GET sensitivity=$it via VSM ✓")
            } ?: -1
        } else {
            val raw = getIntPropertyVpm(PROP_AEB_SENSITIVITY)
            if (raw < 1) -1 else raw
        }
    }

    /**
     * Définit la sensibilité AEB (1=Faible, 2=Standard, 3=Élevé).
     * SWI133         : PROP_AEB_SENSITIVITY (0x302000e, VPM)
     * SWI68/SWI165   : VehicleSettingManager.setFcwSensitivity(I)
     * SWI69/SWI131   : CarVehicleSettingClient.setFcwSensitivity(I)
     */
    fun setAebSensitivity(level: Int): Boolean {
        // setFcwSensitivity(I)V est void → callVsmVoid() pour éviter le faux-négatif
        return if (FirmwareInfo.isVsmBased()) {
            AppLogger.i(TAG, "  AEB SET sensitivity=$level via VSM")
            callVsmVoid("setFcwSensitivity", level)
        } else {
            AppLogger.i(TAG, "  AEB SET sensitivity=$level via VPM")
            setIntPropertyVpmRecovery(PROP_AEB_SENSITIVITY, level)
        }
    }

    // -------------------------------------------------------------------------
    // ELK — Assistant de sortie de voie (SWI133 uniquement pour l'instant)
    // Utilise IVehicleSettingService via sVehicleBinder (TX 0x53–0x56)
    // -------------------------------------------------------------------------

    /**
     * Retourne le mode ELK courant (1=OFF, 2=Alerte, 3=Aider, 5=Maintien d'urgence).
     * Routage par firmware :
     *   SWI133         → VSM133 (getLaneKeepingAsstMode) → binder TX 0x53
     *   SWI68/SWI165   → VSM    (getLaneKeepingAsstMode)
     *   SWI69/SWI131   → VSM    (getLasMode)
     */
    fun getElkMode(): Int = when {
        !FirmwareInfo.isVsmBased() -> {
            val vsm = callVsm133("getLaneKeepingAsstMode")
            if (vsm is Int && vsm > 0) {
                AppLogger.d(TAG, "  ELK GET mode=$vsm via VSM133 ✓")
                vsm
            } else elkBinderGet(TX_ELK_GET_MODE)
        }
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
            // SWI69/SWI131/SWI132 — CarVehicleSettingClient
            (callVsm("getLasMode") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET mode=$it via VSM (Las) ✓")
            } ?: -1
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            (callVsm("getLaneKeepingAsstMode") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET mode=$it via VSM ✓")
            } ?: -1
        }
    }

    /**
     * Définit le mode ELK.
     * Routage identique à getElkMode().
     */
    fun setElkMode(mode: Int): Boolean = when {
        !FirmwareInfo.isVsmBased() -> {
            if (sVsm133 != null) {
                AppLogger.i(TAG, "  ELK SET mode=$mode via VSM133")
                if (!VehicleWriteGate.allow("VSM133 setLaneKeepingAsstMode")) {
                    false
                } else {
                    callVsm133("setLaneKeepingAsstMode", mode)
                    true
                }
            } else elkBinderSet(TX_ELK_SET_MODE, mode)
        }
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
            // SWI69/SWI131/SWI132 — CarVehicleSettingClient
            AppLogger.i(TAG, "  ELK SET mode=$mode via VSM (Las)")
            callVsm("setLasMode", mode)
            true
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            AppLogger.i(TAG, "  ELK SET mode=$mode via VSM")
            callVsm("setLaneKeepingAsstMode", mode)
            true
        }
    }

    /**
     * Retourne la sensibilité ELK courante (1=Faible, 2=Standard, 3=Élevé).
     * Routage par firmware — identique à getElkMode().
     */
    fun getElkSensitivity(): Int = when {
        !FirmwareInfo.isVsmBased() -> {
            val vsm = callVsm133("getLaneKeepingAsstSen")
            if (vsm is Int && vsm > 0) {
                AppLogger.d(TAG, "  ELK GET sen=$vsm via VSM133 ✓")
                vsm
            } else elkBinderGet(TX_ELK_GET_SEN)
        }
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
            // SWI69/SWI131/SWI132 — CarVehicleSettingClient
            (callVsm("getLasSensitivity") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET sen=$it via VSM (Las) ✓")
            } ?: -1
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            (callVsm("getLaneKeepingAsstSen") as? Int)?.takeIf { it > 0 }?.also {
                AppLogger.d(TAG, "  ELK GET sen=$it via VSM ✓")
            } ?: -1
        }
    }

    /**
     * Définit la sensibilité ELK.
     * Routage identique à getElkMode().
     */
    fun setElkSensitivity(level: Int): Boolean = when {
        !FirmwareInfo.isVsmBased() -> {
            if (sVsm133 != null) {
                AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM133")
                if (!VehicleWriteGate.allow("VSM133 setLaneKeepingAsstSen")) {
                    false
                } else {
                    callVsm133("setLaneKeepingAsstSen", level)
                    true
                }
            } else elkBinderSet(TX_ELK_SET_SEN, level)
        }
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
            // SWI69/SWI131/SWI132 — CarVehicleSettingClient
            AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM (Las)")
            callVsm("setLasSensitivity", level)
            true
        }
        else -> {
            // SWI68/SWI165 — VehicleSettingManager
            AppLogger.i(TAG, "  ELK SET sensitivity=$level via VSM")
            callVsm("setLaneKeepingAsstSen", level)
            true
        }
    }

    /** Retourne true si l'ELK est activé (mode ≠ OFF). */
    fun isElkEnabled(): Boolean {
        val mode = getElkMode()
        return mode > 0 && mode != ElkMode.OFF
    }

    /**
     * SWI132 — Alerte sonore (LAS Warning Sound) : 0=OFF, 1=ON.
     * Retourne -1 si erreur ou firmware non SWI132.
     */
    fun getLasWarningSound(): Int {
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.SWI132) return -1
        return (callVsm("getLasWarningSound") as? Int)?.also {
            AppLogger.d(TAG, "  LAS GET sound=$it via VSM ✓")
        } ?: -1
    }

    fun setLasWarningSound(enabled: Boolean): Boolean {
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.SWI132) return false
        AppLogger.i(TAG, "  LAS SET sound=${if (enabled) "ON" else "OFF"} via VSM")
        callVsm("setLasWarningSound", if (enabled) 1 else 0)
        return true
    }

    /**
     * SWI132 — Rappel par vibration (LAS Warning Vibration) : 0=OFF, 1=ON.
     * Retourne -1 si erreur ou firmware non SWI132.
     */
    fun getLasWarningVibration(): Int {
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.SWI132) return -1
        return (callVsm("getLasWarningVibration") as? Int)?.also {
            AppLogger.d(TAG, "  LAS GET vibration=$it via VSM ✓")
        } ?: -1
    }

    fun setLasWarningVibration(enabled: Boolean): Boolean {
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.SWI132) return false
        AppLogger.i(TAG, "  LAS SET vibration=${if (enabled) "ON" else "OFF"} via VSM")
        callVsm("setLasWarningVibration", if (enabled) 1 else 0)
        return true
    }

    /**
     * GET via IVehicleSettingService binder — layout smali :
     *   data : [writeInterfaceToken]
     *   transact(code, data, reply, 0)
     *   reply: readException() + readInt()
     */
    private fun elkBinderGet(txCode: Int): Int {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return -1
        }
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            val ok = binder.transact(txCode, data, reply, 0)
            if (!ok) {
                AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} — transact returned false")
                return -1
            }
            reply.readException()
            val result = reply.readInt()
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} = $result")
            result
        } catch (e: Exception) {
            AppLogger.d(TAG, "  ELK GET TX=0x${txCode.toString(16)} exc: ${e.message}")
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * SET via IVehicleSettingService binder — layout smali :
     *   data : [writeInterfaceToken, writeInt(value)]
     *   transact(code, data, null, FLAG_ONEWAY=1)
     */
    private fun elkBinderSet(txCode: Int, value: Int): Boolean {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  ELK SET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return false
        }
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            data.writeInt(value)
            binder.transact(txCode, data, null, IBinder.FLAG_ONEWAY)
            AppLogger.i(TAG, "  ELK SET TX=0x${txCode.toString(16)} value=$value ✓")
            true
        } catch (e: Exception) {
            AppLogger.d(TAG, "  ELK SET TX=0x${txCode.toString(16)} exc: ${e.message}")
            false
        } finally {
            data.recycle()
        }
    }

    /**
     * GET via IVehicleSettingService SWI132 (DESCRIPTOR_VSM132, two-way flag=0x0).
     * Retourne la valeur entière lue, ou -1 en cas d'erreur.
     */
    private fun swi132BinderGet(txCode: Int): Int {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  SWI132 GET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return -1
        }
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VSM132)
            val ok = binder.transact(txCode, data, reply, 0)
            if (!ok) {
                AppLogger.d(TAG, "  SWI132 GET TX=0x${txCode.toString(16)} — transact false")
                return -1
            }
            reply.readException()
            val result = reply.readInt()
            AppLogger.d(TAG, "  SWI132 GET TX=0x${txCode.toString(16)} = $result")
            result
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI132 GET TX=0x${txCode.toString(16)} exc: ${e.message}")
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * SET via IVehicleSettingService SWI132 (DESCRIPTOR_VSM132, two-way flag=0x0).
     * Différent de elkBinderSet : utilise le bon DESCRIPTOR et flag two-way.
     */
    private fun swi132BinderSet(txCode: Int, value: Int): Boolean {
        val binder = sVehicleBinder ?: run {
            AppLogger.d(TAG, "  SWI132 SET TX=0x${txCode.toString(16)} — sVehicleBinder null")
            return false
        }
        val data  = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VSM132)
            data.writeInt(value)
            binder.transact(txCode, data, reply, 0)
            reply.readException()
            AppLogger.i(TAG, "  SWI132 SET TX=0x${txCode.toString(16)} value=$value ✓")
            true
        } catch (e: Exception) {
            AppLogger.d(TAG, "  SWI132 SET TX=0x${txCode.toString(16)} exc: ${e.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // TSR — Reconnaissance des panneaux de vitesse
    // -------------------------------------------------------------------------

    fun isTsrOn(): Boolean = when {
        FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 ->
            // Priorité CarVehicleSettingClient (binder vehiclesetting bloqué SELinux)
            // Convention identique SWI69/SWI131 : 0=ON, 1=OFF
            (callVsm("getSLIFWarningState") as? Int)?.let { raw ->
                AppLogger.d(TAG, "  SWI132 TSR GET via VSM → $raw")
                raw == 0
            } ?: (swi132BinderGet(VSM132_TX_GET_SLIF) == 1)  // fallback binder (rarement accessible)
        FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
            getIntPropertyVpm(PROP_TSR_MODE) > 0
        FirmwareInfo.isNewGenVsm() ->   // SWI69 + SWI131 — convention inversée : 0=ON, 1=OFF
            (callVsm("getSLIFWarningState") as? Int) == 0
        FirmwareInfo.isVsmBased() ->    // SWI68 + SWI165
            (callVsm("getSpeedAsstSlifWarning") as? Int) == 1
        else -> false
    }

    fun setTsrMode(enabled: Boolean): Boolean {
        AppLogger.i(TAG, "setTsrMode → $enabled")
        return when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {
                // Priorité CarVehicleSettingClient — setSLIFWarningState(I)V confirmé dans smali SWI132
                // Convention identique SWI69/SWI131 : 0=activer, 1=désactiver
                if (callVsmVoid("setSLIFWarningState", if (enabled) 0 else 1)) {
                    AppLogger.i(TAG, "  SWI132 TSR → VSM OK")
                    true
                } else {
                    // Fallback binder direct (bloqué SELinux sur la plupart des builds SWI132)
                    AppLogger.w(TAG, "  SWI132 TSR: VSM failed — essai binder TX 0x057")
                    swi132BinderSet(VSM132_TX_SLIF_WARNING, if (enabled) 1 else 0)
                }
            }
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 -> {
                // SWI133 : le firmware remet OVERSPEED et SPEED_TONE à ON quand le SLIF est réactivé
                // → on sauvegarde avant et on restaure après.
                val prefs = sAppContext?.getSharedPreferences("mg4_settings", 0)
                if (!enabled) {
                    val overspeedOn = isOverspeedAlarmOn()
                    val speedToneOn = isSpeedLimitToneOn()
                    prefs?.edit()
                        ?.putBoolean("tsr_saved_overspeed", overspeedOn)
                        ?.putBoolean("tsr_saved_speed_tone", speedToneOn)
                        ?.apply()
                    AppLogger.i(TAG, "  TSR OFF — sauvegarde overspeed=$overspeedOn speedTone=$speedToneOn")
                }
                val ok = setIntPropertyVpmRecovery(PROP_TSR_MODE, if (enabled) 1 else 0)
                if (enabled && ok) {
                    Thread.sleep(400)
                    val savedOverspeed = prefs?.getBoolean("tsr_saved_overspeed", true) ?: true
                    val savedSpeedTone = prefs?.getBoolean("tsr_saved_speed_tone", true) ?: true
                    AppLogger.i(TAG, "  TSR ON — restauration overspeed=$savedOverspeed speedTone=$savedSpeedTone")
                    setOverspeedAlarm(savedOverspeed)
                    setSpeedLimitTone(savedSpeedTone)
                }
                ok
            }
            FirmwareInfo.isNewGenVsm() -> {   // SWI69 + SWI131 — convention inversée : 0=activer, 1=désactiver
                // setSLIFWarningState(I)V est void → callVsmVoid()
                callVsmVoid("setSLIFWarningState", if (enabled) 0 else 1)
            }
            FirmwareInfo.isVsmBased() -> {    // SWI68 + SWI165
                // L'avertissement sonore pourrait être remis à ON lors de la réactivation du TSR
                // → on sauvegarde avant et on restaure après.
                val prefs = sAppContext?.getSharedPreferences("mg4_settings", 0)
                if (!enabled) {
                    val soundOn = isSoundWarningOn()
                    prefs?.edit()?.putBoolean("tsr_saved_sound_warning", soundOn)?.apply()
                    AppLogger.i(TAG, "  TSR OFF — sauvegarde soundWarning=$soundOn")
                }
                // setSpeedAsstSlifWarning(I)V est void → callVsmVoid()
                if (!callVsmVoid("setSpeedAsstSlifWarning", if (enabled) 1 else 0)) return false
                if (enabled) {
                    Thread.sleep(400)
                    val savedSound = prefs?.getBoolean("tsr_saved_sound_warning", true) ?: true
                    AppLogger.i(TAG, "  TSR ON — restauration soundWarning=$savedSound")
                    setSoundWarning(savedSound)
                }
                true
            }
            else -> false
        }
    }

    /**
     * SWI133 : retourne (overspeed, speedTone) tels que sauvegardés lors du dernier TSR OFF.
     * Utilisé par l'UI pour mettre à jour les switches après la réactivation du TSR, sans
     * relire le hardware (le VPM a une latence de propagation qui renverrait encore ON
     * pendant ~500–1000ms après les écritures internes de setTsrMode).
     */
    fun savedTsrAlerts(): Pair<Boolean, Boolean> {
        val prefs = sAppContext?.getSharedPreferences("mg4_settings", 0)
        return Pair(
            prefs?.getBoolean("tsr_saved_overspeed",  true) ?: true,
            prefs?.getBoolean("tsr_saved_speed_tone", true) ?: true
        )
    }

    // -------------------------------------------------------------------------
    // Économie d'énergie (Endurance Mode)
    // -------------------------------------------------------------------------

    fun isEnergySavingOn(): Boolean = when {
        FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
            getIntPropertyVpm(PROP_ENERGY_SAVING) == 1
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 ->  // SWI69 + SWI131 + SWI132
            (callVsm("getEnduranceMode") as? Int) == 1
        FirmwareInfo.isVsmBased() ->        // SWI68 + SWI165
            (callVsm("getLongerEndurance") as? Int) == 1
        else -> false
    }

    fun setEnergySavingMode(enabled: Boolean): Boolean {
        AppLogger.i(TAG, "setEnergySavingMode → $enabled")
        return when {
            FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133 ->
                setIntPropertyVpmRecovery(PROP_ENERGY_SAVING, if (enabled) 1 else 0)
            FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132 -> {  // SWI69 + SWI131 + SWI132
                // setEnduranceMode(I)V est void → callVsmVoid()
                callVsmVoid("setEnduranceMode", if (enabled) 1 else 0)
            }
            FirmwareInfo.isVsmBased() -> {   // SWI68 + SWI165
                // setLongerEndurance(I)V est void → callVsmVoid()
                callVsmVoid("setLongerEndurance", if (enabled) 1 else 0)
            }
            else -> false
        }
    }

    fun isKatman4Ready(): Boolean =
        if (FirmwareInfo.isVsmBased()) sVsm != null && sVsmService != null
        else                           sVpm != null && sVpmService != null
    fun isKatman4VpmCreated(): Boolean      = sVpm != null || sVsm != null
    fun isCarPropertyManagerReady(): Boolean = sCarPropertyManager != null
    fun isCarHvacManagerReady(): Boolean     = sCarHvacManager != null

    // -------------------------------------------------------------------------
    // IGNITION_STATE — CarPropertyManager callback (standard AAOS, tous firmwares)
    // -------------------------------------------------------------------------

    /**
     * Enregistre un CarPropertyEventCallback sur PROP_IGNITION_STATE via réflexion.
     * Protégé par [sIgnitionCallbackRegistered] — ne s'exécute qu'une seule fois.
     * Lit l'état courant immédiatement après l'enregistrement : CPM ne notifie que sur changement,
     * donc si la voiture est déjà READY au moment du bind, aucun event ne serait reçu sans cette lecture.
     */
    private fun registerIgnitionPropertyCallback() {
        if (sIgnitionCallbackRegistered) return
        val cpm = sCarPropertyManager ?: return
        try {
            val allRegMethods = cpm.javaClass.methods
                .filter { it.name == "registerCallback" }
                .joinToString(" | ") { m ->
                    "(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                }
            AppLogger.i(TAG, "  IGNITION: CPM.registerCallback variants: $allRegMethods")

            val registerMethod = cpm.javaClass.methods.firstOrNull { m ->
                m.name == "registerCallback" && m.parameterCount == 3
            } ?: run {
                AppLogger.w(TAG, "  IGNITION: NO 3-param registerCallback! Available: $allRegMethods")
                return
            }

            val callbackType = registerMethod.parameterTypes[0]
            AppLogger.i(TAG, "  IGNITION: callbackType=${callbackType.simpleName} isInterface=${callbackType.isInterface}")

            if (!callbackType.isInterface) {
                AppLogger.w(TAG, "  IGNITION: ${callbackType.name} NOT interface — proxy impossible")
                return
            }

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackType.classLoader, arrayOf(callbackType)
            ) { _, method, args ->
                if (method.name == "onChangeEvent" && args != null) {
                    val cpv = args[0] ?: return@newProxyInstance null
                    try {
                        val value = cpv.javaClass.getMethod("getValue").invoke(cpv) as? Int
                        if (value != null) {
                            AppLogger.i(TAG, "IGNITION_STATE event → $value (${ignitionStateName(value)})")
                            dispatchIgnitionState(value)
                        } else {
                            AppLogger.w(TAG, "  IGNITION: onChangeEvent getValue() retourné null")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "  IGNITION: onChangeEvent parse error: ${e.message}")
                    }
                } else if (method.name == "onErrorEvent") {
                    AppLogger.w(TAG, "  IGNITION: onErrorEvent args=${args?.joinToString()}")
                }
                null
            }

            sIgnitionCallbackProxy = proxy   // référence forte pour éviter le GC
            registerMethod.invoke(cpm, proxy, PROP_IGNITION_STATE, 0f)
            sIgnitionCallbackRegistered = true
            AppLogger.i(TAG, "  IGNITION_STATE callback registered ✓ (propId=0x${PROP_IGNITION_STATE.toString(16)})")

            // Lecture immédiate de l'état courant
            Handler(Looper.getMainLooper()).postDelayed({
                val currentState = getCurrentIgnitionState()
                AppLogger.i(TAG, "  IGNITION: état initial lu = $currentState (${ignitionStateName(currentState)})")
                if (currentState > 0) {
                    dispatchIgnitionState(currentState)
                }
            }, 300L)

        } catch (e: Exception) {
            AppLogger.w(TAG, "  IGNITION: registerCallback error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun dispatchIgnitionState(state: Int) {
        val toNotify = ignitionCallbacks.toList()
        if (toNotify.isEmpty()) return
        Handler(Looper.getMainLooper()).post { toNotify.forEach { it(state) } }
    }

    private fun ignitionStateName(state: Int) = when (state) {
        IgnitionState.ON        -> "ON/READY"
        IgnitionState.OFF       -> "OFF"
        IgnitionState.ACC       -> "ACC"
        IgnitionState.LOCK      -> "LOCK"
        IgnitionState.START     -> "START"
        IgnitionState.UNDEFINED -> "UNDEFINED"
        else                    -> "?"
    }

    // -------------------------------------------------------------------------
    // Katman5 SWI69/SWI131 — ICarGeneralService via CarAdapterClient (queryClient(0x1))
    // -------------------------------------------------------------------------

    private data class Swi69Ctx(
        val ctx: Context,
        val adapterClass: Class<*>,
        val generalClientClass: Class<*>
    )

    private fun findSwi69Classes(context: Context): Swi69Ctx? {
        for (pkg in listOf(LAUNCHER69_PKG, VEHICLE_SETTING_PKG)) {
            try {
                val ctx = context.createPackageContext(
                    pkg,
                    android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
                )
                return Swi69Ctx(
                    ctx,
                    ctx.classLoader.loadClass(CAR_ADAPTER_CLASS),
                    ctx.classLoader.loadClass(CAR_GENERAL_CLIENT_CLASS)
                )
            } catch (_: Exception) {}
        }
        return null
    }

    private fun initKatman5Swi69(context: Context) {
        if (sVcmCallbackRegistered) return

        val classes = findSwi69Classes(context) ?: run {
            AppLogger.w(TAG, "  Katman5 SWI69: CarAdapterClient/CarGeneralClient introuvable — retry in 10s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman5Swi69(context.applicationContext) }, 10_000)
            return
        }
        val (_, adapterClass, generalClientClass) = classes
        AppLogger.i(TAG, "  Katman5 SWI69: classes chargées ✓")

        fun trySetupGeneralClient(): Boolean {
            if (sVcmCallbackRegistered) return true

            val adapter = try {
                adapterClass.getMethod("getInstance", Context::class.java)
                    .invoke(null, context.applicationContext)
            } catch (_: Exception) { null } ?: return false

            val ibinder = try {
                adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
                    .invoke(adapter, BIND_CODE_CAR_GENERAL) as? IBinder
            } catch (_: Exception) { null } ?: run {
                AppLogger.d(TAG, "  Katman5 SWI69: queryClient(0x${BIND_CODE_CAR_GENERAL.toString(16)}) → null")
                return false
            }

            val client = try {
                generalClientClass.getConstructor(IBinder::class.java).newInstance(ibinder)
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman5 SWI69: CarGeneralClient ctor error: ${e.message}")
                return false
            }
            sCarGeneral = client   // conservé pour la luminosité écran A9 (setScreenBrightness)

            val registMethod = generalClientClass.methods.firstOrNull {
                it.name == "registListener" && it.parameterCount == 1
            } ?: run {
                AppLogger.w(TAG, "  Katman5 SWI69: registListener non trouvé")
                return false
            }

            val callbackType = registMethod.parameterTypes[0]
            if (!callbackType.isInterface) {
                AppLogger.w(TAG, "  Katman5 SWI69: callback non-interface — proxy impossible")
                return false
            }

            // Binder réel nécessaire pour l'enregistrement cross-process via AIDL.
            // ICarGeneralService est dans un processus distant (com.saicmotor.caradapter) :
            // registListener() appelle writeStrongBinder(callback.asBinder()) — un proxy qui
            // retourne null pour asBinder() transmettrait un binder null au service, qui ne
            // pourrait jamais rappeler. On crée donc un Binder concret qui implémente onTransact
            // pour le code 0x7 (TRANSACTION_onIgnitionStateChange, identique sur SWI69 et SWI131).
            val callbackBinder = object : android.os.Binder() {
                override fun onTransact(
                    code: Int, data: android.os.Parcel,
                    reply: android.os.Parcel?, flags: Int
                ): Boolean {
                    return when (code) {
                        0x7 -> { // TRANSACTION_onIgnitionStateChange
                            data.enforceInterface("com.saicmotor.carapi.general.ICarGeneralCallback")
                            val ignition = data.readInt()
                            AppLogger.i(TAG, "  Katman5 SWI69: onTransact ignition=$ignition (${carIgnitionName(ignition)})")
                            dispatchVehicleConditionIgnition(ignition)
                            reply?.writeNoException()
                            true
                        }
                        else -> super.onTransact(code, data, reply, flags)
                    }
                }
            }

            val proxy = try {
                java.lang.reflect.Proxy.newProxyInstance(
                    callbackType.classLoader, arrayOf(callbackType)
                ) { _, method, args ->
                    when (method.name) {
                        "onIgnitionStateChange" -> {
                            // Chemin in-process (rare) — le service appelle directement l'interface
                            val ignition = args?.get(0) as? Int
                            if (ignition != null) {
                                AppLogger.i(TAG, "  Katman5 SWI69: ignition=$ignition (${carIgnitionName(ignition)})")
                                dispatchVehicleConditionIgnition(ignition)
                            }
                        }
                        "asBinder" -> return@newProxyInstance callbackBinder
                    }
                    null
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman5 SWI69: proxy creation error: ${e.message}")
                return false
            }

            return try {
                registMethod.invoke(client, proxy)
                sVcmListener = callbackBinder  // référence forte sur le Binder pour éviter le GC
                sVcmCallbackRegistered = true
                AppLogger.i(TAG, "  Katman5 SWI69: ICarGeneralCallback enregistré ✓")

                val toNotify = katman5ReadyListeners.toList()
                katman5ReadyListeners.clear()
                Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val ignition = generalClientClass.getMethod("getIgnitionState").invoke(client) as? Int
                        if (ignition != null) {
                            AppLogger.i(TAG, "  Katman5 SWI69: état initial ignition=$ignition")
                            dispatchVehicleConditionIgnition(ignition)
                        }
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "  Katman5 SWI69: getIgnitionState error: ${e.message}")
                    }
                }, 500L)

                true
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman5 SWI69: registListener error: ${e.message}")
                false
            }
        }

        if (!trySetupGeneralClient()) {
            val h = Handler(Looper.getMainLooper())
            listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L).forEach { delay ->
                h.postDelayed({ if (!sVcmCallbackRegistered) trySetupGeneralClient() }, delay)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Katman5 — VehicleConditionManager (IVehicleConditionService via IHubService)
    // SWI133 / SWI68 / SWI165
    // -------------------------------------------------------------------------

    private fun initKatman5(context: Context) {
        if (sVcmCallbackRegistered) return

        val launcherCtx = listOf(LAUNCHER68_PKG, LAUNCHER69_PKG).firstNotNullOfOrNull { pkg ->
            try {
                context.createPackageContext(
                    pkg,
                    android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
                )
            } catch (_: Exception) { null }
        } ?: run {
            AppLogger.w(TAG, "  Katman5: launcher package introuvable")
            return
        }

        val vcmClass = try {
            launcherCtx.classLoader.loadClass(VCM_CLASS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman5: classe VCM non trouvée: ${e.message} — retry in 10s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman5(context.applicationContext) }, 10_000)
            return
        }

        // Tentative 1 : singleton déjà initialisé par le launcher
        val existing = try {
            val f = vcmClass.getDeclaredField("sVehicleConditionManager")
            f.isAccessible = true
            f.get(null)
        } catch (_: Exception) { null }

        if (existing != null) {
            AppLogger.i(TAG, "  Katman5: singleton VCM déjà existant ✓")
            sVcm = existing
            setupVcmCallback(existing, launcherCtx)
            return
        }

        // Tentative 2 : appeler init(Context, IVehicleServiceListener)
        val initMethod = vcmClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }

        if (initMethod != null) {
            val listenerType = initMethod.parameterTypes[1]
            val listenerArg: Any? = if (listenerType.isInterface) {
                try {
                    java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.classLoader, arrayOf(listenerType)
                    ) { _, method, args ->
                        when (method.name) {
                            "onServiceConnected" -> {
                                AppLogger.i(TAG, "  Katman5: onServiceConnected ✓")
                                val mgr = args?.getOrNull(0)
                                    ?.takeIf { it.javaClass.name.contains("VehicleConditionManager") }
                                val instance = mgr ?: try {
                                    val f = vcmClass.getDeclaredField("sVehicleConditionManager")
                                    f.isAccessible = true
                                    f.get(null)
                                } catch (_: Exception) { null }
                                if (instance != null) {
                                    sVcm = instance
                                    setupVcmCallback(instance, launcherCtx)
                                }
                            }
                            "onServiceDisconnected" -> {
                                AppLogger.w(TAG, "  Katman5: onServiceDisconnected")
                                sVcmCallbackRegistered = false
                                sVcmListener = null
                            }
                        }
                        null
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "  Katman5: proxy init error: ${e.message}")
                    null
                }
            } else null

            try {
                initMethod.invoke(null, context.applicationContext, listenerArg)
                AppLogger.i(TAG, "  Katman5: VehicleConditionManager.init() called")
            } catch (e: Exception) {
                AppLogger.w(TAG, "  Katman5: init() error: ${e.message}")
            }
        } else {
            AppLogger.w(TAG, "  Katman5: init(Context, listener) non trouvé")
        }

        // Retries — singleton disponible après connexion asynchrone
        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (!sVcmCallbackRegistered) {
                    val mgr = try {
                        val f = vcmClass.getDeclaredField("sVehicleConditionManager")
                        f.isAccessible = true
                        f.get(null)
                    } catch (_: Exception) { null }
                    if (mgr != null && sVcm == null) {
                        AppLogger.i(TAG, "  Katman5: singleton récupéré @${delay}ms")
                        sVcm = mgr
                        setupVcmCallback(mgr, launcherCtx)
                    } else if (sVcm != null && !sVcmCallbackRegistered) {
                        setupVcmCallback(sVcm!!, launcherCtx)
                    }
                }
            }, delay)
        }
    }

    private fun setupVcmCallback(vcm: Any, launcherCtx: Context) {
        if (sVcmCallbackRegistered) return

        val registerMethod = vcm.javaClass.methods.firstOrNull { m ->
            m.name == "registerVehicleConditionCallback" && m.parameterCount == 1
        } ?: vcm.javaClass.methods.firstOrNull { m ->
            m.name.startsWith("register") && m.parameterCount == 1 &&
            m.parameterTypes[0].isInterface &&
            m.parameterTypes[0].methods.any { it.name.contains("ConditionChange", ignoreCase = true) }
        } ?: run {
            AppLogger.w(TAG, "  Katman5: registerVehicleConditionCallback non trouvé — méthodes: ${
                vcm.javaClass.methods.filter { it.name.startsWith("register") }.joinToString { it.name }
            }")
            return
        }

        val callbackType = registerMethod.parameterTypes[0]
        AppLogger.i(TAG, "  Katman5: callback type = ${callbackType.name}")

        val proxy = try {
            java.lang.reflect.Proxy.newProxyInstance(
                callbackType.classLoader, arrayOf(callbackType)
            ) { _, method, args ->
                if (method.name.contains("ChangeEvent", ignoreCase = true) && args != null) {
                    val bean = args[0] ?: return@newProxyInstance null
                    try {
                        val ignition = bean.javaClass.getMethod("getVehicleIgnition").invoke(bean) as? Int
                        if (ignition != null) {
                            AppLogger.i(TAG, "  Katman5 event: ignition=$ignition (${carIgnitionName(ignition)})")
                            dispatchVehicleConditionIgnition(ignition)
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "  Katman5: onChangeEvent parse error: ${e.message}")
                    }
                }
                null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman5: proxy creation error: ${e.message}")
            return
        }

        try {
            registerMethod.invoke(vcm, proxy)
            sVcmListener = proxy
            sVcmCallbackRegistered = true
            AppLogger.i(TAG, "  Katman5: callback enregistré ✓")

            val toNotify = katman5ReadyListeners.toList()
            katman5ReadyListeners.clear()
            Handler(Looper.getMainLooper()).post { toNotify.forEach { it() } }

            // Lecture immédiate (le callback ne se déclenche que sur CHANGEMENT)
            Handler(Looper.getMainLooper()).postDelayed({
                val ignition = try {
                    vcm.javaClass.getMethod("getVehicleIgnition").invoke(vcm) as? Int
                } catch (_: Exception) { null }
                if (ignition != null) {
                    AppLogger.i(TAG, "  Katman5: état initial = $ignition (${carIgnitionName(ignition)})")
                    dispatchVehicleConditionIgnition(ignition)
                }
            }, 500L)

        } catch (e: Exception) {
            AppLogger.w(TAG, "  Katman5: registerVehicleConditionCallback error: ${e.message}")
        }
    }

    private fun dispatchVehicleConditionIgnition(state: Int) {
        // Ne dispatcher que si l'état change réellement — évite les faux RUN répétés
        // que VehicleConditionManager envoie à chaque changement de condition véhicule
        // (changement de rapport D/N/R, etc.) alors que la voiture est déjà en RUN.
        if (state == sLastVcmIgnitionState) return
        sLastVcmIgnitionState = state
        val toNotify = vehicleConditionCallbacks.toList()
        if (toNotify.isEmpty()) return
        Handler(Looper.getMainLooper()).post { toNotify.forEach { it(state) } }
    }

    private fun carIgnitionName(state: Int) = when (state) {
        CarIgnitionItem.OFF       -> "OFF"
        CarIgnitionItem.ACCESSORY -> "ACC"
        CarIgnitionItem.RUN       -> "RUN/READY"
        CarIgnitionItem.CRANK     -> "CRANK"
        else                      -> "?(${state})"
    }

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    fun setDriveModeListener(listener: DriveModeListener?) { sDriveModeListener = listener }
    fun setHvacListener(listener: HvacListener?) { sHvacListener = listener }

    // -------------------------------------------------------------------------
    // Diagnostic
    // -------------------------------------------------------------------------

    /** Retourne true si le binder IVehicleSettingService est disponible. */
    fun isVehicleBinderAvailable(): Boolean = sVehicleBinder != null

    /**
     * Génère un rapport de diagnostic complet :
     * état des services, tests binder SWI132 en temps réel, dump AppLogger.
     * À appeler sur Dispatchers.IO (les TX binder sont bloquants).
     */
    fun buildDiagnosticReport(appVersion: String): String {
        val sb  = StringBuilder()
        val gen = FirmwareInfo.getGeneration()
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val now = sdf.format(java.util.Date())

        sb.appendLine("══ MG4Control — Diagnostic ══")
        sb.appendLine("Generated : $now")
        sb.appendLine("App       : v$appVersion")
        sb.appendLine("Firmware  : ${gen.name}")
        sb.appendLine()

        sb.appendLine("── Services ──")
        sb.appendLine("Katman1 CPM  : ${if (sCarPropertyManager != null) "✓" else "✗"}")
        sb.appendLine("Katman1 HVAC : ${if (sCarHvacManager    != null) "✓" else "✗"}")
        sb.appendLine("Katman4 créé : ${if (isKatman4VpmCreated())      "✓" else "✗"}")
        sb.appendLine("Katman4 prêt : ${if (isKatman4Ready())           "✓" else "✗"}")
        sb.appendLine("Binder vsett : ${if (sVehicleBinder    != null) "✓ OK" else "✗ null"}")
        val katman5Path = if (FirmwareInfo.isNewGenVsm() || gen == FirmwareInfo.Gen.SWI132)
            "ICarGeneralService" else "VehicleConditionMgr"
        sb.appendLine("Katman5 prêt : ${if (sVcmCallbackRegistered) "✓ ($katman5Path)" else "✗ ($katman5Path)"}")
        sb.appendLine("Katman5 ign  : ${carIgnitionName(sLastVcmIgnitionState)} ($sLastVcmIgnitionState)")
        sb.appendLine()

        if (gen == FirmwareInfo.Gen.SWI132) {

            // ── Binder IVehicleSettingService ─────────────────────────────
            sb.appendLine("── SWI132 Binder (IVehicleSettingService) ──")
            val binderOk = sVehicleBinder != null
            sb.appendLine("Binder présent   : ${if (binderOk) "✓" else "✗ null → toutes alertes KO"}")
            if (binderOk) {
                val alive = try { sVehicleBinder!!.pingBinder() } catch (_: Exception) { false }
                sb.appendLine("pingBinder       : ${if (alive) "✓ vivant" else "✗ mort"}")
                val actualDesc = try { sVehicleBinder!!.interfaceDescriptor ?: "null" }
                                 catch (_: Exception) { "exception" }
                sb.appendLine("Descriptor attendu : $DESCRIPTOR_VSM132")
                sb.appendLine("Descriptor réel    : $actualDesc${
                    if (actualDesc == DESCRIPTOR_VSM132) " ✓" else " ← MISMATCH !"}")
            }
            sb.appendLine()

            // ── Alertes — lecture brute + interprétation ──────────────────
            fun fmtRaw(raw: Int, onVal: Int = 1): String = when {
                raw < 0  -> "$raw ← ERREUR (SELinux ? binder mort ?)"
                raw == onVal -> "$raw → ON ✓"
                raw == 0 -> "$raw → OFF"
                else     -> "$raw → ?"
            }

            sb.appendLine("── SWI132 Alertes (GET) ──")
            val rawOverspeed  = swi132BinderGet(VSM132_TX_GET_OVERSPEED)
            val rawSlif       = swi132BinderGet(VSM132_TX_GET_SLIF)
            val rawSpeedLimit = swi132BinderGet(VSM132_TX_GET_SPEED_LIMIT)
            sb.appendLine("getOverSpeedSoundMode  (0x129) : ${fmtRaw(rawOverspeed)}")
            sb.appendLine("getSLIFWarningState    (0x058) : ${fmtRaw(rawSlif)}")
            sb.appendLine("getSpeedLimitSoundMode (0x12b) : ${fmtRaw(rawSpeedLimit)}")
            sb.appendLine()

            // ── Alertes — test SET aller-retour (écrit la valeur courante) ─
            // Si la valeur brute est lisible (≥ 0), on réécrit la même valeur pour tester
            // que le SET passe (sans modifier l'état réel de la voiture).
            sb.appendLine("── SWI132 Alertes (SET round-trip) ──")
            if (rawOverspeed >= 0) {
                val setOk = swi132BinderSet(VSM132_TX_OVERSPEED_SOUND, rawOverspeed)
                val verify = swi132BinderGet(VSM132_TX_GET_OVERSPEED)
                sb.appendLine("setOverSpeedSoundMode  (0x128) : ${if (setOk) "✓ écrit" else "✗ échec"}" +
                    " → relecture : ${fmtRaw(verify)}")
            } else {
                sb.appendLine("setOverSpeedSoundMode  (0x128) : skip (GET KO)")
            }
            if (rawSpeedLimit >= 0) {
                val setOk = swi132BinderSet(VSM132_TX_SPEED_LIMIT, rawSpeedLimit)
                val verify = swi132BinderGet(VSM132_TX_GET_SPEED_LIMIT)
                sb.appendLine("setSpeedLimitSoundMode (0x12a) : ${if (setOk) "✓ écrit" else "✗ échec"}" +
                    " → relecture : ${fmtRaw(verify)}")
            } else {
                sb.appendLine("setSpeedLimitSoundMode (0x12a) : skip (GET KO)")
            }
            if (rawSlif >= 0) {
                val setOk = swi132BinderSet(VSM132_TX_SLIF_WARNING, rawSlif)
                val verify = swi132BinderGet(VSM132_TX_GET_SLIF)
                sb.appendLine("setSLIFWarningState    (0x057) : ${if (setOk) "✓ écrit" else "✗ échec"}" +
                    " → relecture : ${fmtRaw(verify)}")
            } else {
                sb.appendLine("setSLIFWarningState    (0x057) : skip (GET KO)")
            }
            sb.appendLine()

            // ── CarVehicleSettingClient (Katman4) ─────────────────────────
            sb.appendLine("── SWI132 CarVehicleSettingClient (sVsm=${if (sVsm != null) "✓" else "✗ null"}) ──")
            fun vsmGet(method: String): Int = try {
                (callVsm(method) as? Int) ?: -1
            } catch (_: Exception) { -1 }
            fun fmtVsm(v: Int, ok: String) = if (v >= 0) "$v → $ok" else "-1 ← ERREUR (méthode absente ou sVsm null)"

            // ACC/TJA
            val accTjaRaw = getAccTjaMode()
            sb.appendLine("getAccTjaState   : ${when (accTjaRaw) {
                Swi68Mode.OFF -> "4 → OFF"
                Swi68Mode.ACC -> "1 → ACC"
                Swi68Mode.TJA -> "2 → TJA"
                -1            -> "-1 ← ERREUR"
                else          -> "$accTjaRaw → ?"
            }}")

            // AEB
            val fcwRaw = vsmGet("getFcwState")
            sb.appendLine("getFcwState      : ${when (fcwRaw) {
                2 -> "2 → AEB ON" ; 1 -> "1 → AEB OFF" ; -1 -> "-1 ← ERREUR" ; else -> "$fcwRaw → ?"
            }}")
            val fcwModeRaw = vsmGet("getFcwAutoBrakeMode")
            sb.appendLine("getFcwAutoBreak  : ${when (fcwModeRaw) {
                1 -> "1 → Alerte" ; 2 -> "2 → Al.+Frein" ; -1 -> "-1 ← ERREUR" ; else -> "$fcwModeRaw → ?"
            }}")
            val fcwSenRaw = vsmGet("getFcwSensitivity")
            sb.appendLine("getFcwSensitiv.  : ${when (fcwSenRaw) {
                1 -> "1 → Faible" ; 2 -> "2 → Standard" ; 3 -> "3 → Élevé" ; -1 -> "-1 ← ERREUR" ; else -> "$fcwSenRaw → ?"
            }}")

            // ELK
            val lasRaw = vsmGet("getLasMode")
            sb.appendLine("getLasMode (ELK) : ${when (lasRaw) {
                1 -> "1 → OFF" ; 2 -> "2 → Alerte" ; 3 -> "3 → Assist" ; 5 -> "5 → Urgence"
                -1 -> "-1 ← ERREUR" ; else -> "$lasRaw → ?"
            }}")

            // ── ALERTES via VSM (nouveau path — confirmé dans smali SWI132) ─
            sb.appendLine()
            sb.appendLine("── SWI132 Alertes via VSM (ICarVehicleSettingService) ──")
            val vsmOverspeed = vsmGet("getOverSpeedSoundMode")
            sb.appendLine("getOverSpeedSoundMode  : ${when {
                vsmOverspeed < 0  -> "-1 ← ERREUR (méthode absente ?)"
                vsmOverspeed == 0 -> "0 → OFF"
                else              -> "$vsmOverspeed → ON"
            }}")
            val vsmSpeedLimit = vsmGet("getSpeedLimitSoundMode")
            sb.appendLine("getSpeedLimitSoundMode : ${when {
                vsmSpeedLimit < 0  -> "-1 ← ERREUR (méthode absente ?)"
                vsmSpeedLimit == 0 -> "0 → OFF"
                else               -> "$vsmSpeedLimit → ON"
            }}")

            // TSR / SLIF
            val vsmSlif = vsmGet("getSLIFWarningState")
            sb.appendLine("getSLIFWarningState    : ${when {
                vsmSlif < 0 -> "-1 ← ERREUR (méthode absente ?)"
                vsmSlif == 0 -> "0 → TSR ON (convention SWI69)"
                vsmSlif == 1 -> "1 → TSR OFF (convention SWI69)"
                else -> "$vsmSlif → ?"
            }}")

            // Son d'alerte de voie (LAS)
            val vsmLasSound = vsmGet("getLasWarningSound")
            sb.appendLine("getLasWarningSound     : ${when {
                vsmLasSound < 0 -> "-1 ← ERREUR (méthode absente ?)"
                vsmLasSound == 2 -> "2 → ON"
                vsmLasSound == 1 -> "1 → OFF"
                else -> "$vsmLasSound → ?"
            }}")

            // Test SET round-trip via VSM (sans modifier l'état réel : on réécrit la valeur courante)
            sb.appendLine()
            sb.appendLine("── SWI132 SET round-trip via VSM ──")
            if (vsmOverspeed >= 0) {
                val setOk = callVsmVoid("setOverSpeedSoundMode", vsmOverspeed)
                val verify = vsmGet("getOverSpeedSoundMode")
                sb.appendLine("setOverSpeedSoundMode  : ${if (setOk) "✓" else "✗"} → relecture : $verify${
                    if (setOk && verify == vsmOverspeed) " ✓ cohérent" else if (setOk) " ← valeur changée !" else ""}")
            } else {
                sb.appendLine("setOverSpeedSoundMode  : skip (GET KO)")
            }
            if (vsmSpeedLimit >= 0) {
                val setOk = callVsmVoid("setSpeedLimitSoundMode", vsmSpeedLimit)
                val verify = vsmGet("getSpeedLimitSoundMode")
                sb.appendLine("setSpeedLimitSoundMode : ${if (setOk) "✓" else "✗"} → relecture : $verify${
                    if (setOk && verify == vsmSpeedLimit) " ✓ cohérent" else if (setOk) " ← valeur changée !" else ""}")
            } else {
                sb.appendLine("setSpeedLimitSoundMode : skip (GET KO)")
            }
            if (vsmSlif >= 0) {
                val setOk = callVsmVoid("setSLIFWarningState", vsmSlif)
                val verify = vsmGet("getSLIFWarningState")
                sb.appendLine("setSLIFWarningState    : ${if (setOk) "✓" else "✗"} → relecture : $verify${
                    if (setOk && verify == vsmSlif) " ✓ cohérent" else if (setOk) " ← valeur changée !" else ""}")
            } else {
                sb.appendLine("setSLIFWarningState    : skip (GET KO)")
            }

            // Éco
            val enduranceRaw = vsmGet("getEnduranceMode")
            sb.appendLine()
            sb.appendLine("getEnduranceMode : ${when (enduranceRaw) {
                1 -> "1 → Éco ON" ; 0 -> "0 → Éco OFF" ; -1 -> "-1 ← ERREUR" ; else -> "$enduranceRaw → ?"
            }}")
            sb.appendLine()
        }

        sb.appendLine("── AppLogger (${AppLogger.entries.size} entrées) ──")
        AppLogger.entries.forEach { e ->
            sb.appendLine("[${e.time}] ${e.tag}: ${e.msg}")
        }

        return sb.toString()
    }

    // ── Contrôle audio (CarAdapterService vendor SAIC) — A9 uniquement ──────────
    //
    // Le service vendor `com.saicmotor.caradapter` (descripteur ICarAudioService)
    // n'existe QUE sur la famille A9 (SWI69/131/132). Sur old-SDK (SWI133/68/165)
    // il est absent → on ne tente même pas le bind (cf. hasAudioControl / initAudio).
    // Codes de transaction vérifiés identiques sur les 3 A9 (ICarAudioService$Stub).

    /** A9 (SWI69/131/132) : loudness via le service vendor caradapter (ICarAudioService). */
    private fun isA9Sound(): Boolean =
        FirmwareInfo.isNewGenVsm() || FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI132

    /** Ancien SDK (SWI133/68/165) : loudness via SmartSoundManager (SDK systemsettings). */
    private fun isOldSdkSound(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.SWI68 || gen == FirmwareInfo.Gen.SWI165
    }

    /** Onglet Audio (baisse volume à l'ouverture de porte) : uniquement là où c'est fonctionnel. */
    fun hasAudioControl(): Boolean = hasDoorVolumeFeature()

    private const val DESCRIPTOR_CARADAPTER = "com.saicmotor.carapi.ICarAdapterService"
    private const val TX_QUERY_AUDIO_CLIENT = 1
    private const val HELPER_AUDIO_CODE     = 10

    private const val AUDIO_SET_FADER_FRONT   = 12
    private const val AUDIO_SET_BALANCE_RIGHT = 13
    private const val AUDIO_SET_SPEED_VOL     = 17
    private const val AUDIO_GET_SPEED_VOL     = 18
    private const val AUDIO_SET_3D_EFFECT     = 26
    private const val AUDIO_GET_3D_EFFECT     = 27
    private const val AUDIO_SET_SOUND_FIELD   = 30
    private const val AUDIO_GET_BALANCE       = 31
    private const val AUDIO_GET_FADER         = 32
    private const val AUDIO_SET_BOSE_SOUND    = 36
    private const val AUDIO_GET_BOSE_SOUND    = 37
    private const val AUDIO_SET_TONE          = 40
    private const val AUDIO_GET_TONE          = 41

    @Volatile private var sCarAdapterBinder: IBinder? = null
    @Volatile private var sAudioHelper: IBinder? = null
    @Volatile private var sAudioDescriptor: String = ""
    @Volatile private var sAudioServiceConn: ServiceConnection? = null

    val isAudioAvailable: Boolean get() = sAudioHelper?.isBinderAlive == true

    fun initAudio(context: Context) {
        // Le bind caradapter ne concerne que l'A9. Sur old-SDK, le loudness passe par
        // SmartSoundManager (initialisé dans le flux Katman4), donc rien à binder ici.
        if (!isA9Sound()) return
        if (sAudioHelper?.isBinderAlive == true) return
        if (sAudioServiceConn != null) return
        val intent = Intent().apply {
            setClassName("com.saicmotor.caradapter", "com.saicmotor.caradapter.service.CarAdapterService")
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                sCarAdapterBinder = binder
                AppLogger.i(TAG, "  Audio: CarAdapterService connecté ✓")
                CoroutineScope(Dispatchers.IO).launch { tryGetAudioHelper() }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                sCarAdapterBinder = null; sAudioHelper = null
            }
        }
        sAudioServiceConn = conn
        try {
            val bound = context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                sAudioServiceConn = null
                Handler(Looper.getMainLooper()).postDelayed({ if (sAudioHelper == null) initAudio(context.applicationContext) }, 10_000L)
            }
        } catch (e: Exception) { sAudioServiceConn = null; AppLogger.w(TAG, "  Audio: bindService error: ${e.message}") }
    }

    private fun tryGetAudioHelper() {
        val svc = sCarAdapterBinder ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_CARADAPTER)
            data.writeInt(HELPER_AUDIO_CODE)
            if (svc.transact(TX_QUERY_AUDIO_CLIENT, data, reply, 0)) {
                reply.readException()
                val helper = reply.readStrongBinder()
                if (helper != null && helper.isBinderAlive) {
                    sAudioHelper = helper; sAudioDescriptor = helper.interfaceDescriptor ?: ""
                    AppLogger.i(TAG, "  Audio: helper OK descriptor='$sAudioDescriptor'")
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({ CoroutineScope(Dispatchers.IO).launch { tryGetAudioHelper() } }, 5_000)
                }
            }
        } finally { data.recycle(); reply.recycle() }
    }

    private fun audioGet(txCode: Int): Int {
        val h = sAudioHelper ?: return -1
        if (!h.isBinderAlive) { sAudioHelper = null; return -1 }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(sAudioDescriptor)
            if (h.transact(txCode, data, reply, 0)) { reply.readException(); reply.readInt() } else -1
        } catch (_: Exception) { -1 } finally { data.recycle(); reply.recycle() }
    }

    private fun audioSet(txCode: Int, value: Int): Boolean {
        val h = sAudioHelper ?: return false
        if (!h.isBinderAlive) { sAudioHelper = null; return false }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(sAudioDescriptor)
            data.writeInt(value)
            h.transact(txCode, data, reply, 0).also { if (it) reply.readException() }
        } catch (_: Exception) { false } finally { data.recycle(); reply.recycle() }
    }

    private const val AUDIO_TYPE_MIN  = 0
    private const val AUDIO_TYPE_MAX  = 3
    private const val AUDIO_LEVEL_MIN = -9
    private const val AUDIO_LEVEL_MAX =  9

    fun getBoseSoundType(): Int              = audioGet(AUDIO_GET_BOSE_SOUND)
    fun setBoseSoundType(t: Int): Boolean    = audioSet(AUDIO_SET_BOSE_SOUND, t.coerceIn(AUDIO_TYPE_MIN, AUDIO_TYPE_MAX))
    fun getAudioBalance(): Int               = audioGet(AUDIO_GET_BALANCE)
    fun setAudioBalance(v: Int): Boolean     = audioSet(AUDIO_SET_BALANCE_RIGHT, v.coerceIn(AUDIO_LEVEL_MIN, AUDIO_LEVEL_MAX))
    fun getAudioFader(): Int                 = audioGet(AUDIO_GET_FADER)
    fun setAudioFader(v: Int): Boolean       = audioSet(AUDIO_SET_FADER_FRONT, v.coerceIn(AUDIO_LEVEL_MIN, AUDIO_LEVEL_MAX))
    // ── Volume média — VOL_TYPE_MEDIA=0 ────────────────────────────────────────
    // Routage par firmware : old-SDK (133/68/165) → SmartSoundManager (reflection) ;
    // A9 (69/131/132) → ICarAudioService via binder caradapter (tx getMax=0x5 / getVol=0x6 / setVol=0x7).
    private const val VOL_TYPE_MEDIA    = 0
    private const val AUDIO_GET_MAX_VOL = 0x5
    private const val AUDIO_GET_VOLUME  = 0x6
    private const val AUDIO_SET_VOLUME  = 0x7

    private const val VOL_TAG = "MG4_VOL"

    /** Max du volume média (borne le slider). -1 si indisponible. */
    fun getMediaVolumeMax(): Int {
        val v = when {
            isOldSdkSound() -> smartSoundGetInt("getMaxVolume", VOL_TYPE_MEDIA)
            isA9Sound()     -> audioGetArg(AUDIO_GET_MAX_VOL, VOL_TYPE_MEDIA)
            else            -> -1
        }
        AppLogger.i(VOL_TAG, "getMediaVolumeMax = $v  [oldSdk=${isOldSdkSound()} a9=${isA9Sound()} smartSound=${sSmartSound != null} audioHelper=${sAudioHelper != null}]")
        logMediaVolumeDiag()   // A9 : compare type-0 vs group-id (no-op ailleurs)
        return v
    }

    /** Volume média courant. -1 si indisponible. */
    fun getMediaVolume(): Int {
        val v = when {
            isOldSdkSound() -> smartSoundGetInt("getVolume", VOL_TYPE_MEDIA)
            isA9Sound()     -> audioGetArg(AUDIO_GET_VOLUME, VOL_TYPE_MEDIA)
            else            -> -1
        }
        AppLogger.i(VOL_TAG, "getMediaVolume = $v")
        return v
    }

    /** Fixe le volume média. setVolume(type, niveau, flags=0). */
    fun setMediaVolume(level: Int): Boolean {
        val ok = when {
            isOldSdkSound() -> smartSoundSetVolume(level)
            isA9Sound()     -> audioSet3(AUDIO_SET_VOLUME, VOL_TYPE_MEDIA, level, 0)
            else            -> false
        }
        AppLogger.i(VOL_TAG, "setMediaVolume($level) = $ok")
        return ok
    }

    // old-SDK : SmartSoundManager (reflection)
    private fun smartSoundGetInt(method: String, arg: Int): Int {
        val m = sSmartSound ?: return -1
        return try { m.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(m, arg) as? Int ?: -1 }
        catch (_: Exception) { -1 }
    }
    private fun smartSoundSetVolume(level: Int): Boolean {
        val m = sSmartSound ?: return false
        return try {
            m.javaClass.getMethod("setVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(m, VOL_TYPE_MEDIA, level, 0); true
        } catch (e: Exception) { AppLogger.w(TAG, "setMediaVolume oldSdk exc: ${e.message}"); false }
    }

    // A9 : ICarAudioService via binder. getVolume(usage)/getMaxVolume(usage) = 1 arg ; setVolume(usage,val,flags) = 3 args.
    private fun audioGetArg(txCode: Int, arg: Int): Int {
        val h = sAudioHelper ?: return -1
        if (!h.isBinderAlive) { sAudioHelper = null; return -1 }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(sAudioDescriptor); data.writeInt(arg)
            if (h.transact(txCode, data, reply, 0)) { reply.readException(); reply.readInt() } else -1
        } catch (_: Exception) { -1 } finally { data.recycle(); reply.recycle() }
    }
    private fun audioSet3(txCode: Int, a: Int, b: Int, c: Int): Boolean {
        val h = sAudioHelper ?: return false
        if (!h.isBinderAlive) { sAudioHelper = null; return false }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(sAudioDescriptor); data.writeInt(a); data.writeInt(b); data.writeInt(c)
            h.transact(txCode, data, reply, 0).also { if (it) reply.readException() }
        } catch (_: Exception) { false } finally { data.recycle(); reply.recycle() }
    }

    // Diagnostic A9 : le param de getVolume/getMaxVolume est-il un "type" (0=media) ou un
    // "group id" (AAOS) ? On logge les deux pour comparer au max réel de la voiture.
    private const val AUDIO_GET_GROUP_FOR_USAGE = 0xe   // getVolumeGroupIdForUsage(usage)
    private const val USAGE_MEDIA_AAOS = 1              // AudioAttributes.USAGE_MEDIA
    fun logMediaVolumeDiag() {
        if (!isA9Sound()) return
        val maxT = audioGetArg(AUDIO_GET_MAX_VOL, VOL_TYPE_MEDIA)
        val volT = audioGetArg(AUDIO_GET_VOLUME,  VOL_TYPE_MEDIA)
        val grp  = audioGetArg(AUDIO_GET_GROUP_FOR_USAGE, USAGE_MEDIA_AAOS)
        val maxG = if (grp in 0..64) audioGetArg(AUDIO_GET_MAX_VOL, grp) else -1
        val volG = if (grp in 0..64) audioGetArg(AUDIO_GET_VOLUME,  grp) else -1
        AppLogger.i(VOL_TAG, "A9 diag: parType0[max=$maxT vol=$volT]  groupForUsage(MEDIA)=$grp  parGroup[max=$maxG vol=$volG]")
    }

    // ── Baisse du volume à l'ouverture d'une porte avant (v1 : SWI133) ──────────
    // Détection via l'API Car AOSP **CarPropertyManager** (service "property") + permission
    // CAR_VENDOR_EXTENSION (déjà déclarée). IDs portes AVANT confirmés par un utilisateur :
    // FL/FR "ratio" (taux d'ouverture, >0 = ouverte) + "mode". Le SDK SAIC (getIntProperty)
    // ne les lisait pas. Poll ~700ms, change-only (log MG4_DOOR), baisse à l'ouverture.
    // Pas de restauration. Connexion Car async (createCar + ServiceConnection).

    private const val DOORWATCH_TAG = "MG4_DOOR"
    // Signal d'ouverture confirmé sur SWI133 : DLOCK_DOOR_OPEN_STS (propriété de zone PORTE),
    // areaId 0x1 = porte AV-gauche, 0x4 = AV-droite ; valeur 1 = ouverte, 0 = fermée.
    private const val DOOR_OPEN_PROP = 0x2640c623
    private val DOOR_FRONT_AREAS = intArrayOf(0x1, 0x4)
    private val sDoorReadLast = HashMap<Int, Int>()
    @Volatile private var sDoorSubProperty = false   // voie A : property.registerListener attachée
    @Volatile private var sDoorSubDoorlock = false   // voie B : doorlock.registerCallback attachée
    @Volatile private var sDoorWatcherOn = false
    @Volatile private var sCarInstance: Any? = null
    @Volatile private var sCarPropMgr: Any? = null   // CarPropertyManager (service "property")
    @Volatile private var sCarDoorMgr: Any? = null   // CarDoorLockManager (service "doorlock")
    @Volatile private var sDoorConnecting = false
    @Volatile private var sAnyFrontOpenPrev = false
    @Volatile private var sVolumeBeforeDrop = -1     // volume mémorisé à l'ouverture (pour restauration)

    /** Baisse du volume à l'ouverture de porte : détection DLOCK_DOOR_OPEN_STS via CarPropertyManager.
     *  Lisible/fonctionnel uniquement sur SWI132 et SWI133 ; ailleurs le prop n'est pas exposé à l'app. */
    fun hasDoorVolumeFeature(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI132 || gen == FirmwareInfo.Gen.SWI133
    }

    private fun doorVolumeEnabled(): Boolean =
        sAppContext?.getSharedPreferences("mg4_settings", 0)?.getBoolean("door_volume_enabled", false) ?: false

    private fun doorVolumeLevel(): Int =
        sAppContext?.getSharedPreferences("mg4_settings", 0)?.getInt("door_volume_level", 0) ?: 0

    private fun doorRestoreEnabled(): Boolean =
        sAppContext?.getSharedPreferences("mg4_settings", 0)?.getBoolean("door_volume_restore", false) ?: false

    /** areaIds des portes choisies par l'utilisateur (gauche=0x1, droite=0x4). */
    private fun doorTriggerAreas(): IntArray {
        val p = sAppContext?.getSharedPreferences("mg4_settings", 0) ?: return DOOR_FRONT_AREAS
        val list = ArrayList<Int>(2)
        if (p.getBoolean("door_volume_left", true)) list += 0x1
        if (p.getBoolean("door_volume_right", true)) list += 0x4
        return list.toIntArray()
    }

    /** Démarrage auto au boot (appelé par init) : ne lance le watcher que si la feature est activée. */
    fun startDoorWatcherIfEnabled() {
        if (hasDoorVolumeFeature() && doorVolumeEnabled()) startDoorVolumeWatcher()
    }

    fun startDoorVolumeWatcher() {
        if (!hasDoorVolumeFeature()) return
        sDoorWatcherOn = true
        connectCarProperty()
    }

    fun stopDoorVolumeWatcher() {
        sDoorWatcherOn = false           // poll conservé ; on ne déclenche plus la baisse
        AppLogger.i(TAG, "  DoorVolumeWatcher: déclenchement désactivé")
    }

    /** Sonde du bouton Diagnostic : logge le volume + l'état des portes à l'instant du clic. */
    fun runDoorVolumeDiag() {
        AppLogger.i(VOL_TAG, "── DIAG (bouton Diagnostic) ──")
        getMediaVolumeMax()
        getMediaVolume()
        connectCarProperty()   // idempotent ; normalement déjà connecté depuis l'init
        registerDoorCallback() // re-tente la souscription si pas encore posée
        probeDoorSnapshot()
    }

    private fun probeDoorSnapshot() {
        if (sCarPropMgr == null && sCarDoorMgr == null) {
            AppLogger.w(DOORWATCH_TAG, "DIAG porte: aucun manager Car (createCar échec ?)")
            return
        }
        AppLogger.i(DOORWATCH_TAG, "DIAG porte: property=${sCarPropMgr != null} doorlock=${sCarDoorMgr != null}")
        for (area in DOOR_FRONT_AREAS) {
            val v = readDoorOpen(area)
            AppLogger.i(DOORWATCH_TAG, "DIAG area=0x${area.toString(16)} = ${v ?: "illisible"}")
        }
        AppLogger.i(DOORWATCH_TAG, "DIAG souscription: property=$sDoorSubProperty doorlock=$sDoorSubDoorlock état=" +
            if (sDoorReadLast.isEmpty()) "(aucun event reçu)"
            else sDoorReadLast.entries.joinToString { "0x${it.key.toString(16)}=${it.value}" })
    }

    /** Connexion (async) à l'API Car AOSP → CarPropertyManager ("property") ET CarDoorLockManager
     *  ("doorlock"). Selon le firmware, la porte est exposée par l'un ou l'autre → on lit via les deux. */
    private fun connectCarProperty() {
        if (sCarPropMgr != null || sCarDoorMgr != null || sDoorConnecting) return
        val ctx = sAppContext ?: return
        sDoorConnecting = true
        try {
            val carCls = ctx.classLoader.loadClass("android.car.Car")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try {
                        val car = sCarInstance ?: return
                        val getMgr = carCls.getMethod("getCarManager", String::class.java)
                        sCarPropMgr = try { getMgr.invoke(car, "property") } catch (_: Exception) { null }
                        sCarDoorMgr = try { getMgr.invoke(car, "doorlock") } catch (_: Exception) { null }
                        AppLogger.i(DOORWATCH_TAG, "managers: property=${sCarPropMgr != null} doorlock=${sCarDoorMgr != null}")
                        if (sCarPropMgr != null || sCarDoorMgr != null) startDoorPolling()
                        else AppLogger.w(DOORWATCH_TAG, "aucun manager porte disponible")
                    } catch (e: Exception) { AppLogger.w(DOORWATCH_TAG, "getCarManager échec: ${e.message}") }
                }
                override fun onServiceDisconnected(name: ComponentName?) { sCarPropMgr = null; sCarDoorMgr = null; sDoorSubProperty = false; sDoorSubDoorlock = false }
            }
            val car = carCls.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, ctx, conn)
            sCarInstance = car
            try { carCls.getMethod("connect").invoke(car) } catch (_: Exception) {}
            AppLogger.i(DOORWATCH_TAG, "connexion Car (property + doorlock)…")
        } catch (e: Exception) {
            sDoorConnecting = false
            AppLogger.w(DOORWATCH_TAG, "Car createCar échec: ${e.message}")
        }
    }

    // Lit DLOCK_DOOR_OPEN_STS(areaId) via CarPropertyManager.getIntProperty puis, en repli,
    // via CarDoorLockManager.getProperty(Integer, propId, areaId).getValue(). null si aucun.
    private fun readDoorOpen(area: Int): Int? {
        sCarPropMgr?.let { m ->
            try {
                return m.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(m, DOOR_OPEN_PROP, area) as? Int
            } catch (_: Exception) {}
        }
        sCarDoorMgr?.let { m ->
            try {
                val cpv = m.javaClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(m, Integer::class.java, DOOR_OPEN_PROP, area) ?: return null
                return cpv.javaClass.getMethod("getValue").invoke(cpv) as? Int
            } catch (_: Exception) {}
        }
        return null
    }

    private fun startDoorPolling() {
        registerDoorCallback()   // souscription ON_CHANGE (complète le poll)
        AppLogger.i(DOORWATCH_TAG, "watcher porte actif (property=${sCarPropMgr != null} doorlock=${sCarDoorMgr != null})")
        val h = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (sCarPropMgr == null && sCarDoorMgr == null) return
                // DLOCK_DOOR_OPEN_STS sur les portes avant (0x1 gauche / 0x4 droite).
                for (area in DOOR_FRONT_AREAS) {
                    val v = readDoorOpen(area) ?: continue
                    onDoorAreaValue(area, v)
                }
                h.postDelayed(this, 500L)
            }
        }
        h.post(poll)
    }

    /** Point d'entrée unique pour une valeur porte (poll OU événement de souscription).
     *  Met à jour l'état, log en change-only, puis réévalue le déclenchement volume. */
    @Synchronized
    private fun onDoorAreaValue(area: Int, v: Int) {
        val prev = sDoorReadLast[area]
        if (prev == null || prev != v) {
            sDoorReadLast[area] = v
            AppLogger.i(DOORWATCH_TAG, "area=0x${area.toString(16)} ${prev ?: "?"} → $v")
        }
        evaluateDoorTrigger()
    }

    /** Recalcule "au moins une porte choisie ouverte" à partir de l'état accumulé et applique
     *  la baisse (front d'ouverture) / la restauration (front de fermeture). Idempotent. */
    private fun evaluateDoorTrigger() {
        if (!(sDoorWatcherOn && doorVolumeEnabled())) return
        val triggerAreas = doorTriggerAreas()
        val anyOpen = triggerAreas.any { sDoorReadLast[it] == 1 }
        if (anyOpen && !sAnyFrontOpenPrev) {
            val level = doorVolumeLevel()
            CoroutineScope(Dispatchers.IO).launch {
                sVolumeBeforeDrop = getMediaVolume()
                val ok = setMediaVolume(level)
                AppLogger.i(DOORWATCH_TAG, "porte ouverte → vol $sVolumeBeforeDrop→$level = $ok")
            }
        } else if (!anyOpen && sAnyFrontOpenPrev) {
            val restore = sVolumeBeforeDrop
            sVolumeBeforeDrop = -1
            if (doorRestoreEnabled() && restore >= 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = setMediaVolume(restore)
                    AppLogger.i(DOORWATCH_TAG, "porte fermée → restauration vol $restore = $ok")
                }
            }
        }
        sAnyFrontOpenPrev = anyOpen
    }

    /** InvocationHandler commun aux deux interfaces de callback (onChangeEvent/onErrorEvent).
     *  Filtre DLOCK_DOOR_OPEN_STS et alimente onDoorAreaValue. Gère aussi les méthodes Object. */
    private fun doorEventHandler(src: String) = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "onChangeEvent" -> {
                try {
                    val cpv = args?.getOrNull(0)
                    if (cpv != null) {
                        val pid = cpv.javaClass.getMethod("getPropertyId").invoke(cpv) as? Int
                        val area = cpv.javaClass.getMethod("getAreaId").invoke(cpv) as? Int ?: 0
                        val raw = cpv.javaClass.getMethod("getValue").invoke(cpv)
                        val v = when (raw) {
                            is Boolean -> if (raw) 1 else 0
                            is Number  -> raw.toInt()
                            else       -> null
                        }
                        if (pid == DOOR_OPEN_PROP && v != null) {
                            AppLogger.i(DOORWATCH_TAG, "EVENT[$src] area=0x${area.toString(16)} = $v")
                            onDoorAreaValue(area, v)
                        }
                    }
                } catch (e: Exception) { AppLogger.w(DOORWATCH_TAG, "onChangeEvent: ${e.message}") }
                null
            }
            "onErrorEvent" -> { AppLogger.w(DOORWATCH_TAG, "EVENT erreur porte"); null }
            "hashCode"     -> System.identityHashCode(proxy)
            "equals"       -> proxy === args?.getOrNull(0)
            "toString"     -> "MG4DoorListener@" + Integer.toHexString(System.identityHashCode(proxy))
            else           -> null
        }
    }

    /**
     * S'abonne aux changements de DLOCK_DOOR_OPEN_STS. Nécessaire sur SWI69/131/68/165 où le prop
     * n'est PAS lisible à la demande (getProperty lève IllegalArgumentException "Failed to get value")
     * mais poussé en ON_CHANGE. IMPORTANT : le Proxy doit être défini par le classloader de l'APP
     * (android.car est en BootClassLoader → Proxy.newProxyInstance y échoue). On tente DEUX voies :
     *   A) CarPropertyManager.registerListener (service "property")
     *   B) CarDoorLockManager.registerCallback   (service "doorlock", comme la SystemUI d'origine)
     */
    private fun registerDoorCallback() {
        val cl = sAppContext?.classLoader ?: return

        // Voie A — CarPropertyManager.registerListener(CarPropertyEventListener, propId, rate).
        // rate=5f (et non 0f) : si le VHAL déclare la prop CONTINUOUS, un rate 0 = aucune mise à jour.
        if (!sDoorSubProperty) sCarPropMgr?.let { m ->
            try {
                val iface = cl.loadClass("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
                val proxy = Proxy.newProxyInstance(cl, arrayOf(iface), doorEventHandler("property"))
                val ok = m.javaClass.getMethod("registerListener", iface,
                    Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(m, proxy, DOOR_OPEN_PROP, 5f)
                sDoorSubProperty = true
                AppLogger.i(DOORWATCH_TAG, "souscription porte (property.registerListener rate=5) OK=$ok")
            } catch (e: Exception) {
                val c = e.cause ?: e
                AppLogger.w(DOORWATCH_TAG, "property.registerListener échec: ${c.javaClass.simpleName}: ${c.message}")
            }
        }

        // Voie B — CarDoorLockManager.registerCallback(CarDoorLockEventCallback) — voie de l'OEM
        if (!sDoorSubDoorlock) sCarDoorMgr?.let { m ->
            try {
                val iface = cl.loadClass("android.car.hardware.doorlock.CarDoorLockManager\$CarDoorLockEventCallback")
                val proxy = Proxy.newProxyInstance(cl, arrayOf(iface), doorEventHandler("doorlock"))
                m.javaClass.getMethod("registerCallback", iface).invoke(m, proxy)
                sDoorSubDoorlock = true
                AppLogger.i(DOORWATCH_TAG, "souscription porte (doorlock.registerCallback) OK")
            } catch (e: Exception) {
                val c = e.cause ?: e
                AppLogger.w(DOORWATCH_TAG, "doorlock.registerCallback échec: ${c.javaClass.simpleName}: ${c.message}")
            }
        }
    }

    fun getSpeedVolumeLevel(): Int           = audioGet(AUDIO_GET_SPEED_VOL)
    fun setSpeedVolumeLevel(l: Int): Boolean = audioSet(AUDIO_SET_SPEED_VOL, l.coerceIn(AUDIO_TYPE_MIN, AUDIO_TYPE_MAX))
    fun getSoundFieldType(): Int             = -1
    fun setSoundFieldType(t: Int): Boolean   = audioSet(AUDIO_SET_SOUND_FIELD, t)
    fun get3dEffectType(): Int               = audioGet(AUDIO_GET_3D_EFFECT)
    fun set3dEffectType(t: Int): Boolean     = audioSet(AUDIO_SET_3D_EFFECT, t.coerceIn(AUDIO_TYPE_MIN, AUDIO_TYPE_MAX))
    fun getToneControl(): Int                = audioGet(AUDIO_GET_TONE)
    fun setToneControl(v: Int): Boolean      = audioSet(AUDIO_SET_TONE, v.coerceIn(AUDIO_LEVEL_MIN, AUDIO_LEVEL_MAX))
}
