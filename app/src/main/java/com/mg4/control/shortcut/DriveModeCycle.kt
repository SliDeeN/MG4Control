package com.mg4.control.shortcut

import android.content.Context
import com.mg4.control.model.DriveMode

/**
 * Ordre du cycle des modes de conduite (issue #207), tel que l'utilisateur l'a composé — le
 * pendant de [RegenCycle] : le bouton d'origine fait tout le tour des modes, ce raccourci ne
 * parcourt que ceux qu'on a choisis, dans l'ordre choisi.
 *
 * Réglage GLOBAL, comme celui de la régénération, et pour la même raison : le cycle repart du mode
 * réellement lu sur la voiture, deux cycles différents sur deux boutons se contrediraient.
 * Les cinq modes partout, Personnalisé compris (il marche sur tous les firmwares). Utilisable en
 * roulant, comme le bouton de la voiture (décision du 2026-11-03).
 *
 * Rien n'est écrit tant que l'utilisateur n'a rien composé : sans clé, [DEFAUT].
 */
object DriveModeCycle {

    /** Même fichier que le reste des raccourcis — un seul endroit à sauvegarder. */
    private const val PREFS = "mg4_shortcuts"
    private const val KEY   = "shortcut_drive_mode_cycle_order"

    /** Sous deux modes, le raccourci ne ferait qu'imposer un mode fixe : plus un cycle. */
    const val MIN_MODES = 2

    /** Les modes proposés, dans l'ordre de l'écran Conduite. */
    val OFFERTS = listOf(DriveMode.ECO, DriveMode.NORMAL, DriveMode.SPORT, DriveMode.SNOW, DriveMode.CUSTOM)

    /** Sans composition : les trois modes de tous les jours. */
    val DEFAUT = listOf(DriveMode.ECO, DriveMode.NORMAL, DriveMode.SPORT)

    fun order(ctx: Context): List<DriveMode> = resolve(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, ordre: List<DriveMode>) {
        prefs(ctx).edit().putString(KEY, encode(ordre)).apply()
    }

    fun encode(ordre: List<DriveMode>): String = ordre.joinToString(",") { it.value.toString() }

    /**
     * Séquence lue, toujours exploitable : valeur illisible, mode inconnu ou doublon écartés, et
     * [DEFAUT] si ce qui reste ne fait plus un cycle.
     */
    fun resolve(brut: String?): List<DriveMode> {
        if (brut.isNullOrBlank()) return DEFAUT
        val lus = brut.split(',').mapNotNull { morceau ->
            morceau.trim().toIntOrNull()?.let { v -> OFFERTS.firstOrNull { it.value == v } }
        }.distinct()
        return if (lus.size >= MIN_MODES) lus else DEFAUT
    }

    /**
     * Mode suivant de [cycle] après [actuel]. Mode actuel hors du cycle — ou illisible — : le
     * premier du cycle ; après le dernier, le premier. Null seulement pour un cycle vide.
     */
    fun next(cycle: List<DriveMode>, actuel: DriveMode?): DriveMode? {
        if (cycle.isEmpty()) return null
        val i = cycle.indexOf(actuel)
        return if (i < 0) cycle.first() else cycle[(i + 1) % cycle.size]
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
