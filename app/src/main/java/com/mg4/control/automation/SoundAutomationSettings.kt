package com.mg4.control.automation

import android.content.Context
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundSlot
import java.io.File

/**
 * Sons d'automatisation (issue #161) : interrupteur général, les deux sons (fichier copié dans
 * l'appli, titre, durée, volume, événements) et les profils pour lesquels ils jouent. Désactivé
 * par défaut ; aucun profil choisi = tous.
 */
object SoundAutomationSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_ENABLED = "sound_enabled"
    private const val KEY_PROFILES = "sound_profiles"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun cle(index: Int, champ: String) = "sound_${index + 1}_$champ"

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun slots(ctx: Context): List<SoundSlot> = (0 until SoundAutomationRules.SLOTS).map { slot(ctx, it) }

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
        prefs(ctx).edit()
            .putString(cle(index, "path"), s.path)
            .putString(cle(index, "title"), s.title)
            .putLong(cle(index, "duration"), s.durationMs)
            .putInt(cle(index, "volume"), s.volume ?: -1)
            .putString(cle(index, "events"), SoundAutomationRules.eventsCsv(s.events))
            .apply()
    }

    fun profiles(ctx: Context): Set<String> =
        prefs(ctx).getString(KEY_PROFILES, null).orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun setProfiles(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putString(KEY_PROFILES, ids.joinToString(",")).apply()

    /** Dossier des sons importés, dans le stockage de l'appli : la clé USB peut être retirée. */
    fun dossier(ctx: Context): File = File(ctx.filesDir, "sounds").apply { mkdirs() }
}
