package com.mg4.control.automation

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RuleHistoryEntry
import com.mg4.control.model.RuleLastRun

/**
 * Enregistrement de Mes automatisations (issue #201) : la liste en un blob JSON, et à part le
 * résultat de la dernière exécution de chacune (écrit par le moteur, lu par la liste).
 */
object CustomRuleStore {

    private const val PREFS = "mg4_custom_rules"
    private const val KEY_RULES = "rules_json"
    private const val KEY_RUNS = "last_runs_json"
    const val MAX_RULES = 30

    private val gson = Gson()
    private val typeRegles = object : TypeToken<List<CustomRule>>() {}.type
    private val typeRuns = object : TypeToken<Map<String, RuleLastRun>>() {}.type

    /** Même verrou pour l'écran et le moteur : chaque écriture relit, modifie, réécrit le blob. */
    private val LOCK = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le blob tel qu'enregistré : le moteur ne redécode que s'il a changé. */
    fun rawJson(ctx: Context): String? = prefs(ctx).getString(KEY_RULES, null)

    fun decode(json: String?): List<CustomRule> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching { gson.fromJson<List<CustomRule>>(json, typeRegles) }
            .onFailure { AppLogger.w(CustomRules.TAG, "automatisations illisibles : ${it.message}") }
            .getOrNull().orEmpty()
            .filterNotNull()
    }

    fun encode(rules: List<CustomRule>): String = gson.toJson(rules, typeRegles)

    fun all(ctx: Context): List<CustomRule> = decode(rawJson(ctx))

    private fun write(ctx: Context, rules: List<CustomRule>) {
        prefs(ctx).edit().putString(KEY_RULES, encode(rules)).apply()
    }

    /** Faux quand la liste est pleine ([MAX_RULES]). */
    fun add(ctx: Context, rule: CustomRule): Boolean = synchronized(LOCK) {
        val l = all(ctx)
        if (l.size >= MAX_RULES) return false
        write(ctx, l + rule)
        true
    }

    /**
     * Enregistre [rule] : remplace celle de même id, ou l'ajoute à la fin. Faux quand c'est un
     * ajout et que la liste est pleine.
     */
    fun upsert(ctx: Context, rule: CustomRule): Boolean = synchronized(LOCK) {
        val l = all(ctx)
        val i = l.indexOfFirst { it.id == rule.id }
        if (i >= 0) {
            write(ctx, l.toMutableList().also { it[i] = rule })
            return true
        }
        if (l.size >= MAX_RULES) return false
        write(ctx, l + rule)
        true
    }

    /** La variable [old] renommée [new] dans toutes les automatisations. */
    fun renameVariable(ctx: Context, old: String, new: String) = synchronized(LOCK) {
        write(ctx, all(ctx).map { com.mg4.control.model.RuleLogic.renameVariable(it, old, new) })
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) = synchronized(LOCK) {
        write(ctx, all(ctx).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(ctx: Context, id: String) = synchronized(LOCK) {
        write(ctx, all(ctx).filterNot { it.id == id })
        val runs = lastRuns(ctx) - id
        prefs(ctx).edit().putString(KEY_RUNS, gson.toJson(runs, typeRuns)).apply()
    }

    // ── Interrupteur général ──
    private const val KEY_ENGINE = "engine_enabled"

    fun isEngineEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENGINE, true)
    fun setEngineEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENGINE, on).apply()

    // ── Historique : les [HISTORY_MAX] dernières exécutions, toutes règles ──
    private const val KEY_HISTORY = "history_json"
    const val HISTORY_MAX = 20
    private val typeHistory = object : TypeToken<List<RuleHistoryEntry>>() {}.type

    fun history(ctx: Context): List<RuleHistoryEntry> =
        runCatching { gson.fromJson<List<RuleHistoryEntry>>(prefs(ctx).getString(KEY_HISTORY, null), typeHistory) }
            .getOrNull().orEmpty().filterNotNull()

    fun addHistory(ctx: Context, id: String, name: String, run: RuleLastRun) = synchronized(LOCK) {
        val l = (listOf(RuleHistoryEntry(id, name, run)) + history(ctx)).take(HISTORY_MAX)
        prefs(ctx).edit().putString(KEY_HISTORY, gson.toJson(l, typeHistory)).apply()
    }

    fun lastRuns(ctx: Context): Map<String, RuleLastRun> {
        val json = prefs(ctx).getString(KEY_RUNS, null) ?: return emptyMap()
        return runCatching { gson.fromJson<Map<String, RuleLastRun>>(json, typeRuns) }.getOrNull().orEmpty()
    }

    fun setLastRun(ctx: Context, id: String, run: RuleLastRun) = synchronized(LOCK) {
        // Une règle supprimée pendant son exécution n'a plus de ligne à garder.
        if (all(ctx).none { it.id == id }) return@synchronized
        prefs(ctx).edit().putString(KEY_RUNS, gson.toJson(lastRuns(ctx) + (id to run), typeRuns)).apply()
    }
}
