package com.mg4.control.service

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4.control.MainActivity
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import com.mg4.control.util.LocaleHelper
import com.mg4.control.R
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.tasker.TaskerBridgeService
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MG4ControlService : Service() {

    companion object {
        private const val TAG          = "MG4_SVC"
        private const val CHANNEL_ID   = "mg4_control_channel"
        private const val NOTIF_ID     = 1
        private const val PREFS_SHORTCUTS = "mg4_shortcuts"

        /** Destinataire du broadcast d'allumage. Absent = broadcast sans effet, pas d'erreur. */
        private const val TASKER_PACKAGE = "com.mg4.tasker"

        /** Marge laissée à l'application du profil par défaut (HVAC scrute jusqu'à ~7 s). */
        private const val TASKER_NOTIFY_DELAY_MS = 8_000L

        // Intent action broadcast par le système SAIC pour les touches physiques
        private const val HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report"

        /**
         * [T-902] Permission exigée de l'ÉMETTEUR du broadcast hardkey. Déclarée en
         * protectionLevel="signature" dans le Manifest : seule une app signée avec la clé
         * plateforme de la ROM l'obtient, ce qui exclut toute app tierce qui tenterait de
         * forger l'action pour piloter les raccourcis (et donc l'état du véhicule).
         */
        const val HARDKEY_PERMISSION = "com.mg4.control.permission.RECEIVE_HARDKEY"

        // Keycodes des boutons ★ du volant
        private const val KEYCODE_BTN1     = 17    // STAR_LEFT
        private const val KEYCODE_BTN2     = 286   // STAR_RIGHT
        private const val KEYCODE_BTN2_ALT = 18    // alias STAR_RIGHT (certains firmwares)

        /**
         * Flag one-shot : le profil n'est appliqué qu'une seule fois par session de processus.
         * Évite le double-apply quand MainActivity et BootReceiver démarrent le service.
         */
        @Volatile private var profileScheduled = false

    }

    // ── Hardkey receiver ─────────────────────────────────────────────────────

    private var hardkeyReceiver: BroadcastReceiver? = null

    // ── [BT-PROFILES] Receiver ACL Bluetooth ─────────────────────────────────
    private var btAclReceiver: BroadcastReceiver? = null

    // ── Receiver sync thème launcher ─────────────────────────────────────────
    private var skinChangeReceiver: BroadcastReceiver? = null

    // ── Listener de cycle d'allumage (Katman5) ──────────────────────────────
    private var vehicleConditionListener: ((Int) -> Unit)? = null

    // État par slot pour la détection d'appui long
    private val slotLongTriggered = mutableMapOf<String, Boolean>()

    // États des toggles en mémoire — réinitialisés à chaque démarrage du service (= redémarrage voiture)
    // Évite le bug du 1er appui : si on utilise SharedPrefs, l'état persisté peut ne pas correspondre
    // à l'état réel de la voiture après un redémarrage, causant un toggle dans le mauvais sens.
    private val toggleStates = mutableMapOf<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification())
        MG4Hardware.init(applicationContext)
        registerHardkeyReceiver()
        registerBtAclReceiver()        // [BT-PROFILES]
        registerSkinChangeReceiver()   // [THEME-AUTO]
        registerIgnitionListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        vehicleConditionListener?.let { MG4Hardware.unregisterVehicleConditionListener(it) }
        vehicleConditionListener = null
        hardkeyReceiver?.let { unregisterReceiver(it) }
        hardkeyReceiver = null
        btAclReceiver?.let { unregisterReceiver(it) }     // [BT-PROFILES]
        btAclReceiver = null
        skinChangeReceiver?.let { unregisterReceiver(it) } // [THEME-AUTO]
        skinChangeReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand")
        scheduleDefaultProfileOnce()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Enregistrement dynamique du receiver ─────────────────────────────────

    private fun registerHardkeyReceiver() {
        hardkeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                handleHardkeyIntent(intent)
            }
        }
        // L'émetteur doit détenir HARDKEY_PERMISSION (signature) : un broadcast forgé par
        // une app tierce n'atteint jamais le receiver. EXPORTED reste nécessaire, l'émetteur
        // légitime étant une app système externe.
        ContextCompat.registerReceiver(
            this, hardkeyReceiver, IntentFilter(HARDKEY_ACTION),
            HARDKEY_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "HardkeyReceiver enregistré → $HARDKEY_ACTION (permission $HARDKEY_PERMISSION)")
    }

    // ── Traitement d'un event hardkey ────────────────────────────────────────

    private fun handleHardkeyIntent(intent: Intent) {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // Raccourcis désactivés globalement → on laisse le launcher gérer
        if (!prefs.getBoolean("shortcut_enabled", false)) return

        // Lecture du keycode (plusieurs noms d'extra selon le firmware)
        val keycode = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1)
            .takeIf { it >= 0 }
            ?: intent.getIntExtra("keycode", -1).takeIf { it >= 0 }
            ?: intent.getIntExtra("keyCode", -1).takeIf { it >= 0 }
            ?: return

        val isDown = intent.getBooleanExtra("android.intent.extra.hardkey.down", false)
                     || intent.getBooleanExtra("down", false)
        val isLong = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false)
                     || intent.getBooleanExtra("longpress", false)

        AppLogger.i(TAG, "HARDKEY keycode=$keycode down=$isDown long=$isLong")

        val slot = when (keycode) {
            KEYCODE_BTN1                   -> "btn1"
            KEYCODE_BTN2, KEYCODE_BTN2_ALT -> "btn2"
            else -> return
        }

        when {
            isDown && isLong -> {
                slotLongTriggered[slot] = true
                val pressKey = "${slot}_long"
                val action = ShortcutAction.fromId(prefs.getInt("shortcut_$pressKey", 0))
                if (action != ShortcutAction.NONE) executeToggle(action, pressKey)
            }
            isDown -> {
                slotLongTriggered[slot] = false
            }
            else -> {
                if (slotLongTriggered[slot] == true) {
                    slotLongTriggered[slot] = false
                    return
                }
                val pressKey = "${slot}_single"
                val action = ShortcutAction.fromId(prefs.getInt("shortcut_$pressKey", 0))
                if (action != ShortcutAction.NONE) executeToggle(action, pressKey)
            }
        }
    }

    // ── Exécution du toggle ──────────────────────────────────────────────────

    private fun executeToggle(action: ShortcutAction, pressKey: String = "") {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // PROFILE_PICKER : overlay flottant au-dessus du launcher — aucun toggle d'état
        if (action == ShortcutAction.PROFILE_PICKER) {
            Handler(Looper.getMainLooper()).post {
                ProfilePickerOverlay.show(this@MG4ControlService)
            }
            return
        }

        // APPLY_PROFILE : action directe — pas de toggle d'état, chaque pression applique le profil
        if (action == ShortcutAction.APPLY_PROFILE) {
            val profileId = prefs.getString("shortcut_${pressKey}_profile_id", null) ?: return
            CoroutineScope(Dispatchers.IO).launch {
                val profile = ProfileManager(applicationContext).getById(profileId)
                if (profile == null) {
                    prefs.edit().putInt("shortcut_$pressKey", ShortcutAction.NONE.id).apply()
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — profil $profileId introuvable, reset NONE")
                } else {
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — application de '${profile.name}'")
                    ProfileApplier.apply(profile)
                }
            }
            return
        }

        // VEHICLE_POWER_OFF : check P → confirmation (overlay) → extinction. Sinon message "en P".
        if (action == ShortcutAction.VEHICLE_POWER_OFF) {
            showVehiclePowerOffConfirm()
            return
        }

        // Pour tous les autres toggles : état en mémoire (réinitialisé au démarrage du service)
        // Évite le bug du 1er appui causé par un état SharedPrefs désynchronisé après redémarrage.
        val newState = !(toggleStates[action.name] ?: false)
        toggleStates[action.name] = newState

        AppLogger.i(TAG, "SHORTCUT ${action.name} → ${if (newState) "ON/A" else "OFF/B"}")

        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                ShortcutAction.ONE_PEDAL -> {
                    if (newState) {
                        MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL)
                    } else {
                        val fallback = RegenLevel.fromValue(
                            prefs.getInt("shortcut_one_pedal_fallback", RegenLevel.HIGH.value)
                        )
                        MG4Hardware.setRegenLevel(fallback)
                    }
                }
                ShortcutAction.AEB_CYCLE -> {
                    val mode = if (newState)
                        prefs.getInt("shortcut_aeb_mode_a", AebMode.ALARM)
                    else
                        prefs.getInt("shortcut_aeb_mode_b", AebMode.ALARM_BRAKE)
                    MG4Hardware.setAebMode(mode)
                }
                ShortcutAction.SOUND_WARNING    -> MG4Hardware.setSoundWarning(newState)
                ShortcutAction.OVERSPEED_ALARM  -> MG4Hardware.setOverspeedAlarm(newState)
                ShortcutAction.SPEED_LIMIT_TONE -> MG4Hardware.setSpeedLimitTone(newState)
                ShortcutAction.ADAS_CYCLE -> {
                    // Tous les firmwares connus stockent des indices 0-4 (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA)
                    val modeA = prefs.getInt("shortcut_adas_mode_a", 3)
                    val modeB = prefs.getInt("shortcut_adas_mode_b", 0)
                    val mode  = if (newState) modeA else modeB
                    if (FirmwareInfo.isVsmBased()) {
                        // VSM (SWI68/69/131/132/165) : index → mode ACC/TJA (setAccTjaMode)
                        // + limiteur de vitesse (setSpeedLimiterMode), réglages exclusifs.
                        when (mode) {
                            1 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.MANUEL);      MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
                            2 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.INTELLIGENT); MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
                            3 -> { MG4Hardware.setAccTjaMode(Swi68Mode.ACC); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
                            4 -> { MG4Hardware.setAccTjaMode(Swi68Mode.TJA); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
                            else -> { MG4Hardware.setAccTjaMode(Swi68Mode.OFF); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
                        }
                    } else {
                        // SWI133 : VPM direct (l'index est aussi la valeur mixedIntelligentDrive)
                        MG4Hardware.setMixedIntelligentDrive(mode)
                    }
                }
                ShortcutAction.ENERGY_SAVING_TOGGLE -> MG4Hardware.setEnergySavingMode(newState)
                ShortcutAction.TSR_TOGGLE           -> MG4Hardware.setTsrMode(newState)
                ShortcutAction.OPEN_APP -> {
                    val intent = Intent(this@MG4ControlService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
                ShortcutAction.OPEN_CUSTOM_APP -> {
                    val pkg = prefs.getString("shortcut_${pressKey}_custom_app", null)
                    if (pkg != null) {
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Raccourci « Éteindre la voiture » : vérifie d'abord la position P (lecture gear), puis affiche
     * le MÊME dialogue de confirmation que les Réglages, en fenêtre overlay (déclenché depuis le
     * service). Si pas en P → Toast. `vehiclePowerOff()` re-vérifie le P au moment de l'envoi.
     */
    private fun showVehiclePowerOffConfirm() {
        CoroutineScope(Dispatchers.IO).launch {
            val inPark = MG4Hardware.isVehicleInPark()
            Handler(Looper.getMainLooper()).post {
                if (inPark == true) {
                    val themed = ContextThemeWrapper(LocaleHelper.applyLocale(this@MG4ControlService), R.style.Theme_MG4Control)
                    val dialog = AlertDialog.Builder(themed)
                        .setTitle(R.string.vehicle_power_dialog_title)
                        .setMessage(R.string.vehicle_power_dialog_msg)
                        .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                        .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val ok = MG4Hardware.vehiclePowerOff()
                                AppLogger.i(TAG, "SHORTCUT VEHICLE_POWER_OFF confirmé → $ok")
                            }
                        }
                        .create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } else {
                    Toast.makeText(this@MG4ControlService, R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Planifie l'application du profil au démarrage du processus (one-shot).
     * Priorité : profil BT associé → profil par défaut.
     * Si connectedMacs est vide (téléphone connecté avant démarrage du service),
     * une requête HFP async est effectuée en fallback.
     */
    private fun scheduleDefaultProfileOnce() {
        if (profileScheduled) {
            AppLogger.i(TAG, "Profil déjà planifié — skip")
            return
        }
        profileScheduled = true

        val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apply_profile", true)) {
            AppLogger.i(TAG, "auto_apply_profile désactivé — skip")
            return
        }

        val pm = ProfileManager(applicationContext)

        // [BT-PROFILES] Cherche tous les profils BT parmi les appareils déjà connus en mémoire
        val btProfiles = BluetoothProfileManager.getConnectedMacs()
            .mapNotNull { mac -> pm.getProfileForBtDevice(mac) }
            .distinctBy { it.id }

        when {
            btProfiles.size >= 2 -> {
                // Conflit BT : plusieurs appareils ont un profil associé → popup de sélection
                AppLogger.i(TAG, "[BT] ${btProfiles.size} profils BT en conflit — popup de sélection")
                MG4Hardware.whenKatman1Ready {
                    ProfilePickerOverlay.show(
                        context      = applicationContext,
                        profiles     = btProfiles,
                        onAutoDismiss = {
                            // Timeout sans sélection → applique le 1er profil (comportement historique)
                            CoroutineScope(Dispatchers.IO).launch {
                                AppLogger.i(TAG, "[BT] Timeout → fallback profil '${btProfiles[0].name}'")
                                ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                                    AppLogger.i(TAG, "[BT] Fallback '${btProfiles[0].name}' — ok=$ok")
                                }
                            }
                        }
                    )
                }
                return
            }
            btProfiles.size == 1 -> {
                AppLogger.i(TAG, "[BT] Profil BT '${btProfiles[0].name}' trouvé au démarrage — en attente Katman1")
                MG4Hardware.whenKatman1Ready {
                    ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                        AppLogger.i(TAG, "[BT] Profil '${btProfiles[0].name}' appliqué — ok=$ok")
                    }
                }
                return
            }
        }

        // [BT-PROFILES] Fallback : requête HFP async (cas téléphone connecté avant démarrage service)
        BluetoothProfileManager.checkConnectedHfpDevices(applicationContext) { devices ->
            val hfpProfiles = devices.mapNotNull { dev -> pm.getProfileForBtDevice(dev.address) }
                .distinctBy { it.id }

            when {
                hfpProfiles.size >= 2 -> {
                    AppLogger.i(TAG, "[BT-HFP] ${hfpProfiles.size} profils en conflit — popup")
                    MG4Hardware.whenKatman1Ready {
                        ProfilePickerOverlay.show(
                            context       = applicationContext,
                            profiles      = hfpProfiles,
                            onAutoDismiss = {
                                CoroutineScope(Dispatchers.IO).launch {
                                    AppLogger.i(TAG, "[BT-HFP] Timeout → fallback '${hfpProfiles[0].name}'")
                                    ProfileApplier.apply(hfpProfiles[0], autoStart = true) { ok ->
                                        AppLogger.i(TAG, "[BT-HFP] Fallback appliqué — ok=$ok")
                                    }
                                }
                            }
                        )
                    }
                }
                hfpProfiles.size == 1 -> {
                    AppLogger.i(TAG, "[BT-HFP] Profil '${hfpProfiles[0].name}' trouvé via HFP — en attente Katman1")
                    MG4Hardware.whenKatman1Ready {
                        ProfileApplier.apply(hfpProfiles[0], autoStart = true) { ok ->
                            AppLogger.i(TAG, "[BT-HFP] Profil '${hfpProfiles[0].name}' appliqué — ok=$ok")
                        }
                    }
                }
                else -> {
                    // Aucun match BT → profil par défaut
                    val defaultProfile = pm.getDefaultProfile()
                    if (defaultProfile == null) {
                        AppLogger.i(TAG, "Aucun profil par défaut défini — skip")
                        return@checkConnectedHfpDevices
                    }
                    AppLogger.i(TAG, "Profil par défaut '${defaultProfile.name}' — en attente Katman1")
                    MG4Hardware.whenKatman1Ready {
                        AppLogger.i(TAG, "Hardware prêt → application du profil '${defaultProfile.name}'")
                        ProfileApplier.apply(defaultProfile, autoStart = true) { ok ->
                            AppLogger.i(TAG, "Profil '${defaultProfile.name}' appliqué — ok=$ok")
                        }
                    }
                }
            }
        }
    }

    // ── Listener IGNITION_STATE (Katman5) ────────────────────────────────────

    /**
     * Enregistre le listener Katman5 sur les changements d'état d'allumage.
     * À chaque RUN (0x2), applique le profil par défaut.
     */
    private fun registerIgnitionListener() {
        val vcListener: (Int) -> Unit = { state ->
            when (state) {
                MG4Hardware.CarIgnitionItem.RUN -> {
                    AppLogger.i(TAG, "Katman5 IGNITION_RUN → application du profil")
                    Handler(Looper.getMainLooper()).postDelayed({
                        applyDefaultProfileOnIgnition()
                    }, 500L)
                    notifyTaskerOnIgnition()
                }
                MG4Hardware.CarIgnitionItem.OFF -> {
                    // Extinction → on oublie le choix manuel : le prochain cycle repart sur le défaut/BT
                    if (ProfileApplier.lastManualProfileId != null) {
                        AppLogger.i(TAG, "Katman5 IGNITION_OFF → reset du choix manuel")
                        ProfileApplier.lastManualProfileId = null
                    }
                }
            }
        }
        vehicleConditionListener = vcListener
        MG4Hardware.registerVehicleConditionListener(vcListener)
        AppLogger.i(TAG, "Listener Katman5 enregistré")
    }

    /**
     * Prévient MG4Tasker qu'un cycle d'allumage vient de commencer.
     *
     * Le délai n'est pas cosmétique : [applyDefaultProfileOnIgnition] démarre à +500 ms et
     * ses écritures HVAC scrutent l'état jusqu'à ~7 s. Réveiller le tasker plus tôt le
     * ferait écrire pendant qu'un profil est en cours d'application — ces écritures
     * unitaires ne passent pas par le mutex de ProfileApplier et s'entrelaceraient avec
     * les séquences ADAS multi-étapes. On laisse donc le profil se poser d'abord.
     *
     * Broadcast explicite (package ciblé) + permission signature : aucune app tierce ne
     * peut ni le recevoir ni s'y substituer.
     */
    private fun notifyTaskerOnIgnition() {
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(TaskerBridgeService.ACTION_IGNITION_ON).apply {
                setPackage(TASKER_PACKAGE)
            }
            try {
                sendBroadcast(intent, TaskerBridgeService.PERMISSION_BRIDGE)
                AppLogger.i(TAG, "IGNITION → notification MG4Tasker envoyée")
            } catch (e: Exception) {
                AppLogger.w(TAG, "IGNITION → notification MG4Tasker échouée : ${e.message}")
            }
        }, TASKER_NOTIFY_DELAY_MS)
    }

    /**
     * [BT-PROFILES] Enregistre les receivers ACL Bluetooth pour maintenir
     * la liste des appareils connectés dans BluetoothProfileManager.
     */
    private fun registerBtAclReceiver() {
        btAclReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                val mac = device.address ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        BluetoothProfileManager.onDeviceConnected(mac)
                        AppLogger.i(TAG, "[BT] Appareil connecté : $mac")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        BluetoothProfileManager.onDeviceDisconnected(mac)
                        AppLogger.i(TAG, "[BT] Appareil déconnecté : $mac")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Broadcasts système protégés (seul le système peut les émettre) : pas de permission
        // supplémentaire à exiger, mais l'export est rendu explicite.
        ContextCompat.registerReceiver(
            this, btAclReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "[BT] BtAclReceiver enregistré")
    }

    /**
     * Applique le profil approprié suite à un événement IGNITION_STATE=RUN.
     * Priorité : choix manuel récent (popup/app) → profil BT associé → profil par défaut.
     */
    private fun applyDefaultProfileOnIgnition() {
        val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apply_profile", true)) {
            AppLogger.i(TAG, "IGNITION → auto_apply_profile désactivé, skip")
            return
        }

        val pm = ProfileManager(applicationContext)

        // Choix manuel récent (popup volant / app) → prioritaire sur BT et défaut.
        // L'utilisateur a explicitement sélectionné un profil depuis le démarrage : on le respecte.
        val manualId = ProfileApplier.lastManualProfileId
        if (manualId != null) {
            val manualProfile = pm.getById(manualId)
            if (manualProfile != null) {
                AppLogger.i(TAG, "IGNITION → choix manuel respecté : '${manualProfile.name}'")
                MG4Hardware.whenKatman1Ready {
                    ProfileApplier.apply(manualProfile, autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION → profil manuel '${manualProfile.name}' ré-appliqué — ok=$ok")
                    }
                }
                return
            } else {
                // Profil supprimé entre-temps → on oublie le choix et on retombe sur le défaut/BT
                AppLogger.i(TAG, "IGNITION → choix manuel introuvable (id=$manualId), fallback défaut/BT")
                ProfileApplier.lastManualProfileId = null
            }
        }

        // [BT-PROFILES] Cherche tous les profils BT parmi les appareils connectés
        val btProfiles = BluetoothProfileManager.getConnectedMacs()
            .mapNotNull { mac -> pm.getProfileForBtDevice(mac) }
            .distinctBy { it.id }

        when {
            btProfiles.size >= 2 -> {
                AppLogger.i(TAG, "IGNITION [BT] → ${btProfiles.size} profils en conflit — popup")
                MG4Hardware.whenKatman1Ready {
                    ProfilePickerOverlay.show(
                        context       = applicationContext,
                        profiles      = btProfiles,
                        onAutoDismiss = {
                            CoroutineScope(Dispatchers.IO).launch {
                                AppLogger.i(TAG, "IGNITION [BT] Timeout → fallback '${btProfiles[0].name}'")
                                ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                                    AppLogger.i(TAG, "IGNITION [BT] Fallback appliqué — ok=$ok")
                                }
                            }
                        }
                    )
                }
            }
            btProfiles.size == 1 -> {
                AppLogger.i(TAG, "IGNITION [BT] → application du profil '${btProfiles[0].name}'")
                MG4Hardware.whenKatman1Ready {
                    ProfileApplier.apply(btProfiles[0], autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION [BT] → profil '${btProfiles[0].name}' appliqué — ok=$ok")
                    }
                }
            }
            else -> {
                // Aucun match BT → profil par défaut
                val defaultProfile = pm.getDefaultProfile() ?: run {
                    AppLogger.i(TAG, "IGNITION → aucun profil par défaut, skip")
                    return
                }
                AppLogger.i(TAG, "IGNITION → application du profil par défaut '${defaultProfile.name}'")
                MG4Hardware.whenKatman1Ready {
                    ProfileApplier.apply(defaultProfile, autoStart = true) { ok ->
                        AppLogger.i(TAG, "IGNITION → profil '${defaultProfile.name}' appliqué — ok=$ok")
                    }
                }
            }
        }
    }

    // ── Receiver sync thème launcher (SWI69 / SWI131 / SWI132) ─────────────

    /**
     * Écoute le broadcast "com.saicmotor.changeSkin" émis par le launcher MG
     * lorsque l'utilisateur change de thème (sombre ↔ clair).
     * Ne fait rien si le firmware n'expose pas SKIN_THEME_CONFIG ou si
     * l'utilisateur a choisi un thème manuel (mode ≠ "auto").
     */
    private fun registerSkinChangeReceiver() {
        if (!ThemeHelper.hasSkinThemeConfig(this)) {
            // SWI133/68 : MODE_NIGHT_FOLLOW_SYSTEM gère la sync automatiquement
            AppLogger.i(TAG, "[THEME] SKIN_THEME_CONFIG absent — FOLLOW_SYSTEM actif, broadcast non requis")
            return
        }
        skinChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
                if (prefs.getString(ThemeHelper.PREF_THEME_MODE, "dark") != "auto") return

                val nightMode = ThemeHelper.getLauncherNightMode(ctx)
                Handler(Looper.getMainLooper()).post {
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
                    ThemeHelper.notifyThemeChanged()
                }
                AppLogger.i(TAG, "[THEME] changeSkin reçu → nightMode=$nightMode")
            }
        }
        // Émis par le launcher SAIC (app externe) : export explicite. N'écrit rien dans le
        // véhicule — un broadcast forgé ne peut que changer le thème de l'app.
        ContextCompat.registerReceiver(
            this, skinChangeReceiver, IntentFilter(ThemeHelper.ACTION_SKIN_CHANGE),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLogger.i(TAG, "[THEME] SkinChangeReceiver enregistré")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MG4 Control", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MG4 Control")
            .setContentText("Service actif")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
