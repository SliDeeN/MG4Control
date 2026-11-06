package com.mg4.control.automation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.RuleSound
import java.io.File

/**
 * Sons importés depuis l'éditeur de Mes automatisations (2026-11-06) : à part des sons de la page
 * Sons (qui ont chacun leurs moments), sans plafond lié aux événements. Fichiers dans
 * `files/rule_sounds`, liste dans les réglages privés `mg4_rule_sounds`.
 */
object RuleSounds {

    private const val TAG = "MG4_RULES"
    private const val PREFS = "mg4_rule_sounds"
    private const val KEY = "sounds_json"
    const val MAX = 12

    private val gson = Gson()
    private val type = object : TypeToken<List<RuleSound>>() {}.type
    private val LOCK = Any()

    private fun dossier(ctx: Context) = File(ctx.filesDir, "rule_sounds").apply { mkdirs() }

    fun all(ctx: Context): List<RuleSound> =
        runCatching { gson.fromJson<List<RuleSound>>(ctx.getSharedPreferences(PREFS, 0).getString(KEY, null), type) }
            .getOrNull().orEmpty().filterNotNull()

    fun get(ctx: Context, id: String): RuleSound? = all(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, l: List<RuleSound>) =
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, gson.toJson(l, type)).apply()

    /** Copie [f] dans l'appli ; le son ajouté, ou `null` (liste pleine, copie impossible). Hors fil principal. */
    fun importer(ctx: Context, f: SoundImport.Fichier): RuleSound? = synchronized(LOCK) {
        val l = all(ctx)
        if (l.size >= MAX) return null
        return try {
            val cible = File(dossier(ctx), "son_${System.currentTimeMillis()}.${f.file.extension.lowercase()}")
            f.file.inputStream().use { e -> cible.outputStream().use { e.copyTo(it) } }
            val s = RuleSound(title = f.file.name, path = cible.path, durationMs = f.durationMs)
            save(ctx, l + s)
            AppLogger.i(TAG, "son d'automatisation importé : ${f.file.name} (${f.durationMs} ms)")
            s
        } catch (e: Exception) {
            AppLogger.w(TAG, "import du son ${f.file.name} impossible : ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    fun delete(ctx: Context, id: String) = synchronized(LOCK) {
        val l = all(ctx)
        l.firstOrNull { it.id == id }?.path?.let { File(it).delete() }
        save(ctx, l.filterNot { it.id == id })
    }

    // ── Bip intégré ──────────────────────────────────────────────────────────

    private val principal = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private val liberer = Runnable { tone?.release(); tone = null }

    /** Un bip court, sur le flux des notifications, à [volumePct] % (10–100). */
    fun bip(volumePct: Int) {
        principal.post {
            runCatching {
                principal.removeCallbacks(liberer)
                tone?.release()
                tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volumePct.coerceIn(10, 100))
                    .also { it.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
                principal.postDelayed(liberer, 1_000L)
            }.onFailure { AppLogger.w(TAG, "bip impossible : ${it.javaClass.simpleName} ${it.message}") }
        }
    }
}
