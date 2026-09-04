package com.mg4.control.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode
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

    // ⚠️ VehicleCONTROLManager, a ne confondre ni avec VehicleSettingManager (sVsm) ni avec
    // VehicleConditionManager (sVcm). C'est le seul a porter get/setEspSwitch sur SWI68/165 :
    // chercher ces methodes sur sVsm echouait en silence, l'ESC ne repondait donc pas.
    private const val VCONTROL_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleControlManager"
    private const val LAUNCHER68_PKG = "com.saicmotor.hmi.launcher"

    // Luminosité écran — ancien SDK (SWI133/68/165) : GeneralManager.setBrightness(Int)/getBrightness().
    // Plage native 0..255 (constantes DARKEST_VALUE=0x0 / BRIGHTEST_VALUE=0xff confirmées dans le smali).
    // A9 (SWI132/131/69) = phase 2 (setScreenBrightness(III), params non décodables sans SystemUI).
    private const val GENERAL_MANAGER_CLASS = "com.saicmotor.sdk.systemsettings.GeneralManager"
    private const val BRIGHTNESS_NATIVE_MAX = 255

    // Volume média — ancien SDK (SWI133/68/165) : SmartSoundManager.getVolume/setVolume/getMaxVolume(type).
    // Même SDK systemsettings/BaseManager que GeneralManager (singleton sInstance + init(Context, listener)).
    private const val SMART_SOUND_MANAGER_CLASS = "com.saicmotor.sdk.systemsettings.SmartSoundManager"
    // Public : le raccourci « luminosité - » doit clamper sur CE plancher pour que son log dise
    // la vérité. Le redéfinir de son côté le ferait diverger le jour où cette valeur change.
    const val BRIGHTNESS_MIN_PERCENT = 5   // plancher de sécurité : ne jamais éteindre l'écran

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
    @Volatile private var sVcontrol: Any? = null     // VehicleControlManager (ESC, SWI68/165)
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
        val raw = getFloatPropertyCPM(PROP_VEHICLE_SPEED, AREA_GLOBAL)
            ?: getFloatPropertyCPM(PROP_VEHICLE_SPEED, 0)
            ?: return null
        // ⚠️ La spec AOSP dit m/s, mais le VHAL SAIC renvoie DÉJÀ des km/h. Confirmé par
        // deux voies : (1) mesures terrain (refus à 30 km/h réels avec un seuil à 50, la
        // bascule tombait à ~14 = 50/3,6) ; (2) code OEM — CustomKeyHandler compare
        // getCarSpeed() à 15.0f pour couper la caméra 360, seuil qui n'a de sens qu'en km/h.
        // Un ancien `* 3.6f` triplait donc la vitesse et bloquait bien trop tôt.
        // La valeur est signée (négative en marche arrière) : c'est la vitesse absolue qui
        // compte pour savoir si le véhicule bouge.
        return kotlin.math.abs(raw)
    }

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
        // Température (sonde Diagnostic) — bind async du service clim SAIC ; no-op si absent.
        initAirCondition(context)
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
        tryInitVehicleControlManager(launcherCtx, context)   // ESC sur SWI68/165
        tryInitGeneralManager(launcherCtx, context)

        // 3c) SmartSoundManager pour SWI133 (loudness audio)
        tryInitSmartSoundManager(launcherCtx, context)

        // 4) Retries pour récupérer mIVehiclePropertyService et VSM133 une fois le service connecté
        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L).forEach { delay ->
            h.postDelayed({
                if (sVpmService == null) tryGetVpmService(sVpm ?: return@postDelayed)
                if (sVsm133 == null) tryInitVsm133(launcherCtx, context)
                if (sVcontrol == null) tryInitVehicleControlManager(launcherCtx, context)
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

    /**
     * VehicleControlManager — porte l'ESC sur SWI68/165 (get/setEspSwitch).
     *
     * Même schéma que [tryInitGeneralManager] : singleton statique, sinon init(Context, listener).
     * On DOIT appeler init() nous-mêmes : la classe vient du classloader du launcher, mais les
     * statiques vivent par processus, donc le singleton déjà construit côté launcher ne nous est
     * pas visible.
     */
    private fun tryInitVehicleControlManager(launcherCtx: Context, appCtx: Context) {
        if (sVcontrol != null) return
        try {
            val cls = launcherCtx.classLoader.loadClass(VCONTROL_CLASS)
            val f = cls.getDeclaredField("sVehicleControlManager")
            f.isAccessible = true
            f.get(null)?.let {
                sVcontrol = it
                AppLogger.i(TAG, "  VehicleControlManager singleton ✓")
                return
            }
            val initMethod = cls.methods.firstOrNull { m ->
                m.name == "init" && m.parameterCount == 2 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AppLogger.w(TAG, "  VehicleControlManager init() non trouvé")
                return
            }
            val listenerType = initMethod.parameterTypes[1]
            val listener = if (listenerType.isInterface) {
                java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        try {
                            f.get(null)?.let { sVcontrol = it }
                            AppLogger.i(TAG, "  VehicleControlManager onServiceConnected — " +
                                "sVcontrol=${if (sVcontrol != null) "OK ✓" else "null"}")
                        } catch (_: Exception) {}
                    }
                    null
                }
            } else null
            initMethod.invoke(null, appCtx, listener)
            f.get(null)?.let { sVcontrol = it }
            AppLogger.i(TAG, "  VehicleControlManager.init() appelé — " +
                "sVcontrol=${if (sVcontrol != null) "OK ✓" else "null"}")
        } catch (e: Exception) {
            AppLogger.d(TAG, "  tryInitVehicleControlManager exc: ${e.message}")
        }
    }

    /** Lecture sur VehicleControlManager. null si indisponible. */
    private fun callVcontrol(methodName: String, vararg args: Any?): Any? {
        val m = sVcontrol ?: return null
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            m.javaClass.getMethod(methodName, *types).invoke(m, *args)
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VCTRL: $methodName() exc: ${e.message}")
            null
        }
    }

    /** Écriture sur VehicleControlManager — soumise au verrou de vitesse comme toute écriture. */
    private fun callVcontrolVoid(methodName: String, vararg args: Any?): Boolean {
        if (!VehicleWriteGate.allow("VCTRL $methodName")) return false
        val m = sVcontrol ?: run {
            AppLogger.w(TAG, "  VCTRL: $methodName() — manager non lié")
            return false
        }
        return try {
            val types = args.map { if (it is Int) Int::class.javaPrimitiveType!! else it!!.javaClass }.toTypedArray()
            m.javaClass.getMethod(methodName, *types).invoke(m, *args)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VCTRL: $methodName() exc: ${e.message}")
            false
        }
    }

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
        tryInitVehicleControlManager(launcherCtx, context)   // ESC sur SWI68/165
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
                if (sVcontrol == null) tryInitVehicleControlManager(launcherCtx, context)
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

    // ── Mode de conduite Personnalisé : puissance, direction, pédale ─────────
    //
    // Ces trois réglages n'existent que dans le mode Personnalisé du véhicule. Ils sont présents
    // sur les six firmwares, mais par deux voies différentes.

    /**
     * Signaux du SDK vehiclesettings (SWI133/68/165).
     *
     * ⚠️ Ce ne sont PAS des identifiants de propriété VHAL : l'app d'origine passe par
     * `VehiclePropertyManager` avec sa propre numérotation. Les équivalents VHAL existent
     * (0x2140a18c / 0x2140a18d / 0x2140a18e) si cette voie devenait un jour nécessaire.
     */
    private const val SIG_CUSTOM_POWER    = 0x2040002
    private const val SIG_CUSTOM_STEERING = 0x2040004
    private const val SIG_CUSTOM_PEDAL    = 0x2040005

    /** Index manipulés par l'application — jamais les valeurs véhicule, voir [valeurPuissance]. */
    object CustomDrive {
        const val ECO_COMFORT = 0
        const val NORMAL      = 1
        const val SPORT       = 2
    }

    /**
     * A9 : SWI69, SWI131, SWI132. Même ensemble que la clim, mais fonction distincte à dessein —
     * changer la règle d'un domaine ne doit pas déplacer l'autre en silence.
     */
    private fun isDriveCustomA9(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI69 || gen == FirmwareInfo.Gen.SWI131 ||
               gen == FirmwareInfo.Gen.SWI132
    }

    // ⚠️ TROIS ÉCHELLES DIFFÉRENTES, relevées dans le code d'origine des deux familles :
    //  • direction : 1/2/3 partout ;
    //  • pédale : 1/0/2 partout — Normal vaut ZÉRO, et non la valeur du milieu. Le smali SWI133
    //    réassigne le registre entre deux branches, ce qui se lit 1/2/3 si on va trop vite ;
    //    le dispatch A9 sur les libellés comfort/normal/sport donne le même résultat ;
    //  • puissance : 1/2/3 en old-SDK mais 2/3/4 sur A9, où la voiture réutilise l'échelle des
    //    modes de conduite (ÉCO=2, NORMAL=3, SPORT=4).
    //
    // D'où l'index 0/1/2 exposé au reste de l'application : personne d'autre ne manipule ces
    // nombres, et une échelle qui changerait ne se corrige qu'ici.
    private fun valeurPuissance(index: Int): Int = index + (if (isDriveCustomA9()) 2 else 1)
    private fun valeurDirection(index: Int): Int = index + 1
    private fun valeurPedale(index: Int): Int = when (index) {
        CustomDrive.ECO_COMFORT -> 1
        CustomDrive.SPORT       -> 2
        else                    -> 0
    }

    private fun indexPuissance(v: Int): Int? =
        (v - (if (isDriveCustomA9()) 2 else 1)).takeIf { it in 0..2 }
    private fun indexDirection(v: Int): Int? = (v - 1).takeIf { it in 0..2 }
    private fun indexPedale(v: Int): Int? = when (v) {
        1    -> CustomDrive.ECO_COMFORT
        0    -> CustomDrive.NORMAL
        2    -> CustomDrive.SPORT
        else -> null
    }

    /** Puissance en chevaux du mode Personnalisé. [index] : 0=Éco, 1=Normal, 2=Sport. */
    fun setCustomPower(index: Int): Boolean {
        if (index !in 0..2) return false
        val v = valeurPuissance(index)
        if (logEnabled) AppLogger.i(TAG, "setCustomPower → index=$index valeur=$v")
        return if (isDriveCustomA9()) callVsmVoid("setDrivingPowerTrainMode", v)
               else setIntPropertyVpmRecovery(SIG_CUSTOM_POWER, v)
    }

    /** Direction du mode Personnalisé. [index] : 0=Confort, 1=Normal, 2=Sport. */
    fun setCustomSteering(index: Int): Boolean {
        if (index !in 0..2) return false
        val v = valeurDirection(index)
        if (logEnabled) AppLogger.i(TAG, "setCustomSteering → index=$index valeur=$v")
        // A9 : setSteeringMode, et NON setDrivingEpsMode — les deux existent, seule la première
        // est appelée par l'app d'origine. Vérifié en traçant fragment → presenter → model.
        return if (isDriveCustomA9()) callVsmVoid("setSteeringMode", v)
               else setIntPropertyVpmRecovery(SIG_CUSTOM_STEERING, v)
    }

    /** Force exercée sur la pédale. [index] : 0=Confort, 1=Normal, 2=Sport. */
    fun setCustomPedal(index: Int): Boolean {
        if (index !in 0..2) return false
        val v = valeurPedale(index)
        if (logEnabled) AppLogger.i(TAG, "setCustomPedal → index=$index valeur=$v")
        return if (isDriveCustomA9()) callVsmVoid("setBrakePedalMode", v)
               else setIntPropertyVpmRecovery(SIG_CUSTOM_PEDAL, v)
    }

    /**
     * Lectures — `null` si le véhicule ne répond pas OU rend une valeur hors échelle.
     *
     * C'est le seul garde-fou honnête pour savoir si la voiture porte réellement ces réglages :
     * ils existent sur les six firmwares, mais rien ne dit qu'ils sont montés sur toutes les
     * finitions. Un `null` fait masquer la ligne plutôt que d'offrir un bouton sans effet.
     */
    fun getCustomPower(): Int? = indexPuissance(
        if (isDriveCustomA9()) (callVsm("getDrivingPowerTrainMode") as? Int) ?: -1
        else getIntPropertyVpm(SIG_CUSTOM_POWER))

    fun getCustomSteering(): Int? = indexDirection(
        if (isDriveCustomA9()) (callVsm("getSteeringMode") as? Int) ?: -1
        else getIntPropertyVpm(SIG_CUSTOM_STEERING))

    fun getCustomPedal(): Int? = indexPedale(
        if (isDriveCustomA9()) (callVsm("getBrakePedalMode") as? Int) ?: -1
        else getIntPropertyVpm(SIG_CUSTOM_PEDAL))

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

    /**
     * Variantes nullables des lectures de chauffage : `null` = propriété ILLISIBLE, ce qui n'est
     * PAS « éteint ».
     *
     * Les trois lectures ci-dessus écrasent cette distinction — `coerceAtLeast(0)` et `> 0`
     * transforment le -1 d'échec de [getIntPropertyHvac] en 0/false. C'est sans conséquence pour
     * l'affichage, mais ça casse le cycle de l'API externe : partir d'un 0 supposé alors que la
     * propriété est muette produirait un cycle bloqué à 1, et si le siège est réellement à 3
     * l'utilisateur appuie pour monter et voit la valeur descendre.
     */
    fun getSeatHeatLeftOrNull(): Int? =
        getIntPropertyHvac(PROP_SEAT_HEAT_L, AREA_HVAC).takeIf { it >= 0 }

    fun getSeatHeatRightOrNull(): Int? =
        getIntPropertyHvac(PROP_SEAT_HEAT_R, AREA_HVAC).takeIf { it >= 0 }

    fun getSteeringHeatOrNull(): Boolean? =
        getIntPropertyHvac(PROP_STEERING_HEAT, AREA_HVAC).takeIf { it >= 0 }?.let { it > 0 }

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
    // Somnolence (DMS), sensibilité de son alerte, et ESC — SWI133 uniquement
    //
    // Décodé du smali de com.saicmotor.hmi.vehiclesettings, classe VehiclePropertyID :
    //   ID_AAD_UDW_MAIN_SWITCH             0x3010005   1=OFF, 2=ON, 0=OFF
    //   ID_AAD_UDW_ALARM_TONE_SENSITIVITY  0x3010007   1=Faible, 2=Moyen, 3=Élevé
    //   ID_ZONED_VEHICLE_ESP               0x4020003   lecture 0=OFF, 1=ON, 2=OFF
    //
    // UDW = « Unfit Driver Warning » : la sensibilité appartient bien à la somnolence et non au
    // FCW — le handler d'origine s'appelle unsteadyDrivingWarningSenOnClick.
    //
    // Les autres firmwares exposent les mêmes réglages par d'autres voies, non câblées tant que
    // SWI133 n'est pas validé sur véhicule :
    //   SWI68/165     : setEspSwitch / setUnsteadyDrivingWarning / setUnsteadyDrivingWarningSen
    //   A9 69/131/132 : transactions 0x54 (ESC, nommé « Eps »), 0x90 (DMS), 0x96 (sensibilité)
    // -------------------------------------------------------------------------

    // ⚠️ 0x3010005 (UDW_MAIN_SWITCH) et NON 0x3010001 (DMS_SWITCH). Les deux existent et
    // portent des libelles voisins : DMS_SWITCH pilote la surveillance par CAMERA
    // ("Avertisseur de somnolence du conducteur"), UDW_MAIN_SWITCH l'avertissement de
    // somnolence de la capture ecran. Ecrire sur DMS_SWITCH n'avait aucun effet visible.
    // Coherence a verifier a l'avenir : le commutateur et sa sensibilite doivent appartenir
    // a la MEME famille (ici UDW_*), sinon c'est qu'on a melange deux reglages.
    private const val PROP_UDW_MAIN_SWITCH = 0x3010005
    private const val PROP_DMS_SENSITIVITY = 0x3010007
    private const val PROP_ESC             = 0x4020003

    private const val DMS_OFF = 1
    private const val DMS_ON  = 2

    /** Sensibilité de l'alerte de somnolence — mêmes paliers que la sensibilité AEB. */
    object DrowsinessSensitivity {
        const val LOW    = 1
        const val MEDIUM = 2
        const val HIGH   = 3
    }

    /**
     * Vrai si ce firmware expose somnolence et ESC par la voie câblée ici (propriétés VPM).
     *
     * Volontairement restreint à SWI133 : ces IDs n'existent QUE sur cette génération — vérifié,
     * ils sont absents du smali SWI68/165 (méthodes nommées) et d'A9 (transactions binder).
     * Élargir la condition sans câbler ces voies ferait échouer les écritures en silence.
     */
    fun hasDrowsinessAndEsc(): Boolean =
        FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN

    /**
     * Vrai si ce firmware passe par carapi (A9). Les noms de méthodes y diffèrent de l'old-SDK.
     *
     * ⚠️ Ne PAS utiliser `FirmwareInfo.isNewGenVsm()` ici : il ne couvre que SWI69 et SWI131,
     * il laisse SWI132 de côté alors que c'est bien un A9. Même triplet que isClimateA9().
     */
    private fun isA9Vsm(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI69 || gen == FirmwareInfo.Gen.SWI131 ||
               gen == FirmwareInfo.Gen.SWI132
    }

    // Noms de méthodes sur sVsm. Le commutateur et sa sensibilité viennent TOUJOURS de la même
    // famille (UDW) : c'est la règle que la panne SWI133 a mise en évidence — un setDmsStatus
    // (surveillance caméra) à côté d'un setUdwSensitivity ne pilote pas le même réglage.
    // Codes de transaction A9 correspondants, consécutifs, ce qui confirme le regroupement :
    //   setUdwStatus 0x94 / get 0x95   setUdwSensitivityState 0x96 / get 0x97
    //   setDrivingEpsMode 0x54 / get 0x55   (ESC, nommé « Eps » et non « Esc »)
    private fun nUdwSet() = if (isA9Vsm()) "setUdwStatus"           else "setUnsteadyDrivingWarning"
    private fun nUdwGet() = if (isA9Vsm()) "getUdwStatus"           else "getUnsteadyDrivingWarning"
    private fun nSenSet() = if (isA9Vsm()) "setUdwSensitivityState" else "setUnsteadyDrivingWarningSen"
    private fun nSenGet() = if (isA9Vsm()) "getUdwSensitivityState" else "getUnsteadyDrivingWarningSen"
    private fun nEscSet() = if (isA9Vsm()) "setDrivingEpsMode"      else "setEspSwitch"
    private fun nEscGet() = if (isA9Vsm()) "getDrivingEpsMode"      else "getEspSwitch"

    /** Lecture brute du commutateur/sensibilité, quelle que soit la voie. -1 si illisible. */
    private fun readSafety(prop: Int, method: String): Int =
        if (FirmwareInfo.isVsmBased()) (callVsm(method) as? Int) ?: -1
        else getIntPropertyVpm(prop)

    private fun writeSafety(prop: Int, method: String, value: Int): Boolean =
        if (FirmwareInfo.isVsmBased()) callVsmVoid(method, value)
        else setIntPropertyVpmRecovery(prop, value)

    /**
     * Avertissement de somnolence : true=ON, false=OFF, null=illisible.
     *
     * ⚠️ La valeur **0** compte pour OFF, pas pour « illisible ». C'est ce que fait l'UI d'origine
     * (onDriverMonitorSysStatusChanged : 1→OFF, 2→ON, 0→OFF, autre→« value error »), et l'ignorer
     * renvoyait null sur un état parfaitement valide — donc aucun bouton allumé à l'écran.
     * Seul -1, la sentinelle d'échec de [getIntPropertyVpm], vaut réellement « illisible ».
     */
    fun isDrowsinessOn(): Boolean? {
        if (!hasDrowsinessAndEsc()) return null
        return when (readSafety(PROP_UDW_MAIN_SWITCH, nUdwGet())) {
            DMS_ON     -> true
            DMS_OFF, 0 -> false
            else       -> null
        }
    }

    fun setDrowsiness(on: Boolean): Boolean {
        if (!hasDrowsinessAndEsc()) return false
        val v = if (on) DMS_ON else DMS_OFF
        AppLogger.i(TAG, "  UDW SET switch=$v (${if (on) "ON" else "OFF"}) via ${nUdwSet()}")
        return writeSafety(PROP_UDW_MAIN_SWITCH, nUdwSet(), v)
    }

    /** Sensibilité de l'alerte somnolence (1=Faible, 2=Moyen, 3=Élevé), -1 si illisible. */
    fun getDrowsinessSensitivity(): Int {
        if (!hasDrowsinessAndEsc()) return -1
        val raw = readSafety(PROP_DMS_SENSITIVITY, nSenGet())
        return if (raw in 1..3) raw else -1
    }

    fun setDrowsinessSensitivity(level: Int): Boolean {
        if (!hasDrowsinessAndEsc() || level !in 1..3) return false
        AppLogger.i(TAG, "  UDW SET sensitivity=$level via ${nSenSet()}")
        return writeSafety(PROP_DMS_SENSITIVITY, nSenSet(), level)
    }

    /**
     * Lecture brute de l'ESC. Trois voies DIFFÉRENTES, et c'est le piège :
     *   • SWI133      : propriété VPM 0x4020003 ;
     *   • SWI68/165   : **VehicleControlManager**, pas le VehicleSettingManager — get/setEspSwitch
     *     n'existent que là. Les chercher sur sVsm échouait sans bruit, d'où un ESC inerte ;
     *   • A9          : CarVehicleSettingClient (setDrivingEpsMode), donc bien sVsm.
     */
    private fun readEscRaw(): Int = when {
        isA9Vsm()                 -> (callVsm(nEscGet()) as? Int) ?: -1
        FirmwareInfo.isVsmBased() -> (callVcontrol(nEscGet()) as? Int) ?: -1
        else                      -> getIntPropertyVpm(PROP_ESC)
    }

    private fun writeEscRaw(value: Int): Boolean = when {
        isA9Vsm()                 -> callVsmVoid(nEscSet(), value)
        FirmwareInfo.isVsmBased() -> callVcontrolVoid(nEscSet(), value)
        else                      -> setIntPropertyVpmRecovery(PROP_ESC, value)
    }

    /** ESC : true=ON, false=OFF, null=illisible. Lecture 0=OFF, 1=ON, 2=OFF. */
    fun isEscOn(): Boolean? {
        if (!hasDrowsinessAndEsc()) return null
        return when (readEscRaw()) {
            1    -> true
            0, 2 -> false
            else -> null
        }
    }

    /**
     * Active/désactive l'ESC.
     *
     * ⚠️ L'écriture n'est PAS un « set » ordinaire : dans l'UI d'origine, l'interrupteur ET son
     * dialogue de confirmation écrivent tous deux la valeur **1**. C'est la signature d'une
     * bascule qui ignore son argument, comme les commandes clim SAIC (voir hvacCycleTo).
     *
     * On lit donc l'état courant et on n'écrit QUE s'il diffère de la cible. Le helper reste juste
     * dans les deux hypothèses : si c'était en réalité un « set » où 1=ON, écrire 1 pour passer de
     * OFF à ON donne le même résultat. [runSafetyDiag] tranche la question.
     *
     * Refuse plutôt que de deviner quand l'état courant est illisible : sur une bascule, partir
     * d'un état supposé fait l'inverse de ce qui est demandé une fois sur deux.
     */
    /** Nombre de lectures concordantes exigées avant d'agir sur l'ESC. */
    private const val ESC_LECTURES = 3
    /** Intervalle entre deux lectures, en ms. */
    private const val ESC_INTERVALLE_MS = 300L
    /** Délai laissé au calculateur pour appliquer la bascule avant de constater l'effet. */
    private const val ESC_STABILISATION_MS = 600L

    /**
     * État de l'ESC confirmé par [ESC_LECTURES] lectures concordantes, ou `null` si on ne peut
     * pas conclure.
     *
     * Deux raisons de renoncer, et la seconde est la plus importante :
     *  • une lecture illisible — on ne devine pas ;
     *  • des lectures **divergentes**, qui signifient que la voiture est en train d'agir sur
     *    l'ESC à cet instant précis. C'est exactement le moment où il ne faut surtout pas
     *    écrire : l'écriture est une bascule, elle s'ajouterait à celle du véhicule et
     *    produirait l'inverse du résultat voulu.
     */
    private fun lireEscStable(): Boolean? {
        val lectures = ArrayList<Boolean?>(ESC_LECTURES)
        repeat(ESC_LECTURES) { i ->
            if (i > 0) try { Thread.sleep(ESC_INTERVALLE_MS) } catch (_: InterruptedException) {}
            lectures.add(isEscOn())
        }
        if (lectures.any { it == null }) {
            AppLogger.w(SAFE_TAG, "ESC : lecture illisible ($lectures) — aucune action")
            return null
        }
        val distinctes = lectures.distinct()
        if (distinctes.size > 1) {
            AppLogger.w(SAFE_TAG, "ESC : lectures DIVERGENTES ($lectures) — la voiture agit " +
                "en ce moment, on renonce plutôt que de s'ajouter à sa bascule")
            return null
        }
        return distinctes.first()
    }

    /**
     * Active ou désactive l'ESC.
     *
     * ⚠️ L'écriture est un CYCLE, pas une consigne : vérifié sur cinq chemins de l'UI d'origine,
     * deux firmwares et deux API différentes — la valeur écrite est **toujours 1**, pour allumer
     * comme pour éteindre. Il n'existe aucune voie à valeur absolue. C'est donc la lecture
     * préalable, et elle seule, qui détermine le sens de l'action.
     *
     * D'où le protocole : on n'agit QUE si [ESC_LECTURES] lectures concordantes diffèrent de la
     * cible. Toute incertitude — lecture illisible ou instable — vaut abstention. Le véhicule
     * remet l'ESC sur ON à chaque démarrage ; ne rien faire est donc toujours sans danger,
     * contrairement à une écriture au mauvais moment.
     *
     * ⚠️ BLOQUE environ une seconde : à n'appeler QUE hors du thread principal.
     */
    fun setEsc(on: Boolean): Boolean {
        if (!hasDrowsinessAndEsc()) return false
        // Garde explicite : whenKatman4Ready republie ses écouteurs sur le Looper principal,
        // donc un appelant distrait arriverait ici sur le thread UI et gèlerait l'écran.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLogger.w(SAFE_TAG, "setEsc refusé — appelé sur le thread UI alors qu'il attend " +
                "~1 s. L'appelant doit basculer sur un contexte IO.")
            return false
        }

        // ⚠️ GARDE D'ALLUMAGE — cause d'un ESC désactivé au démarrage, reproduite le 2026-08-26.
        //
        // Scénario : on monte dans la voiture alors que l'infodivertissement tourne déjà mais que
        // le tableau de bord est encore éteint. Le service démarre et applique le profil, donc
        // AVANT que le véhicule ne soit réveillé. À cet instant la propriété ESC ne reflète pas
        // l'état réel — et comme l'écriture est une BASCULE pilotée par cette lecture, viser
        // « ON » à partir d'un « OFF » erroné INVERSE un ESC qui était actif. Il est alors
        // réellement désactivé, en silence.
        //
        // Le second passage (IGNITION_RUN, puis passage en D) relisait correctement et le
        // remettait sur ON : d'où l'impression d'un réglage qui « revient tout seul ».
        //
        // On refuse donc d'agir sur un état d'allumage CONNU comme non-RUN. Un état illisible
        // n'est pas un refus : sur un firmware où la propriété ne répond pas, bloquer priverait
        // l'utilisateur du réglage sans rien protéger.
        //
        // Aucune fonction perdue : le véhicule remet l'ESC sur ON à chaque démarrage, et le
        // profil est ré-appliqué 500 ms après IGNITION_RUN — un profil qui veut l'ESC sur OFF
        // sera donc bien honoré, simplement un peu plus tard.
        val allumage = getCurrentIgnitionState()
        if (allumage == CarIgnitionItem.OFF || allumage == CarIgnitionItem.ACCESSORY ||
            allumage == CarIgnitionItem.CRANK) {
            AppLogger.w(SAFE_TAG, "ESC : véhicule pas en RUN (allumage=$allumage) — aucune " +
                "action. Basculer maintenant partirait d'une lecture non fiable.")
            return false
        }

        val stable = lireEscStable() ?: return false
        if (stable == on) {
            AppLogger.i(SAFE_TAG, "ESC déjà ${if (on) "ON" else "OFF"} (confirmé " +
                "$ESC_LECTURES fois) — aucune écriture")
            return true
        }

        AppLogger.i(SAFE_TAG, "ESC → ${if (on) "ON" else "OFF"} : état confirmé=$stable, " +
            "bascule via ${nEscSet()} (écriture de 1)")
        val ok = writeEscRaw(1)

        try { Thread.sleep(ESC_STABILISATION_MS) } catch (_: InterruptedException) {}
        val obtenu = isEscOn()
        if (obtenu != on) {
            AppLogger.w(SAFE_TAG, "⚠️ ESC : cible=${if (on) "ON" else "OFF"} mais état relu=" +
                "${obtenu ?: "illisible"} — AUCUNE réécriture (elle ferait osciller)")
        } else {
            AppLogger.i(SAFE_TAG, "ESC conforme après écriture : ${if (on) "ON" else "OFF"}")
        }
        return ok && obtenu == on
    }

    // ── Sonde somnolence / sensibilité / ESC (bouton Diagnostic) ──────────────
    private const val SAFE_TAG = "MG4_SAFE"

    /**
     * Sonde des trois réglages — **lecture seule**.
     *
     * Elle est appelée par le bouton Diagnostic, qui enchaîne les sondes pour produire un
     * rapport : rien de ce qui est déclenché là ne doit modifier l'état du véhicule, et surtout
     * pas un organe de sécurité active. La question ouverte sur l'encodage d'écriture de l'ESC
     * est donc tranchée ailleurs — [setEsc] relit et journalise après chaque écriture, donc un
     * simple appui sur le bouton ESC de l'écran suffit à conclure.
     */
    fun runSafetyDiag() {
        AppLogger.i(SAFE_TAG, "── DIAG somnolence / sensibilité / ESC ──")
        AppLogger.i(SAFE_TAG, "firmware=${FirmwareInfo.getGeneration()} géré=${hasDrowsinessAndEsc()}")
        // L'allumage conditionne toute écriture ESC : hors RUN, la bascule est refusée. Sans
        // cette ligne, un rapport où l ESC « ne répond pas » resterait inexplicable.
        AppLogger.i(SAFE_TAG, "allumage=${getCurrentIgnitionState()} (RUN=${CarIgnitionItem.RUN} " +
            "→ seule valeur qui autorise une bascule ESC)")
        if (!hasDrowsinessAndEsc()) {
            AppLogger.i(SAFE_TAG, "→ firmware inconnu, aucune voie applicable")
            return
        }
        AppLogger.i(SAFE_TAG, "voie UDW=" +
            (if (FirmwareInfo.isVsmBased()) "sVsm (" + nUdwGet() + ")" else "VPM") +
            " | voie ESC=" + when {
                isA9Vsm()                 -> "sVsm (" + nEscGet() + ")"
                FirmwareInfo.isVsmBased() -> "VehicleControlManager lié=" + (sVcontrol != null)
                else                      -> "VPM 0x4020003"
            })

        val dms = readSafety(PROP_UDW_MAIN_SWITCH, nUdwGet())
        val sen = readSafety(PROP_DMS_SENSITIVITY, nSenGet())
        val esc = readEscRaw()
        AppLogger.i(SAFE_TAG, "UDW_MAIN_SWITCH(0x3010005) = $dms (2=ON, 1 et 0=OFF) → ${isDrowsinessOn()}")
        AppLogger.i(SAFE_TAG, "UDW_SENSITIVITY(0x3010007) = $sen (1=Faible, 2=Moyen, 3=Élevé)")
        AppLogger.i(SAFE_TAG, "ESP(0x4020003)             = $esc (0=OFF, 1=ON, 2=OFF) → ${isEscOn()}")

        AppLogger.i(SAFE_TAG, "── fin DIAG ──")
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

    // ────────────────────────────────────────────────────────────────────────
    // Pistes audio : suivant / précédent / lecture-pause
    // ────────────────────────────────────────────────────────────────────────

    private const val MEDIA_TAG = "MG4_MEDIA"

    /**
     * ⚠️ LES TOUCHES MÉDIA D'ANDROID NE SUFFISENT PAS ICI. Mesuré sur véhicule, pas supposé.
     *
     * `dispatchMediaKeyEvent` ne peut atteindre qu'une **MediaSession**. Or la sonde
     * [runMediaDiag] n'en a trouvé qu'UNE sur la voiture : `com.android.bluetooth`. Ni les
     * sources d'origine (radio, USB) ni Android Auto n'en publient. La touche partait donc dans
     * le vide — sans la moindre erreur, ce qui la rendait indiscernable d'un envoi réussi.
     *
     * Le launcher ne passe pas par là. `MediaPlayControlManager.next()`, décompilé, appelle un
     * binder DIFFÉRENT selon la source active. Tout transite par un service unique,
     * [MEDIA_PKG] / [MEDIA_CLS], dont **l'action de l'intent choisit l'interface rendue**.
     */
    private const val MEDIA_PKG = "com.saicmotor.service.media"
    private const val MEDIA_CLS = "com.saicmotor.service.media.MediaService"

    // Actions de liaison et descripteurs AIDL — relevés dans le smali du launcher SWI133.
    private const val ACT_MEDIA   = "com.saicmotor.service.media.MEDIA_PLAYER_ACTION"
    private const val ACT_CPAA    = "com.saicmotor.service.media.CPAA_PLAYER_ACTION"
    private const val ACT_BT      = "com.saicmotor.service.media.BT_MUSIC_ACTION"
    private const val ACT_USB     = "com.saicmotor.service.media.MUSIC_PLAYER_ACTION"
    private const val ACT_ONLINE  = "com.saicmotor.service.media.ONLINE_MUSIC_ACTION"
    private const val ACT_STATUS  = "com.saicmotor.service.media.PLAY_STATUS_ACTION"

    private const val DESC_MEDIA  = "com.saicmotor.sdk.media.IMediaPlayerBinderInterface"
    private const val DESC_CPAA   = "com.saicmotor.sdk.media.ICpAaBinderInterface"
    private const val DESC_BT     = "com.saicmotor.sdk.media.IBtMusicBinderInterface"
    private const val DESC_USB    = "com.saicmotor.sdk.media.IMusicPlayerBinderInterface"
    private const val DESC_ONLINE = "com.saicmotor.sdk.media.IOnlineMusicBinderInterface"
    private const val DESC_STATUS = "com.saicmotor.sdk.media.IPlayStatusBinderInterface"

    /**
     * Valeurs rendues par `getCurrentMediaSource`.
     *
     * ⚠️ L'espace de valeurs est HYBRIDE, et ça ne se devine pas : le SDK y mélange ses
     * `MEDIA_TYPE_*` (petits entiers) et ses `*_SOURCE_CODE` (0x12, 0x32, 0x46). Seules quatre
     * valeurs sont CERTAINES — les trois constantes du SDK, et le 3 relevé sur véhicule en
     * radio. Les autres restent des candidats, d'où l'identification de secours plus bas :
     * quand le code est inconnu, on demande aux lecteurs lequel joue au lieu de parier.
     */
    private const val SRC_RADIO_MIN = 1       // 1 radio, 2 FM, 3 AM, 4 DAB
    private const val SRC_RADIO_MAX = 4
    private const val SRC_BT        = 5       // certain (relevé véhicule 2026-08-26)
    private const val SRC_ONLINE    = 6       // candidat
    private const val SRC_USB       = 7       // candidat
    private const val SRC_USB_VIDEO = 0x12    // certain (USB_VIDEO_SOURCE_CODE)
    private const val SRC_CARPLAY   = 0x32    // certain (CP_MEDIA_SOURCE_CODE)
    private const val SRC_AA        = 0x46    // certain (AA_MEDIA_SOURCE_CODE)

    /** États de lecture (`MediaConstants`) : seul START vaut « en train de jouer ». */
    private const val PLAYER_STATUS_START = 3

    // ── Service radio : un service à part, avec sa propre interface ──
    private const val RADIO_PKG  = "com.saicmotor.service.radio"
    private const val RADIO_ACT  = "com.saicmotor.service.radio.radioservice"
    private const val DESC_RADIO = "com.saicmotor.sdk.radio.IRadioAppService"
    private const val RADIO_NEXT       = 0xd
    private const val RADIO_PREV       = 0xe
    private const val RADIO_INFO       = 0x13   // getCurrentRadioInfo → RadioBean
    private const val RADIO_PLAY       = 0x1b   // srcPlayRadio
    private const val RADIO_PAUSE      = 0x1c   // srcPauseRadio

    private fun nomSource(v: Int): String = when {
        v == 0 -> "aucune"
        v in SRC_RADIO_MIN..SRC_RADIO_MAX -> "radio"
        v == SRC_BT -> "Bluetooth"
        v == SRC_ONLINE -> "en ligne"
        v == SRC_USB -> "USB"
        v == SRC_USB_VIDEO -> "vidéo USB"
        v == SRC_CARPLAY -> "CarPlay"
        v == SRC_AA -> "Android Auto"
        else -> "inconnue ($v)"
    }

    // ── Projection : le service allgo, hors SDK SAIC ────────────────────────
    //
    // CarPlay et Android Auto ne se pilotent PAS par le même chemin selon le firmware, et les
    // deux voies sont exactement complémentaires — vérifié dans les 6 launchers :
    //   • SWI133 : `ICpAaBinderInterface` du SDK média SAIC ;
    //   • SWI68 / 165 / 69 / 131 / 132 : ce service-ci, celui de la pile de projection allgo,
    //     que le launcher appelle lui-même.
    // Aucun firmware n'a les deux, aucun n'en est dépourvu.
    /**
     * Préfixe des paquets de la pile de projection allgo.
     *
     * Sert à RECONNAÎTRE sa session média pour ne surtout pas s'en servir — voir [sessionMedia].
     */
    private const val PREFIXE_PROJECTION = "com.allgo."

    private const val RUI_PKG = "com.allgo.rui"
    private const val RUI_CLS = "com.allgo.rui.RemoteUIService"
    private const val DESC_RUI = "com.allgo.rui.IRemoteUIService"
    private const val TX_RUI_MEDIA_KEY = 0x12   // sendMediaPlayControlKey(int) : int

    /**
     * Valeurs de `sendMediaPlayControlKey`, relevées dans les méthodes du launcher SWI68 :
     * `playRemoteUIyMusicResource`, `pauseRemoteUIyMusicResource`, `touchNextRemoteUiMusic`,
     * `touchPreviousRemoteUiMusic`. Ce ne sont PAS des keycodes Android — c'est une énumération
     * propre à allgo, qu'il aurait été impossible de deviner.
     *
     * Piste suivante et précédente se donnent en DEUX temps, appui puis relâchement, comme le
     * fait le launcher.
     */
    private const val RUI_PLAY      = 1
    private const val RUI_PAUSE     = 2
    private const val RUI_NEXT_DOWN = 7
    private const val RUI_PREV_DOWN = 8
    private const val RUI_NEXT_UP   = 11
    private const val RUI_PREV_UP   = 12

    /**
     * S'assure que le helper audio A9 est joignable, et le récupère sinon.
     *
     * ⚠️ Sans ça, sa perte était DÉFINITIVE pour la durée du processus, et personne ne s'en
     * apercevait :
     *  • [audioGetArg] et [audioSet3] mettent `sAudioHelper` à null dès que son binder meurt ;
     *  • [initAudio] refuse de refaire la liaison tant que `sAudioServiceConn` existe — or
     *    `onServiceDisconnected` ne l'efface pas ;
     *  • le helper est un binder de SECOND niveau, obtenu par une requête au CarAdapter : même
     *    quand ce dernier va bien, plus rien ne le redemande.
     *
     * Le volume basculait alors en silence sur `AudioManager`, jusqu'à la prochaine ouverture de
     * l'application — la seule à appeler [initAudio]. Vu de l'utilisateur : un volume qui
     * « marchait avant » et ne marche plus, sans rien avoir changé.
     */
    private fun assurerHelperAudio() {
        if (!isA9Sound()) return
        if (sAudioHelper?.isBinderAlive == true) return
        val ctx = sAppContext ?: return

        // Le CarAdapter répond encore : il suffit de lui redemander le helper.
        if (sCarAdapterBinder?.isBinderAlive == true) {
            AppLogger.i(VOL_TAG, "helper audio perdu — nouvelle requête au CarAdapter")
            tryGetAudioHelper()
            if (sAudioHelper?.isBinderAlive == true) return
        }

        // Le service lui-même est parti : on repart d'une liaison neuve. L'ancienne connexion
        // est détachée d'abord, sinon on en accumulerait une par tentative.
        AppLogger.i(VOL_TAG, "CarAdapter injoignable — nouvelle liaison audio")
        sAudioServiceConn?.let { conn ->
            runCatching { ctx.applicationContext.unbindService(conn) }
        }
        sAudioServiceConn = null
        sCarAdapterBinder = null
        initAudio(ctx)

        // La liaison est asynchrone. Sans cette attente, le premier appui échouerait et seul le
        // suivant agirait — un raccourci qui « marche une fois sur deux » est pire qu'un
        // raccourci qui ne marche pas, on ne sait pas quoi en conclure. L'appelant est déjà sur
        // un contexte IO, l'attente ne coûte rien à l'écran.
        val limite = SystemClock.uptimeMillis() + MEDIA_BIND_MS
        while (SystemClock.uptimeMillis() < limite) {
            if (sAudioHelper?.isBinderAlive == true) {
                AppLogger.i(VOL_TAG, "helper audio récupéré")
                return
            }
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
        }
        AppLogger.w(VOL_TAG, "helper audio toujours absent après ${MEDIA_BIND_MS} ms — " +
            "le volume passera par AudioManager")
    }

    /** Envoie une commande à la pile de projection. Rend faux si le service ne répond pas. */
    private fun ruiEnvoyer(valeur: Int): Boolean {
        val binder = serviceBinder("", RUI_PKG, RUI_CLS) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_RUI)
            data.writeInt(valeur)
            binder.transact(TX_RUI_MEDIA_KEY, data, reply, 0)
            reply.readException()
            // La méthode rend un entier : on le journalise sans l'interpréter. Sa signification
            // est inconnue, mais il distinguera un refus d'un acquittement le jour où il faudra.
            val retour = if (reply.dataAvail() > 0) reply.readInt() else 0
            AppLogger.i(MEDIA_TAG, "projection : sendMediaPlayControlKey($valeur) → $retour")
            true
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "projection : commande $valeur refusée — ${(e.cause ?: e).message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Commande média de la projection par la voie allgo.
     *
     * [joue] décide du sens de la bascule lecture/pause : ce service n'expose pas d'état, c'est
     * l'appelant qui l'a déjà déterminé (session, ou `isMusicActive` à défaut).
     */
    private fun ruiMedia(cmd: CmdMedia, joue: Boolean): Boolean = when (cmd) {
        // Appui PUIS relâchement : un appui laissé « enfoncé » serait interprété comme une
        // avance rapide, c'est le rôle des deux codes distincts.
        CmdMedia.SUIVANT ->
            ruiEnvoyer(RUI_NEXT_DOWN) && ruiEnvoyer(RUI_NEXT_UP)
        CmdMedia.PRECEDENT ->
            ruiEnvoyer(RUI_PREV_DOWN) && ruiEnvoyer(RUI_PREV_UP)
        CmdMedia.LECTURE_PAUSE ->
            ruiEnvoyer(if (joue) RUI_PAUSE else RUI_PLAY)
    }

    private enum class CmdMedia { SUIVANT, PRECEDENT, LECTURE_PAUSE }

    // Accès croisé : onServiceConnected arrive sur le thread principal, la lecture se fait
    // depuis le contexte IO du raccourci. Des HashMap simples se corrompraient silencieusement.
    private val sMediaBinders = ConcurrentHashMap<String, IBinder>()
    private val sMediaConns = ConcurrentHashMap<String, ServiceConnection>()

    /**
     * Actions dont la liaison n'aboutit pas, avec l'instant du constat.
     *
     * ⚠️ Sans cette mémoire, un service absent du firmware coûtait [MEDIA_BIND_MS] à CHAQUE
     * appui : mesuré sur SWI68, où `CPAA_PLAYER_ACTION` n'existe pas, 1,2 s d'attente avant
     * même d'essayer la voie suivante. Un raccourci de volant qui répond en une seconde et
     * demie passe pour cassé, même quand il finit par agir.
     *
     * Le constat est daté plutôt que définitif : un service peut être simplement lent à
     * démarrer, et l'exclure pour toujours sur un seul échec serait excessif.
     */
    private val sMediaSansReponse = ConcurrentHashMap<String, Long>()

    /** Délai d'attente d'une liaison, en ms. */
    private const val MEDIA_BIND_MS = 1200L

    /** Durée pendant laquelle on ne retente pas une action qui n'a pas répondu. */
    private const val MEDIA_ABSENT_MS = 300_000L

    /**
     * Binder du service média pour une action donnée, ou null.
     *
     * La liaison est asynchrone alors qu'un appui sur le volant attend une action immédiate :
     * on attend donc brièvement le rattachement. C'est sans risque pour l'écran — tout le
     * chemin des raccourcis s'exécute déjà sur un contexte IO.
     *
     * Une action inconnue du firmware (SWI68/165 n'ont ni façade générique ni CarPlay/AA) fait
     * simplement échouer `bindService` : on rend null, et l'appelant passe au repli suivant.
     */
    private fun mediaBinder(action: String, attenteMs: Long = 1200): IBinder? =
        serviceBinder(action, MEDIA_PKG, MEDIA_CLS, attenteMs)

    /**
     * Binder d'un service SAIC, lié à la demande et mis en cache.
     *
     * [cls] est nul pour le service radio : il se lie par paquet + action, pas par classe —
     * c'est ainsi que `RadioOptionManager` procède, et une liaison par classe échouerait.
     */
    private fun serviceBinder(action: String, pkg: String, cls: String?,
                              attenteMs: Long = MEDIA_BIND_MS): IBinder? {
        // Clé de cache : l'action quand il y en a une, sinon le composant visé. Le service de
        // projection allgo se lie en effet par COMPOSANT seul, sans action — deux services
        // différents ne doivent pas se partager une entrée de cache.
        val cle = if (action.isEmpty()) "$pkg/$cls" else action
        sMediaBinders[cle]?.let { if (it.isBinderAlive) return it else sMediaBinders.remove(cle) }
        val ctx = sAppContext ?: return null

        // Cible déjà constatée sans réponse récemment : on rend la main tout de suite plutôt
        // que de refaire attendre l'utilisateur pour le même constat.
        sMediaSansReponse[cle]?.let { instant ->
            if (SystemClock.uptimeMillis() - instant < MEDIA_ABSENT_MS) return null
            sMediaSansReponse.remove(cle)
        }

        if (!sMediaConns.containsKey(cle)) {
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null) sMediaBinders[cle] = service
                    sMediaSansReponse.remove(cle)
                    AppLogger.i(MEDIA_TAG, "lié à ${cle.substringAfterLast('.')}")
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    sMediaBinders.remove(cle)
                    AppLogger.w(MEDIA_TAG, "liaison perdue : ${cle.substringAfterLast('.')}")
                }
                // Le service existe mais refuse CETTE action : son onBind rend null. Le système
                // nous le dit ici, immédiatement — inutile d'attendre le délai pour le découvrir.
                override fun onNullBinding(name: ComponentName?) {
                    sMediaSansReponse[cle] = SystemClock.uptimeMillis()
                    AppLogger.i(MEDIA_TAG, "${cle.substringAfterLast('.')} : " +
                        "le service ne fournit pas cette interface sur ce firmware")
                }
            }
            sMediaConns[cle] = conn
            val ok = try {
                val intent = if (action.isEmpty()) Intent() else Intent(action)
                if (cls != null) intent.setClassName(pkg, cls) else intent.setPackage(pkg)
                ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                AppLogger.w(MEDIA_TAG, "bindService ${cle.substringAfterLast('.')} : " +
                    "${(e.cause ?: e).message}")
                false
            }
            if (!ok) {
                sMediaConns.remove(cle)
                sMediaSansReponse[cle] = SystemClock.uptimeMillis()
                AppLogger.w(MEDIA_TAG, "service indisponible pour " +
                    "${cle.substringAfterLast('.')} (absent de ce firmware ?)")
                return null
            }
        }

        val limite = SystemClock.uptimeMillis() + attenteMs
        while (SystemClock.uptimeMillis() < limite) {
            sMediaBinders[cle]?.let { return it }
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
        }
        // Seule une attente COMPLÈTE vaut constat. La sonde de diagnostic interroge avec un
        // délai raccourci : en tirer une conclusion mettrait de côté un service simplement lent,
        // et le raccourci suivant en pâtirait sans raison.
        if (attenteMs >= MEDIA_BIND_MS) {
            sMediaSansReponse[cle] = SystemClock.uptimeMillis()
            AppLogger.w(MEDIA_TAG, "liaison ${cle.substringAfterLast('.')} non établie en " +
                "${attenteMs} ms — mise de côté ${MEDIA_ABSENT_MS / 1000} s")
        } else {
            AppLogger.w(MEDIA_TAG, "liaison ${cle.substringAfterLast('.')} non établie en " +
                "${attenteMs} ms (sondage court, aucun constat retenu)")
        }
        return null
    }

    /**
     * Appelle une méthode SANS ARGUMENT du service média.
     *
     * ⚠️ Volontairement séparé de [binderTransact] : celui-ci écrit un areaId et une valeur —
     * la forme des propriétés véhicule — et passe par le verrou de vitesse. Changer de piste
     * n'est pas un réglage de conduite ; le bloquer au-delà d'une certaine vitesse n'aurait
     * aucun sens.
     *
     * ⚠️ `transact` rend vrai dès que l'appel a été REÇU, pas qu'il a produit un effet. D'où le
     * journal à chaque étape : c'est lui, pas la valeur de retour, qui dira au volant quelle
     * voie a réellement agi.
     */
    private fun mediaTransact(action: String, descripteur: String, code: Int,
                              pkg: String = MEDIA_PKG, cls: String? = MEDIA_CLS,
                              arg: Int? = null): Boolean {
        val binder = serviceBinder(action, pkg, cls) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descripteur)
            // ⚠️ Toutes ces méthodes ne sont pas sans argument. Omettre celui qu'attend le
            // service ne provoque aucune erreur : il lit un parcel vide et obtient 0 — une
            // valeur parfaitement valide, mais qui n'est pas celle voulue.
            if (arg != null) data.writeInt(arg)
            val ok = binder.transact(code, data, reply, 0)
            reply.readException()
            AppLogger.i(MEDIA_TAG, "${action.substringAfterLast('.')} tx=0x${Integer.toHexString(code)} → $ok")
            ok
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "${action.substringAfterLast('.')} " +
                "tx=0x${Integer.toHexString(code)} : ${(e.cause ?: e).message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Lit un entier rendu par une méthode sans argument du service média, ou null.
     *
     * Un `null` n'est pas un zéro : il signifie « la question n'a pas pu être posée ». Confondre
     * les deux ferait passer une source muette pour une source à l'arrêt.
     */
    private fun mediaLireInt(action: String, descripteur: String, code: Int): Int? {
        val binder = mediaBinder(action) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descripteur)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "${action.substringAfterLast('.')} " +
                "lecture tx=0x${Integer.toHexString(code)} : ${(e.cause ?: e).message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * CarPlay / Android Auto joue-t-il ? `null` si on ne peut pas conclure.
     *
     * `getLastCpAaAudioInfoBean` (tx 0x7) rend un `AudioInfoBean` dont le champ `mPlayState`
     * vaut 3 en lecture — la carte média du launcher le compare à cette valeur exacte. Il faut
     * dérouler le bean dans l'ordre de son `writeToParcel` : id(long), durée(long), puis sept
     * chaînes, deux longs, une chaîne, et enfin **l'état(int)**.
     *
     * Sans cette lecture, la projection retombait sur `isMusicActive`, qui reste vrai deux à
     * trois secondes après une pause : le raccourci renvoyait une pause au lieu d'une reprise,
     * et il fallait patienter avant que la bascule redevienne possible.
     */
    private fun cpAaEnLecture(): Boolean? {
        val binder = serviceBinder(ACT_CPAA, MEDIA_PKG, MEDIA_CLS) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_CPAA)
            binder.transact(0x7, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return null       // bean nul : rien à conclure
            reply.readLong(); reply.readLong()          // id, durée
            repeat(7) { reply.readString() }            // nom, pochette, chemin, artiste, utilisateur, avatar, album
            reply.readLong(); reply.readLong()          // ajout, dernière lecture
            reply.readString()                          // position lisible
            val etat = reply.readInt()
            AppLogger.i(MEDIA_TAG, "état projection = $etat ($PLAYER_STATUS_START = en lecture)")
            etat == PLAYER_STATUS_START
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "état projection illisible : ${(e.cause ?: e).message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
    /** Bande et état de la radio, lus en une seule interrogation. */
    private data class InfoRadio(val type: Int, val enLecture: Boolean)

    /**
     * Lit le `RadioBean` courant : bande écoutée et lecture en cours. `null` si illisible.
     *
     * Les deux informations viennent du même appel parce qu'elles sont dans le même bean, et
     * qu'une seconde interrogation donnerait au mieux le même résultat, au pire un résultat
     * décalé.
     *
     * ⚠️ Lecture dans l'ordre EXACT de `writeToParcel` : enable(byte), nom(String), rds(byte),
     * pochette(String), fréquence(int), **type(int)**, **état(int)**. C'est le seul endroit du
     * projet couplé à la sérialisation d'un bean SAIC — tout est sous try/catch, et un échec
     * vaut `null`, jamais une supposition.
     *
     * `isPlaying()` du SDK radio se résume à `getRadioState() == 1` ; `next()`/`previous()`, eux,
     * reçoivent `getRadioType()`.
     */
    private fun radioInfo(): InfoRadio? {
        val binder = serviceBinder(RADIO_ACT, RADIO_PKG, null) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_RADIO)
            binder.transact(RADIO_INFO, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return null      // bean nul
            reply.readByte(); reply.readString()       // enable, nom
            reply.readByte(); reply.readString()       // rds, pochette
            reply.readInt()                            // fréquence
            val type = reply.readInt()
            val etat = reply.readInt()
            AppLogger.i(MEDIA_TAG, "radio : type=$type état=$etat (1 = en lecture)")
            InfoRadio(type, etat == 1)
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "état radio illisible : ${(e.cause ?: e).message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Source audio réellement active, ou -1 si le service SAIC ne répond pas. */
    private fun mediaSourceCourante(): Int {
        val v = mediaLireInt(ACT_STATUS, DESC_STATUS, 0x9) ?: return -1   // getCurrentMediaSource
        AppLogger.i(MEDIA_TAG, "source courante = $v (${nomSource(v)})")
        return v
    }

    /**
     * La source est-elle en train de jouer ?
     *
     * ⚠️ On interroge la SOURCE quand elle sait répondre, et `isMusicActive` seulement à défaut.
     * Ce dernier reste vrai un moment après un arrêt — c'est ce qui obligeait à patienter une à
     * trois secondes entre deux appuis : le second appui relisait « ça joue encore » et envoyait
     * une seconde pause au lieu d'une lecture.
     */
    private fun enLecture(source: Int): Boolean {
        val reel = when (source) {
            // getPlayState : booléen sérialisé en int.
            SRC_BT -> mediaLireInt(ACT_BT, DESC_BT, 0x9)?.let { it != 0 }
            // getPlayerStatus : un état parmi PLAYER_STATUS_*.
            SRC_ONLINE -> mediaLireInt(ACT_ONLINE, DESC_ONLINE, 0x6)?.let { it == PLAYER_STATUS_START }
            // La projection expose son état dans son bean, pas par un getter direct.
            SRC_CARPLAY, SRC_AA -> cpAaEnLecture()
            else -> null
        }
        if (reel != null) {
            AppLogger.i(MEDIA_TAG, "état de lecture (source ${nomSource(source)}) = $reel")
            return reel
        }
        // Les sessions AVANT isMusicActive : elles disent un état réel, lui reste vrai
        // plusieurs secondes après un arrêt. Sans cet ordre, la projection sur SWI68 et SWI165 —
        // où l interface CarPlay/AA du SDK n existe pas, donc où l état n est jamais exposé —
        // gardait le drapeau trompeur, et une pause suivie d une reprise renvoyait une pause.
        val parSession = sessionJoue()
        if (parSession != null) {
            AppLogger.i(MEDIA_TAG, "état non exposé par ${nomSource(source)} — sessions = $parSession")
            return parSession
        }
        val repli = musiqueEnCours()
        AppLogger.i(MEDIA_TAG, "état non exposé par ${nomSource(source)} et sessions muettes — " +
            "repli isMusicActive = $repli")
        return repli
    }

    /** Vrai si quelque chose joue — sert à choisir entre `play` et `pause`. */
    private fun musiqueEnCours(): Boolean =
        (sAppContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true

    /**
     * Exécute une commande média en essayant les voies dans l'ordre de fiabilité décroissante.
     *
     * 1. **La source déclarée par le service** : c'est la seule voie déterministe, celle que le
     *    launcher emprunte lui-même.
     * 2. **La façade générique** `MEDIA_PLAYER_ACTION`, qui n'aiguille pas — utile quand la
     *    source courante est illisible ou inconnue de notre table. Absente de SWI68/165.
     * 3. **CarPlay / Android Auto**, interface dédiée. Absente elle aussi de SWI68/165.
     * 4. **La touche média Android**, dernier recours : c'est la seule voie sur les firmwares A9,
     *    où ce service SAIC n'existe pas, et la seule qui atteigne une session Bluetooth.
     */
    /** `getCurrentAudioType` de `ICarAudioService` (A9). */
    private const val AUDIO_GET_CURRENT_TYPE = 0x2c

    /**
     * SONDE — source audio déclarée par le véhicule sur A9, ou `null` si indisponible.
     *
     * Elle ne sert encore À RIEN dans les décisions : elle est là pour être relevée dans les
     * rapports, parce qu'il nous manque une information et une seule.
     *
     * Le problème mesuré sur SWI132 : quand un téléphone est connecté, la session Bluetooth et
     * celle de la radio se déclarent **toutes deux en lecture**. Relevé sur un essai radio :
     * 18 fois la radio retenue, 16 fois le Bluetooth — un tirage au sort. Départager par
     * l'ordre de la liste ne peut pas marcher.
     *
     * `getCurrentAudioType` est la seule source qui sache dire quelle source est réellement
     * audible. Il reste à apprendre son espace de valeurs : d'où cette lecture, journalisée
     * dans les cas ambigus et dans le rapport de diagnostic. Deux rapports — un sur radio, un
     * sur Bluetooth — suffiront à établir la table, et l'aiguillage deviendra déterministe.
     *
     * ⚠️ Absent du launcher SWI131 (interface plus ancienne, 0x2c hors plage) : la transaction
     * y est rejetée sans effet de bord. Vérifié dans le smali avant d'oser l'appel.
     */
    private fun typeAudioCourant(): Int? {
        if (!isA9Sound()) return null
        val h = sAudioHelper ?: return null
        if (!h.isBinderAlive) return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(sAudioDescriptor)
            if (!h.transact(AUDIO_GET_CURRENT_TYPE, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Une session est-elle en lecture ? `null` si on ne peut pas les consulter.
     *
     * ⚠️ Sert à décider du sens de la bascule pour la projection, à la place de `isMusicActive`.
     * Ce dernier reste vrai plusieurs secondes après un arrêt — le piège déjà rencontré sur les
     * autres sources : après une pause, on renvoyait une pause au lieu d'une reprise.
     */
    private fun sessionJoue(): Boolean? {
        val ctx = sAppContext ?: return null
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        return try {
            msm.getActiveSessions(null)
                .any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pilotage par **session média** — la voie du framework Android.
     *
     * C'est ainsi que le launcher d'origine procède sur les firmwares A9 : son `MediaModel`
     * appelle `MediaSessionManager.getActiveSessions()` puis les `TransportControls` du
     * contrôleur. Chaque source y publie son propre MediaBrowserService (Bluetooth, projection
     * allgo, CarPlay, en ligne, USB). C'est donc la seule voie sur A9, où le service média SAIC
     * n'existe pas — et le seul recours pour CarPlay/Android Auto sur SWI68 et SWI165, dont le
     * SDK média ne contient pas l'interface de projection.
     *
     * ⚠️ SÛRE PAR CONSTRUCTION, contrairement à l'envoi d'une touche média : on ne commande que
     * la session dont l'état DÉCLARE qu'elle joue — c'est-à-dire la source déjà audible. Elle ne
     * peut donc pas réveiller une source endormie, l'erreur qui faisait changer de source.
     * Seule exception, la reprise : elle n'agit que si AUCUNE session ne joue.
     */
    private fun sessionMedia(cmd: CmdMedia): Boolean {
        val ctx = sAppContext ?: return false
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false
        val sessions: List<MediaController> = try {
            msm.getActiveSessions(null)
        } catch (e: SecurityException) {
            AppLogger.w(MEDIA_TAG, "sessions inaccessibles — MEDIA_CONTENT_CONTROL absente " +
                "de cette build ? (${e.message})")
            return false
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "sessions illisibles : ${(e.cause ?: e).message}")
            return false
        }

        // SONDE : plusieurs sessions qui se déclarent en lecture EN MÊME TEMPS, c'est le cas
        // qu'on ne sait pas trancher. On le journalise avec le type audio du véhicule — le
        // choix ci-dessous, lui, est inchangé.
        val enLecture = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (enLecture.size > 1) {
            AppLogger.w(MEDIA_TAG, "AMBIGU : ${enLecture.size} sessions se déclarent en lecture " +
                "(${enLecture.joinToString { it.packageName ?: "?" }}) — type audio véhicule = " +
                "${typeAudioCourant() ?: "non exposé"} — retenue : ${enLecture[0].packageName}")
        }
        val joue = enLecture.firstOrNull()
        val cible = joue
            ?: if (cmd == CmdMedia.LECTURE_PAUSE)
                   // ⚠️ Pour REPRENDRE, on ne demande pas un état précis mais une CAPACITÉ.
                   // Exiger STATE_PAUSED était trop strict : mesuré sur SWI132, la radio mise en
                   // pause ne se déclare pas forcément « en pause » — elle peut annoncer arrêtée
                   // ou aucun état. On ne trouvait alors aucune cible et la reprise était
                   // impossible, alors que la pause fonctionnait parfaitement.
                   //
                   // La liste est ordonnée par priorité : la première session capable de
                   // reprendre est la plus récemment active, donc celle que l'utilisateur veut.
                   sessions.firstOrNull {
                       ((it.playbackState?.actions ?: 0L) and
                           (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
                   }
               else null

        if (cible == null) {
            AppLogger.i(MEDIA_TAG, "aucune session exploitable parmi ${sessions.size} " +
                "(${sessions.joinToString { "${it.packageName}:${it.playbackState?.state}" }})")
            return false
        }

        // ⚠️ LA SESSION DE PROJECTION EST À ÉCARTER, quoi qu'elle déclare.
        //
        // Mesuré sur deux véhicules : elle annonce PAUSE, PLAY et PLAY_PAUSE (actions=0x240286)
        // et n'en honore AUCUN — sur SWI132, lecture et pause restaient sans effet alors que
        // `déclarée=true`. Elle n'annonce pas non plus les changements de piste, qui ne
        // marchaient pas davantage sur SWI131.
        //
        // Autrement dit, ses déclarations ne valent rien. Le service allgo, lui, fonctionne pour
        // les trois commandes : on lui rend la main immédiatement plutôt que de perdre l'appui
        // dans une session qui acquiesce sans agir.
        if (cible.packageName?.startsWith(PREFIXE_PROJECTION) == true) {
            AppLogger.i(MEDIA_TAG, "session ${cible.packageName} = projection — écartée, " +
                "ses déclarations ne sont pas honorées (voie allgo)")
            return false
        }

        // Une session annonce ce qu'elle sait faire dans `actions`. Ignorer cette déclaration
        // revient à parler dans le vide : `skipToNext()` sur une session qui ne déclare pas
        // ACTION_SKIP_TO_NEXT ne lève rien et ne fait rien — exactement ce qu'a vécu le testeur
        // SWI131 sur Android Auto, session trouvée et en lecture, commande sans effet.
        val actions = cible.playbackState?.actions ?: 0L
        val requise = when (cmd) {
            CmdMedia.SUIVANT -> PlaybackState.ACTION_SKIP_TO_NEXT
            CmdMedia.PRECEDENT -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            CmdMedia.LECTURE_PAUSE ->
                PlaybackState.ACTION_PLAY_PAUSE or
                    (if (joue != null) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)
        }
        val declaree = (actions and requise) != 0L
        AppLogger.i(MEDIA_TAG, "session ${cible.packageName} état=${cible.playbackState?.state} " +
            "actions=0x${java.lang.Long.toHexString(actions)} ${cmd.name} déclarée=$declaree")

        return try {
            if (declaree) {
                when (cmd) {
                    CmdMedia.SUIVANT -> cible.transportControls.skipToNext()
                    CmdMedia.PRECEDENT -> cible.transportControls.skipToPrevious()
                    CmdMedia.LECTURE_PAUSE ->
                        if (joue != null) cible.transportControls.pause()
                        else cible.transportControls.play()
                }
            } else {
                // Action non déclarée : on ne tente RIEN par cette voie et on rend la main.
                //
                // Le bouton média envoyé à la session avait été essayé ici — sans effet, mesuré
                // sur SWI68 et SWI131. La vraie réponse pour la projection est le service allgo,
                // et rendre « vrai » à tort empêcherait l'appelant de l'essayer.
                AppLogger.i(MEDIA_TAG, "${cmd.name} non déclarée par ${cible.packageName} — " +
                    "voie session abandonnée")
                return false
            }
            true
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "commande de session refusée : ${(e.cause ?: e).message}")
            false
        }
    }

    /**
     * Quand le code de source est inconnu, on DEMANDE aux lecteurs lequel joue.
     *
     * C'est ce qui rend le pilotage robuste malgré une table de sources incomplète : on ne
     * commande que celui qui se déclare en lecture, donc jamais une source endormie — c'est
     * exactement l'erreur qui faisait changer de source auparavant.
     */
    private fun sourceQuiJoue(): Int? {
        if (mediaLireInt(ACT_BT, DESC_BT, 0x9)?.let { it != 0 } == true) return SRC_BT
        if (mediaLireInt(ACT_USB, DESC_USB, 0x1e)?.let { it != 0 } == true) return SRC_USB
        if (mediaLireInt(ACT_ONLINE, DESC_ONLINE, 0x6) == PLAYER_STATUS_START) return SRC_ONLINE
        return null
    }

    /**
     * Façade générique `MEDIA_PLAYER_ACTION` — elle s'adresse à la source courante, sans
     * aiguillage. Présente sur SWI133 seulement.
     *
     * ⚠️ `play` (tx 2) attend un TYPE DE MÉDIA : le launcher lui passe
     * `getLastMediaInfoBean().getMediaType()`. `next`, `prev` et `pause` n'attendent rien.
     *
     * On ne l'appelle donc PAS pour reprendre une lecture : l'envoyer sans type ferait lire 0 au
     * service — `MEDIA_TYPE_NONE` — et le résultat serait au mieux sans effet, au pire une
     * source démarrée au hasard. Même principe que la bande radio : sans l'argument, on
     * s'abstient. Cette voie n'est de toute façon qu'un repli ; si un rapport montre qu'on
     * s'y arrête vraiment, on lira le bean pour obtenir le type.
     */
    private fun facadeGenerique(cmd: CmdMedia, joue: Boolean): Boolean {
        if (cmd == CmdMedia.LECTURE_PAUSE && !joue) {
            AppLogger.i(MEDIA_TAG, "façade générique : reprise impossible sans type de média — " +
                "aucune action")
            return false
        }
        return mediaTransact(ACT_MEDIA, DESC_MEDIA, when (cmd) {
            CmdMedia.SUIVANT -> 5
            CmdMedia.PRECEDENT -> 4
            CmdMedia.LECTURE_PAUSE -> 3   // pause : sans argument
        })
    }

    private fun commandeMedia(cmd: CmdMedia): Boolean {
        val src = mediaSourceCourante()

        // Service SAIC muet : c'est le cas des firmwares A9, où il n'existe pas. Trois voies
        // s'y succèdent, de la plus précise à la plus grossière.
        if (src < 0) {
            // Firmwares A9 : le service média SAIC n'existe pas, et leur launcher n'en utilise
            // pas non plus — il pilote les sessions média du framework. C'est donc la voie
            // normale ici, pas un pis-aller.
            AppLogger.i(MEDIA_TAG, "service média SAIC absent — pilotage par session")
            if (sessionMedia(cmd)) return true

            // La session couvre radio, Bluetooth et USB sur ces firmwares. Ce qu'elle ne couvre
            // pas, c'est la projection : elle ne déclare ni piste suivante ni précédente. D'où
            // le service allgo, qui est justement la voie que leur launcher emprunte.
            // L'état vient des sessions, pas d'isMusicActive : c'est la seule source fiable
            // ici, et `sessionMedia` vient justement de les consulter.
            if (ruiMedia(cmd, sessionJoue() ?: musiqueEnCours())) return true

            // Ultime recours, si l'énumération des sessions est refusée : la touche média, mais
            // UNIQUEMENT si quelque chose joue. Sans ce garde-fou, l'envoyer pendant que la
            // radio joue (isMusicActive = faux, mesuré) réveillerait la session Bluetooth et
            // changerait la source — le défaut corrigé plus haut, revenu par la porte de
            // derrière. Contrepartie assumée : pas de reprise d'une lecture déjà arrêtée.
            if (!musiqueEnCours()) {
                AppLogger.i(MEDIA_TAG, "aucune session exploitable et aucune lecture en cours — " +
                    "aucune touche envoyée")
                return false
            }
            return envoyerToucheMedia(when (cmd) {
                CmdMedia.SUIVANT -> KeyEvent.KEYCODE_MEDIA_NEXT
                CmdMedia.PRECEDENT -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                CmdMedia.LECTURE_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            })
        }

        // ⚠️ AUCUNE CASCADE ENTRE SOURCES. Essayer les binders l'un après l'autre revenait à
        // commander une source qui ne jouait pas : elle répondait « oui » et se remettait à
        // jouer, ce qui CHANGEAIT la source sous les doigts de l'utilisateur.
        val cible = if (src in SRC_RADIO_MIN..SRC_RADIO_MAX || src == SRC_BT ||
                        src == SRC_ONLINE || src == SRC_USB || src == SRC_CARPLAY ||
                        src == SRC_AA) src
                    else sourceQuiJoue()?.also {
                        AppLogger.i(MEDIA_TAG, "code $src inconnu — lecteur identifié : ${nomSource(it)}")
                    } ?: src

        val ok = when {
            cible in SRC_RADIO_MIN..SRC_RADIO_MAX -> {
                // La radio a son propre service : next/previous y changent de station, et
                // srcPlayRadio/srcPauseRadio sont les commandes que le launcher utilise.
                val info = radioInfo()
                // Un état illisible est traité comme « en lecture » : c'est l'état normal
                // d'une radio qui est la source active, et une pause de trop se corrige d'un
                // second appui — l'inverse laisserait le raccourci sans effet.
                val joue = info?.enLecture != false
                val code = when (cmd) {
                    CmdMedia.SUIVANT -> RADIO_NEXT
                    CmdMedia.PRECEDENT -> RADIO_PREV
                    CmdMedia.LECTURE_PAUSE -> if (joue) RADIO_PAUSE else RADIO_PLAY
                }

                // ⚠️ `next` et `previous` attendent la BANDE écoutée, contrairement à
                // play/pause. Sans elle le service lit 0 et bascule en FM : mesuré sur SWI133,
                // une station DAB retombait systématiquement en FM. Bande inconnue = on
                // s'abstient, plutôt que de changer de bande dans le dos de l'utilisateur.
                val bande = if (cmd == CmdMedia.LECTURE_PAUSE) null else info?.type
                if (cmd != CmdMedia.LECTURE_PAUSE && bande == null) {
                    AppLogger.w(MEDIA_TAG, "radio : bande illisible — aucune action " +
                        "(l'envoyer sans la bande ferait basculer en FM)")
                    return false
                }

                val ok = mediaTransact(RADIO_ACT, DESC_RADIO, code, RADIO_PKG, null, bande)
                // Repli DANS la source uniquement : la façade générique s'adresse à la source
                // courante, elle ne peut donc pas en réveiller une autre.
                if (ok) true else facadeGenerique(cmd, joue)
            }

            cible == SRC_BT -> mediaTransact(ACT_BT, DESC_BT, when (cmd) {
                CmdMedia.SUIVANT -> 5
                CmdMedia.PRECEDENT -> 4
                CmdMedia.LECTURE_PAUSE -> if (enLecture(cible)) 1 else 2
            })

            cible == SRC_USB -> mediaTransact(ACT_USB, DESC_USB, when (cmd) {
                CmdMedia.SUIVANT -> 0x1a          // playNextMusic
                CmdMedia.PRECEDENT -> 0x19        // playLastMusic
                CmdMedia.LECTURE_PAUSE -> 0xc     // playOrPause : vraie bascule
            })

            cible == SRC_ONLINE -> mediaTransact(ACT_ONLINE, DESC_ONLINE, when (cmd) {
                CmdMedia.SUIVANT -> 4
                CmdMedia.PRECEDENT -> 3
                CmdMedia.LECTURE_PAUSE -> if (enLecture(cible)) 1 else 2
            })

            cible == SRC_CARPLAY || cible == SRC_AA -> {
                // L'état n'est lu que si la commande en dépend : inutile d'imposer une lecture
                // binder à « piste suivante », qui n'en a que faire.
                val joue = cmd == CmdMedia.LECTURE_PAUSE && enLecture(cible)
                // SWI133 par le SDK SAIC, tous les autres par le service allgo. Les deux voies
                // sont exclusives selon le firmware : celle qui est absente échoue sans effet.
                mediaTransact(ACT_CPAA, DESC_CPAA, when (cmd) {
                    CmdMedia.SUIVANT -> 4
                    CmdMedia.PRECEDENT -> 3
                    CmdMedia.LECTURE_PAUSE -> if (joue) 2 else 1
                }) || ruiMedia(cmd, joue)
            }

            else -> {
                AppLogger.i(MEDIA_TAG, "source ${nomSource(cible)} sans lecteur identifié — " +
                    "façade générique")
                facadeGenerique(cmd, enLecture(cible))
            }
        }
        if (ok) return true

        // Repli universel, et sans danger : la voie des sessions ne commande que ce qui joue
        // déjà. C'est elle qui rattrape CarPlay et Android Auto sur SWI68 et SWI165, dont le
        // SDK média ne contient pas l'interface de projection.
        AppLogger.i(MEDIA_TAG, "voie SAIC sans effet sur ${nomSource(cible)} — essai par session")
        return sessionMedia(cmd)
    }

    fun mediaNext(): Boolean = commandeMedia(CmdMedia.SUIVANT)

    fun mediaPrevious(): Boolean = commandeMedia(CmdMedia.PRECEDENT)

    fun mediaPlayPause(): Boolean = commandeMedia(CmdMedia.LECTURE_PAUSE)

    /**
     * Monte ou descend le volume média d'un cran.
     *
     * Deux voies, dans cet ordre :
     *  1. la voie **SAIC** ([getMediaVolume] / [setMediaVolume]) — la même que la baisse à
     *     l'ouverture de porte, déjà validée sur véhicule. C'est la seule qui donne un niveau
     *     ABSOLU, donc un log exploitable et un vrai respect des bornes ;
     *  2. à défaut, `AudioManager.adjustStreamVolume`, la voie Android standard. Elle ne dit pas
     *     d'où l'on part, mais elle répond là où les managers SAIC ne sont pas liés — et elle
     *     affiche l'indicateur système, ce qui donne un retour visuel.
     *
     * Contrairement aux touches média, aucune des deux ne dépend d'une application : le volume
     * est un réglage du véhicule.
     */
    fun mediaVolumeStep(delta: Int): Boolean {
        assurerHelperAudio()
        val actuel = getMediaVolume()
        val max = getMediaVolumeMax()
        if (actuel >= 0 && max > 0) {
            val cible = (actuel + delta).coerceIn(0, max)
            if (cible == actuel) {
                AppLogger.i(VOL_TAG, "volume déjà à la borne ($actuel/$max) — aucune écriture")
                return true
            }
            AppLogger.i(VOL_TAG, "volume $actuel → $cible (max $max, voie SAIC)")
            return setMediaVolume(cible)
        }
        val am = sAppContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            AppLogger.w(VOL_TAG, "volume : ni voie SAIC ni AudioManager — aucune action")
            return false
        }
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            AppLogger.i(VOL_TAG, "volume ${if (delta > 0) "+1" else "-1"} " +
                "(voie Android — niveau SAIC illisible, actuel=$actuel max=$max)")
            true
        } catch (e: Exception) {
            AppLogger.w(VOL_TAG, "volume : ${(e.cause ?: e).message}")
            false
        }
    }

    /**
     * Sonde média, LECTURE SEULE — elle n'envoie aucune commande de lecture.
     *
     * Elle a déjà tranché la question de départ : une seule MediaSession existe sur la voiture
     * (`com.android.bluetooth`), ce qui condamnait la voie des touches média. Elle reste utile
     * pour la suite : identifier la valeur de `getCurrentMediaSource` des sources non encore
     * répertoriées (radio, projection), et vérifier quelles interfaces répondent sur un
     * firmware donné.
     */
    fun runMediaDiag() {
        val ctx = sAppContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        AppLogger.i(MEDIA_TAG, "── DIAG média ────────────────────────────────")
        AppLogger.i(MEDIA_TAG, "lecture en cours (isMusicActive) = ${am?.isMusicActive}")
        // Type audio déclaré par le véhicule (A9). À relever une fois par source pour établir
        // la table — voir [typeAudioCourant].
        AppLogger.i(MEDIA_TAG, "type audio véhicule (A9) = ${typeAudioCourant() ?: "non exposé"}")

        // Sessions média : c'est ce qui a montré que les touches média ne pouvaient pas aboutir.
        try {
            val msm = ctx.getSystemService("media_session")
                ?: throw IllegalStateException("MediaSessionManager absent")
            val m = msm.javaClass.getMethod("getActiveSessions", ComponentName::class.java)
            @Suppress("UNCHECKED_CAST")
            val sessions = m.invoke(msm, null) as? List<Any>
            AppLogger.i(MEDIA_TAG, "sessions média actives : ${sessions?.size ?: 0}")
            sessions?.forEach { session ->
                val pkg = runCatching {
                    session.javaClass.getMethod("getPackageName").invoke(session)
                }.getOrNull()
                // Les ACTIONS déclarées valent autant que l'état : une session qui joue mais
                // n'annonce pas SKIP_TO_NEXT laissera « piste suivante » sans effet, et rien
                // dans le comportement ne permet de le deviner.
                val etat = runCatching {
                    session.javaClass.getMethod("getPlaybackState").invoke(session)
                }.getOrNull()
                val num = runCatching {
                    etat?.javaClass?.getMethod("getState")?.invoke(etat) as? Int
                }.getOrNull()
                val actions = runCatching {
                    etat?.javaClass?.getMethod("getActions")?.invoke(etat) as? Long
                }.getOrNull()
                AppLogger.i(MEDIA_TAG, "  session : $pkg état=${num ?: "?"} " +
                    "actions=0x${java.lang.Long.toHexString(actions ?: 0L)}")
            }
        } catch (e: Exception) {
            val cause = e.cause ?: e
            AppLogger.w(MEDIA_TAG, "sessions média illisibles : " +
                "${cause.javaClass.simpleName} — ${cause.message}")
        }

        // Service média SAIC : quelles interfaces répondent, et sur quelle source.
        AppLogger.i(MEDIA_TAG, "service SAIC $MEDIA_PKG :")
        mediaSourceCourante()
        listOf(ACT_MEDIA to "façade générique", ACT_CPAA to "CarPlay/AA", ACT_BT to "Bluetooth",
               ACT_USB to "USB", ACT_ONLINE to "en ligne").forEach { (action, nom) ->
            val lie = mediaBinder(action, attenteMs = 400) != null
            AppLogger.i(MEDIA_TAG, "  $nom (${action.substringAfterLast('.')}) : " +
                if (lie) "lié ✓" else "absent")
        }
        AppLogger.i(MEDIA_TAG, "──────────────────────────────────────────────")
    }

    /**
     * Envoie une touche média au système, qui la remet à la session active.
     *
     * DOWN **puis** UP : une session n'a aucune obligation d'agir sur l'appui, plusieurs
     * n'agissent qu'au relâchement. N'envoyer que le DOWN laisserait en plus une touche
     * « enfoncée » du point de vue du système.
     *
     * ⚠️ Dernier recours uniquement — voir l'avertissement en tête de section.
     */
    private fun envoyerToucheMedia(keyCode: Int): Boolean {
        val am = sAppContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            AppLogger.w(MEDIA_TAG, "AudioManager indisponible — touche média non envoyée")
            return false
        }
        return try {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            AppLogger.i(MEDIA_TAG, "touche ${KeyEvent.keyCodeToString(keyCode)} envoyée " +
                "(lecture en cours = ${am.isMusicActive})")
            true
        } catch (e: Exception) {
            AppLogger.w(MEDIA_TAG, "envoi impossible : ${(e.cause ?: e).message}")
            false
        }
    }

    /** Baisse du volume à l'ouverture de porte : détection DLOCK_DOOR_OPEN_STS via CarPropertyManager.
     *  Lisible/fonctionnel uniquement sur SWI132 et SWI133 ; ailleurs le prop n'est pas exposé à l'app. */
    fun hasDoorVolumeFeature(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI132 || gen == FirmwareInfo.Gen.SWI133
    }

    private fun doorVolumeEnabled(): Boolean {
        // Mode Garage : une portière qui fait chuter le volume est exactement le genre de
        // comportement inexpliqué qu'on ne veut pas laisser observer.
        if (GarageMode.isOn(sAppContext)) return false
        return sAppContext?.getSharedPreferences("mg4_settings", 0)
            ?.getBoolean("door_volume_enabled", false) ?: false
    }

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

    // ── Sonde température (bouton Diagnostic) ─────────────────────────────────
    private const val TEMP_TAG = "MG4_TEMP"
    // Source RÉELLE de la temp (décompilé SystemUI SWI133) : service clim SAIC, PAS une propriété CPM.
    private const val AIRCON_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.AirConditionManager"
    @Volatile private var sAirCondition: Any? = null

    // Voie CPM (secondaire). ⚠ SAIC a INVERSÉ current/set (0x…502/503) par rapport à l'AAOS.
    private const val PROP_ENV_OUTSIDE_TEMP  = 0x11600703  // ENV_OUTSIDE_TEMPERATURE (AAOS std — renvoie 0 ici)
    private const val PROP_HVAC_TEMP_OUTCAR  = 0x15602511  // HVAC_TEMPERATURE_OUTCAR (vendor SAIC = temp extérieure)
    private const val PROP_HVAC_AMBIENT_TEMP = 0x1560252a  // HVAC_AMBIENT_TEMPERATURE (vendor SAIC)
    private const val PROP_HVAC_TEMP_CURRENT = 0x15600502  // HVAC_TEMPERATURE_CURRENT (SAIC — inversé vs AAOS)
    private val TEMP_HVAC_AREAS = intArrayOf(0x1, 0x2, 0x4, AREA_HVAC, AREA_GLOBAL, 0)

    private fun fmtTemp(v: Float?): String = when {
        v == null || v.isNaN() -> "illisible"
        v <= -1000f            -> "n/c(${"%.0f".format(v)})"   // sentinelle SAIC -10000 = service non connecté
        else                   -> "%.1f".format(v)
    }

    /** Lit un getter float sans argument sur le manager clim SAIC (réflexion). */
    private fun acFloat(name: String): Float? {
        val ac = sAirCondition ?: return null
        return try { ac.javaClass.getMethod(name).invoke(ac) as? Float } catch (_: Exception) { null }
    }
    /** Lit un getter int sans argument sur le manager clim SAIC (réflexion). */
    private fun acInt(name: String): Int? {
        val ac = sAirCondition ?: return null
        return try { ac.javaClass.getMethod(name).invoke(ac) as? Int } catch (_: Exception) { null }
    }

    /**
     * Bind (async) au service clim SAIC — `AirConditionManager`, même SDK que VehicleConditionManager
     * (Katman5). C'est la VRAIE source de la temp extérieure (`getOutCarTemp`), pas une propriété CPM.
     * No-op silencieux si le SDK est absent (ex. A9, autre package). Idempotent.
     */
    private fun initAirCondition(context: Context) {
        if (sAirCondition != null) return
        val launcherCtx = listOf(LAUNCHER68_PKG, LAUNCHER69_PKG).firstNotNullOfOrNull { pkg ->
            try {
                context.createPackageContext(
                    pkg,
                    android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
                )
            } catch (_: Exception) { null }
        } ?: return

        val acClass = try {
            launcherCtx.classLoader.loadClass(AIRCON_CLASS)
        } catch (e: Exception) {
            AppLogger.d(TEMP_TAG, "AirConditionManager absent: ${e.message}")
            return
        }
        fun singleton(): Any? = try { acClass.getMethod("getInstance").invoke(null) } catch (_: Exception) { null }

        val initMethod = acClass.methods.firstOrNull { m ->
            m.name == "init" && m.parameterCount == 2 &&
            Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }
        if (initMethod != null) {
            val listenerType = initMethod.parameterTypes[1]
            val listenerArg: Any? = if (listenerType.isInterface) try {
                java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, method, _ ->
                    if (method.name == "onServiceConnected") {
                        AppLogger.i(TEMP_TAG, "AirCondition: onServiceConnected ✓")
                        sAirCondition = singleton()
                    }
                    null
                }
            } catch (_: Exception) { null } else null
            try {
                initMethod.invoke(null, context.applicationContext, listenerArg)
                AppLogger.i(TEMP_TAG, "AirCondition.init() appelé")
            } catch (e: Exception) {
                AppLogger.w(TEMP_TAG, "AirCondition.init() erreur: ${e.message}")
            }
        }
        // Handle immédiat ; la valeur sera valide dès que le service est connecté (async).
        if (sAirCondition == null) sAirCondition = singleton()
    }

    /**
     * Sonde du bouton Diagnostic (lecture seule). Voie principale = service clim SAIC
     * (`getOutCarTemp`, ce que fait l'OEM). Voie CPM = secondaire, teste les IDs vendor.
     */
    fun runTemperatureDiag() {
        AppLogger.i(TEMP_TAG, "── DIAG température ──")
        sAppContext?.let { initAirCondition(it) }   // au cas où l'init au démarrage n'a pas abouti

        // Voie OEM (la bonne).
        if (sAirCondition == null) {
            AppLogger.i(TEMP_TAG, "AirConditionManager indisponible (SDK non chargé) — voir voie CPM")
        } else {
            AppLogger.i(TEMP_TAG, "OEM getOutCarTemp=${fmtTemp(acFloat("getOutCarTemp"))} " +
                "drvSet=${acInt("getDrvTemp") ?: "?"} psgSet=${acInt("getPsgTemp") ?: "?"}")
        }

        // Voie CPM secondaire : IDs vendor SAIC (au cas où certains soient lisibles en direct).
        AppLogger.i(TEMP_TAG, "CPM EXTstd(0x11600703)=${fmtTemp(getFloatPropertyCPM(PROP_ENV_OUTSIDE_TEMP, AREA_GLOBAL))} " +
            "OUTCAR(0x15602511)=${fmtTemp(getFloatPropertyCPM(PROP_HVAC_TEMP_OUTCAR, AREA_GLOBAL))} " +
            "AMBIENT(0x1560252a)=${fmtTemp(getFloatPropertyCPM(PROP_HVAC_AMBIENT_TEMP, AREA_GLOBAL))}")
        for (area in TEMP_HVAC_AREAS) {
            val a = "0x${Integer.toHexString(area)}"
            AppLogger.i(TEMP_TAG, "CPM area=$a OUTCAR=${fmtTemp(getFloatPropertyCPM(PROP_HVAC_TEMP_OUTCAR, area))} " +
                "AMBIENT=${fmtTemp(getFloatPropertyCPM(PROP_HVAC_AMBIENT_TEMP, area))} " +
                "CURRENT=${fmtTemp(getFloatPropertyCPM(PROP_HVAC_TEMP_CURRENT, area))}")
        }
    }

    /**
     * Température extérieure en °C, ou null si illisible. Voie OEM (`getOutCarTemp`) puis
     * repli CPM (`HVAC_TEMPERATURE_OUTCAR` @ zone 0x75, validé sur SWI133). Sentinelle SAIC
     * (-10000) et NaN => null. Lecture seule.
     */
    fun getOutsideTempCelsius(): Float? {
        acFloat("getOutCarTemp")?.let { if (!it.isNaN() && it > -1000f) return it }
        getFloatPropertyCPM(PROP_HVAC_TEMP_OUTCAR, AREA_HVAC)?.let { if (!it.isNaN() && it > -1000f) return it }
        return null
    }

    // ── Sonde vitesse (bouton Diagnostic) ─────────────────────────────────────
    private const val SPEED_TAG = "MG4_SPEED"

    private fun fmtSpeed(v: Float?): String =
        if (v == null || v.isNaN()) "illisible" else "%.1f".format(v)

    /** Lit un getter float sans argument sur VehicleConditionManager (Katman5, old-SDK). */
    private fun vcmFloat(name: String): Float? {
        val vcm = sVcm ?: return null
        return try { vcm.javaClass.getMethod(name).invoke(vcm) as? Float } catch (_: Exception) { null }
    }

    /**
     * Sonde du bouton Diagnostic : logge la vitesse BRUTE telle que rendue par le véhicule,
     * pour valider l'unité firmware par firmware. Lecture seule.
     *
     * Mode d'emploi : rouler à une vitesse connue (ex. 50 au compteur) et cliquer Diagnostic.
     *  - valeur brute ≈ compteur  → km/h (ce que l'app suppose désormais) ✓
     *  - valeur brute ≈ compteur/3,6 → m/s (il faudrait reconvertir sur ce firmware)
     */
    fun runSpeedDiag() {
        AppLogger.i(SPEED_TAG, "── DIAG vitesse ──")
        val rawGlobal = getFloatPropertyCPM(PROP_VEHICLE_SPEED, AREA_GLOBAL)
        val rawZero   = getFloatPropertyCPM(PROP_VEHICLE_SPEED, 0)
        val oem       = vcmFloat("getCarSpeed")   // sentinelle OEM -1.0f = indisponible
        AppLogger.i(SPEED_TAG, "CPM brut(0x11600207) area=GLOBAL: ${fmtSpeed(rawGlobal)} | area=0: ${fmtSpeed(rawZero)}")
        AppLogger.i(SPEED_TAG, "OEM getCarSpeed (VCM): ${fmtSpeed(oem)} (-1,0 = service indispo)")
        AppLogger.i(SPEED_TAG, "→ vitesse retenue par l'app: ${fmtSpeed(getVehicleSpeedKmh())} km/h")
        AppLogger.i(SPEED_TAG, "Comparer au compteur : identique = km/h OK ; ~3,6x plus petit = m/s")
    }

    // ── Sonde climatisation (bouton Diagnostic) ───────────────────────────────
    private const val CLIM_TAG = "MG4_CLIM"

    /**
     * Propriétés HVAC vendor SAIC (table YFVehicleProperty), zone SEAT — mêmes famille et
     * zone (0x75) que les sièges chauffants et la temp extérieure, qui fonctionnent déjà.
     * Le type se lit dans l'ID : 0x..6..=FLOAT, 0x..2..=BOOLEAN, sinon INT32.
     */
    private val CLIMATE_PROPS: List<Pair<String, Int>> = listOf(
        "POWER_ON"        to 0x15400510,
        "POWER_STATUS"    to 0x1540250f,
        "AC_ON"           to 0x15402500,
        "AUTO_ON"         to 0x15402502,
        "FAN_SPEED"       to 0x15400500,
        "BLOWER_SPEED"    to 0x1540250d,
        "FAN_DIRECTION"   to 0x15400501,
        "DRVTEMP_SET"     to 0x1560250b,
        "PSGTEMP_SET"     to 0x1560250c,
        "TEMPERATURE_SET" to 0x15600503,
        "RECIRC_ON"       to 0x15200508,
        "AC_LOOP_MODE"    to 0x15402507,
        "ECON_ON"         to 0x15402504,
        "DUAL_ON"         to 0x15402501,
        "DEFROST_FRONT"   to 0x15402515,   // orthographe SAIC : FORNT
        "DEFROST_REAR"    to 0x15402516,
        "SEAT_VENT_DRV"   to 0x15402525,
        "SEAT_VENT_PSG"   to 0x15402526,
        "PM25_CONCENTR"   to 0x15402509,
        "ANION_STATUS"    to 0x15402510
    )

    /** Lecture typée via CarHvacManager. Renvoie la valeur ou la raison de l'échec. LECTURE SEULE. */
    private fun climRead(propId: Int, area: Int): String {
        val hvac = sCarHvacManager ?: return "HVAC absent"
        return try {
            val getter = when (propId and 0x00FF0000) {
                0x00600000 -> "getFloatProperty"
                0x00200000 -> "getBooleanProperty"
                else       -> "getIntProperty"
            }
            val v = hvac.javaClass.getMethod(getter, Int::class.java, Int::class.java)
                .invoke(hvac, propId, area)
            v?.toString() ?: "null"
        } catch (e: Exception) {
            "illisible(${(e.cause ?: e).javaClass.simpleName})"
        }
    }

    /**
     * Sonde du bouton Diagnostic : tente de LIRE les propriétés de climatisation à la zone
     * HVAC (0x75). **Aucune écriture** — on ne fait que constater ce qui répond, firmware par
     * firmware, avant d'envisager un pilotage.
     *
     * Les deux dernières lignes sont des TÉMOINS : des propriétés déjà connues pour marcher
     * (siège chauffant, temp extérieure). Si elles répondent et que les autres non, l'écart
     * est significatif ; si elles échouent aussi, c'est le manager qui n'est pas prêt.
     */
    fun runClimateDiag() {
        AppLogger.i(CLIM_TAG, "── DIAG climatisation (lecture seule) ──")
        AppLogger.i(CLIM_TAG, "HVAC manager=${sCarHvacManager != null} zone=0x${Integer.toHexString(AREA_HVAC)}")
        for ((label, propId) in CLIMATE_PROPS) {
            AppLogger.i(CLIM_TAG, "  ${label.padEnd(16)} 0x${Integer.toHexString(propId)} = ${climRead(propId, AREA_HVAC)}")
        }
        AppLogger.i(CLIM_TAG, "TÉMOIN siègeChauffG(0x15402513) = ${climRead(PROP_SEAT_HEAT_L, AREA_HVAC)}")
        AppLogger.i(CLIM_TAG, "TÉMOIN tempExt(0x15602511)      = ${climRead(PROP_HVAC_TEMP_OUTCAR, AREA_HVAC)}")

        // Voie OEM en parallèle des propriétés : le dégivrage arrière est piloté par un bouton
        // PHYSIQUE sur le véhicule — on veut savoir si le service en reflète l'état malgré tout.
        // (une propriété à 0 ne prouve rien ; si l'OEM renvoie autre chose, l'état est lisible)
        if (sAirCondition != null) {
            AppLogger.i(CLIM_TAG, "OEM dégivrage AV=${acInt("getFrontWindowDefroster") ?: "n/a"} " +
                "AR=${acInt("getBackWindowDefroster") ?: "n/a"}  (−1 = non exposé)")
            AppLogger.i(CLIM_TAG, "OEM power=${acInt("getHvacPowerStatus") ?: "n/a"} ac=${acInt("getAcSwitch") ?: "n/a"} " +
                "auto=${acInt("getAutoStatus") ?: "n/a"} loop=${acInt("getLoopMode") ?: "n/a"}")
        }

        // Voie A9 : lit le CarHvacClient (queryClient 0x7). Sert à mesurer les deux inconnues —
        // l'encodage de la recirculation et les bornes réelles température/ventilation.
        if (isClimateA9()) {
            if (hvacA9() == null) {
                AppLogger.w(CLIM_TAG, "A9: CarHvacClient indisponible (queryClient(0x7) muet)")
            } else {
                AppLogger.i(CLIM_TAG, "A9 power=${a9Get("getHvacPowerStatus")} ac=${a9Get("getACStatus")} " +
                    "auto=${a9Get("getAutoStatus")}")
                AppLogger.i(CLIM_TAG, "A9 drvTemp=${a9Get("getDriverTemperature")} psgTemp=${a9Get("getPassengerTemperature")} " +
                    "fan=${a9Get("getFanSpeed")} fanDir=${a9Get("getFanDirection")}")
                AppLogger.i(CLIM_TAG, "A9 recirc=${a9Get("getAirCirculationStatus")} " +
                    "(à comparer au mode affiché : 0/1/2 = intérieur/extérieur/auto ?)")
                AppLogger.i(CLIM_TAG, "A9 dégivrageAV=${a9Get("getFrontDefrostStatus")} " +
                    "dégivrageAR=${a9Get("getRearDefrostStatus")} tempExt=${a9Get("getOutSideTemperature")}")
            }
        }
    }

    /**
     * Candidats pour la CONSIGNE de température. Les variantes FLOAT (…SET) ont échoué à la
     * zone 0x75 ; la table SAIC propose aussi des variantes ENTIÈRES suffixées "SWA", et la
     * consigne est une propriété par siège → la bonne zone n'est peut-être pas le masque 0x75.
     */
    private val TEMP_SETPOINT_CANDIDATES: List<Pair<String, Int>> = listOf(
        "DRVTEMP_SET"         to 0x1560250b,   // FLOAT
        "PSGTEMP_SET"         to 0x1560250c,   // FLOAT
        "TEMPERATURE_SET"     to 0x15600503,   // FLOAT
        "AC_DRVRTEMSWA"       to 0x1540252e,   // INT  ← variante entière
        "AC_PSNGTEMSWA"       to 0x15402544,   // INT  ← variante entière
        "REAR_TEMPERATURE"    to 0x15602536,
        "SEAT_TEMPERATURE"    to 0x1540050b,
        "TEMPERATURE_CURRENT" to 0x15600502
    )

    private val TEMP_SETPOINT_AREAS = intArrayOf(AREA_HVAC, 0x1, 0x2, 0x4, AREA_GLOBAL, 0)

    /**
     * Chasse à la consigne de température : balaye candidats × zones et ne journalise que les
     * lectures QUI RÉUSSISSENT (sinon le log serait noyé). Lecture seule.
     *
     * Mode d'emploi : noter la consigne réelle affichée par la voiture, puis chercher cette
     * valeur dans les résultats (attention à un éventuel encodage ×10 : 25 °C → 250).
     */
    fun runClimateSetpointHunt() {
        AppLogger.i(CLIM_TAG, "── CHASSE consigne température (lecture seule) ──")
        var hits = 0
        var fails = 0
        for ((label, propId) in TEMP_SETPOINT_CANDIDATES) {
            for (area in TEMP_SETPOINT_AREAS) {
                val r = climRead(propId, area)
                if (r.startsWith("illisible") || r == "null" || r == "HVAC absent") { fails++; continue }
                hits++
                AppLogger.i(CLIM_TAG, "  ✔ ${label.padEnd(20)} 0x${Integer.toHexString(propId)} " +
                    "@0x${Integer.toHexString(area)} = $r")
            }
        }
        AppLogger.i(CLIM_TAG, "  → $hits lecture(s) réussie(s), $fails échec(s)")
        // Voie OEM (AirConditionManager) — déjà bindée par la feature température, old-SDK.
        AppLogger.i(CLIM_TAG, "OEM drvTemp=${acInt("getDrvTemp") ?: "n/a"} psgTemp=${acInt("getPsgTemp") ?: "n/a"} " +
            "min=${acInt("getMinTemp") ?: "n/a"} max=${acInt("getMaxTemp") ?: "n/a"} " +
            "airVol=${acInt("getAirVolumeLevel") ?: "n/a"} acSwitch=${acInt("getAcSwitch") ?: "n/a"}")
        AppLogger.i(CLIM_TAG, "→ repérer la consigne affichée par la voiture (ex. 25, ou 250 si ×10)")
    }

    // ── Voie A9 (SWI69/131/132) : carapi CarHvacClient via queryClient(0x7) ──────
    // Le SDK vehiclesettings est ABSENT sur A9 ; la clim passe par l'adaptateur carapi,
    // exactement comme le power-off (queryClient(0xf)) déjà en place.
    private const val HVAC_SERVICE_CODE  = 0x7
    private const val HVAC_CLIENT_CLASS  = "com.saicmotor.carapi.client.CarHvacClient"

    @Volatile private var sCarHvacA9: Any? = null

    /** Obtient (et mémorise) le CarHvacClient A9. null si indisponible. */
    private fun hvacA9(): Any? {
        sCarHvacA9?.let { return it }
        val cl = sVsm?.javaClass?.classLoader ?: return null
        return try {
            val adapterClass = cl.loadClass(CAR_ADAPTER_CLIENT_CLASS)
            val adapter = adapterClass.getMethod("getInstance", Context::class.java).invoke(null, sAppContext)
            val binder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType)
                .invoke(adapter, HVAC_SERVICE_CODE) as? IBinder ?: return null
            val clientClass = cl.loadClass(HVAC_CLIENT_CLASS)
            clientClass.getConstructor(IBinder::class.java).newInstance(binder).also {
                sCarHvacA9 = it
                AppLogger.i(CLIM_TAG, "A9: CarHvacClient obtenu via queryClient(0x7) ✓")
            }
        } catch (e: Exception) {
            AppLogger.d(CLIM_TAG, "A9: CarHvacClient indisponible : ${(e.cause ?: e).message}")
            null
        }
    }

    /** Lecture sans argument sur le client HVAC A9 (Boolean, Int ou Float selon la méthode). */
    private fun a9Get(name: String): Any? {
        val c = hvacA9() ?: return null
        return try { c.javaClass.getMethod(name).invoke(c) } catch (_: Exception) { null }
    }

    /** Appel sans argument (les `switch…()` : bascules). */
    private fun a9Call(name: String): Boolean {
        val c = hvacA9() ?: return false
        return try { c.javaClass.getMethod(name).invoke(c); true }
        catch (e: Exception) { AppLogger.w(CLIM_TAG, "A9 $name() échec : ${(e.cause ?: e).message}"); false }
    }

    /** Écriture typée (setFanSpeed(Int), setDriverTemperature(Float)). */
    private fun a9Set(name: String, value: Any): Boolean {
        val c = hvacA9() ?: return false
        val type = if (value is Float) Float::class.javaPrimitiveType else Int::class.javaPrimitiveType
        return try { c.javaClass.getMethod(name, type).invoke(c, value); true }
        catch (e: Exception) { AppLogger.w(CLIM_TAG, "A9 $name($value) échec : ${(e.cause ?: e).message}"); false }
    }

    /**
     * Amène une bascule A9 (`switch…()`) jusqu'à l'état voulu — équivalent de [hvacCycleTo],
     * mais l'état se lit par méthode et non par propriété.
     * ⚠️ Bloquant → hors du thread principal.
     */
    private fun a9CycleTo(label: String, getter: String, target: Int, maxSteps: Int, advance: String): Boolean {
        var steps = 0
        while (steps <= maxSteps) {
            val cur = when (val v = a9Get(getter)) {
                is Boolean -> if (v) 1 else 0
                is Int     -> v
                else       -> -1
            }
            if (cur == target) { climLog("A9 $label=$target atteint ($steps avance(s))", true); return true }
            if (cur < 0) { climLog("A9 $label=$target — état illisible", false); return false }
            a9Call(advance)
            steps++
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
        }
        climLog("A9 $label=$target NON atteint", false)
        return false
    }

    /** Écrit une valeur entière sur le manager clim SAIC. false si la méthode échoue. */
    private fun acSet(name: String, value: Int): Boolean {
        val ac = sAirCondition ?: return false
        return try {
            ac.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(ac, value)
            true
        } catch (e: Exception) {
            AppLogger.w(CLIM_TAG, "  $name($value) échec : ${(e.cause ?: e).message}")
            false
        }
    }

    /**
     * Un cycle de test sur une grandeur : lit, écrit une valeur voisine, relit pour vérifier,
     * puis RESTAURE la valeur d'origine et revérifie. Bloquant (attentes) → appeler hors du
     * thread principal.
     */
    private fun climWriteProbe(label: String, getter: String, setter: String, minGetter: String, maxGetter: String) {
        val before = acInt(getter)
        if (before == null || before < 0) {
            AppLogger.w(CLIM_TAG, "$label : lecture initiale impossible ($getter=${before ?: "null"}) → test ignoré")
            return
        }
        val lo = acInt(minGetter)?.takeIf { it >= 0 } ?: 0
        val hi = acInt(maxGetter)?.takeIf { it > lo } ?: (before + 1)
        // Valeur voisine, en restant dans la plage : un écart de 1 suffit à prouver l'écriture.
        val target = if (before < hi) before + 1 else before - 1
        if (target < lo || target > hi) {
            AppLogger.w(CLIM_TAG, "$label : pas de valeur voisine dans la plage $lo..$hi → test ignoré")
            return
        }

        AppLogger.i(CLIM_TAG, "$label : actuel=$before plage=$lo..$hi → tentative $target")
        val written = acSet(setter, target)
        Thread.sleep(800)
        val after = acInt(getter)
        AppLogger.i(CLIM_TAG, "  écriture=$written relecture=$after " +
            if (after == target) "✅ PRISE EN COMPTE" else "❌ non prise")

        // Restauration systématique, même si l'écriture a échoué.
        val restoredOk = acSet(setter, before)
        Thread.sleep(800)
        val restored = acInt(getter)
        AppLogger.i(CLIM_TAG, "  restauration=$restoredOk → $restored " +
            if (restored == before) "✅ état d'origine rétabli" else "⚠️ VÉRIFIER MANUELLEMENT (attendu $before)")
    }

    /**
     * Test d'ÉCRITURE de la climatisation — **réversible**. Modifie brièvement la consigne de
     * température puis la ventilation, vérifie que la voiture prend la valeur, et remet
     * systématiquement l'état d'origine.
     *
     * Confort uniquement : ne touche à aucun réglage de conduite, donc hors périmètre du
     * verrou de vitesse (VehicleWriteGate), conformément à la politique T-904.
     *
     * ⚠️ Bloquant (~3,5 s) → appeler depuis un thread IO, jamais depuis le thread principal.
     */
    fun runClimateWriteTest() {
        AppLogger.i(CLIM_TAG, "── TEST ÉCRITURE climatisation (réversible) ──")
        if (sAirCondition == null) {
            AppLogger.w(CLIM_TAG, "AirConditionManager indisponible → test impossible sur ce firmware")
            return
        }
        climWriteProbe("Consigne conducteur", "getDrvTemp", "setDrvTemp", "getMinTemp", "getMaxTemp")
        climWriteProbe("Ventilation", "getAirVolumeLevel", "setAirVolumeLevel", "getMinAirVolume", "getMaxAirVolume")
        AppLogger.i(CLIM_TAG, "── fin du test — l'état d'origine doit être rétabli ──")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Pilotage climatisation (page Dashboard) — voie OEM AirConditionManager
    //  Les propriétés CarHvacManager ne portent PAS la consigne (0.0 partout) :
    //  tout passe donc par le manager SAIC, seul à exposer lecture ET écriture.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Modes de recirculation. Encodage **mesuré sur véhicule** (SWI133) en changeant le mode
     * depuis l'écran de la voiture et en relisant HVAC_AC_LOOP_MODE :
     *   intérieur → 0, extérieur → 1, auto → 2.
     * ⚠️ HVAC_RECIRC_ON (0x15200508) reste à `false` dans les trois cas : propriété inutilisable.
     */
    object LoopMode {
        const val INNER   = 0   // air recyclé
        const val OUTSIDE = 1   // air extérieur
        const val AUTO    = 2
    }

    /** Mode de recirculation — seule source fiable (l'OEM getLoopMode n'est pas vérifié). */
    private const val PROP_HVAC_LOOP_MODE = 0x15402507
    /** État A/C — encodage mesuré sur véhicule : 1 = allumé, 0 = éteint. */
    private const val PROP_HVAC_AC_ON = 0x15402500

    /**
     * Amène une commande **cyclique** du HVAC SAIC jusqu'à un état voulu.
     *
     * Plusieurs commandes de ce service ignorent leur argument et se contentent d'avancer d'un
     * cran (constaté pour la recirculation, et l'A9 le nomme explicitement `switch…()`). La
     * seule façon fiable d'atteindre un état précis est donc : lire, comparer, avancer, relire.
     *
     * [maxSteps] borne la boucle au nombre d'états du cycle : au-delà, la commande n'agit pas
     * sur ce firmware et on abandonne en le journalisant plutôt que de tourner en rond.
     * ⚠️ Bloquant (~400 ms par cran) → hors du thread principal.
     */
    private fun hvacCycleTo(label: String, propId: Int, target: Int, maxSteps: Int, advance: () -> Unit): Boolean {
        var steps = 0
        while (steps <= maxSteps) {
            val current = getIntPropertyHvac(propId, AREA_HVAC)
            if (current == target) {
                climLog("$label=$target atteint ($steps avance(s))", true)
                return true
            }
            if (current < 0) {
                climLog("$label=$target — état courant illisible", false)
                return false
            }
            advance()
            steps++
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
        }
        climLog("$label=$target NON atteint (lu=${getIntPropertyHvac(propId, AREA_HVAC)})", false)
        return false
    }

    /**
     * Instantané complet de la climatisation, bornes incluses — lu en une passe pour éviter
     * une dizaine d'allers-retours binder à chaque rafraîchissement.
     * Un champ à null = non lisible sur ce firmware ; l'UI grise le contrôle correspondant.
     */
    data class ClimateState(
        val powerOn: Boolean?,
        val tempC: Int?,
        val tempMin: Int,
        val tempMax: Int,
        val fanLevel: Int?,
        val fanMin: Int,
        val fanMax: Int,
        val acOn: Boolean?,
        val autoOn: Boolean?,
        val loopMode: Int?,
        val defrostFront: Boolean?,
        val defrostRear: Boolean?
    )

    /**
     * Vrai si le firmware expose le SDK clim SAIC (old-SDK : SWI133/68/165 ; absent sur A9).
     *
     * ⚠️ Critère volontairement **déterministe** : surtout PAS `sAirCondition != null`, qui
     * dépend d'une liaison asynchrone. L'adapter du ViewPager n'appelle getItemCount() qu'une
     * fois, à sa création : si la liaison n'était pas encore faite, la page disparaissait
     * définitivement. Ici la réponse est connue dès le démarrage et ne change jamais.
     * Si la liaison n'est pas encore prête, la page s'affiche avec ses contrôles grisés, puis
     * se remplit au premier rafraîchissement réussi.
     */
    fun hasClimateControl(): Boolean = FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN

    /** Vrai si ce firmware passe par la voie carapi (A9) plutôt que par le SDK vehiclesettings. */
    private fun isClimateA9(): Boolean {
        val gen = FirmwareInfo.getGeneration()
        return gen == FirmwareInfo.Gen.SWI69 ||
               gen == FirmwareInfo.Gen.SWI131 ||
               gen == FirmwareInfo.Gen.SWI132
    }

    /**
     * Lecture complète de l'état clim. null si le service n'est pas (encore) disponible —
     * l'UI grise alors ses contrôles. La liaison est retentée à chaque appel : elle peut
     * n'aboutir qu'après la création de la page (bind asynchrone), auquel cas le prochain
     * rafraîchissement périodique remplit l'écran tout seul.
     */
    fun getClimateState(): ClimateState? {
        if (isClimateA9()) return getClimateStateA9()
        if (sAirCondition == null) sAppContext?.let { initAirCondition(it) }
        if (sAirCondition == null) return null
        // Bornes lues sur la voiture (jamais codées en dur) ; repli sur des valeurs sûres.
        val tMin = acInt("getMinTemp")?.takeIf { it in 0..50 } ?: 16
        val tMax = acInt("getMaxTemp")?.takeIf { it > tMin } ?: 32
        val fMin = acInt("getMinAirVolume")?.takeIf { it >= 0 } ?: 1
        val fMax = acInt("getMaxAirVolume")?.takeIf { it > fMin } ?: 10
        return ClimateState(
            powerOn      = acInt("getHvacPowerStatus")?.takeIf { it >= 0 }?.let { it == 1 },
            tempC        = acInt("getDrvTemp")?.takeIf { it in tMin..tMax },
            tempMin      = tMin,
            tempMax      = tMax,
            fanLevel     = acInt("getAirVolumeLevel")?.takeIf { it >= 0 },
            fanMin       = fMin,
            fanMax       = fMax,
            acOn         = acInt("getAcSwitch")?.takeIf { it >= 0 }?.let { it == 1 },
            autoOn       = acInt("getAutoStatus")?.takeIf { it >= 0 }?.let { it == 1 },
            // Lu via la PROPRIÉTÉ (0/1/2 mesurés sur véhicule), pas via l'OEM getLoopMode.
            loopMode     = getIntPropertyHvac(PROP_HVAC_LOOP_MODE, AREA_HVAC).takeIf { it >= 0 },
            defrostFront = acInt("getFrontWindowDefroster")?.takeIf { it >= 0 }?.let { it == 1 },
            defrostRear  = acInt("getBackWindowDefroster")?.takeIf { it >= 0 }?.let { it == 1 }
        )
    }

    /**
     * État clim sur A9, via CarHvacClient.
     *
     * ⚠️ Les bornes sont ÉCRITES EN DUR, contrairement à l'ancien SDK qui les demande au
     * véhicule : `ICarHvacService` n'expose aucun `getMinTemp` / `getMaxTemp` / niveau de
     * ventilation maximal — vérifié dans le service décompilé. Il n'y a donc rien à lire.
     *
     * Les valeurs ci-dessous ne sont plus des estimations prudentes : elles ont été MESURÉES sur
     * SWI132 (rapports de diagnostic du 2026-08-25, réglages poussés aux extrêmes) puis
     * recoupées avec les constantes du launcher d'origine — variante EH32 :
     * `AUTO_TEMP_MIN_LEVEL=0x12` (18), `AUTO_TEMP_MAX_LEVEL=0x20` (32),
     * `AUTO_FANS_MIN_LEVEL=1`, `AUTO_FANS_MAX_LEVEL=0xb` (11).
     *
     * L'écart entre 18–32 (le launcher) et 17–33 (la mesure) n'est pas une contradiction : 18–32
     * est la plage NUMÉRIQUE, tandis que 17 et 33 sont les positions LO et HI qui l'encadrent.
     * On garde donc 17–33, ce que le véhicule accepte réellement.
     *
     * ⚠️ Le launcher connaît une seconde variante (ventilation 1–8, température 16–28). Si un
     * jour un A9 refuse ces bornes, c'est la première piste — et il faudra alors trouver comment
     * le véhicule déclare sa variante, le SDK ne le disant pas.
     *
     * Reste supposé : l'encodage de `getAirCirculationStatus()` — même convention que l'old-SDK
     * (0=intérieur, 1=extérieur, 2=auto), à confirmer par la sonde Diagnostic.
     */
    private fun getClimateStateA9(): ClimateState? {
        if (hvacA9() == null) return null
        val temp = (a9Get("getDriverTemperature") as? Float)?.takeIf { !it.isNaN() && it > 0f }?.toInt()
        return ClimateState(
            powerOn      = a9Get("getHvacPowerStatus") as? Boolean,
            tempC        = temp,
            tempMin      = 17,        // LO, mesuré
            tempMax      = 33,        // HI, mesuré
            fanLevel     = (a9Get("getFanSpeed") as? Int)?.takeIf { it >= 0 },
            fanMin       = 1,         // 0 = éteint, ce qui relève de l'interrupteur, pas du cran
            fanMax       = 11,        // mesuré, et identique à AUTO_FANS_MAX_LEVEL du launcher
            acOn         = a9Get("getACStatus") as? Boolean,
            autoOn       = a9Get("getAutoStatus") as? Boolean,
            loopMode     = (a9Get("getAirCirculationStatus") as? Int)?.takeIf { it >= 0 },
            defrostFront = a9Get("getFrontDefrostStatus") as? Boolean,
            defrostRear  = a9Get("getRearDefrostStatus") as? Boolean
        )
    }

    /** Appelle une méthode sans argument du manager clim (open…/close…). */
    private fun acCall(name: String): Boolean {
        val ac = sAirCondition ?: return false
        return try {
            ac.javaClass.getMethod(name).invoke(ac); true
        } catch (e: Exception) {
            AppLogger.w(CLIM_TAG, "  $name() échec : ${(e.cause ?: e).message}"); false
        }
    }

    // ── Écritures (confort : hors périmètre du verrou de vitesse, cf. T-904) ──
    // Chaque écriture est journalisée : c'est la seule trace exploitable quand un réglage
    // « ne prend pas » sur un firmware donné (le service peut acquiescer sans rien faire).
    private fun climLog(what: String, ok: Boolean) = AppLogger.i(CLIM_TAG, "SET $what → $ok")

    fun setClimatePower(on: Boolean): Boolean =
        if (isClimateA9())
            a9CycleTo("power", "getHvacPowerStatus", if (on) 1 else 0, 2, "switchHvacPowerStatus")
        else
            acCall(if (on) "openHvacPower" else "closeHvacPower").also { climLog("power=$on", it) }

    /** Consigne : Int sur old-SDK, **Float** sur A9 (setDriverTemperature(F)). */
    fun setClimateTemp(celsius: Int): Boolean =
        if (isClimateA9())
            a9Set("setDriverTemperature", celsius.toFloat()).also { climLog("A9 temp=$celsius", it) }
        else
            acSet("setDrvTemp", celsius).also { climLog("temp=$celsius", it) }

    fun setClimateFan(level: Int): Boolean =
        if (isClimateA9())
            a9Set("setFanSpeed", level).also { climLog("A9 fan=$level", it) }
        else
            acSet("setAirVolumeLevel", level).also { climLog("fan=$level", it) }

    /**
     * A/C — bascule, comme la recirculation. L'encodage de lecture (1=ON, 0=OFF) a été mesuré
     * sur véhicule ; envoyer `setAcStatus(0)` restait sans effet, ce qui trahit un argument
     * ignoré. On avance donc jusqu'à l'état voulu. Deux états ⇒ une bascule suffit.
     */
    fun setClimateAc(on: Boolean): Boolean =
        if (isClimateA9())
            a9CycleTo("ac", "getACStatus", if (on) 1 else 0, 2, "switchACStatus")
        else
            hvacCycleTo("ac", PROP_HVAC_AC_ON, if (on) 1 else 0, maxSteps = 2) {
                acSet("setAcStatus", 1)
            }

    fun setClimateAuto(on: Boolean): Boolean =
        if (isClimateA9())
            a9CycleTo("auto", "getAutoStatus", if (on) 1 else 0, 2, "switchAutoStatus")
        else
            acSet("setAutoStatus", if (on) 1 else 0).also { climLog("auto=$on", it) }

    /**
     * Recirculation — commande **CYCLIQUE**, pas une affectation.
     *
     * Constaté sur véhicule (SWI133) : `setLoopMode(n)` **ignore son argument** et fait avancer
     * d'un cran dans le cycle extérieur → intérieur → auto → extérieur… Les méthodes
     * `openLoopInner/Outside/Auto()` avaient le même défaut (on demandait « intérieur » et la
     * voiture passait en « auto »). Même sémantique que `switchAirCirculationStatus()` sur A9.
     *
     * On procède donc comme les sièges chauffants ([setHvacLevelWithToggle]) : avancer d'un cran
     * puis relire, jusqu'à atteindre la cible. La lecture se fait sur la PROPRIÉTÉ, dont
     * l'encodage a été mesuré (0/1/2) ; l'avance utilise la seule commande observée efficace.
     *
     * Trois modes ⇒ deux avances suffisent depuis n'importe quel état ; au-delà de 3 on
     * abandonne, c'est que la commande n'agit pas sur ce firmware.
     *
     * ⚠️ Bloquant (jusqu'à ~1,2 s) → appeler hors du thread principal.
     */
    fun setClimateLoopMode(target: Int): Boolean {
        if (target !in LoopMode.INNER..LoopMode.AUTO) return false
        // 3 modes ⇒ 2 avances suffisent depuis n'importe quel état.
        return if (isClimateA9())
            a9CycleTo("loopMode", "getAirCirculationStatus", target, 3, "switchAirCirculationStatus")
        else
            hvacCycleTo("loopMode", PROP_HVAC_LOOP_MODE, target, maxSteps = 3) {
                acSet("setLoopMode", 1)   // l'argument est ignoré : avance d'un cran
            }
    }

    fun setClimateDefrostFront(on: Boolean): Boolean =
        if (isClimateA9())
            a9CycleTo("defrostFront", "getFrontDefrostStatus", if (on) 1 else 0, 2, "switchFrontDefrostStatus")
        else
            acCall(if (on) "openFrontWindowDefroster" else "closeFrontWindowDefroster")
                .also { climLog("defrostFront=$on", it) }

    fun setClimateDefrostRear(on: Boolean): Boolean =
        if (isClimateA9())
            a9CycleTo("defrostRear", "getRearDefrostStatus", if (on) 1 else 0, 2, "switchRearDefrostStatus")
        else
            acCall(if (on) "openBackWindowDefroster" else "closeBackWindowDefroster")
                .also { climLog("defrostRear=$on", it) }

    /**
     * Applique un préréglage complet de climatisation (automatisation température).
     *
     * Met d'abord le système **et l'A/C en marche** : régler une consigne sur une clim éteinte
     * ne produit rien de visible, et l'automatisation semblerait ne pas fonctionner.
     *
     * La consigne et la ventilation sont **clampées aux bornes réelles du véhicule** (lues dans
     * l'état), pas aux bornes de saisie de l'UI — un firmware peut accepter 17–33 quand un autre
     * fait 15–31. Les dégivrages ne sont écrits que si leur état est lisible : sinon on
     * n'enverrait qu'une commande à l'aveugle.
     *
     * ⚠️ Bloquant (plusieurs secondes avec les bascules) → appeler depuis un thread IO.
     */
    /**
     * Applique le bloc clim d'un profil — exactement ce qu'il porte, et rien d'autre.
     *
     * Distinct d'[applyClimatePreset], qui force marche et A/C à ON : un préréglage
     * d'automatisation sert toujours à FAIRE fonctionner la clim, alors qu'un profil doit aussi
     * pouvoir l'éteindre. D'où deux entrées plutôt qu'un paramètre de plus, les deux appelants
     * n'ayant pas la même intention.
     *
     * Les `null` valent « ne pas y toucher », comme dans l'automatisation : un profil qui ne se
     * prononce pas sur le dégivrage ne doit pas l'éteindre au passage.
     */
    fun applyProfileClimate(
        power: Boolean,
        ac: Boolean,
        autoMode: Boolean,
        targetTemp: Int,
        fanLevel: Int,
        defrostFront: Boolean?,
        defrostRear: Boolean?,
        loopMode: Int?
    ): Boolean {
        val state = getClimateState() ?: run {
            AppLogger.w(CLIM_TAG, "Profil : état clim illisible → abandon")
            return false
        }
        // Clim éteinte : tout le reste n'aurait aucun sens, et écrire une consigne sur une clim
        // à l'arrêt la rallume sur certains firmwares.
        if (!power) {
            val ok = setClimatePower(false)
            AppLogger.i(CLIM_TAG, "Profil : climatisation ÉTEINTE → ok=$ok")
            return ok
        }
        var ok = setClimatePower(true)
        ok = setClimateAc(ac) && ok
        ok = setClimateTemp(targetTemp.coerceIn(state.tempMin, state.tempMax)) && ok

        // ⚠️ Mode auto et ventilation manuelle s'excluent : régler une vitesse fait sortir du
        // mode auto, et activer le mode auto reprend la main sur la vitesse. Appliquer les deux
        // donnerait un état final décidé par le seul ordre des appels, pas par l'utilisateur.
        if (autoMode) {
            ok = setClimateAuto(true) && ok
        } else {
            ok = setClimateFan(fanLevel.coerceIn(state.fanMin, state.fanMax)) && ok
        }

        // Deux conditions : que le profil se prononce, ET que le véhicule expose le réglage.
        if (defrostFront != null && state.defrostFront != null) {
            ok = setClimateDefrostFront(defrostFront) && ok
        }
        if (defrostRear != null && state.defrostRear != null) {
            ok = setClimateDefrostRear(defrostRear) && ok
        }
        if (loopMode != null) {
            val mode = when (loopMode) {
                0    -> LoopMode.INNER
                1    -> LoopMode.OUTSIDE
                else -> LoopMode.AUTO
            }
            ok = setClimateLoopMode(mode) && ok
        }

        AppLogger.i(CLIM_TAG, "Profil : clim=ON A/C=$ac consigne=$targetTemp " +
            (if (autoMode) "ventilation=AUTO" else "vent=$fanLevel") +
            " dégAV=${defrostFront ?: "inchangé"} dégAR=${defrostRear ?: "inchangé"} " +
            "recyclage=${loopMode ?: "inchangé"} → ok=$ok")
        return ok
    }

    fun applyClimatePreset(
        targetTemp: Int,
        fanLevel: Int,
        defrostFront: Boolean,
        defrostRear: Boolean,
        autoMode: Boolean = false,
        loopMode: Int? = null
    ): Boolean {
        val state = getClimateState() ?: run {
            AppLogger.w(CLIM_TAG, "Préréglage : état clim illisible → abandon")
            return false
        }
        var ok = setClimatePower(true)
        ok = setClimateAc(true) && ok
        ok = setClimateTemp(targetTemp.coerceIn(state.tempMin, state.tempMax)) && ok

        // ⚠️ Mode auto et ventilation manuelle s'excluent : régler une vitesse fait sortir du
        // mode auto, et activer le mode auto reprend la main sur la vitesse. Appliquer les deux
        // donnerait un état final décidé par le seul ordre des appels, pas par l'utilisateur.
        if (autoMode) {
            ok = setClimateAuto(true) && ok
        } else {
            ok = setClimateFan(fanLevel.coerceIn(state.fanMin, state.fanMax)) && ok
        }

        if (state.defrostFront != null) ok = setClimateDefrostFront(defrostFront) && ok
        if (state.defrostRear  != null) ok = setClimateDefrostRear(defrostRear) && ok

        // null = l'utilisateur n'a pas demandé à piloter le recyclage : on n'y touche pas, pour
        // ne pas modifier le comportement des automatisations déjà configurées.
        if (loopMode != null) {
            val mode = when (loopMode) {
                0    -> LoopMode.INNER
                1    -> LoopMode.OUTSIDE
                else -> LoopMode.AUTO
            }
            ok = setClimateLoopMode(mode) && ok
        }

        AppLogger.i(CLIM_TAG, "Préréglage appliqué : consigne=$targetTemp " +
            (if (autoMode) "ventilation=AUTO" else "vent=$fanLevel") +
            " dégAV=$defrostFront dégAR=$defrostRear " +
            "recyclage=${loopMode ?: "inchangé"} → ok=$ok")
        return ok
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
