package com.mg4control.repository

import android.content.Context
import android.util.Log
import com.mg4control.ui.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

private const val TAG = "VehicleRepository"

private const val LAUNCHER_PACKAGE = "com.saicmotor.hmi.launcher"
private const val CLASS_PROPERTY_MANAGER =
    "com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager"
private const val IFACE_SERVICE_LISTENER =
    "com.saicmotor.sdk.vehiclesettings.VehicleServiceContract\$IVehicleServiceListener"

class VehicleRepository(private val appContext: Context) {

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var managerInstance: Any?  = null
    private var methodSetInt: Method?  = null
    private var methodGetInt: Method?  = null
    private var methodSetMix: Method?  = null
    private var methodGetMix: Method?  = null

    // Callback déclenché quand le service SAIC est vraiment prêt
    var onServiceConnectedCallback: (() -> Unit)? = null

    fun connect() {
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting) return

        _connectionState.value = ConnectionState.Connecting
        AppLogger.i(TAG, "=== Démarrage connexion SDK SAIC ===")

        try {
            // 1. Contexte + ClassLoader du launcher SAIC
            AppLogger.i(TAG, "createPackageContext($LAUNCHER_PACKAGE)...")
            val launcherCtx = appContext.createPackageContext(
                LAUNCHER_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val cl = launcherCtx.classLoader
            AppLogger.i(TAG, "ClassLoader OK : ${cl.javaClass.name}")

            // 2. Charger la classe manager
            AppLogger.i(TAG, "loadClass($CLASS_PROPERTY_MANAGER)...")
            val managerClass = cl.loadClass(CLASS_PROPERTY_MANAGER)
            AppLogger.i(TAG, "Classe chargée OK")

            // 3. Proxy pour IVehicleServiceListener — intercepte onServiceConnected
            AppLogger.i(TAG, "Création proxy IVehicleServiceListener...")
            val listenerIface = cl.loadClass(IFACE_SERVICE_LISTENER)
            val proxy = Proxy.newProxyInstance(cl, arrayOf(listenerIface)) { _, method, args ->
                AppLogger.d(TAG, "Listener.${method.name}(${args?.joinToString() ?: ""})")
                // Quand le service SAIC est vraiment prêt, on relance un refresh
                if (method.name == "onServiceConnected") {
                    AppLogger.i(TAG, "onServiceConnected reçu — refresh des paramètres...")
                    onServiceConnectedCallback?.invoke()
                }
                null
            }
            AppLogger.i(TAG, "Proxy créé OK")

            // 4. Appeler init(Context, IVehicleServiceListener)
            //    On passe appContext pour que bindService() soit appelé depuis notre processus
            AppLogger.i(TAG, "Recherche méthode init(Context, Listener)...")
            val initMethod = managerClass.declaredMethods.firstOrNull { m ->
                m.name == "init" && m.parameterTypes.size == 2
            } ?: throw Exception("Méthode init() introuvable dans $CLASS_PROPERTY_MANAGER")

            AppLogger.i(TAG, "Appel init(appContext, proxy)...")
            try {
                initMethod.invoke(null, appContext, proxy)
                AppLogger.i(TAG, "init() retourné sans exception")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                AppLogger.w(TAG, "init() InvocationTargetException : ${cause?.javaClass?.name}: ${cause?.message}")
                cause?.stackTrace?.take(8)?.forEach { AppLogger.w(TAG, "  at $it") }
            } catch (e: Exception) {
                AppLogger.w(TAG, "init() exception : ${e.javaClass.name}: ${e.message}")
            }

            // 5. Lire l'instance depuis le champ statique sVehiclePropertyManager
            AppLogger.i(TAG, "Lecture champ statique sVehiclePropertyManager...")
            val field: Field = managerClass.getDeclaredField("sVehiclePropertyManager")
            field.isAccessible = true
            managerInstance = field.get(null)
            AppLogger.i(TAG, "sVehiclePropertyManager = $managerInstance")

            // Lire aussi sIsServiceConnected pour diagnostic
            val connField = managerClass.getDeclaredField("sIsServiceConnected")
            connField.isAccessible = true
            val isConnected = connField.get(null) as? Boolean ?: false
            AppLogger.i(TAG, "sIsServiceConnected = $isConnected")

            if (managerInstance == null) {
                throw Exception(
                    "sVehiclePropertyManager est null après init()\n" +
                    "sIsServiceConnected=$isConnected\n" +
                    "→ Voir les lignes W/ ci-dessus pour la vraie cause"
                )
            }

            // 6. Mettre en cache les méthodes
            methodGetInt = managerClass.getMethod("getIntProperty", Int::class.java)
            methodSetInt = managerClass.getMethod("setIntProperty", Int::class.java, Int::class.java)
            methodGetMix = managerClass.getMethod("getMixProperty", Class::class.java, Int::class.java)
            methodSetMix = managerClass.getMethod("setMixProperty", Class::class.java, Int::class.java, Any::class.java)
            AppLogger.i(TAG, "Méthodes get/set cachées OK")

            _connectionState.value = ConnectionState.Connected
            AppLogger.i(TAG, "✅ Connexion SDK SAIC réussie !")

        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            val msg = "${cause?.javaClass?.simpleName}: ${cause?.message}"
            AppLogger.e(TAG, "❌ Erreur connexion : $msg", e)
            _connectionState.value = ConnectionState.Error(msg)
        }
    }

    fun disconnect() {
        managerInstance = null
        methodSetInt    = null
        methodGetInt    = null
        _connectionState.value = ConnectionState.Disconnected
        AppLogger.i(TAG, "Déconnecté")
    }

    fun getIntProperty(id: Int): Int {
        return try {
            val result = methodGetInt?.invoke(managerInstance, id) as? Int ?: -1
            AppLogger.d(TAG, "get(0x${id.toString(16)}) = $result")
            result
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            AppLogger.e(TAG, "getIntProperty(0x${id.toString(16)}) : ${cause?.javaClass?.simpleName}: ${cause?.message}")
            -1
        }
    }

    fun setIntProperty(id: Int, value: Int): Boolean {
        return try {
            AppLogger.d(TAG, "set(0x${id.toString(16)}, $value)")
            methodSetInt?.invoke(managerInstance, id, value)
            AppLogger.i(TAG, "set(0x${id.toString(16)}, $value) OK")
            true
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            AppLogger.e(TAG, "setIntProperty(0x${id.toString(16)}, $value) : ${cause?.javaClass?.simpleName}: ${cause?.message}")
            false
        }
    }

    // Helpers métier
    fun getDrivingMode()         = getIntProperty(VehiclePropertyIds.DRIVING_MODE)
    fun setDrivingMode(v: Int)   = setIntProperty(VehiclePropertyIds.DRIVING_MODE, v)
    fun getRegenLevel()          = getIntProperty(VehiclePropertyIds.REGENERATIVE_LEVEL)
    fun setRegenLevel(v: Int)    = setIntProperty(VehiclePropertyIds.REGENERATIVE_LEVEL, v)
    fun getOnePedal()            = getIntProperty(VehiclePropertyIds.SIGNAL_PEDAL)
    fun setOnePedal(on: Boolean) = setIntProperty(
        VehiclePropertyIds.SIGNAL_PEDAL,
        if (on) VehiclePropertyIds.SignalPedal.ON else VehiclePropertyIds.SignalPedal.OFF
    )

    fun getOverspeedAlarm()            = getIntProperty(VehiclePropertyIds.OVERSPEED_SOUND_ALARM)
    fun setOverspeedAlarm(on: Boolean) = setIntProperty(
        VehiclePropertyIds.OVERSPEED_SOUND_ALARM,
        if (on) VehiclePropertyIds.AlertSwitch.ON else VehiclePropertyIds.AlertSwitch.OFF
    )

    fun getSpeedLimitChangeTone()            = getIntProperty(VehiclePropertyIds.SPEED_LIMIT_CHANGE_TONE)
    fun setSpeedLimitChangeTone(on: Boolean) = setIntProperty(
        VehiclePropertyIds.SPEED_LIMIT_CHANGE_TONE,
        if (on) VehiclePropertyIds.AlertSwitch.ON else VehiclePropertyIds.AlertSwitch.OFF
    )

    fun getMixInt(id: Int): Int {
        return try {
            // Stratégie 1 : appel direct à getMixIntProperty sur le service AIDL interne
            val managerClass = managerInstance?.javaClass ?: return -1
            val serviceField = managerClass.getDeclaredField("mIVehiclePropertyService")
            serviceField.isAccessible = true
            val service = serviceField.get(managerInstance)
            if (service != null) {
                val getMixIntMethod = service.javaClass.getMethod("getMixIntProperty", Int::class.java)
                val result = getMixIntMethod.invoke(service, id) as? Int ?: -1
                AppLogger.d(TAG, "getMixInt direct(0x${id.toString(16)}) = $result")
                return result
            }

            // Stratégie 2 : via getMixProperty avec cast Number
            val obj = methodGetMix?.invoke(managerInstance, Integer.TYPE, id)
            val result = (obj as? Number)?.toInt() ?: -1
            AppLogger.d(TAG, "getMixInt fallback(0x${id.toString(16)}) = $result")
            result
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            AppLogger.e(TAG, "getMixInt(0x${id.toString(16)}) : ${cause?.javaClass?.simpleName}: ${cause?.message}")
            -1
        }
    }

    fun setMixInt(id: Int, value: Int): Boolean {
        return try {
            // Stratégie 1 : appel direct à setMixIntProperty sur le service AIDL interne
            val managerClass = managerInstance?.javaClass ?: return false
            val serviceField = managerClass.getDeclaredField("mIVehiclePropertyService")
            serviceField.isAccessible = true
            val service = serviceField.get(managerInstance)
            if (service != null) {
                val setMixIntMethod = service.javaClass.getMethod("setMixIntProperty", Int::class.java, Int::class.java)
                setMixIntMethod.invoke(service, id, value)
                AppLogger.i(TAG, "setMixInt direct(0x${id.toString(16)}, $value) OK")
                return true
            }

            // Stratégie 2 : via setMixProperty
            val boxed = Integer.valueOf(value)
            methodSetMix?.invoke(managerInstance, Integer.TYPE, id, boxed)
            AppLogger.i(TAG, "setMixInt fallback(0x${id.toString(16)}, $value) OK")
            true
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            AppLogger.e(TAG, "setMixInt(0x${id.toString(16)}, $value) : ${cause?.javaClass?.simpleName}: ${cause?.message}")
            false
        }
    }

    fun getAdasMode()        = getMixInt(VehiclePropertyIds.MIXED_INTELLIGENT_DRIVE)
    fun setAdasMode(v: Int)  = setMixInt(VehiclePropertyIds.MIXED_INTELLIGENT_DRIVE, v)

    /** Loggue l'état interne du service pour diagnostic */
    fun logMixServiceState() {
        try {
            val managerClass = managerInstance?.javaClass ?: run {
                AppLogger.w(TAG, "managerInstance est null")
                return
            }
            // Lire mIVehiclePropertyService via réflexion
            val serviceField = managerClass.getDeclaredField("mIVehiclePropertyService")
            serviceField.isAccessible = true
            val service = serviceField.get(managerInstance)
            AppLogger.i(TAG, "mIVehiclePropertyService = $service")

            // Lister les méthodes getMixProperty disponibles
            val mixMethods = managerClass.methods.filter { it.name.contains("Mix", ignoreCase = true) }
            AppLogger.i(TAG, "Méthodes Mix disponibles : ${mixMethods.map { "${it.name}(${it.parameterTypes.map{it.simpleName}})" }}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "logMixServiceState erreur : ${e.message}", e)
        }
    }
}

