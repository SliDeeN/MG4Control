package com.mg4.control.automation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RuleEffects
import com.mg4.control.model.RuleEvent
import com.mg4.control.model.RuleGate
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleLogic
import com.mg4.control.model.RuleRun
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.RuleState
import com.mg4.control.model.RuleTrigger
import com.mg4.control.model.StatsTracker
import com.mg4.control.model.TimeWatch
import com.mg4.control.model.TriggerWatch
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.service.MG4ControlService
import com.mg4.control.service.VehicleCommands
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.GarageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Mes automatisations (issue #201, étape 1 du 2026-11-02) — le moteur : lit la voiture, suit les
 * déclencheurs, évalue les conditions, exécute les actions. Les règles pures sont dans
 * [RuleLogic], [TriggerWatch], [RuleGate] et [RuleRun].
 *
 * **Rythme** : un relevé toutes les [TICK_MS], seulement s'il existe une règle active à
 * déclencheur « devient vrai » ou horaire, et seulement des signaux dont ces règles ont besoin.
 * Les événements (démarrage, porte avant) arrivent sans relevé.
 *
 * **Écritures** : par [VehicleCommands] (mêmes validations et même verrou de conduite que l'API
 * externe), les raccourcis par le chemin des raccourcis avancés du service. Rien en Mode Garage.
 *
 * Fil `mg4-rules` pour les déclencheurs ; chaque exécution est une coroutine (une attente ne
 * bloque ni le fil ni les autres règles). Journal : [TAG].
 */
object CustomRules {

    const val TAG = "MG4_RULES"

    private const val TICK_MS = 2_000L
    /** Le démarrage de l'écran attend que la voiture ait monté ses services (comme les sons). */
    private const val APP_START_DELAY_MS = 4_000L

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-rules").also { it.start() }.looper) }
    private val principal = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var app: Context? = null

    // ── Fil mg4-rules ──
    private var brut: String? = null
    private var regles: List<CustomRule> = emptyList()
    /** Par règle, un suivi par déclencheur (même rang que [CustomRule.triggers]). */
    private var suivis: Map<String, List<Any?>> = emptyMap()
    private var porteAbonnee = false
    private var releve = false
    private val departs = mutableMapOf<String, Long>()

    /** Exécutions en cours, par id de règle (touchées aussi depuis les coroutines). */
    private val enCours = ConcurrentHashMap<String, Job>()

    /** Prévenu sur le fil principal à la fin d'une exécution (liste de l'onglet). */
    @Volatile var surChangement: (() -> Unit)? = null

    /** Démarrage du service : charge les règles, puis l'événement « démarrage de l'écran ». */
    fun start(context: Context) {
        app = context.applicationContext
        fil.post {
            recharger()
            fil.postDelayed({ evenement(RuleEvent.APP_START) }, APP_START_DELAY_MS)
        }
    }

    /** Les règles ont changé (onglet) : relues, suivis remis à zéro. */
    fun refresh(context: Context) {
        app = context.applicationContext
        fil.post { recharger() }
    }

    fun isRunning(id: String): Boolean = enCours[id]?.isActive == true

    /** Bouton Tester : les actions tout de suite, sans déclencheur ni conditions. */
    fun test(context: Context, id: String) {
        app = context.applicationContext
        fil.post {
            recharger()
            val r = regles.firstOrNull { it.id == id } ?: return@post
            if (GarageMode.isOn(context)) {
                AppLogger.i(TAG, "« ${r.name} » : test refusé — Mode Garage")
                return@post
            }
            lancer(r, "test")
        }
    }

    // ── Chargement (fil mg4-rules) ───────────────────────────────────────────

    private fun recharger() {
        val ctx = app ?: return
        val json = CustomRuleStore.rawJson(ctx)
        if (json != brut) {
            brut = json
            regles = CustomRuleStore.decode(json)
            suivis = regles.associate { r ->
                r.id to r.triggers.map {
                    when (it.type) {
                        RuleTrigger.Type.BECOMES -> TriggerWatch()
                        RuleTrigger.Type.TIME -> TimeWatch()
                        else -> null
                    }
                }
            }
            // Une règle supprimée ou coupée n'a plus à finir ce qu'elle faisait.
            val actives = regles.filter { it.enabled }.map { it.id }.toSet()
            enCours.keys.filter { it !in actives }.forEach { enCours.remove(it)?.cancel() }
            AppLogger.i(TAG, "${regles.size} automatisation(s), ${actives.size} active(s)")
        }
        val actives = regles.filter { it.enabled }
        val porte = actives.any { r -> r.triggers.any { it.type == RuleTrigger.Type.EVENT && it.event == RuleEvent.FRONT_DOOR_OPEN } }
        if (porte != porteAbonnee) {
            porteAbonnee = porte
            if (porte) MG4Hardware.addFrontDoorOpenListener(porteOuverte) else MG4Hardware.removeFrontDoorOpenListener(porteOuverte)
        }
        val voulu = actives.any { r -> r.triggers.any { it.type == RuleTrigger.Type.BECOMES || it.type == RuleTrigger.Type.TIME } }
        if (voulu && !releve) {
            releve = true
            fil.post(tick)
        } else if (!voulu && releve) {
            releve = false
            fil.removeCallbacks(tick)
        }
    }

    private val porteOuverte: () -> Unit = { fil.post { evenement(RuleEvent.FRONT_DOOR_OPEN) } }

    // ── Déclencheurs (fil mg4-rules) ─────────────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            if (!releve) return
            runCatching { releverEtComparer() }
                .onFailure { AppLogger.w(TAG, "relevé manqué : ${it.javaClass.simpleName} ${it.message}") }
            fil.postDelayed(this, TICK_MS)
        }
    }

    private fun releverEtComparer() {
        val ctx = app ?: return
        recharger()
        if (GarageMode.isOn(ctx)) return
        val actives = regles.filter { it.enabled }
        val suivies = actives.filter { r -> r.triggers.any { it.type == RuleTrigger.Type.BECOMES || it.type == RuleTrigger.Type.TIME } }
        if (suivies.isEmpty()) return
        val signaux = suivies.flatMap { r -> r.triggers.flatMap { RuleLogic.signals(it) } }.toSet()
        val etat = lire(ctx, signaux, horloge = suivies.any { r -> r.triggers.any { it.type == RuleTrigger.Type.TIME } })
        val maintenant = SystemClock.elapsedRealtime()
        for (r in suivies) {
            val s = suivis[r.id] ?: continue
            r.triggers.forEachIndexed { i, t ->
                val part = when (val w = s.getOrNull(i)) {
                    is TriggerWatch -> w.onValue(RuleLogic.evaluate(t.condition, etat), maintenant, t.forSeconds * 1_000L)
                    is TimeWatch -> t.at?.let { w.onMinute(etat.minuteOfDay, it) } ?: false
                    else -> false
                }
                if (part) declencher(r, "déclencheur ${i + 1}")
            }
        }
    }

    private fun evenement(e: RuleEvent) {
        recharger()
        regles.filter { r -> r.enabled && r.triggers.any { it.type == RuleTrigger.Type.EVENT && it.event == e } }
            .forEach { declencher(it, e.name) }
    }

    private fun declencher(r: CustomRule, origine: String) {
        val ctx = app ?: return
        if (GarageMode.isOn(ctx)) {
            AppLogger.i(TAG, "« ${r.name} » ($origine) : Mode Garage, rien")
            return
        }
        val etat = lire(ctx, RuleLogic.signals(r), RuleLogic.usesClock(r))
        val conditions = RuleLogic.evaluate(r.condition, etat)
        if (conditions != true) {
            AppLogger.i(TAG, "« ${r.name} » ($origine) : conditions ${if (conditions == false) "fausses" else "inconnues"}, rien")
            return
        }
        val maintenant = SystemClock.elapsedRealtime()
        when (RuleGate.decide(r, isRunning(r.id), departs[r.id], maintenant, ReadyWatcher.ready)) {
            RuleGate.Decision.START -> lancer(r, origine)
            RuleGate.Decision.RESTART -> {
                enCours.remove(r.id)?.cancel()
                lancer(r, "$origine, relancée")
            }
            RuleGate.Decision.BUSY -> AppLogger.i(TAG, "« ${r.name} » ($origine) : déjà en cours, ignorée")
            RuleGate.Decision.COOLDOWN -> AppLogger.i(TAG, "« ${r.name} » ($origine) : moins de ${r.cooldownMinutes} min depuis la dernière, ignorée")
            RuleGate.Decision.NOT_READY -> AppLogger.i(TAG, "« ${r.name} » ($origine) : voiture pas en READY, ignorée")
        }
    }

    // ── Exécution ────────────────────────────────────────────────────────────

    /** Sur le fil mg4-rules (départs comptés là) ; les actions, dans une coroutine. */
    private fun lancer(r: CustomRule, origine: String) {
        val ctx = app ?: return
        departs[r.id] = SystemClock.elapsedRealtime()
        AppLogger.i(TAG, "« ${r.name} » ($origine) : ${r.actions.size} action(s)")
        val run = RuleRun(Effets(ctx, r))
        val job = scope.launch {
            try {
                run.run(r.actions)
            } finally {
                val heure = System.currentTimeMillis().takeIf { StatsTracker.clockPlausible(it) }
                CustomRuleStore.setLastRun(ctx, r.id, RuleLastRun(heure, run.done, run.failed))
                AppLogger.i(TAG, "« ${r.name} » terminée : ${run.done} ok, ${run.failed} en échec — ${run.journal.joinToString(" ; ")}")
                principal.post { surChangement?.invoke() }
            }
        }
        enCours[r.id] = job
        job.invokeOnCompletion { enCours.remove(r.id, job) }
    }

    private class Effets(private val ctx: Context, private val r: CustomRule) : RuleEffects {
        override fun state(): RuleState = lire(ctx, RuleLogic.signals(r), RuleLogic.usesClock(r))

        override fun set(key: String, value: String): Boolean {
            if (GarageMode.isOn(ctx)) return false
            return VehicleCommands.set(ctx, key, value, TAG)
        }

        override fun shortcut(name: String): Boolean {
            val sc = ShortcutAction.entries.firstOrNull { it.name == name }
            if (sc == null || sc in INTERDITS || GarageMode.isOn(ctx)) return false
            val i = Intent(ctx, MG4ControlService::class.java).apply {
                action = MG4ControlService.ACTION_ADV_SHORTCUT
                putExtra(MG4ControlService.EXTRA_ADV_ACTION, sc.name)
                putExtra(MG4ControlService.EXTRA_ADV_SLOT, "rule:${r.id}")
            }
            return runCatching { ctx.startForegroundService(i) }
                .onFailure { AppLogger.w(TAG, "raccourci ${sc.name} : relais impossible — ${it.message}") }
                .isSuccess
        }

        override suspend fun profile(id: String): Boolean {
            if (GarageMode.isOn(ctx)) return false
            return suspendCancellableCoroutine { suite ->
                val lance = VehicleCommands.applyProfile(ctx, id, TAG) { ok -> if (suite.isActive) suite.resume(ok) }
                if (!lance && suite.isActive) suite.resume(false)
            }
        }

        override fun notify(text: String) {
            if (text.isBlank()) return
            principal.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
        }

        override suspend fun pause(ms: Long) = delay(ms)
    }

    /**
     * Raccourcis qu'une automatisation ne lance pas : ceux qui attendent un réglage propre à la
     * touche (raccourci perso, profil de la touche) — les profils ont leur propre action.
     */
    private val INTERDITS = setOf(ShortcutAction.NONE, ShortcutAction.CUSTOM, ShortcutAction.APPLY_PROFILE)

    // ── Lectures ─────────────────────────────────────────────────────────────

    private fun lire(ctx: Context, signaux: Set<RuleSignal>, horloge: Boolean): RuleState {
        val valeurs = HashMap<RuleSignal, Any?>()
        for (s in signaux) {
            valeurs[s] = runCatching { lireUn(ctx, s) }
                .onFailure { AppLogger.w(TAG, "lecture ${s.name} : ${it.message}") }
                .getOrNull()
        }
        if (!horloge) return RuleState(valeurs)
        val maintenant = System.currentTimeMillis()
        // Horloge d'usine au réveil (~2019) : ni heure ni jour tant qu'elle n'est pas remise.
        if (!StatsTracker.clockPlausible(maintenant)) return RuleState(valeurs)
        val c = Calendar.getInstance().apply { timeInMillis = maintenant }
        // Calendar : dimanche = 1 … samedi = 7 ; ici lundi = 1 … dimanche = 7.
        val jour = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        return RuleState(valeurs, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), jour)
    }

    private fun lireUn(ctx: Context, s: RuleSignal): Any? = when (s) {
        RuleSignal.READY -> ReadyWatcher.ready
        RuleSignal.GEAR -> RuleSignal.gearText(MG4Hardware.readGearRaw())
        RuleSignal.SPEED -> MG4Hardware.getVehicleSpeedKmh()?.toDouble()
        RuleSignal.SOC -> EnergyReader.soc()?.toDouble()
        RuleSignal.RANGE -> EnergyReader.rangeKm()?.toDouble()
        RuleSignal.CHARGING -> EnergyReader.charging()
        RuleSignal.OUTSIDE_TEMP -> MG4Hardware.getOutsideTempCelsius()?.toDouble()
        RuleSignal.HVAC_ON -> if (MG4Hardware.hasClimateControl()) MG4Hardware.getClimateState()?.powerOn else null
        RuleSignal.BT_DEVICE -> BluetoothProfileManager.getConnectedMacs()
        RuleSignal.PROFILE -> ActiveProfile.id(ctx)
        RuleSignal.DRIVE_MODE -> MG4Hardware.getDriveMode()?.name
    }
}
