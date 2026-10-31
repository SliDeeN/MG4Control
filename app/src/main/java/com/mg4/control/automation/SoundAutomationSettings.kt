package com.mg4.control.automation

import android.content.Context
import android.content.SharedPreferences
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundSlot
import java.io.File

/**
 * Sons d'automatisation (issue #161) : interrupteur général, la liste des sons (fichier copié dans
 * l'appli, titre, durée, volume, événements) et les profils pour lesquels ils jouent. Désactivé
 * par défaut ; aucun profil choisi = tous.
 *
 * Depuis le 2026-10-29 la liste a une longueur ([count], clé `sound_count`) : avant, il y avait
 * toujours deux emplacements. Les clés d'un son restent `sound_<rang>_…`.
 */
object SoundAutomationSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_ENABLED = "sound_enabled"
    private const val KEY_PROFILES = "sound_profiles"
    private const val KEY_COUNT = "sound_count"
    /** Les deux emplacements fixes d'avant la liste. */
    private const val ANCIENS_EMPLACEMENTS = 2
    private val CHAMPS = listOf("path", "title", "duration", "volume", "events")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun cle(index: Int, champ: String) = "sound_${index + 1}_$champ"

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun slots(ctx: Context): List<SoundSlot> = (0 until count(ctx)).map { slot(ctx, it) }

    /**
     * Nombre de sons. Première lecture après la mise à jour : les deux anciens emplacements qui
     * portaient quelque chose (fichier ou événements) deviennent la liste, dans leur ordre.
     */
    fun count(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains(KEY_COUNT)) return p.getInt(KEY_COUNT, 0).coerceIn(0, SoundAutomationRules.MAX_SOUNDS)
        val gardes = (0 until ANCIENS_EMPLACEMENTS).map { slot(ctx, it) }.filter { it.hasFile || it.events.isNotEmpty() }
        val e = p.edit()
        (0 until ANCIENS_EMPLACEMENTS).forEach { effacer(e, it) }
        gardes.forEachIndexed { i, s -> ecrire(e, i, s) }
        e.putInt(KEY_COUNT, gardes.size).apply()
        return gardes.size
    }

    /** Nouveau son en fin de liste ; son rang, ou `null` si la liste a déjà [max] sons. */
    fun ajouter(ctx: Context, s: SoundSlot, max: Int): Int? {
        val n = count(ctx)
        if (n >= max) return null
        prefs(ctx).edit().also { ecrire(it, n, s) }.putInt(KEY_COUNT, n + 1).apply()
        return n
    }

    /** Retire le son [index] de la liste : les suivants remontent d'un rang. Le fichier reste à effacer. */
    fun supprimer(ctx: Context, index: Int) {
        val sons = slots(ctx)
        if (index !in sons.indices) return
        val e = prefs(ctx).edit()
        sons.indices.forEach { effacer(e, it) }
        sons.filterIndexed { i, _ -> i != index }.forEachIndexed { i, s -> ecrire(e, i, s) }
        e.putInt(KEY_COUNT, sons.size - 1).apply()
    }

    fun slot(ctx: Context, index: Int): SoundSlot {
        val p = prefs(ctx)
        // Un fichier disparu (données effacées) compte comme absent.
        val chemin = p.getString(cle(index, "path"), null)?.takeIf { File(it).isFile }
        return SoundSlot(
            path = chemin,
            title = p.getString(cle(index, "title"), null),
            durationMs = p.getLong(cle(index, "duration"), 0L),
            volume = p.getInt(cle(index, "volume"), -1).takeIf { it >= 0 },
            events = SoundAutomationRules.parseEvents(p.getString(cle(index, "events"), null)),
        )
    }

    fun saveSlot(ctx: Context, index: Int, s: SoundSlot) {
        prefs(ctx).edit().also { ecrire(it, index, s) }.apply()
    }

    private fun ecrire(e: SharedPreferences.Editor, index: Int, s: SoundSlot) {
        e.putString(cle(index, "path"), s.path)
            .putString(cle(index, "title"), s.title)
            .putLong(cle(index, "duration"), s.durationMs)
            .putInt(cle(index, "volume"), s.volume ?: -1)
            .putString(cle(index, "events"), SoundAutomationRules.eventsCsv(s.events))
    }

    private fun effacer(e: SharedPreferences.Editor, index: Int) {
        CHAMPS.forEach { e.remove(cle(index, it)) }
    }

    fun profiles(ctx: Context): Set<String> =
        prefs(ctx).getString(KEY_PROFILES, null).orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun setProfiles(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putString(KEY_PROFILES, ids.joinToString(",")).apply()

    /** Dossier des sons importés, dans le stockage de l'appli : la clé USB peut être retirée. */
    fun dossier(ctx: Context): File = File(ctx.filesDir, "sounds").apply { mkdirs() }
}
