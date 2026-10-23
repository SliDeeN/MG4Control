package com.mg4.control.model

/**
 * Pré-climatisation (issue #130, 2026-10-23) — les règles, sans Android.
 *
 * Sans iSmart, rien ne rallume une voiture éteinte depuis l'écran central (essais de MG4Dash sur
 * SWI69, 2026-10-19) : la voiture reste donc ALLUMÉE et verrouillée pendant l'absence du
 * conducteur, et la clim tourne selon l'un des deux modes :
 *  - [PreClimateMode.NOW] : tout de suite, au plus [PreClimateRequest.maxMin] minutes (2 h) ;
 *  - [PreClimateMode.AT_TIME] : à une heure choisie, en un ou plusieurs cycles séparés d'une
 *    pause, clim coupée en attendant (≈ 0,2 kW voiture allumée, relevé de l'auteur de l'issue).
 *
 * Les échéances sont en temps MONOTONE (`elapsedRealtime`) : l'horloge murale du boîtier peut
 * sauter (réveil à sa date d'usine). Seule l'heure du premier cycle vient d'elle, une fois, au
 * démarrage.
 */
enum class PreClimateMode { NOW, AT_TIME }

/** Ce que l'utilisateur a choisi dans le pop-up. Températures en °C, durées en minutes. */
data class PreClimateRequest(
    val mode: PreClimateMode,
    /** [PreClimateMode.NOW] : durée maximale, l'une de [PreClimatePlan.MAX_CHOICES]. */
    val maxMin: Int,
    /** [PreClimateMode.AT_TIME] : heure du premier cycle, en minutes depuis minuit. */
    val firstMinuteOfDay: Int,
    val runMin: Int,
    val runs: Int,
    val pauseMin: Int,
    val tempC: Int,
    val fan: Int,
    val ac: Boolean,
    /**
     * Mode chien (2026-10-23) : l'écran reste allumé et dit aux passants que tout va bien, avec la
     * température réglée. Mode « Maintenant » seulement : en attente, la clim serait coupée.
     * Durée imposée : [PreClimatePlan.DOG_MAX_MIN] (demande du 2026-10-23).
     */
    val dog: Boolean = false,
)

/** L'écran de suivi pendant l'attente, portes verrouillées (page du raccourci). */
enum class PreClimateScreenMode {
    /** Luminosité inchangée. */
    NORMAL,
    /** Voile et luminosité au minimum ; un toucher rallume. */
    DIM,
    /** Voile presque opaque. */
    OFF,
}

/**
 * Ce que fait la clim d'après l'écart entre la consigne et l'extérieur (2026-10-23) : la couleur des
 * particules, de l'anneau et de la température de l'écran de suivi — bleu, orange, ou bleu calme.
 */
enum class PreClimateTrend { COOLING, HEATING, HOLDING }

/** Pourquoi la pré-climatisation s'est arrêtée. */
enum class PreClimateEnd {
    /** Durée écoulée ou dernier cycle fini : clim rétablie, voiture éteinte. */
    DONE,
    /** Batterie sous le seuil : comme [DONE]. */
    BATTERY,
    /** Portes déverrouillées : le conducteur revient, la voiture reste allumée. */
    UNLOCKED,
    /** La voiture roule. */
    DRIVING,
    /** Bouton « Arrêter » de l'écran de suivi. */
    STOPPED,
    /** « Annuler » pendant le décompte : rien n'a été verrouillé. */
    CANCELLED;

    /** Fins qui éteignent la voiture : personne n'est revenu. */
    val powersOff: Boolean get() = this == DONE || this == BATTERY
}

object PreClimatePlan {

    /**
     * Durées maximales proposées en mode [PreClimateMode.NOW]. Plus de « Sans limite » depuis le
     * 2026-10-23 : 2 h au plus (une valeur 0 enregistrée avant redevient [DEFAULT_MAX_MIN]).
     */
    val MAX_CHOICES = listOf(15, 30, 60, 120)
    const val DEFAULT_MAX_MIN = 60
    /** Mode chien : toujours la durée la plus longue, sans choix. */
    const val DOG_MAX_MIN = 120

    const val RUN_MIN = 5
    const val RUN_MAX = 60
    const val RUN_STEP = 5
    const val DEFAULT_RUN_MIN = 15

    const val RUNS_MIN = 1
    const val RUNS_MAX = 5
    const val DEFAULT_RUNS = 1

    const val PAUSE_MIN = 10
    const val PAUSE_MAX = 120
    const val PAUSE_STEP = 10
    const val DEFAULT_PAUSE_MIN = 30

    /** Pas de l'heure du premier cycle, en minutes. */
    const val FIRST_STEP = 5

    /** Décompte avant le verrouillage, en secondes (Réglages du raccourci). */
    const val LOCK_MIN_S = 10
    const val LOCK_MAX_S = 60
    const val LOCK_STEP_S = 5
    const val DEFAULT_LOCK_S = 20

    /** Seuil de batterie sous lequel tout s'arrête, en %. */
    const val SOC_MIN = 10
    const val SOC_MAX = 40
    const val SOC_STEP = 5
    const val DEFAULT_SOC = 20

    private const val MINUTES_PAR_JOUR = 1440
    private const val MS_PAR_MIN = 60_000L

    /**
     * Minutes de [nowMinuteOfDay] (avec ses secondes) jusqu'au prochain [firstMinuteOfDay] : une
     * heure déjà passée — ou à moins d'une minute — est celle du lendemain.
     */
    fun minutesUntil(firstMinuteOfDay: Int, nowMinuteOfDay: Double): Double {
        val d = firstMinuteOfDay - nowMinuteOfDay
        return if (d < 1.0) d + MINUTES_PAR_JOUR else d
    }

    /** Vrai si le premier cycle tombe le lendemain (pour le dire dans le pop-up). */
    fun isTomorrow(firstMinuteOfDay: Int, nowMinuteOfDay: Double): Boolean =
        firstMinuteOfDay - nowMinuteOfDay < 1.0

    /** Un cycle de clim, en temps monotone. */
    data class Cycle(val startMs: Long, val endMs: Long)

    /** Durée maximale effective en mode [PreClimateMode.NOW] : celle choisie, ou 2 h en mode chien. */
    fun maxMinutes(req: PreClimateRequest): Int =
        if (req.dog && req.mode == PreClimateMode.NOW) DOG_MAX_MIN else clampMax(req.maxMin)

    /**
     * Cycles de [req], [startMs] étant l'instant (monotone) du démarrage et [nowMinuteOfDay]
     * l'heure murale au même instant. Mode NOW : un seul cycle, du démarrage à la durée maximale.
     */
    fun cycles(req: PreClimateRequest, startMs: Long, nowMinuteOfDay: Double): List<Cycle> = when (req.mode) {
        PreClimateMode.NOW -> listOf(Cycle(startMs, startMs + maxMinutes(req) * MS_PAR_MIN))
        PreClimateMode.AT_TIME -> {
            val first = startMs + (minutesUntil(req.firstMinuteOfDay, nowMinuteOfDay) * MS_PAR_MIN).toLong()
            (0 until req.runs.coerceIn(RUNS_MIN, RUNS_MAX)).map { i ->
                val s = first + i * (req.runMin + req.pauseMin) * MS_PAR_MIN
                Cycle(s, s + req.runMin * MS_PAR_MIN)
            }
        }
    }

    /** Heures murales (minutes depuis minuit) de début et de fin de chaque cycle, pour l'affichage. */
    fun wallTimes(req: PreClimateRequest, nowMinuteOfDay: Double): List<Pair<Int, Int>> =
        cycles(req, 0L, nowMinuteOfDay).map { c ->
            // Arrondi, pas troncature : 1109,9999 doit rester 18:30 et non devenir 18:29.
            fun mur(ms: Long) = Math.floorMod(Math.round(nowMinuteOfDay + ms / MS_PAR_MIN.toDouble()), MINUTES_PAR_JOUR.toLong()).toInt()
            mur(c.startMs) to mur(c.endMs)
        }

    /** Où en est le programme. */
    sealed class Step {
        /** Clim coupée jusqu'au cycle [index] ; l'attente a commencé à [fromMs]. */
        data class Waiting(val index: Int, val fromMs: Long, val untilMs: Long) : Step()
        /** Cycle [index] en cours, jusqu'à [untilMs]. */
        data class Running(val index: Int, val fromMs: Long, val untilMs: Long) : Step()
        /** Tous les cycles sont finis. */
        data object Done : Step()
    }

    /** Étape à l'instant [nowMs] ; l'attente du premier cycle commence au verrouillage [lockMs]. */
    fun step(cycles: List<Cycle>, lockMs: Long, nowMs: Long): Step {
        for ((i, c) in cycles.withIndex()) {
            if (nowMs >= c.endMs) continue
            if (nowMs < c.startMs) return Step.Waiting(i, if (i == 0) minOf(lockMs, c.startMs) else cycles[i - 1].endMs, c.startMs)
            return Step.Running(i, c.startMs, c.endMs)
        }
        return Step.Done
    }

    /** Part écoulée de l'étape, de 0 à 1 (l'arc de l'écran de suivi). */
    fun fraction(step: Step, nowMs: Long): Float {
        val (from, until) = when (step) {
            is Step.Waiting -> step.fromMs to step.untilMs
            is Step.Running -> step.fromMs to step.untilMs
            Step.Done -> return 1f
        }
        if (until <= from) return 1f
        return ((nowMs - from).toFloat() / (until - from)).coerceIn(0f, 1f)
    }

    // ── Animation de l'écran de suivi ────────────────────────────────────────

    /** Écart (°C) en deçà duquel la clim ne fait que maintenir. */
    private const val TREND_DEADBAND_C = 0.5f
    /** Écart (°C) au-delà duquel l'animation est à son maximum (≈ 10 °C au-dessus du plancher). */
    private const val TREND_FULL_C = 14f

    /** Refroidit si la consigne est sous l'extérieur, chauffe au-dessus ; extérieur illisible : maintient. */
    fun trend(setC: Int, outsideC: Float?): PreClimateTrend {
        val ecart = setC - (outsideC ?: return PreClimateTrend.HOLDING)
        return when {
            ecart > TREND_DEADBAND_C -> PreClimateTrend.HEATING
            ecart < -TREND_DEADBAND_C -> PreClimateTrend.COOLING
            else -> PreClimateTrend.HOLDING
        }
    }

    /** Force de l'animation, de 0,15 (maintien) à 1 : plus l'écart est grand, plus elle est ample et vive. */
    fun intensity(setC: Int, outsideC: Float?): Float {
        if (trend(setC, outsideC) == PreClimateTrend.HOLDING) return 0.15f
        return (0.3f + kotlin.math.abs(setC - outsideC!!) / TREND_FULL_C).coerceAtMost(1f)
    }

    // ── Réglages bornés ──────────────────────────────────────────────────────

    fun clampMax(v: Int) = if (v in MAX_CHOICES) v else DEFAULT_MAX_MIN
    fun clampRun(v: Int) = v.coerceIn(RUN_MIN, RUN_MAX)
    fun clampRuns(v: Int) = v.coerceIn(RUNS_MIN, RUNS_MAX)
    fun clampPause(v: Int) = v.coerceIn(PAUSE_MIN, PAUSE_MAX)
    fun clampLock(v: Int) = v.coerceIn(LOCK_MIN_S, LOCK_MAX_S)
    fun clampSoc(v: Int) = v.coerceIn(SOC_MIN, SOC_MAX)

    /**
     * Heure du premier cycle après un pas de [direction] (±1) : la graduation de 5 minutes
     * suivante ou précédente, en tournant sur 24 h.
     */
    fun stepFirst(firstMinuteOfDay: Int, direction: Int): Int {
        val reste = Math.floorMod(firstMinuteOfDay, FIRST_STEP)
        val suivante = when {
            direction > 0 -> firstMinuteOfDay - reste + FIRST_STEP
            reste == 0 -> firstMinuteOfDay - FIRST_STEP
            else -> firstMinuteOfDay - reste
        }
        return Math.floorMod(suivante, MINUTES_PAR_JOUR)
    }

    /** Heure proposée la première fois : dans une heure, arrondie aux 5 minutes suivantes. */
    fun defaultFirst(nowMinuteOfDay: Double): Int {
        val dans = (nowMinuteOfDay + 60).toInt()
        return Math.floorMod(dans + (FIRST_STEP - dans % FIRST_STEP) % FIRST_STEP, MINUTES_PAR_JOUR)
    }
}

/**
 * Retour du conducteur : les portes vues déverrouillées [needed] fois de suite. Une lecture ratée
 * (`null`) ne compte ni pour ni contre ; une lecture « verrouillé » remet à zéro.
 */
class UnlockWatch(private val needed: Int = 2) {
    private var count = 0
    fun observe(locked: Boolean?): Boolean {
        when (locked) {
            false -> count++
            true -> count = 0
            null -> Unit
        }
        return count >= needed
    }
}

/**
 * Réglages de la clim avant la pré-climatisation, rétablis à la fin (demande du 2026-10-23 : « au
 * déverrouillage ou à la fin du minuteur, tous les réglages du HVAC reviennent à ceux d'avant »).
 * Un champ `null` n'a pas pu être lu : on n'y touche pas.
 */
data class ClimateSnapshot(
    val powerOn: Boolean?,
    val tempC: Int?,
    /** Niveau de ventilation RÉEL ; `null` en AUTO (la voiture annonce alors 15, qui n'en est pas un). */
    val fan: Int?,
    val auto: Boolean?,
    val ac: Boolean?,
    val loopMode: Int?,
    val airFlow: Int?,
)

/** Une écriture de la clim, dans l'ordre où la faire. */
sealed class ClimateStep {
    data object PowerOn : ClimateStep()
    data object PowerOff : ClimateStep()
    data class Temp(val celsius: Int) : ClimateStep()
    data class Fan(val level: Int) : ClimateStep()
    data object AutoOn : ClimateStep()
    data class Ac(val on: Boolean) : ClimateStep()
    data class Loop(val mode: Int) : ClimateStep()
    data class Air(val direction: Int) : ClimateStep()
}

object PreClimateClimate {

    /**
     * Réglages de la pré-climatisation : marche, puis consigne, ventilation (qui fait sortir
     * d'AUTO) et compresseur — ce dernier une fois la clim lancée (MG4Dash, 2026-10-22 : l'A/C
     * envoyé en même temps que la mise en marche ne prenait pas toujours).
     */
    fun applySteps(req: PreClimateRequest): List<ClimateStep> = listOf(
        ClimateStep.PowerOn, ClimateStep.Temp(req.tempC), ClimateStep.Fan(req.fan), ClimateStep.Ac(req.ac))

    /**
     * Écritures qui rendent à la clim l'état de [snap].
     *
     * Les réglages ne s'écrivent que clim en marche (une consigne envoyée à une clim arrêtée la
     * rallume sur certains firmwares) : si la pré-climatisation a écrit les siens ([presetApplied])
     * et que la clim est arrêtée ([climOn] faux ou inconnu), on la remet en marche le temps de les
     * rétablir, puis on l'arrête si elle l'était — sans quoi elle repartirait, au prochain trajet,
     * sur les réglages de la pré-climatisation.
     *
     * En AUTO, la voiture choisit seule la ventilation et le sens de l'air : on rallume AUTO et on
     * ne réécrit ni l'un ni l'autre (les écrire en ferait sortir).
     */
    fun restoreSteps(snap: ClimateSnapshot, presetApplied: Boolean, climOn: Boolean?): List<ClimateStep> {
        if (!presetApplied) {
            // Rien n'a été réglé : seul l'état marche / arrêt a pu changer (coupée au verrouillage).
            return when {
                snap.powerOn == true && climOn != true -> listOf(ClimateStep.PowerOn)
                snap.powerOn == false && climOn != false -> listOf(ClimateStep.PowerOff)
                else -> emptyList()
            }
        }
        val pas = mutableListOf<ClimateStep>()
        if (climOn != true) pas += ClimateStep.PowerOn
        snap.tempC?.let { pas += ClimateStep.Temp(it) }
        if (snap.auto == true) pas += ClimateStep.AutoOn
        else snap.fan?.let { pas += ClimateStep.Fan(it) }
        snap.ac?.let { pas += ClimateStep.Ac(it) }
        snap.loopMode?.let { pas += ClimateStep.Loop(it) }
        if (snap.auto != true) snap.airFlow?.takeIf { it in 0..6 }?.let { pas += ClimateStep.Air(it) }
        if (snap.powerOn == false) pas += ClimateStep.PowerOff
        return pas
    }
}
