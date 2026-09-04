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
import com.mg4.control.automation.AutomationDecision
import com.mg4.control.automation.AutomationSettings
import com.mg4.control.automation.ClimateAutomationDecision
import com.mg4.control.automation.ClimateAutomationSettings
import com.mg4.control.api.ExternalApi
import com.mg4.control.api.ExternalApiReceiver
import com.mg4.control.model.DriveMode
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.RegenCycle
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateNotifier
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode
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

        // Intent action broadcast par le système SAIC pour les touches physiques
        private const val HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report"

        /** Actions dont l'état est LU sur le véhicule à chaque pression (voir executeVehicleAction). */
        private val DIRECT_VEHICLE_ACTIONS = setOf(
            ShortcutAction.ESC_TOGGLE, ShortcutAction.DROWSINESS_TOGGLE,
            ShortcutAction.DROWSINESS_SEN_CYCLE, ShortcutAction.HVAC_TOGGLE,
            ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_TEMP_DOWN,
            ShortcutAction.HVAC_FAN_UP, ShortcutAction.HVAC_FAN_DOWN,
            ShortcutAction.ADAS_CYCLE,
            ShortcutAction.REGEN_CYCLE, ShortcutAction.SEAT_HEAT_LEFT_CYCLE,
            ShortcutAction.SEAT_HEAT_RIGHT_CYCLE, ShortcutAction.STEERING_HEAT_TOGGLE,
            ShortcutAction.DEFROST_FRONT_TOGGLE, ShortcutAction.DEFROST_REAR_TOGGLE,
            ShortcutAction.HVAC_RECIRC_CYCLE,
            ShortcutAction.BRIGHTNESS_UP, ShortcutAction.BRIGHTNESS_DOWN,
            ShortcutAction.MEDIA_NEXT, ShortcutAction.MEDIA_PREVIOUS,
            ShortcutAction.MEDIA_PLAY_PAUSE,
            ShortcutAction.VOLUME_UP, ShortcutAction.VOLUME_DOWN
        )


        /** Pas de luminosité par pression, en % — 10 crans du plancher au maximum. */
        private const val BRIGHTNESS_STEP = 10

        /** Raccourcis avancés (service d'accessibilité) — intent EXPLICITE, jamais exporté. */
        /**
         * Envoyée par les Réglages quand le Mode Garage change, pour que la notification
         * persistante dise la vérité tout de suite. Volontairement traitée avant la routine de
         * démarrage : un simple changement d'interrupteur ne doit pas relancer l'application
         * du profil par défaut.
         */
        const val ACTION_GARAGE_CHANGED = "com.mg4.control.internal.GARAGE_CHANGED"

        const val ACTION_ADV_SHORTCUT = "com.mg4.control.internal.ADV_SHORTCUT"
        const val EXTRA_ADV_ACTION    = "adv_action"
        const val EXTRA_ADV_SLOT      = "adv_slot"

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

        /** Dernière exécution de l'automatisation clim — anti-rebond (démarrage service et
         *  IGNITION_RUN arrivent souvent à quelques secondes d'écart : sans ça, on écraserait
         *  un réglage que l'utilisateur vient de faire à la main entre les deux). */
        @Volatile private var climateAutoLastRunMs = 0L
        private const val CLIMATE_AUTO_DEBOUNCE_MS = 60_000L

    }

    // ── Hardkey receiver ─────────────────────────────────────────────────────

    private var hardkeyReceiver: BroadcastReceiver? = null

    // ── [BT-PROFILES] Receiver ACL Bluetooth ─────────────────────────────────
    private var btAclReceiver: BroadcastReceiver? = null

    // ── Receiver sync thème launcher ─────────────────────────────────────────
    private var skinChangeReceiver: BroadcastReceiver? = null

    // ── Receiver API externe (issue #79) ─────────────────────────────────────
    private var externalApiReceiver: BroadcastReceiver? = null

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
        // AVANT startForeground : la notification annonce le Mode Garage, elle doit donc être
        // construite après que l'ancien réglage a été repris.
        GarageMode.migrateIfNeeded(applicationContext)
        startForeground(NOTIF_ID, buildNotification())
        MG4Hardware.init(applicationContext)
        // ⚠️ Le helper audio A9 n'était lié que par MainActivity. Résultat : le raccourci
        // « Volume + / - » ne faisait rien tant que l'utilisateur n'avait pas ouvert
        // l'application au moins une fois depuis le démarrage — signalé sur SWI131, et
        // parfaitement logique une fois vu d'ici. Le service, lui, démarre au boot.
        MG4Hardware.initAudio(applicationContext)
        registerHardkeyReceiver()
        registerBtAclReceiver()        // [BT-PROFILES]
        registerSkinChangeReceiver()   // [THEME-AUTO]
        registerExternalApiReceiver()  // issue #79
        registerIgnitionListener()
        // Le contact est peut-être déjà mis : le service redémarre aussi après une mise à jour
        // de l'application ou un arrêt système, sans qu'aucun IGNITION_RUN ne suive.
        Handler(Looper.getMainLooper()).postDelayed(
            { tryUpdateNotice("démarrage service") }, 60_000L)
    }

    // ── Mise à jour disponible — popup par-dessus l'infodivertissement ───────

    /**
     * Interroge le dépôt et, s'il y a du neuf, le signale par-dessus l'infodivertissement.
     *
     * Tout ce qui décide de le faire ou non vit dans [UpdateNotifier] (interrupteurs, intervalle
     * de six heures, version déjà proposée) et dans [UpdateOverlay] (verrou de conduite). Ici il
     * ne reste que le câblage des trois actions.
     *
     * Le contexte utilisé est celui de l'APPLICATION : ces appels sont différés de plusieurs
     * dizaines de secondes, et le popup est une fenêtre système qui n'a pas à dépendre de la
     * survie du service.
     */
    private fun tryUpdateNotice(raison: String) {
        val ctx = applicationContext
        if (GarageMode.isOn(ctx)) return
        UpdateNotifier.check(ctx, raison) { info ->
            val actuelle = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }

            UpdateOverlay.show(
                context = ctx,
                info = info,
                versionActuelle = actuelle,
                onInstaller = {
                    // On EMPORTE la release trouvée : sans elle, MainActivity relancerait sa
                    // propre requête réseau et l'utilisateur attendrait devant un écran vide
                    // pour réapprendre ce qu'on vient de lui dire.
                    val intent = UpdateNotifier.putInto(
                        Intent(ctx, MainActivity::class.java), info
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    ctx.startActivity(intent)
                },
                onIgnorer = {
                    // Exactement le même mécanisme que le bouton « Ignorer » du dialogue de
                    // l'application : la version disparaît aussi de la vérification au
                    // lancement. « Ignorer 2.6.7 » veut dire la même chose partout.
                    UpdateChecker.skipVersion(ctx, info.versionName)
                    AppLogger.i(TAG, "MAJ ${info.versionName} ignorée par l'utilisateur")
                },
                onDesactiver = {
                    UpdateNotifier.setEnabled(ctx, false)
                    // Dire OÙ le réactiver : l'utilisateur vient d'éteindre une fonction et
                    // n'a aucune raison de deviner qu'elle a un interrupteur dans les Réglages.
                    Toast.makeText(ctx, R.string.update_overlay_disabled_toast,
                        Toast.LENGTH_LONG).show()
                },
                onAffiche = { UpdateNotifier.marquerProposee(info.versionName) }
            )
        }
    }

    /**
     * Deuxième enregistrement du receiver d'API externe, en plus de celui du Manifest.
     *
     * ⚠️ INDISPENSABLE, ne pas supprimer en croyant faire un doublon : depuis Android 8, un
     * receiver déclaré au Manifest ne reçoit plus les broadcasts **implicites**, et une action
     * personnalisée en est un. KeyMapper ou Tasker enverraient l'intent sans que rien n'arrive,
     * silencieusement. Un receiver enregistré par code n'a pas cette limite.
     *
     * Le Manifest reste utile pour les émetteurs qui ciblent explicitement le paquet
     * (`setPackage`), y compris quand le service n'est pas encore démarré.
     */
    private fun registerExternalApiReceiver() {
        externalApiReceiver = ExternalApiReceiver()
        val filter = IntentFilter().apply {
            addAction(ExternalApi.ACTION_EXECUTE)
            addAction(ExternalApi.ACTION_SET)
            // Les actions directes sont construites depuis la même liste que le Manifest :
            // en ajouter une ne doit se faire qu'à un seul endroit côté code.
            ExternalApi.DIRECT_ACTIONS.forEach { addAction(ExternalApi.ACTION_PREFIX + it) }
        }
        // Émetteurs tiers par nature : l'export est explicite. Le contrôle d'accès est
        // l'interrupteur des Réglages, vérifié dans le receiver ET dans le service.
        ContextCompat.registerReceiver(this, externalApiReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        AppLogger.i(ExternalApi.LOG_TAG, "receiver API externe enregistré (dynamique + Manifest)")
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
        externalApiReceiver?.let { unregisterReceiver(it) } // issue #79
        externalApiReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand")
        // Relais de l'API externe (issue #79) : traité AVANT la routine de démarrage, sinon un
        // simple appel tiers relancerait l'application du profil par défaut à chaque commande.
        if (intent?.action == ACTION_GARAGE_CHANGED) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
            return START_STICKY
        }
        if (handleAdvancedShortcutIntent(intent)) return START_STICKY
        if (handleExternalApiIntent(intent)) return START_STICKY
        tryClimateAutomation("démarrage service")
        scheduleDefaultProfileOnce()
        return START_STICKY
    }

    /**
     * Exécute une action déclenchée par [com.mg4.control.accessibility.KeyCaptureService].
     *
     * Volontairement séparé du relais de l'API externe : celui-ci est verrouillé par
     * l'interrupteur des Réglages, alors qu'un raccourci avancé est déclenché par une touche
     * physique que l'utilisateur a lui-même enregistrée. Passer par la même porte aurait rendu
     * les raccourcis avancés inopérants tant que l'API tierce est désactivée — c'est-à-dire par
     * défaut. L'intent est explicite (Intent(ctx, MG4ControlService)), donc non exposé.
     */
    private fun handleAdvancedShortcutIntent(intent: Intent?): Boolean {
        if (intent?.action != ACTION_ADV_SHORTCUT) return false
        // Le service d'accessibilité n'émet déjà plus rien en Mode Garage ; ce second verrou
        // couvre le cas d'un intent resté en file d'attente au moment de l'activation.
        if (GarageMode.isOn(this)) {
            AppLogger.i(TAG, "raccourci avancé ignoré — Mode Garage")
            return true
        }
        val nom = intent.getStringExtra(EXTRA_ADV_ACTION).orEmpty()
        val sc = ShortcutAction.values().firstOrNull { it.name == nom }
        if (sc == null || sc == ShortcutAction.NONE) {
            AppLogger.w(TAG, "raccourci avancé : action inconnue '$nom'")
            return true
        }
        AppLogger.i(TAG, "raccourci avancé → ${sc.name}")
        executeToggle(sc, intent.getStringExtra(EXTRA_ADV_SLOT).orEmpty())
        return true
    }

    /**
     * Exécute une commande venue d'[ExternalApiReceiver]. Retourne vrai si l'intent en était une.
     *
     * Le contrôle d'accès a déjà eu lieu dans le receiver ; on le revérifie quand même, parce que
     * ce service est aussi démarrable autrement et qu'un interrupteur de sécurité ne doit pas
     * dépendre d'un seul point de passage.
     */
    private fun handleExternalApiIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action != ExternalApi.ACTION_EXECUTE && action != ExternalApi.ACTION_SET) return false
        if (GarageMode.isOn(this)) {
            AppLogger.i(ExternalApi.LOG_TAG, "REFUS $action — Mode Garage")
            return true
        }
        if (!ExternalApi.isEnabled(this)) {
            AppLogger.i(ExternalApi.LOG_TAG, "REFUS $action — API externe désactivée")
            return true
        }

        if (action == ExternalApi.ACTION_EXECUTE) {
            val name = intent.getStringExtra(ExternalApi.EXTRA_ACTION).orEmpty()
            // Filtre appliqué ICI, donc pour les deux formes d'appel : retirer une commande des
            // seules actions directes ne protégerait rien, l'extra `action` y donnant le même accès.
            if (name.uppercase() in ExternalApi.BLOCKED_ACTIONS) {
                AppLogger.w(ExternalApi.LOG_TAG, "REFUS '$name' — commande non exposée à l'API externe")
                return true
            }
            val sc = ShortcutAction.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (sc == null || sc == ShortcutAction.NONE) {
                AppLogger.w(ExternalApi.LOG_TAG, "action inconnue : '$name'")
                return true
            }
            // APPLY_PROFILE lit d'ordinaire l'id stocké pour la touche volant. Depuis l'API, le
            // profil est nommé dans l'intent : on le résout et on l'applique directement.
            if (sc == ShortcutAction.APPLY_PROFILE) {
                applyProfileByName(intent.getStringExtra(ExternalApi.EXTRA_PROFILE))
                return true
            }
            AppLogger.i(ExternalApi.LOG_TAG, "EXECUTE ${sc.name}")
            executeToggle(sc)
            return true
        }

        // ── ACTION_SET ────────────────────────────────────────────────────────
        val key   = intent.getStringExtra(ExternalApi.EXTRA_KEY).orEmpty()
        val value = intent.getStringExtra(ExternalApi.EXTRA_VALUE).orEmpty()
        AppLogger.i(ExternalApi.LOG_TAG, "SET $key=$value")

        CoroutineScope(Dispatchers.IO).launch {
            // NEXT/PREV/TOGGLE sont résolus en valeur concrète AVANT le dispatch : le cycle
            // emprunte ensuite exactement le même chemin d'écriture qu'une consigne explicite,
            // donc les mêmes validations, le même clampage et le même verrou de vitesse.
            val dir = ExternalApi.cycleDirection(value)
            val v   = if (dir == null) value else resolveCycle(key, dir)
            if (v == null) {
                AppLogger.w(ExternalApi.LOG_TAG,
                    "CYCLE $key=$value refusé — clé non cyclable ou état courant illisible")
                return@launch
            }
            if (dir != null) AppLogger.i(ExternalApi.LOG_TAG, "CYCLE $key : $value → $v")

            // Toutes ces écritures passent par MG4Hardware, donc par VehicleWriteGate : refusées
            // en roulant sans que l'API ait à s'en préoccuper.
            val ok = when (key) {
                ExternalApi.SET_DRIVE_MODE -> DriveMode.values()
                    .firstOrNull { it.name.equals(v, true) }
                    ?.let { MG4Hardware.setDriveMode(it); true } ?: false
                ExternalApi.SET_REGEN -> RegenLevel.values()
                    .firstOrNull { it.name.equals(v, true) }
                    ?.let { MG4Hardware.setRegenLevel(it); true } ?: false
                ExternalApi.SET_SEAT_HEAT_LEFT ->
                    v.toIntOrNull()?.takeIf { it in 0..3 }
                        ?.let { MG4Hardware.setSeatHeatLeft(it); true } ?: false
                ExternalApi.SET_SEAT_HEAT_RIGHT ->
                    v.toIntOrNull()?.takeIf { it in 0..3 }
                        ?.let { MG4Hardware.setSeatHeatRight(it); true } ?: false
                ExternalApi.SET_STEERING_HEAT -> {
                    val on = v.equals("true", true) || v == "1"
                    MG4Hardware.setSteeringHeat(on); true
                }
                ExternalApi.SET_PROFILE -> { applyProfileByName(v); true }
                else -> setClimateFromApi(key, v)
            }
            if (!ok) AppLogger.w(ExternalApi.LOG_TAG, "SET refusé — clé ou valeur invalide ($key=$v)")
        }
        return true
    }

    /**
     * Traduit NEXT/PREV en valeur concrète pour [key], en partant de l'état LU sur le véhicule.
     * [dir] vaut +1 ou -1. Retourne null quand la clé n'est pas cyclable ou que son état courant
     * est illisible — et dans ce cas on n'écrit RIEN.
     *
     * ⚠️ Ne jamais « supposer » un point de départ pour sauver l'appel : sur un siège réellement
     * à 3 dont la propriété est muette, partir de 0 ferait descendre la valeur alors que
     * l'utilisateur appuie pour monter. Un refus journalisé est diagnosticable, pas ça.
     *
     * Le cycle boucle (max → min) : c'est indispensable sur un bouton de volant, qui n'a pas de
     * sens « descendre ». Appelée depuis le contexte IO de [handleExternalApiIntent].
     */
    private fun resolveCycle(key: String, dir: Int): String? {
        if (key !in ExternalApi.CYCLABLE_KEYS) return null

        fun step(cur: Int, min: Int, max: Int): String =
            ExternalApi.cycleStep(cur, min, max, dir).toString()
        fun flip(cur: Boolean?): String? = cur?.let { if (it) "0" else "1" }

        when (key) {
            ExternalApi.SET_SEAT_HEAT_LEFT  ->
                return MG4Hardware.getSeatHeatLeftOrNull()?.let { step(it, 0, 3) }
            ExternalApi.SET_SEAT_HEAT_RIGHT ->
                return MG4Hardware.getSeatHeatRightOrNull()?.let { step(it, 0, 3) }
            ExternalApi.SET_STEERING_HEAT   ->
                return flip(MG4Hardware.getSteeringHeatOrNull())
        }

        // Reste : la clim. Un SEUL getClimateState() — c'est une lecture binder, pas un champ.
        if (!MG4Hardware.hasClimateControl()) return null
        val st = MG4Hardware.getClimateState() ?: return null
        return when (key) {
            ExternalApi.SET_HVAC_POWER    -> flip(st.powerOn)
            ExternalApi.SET_HVAC_AC       -> flip(st.acOn)
            ExternalApi.SET_HVAC_AUTO     -> flip(st.autoOn)
            ExternalApi.SET_DEFROST_FRONT -> flip(st.defrostFront)
            ExternalApi.SET_DEFROST_REAR  -> flip(st.defrostRear)
            // Bornes lues sur le véhicule, jamais codées en dur : elles varient d'un firmware
            // à l'autre, et c'est sur elles que le cycle reboucle.
            ExternalApi.SET_HVAC_TEMP     -> st.tempC?.let { step(it, st.tempMin, st.tempMax) }
            ExternalApi.SET_HVAC_FAN      -> st.fanLevel?.let { step(it, st.fanMin, st.fanMax) }
            ExternalApi.SET_HVAC_RECIRC   -> st.loopMode?.let { step(it, 0, 2) }
            else -> null
        }
    }

    /**
     * Clés `SET` de climatisation. Retourne false si [key] n'en est pas une — l'appelant
     * s'en sert pour distinguer « clé inconnue » de « valeur invalide ».
     *
     * ⚠️ Bloquant : les commandes clim SAIC sont des bascules qui avancent d'un cran à la fois,
     * donc plusieurs secondes possibles. Appelée depuis le contexte IO de [handleExternalApiIntent].
     *
     * Consigne et ventilation sont clampées aux **bornes réelles du véhicule**, pas à des
     * valeurs codées en dur : elles diffèrent d'un firmware à l'autre.
     */
    private fun setClimateFromApi(key: String, value: String): Boolean {
        val hvacKeys = setOf(
            ExternalApi.SET_HVAC_POWER, ExternalApi.SET_HVAC_AC, ExternalApi.SET_HVAC_AUTO,
            ExternalApi.SET_HVAC_TEMP, ExternalApi.SET_HVAC_FAN, ExternalApi.SET_HVAC_RECIRC,
            ExternalApi.SET_DEFROST_FRONT, ExternalApi.SET_DEFROST_REAR
        )
        if (key !in hvacKeys) return false
        if (!MG4Hardware.hasClimateControl()) {
            AppLogger.w(ExternalApi.LOG_TAG, "SET $key ignoré — clim non pilotable sur ce firmware")
            return true   // clé connue : ce n'est pas une erreur de syntaxe
        }
        val on = value.equals("true", true) || value == "1"
        return when (key) {
            ExternalApi.SET_HVAC_POWER    -> { MG4Hardware.setClimatePower(on); true }
            ExternalApi.SET_HVAC_AC       -> { MG4Hardware.setClimateAc(on); true }
            ExternalApi.SET_HVAC_AUTO     -> { MG4Hardware.setClimateAuto(on); true }
            ExternalApi.SET_DEFROST_FRONT -> { MG4Hardware.setClimateDefrostFront(on); true }
            ExternalApi.SET_DEFROST_REAR  -> { MG4Hardware.setClimateDefrostRear(on); true }
            ExternalApi.SET_HVAC_RECIRC   -> {
                val mode = when (value.uppercase()) {
                    "INNER", "0"   -> MG4Hardware.LoopMode.INNER
                    "OUTSIDE", "1" -> MG4Hardware.LoopMode.OUTSIDE
                    "AUTO", "2"    -> MG4Hardware.LoopMode.AUTO
                    else           -> return true.also {
                        AppLogger.w(ExternalApi.LOG_TAG, "SET $key : valeur invalide '$value'")
                    }
                }
                MG4Hardware.setClimateLoopMode(mode); true
            }
            ExternalApi.SET_HVAC_TEMP, ExternalApi.SET_HVAC_FAN -> {
                val n = value.toIntOrNull() ?: return true.also {
                    AppLogger.w(ExternalApi.LOG_TAG, "SET $key : valeur non numérique '$value'")
                }
                val state = MG4Hardware.getClimateState() ?: return true.also {
                    AppLogger.w(ExternalApi.LOG_TAG, "SET $key : état clim illisible")
                }
                if (key == ExternalApi.SET_HVAC_TEMP)
                    MG4Hardware.setClimateTemp(n.coerceIn(state.tempMin, state.tempMax))
                else
                    MG4Hardware.setClimateFan(n.coerceIn(state.fanMin, state.fanMax))
                true
            }
            else -> false
        }
    }

    /** Applique un profil désigné par son NOM (insensible à la casse) ou son id. */
    private fun applyProfileByName(nameOrId: String?) {
        val wanted = nameOrId?.trim().orEmpty()
        if (wanted.isEmpty()) {
            AppLogger.w(ExternalApi.LOG_TAG, "APPLY_PROFILE sans nom de profil")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val pm = ProfileManager(applicationContext)
            val profile = pm.getById(wanted)
                ?: pm.getAll().firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            if (profile == null) {
                AppLogger.w(ExternalApi.LOG_TAG, "profil introuvable : '$wanted'")
                return@launch
            }
            AppLogger.i(ExternalApi.LOG_TAG, "application du profil '${profile.name}'")
            ProfileApplier.apply(profile, autoStart = true) { ok ->
                AppLogger.i(ExternalApi.LOG_TAG, "profil '${profile.name}' — ok=$ok")
            }
        }
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
        // Mode Garage : la touche repart au launcher, exactement comme si les raccourcis
        // n'avaient jamais été configurés.
        if (GarageMode.isOn(this)) return

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

    /**
     * Actions à état lu sur le véhicule.
     *
     * ⚠️ Bloquant (lectures binder, et ~1 s pour l'ESC qui confirme son état avant d'agir) :
     * à n'appeler que depuis un contexte IO.
     */
    /**
     * Index ADAS courant — 0=Off, 1=Lim. manuel, 2=Lim. auto, 3=ACC, 4=ICA/TJA — ou `null` si le
     * véhicule ne répond pas. Mêmes indices et même conversion que l'écran ADAS, sans quoi le
     * raccourci et l'écran ne parleraient pas de la même chose.
     */
    private fun adasIndexCourant(): Int? {
        if (!FirmwareInfo.isVsmBased()) {
            // SWI133 : l'index EST la valeur de la propriété VPM.
            val v = MG4Hardware.getMixedIntelligentDrive()
            return if (v < 0) null else v
        }
        val accTja = MG4Hardware.getAccTjaMode()
        if (accTja < 0) return null
        // Le limiteur prime dans l'affichage : il est exclusif du mode ACC/TJA côté véhicule.
        val sas = MG4Hardware.getSpeedLimiterMode()
        return when {
            sas == MG4Hardware.SasMode.MANUEL      -> 1
            sas == MG4Hardware.SasMode.INTELLIGENT -> 2
            accTja == Swi68Mode.ACC                -> 3
            accTja == Swi68Mode.TJA                -> 4
            else                                   -> 0
        }
    }

    /**
     * Écrit un index ADAS. Sur VSM, mode ACC/TJA et limiteur sont deux réglages EXCLUSIFS : il
     * faut poser les deux à chaque fois, sinon l'ancien resterait actif à côté du nouveau.
     */
    private fun ecrireModeAdas(index: Int) {
        if (!FirmwareInfo.isVsmBased()) {
            MG4Hardware.setMixedIntelligentDrive(index)
            return
        }
        when (index) {
            1 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.MANUEL);      MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            2 -> { MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.INTELLIGENT); MG4Hardware.setAccTjaMode(Swi68Mode.OFF) }
            3 -> { MG4Hardware.setAccTjaMode(Swi68Mode.ACC); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            4 -> { MG4Hardware.setAccTjaMode(Swi68Mode.TJA); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
            else -> { MG4Hardware.setAccTjaMode(Swi68Mode.OFF); MG4Hardware.setSpeedLimiterMode(MG4Hardware.SasMode.OFF) }
        }
    }

    private fun executeVehicleAction(action: ShortcutAction) {
        when (action) {
            ShortcutAction.ESC_TOGGLE -> {
                val actuel = MG4Hardware.isEscOn()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT ESC — état illisible, aucune action")
                    return
                }
                AppLogger.i(TAG, "SHORTCUT ESC : $actuel → ${!actuel}")
                MG4Hardware.setEsc(!actuel)
            }

            ShortcutAction.DROWSINESS_TOGGLE -> {
                val actuel = MG4Hardware.isDrowsinessOn()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT somnolence — état illisible, aucune action")
                    return
                }
                AppLogger.i(TAG, "SHORTCUT somnolence : $actuel → ${!actuel}")
                MG4Hardware.setDrowsiness(!actuel)
            }

            ShortcutAction.DROWSINESS_SEN_CYCLE -> {
                val actuel = MG4Hardware.getDrowsinessSensitivity()
                if (actuel < 1) {
                    AppLogger.w(TAG, "SHORTCUT sensibilité — état illisible, aucune action")
                    return
                }
                // Réutilise le calcul de bouclage déjà couvert par les tests de l'API externe
                // plutôt que d'en écrire un second qui pourrait diverger.
                val suivant = ExternalApi.cycleStep(actuel, 1, 3, 1)
                AppLogger.i(TAG, "SHORTCUT sensibilité : $actuel → $suivant")
                MG4Hardware.setDrowsinessSensitivity(suivant)
            }

            ShortcutAction.VOLUME_UP, ShortcutAction.VOLUME_DOWN -> {
                val delta = if (action == ShortcutAction.VOLUME_UP) 1 else -1
                AppLogger.i(TAG, "SHORTCUT volume ${if (delta > 0) "+" else "-"}")
                MG4Hardware.mediaVolumeStep(delta)
            }

            ShortcutAction.MEDIA_NEXT, ShortcutAction.MEDIA_PREVIOUS,
            ShortcutAction.MEDIA_PLAY_PAUSE -> {
                // Aucune lecture préalable : contrairement aux autres actions de ce bloc, il n'y
                // a rien à connaître avant d'agir. Même la pause n'en demande pas — la touche
                // PLAY_PAUSE est une bascule que la source résout elle-même. D'où le passage par
                // ce chemin plutôt que par les bascules à état mémorisé, qui se désynchroniseraient
                // dès que l'utilisateur toucherait à la lecture depuis l'écran d'origine.
                AppLogger.i(TAG, "SHORTCUT média : ${action.name}")
                when (action) {
                    ShortcutAction.MEDIA_NEXT     -> MG4Hardware.mediaNext()
                    ShortcutAction.MEDIA_PREVIOUS -> MG4Hardware.mediaPrevious()
                    else                          -> MG4Hardware.mediaPlayPause()
                }
            }

            ShortcutAction.REGEN_CYCLE -> {
                val actuel = MG4Hardware.getRegenLevel()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT régénération — niveau illisible, aucune action")
                    return
                }
                // En mode SNOW le véhicule impose lui-même la régénération minimale et ignore
                // les consignes — l'écran Conduite grise d'ailleurs les boutons. Écrire quand
                // même donnerait un raccourci qui semble en panne une fois sur trois.
                if (MG4Hardware.getDriveMode() == DriveMode.SNOW) {
                    AppLogger.i(TAG, "SHORTCUT régénération ignoré — mode SNOW, le niveau est " +
                        "imposé par le véhicule")
                    return
                }
                // La séquence est celle que l'utilisateur a composée, et à défaut l'ordre
                // d'origine. Elle est relue À CHAQUE APPUI : le réglage vit dans un autre écran,
                // et le service n'est pas redémarré quand on le modifie.
                //
                // L'ordre par défaut vit à côté de l'enum, où se trouve le piège : l'ordre de
                // déclaration n'est pas l'ordre d'usage. Il y est couvert par des tests.
                val ordre   = RegenCycle.order(this)
                val suivant = RegenLevel.nextInCycle(actuel, ordre)
                AppLogger.i(TAG, "SHORTCUT régénération : ${actuel.label} → ${suivant.label} " +
                    "(cycle ${ordre.joinToString("/") { it.label }})")
                MG4Hardware.setRegenLevel(suivant)
            }

            ShortcutAction.ADAS_CYCLE -> {
                val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)
                // Mêmes valeurs par défaut que l'écran de configuration (Off / ACC). Elles y
                // étaient inversées : l'écran montrait A=Off et B=ACC, le service faisait
                // l'inverse tant que l'utilisateur n'avait touché aucun bouton.
                val modeA = prefs.getInt("shortcut_adas_mode_a", 0)
                val modeB = prefs.getInt("shortcut_adas_mode_b", 3)
                if (modeA == modeB) {
                    AppLogger.w(TAG, "SHORTCUT ADAS — les deux modes sont identiques ($modeA), " +
                        "rien à alterner")
                    return
                }
                // Lecture RÉELLE du mode courant, comme pour la clim ou les sièges chauffants.
                // L'état mémorisé qui servait jusqu'ici repartait de zéro à chaque démarrage du
                // service et ignorait l'écran d'origine : deux appuis de suite pouvaient
                // réécrire le même mode, ce qui se voit comme un raccourci sans effet.
                val actuel = adasIndexCourant()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT ADAS — mode illisible, aucune action")
                    return
                }
                // Sur n'importe quel mode qui n'est ni A ni B (l'utilisateur est passé par
                // l'écran d'origine), on entre par A plutôt que de ne rien faire.
                val cible = if (actuel == modeA) modeB else modeA
                AppLogger.i(TAG, "SHORTCUT ADAS : index $actuel → $cible (A=$modeA, B=$modeB)")
                ecrireModeAdas(cible)
            }

            ShortcutAction.SEAT_HEAT_LEFT_CYCLE, ShortcutAction.SEAT_HEAT_RIGHT_CYCLE -> {
                val gauche = action == ShortcutAction.SEAT_HEAT_LEFT_CYCLE
                // Lectures NULLABLES obligatoires : getSeatHeatLeft() rend 0 aussi bien pour
                // « éteint » que pour « propriété muette ». Partir d'un 0 supposé coincerait le
                // cycle à 1, et si le siège est réellement à 3 l'appui ferait DESCENDRE le
                // chauffage — l'inverse de ce que l'utilisateur demande.
                val actuel = if (gauche) MG4Hardware.getSeatHeatLeftOrNull()
                             else        MG4Hardware.getSeatHeatRightOrNull()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT siège chauffant — niveau illisible, aucune action")
                    return
                }
                val suivant = ExternalApi.cycleStep(actuel, 0, 3, 1)
                AppLogger.i(TAG, "SHORTCUT siège chauffant ${if (gauche) "gauche" else "droit"} " +
                    ": $actuel → $suivant")
                if (gauche) MG4Hardware.setSeatHeatLeft(suivant)
                else        MG4Hardware.setSeatHeatRight(suivant)
            }

            ShortcutAction.STEERING_HEAT_TOGGLE -> {
                val actuel = MG4Hardware.getSteeringHeatOrNull()
                if (actuel == null) {
                    AppLogger.w(TAG, "SHORTCUT volant chauffant — état illisible, aucune action")
                    return
                }
                AppLogger.i(TAG, "SHORTCUT volant chauffant : $actuel → ${!actuel}")
                MG4Hardware.setSteeringHeat(!actuel)
            }

            ShortcutAction.BRIGHTNESS_UP, ShortcutAction.BRIGHTNESS_DOWN -> {
                val actuel = MG4Hardware.getScreenBrightnessPercent()
                if (actuel < 0) {
                    AppLogger.w(TAG, "SHORTCUT luminosité — valeur illisible, aucune action")
                    return
                }
                val pas = if (action == ShortcutAction.BRIGHTNESS_UP) BRIGHTNESS_STEP
                          else -BRIGHTNESS_STEP
                // On CLAMPE, jamais de bouclage : repasser de 100 % au plancher d'une seule
                // pression, de nuit, serait le pire moment pour une surprise. Le plancher vient
                // du matériel — le redéfinir ici le ferait diverger.
                val cible = (actuel + pas).coerceIn(MG4Hardware.BRIGHTNESS_MIN_PERCENT, 100)
                AppLogger.i(TAG, "SHORTCUT luminosité : $actuel% → $cible%")
                if (cible != actuel) MG4Hardware.setScreenBrightnessPercent(cible)
            }

            ShortcutAction.HVAC_TOGGLE, ShortcutAction.HVAC_TEMP_UP,
            ShortcutAction.HVAC_TEMP_DOWN, ShortcutAction.HVAC_FAN_UP,
            ShortcutAction.HVAC_FAN_DOWN, ShortcutAction.DEFROST_FRONT_TOGGLE,
            ShortcutAction.DEFROST_REAR_TOGGLE, ShortcutAction.HVAC_RECIRC_CYCLE -> {
                // Un SEUL getClimateState() : c'est une lecture binder, pas un champ, et les
                // bornes viennent du véhicule — jamais de valeurs codées en dur.
                val etat = MG4Hardware.getClimateState()
                if (etat == null) {
                    AppLogger.w(TAG, "SHORTCUT clim — état illisible, aucune action")
                    return
                }
                when (action) {
                    ShortcutAction.HVAC_TOGGLE -> {
                        val actuel = etat.powerOn
                        if (actuel == null) {
                            AppLogger.w(TAG, "SHORTCUT clim ON/OFF — état illisible")
                            return
                        }
                        AppLogger.i(TAG, "SHORTCUT clim : $actuel → ${!actuel}")
                        MG4Hardware.setClimatePower(!actuel)
                    }
                    ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_TEMP_DOWN -> {
                        val actuel = etat.tempC
                        if (actuel == null) {
                            AppLogger.w(TAG, "SHORTCUT clim température — consigne illisible")
                            return
                        }
                        val pas = if (action == ShortcutAction.HVAC_TEMP_UP) 1 else -1
                        // On CLAMPE, on ne boucle pas : arriver à 32 °C et repartir à 16 en
                        // poussant sur le volant serait une très mauvaise surprise.
                        val cible = (actuel + pas).coerceIn(etat.tempMin, etat.tempMax)
                        AppLogger.i(TAG, "SHORTCUT clim température : $actuel → $cible " +
                            "(bornes ${etat.tempMin}..${etat.tempMax})")
                        if (cible != actuel) MG4Hardware.setClimateTemp(cible)
                    }
                    // ⚠️ Ventilation EXPLICITE, et non plus le `else` du when : depuis que
                    // dégivrages et recirculation partagent cette lecture d'état, un `else`
                    // fourre-tout les traiterait comme des commandes de ventilation.
                    ShortcutAction.HVAC_FAN_UP, ShortcutAction.HVAC_FAN_DOWN -> {
                        val actuel = etat.fanLevel
                        if (actuel == null) {
                            AppLogger.w(TAG, "SHORTCUT clim ventilation — niveau illisible")
                            return
                        }
                        val pas = if (action == ShortcutAction.HVAC_FAN_UP) 1 else -1
                        val cible = (actuel + pas).coerceIn(etat.fanMin, etat.fanMax)
                        AppLogger.i(TAG, "SHORTCUT clim ventilation : $actuel → $cible " +
                            "(bornes ${etat.fanMin}..${etat.fanMax})")
                        if (cible != actuel) MG4Hardware.setClimateFan(cible)
                    }
                    ShortcutAction.DEFROST_FRONT_TOGGLE, ShortcutAction.DEFROST_REAR_TOGGLE -> {
                        val avant  = action == ShortcutAction.DEFROST_FRONT_TOGGLE
                        val actuel = if (avant) etat.defrostFront else etat.defrostRear
                        if (actuel == null) {
                            AppLogger.w(TAG, "SHORTCUT dégivrage — état illisible, aucune action")
                            return
                        }
                        AppLogger.i(TAG, "SHORTCUT dégivrage ${if (avant) "avant" else "arrière"}" +
                            " : $actuel → ${!actuel}")
                        if (avant) MG4Hardware.setClimateDefrostFront(!actuel)
                        else       MG4Hardware.setClimateDefrostRear(!actuel)
                    }
                    ShortcutAction.HVAC_RECIRC_CYCLE -> {
                        val actuel = etat.loopMode
                        if (actuel == null) {
                            AppLogger.w(TAG, "SHORTCUT recirculation — état illisible, aucune action")
                            return
                        }
                        // Ici on BOUCLE, contrairement à la température : trois modes, aucune
                        // borne désagréable à franchir, et sans bouclage le dernier mode serait
                        // un cul-de-sac sur une touche qui ne sait qu'avancer.
                        val suivant = ExternalApi.cycleStep(
                            actuel, MG4Hardware.LoopMode.INNER, MG4Hardware.LoopMode.AUTO, 1)
                        AppLogger.i(TAG, "SHORTCUT recirculation : $actuel → $suivant")
                        MG4Hardware.setClimateLoopMode(suivant)
                    }
                    else -> {}
                }
            }

            else -> AppLogger.w(TAG, "executeVehicleAction : action non gérée ${action.name}")
        }
    }

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

        // Actions qui INTERROGENT le véhicule à chaque pression, au lieu de suivre l'état en
        // mémoire utilisé plus bas. C'est indispensable pour celles-ci : l'utilisateur peut
        // aussi agir sur la clim, l'ESC ou la somnolence depuis l'écran d'origine ou la carte
        // du Dashboard, et un état mémorisé serait déphasé dès la première fois.
        if (action in DIRECT_VEHICLE_ACTIONS) {
            CoroutineScope(Dispatchers.IO).launch { executeVehicleAction(action) }
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

        if (GarageMode.isOn(this)) {
            AppLogger.i(TAG, "Mode Garage — aucun profil au démarrage")
            return
        }

        // Automatisation température (précédence : après choix manuel, avant BT/défaut).
        tryTemperatureAutomation(onFallback = { resolveBtOrDefaultOnSchedule() })
    }

    /** Résolution BT (+ fallback HFP) → défaut au démarrage service (corps historique, inchangé). */
    private fun resolveBtOrDefaultOnSchedule() {
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
                        tryClimateAutomation("IGNITION_RUN")
                    }, 500L)
                    // Bien plus tard que le reste : au coup de contact, la liaison données de
                    // la voiture n'est pas encore montée, et une requête lancée trop tôt
                    // échouerait en consommant le créneau des six heures.
                    Handler(Looper.getMainLooper()).postDelayed(
                        { tryUpdateNotice("IGNITION_RUN") }, 20_000L)
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
     * [BT-PROFILES] Enregistre les receivers ACL Bluetooth pour maintenir
     * la liste des appareils connectés dans BluetoothProfileManager.
     */
    private fun registerBtAclReceiver() {
        btAclReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Un téléphone qui se connecte applique un profil : c'est typiquement ce
                // qu'un technicien verrait arriver sans l'avoir demandé.
                if (GarageMode.isOn(ctx)) return
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
     * Automatisation température — précédence : après choix manuel, avant BT/défaut.
     * Non applicable (désactivée / temp illisible / < seuil / profil absent) => [onFallback].
     * Applicable => application directe (case cochée) ou popup de confirmation
     * (NON/timeout => [onFallback]).
     */
    private fun tryTemperatureAutomation(onFallback: () -> Unit) {
        val ctx = applicationContext
        val cfg = AutomationSettings.read(ctx)
        // Sortie silencieuse = impossible de diagnostiquer à distance : on trace TOUJOURS
        // la config, y compris quand la feature est simplement désactivée.
        if (!cfg.enabled) {
            AppLogger.i(TAG, "Auto temp: DÉSACTIVÉE dans Réglages → fallback BT/défaut")
            onFallback(); return
        }
        val profile = cfg.profileId.takeIf { it.isNotEmpty() }?.let { ProfileManager(ctx).getById(it) }

        MG4Hardware.whenKatman1Ready {
            val temp = MG4Hardware.getOutsideTempCelsius()
            val outcome = AutomationDecision.evaluate(cfg.enabled, temp, cfg.threshold, cfg.direction, profile != null)
            AppLogger.i(TAG, "Auto temp: config → dir=${cfg.direction} seuil=${cfg.threshold}°C " +
                "profil='${profile?.name ?: "AUCUN"}' auto=${cfg.autoExecute} | temp lue=${temp ?: "illisible"} → $outcome")
            if (outcome != AutomationDecision.Outcome.APPLY || profile == null || temp == null) {
                AppLogger.i(TAG, "Auto temp: non applicable → fallback BT/défaut")
                onFallback(); return@whenKatman1Ready
            }
            if (cfg.autoExecute) {
                AppLogger.i(TAG, "Auto temp → application directe '${profile.name}' (temp=$temp dir=${cfg.direction} seuil=${cfg.threshold})")
                ProfileApplier.apply(profile, autoStart = true) { ok -> AppLogger.i(TAG, "Auto temp appliqué — ok=$ok") }
            } else {
                AppLogger.i(TAG, "Auto temp → popup confirmation '${profile.name}'")
                ProfileConfirmOverlay.show(
                    context     = ctx,
                    profile     = profile,
                    threshold   = cfg.threshold,
                    currentTemp = temp,
                    direction   = cfg.direction,
                    onConfirmed = {
                        CoroutineScope(Dispatchers.IO).launch {
                            ProfileApplier.apply(profile, autoStart = true) { ok -> AppLogger.i(TAG, "Auto temp OUI '${profile.name}' — ok=$ok") }
                        }
                    },
                    onDeclined  = { onFallback() }
                )
            }
        }
    }

    /**
     * Automatisation « Déclenchement A/C via la température ».
     *
     * Indépendante des profils : elle a son propre interrupteur, et ne remplace ni ne retarde la
     * chaîne profil — les deux tournent en parallèle. Le Mode Garage, lui, les suspend toutes les
     * deux : c'est bien un comportement autonome, visible de l'extérieur.
     *
     * Anti-rebond [CLIMATE_AUTO_DEBOUNCE_MS] : démarrage service et IGNITION_RUN se suivent de
     * près, et réappliquer écraserait un réglage manuel fait entre les deux.
     */
    private fun tryClimateAutomation(origin: String) {
        val ctx = applicationContext
        if (GarageMode.isOn(ctx)) {
            AppLogger.i(TAG, "Auto A/C ($origin) : Mode Garage — rien n'est appliqué")
            return
        }
        val cfg = ClimateAutomationSettings.read(ctx)
        // Comme pour l'auto température : on trace toujours, même désactivée — sinon un
        // utilisateur qui dit « ça ne marche pas » ne laisse aucune trace exploitable.
        if (!cfg.enabled) {
            AppLogger.i(TAG, "Auto A/C ($origin) : DÉSACTIVÉE dans Automatisation")
            return
        }
        if (!MG4Hardware.hasClimateControl()) {
            AppLogger.i(TAG, "Auto A/C ($origin) : clim non pilotable sur ce firmware")
            return
        }
        // LE PROFIL EST PRIORITAIRE. S'il porte son propre bloc clim, l'automatisation n'a rien
        // à dire : sans cette règle les deux s'écriraient dessus au contact, dans un ordre que
        // rien ne garantit, et le résultat dépendrait de qui finit en dernier.
        //
        // Le profil actif est renseigné DÈS L'ENTRÉE de ProfileApplier.apply(), avant même les
        // écritures véhicule : quand cette vérification a lieu, il désigne bien le profil du
        // cycle en cours.
        val profilActif = ActiveProfile.id(ctx)?.let { ProfileManager(ctx).getById(it) }
        if (profilActif?.hvacEnabled == true) {
            AppLogger.i(TAG, "Auto A/C ($origin) : le profil actif '${profilActif.name}' porte " +
                "sa propre climatisation — priorité au profil")
            return
        }
        val since = System.currentTimeMillis() - climateAutoLastRunMs
        if (climateAutoLastRunMs != 0L && since < CLIMATE_AUTO_DEBOUNCE_MS) {
            AppLogger.i(TAG, "Auto A/C ($origin) : déjà appliquée il y a ${since / 1000}s — skip")
            return
        }

        MG4Hardware.whenKatman1Ready {
            val temp = MG4Hardware.getOutsideTempCelsius()
            val outcome = ClimateAutomationDecision.evaluate(cfg, temp)
            AppLogger.i(TAG, "Auto A/C ($origin) : chaud=${cfg.hot.active}/≥${cfg.hot.threshold}°C " +
                "froid=${cfg.cold.active}/≤${cfg.cold.threshold}°C | temp lue=${temp ?: "illisible"} → $outcome")
            val rule = when (outcome) {
                ClimateAutomationDecision.Outcome.HOT  -> cfg.hot
                ClimateAutomationDecision.Outcome.COLD -> cfg.cold
                ClimateAutomationDecision.Outcome.NONE -> return@whenKatman1Ready
            }
            climateAutoLastRunMs = System.currentTimeMillis()
            // applyClimatePreset enchaîne des bascules (plusieurs secondes) → jamais sur le main thread.
            CoroutineScope(Dispatchers.IO).launch {
                val ok = MG4Hardware.applyClimatePreset(
                    targetTemp   = rule.targetTemp,
                    fanLevel     = rule.fanLevel,
                    defrostFront = rule.defrostFront,
                    defrostRear  = rule.defrostRear,
                    autoMode     = rule.autoMode,
                    loopMode     = rule.loopMode
                )
                AppLogger.i(TAG, "Auto A/C ($origin) : règle $outcome appliquée — ok=$ok")
            }
        }
    }

    /**
     * Applique le profil approprié suite à un événement IGNITION_STATE=RUN.
     * Priorité : choix manuel récent (popup/app) → profil BT associé → profil par défaut.
     */
    private fun applyDefaultProfileOnIgnition() {
        if (GarageMode.isOn(this)) {
            AppLogger.i(TAG, "IGNITION → Mode Garage, aucun profil appliqué")
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

        // Automatisation température (précédence : après choix manuel, avant BT/défaut).
        tryTemperatureAutomation(onFallback = { resolveBtOrDefaultOnIgnition() })
    }

    /** Résolution BT → défaut au passage RUN (corps historique, inchangé). */
    private fun resolveBtOrDefaultOnIgnition() {
        val pm = ProfileManager(applicationContext)
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
        // ⚠️ NE PLUS conditionner l'enregistrement à hasSkinThemeConfig(). Le service démarre sur
        // LOCKED_BOOT_COMPLETED, donc AVANT le déverrouillage et avant que le launcher soit debout :
        // si SKIN_THEME_CONFIG n'est pas encore lisible à cet instant, l'ancienne sonde one-shot
        // concluait « firmware sans skin » et le receiver n'était JAMAIS enregistré de toute la vie
        // du process — le thème auto restait mort jusqu'au prochain redémarrage. Sur un firmware
        // qui n'émet pas ce broadcast, un receiver inutilisé ne coûte rien.
        AppLogger.i(TAG, "[THEME] SKIN_THEME_CONFIG lisible au démarrage=${ThemeHelper.hasSkinThemeConfig(this)}")
        skinChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
                // Défaut "auto" — cohérent avec ThemeHelper et MG4App. L'ancien défaut "dark"
                // faisait sortir le receiver si la clé manquait.
                if (prefs.getString(ThemeHelper.PREF_THEME_MODE, "auto") != "auto") return

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
        // La notification est le seul endroit visible en permanence : sans elle, un Mode
        // Garage oublié se manifesterait par « plus rien ne marche » sans explication.
        // Langue choisie dans l'application, pas celle du système : le service n'a pas de
        // configuration propre, et l'ancien texte était de toute façon en dur.
        val loc = LocaleHelper.applyLocale(this)
        val etat = if (GarageMode.isOn(this)) loc.getString(R.string.notif_garage_mode)
                   else loc.getString(R.string.notif_service_active)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MG4 Control")
            .setContentText(etat)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
