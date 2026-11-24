package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.CampingConfig
import com.mg4.control.model.CampingPlan
import com.mg4.control.model.CampingScreen
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateRequest
import com.mg4.control.model.PreClimateScreenMode

/**
 * Pré-climatisation (issue #130) : réglages de sa page dans l'onglet Raccourcis (décompte avant
 * verrouillage, seuil de batterie, écran pendant l'attente) et derniers choix du pop-up.
 *
 * Le pop-up s'ouvre TOUJOURS sur « Maintenant » (demande du 2026-10-23) : le mode n'est pas
 * mémorisé, le reste l'est. L'A/C est activé la première fois : on veut rafraîchir ou réchauffer.
 */
object PreClimateSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_LOCK = "preclim_lock_s"
    private const val KEY_SOC = "preclim_min_soc"
    /** Ancien réglage à deux choix (atténué / éteint), relu une fois pour la migration. */
    private const val KEY_SCREEN_OFF = "preclim_screen_off"
    private const val KEY_SCREEN_MODE = "preclim_screen_mode"
    private const val KEY_SURV_SCREEN = "preclim_surv_follows_screen"
    private const val KEY_SURV_NOCAM = "preclim_surv_without_camera"
    private const val KEY_MAX = "preclim_max_min"
    private const val KEY_RUN = "preclim_run_min"
    private const val KEY_RUNS = "preclim_runs"
    private const val KEY_PAUSE = "preclim_pause_min"
    private const val KEY_TEMP = "preclim_temp"
    private const val KEY_FAN = "preclim_fan"
    private const val KEY_DOG = "preclim_dog"
    private const val KEY_SURV = "preclim_surveillance"
    /** Fiche sur l'écran conducteur pendant le programme (2026-10-23) : activée par défaut. */
    private const val KEY_CLUSTER = "preclim_cluster"
    /** Rappel « gardez la clé sur vous » avant le décompte (2026-10-31) : activé par défaut. */
    private const val KEY_UNLOCK = "preclim_unlock_reminder"
    /** Surveillance : demander à l'appli caméra de filmer en continu (2026-11-08) : activé par défaut. */
    private const val KEY_DASHCAM_AUTO = "preclim_dashcam_auto"

    /** Mode camping (2026-11-24) : ses réglages, retenus pour la nuit suivante. */
    private const val KEY_CAMP_DELAY = "preclim_camp_delay_s"
    private const val KEY_CAMP_LOCK = "preclim_camp_lock"
    private const val KEY_CAMP_SOC = "preclim_camp_min_soc"
    private const val KEY_CAMP_RECORD = "preclim_camp_record"
    private const val KEY_CAMP_SCREEN = "preclim_camp_screen"
    private const val KEY_CAMP_MSG_CAM = "preclim_camp_msg_camera"
    private const val KEY_CAMP_MSG_ECALL = "preclim_camp_msg_ecall"
    private const val KEY_CAMP_TEMP = "preclim_camp_temp"
    private const val KEY_CAMP_FAN = "preclim_camp_fan"
    private const val KEY_CAMP_AC = "preclim_camp_ac"
    private const val KEY_CAMP_RECIRC = "preclim_camp_recirc_inner"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Réglages du mode camping ; jamais vus : ceux de [CampingConfig] par défaut. */
    fun camping(ctx: Context): CampingConfig {
        val p = prefs(ctx)
        val d = CampingConfig()
        return CampingConfig(
            delayS = CampingPlan.clampDelay(p.getInt(KEY_CAMP_DELAY, d.delayS)),
            lockDoors = p.getBoolean(KEY_CAMP_LOCK, d.lockDoors),
            minSoc = CampingPlan.clampSoc(p.getInt(KEY_CAMP_SOC, d.minSoc)),
            record = p.getBoolean(KEY_CAMP_RECORD, d.record),
            screen = CampingScreen.entries.firstOrNull { it.name == p.getString(KEY_CAMP_SCREEN, null) } ?: d.screen,
            msgCamera = p.getBoolean(KEY_CAMP_MSG_CAM, d.msgCamera),
            msgEcall = p.getBoolean(KEY_CAMP_MSG_ECALL, d.msgEcall),
            tempC = p.getInt(KEY_CAMP_TEMP, d.tempC),
            fan = p.getInt(KEY_CAMP_FAN, d.fan),
            ac = p.getBoolean(KEY_CAMP_AC, d.ac),
            recircInner = p.getBoolean(KEY_CAMP_RECIRC, d.recircInner),
        )
    }

    fun saveCamping(ctx: Context, c: CampingConfig) {
        prefs(ctx).edit()
            .putInt(KEY_CAMP_DELAY, CampingPlan.clampDelay(c.delayS))
            .putBoolean(KEY_CAMP_LOCK, c.lockDoors)
            .putInt(KEY_CAMP_SOC, CampingPlan.clampSoc(c.minSoc))
            .putBoolean(KEY_CAMP_RECORD, c.record)
            .putString(KEY_CAMP_SCREEN, c.screen.name)
            .putBoolean(KEY_CAMP_MSG_CAM, c.msgCamera)
            .putBoolean(KEY_CAMP_MSG_ECALL, c.msgEcall)
            .putInt(KEY_CAMP_TEMP, c.tempC)
            .putInt(KEY_CAMP_FAN, c.fan)
            .putBoolean(KEY_CAMP_AC, c.ac)
            .putBoolean(KEY_CAMP_RECIRC, c.recircInner)
            .apply()
    }

    fun lockSeconds(ctx: Context): Int = PreClimatePlan.clampLock(prefs(ctx).getInt(KEY_LOCK, PreClimatePlan.DEFAULT_LOCK_S))
    fun setLockSeconds(ctx: Context, s: Int) = prefs(ctx).edit().putInt(KEY_LOCK, PreClimatePlan.clampLock(s)).apply()

    fun minSoc(ctx: Context): Int = PreClimatePlan.clampSoc(prefs(ctx).getInt(KEY_SOC, PreClimatePlan.DEFAULT_SOC))
    fun setMinSoc(ctx: Context, pct: Int) = prefs(ctx).edit().putInt(KEY_SOC, PreClimatePlan.clampSoc(pct)).apply()

    /** Écran pendant l'attente : atténué par défaut (et « éteint » pour qui l'avait choisi avant). */
    fun screenMode(ctx: Context): PreClimateScreenMode {
        val p = prefs(ctx)
        val nom = p.getString(KEY_SCREEN_MODE, null)
            ?: return if (p.getBoolean(KEY_SCREEN_OFF, false)) PreClimateScreenMode.OFF else PreClimateScreenMode.DIM
        return PreClimateScreenMode.entries.firstOrNull { it.name == nom } ?: PreClimateScreenMode.DIM
    }

    fun setScreenMode(ctx: Context, mode: PreClimateScreenMode) =
        prefs(ctx).edit().putString(KEY_SCREEN_MODE, mode.name).apply()

    /**
     * La surveillance suit-elle [screenMode] ? Non par défaut (2026-11-13) : l'écran rouge reste
     * allumé pour dissuader les passants. Le mode chien, lui, reste toujours allumé.
     */
    fun survFollowsScreen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SURV_SCREEN, false)
    fun setSurvFollowsScreen(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_SURV_SCREEN, on).apply()

    /**
     * Surveillance permise sans appli dashcam (2026-11-13) : écran d'avertissement et clim coupée,
     * aucune vidéo. Non par défaut ; activé seulement après l'avertissement de la page Garde-voiture.
     */
    fun survWithoutCamera(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SURV_NOCAM, false)
    fun setSurvWithoutCamera(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_SURV_NOCAM, on).apply()

    /** Réglage seul : la fiche ne s'affiche en plus que sur les firmwares du module (`ClusterModuleSettings.isSupported`). */
    fun showOnCluster(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CLUSTER, true)
    fun setShowOnCluster(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_CLUSTER, on).apply()

    /** Coupé par « Ne plus me rappeler », rallumé par l'interrupteur de la page Garde-voiture. */
    fun unlockReminder(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNLOCK, true)
    fun setUnlockReminder(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_UNLOCK, on).apply()

    /** Interrupteur « Lancer l'enregistrement automatiquement » de la carte caméra (issue #180). */
    fun dashcamAuto(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DASHCAM_AUTO, true)
    fun setDashcamAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_DASHCAM_AUTO, on).apply()

    /**
     * Derniers choix du pop-up, mode « Maintenant ». Jamais vus : durée 1 h, premier cycle dans une
     * heure ([nowMinuteOfDay]), température et ventilation de la clim actuelle ([tempNow],
     * [fanNow]) ou, illisibles, 21 °C et 4.
     */
    fun lastRequest(ctx: Context, nowMinuteOfDay: Double, tempNow: Int?, fanNow: Int?): PreClimateRequest {
        val p = prefs(ctx)
        return PreClimateRequest(
            mode = PreClimateMode.NOW,
            maxMin = PreClimatePlan.clampMax(p.getInt(KEY_MAX, PreClimatePlan.DEFAULT_MAX_MIN)),
            // L'heure part toujours de maintenant (issue #262) : elle n'est plus reprise d'une fois sur l'autre.
            firstMinuteOfDay = PreClimatePlan.defaultFirst(nowMinuteOfDay),
            runMin = PreClimatePlan.clampRun(p.getInt(KEY_RUN, PreClimatePlan.DEFAULT_RUN_MIN)),
            runs = PreClimatePlan.clampRuns(p.getInt(KEY_RUNS, PreClimatePlan.DEFAULT_RUNS)),
            pauseMin = PreClimatePlan.clampPause(p.getInt(KEY_PAUSE, PreClimatePlan.DEFAULT_PAUSE_MIN)),
            tempC = p.getInt(KEY_TEMP, -1).takeIf { it > 0 } ?: tempNow ?: DEFAULT_TEMP,
            fan = p.getInt(KEY_FAN, -1).takeIf { it > 0 } ?: fanNow ?: DEFAULT_FAN,
            dog = p.getBoolean(KEY_DOG, false),
            surveillance = p.getBoolean(KEY_SURV, false),
        )
    }

    fun saveRequest(ctx: Context, r: PreClimateRequest) {
        prefs(ctx).edit()
            .putInt(KEY_MAX, PreClimatePlan.clampMax(r.maxMin))
            .putInt(KEY_RUN, r.runMin)
            .putInt(KEY_RUNS, r.runs)
            .putInt(KEY_PAUSE, r.pauseMin)
            .putInt(KEY_TEMP, r.tempC)
            .putInt(KEY_FAN, r.fan)
            .putBoolean(KEY_DOG, r.dog)
            .putBoolean(KEY_SURV, r.surveillance)
            .apply()
        // Le choix du camping n'est jamais retenu (tuile en READY seulement) ; ses réglages, si.
        r.camping?.let { saveCamping(ctx, it) }
    }

    private const val DEFAULT_TEMP = 21
    private const val DEFAULT_FAN = 4
}
