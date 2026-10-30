package com.mg4.control.util

import android.content.Context
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
    private const val KEY_MAX = "preclim_max_min"
    private const val KEY_FIRST = "preclim_first_minute"
    private const val KEY_RUN = "preclim_run_min"
    private const val KEY_RUNS = "preclim_runs"
    private const val KEY_PAUSE = "preclim_pause_min"
    private const val KEY_TEMP = "preclim_temp"
    private const val KEY_FAN = "preclim_fan"
    private const val KEY_DOG = "preclim_dog"
    private const val KEY_SURV = "preclim_surveillance"
    /** Fiche sur l'écran conducteur pendant le programme (2026-10-23) : activée par défaut. */
    private const val KEY_CLUSTER = "preclim_cluster"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    /** Réglage seul : la fiche ne s'affiche en plus que sur les firmwares du module (`ClusterModuleSettings.isSupported`). */
    fun showOnCluster(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CLUSTER, true)
    fun setShowOnCluster(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_CLUSTER, on).apply()

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
            firstMinuteOfDay = p.getInt(KEY_FIRST, -1).takeIf { it in 0 until 1440 }
                ?: PreClimatePlan.defaultFirst(nowMinuteOfDay),
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
            .putInt(KEY_MAX, r.maxMin)
            .putInt(KEY_FIRST, r.firstMinuteOfDay)
            .putInt(KEY_RUN, r.runMin)
            .putInt(KEY_RUNS, r.runs)
            .putInt(KEY_PAUSE, r.pauseMin)
            .putInt(KEY_TEMP, r.tempC)
            .putInt(KEY_FAN, r.fan)
            .putBoolean(KEY_DOG, r.dog)
            .putBoolean(KEY_SURV, r.surveillance)
            .apply()
    }

    private const val DEFAULT_TEMP = 21
    private const val DEFAULT_FAN = 4
}
