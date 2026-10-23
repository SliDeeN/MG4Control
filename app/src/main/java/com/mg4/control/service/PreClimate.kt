package com.mg4.control.service

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.ClimateSnapshot
import com.mg4.control.model.ClimateStep
import com.mg4.control.model.PreClimateClimate
import com.mg4.control.model.PreClimateEnd
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateRequest
import com.mg4.control.model.UnlockWatch
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.PreClimateSettings
import java.util.Calendar

/**
 * Pré-climatisation (issue #130, 2026-10-23) : le programme, sur son propre fil.
 *
 * Lancée par le raccourci au volant ([raccourci]) puis le bouton « Démarrer » du pop-up
 * ([PreClimatePopupOverlay]) : décompte, verrouillage des portes par l'écran (verrouiller avec la
 * clé éteindrait toute la voiture, écran compris), puis la clim selon le mode — tout de suite, ou
 * à une heure en cycles, coupée entre deux. La voiture reste allumée : une fois éteinte, rien ne la
 * rallume depuis l'écran ([PreClimatePlan]).
 *
 * Fin : durée écoulée ou batterie sous le seuil (clim rétablie puis voiture éteinte), portes
 * déverrouillées ou voiture qui roule (clim rétablie, voiture laissée allumée), bouton « Arrêter ».
 * Dans tous les cas la clim retrouve les réglages qu'elle avait avant « Démarrer ».
 *
 * Rien n'est gardé si le service redémarre : un programme interrompu ainsi est perdu (comme chez
 * MG4Dash). Journal : `MG4_PRECLIM`.
 */
object PreClimate {

    const val TAG = "MG4_PRECLIM"

    private const val TICK_MS = 1_000L
    /** Après le verrouillage confirmé, avant de guetter le retour du conducteur. */
    private const val GRACE_MS = 15_000L
    /** Relecture du verrouillage après l'ordre, puis après le repli. */
    private const val LOCK_CHECK_MS = 2_500L
    private const val LOCK_READ_TICKS = 2
    /** Au-delà, la voiture roule : le conducteur est revenu. */
    private const val DRIVING_KMH = 3f
    /** La clim doit tourner pendant un cycle : relue toutes les 10 s, relancée après 30 s d'arrêt. */
    private const val CLIM_CHECK_TICKS = 10
    private const val CLIM_OFF_READS = 3
    private const val OUTSIDE_TICKS = 30
    /** Le temps que la clim démarre avant d'en régler la consigne, et avant d'éteindre la voiture. */
    private const val CLIM_START_MS = 1_500L
    private const val POWER_OFF_DELAY_MS = 3_000L
    /** Écran de fin : l'état est gardé ce temps, puis oublié. */
    private const val END_KEEP_MS = 5 * 60_000L
    private const val WAKE_MAX_MS = 26 * 3_600_000L

    enum class Phase { COUNTDOWN, ACTIVE, ENDED }
    enum class LockState { PENDING, LOCKED, FAILED }

    /** Ce que montre l'écran de suivi ([PreClimateScreen]). Temps monotones. */
    data class Etat(
        val phase: Phase,
        val req: PreClimateRequest,
        val startMs: Long,
        /** Heure murale au démarrage, en minutes depuis minuit. */
        val startWallMinute: Double,
        val lockAtMs: Long,
        val cycles: List<PreClimatePlan.Cycle>,
        /** Début et fin de chaque cycle en heure murale (minutes depuis minuit ; fin -1 = sans limite). */
        val wallTimes: List<Pair<Int, Int>>,
        val step: PreClimatePlan.Step?,
        val lock: LockState,
        val soc: Float?,
        val socStart: Float?,
        val outsideC: Float?,
        val minSoc: Int,
        val snapshot: ClimateSnapshot,
        val end: PreClimateEnd? = null,
        val endWallMinute: Double? = null,
        /** Extinction de fin : envoyée et acceptée (vrai), refusée (faux), pas tentée (null). */
        val powerOffOk: Boolean? = null,
    )

    private val fil by lazy { HandlerThread("mg4-preclim").also { it.start() } }
    private val travail by lazy { Handler(fil.looper) }
    private val principal = Handler(Looper.getMainLooper())

    /** Dernier état : écrit sur le fil du programme, lu partout. */
    @Volatile var etat: Etat? = null
        private set

    /** Écran de suivi, appelé sur le fil principal à chaque relevé. */
    var ecouteur: ((Etat) -> Unit)? = null

    // ── Fil du programme seulement ───────────────────────────────────────────
    private var wake: PowerManager.WakeLock? = null
    /** Contexte de l'application, retenu au démarrage : la fin prévient le combiné. */
    @Volatile private var appContext: Context? = null
    private var presetApplied = false
    private var lastStepKey: String? = null
    private var unlockWatch = UnlockWatch()
    private var lockConfirmedAt = 0L
    private var climOffReads = 0
    private var ticks = 0

    fun actif(): Boolean = etat?.phase.let { it == Phase.COUNTDOWN || it == Phase.ACTIVE }

    /**
     * Raccourci au volant : pendant un programme, réaffiche l'écran de suivi (qui a « Arrêter ») ;
     * sinon ouvre le pop-up de réglage, ou le referme.
     */
    fun raccourci(context: Context) {
        if (actif()) {
            principal.post { PreClimateScreen.afficher(context.applicationContext, "raccourci") }
            return
        }
        PreClimatePopupOverlay.toggle(context)
    }

    /**
     * « Démarrer » du pop-up. Refus (P non confirmé, clim injoignable) : [surRefus] reçoit le
     * texte à montrer, sur le fil principal. Sinon [surDepart], puis l'écran de suivi s'affiche.
     */
    fun demarrer(context: Context, req: PreClimateRequest, surRefus: (Int) -> Unit, surDepart: () -> Unit) {
        val app = context.applicationContext
        travail.post {
            if (actif()) return@post
            // Même garde que l'extinction : la fin éteint la voiture, qui doit être en P.
            if (MG4Hardware.isVehicleInPark() != true) {
                AppLogger.w(TAG, "refusée : levier pas confirmé en P")
                principal.post { surRefus(R.string.preclim_need_park) }
                return@post
            }
            val clim = MG4Hardware.getClimateState()
            if (clim == null) {
                AppLogger.w(TAG, "refusée : clim injoignable")
                principal.post { surRefus(R.string.preclim_clim_unreachable) }
                return@post
            }
            PreClimateSettings.saveRequest(app, req)
            appContext = app
            val borne = req.copy(
                tempC = req.tempC.coerceIn(clim.tempMin, clim.tempMax),
                fan = req.fan.coerceIn(clim.fanMin, clim.fanMax),
                // Mode chien : « Maintenant » seulement — en attente, la clim serait coupée — et 2 h.
                // Le choix de durée enregistré plus haut reste celui de l'utilisateur.
                dog = req.dog && req.mode == PreClimateMode.NOW,
                maxMin = PreClimatePlan.maxMinutes(req))
            val maintenant = SystemClock.elapsedRealtime()
            val mur = minuteDuJour()
            val cycles = PreClimatePlan.cycles(borne, maintenant, mur)
            val snap = instantane(clim)
            val soc = EnergyReader.soc()
            presetApplied = false
            lastStepKey = null
            unlockWatch = UnlockWatch()
            climOffReads = 0
            ticks = 0
            val e = Etat(
                phase = Phase.COUNTDOWN, req = borne, startMs = maintenant, startWallMinute = mur,
                lockAtMs = maintenant + PreClimateSettings.lockSeconds(app) * 1_000L,
                cycles = cycles, wallTimes = PreClimatePlan.wallTimes(borne, mur), step = null,
                lock = LockState.PENDING, soc = soc, socStart = soc,
                outsideC = MG4Hardware.getOutsideTempCelsius(), minSoc = PreClimateSettings.minSoc(app),
                snapshot = snap)
            etat = e
            prendreVeille(app)
            AppLogger.i(TAG, "démarrée : ${borne.mode} " + (if (borne.mode == PreClimateMode.NOW)
                "max ${borne.maxMin} min" else "${borne.runs} × ${borne.runMin} min (pause ${borne.pauseMin} min), " +
                "1er cycle dans ${"%.0f".format(java.util.Locale.US, PreClimatePlan.minutesUntil(borne.firstMinuteOfDay, mur))} min") +
                ", clim ${borne.tempC} °C ventil. ${borne.fan} A/C ${borne.ac}${if (borne.dog) ", mode chien" else ""}, verrouillage dans " +
                "${(e.lockAtMs - maintenant) / 1000} s, arrêt sous ${e.minSoc} % (batterie ${soc ?: "?"} %) ; avant : $snap")
            principal.post {
                surDepart()
                PreClimateScreen.afficher(app, "démarrage")
            }
            // La fiche prend la place du module du combiné — posé pour elle s'il est désactivé.
            ClusterModuleOverlay.refresh(app, "pré-climatisation")
            publier(e)
            // Mode « Maintenant » : la clim prend tout de suite les réglages choisis.
            if (borne.mode == PreClimateMode.NOW) appliquer(borne)
            travail.postDelayed(tick, TICK_MS)
        }
    }

    /**
     * La fiche de la pré-climatisation est-elle à montrer sur l'écran conducteur
     * ([ClusterModuleOverlay]) : programme en cours, réglage activé, firmware du module.
     */
    fun surLeCombine(context: Context): Boolean {
        val phase = etat?.phase ?: return false
        return phase != Phase.ENDED && ClusterModuleSettings.isSupported() && PreClimateSettings.showOnCluster(context)
    }

    /** Bouton « Arrêter » de l'écran de suivi. */
    fun arreter() {
        travail.post { if (actif()) terminer(PreClimateEnd.STOPPED) }
    }

    /** « Annuler » pendant le décompte : rien n'a été verrouillé. */
    fun annuler() {
        travail.post { if (etat?.phase == Phase.COUNTDOWN) terminer(PreClimateEnd.CANCELLED) }
    }

    /** Oublie l'écran de fin (bouton « Fermer »). */
    fun oublierFin() {
        travail.post { if (etat?.phase == Phase.ENDED) etat = null }
    }

    // ── Déroulement ──────────────────────────────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            try {
                battre()
            } catch (e: Exception) {
                AppLogger.w(TAG, "relevé : ${e.javaClass.simpleName} ${e.message}")
            }
            if (actif()) travail.postDelayed(this, TICK_MS)
        }
    }

    private fun battre() {
        val e = etat ?: return
        val maintenant = SystemClock.elapsedRealtime()
        ticks++
        when (e.phase) {
            Phase.COUNTDOWN -> if (maintenant >= e.lockAtMs) verrouiller(e, maintenant) else publier(e)
            Phase.ACTIVE -> suivre(e, maintenant)
            Phase.ENDED -> Unit
        }
    }

    private fun verrouiller(e: Etat, maintenant: Long) {
        MG4Hardware.lockDoors()
        val actif = e.copy(phase = Phase.ACTIVE, lock = LockState.PENDING)
        etat = actif
        travail.postDelayed({ verifierVerrou(1) }, LOCK_CHECK_MS)
        suivre(actif, maintenant)
    }

    /** Relit le verrouillage ; au premier échec, essaie la voie de repli. */
    private fun verifierVerrou(essai: Int) {
        val e = etat ?: return
        if (e.phase != Phase.ACTIVE) return
        val lu = MG4Hardware.readCentralLock()
        val suite = when {
            lu == true -> {
                lockConfirmedAt = SystemClock.elapsedRealtime()
                AppLogger.i(TAG, "portes verrouillées (relecture n° $essai)")
                e.copy(lock = LockState.LOCKED)
            }
            essai == 1 -> {
                AppLogger.w(TAG, "portes pas verrouillées à la relecture ($lu) → voie de repli")
                MG4Hardware.lockDoors(repli = true)
                travail.postDelayed({ verifierVerrou(2) }, LOCK_CHECK_MS)
                e
            }
            else -> {
                // On continue : couper le programme laisserait la voiture tout aussi ouverte et
                // allumée. L'écran le dit ; sans verrouillage, pas de retour guetté.
                AppLogger.w(TAG, "portes NON verrouillées après le repli ($lu) — le programme continue")
                e.copy(lock = LockState.FAILED)
            }
        }
        etat = suite
        publier(suite)
    }

    private fun suivre(e: Etat, maintenant: Long) {
        // Retour du conducteur : portes déverrouillées, une fois le verrouillage confirmé. Relu une
        // seconde sur deux : deux relevés de suite le confirment en quatre secondes.
        if (e.lock == LockState.LOCKED && maintenant - lockConfirmedAt >= GRACE_MS && ticks % LOCK_READ_TICKS == 0 &&
            unlockWatch.observe(MG4Hardware.readCentralLock())) {
            terminer(PreClimateEnd.UNLOCKED); return
        }
        val kmh = MG4Hardware.getVehicleSpeedKmh()
        if (kmh != null && kmh > DRIVING_KMH) { terminer(PreClimateEnd.DRIVING); return }
        val soc = EnergyReader.soc()
        if (soc != null && soc < e.minSoc) { terminer(PreClimateEnd.BATTERY); return }
        val step = PreClimatePlan.step(e.cycles, e.lockAtMs, maintenant)
        if (step == PreClimatePlan.Step.Done) { terminer(PreClimateEnd.DONE); return }

        val cle = when (step) {
            is PreClimatePlan.Step.Waiting -> "W${step.index}"
            is PreClimatePlan.Step.Running -> "R${step.index}"
            PreClimatePlan.Step.Done -> "D"
        }
        if (cle != lastStepKey) {
            when (step) {
                // « Maintenant » : déjà réglée au démarrage.
                is PreClimatePlan.Step.Running ->
                    if (e.req.mode == PreClimateMode.AT_TIME || !presetApplied) {
                        AppLogger.i(TAG, "cycle ${step.index + 1} : clim en marche")
                        appliquer(e.req)
                    }
                // À une heure : clim coupée au verrouillage puis entre deux cycles (MG4Dash #26).
                is PreClimatePlan.Step.Waiting -> {
                    AppLogger.i(TAG, "attente du cycle ${step.index + 1} : clim coupée")
                    MG4Hardware.setClimatePower(false)
                }
                PreClimatePlan.Step.Done -> Unit
            }
            lastStepKey = cle
            climOffReads = 0
        } else if (step is PreClimatePlan.Step.Running && ticks % CLIM_CHECK_TICKS == 0) {
            // La clim doit tourner : relancée si elle s'est arrêtée (voiture, appui sur le bouton…).
            val enMarche = MG4Hardware.getClimateState()?.powerOn
            climOffReads = if (enMarche == false) climOffReads + 1 else 0
            if (climOffReads >= CLIM_OFF_READS) {
                AppLogger.w(TAG, "clim arrêtée en plein cycle → relancée")
                appliquer(e.req)
                climOffReads = 0
            }
        }
        val dehors = if (ticks % OUTSIDE_TICKS == 0) MG4Hardware.getOutsideTempCelsius() ?: e.outsideC else e.outsideC
        val suite = e.copy(step = step, soc = soc ?: e.soc, outsideC = dehors)
        etat = suite
        publier(suite)
    }

    /** Réglages de la pré-climatisation : marche, consigne, ventilation, compresseur. */
    private fun appliquer(req: PreClimateRequest) {
        val ok = PreClimateClimate.applySteps(req).map { pas ->
            ecrire(pas).also { if (pas == ClimateStep.PowerOn) SystemClock.sleep(CLIM_START_MS) }
        }.all { it }
        presetApplied = true
        AppLogger.i(TAG, "clim réglée : ${req.tempC} °C, ventilation ${req.fan}, A/C ${req.ac} → ok=$ok")
    }

    private fun ecrire(pas: ClimateStep): Boolean = try {
        when (pas) {
            ClimateStep.PowerOn -> MG4Hardware.setClimatePower(true)
            ClimateStep.PowerOff -> MG4Hardware.setClimatePower(false)
            is ClimateStep.Temp -> MG4Hardware.setClimateTemp(pas.celsius)
            is ClimateStep.Fan -> MG4Hardware.setClimateFan(pas.level)
            ClimateStep.AutoOn -> MG4Hardware.setClimateAuto(true)
            is ClimateStep.Ac -> MG4Hardware.setClimateAc(pas.on)
            is ClimateStep.Loop -> MG4Hardware.setClimateLoopMode(pas.mode)
            is ClimateStep.Air -> MG4Hardware.setClimateAirFlow(pas.direction)
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "écriture clim $pas : ${e.message}")
        false
    }

    /**
     * Fin du programme, quelle qu'en soit la raison : la clim retrouve ses réglages d'avant, puis
     * la voiture s'éteint si personne n'est revenu ([PreClimateEnd.powersOff]).
     */
    private fun terminer(fin: PreClimateEnd) {
        val e = etat ?: return
        if (e.phase == Phase.ENDED) return
        travail.removeCallbacks(tick)
        AppLogger.i(TAG, "fin : $fin")

        val pas = PreClimateClimate.restoreSteps(e.snapshot, presetApplied, MG4Hardware.getClimateState()?.powerOn)
        val ok = pas.map { p ->
            ecrire(p).also { if (p == ClimateStep.PowerOn) SystemClock.sleep(CLIM_START_MS) }
        }.all { it }
        AppLogger.i(TAG, "clim rétablie (${pas.joinToString { it.toString() }}) → ok=$ok")

        var eteinte: Boolean? = null
        if (fin.powersOff) {
            SystemClock.sleep(POWER_OFF_DELAY_MS)
            eteinte = MG4Hardware.vehiclePowerOff()
            AppLogger.i(TAG, "voiture éteinte → $eteinte")
            if (eteinte) verifierApresExtinction()
        }
        val fini = e.copy(phase = Phase.ENDED, end = fin, endWallMinute = minuteDuJour(),
            powerOffOk = eteinte, soc = EnergyReader.soc() ?: e.soc)
        etat = fini
        publier(fini)
        appContext?.let { ClusterModuleOverlay.refresh(it, "fin de la pré-climatisation") }
        relacherVeille()
        presetApplied = false
        lastStepKey = null
        travail.postDelayed({
            if (etat === fini) {
                etat = null
                principal.post { PreClimateScreen.fermer("fin, état oublié") }
            }
        }, END_KEEP_MS)
    }

    /**
     * Le réglage d'origine « Déverrouiller à l'extinction » pourrait rouvrir les portes : on relit,
     * et on reverrouille une fois si besoin. Non prouvé en voiture — le journal dira ce qu'il en est.
     */
    private fun verifierApresExtinction() {
        SystemClock.sleep(POWER_OFF_DELAY_MS)
        val lu = MG4Hardware.readCentralLock()
        AppLogger.i(TAG, "verrouillage après l'extinction : $lu")
        if (lu == false) {
            MG4Hardware.lockDoors()
            SystemClock.sleep(LOCK_CHECK_MS)
            AppLogger.i(TAG, "reverrouillage après l'extinction → ${MG4Hardware.readCentralLock()}")
        }
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    private fun instantane(s: MG4Hardware.ClimateState): ClimateSnapshot {
        val r = s.reglages()
        return ClimateSnapshot(
            powerOn = s.powerOn,
            tempC = s.tempC,
            // Clim arrêtée, la voiture n'annonce plus de cran : le dernier réglé à la main.
            fan = if (r.enAuto) null else r.ventilationReelle ?: s.lastManualFan,
            auto = if (s.powerOn == false) s.autoOn else r.enAuto,
            ac = s.acOn,
            loopMode = s.loopMode,
            airFlow = s.airFlow,
        )
    }

    private fun publier(e: Etat) {
        principal.post { ecouteur?.invoke(e) }
    }

    private fun prendreVeille(context: Context) {
        relacherVeille()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4control:preclim").apply {
            setReferenceCounted(false)
            acquire(WAKE_MAX_MS)
        }
    }

    private fun relacherVeille() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    /** Heure murale en minutes depuis minuit, secondes comprises. */
    fun minuteDuJour(): Double {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
    }
}
