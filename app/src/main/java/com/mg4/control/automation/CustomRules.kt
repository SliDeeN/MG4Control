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
import com.mg4.control.hardware.ProjectionReader
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.ChargeType
import com.mg4.control.model.ChargeLimit
import com.mg4.control.model.ChangeWatch
import com.mg4.control.model.CustomRule
import com.mg4.control.model.PeriodicWatch
import com.mg4.control.model.RuleAction
import com.mg4.control.model.RuleEffects
import com.mg4.control.model.RuleEvent
import com.mg4.control.model.RuleGate
import com.mg4.control.model.RuleKeys
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleLogic
import com.mg4.control.model.RuleRun
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.RuleSkip
import com.mg4.control.model.RuleState
import com.mg4.control.model.RuleText
import com.mg4.control.model.RuleTrigger
import com.mg4.control.model.StatsTracker
import com.mg4.control.model.SunCalc
import com.mg4.control.model.SunEvent
import com.mg4.control.model.TimeWatch
import com.mg4.control.model.TriggerWatch
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.service.CustomShortcutRunner
import com.mg4.control.service.MG4ControlService
import com.mg4.control.service.ProfileConfirmOverlay
import com.mg4.control.service.VehicleCommands
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.ui.AndroidRuleWords
import com.mg4.control.util.DataUsage
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.GarageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Mes automatisations (issue #201) — le moteur : lit la voiture, suit les déclencheurs, évalue les
 * conditions, exécute les actions. Les règles pures sont dans [RuleLogic], [TriggerWatch],
 * [ChangeWatch], [PeriodicWatch], [RuleGate] et [RuleRun].
 *
 * **Rythme** : un relevé toutes les [TICK_MS], seulement s'il existe une règle active à
 * déclencheur suivi (devient vrai, change, horaire, périodique, soleil), et seulement des signaux
 * dont ces règles ont besoin. Les événements (démarrage, porte avant) et les lancements à la main
 * (bouton, bulle, autre automatisation) arrivent sans relevé.
 *
 * **Écritures** : par [VehicleCommands] et [RuleWrites] (mêmes validations et même verrou de
 * conduite que partout), les raccourcis par le chemin des raccourcis avancés du service. Rien en
 * Mode Garage, rien quand l'interrupteur général de Mes automatisations est coupé.
 *
 * Fil `mg4-rules` pour les déclencheurs ; chaque exécution est une coroutine (une attente ne
 * bloque ni le fil ni les autres règles). Journal : [TAG].
 */
object CustomRules {

    const val TAG = "MG4_RULES"

    private const val TICK_MS = 2_000L
    /** Le démarrage de l'écran attend que la voiture ait monté ses services (comme les sons). */
    private const val APP_START_DELAY_MS = 4_000L
    /** Lancements à la main d'une même règle : au plus [RAFALE_MAX] par [RAFALE_MS] (boucle entre règles). */
    private const val RAFALE_MAX = 5
    private const val RAFALE_MS = 10_000L

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
    private val rafales = mutableMapOf<String, ArrayDeque<Long>>()

    /** Exécutions en cours, par id de règle (touchées aussi depuis les coroutines). */
    private val enCours = ConcurrentHashMap<String, Job>()

    /** Dernier passage en READY, horloge monotone ; `null` hors READY. */
    @Volatile private var readyDepuis: Long? = null

    /** Prévenu sur le fil principal à la fin d'une exécution (liste de l'onglet, éditeur). */
    @Volatile var surChangement: (() -> Unit)? = null

    /**
     * Dernière exécution de chaque règle depuis le démarrage, journal compris — y compris d'une
     * règle essayée dans l'éditeur avant d'être enregistrée, que le stockage ne garde pas.
     */
    private val dernieres = ConcurrentHashMap<String, RuleLastRun>()

    /** La dernière exécution connue : celle de cette session, sinon celle enregistrée. */
    fun lastRun(context: Context, id: String): RuleLastRun? = dernieres[id] ?: CustomRuleStore.lastRuns(context)[id]

    /** Dernier déclenchement refusé (conditions, READY, en cours, anti-rebond, Mode Garage). */
    private val dernieresRefus = ConcurrentHashMap<String, RuleSkip>()

    fun lastSkip(context: Context, id: String): RuleSkip? = dernieresRefus[id] ?: CustomRuleStore.lastSkips(context)[id]

    /** Texte d'un déclencheur, pour l'historique (« déclenchée par … »). */
    private fun par(ctx: Context, t: RuleTrigger): String? = runCatching { RuleText(AndroidRuleWords(ctx)).trigger(t) }.getOrNull()

    /**
     * Déclenchée mais pas exécutée (2026-11-08) : l'historique et la carte le montrent, avec la
     * raison — sinon seul le journal du diagnostic le disait, et la règle semblait ne jamais partir.
     */
    private fun ignorer(ctx: Context, r: CustomRule, by: String?, skip: RuleSkip) {
        val s = skip.copy(atMs = System.currentTimeMillis().takeIf { StatsTracker.clockPlausible(it) }, by = by)
        dernieresRefus[r.id] = s
        CustomRuleStore.addSkip(ctx, r.id, r.name, s)
        principal.post { surChangement?.invoke() }
    }

    /** Démarrage du service : charge les règles, puis l'événement « démarrage de l'écran ». */
    fun start(context: Context) {
        app = context.applicationContext
        ReadyWatcher.add { estReady, _ -> readyDepuis = if (estReady) (readyDepuis ?: SystemClock.elapsedRealtime()) else null }
        fil.post {
            recharger()
            fil.postDelayed({ evenement(RuleEvent.APP_START) }, APP_START_DELAY_MS)
        }
    }

    /** Les règles ont changé (onglet) : relues, suivis remis à zéro. */
    fun refresh(context: Context) {
        app = context.applicationContext
        fil.post { recharger(force = true) }
    }

    fun isRunning(id: String): Boolean = enCours[id]?.isActive == true

    /** Bouton Tester : les actions tout de suite, sans déclencheur ni conditions. */
    fun test(context: Context, id: String) {
        app = context.applicationContext
        fil.post {
            recharger()
            val r = regles.firstOrNull { it.id == id } ?: return@post
            tester(context, r)
        }
    }

    /** Tester depuis l'éditeur : la version affichée, enregistrée ou non. */
    fun test(context: Context, rule: CustomRule) {
        app = context.applicationContext
        fil.post { tester(context, rule) }
    }

    /**
     * Lancement à la main : bouton du volant, bulle, ou action « Lancer une automatisation ». Les
     * conditions de la règle s'appliquent, comme pour tout déclencheur.
     */
    fun runManual(context: Context, id: String?, origine: String, by: String? = null) {
        app = context.applicationContext
        if (id == null) return
        fil.post {
            recharger()
            val r = regles.firstOrNull { it.id == id }
            if (r == null || !r.enabled || !CustomRuleStore.isEngineEnabled(context)) {
                AppLogger.i(TAG, "lancement ($origine) : automatisation $id absente, coupée ou moteur coupé")
                return@post
            }
            val maintenant = SystemClock.elapsedRealtime()
            val rafale = rafales.getOrPut(id) { ArrayDeque() }
            while (rafale.isNotEmpty() && maintenant - rafale.first() > RAFALE_MS) rafale.removeFirst()
            if (rafale.size >= RAFALE_MAX) {
                AppLogger.w(TAG, "« ${r.name} » ($origine) : trop de lancements rapprochés, ignorée")
                return@post
            }
            rafale.addLast(maintenant)
            declencher(r, origine, by ?: context.getString(com.mg4.control.R.string.rule_trg_manual))
        }
    }

    private fun tester(context: Context, r: CustomRule) {
        if (GarageMode.isOn(context)) {
            AppLogger.i(TAG, "« ${r.name} » : test refusé — Mode Garage")
            return
        }
        // Une version en cours d'essai remplace celle qui tournait : on teste ce qu'on voit.
        enCours.remove(r.id)?.cancel()
        lancer(r, "test", compter = false)
    }

    // ── Chargement (fil mg4-rules) ───────────────────────────────────────────

    private val SUIVIS = setOf(RuleTrigger.Type.BECOMES, RuleTrigger.Type.TIME, RuleTrigger.Type.PERIODIC,
        RuleTrigger.Type.CHANGES, RuleTrigger.Type.SUN)

    private fun actives(ctx: Context): List<CustomRule> =
        if (CustomRuleStore.isEngineEnabled(ctx)) regles.filter { it.enabled } else emptyList()

    private fun recharger(force: Boolean = false) {
        val ctx = app ?: return
        val json = CustomRuleStore.rawJson(ctx)
        if (json != brut || force) {
            brut = json
            regles = CustomRuleStore.decode(json)
            val maintenant = SystemClock.elapsedRealtime()
            suivis = regles.associate { r ->
                r.id to r.triggers.map {
                    when (it.type) {
                        RuleTrigger.Type.BECOMES -> TriggerWatch()
                        RuleTrigger.Type.TIME, RuleTrigger.Type.SUN -> TimeWatch()
                        RuleTrigger.Type.PERIODIC -> PeriodicWatch(maintenant)
                        RuleTrigger.Type.CHANGES -> ChangeWatch()
                        else -> null
                    }
                }
            }
            // Une règle supprimée ou coupée n'a plus à finir ce qu'elle faisait.
            val ids = actives(ctx).map { it.id }.toSet()
            enCours.keys.filter { it !in ids }.forEach { enCours.remove(it)?.cancel() }
            AppLogger.i(TAG, "${regles.size} automatisation(s), ${ids.size} active(s)" +
                if (CustomRuleStore.isEngineEnabled(ctx)) "" else " — interrupteur général coupé")
        }
        val actives = actives(ctx)
        val porte = actives.any { r -> r.triggers.any { it.type == RuleTrigger.Type.EVENT && it.event == RuleEvent.FRONT_DOOR_OPEN } }
        if (porte != porteAbonnee) {
            porteAbonnee = porte
            if (porte) MG4Hardware.addFrontDoorOpenListener(porteOuverte) else MG4Hardware.removeFrontDoorOpenListener(porteOuverte)
        }
        val fermee = actives.any { r -> r.triggers.any { it.type == RuleTrigger.Type.EVENT && it.event == RuleEvent.FRONT_DOOR_CLOSE } }
        if (fermee != fermetureAbonnee) {
            fermetureAbonnee = fermee
            if (fermee) MG4Hardware.addFrontDoorCloseListener(porteFermee) else MG4Hardware.removeFrontDoorCloseListener(porteFermee)
        }
        // La position n'est suivie que si une règle s'en sert (lieu, soleil).
        AndroidControls.followLocation(ctx, actives.any { besoinPosition(it) })
        val voulu = actives.any { r -> r.triggers.any { it.type in SUIVIS } }
        if (voulu && !releve) {
            releve = true
            fil.post(tick)
        } else if (!voulu && releve) {
            releve = false
            fil.removeCallbacks(tick)
        }
    }

    private fun besoinPosition(r: CustomRule): Boolean =
        RuleLogic.places(r).isNotEmpty() || RuleSignal.SUN_UP in RuleLogic.signals(r) ||
            r.triggers.any { it.type == RuleTrigger.Type.SUN }

    /** Arrête l'exécution en cours de l'automatisation [id], s'il y en a une ; elle reste active. */
    fun arreterEnCours(id: String, raison: String) {
        enCours.remove(id)?.let {
            it.cancel()
            AppLogger.i(TAG, "automatisation $id arrêtée $raison")
        }
    }

    private val porteOuverte: () -> Unit = { fil.post { evenement(RuleEvent.FRONT_DOOR_OPEN) } }
    private val porteFermee: () -> Unit = { fil.post { evenement(RuleEvent.FRONT_DOOR_CLOSE) } }
    private var fermetureAbonnee = false

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
        val suivies = actives(ctx).filter { r -> r.triggers.any { it.type in SUIVIS } }
        if (suivies.isEmpty()) return
        val signaux = suivies.flatMap { r -> r.triggers.flatMap { RuleLogic.signals(it) } }.toSet()
        val horloge = suivies.any { r -> r.triggers.any { it.type == RuleTrigger.Type.TIME || it.type == RuleTrigger.Type.SUN } }
        val lieux = suivies.flatMap { r -> r.triggers.flatMap { t -> RuleLogic.places(CustomRule(triggers = listOf(t))) } }.toSet()
        val etat = lire(ctx, signaux, horloge, lieux)
        val maintenant = SystemClock.elapsedRealtime()
        for (r in suivies) {
            val s = suivis[r.id] ?: continue
            r.triggers.forEachIndexed { i, t ->
                val w = s.getOrNull(i)
                val part = when {
                    t.type == RuleTrigger.Type.BECOMES && w is TriggerWatch ->
                        w.onValue(RuleLogic.evaluate(t.condition, etat), maintenant, t.forSeconds * 1_000L)
                    t.type == RuleTrigger.Type.TIME && w is TimeWatch -> t.at?.let { w.onMinute(etat.minuteOfDay, it) } ?: false
                    t.type == RuleTrigger.Type.SUN && w is TimeWatch -> soleil(ctx, t)?.let { w.onMinute(etat.minuteOfDay, it) } ?: false
                    t.type == RuleTrigger.Type.PERIODIC && w is PeriodicWatch -> w.onTick(maintenant, (t.every ?: 0) * 60_000L)
                    t.type == RuleTrigger.Type.CHANGES && w is ChangeWatch -> t.condition?.signal?.let { w.onValue(etat.values[it]) } ?: false
                    else -> false
                }
                if (part) declencher(r, "déclencheur ${i + 1}", par(ctx, t))
            }
        }
    }

    /** Minute du lever / coucher du jour, décalage compris ; `null` sans position ou horloge. */
    private fun soleil(ctx: Context, t: RuleTrigger): Int? {
        val sun = t.sun ?: return null
        val maintenant = System.currentTimeMillis()
        if (!StatsTracker.clockPlausible(maintenant)) return null
        val l = AndroidControls.location(ctx) ?: return null
        val c = Calendar.getInstance()
        val tz = TimeZone.getDefault().getOffset(maintenant) / 60_000
        val m = SunCalc.minuteOfDay(c.get(Calendar.DAY_OF_YEAR), l.latitude, l.longitude, tz, rising = sun == SunEvent.SUNRISE)
            ?: return null
        return ((m + t.offset) % 1440 + 1440) % 1440
    }

    private fun evenement(e: RuleEvent) {
        val ctx = app ?: return
        recharger()
        actives(ctx).filter { r -> r.triggers.any { it.type == RuleTrigger.Type.EVENT && it.event == e } }
            .forEach { r -> declencher(r, e.name, r.triggers.firstOrNull { it.type == RuleTrigger.Type.EVENT && it.event == e }?.let { par(ctx, it) }) }
    }

    /** [by] : ce qui l'a déclenchée, en texte (historique) ; [origine] : pour le journal. */
    private fun declencher(r: CustomRule, origine: String, by: String?) {
        val ctx = app ?: return
        if (GarageMode.isOn(ctx)) {
            AppLogger.i(TAG, "« ${r.name} » ($origine) : Mode Garage, rien")
            ignorer(ctx, r, by, RuleSkip(reason = RuleSkip.Reason.GARAGE))
            return
        }
        val etat = lire(ctx, RuleLogic.signals(r), RuleLogic.usesClock(r), RuleLogic.places(r))
        val conditions = RuleLogic.evaluate(r.condition, etat)
        if (conditions != true) {
            AppLogger.i(TAG, "« ${r.name} » ($origine) : conditions ${if (conditions == false) "fausses" else "inconnues"}, rien")
            // La première condition en cause et ce qui a été lu pour elle.
            val texte = RuleText(AndroidRuleWords(ctx))
            val enCause = RuleLogic.firstFailing(r.condition, etat)
            ignorer(ctx, r, by, RuleSkip(
                reason = if (conditions == false) RuleSkip.Reason.CONDITIONS else RuleSkip.Reason.UNKNOWN,
                detail = enCause?.let { texte.condition(it) }, value = texte.readValue(enCause, etat)))
            return
        }
        val maintenant = SystemClock.elapsedRealtime()
        when (RuleGate.decide(r, isRunning(r.id), departs[r.id], maintenant, ReadyWatcher.ready)) {
            RuleGate.Decision.START -> lancer(r, origine, by)
            RuleGate.Decision.RESTART -> {
                enCours.remove(r.id)?.cancel()
                lancer(r, "$origine, relancée", by)
            }
            RuleGate.Decision.BUSY -> {
                AppLogger.i(TAG, "« ${r.name} » ($origine) : déjà en cours, ignorée")
                ignorer(ctx, r, by, RuleSkip(reason = RuleSkip.Reason.BUSY))
            }
            RuleGate.Decision.COOLDOWN -> {
                AppLogger.i(TAG, "« ${r.name} » ($origine) : moins de ${r.cooldownMinutes} min depuis la dernière, ignorée")
                ignorer(ctx, r, by, RuleSkip(reason = RuleSkip.Reason.COOLDOWN, minutes = r.cooldownMinutes))
            }
            RuleGate.Decision.NOT_READY -> {
                AppLogger.i(TAG, "« ${r.name} » ($origine) : voiture pas en READY, ignorée")
                ignorer(ctx, r, by, RuleSkip(reason = RuleSkip.Reason.NOT_READY))
            }
        }
    }

    // ── Exécution ────────────────────────────────────────────────────────────

    /** Sur le fil mg4-rules (départs comptés là) ; les actions, dans une coroutine. */
    /** [compter] : faux pour « Tester », qui n'est pas un déclenchement (compteur de la carte). */
    private fun lancer(r: CustomRule, origine: String, by: String? = null, compter: Boolean = true) {
        val ctx = app ?: return
        departs[r.id] = SystemClock.elapsedRealtime()
        if (compter) CustomRuleStore.countRun(ctx, r.id)
        AppLogger.i(TAG, "« ${r.name} » ($origine) : ${r.actions.size} action(s)")
        // Le journal dit les actions comme l'éditeur : dans la langue de l'appli.
        val texte = RuleText(AndroidRuleWords(ctx))
        val run = RuleRun(Effets(ctx, r)) { texte.action(it) }
        val job = scope.launch {
            try {
                run.run(r.actions)
            } finally {
                val heure = System.currentTimeMillis().takeIf { StatsTracker.clockPlausible(it) }
                val bilan = RuleLastRun(heure, run.done, run.failed, run.journal.toList(), by)
                dernieres[r.id] = bilan
                CustomRuleStore.setLastRun(ctx, r.id, bilan)
                CustomRuleStore.addHistory(ctx, r.id, r.name, bilan)
                AppLogger.i(TAG, "« ${r.name} » terminée : ${run.done} ok, ${run.failed} en échec — ${run.journal.joinToString(" ; ")}")
                principal.post { surChangement?.invoke() }
            }
        }
        enCours[r.id] = job
        job.invokeOnCompletion { enCours.remove(r.id, job) }
    }

    private class Effets(private val ctx: Context, private val r: CustomRule) : RuleEffects {
        override fun state(): RuleState = lire(ctx, RuleLogic.signals(r), RuleLogic.usesClock(r), RuleLogic.places(r))

        override fun set(key: String, value: String): Boolean {
            if (GarageMode.isOn(ctx)) return false
            // « $NOM » : la valeur de la variable NOM (2026-11-06) ; variable inconnue = échec.
            val v = RuleLogic.variableRef(value)?.let { nom ->
                RuleVariables.all(ctx)[nom] ?: run { AppLogger.w(TAG, "réglage $key : variable $nom inconnue"); return false }
            } ?: value
            return if (key in RuleKeys.ALL) RuleWrites.set(ctx, key, v) else VehicleCommands.set(ctx, key, v, TAG)
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

        override suspend fun confirm(text: String, seconds: Int): Boolean? = suspendCancellableCoroutine { suite ->
            ProfileConfirmOverlay.showQuestion(ctx, r.name, text, seconds) { r -> if (suite.isActive) suite.resume(r) }
        }

        /** Un son de la page Sons ([RuleAction.value] = son numéro), comme avant le 2026-11-06. */
        private fun sonDeLaPage(a: RuleAction): Boolean {
            val n = a.value?.toIntOrNull() ?: return false
            if (n !in 0 until SoundAutomationSettings.count(ctx)) return false
            SoundAutomation.ecouter(ctx, n)
            return true
        }

        override suspend fun perform(a: RuleAction): Boolean {
            if (GarageMode.isOn(ctx)) return false
            return when (a.type) {
                RuleAction.Type.PLAY_SOUND -> when {
                    a.key == RuleLogic.BEEP -> { RuleSounds.bip(a.count ?: 70); true }
                    a.key?.startsWith(RuleLogic.RULE_SOUND) == true -> {
                        val son = RuleSounds.get(ctx, a.key.removePrefix(RuleLogic.RULE_SOUND)) ?: return false
                        if (!java.io.File(son.path).canRead()) return false
                        SoundAutomation.jouerFichier(ctx, son.title, son.path, son.durationMs, a.count)
                        true
                    }
                    else -> sonDeLaPage(a)
                }
                RuleAction.Type.VARIABLE_ADD -> {
                    val nom = a.key ?: return false
                    val delta = a.value?.toDoubleOrNull() ?: return false
                    RuleVariables.set(ctx, nom, RuleLogic.addTo(RuleVariables.all(ctx)[RuleLogic.variableName(nom)], delta))
                    true
                }
                RuleAction.Type.RULE_STOP -> {
                    val id = a.text ?: return false
                    // Elle-même n'est pas proposée dans l'éditeur ; si elle l'était, son travail s'arrête au prochain temps d'attente.
                    arreterEnCours(id, "par « ${r.name} »")
                    true
                }
                RuleAction.Type.MEDIA -> if (a.value == RuleLogic.MEDIA_PLAY) {
                    MG4Hardware.mediaResumeIfStopped(); true
                } else {
                    MG4Hardware.mediaPauseIfPlaying(); true
                }
                RuleAction.Type.SAVE_VALUE -> {
                    val nom = a.key ?: return false
                    val sig = RuleSignal.entries.firstOrNull { it.name == a.text } ?: return false
                    val v = RuleLogic.storedValue(runCatching { lireUn(ctx, sig) }.getOrNull()) ?: return false
                    RuleVariables.set(ctx, nom, v)
                    true
                }
                RuleAction.Type.OPEN_APP -> a.text?.let { p -> onMain { AndroidControls.openApp(ctx, p) } } ?: false
                RuleAction.Type.CUSTOM_SHORTCUT -> {
                    val id = a.text ?: return false
                    principal.post { CustomShortcutRunner.run(ctx, id) }
                    true
                }
                RuleAction.Type.RULE_ENABLE -> {
                    val id = a.text ?: return false
                    if (CustomRuleStore.all(ctx).none { it.id == id }) return false
                    CustomRuleStore.setEnabled(ctx, id, a.value == "1")
                    refresh(ctx)
                    principal.post { surChangement?.invoke() }
                    true
                }
                RuleAction.Type.RUN_RULE -> {
                    val id = a.text ?: return false
                    // Une règle qui se relance elle-même tournerait en rond.
                    if (id == r.id || CustomRuleStore.all(ctx).none { it.id == id }) return false
                    runManual(ctx, id, "lancée par « ${r.name} »", "« ${r.name} »")
                    true
                }
                RuleAction.Type.SET_VARIABLE -> {
                    val nom = a.key ?: return false
                    val v = a.value ?: return false
                    RuleVariables.set(ctx, nom, v)
                    true
                }
                RuleAction.Type.SPEAK -> AndroidControls.speak(ctx, a.text.orEmpty())
                RuleAction.Type.NAVIGATE -> {
                    val cible = a.text ?: return false
                    val lieu = RulePlaces.get(ctx, cible)
                    val (lat, lng) = lieu?.let { it.lat to it.lng }
                        ?: cible.split(",").mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                        ?: return false
                    onMain { AndroidControls.navigate(ctx, lat, lng, lieu?.name ?: cible) }
                }
                RuleAction.Type.ABRP_START -> a.text?.let { AbrpStream.start(ctx, it) } ?: false
                RuleAction.Type.ABRP_STOP -> { AbrpStream.stop(ctx, "« ${r.name} »"); true }
                else -> false
            }
        }

        /** Ouvrir une activité : depuis le fil principal, résultat attendu. */
        private suspend fun onMain(f: () -> Boolean): Boolean = suspendCancellableCoroutine { suite ->
            principal.post { val ok = runCatching(f).getOrDefault(false); if (suite.isActive) suite.resume(ok) }
        }
    }

    /**
     * Raccourcis qu'une automatisation ne lance pas : ceux qui attendent un réglage propre à la
     * touche (raccourci perso, profil de la touche, appli choisie pour la touche, automatisation à
     * lancer) — les profils, applis, raccourcis perso et automatisations ont leur propre action.
     * Aussi la liste des choix de l'éditeur.
     */
    val INTERDITS = setOf(
        ShortcutAction.NONE, ShortcutAction.CUSTOM, ShortcutAction.APPLY_PROFILE,
        ShortcutAction.OPEN_APP, ShortcutAction.OPEN_CUSTOM_APP, ShortcutAction.RUN_RULE,
    )

    // ── Lectures ─────────────────────────────────────────────────────────────

    private fun lire(ctx: Context, signaux: Set<RuleSignal>, horloge: Boolean, lieux: Set<String> = emptySet()): RuleState {
        val valeurs = HashMap<RuleSignal, Any?>()
        for (s in signaux) {
            valeurs[s] = runCatching { lireUn(ctx, s) }
                .onFailure { AppLogger.w(TAG, "lecture ${s.name} : ${it.message}") }
                .getOrNull()
        }
        val variables = RuleVariables.all(ctx)
        val chance = Random.nextDouble(0.0, 100.0)
        val position = if (lieux.isNotEmpty()) AndroidControls.location(ctx) else null
        val dans = lieux.associateWith { id ->
            val l = position ?: return@associateWith null
            RulePlaces.get(ctx, id)?.contains(l.latitude, l.longitude)
        }
        val maintenant = System.currentTimeMillis()
        // Horloge d'usine au réveil (~2019) : ni heure ni jour tant qu'elle n'est pas remise.
        if (!horloge || !StatsTracker.clockPlausible(maintenant)) return RuleState(valeurs, variables = variables, chance = chance, places = dans)
        val c = Calendar.getInstance().apply { timeInMillis = maintenant }
        // Calendar : dimanche = 1 … samedi = 7 ; ici lundi = 1 … dimanche = 7.
        val jour = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        return RuleState(valeurs, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), jour,
            (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH), variables, chance, dans)
    }

    private fun clim() = if (MG4Hardware.hasClimateControl()) MG4Hardware.getClimateState() else null

    private fun lireUn(ctx: Context, s: RuleSignal): Any? = when (s) {
        RuleSignal.READY -> ReadyWatcher.ready
        RuleSignal.GEAR -> RuleSignal.gearText(MG4Hardware.readGearRaw())
        RuleSignal.SPEED -> MG4Hardware.getVehicleSpeedKmh()?.toDouble()
        RuleSignal.SOC -> EnergyReader.soc()?.toDouble()
        RuleSignal.RANGE -> EnergyReader.rangeKm()?.toDouble()
        RuleSignal.CHARGING -> EnergyReader.charging()
        RuleSignal.OUTSIDE_TEMP -> MG4Hardware.getOutsideTempCelsius()?.toDouble()
        RuleSignal.HVAC_ON -> clim()?.powerOn
        RuleSignal.BT_DEVICE -> BluetoothProfileManager.getConnectedMacs()
        RuleSignal.PROFILE -> ActiveProfile.id(ctx)
        RuleSignal.DRIVE_MODE -> MG4Hardware.getDriveMode()?.name
        RuleSignal.REGEN -> MG4Hardware.getRegenLevel()?.name
        RuleSignal.ONE_PEDAL -> MG4Hardware.isOnePedalOn()
        RuleSignal.ENERGY_SAVING -> MG4Hardware.isEnergySavingOn()
        RuleSignal.AEB -> MG4Hardware.isAebEnabled()
        RuleSignal.TSR -> MG4Hardware.isTsrOn()
        RuleSignal.ESC -> if (MG4Hardware.hasDrowsinessAndEsc()) MG4Hardware.isEscOn() else null
        RuleSignal.DROWSINESS -> if (MG4Hardware.hasDrowsinessAndEsc()) MG4Hardware.isDrowsinessOn() else null
        RuleSignal.OVERSPEED_ALARM -> MG4Hardware.isOverspeedAlarmOn()
        RuleSignal.AC_ON -> clim()?.acOn
        RuleSignal.HVAC_AUTO -> clim()?.autoOn
        RuleSignal.HVAC_ECO -> clim()?.ecoOn
        RuleSignal.HVAC_TEMP -> clim()?.tempC?.toDouble()
        RuleSignal.FAN -> clim()?.fanLevel?.toDouble()
        RuleSignal.RECIRC -> when (clim()?.loopMode) {
            MG4Hardware.LoopMode.INNER -> "INNER"
            MG4Hardware.LoopMode.OUTSIDE -> "OUTSIDE"
            MG4Hardware.LoopMode.AUTO -> "AUTO"
            else -> null
        }
        RuleSignal.DEFROST_FRONT -> clim()?.defrostFront
        RuleSignal.DEFROST_REAR -> clim()?.defrostRear
        RuleSignal.SEAT_HEAT_LEFT -> MG4Hardware.getSeatHeatLeftOrNull()?.toDouble()
        RuleSignal.SEAT_HEAT_RIGHT -> MG4Hardware.getSeatHeatRightOrNull()?.toDouble()
        RuleSignal.STEERING_HEAT -> MG4Hardware.getSteeringHeatOrNull()
        RuleSignal.BATTERY_HEAT -> if (MG4Hardware.hasBatteryHeating()) MG4Hardware.isBatteryHeatingOn() else null
        RuleSignal.FRONT_DOOR -> MG4Hardware.isFrontDoorOpen()
        RuleSignal.LOCKED -> MG4Hardware.readCentralLock()
        RuleSignal.KEY_AWAY -> if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133)
            MG4Hardware.readIntGlobal(PROP_CLE_SANS_FIL).takeIf { it >= 0 }?.let { it == CLE_ELOIGNEE } else null
        RuleSignal.BRIGHTNESS -> if (MG4Hardware.hasBrightnessControl())
            MG4Hardware.getScreenBrightnessPercent(journal = false).takeIf { it >= 0 }?.toDouble() else null
        RuleSignal.VOLUME -> MG4Hardware.getMediaVolume().takeIf { it >= 0 }?.toDouble()
        RuleSignal.NIGHT -> MG4Hardware.isSideLightOn()
        RuleSignal.DRIVE_MINUTES -> if (ReadyWatcher.ready == true)
            readyDepuis?.let { (SystemClock.elapsedRealtime() - it) / 60_000.0 } ?: 0.0 else 0.0
        RuleSignal.DATA_TODAY_MB -> DataUsage.ethernet(ctx, DataUsage.startOfDay(), System.currentTimeMillis())?.total?.let { it / 1_048_576.0 }
        RuleSignal.DATA_MONTH_MB -> DataUsage.ethernet(ctx, DataUsage.startOfMonth(), System.currentTimeMillis())?.total?.let { it / 1_048_576.0 }
        RuleSignal.CHARGE_LIMIT -> EnergyReader.chargeLimitCode()?.let { ChargeLimit.pct(it) }?.toDouble()
        RuleSignal.PLUGGED -> EnergyReader.plugged()
        RuleSignal.CHARGE_DC -> EnergyReader.plugged()?.let { it && EnergyReader.plugType() == ChargeType.DC }
        RuleSignal.FIRMWARE -> FirmwareInfo.getGeneration().name
        RuleSignal.PROJECTION -> (ProjectionReader.appareilActif(ctx) as? ProjectionReader.Lecture.Lue)?.let { it.appareil != null }
        RuleSignal.WIFI_ON -> AndroidControls.wifiOn(ctx)
        RuleSignal.WIFI_SSID -> AndroidControls.wifiSsid(ctx)
        RuleSignal.BT_ON -> AndroidControls.bluetoothOn()
        RuleSignal.HOTSPOT_ON -> AndroidControls.hotspotOn(ctx)
        RuleSignal.MEDIA_PLAYING -> AndroidControls.mediaPlaying(ctx)
        RuleSignal.IN_CALL -> AndroidControls.inCall(ctx)
        RuleSignal.SUN_UP -> {
            val maintenant = System.currentTimeMillis()
            val l = AndroidControls.location(ctx)
            if (l == null || !StatsTracker.clockPlausible(maintenant)) null else {
                val c = Calendar.getInstance()
                SunCalc.isUp(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), c.get(Calendar.DAY_OF_YEAR),
                    l.latitude, l.longitude, TimeZone.getDefault().getOffset(maintenant) / 60_000)
            }
        }
    }

    /** Chargeur sans fil : 8 quand la clé s'éloigne (relevé SWI133). */
    private const val PROP_CLE_SANS_FIL = 0x2140f445
    private const val CLE_ELOIGNEE = 8
}
