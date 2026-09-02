package com.mg4.control.shortcut

import android.content.Context
import com.mg4.control.model.RegenLevel

/**
 * Ordre du cycle de régénération, tel que l'utilisateur l'a composé.
 *
 * Réglage GLOBAL et non par emplacement : la même touche, qu'elle vienne des raccourcis
 * classiques ou des avancés, doit parcourir la même séquence. Deux cycles concurrents sur deux
 * boutons se contrediraient au premier appui, puisque le cycle repart toujours du niveau
 * réellement lu sur le véhicule.
 *
 * Rien n'est écrit tant que l'utilisateur n'a rien composé : l'absence de clé signifie
 * « comportement d'origine », soit [RegenLevel.CYCLE_ORDER]. C'est ce qui garantit qu'une mise à
 * jour ne change le cycle de personne.
 */
object RegenCycle {

    /** Même fichier que le reste des raccourcis — un seul endroit à sauvegarder. */
    private const val PREFS = "mg4_shortcuts"
    private const val KEY   = "shortcut_regen_cycle_order"

    /**
     * En dessous de deux modes, il n'y a plus de cycle mais une consigne fixe : le premier appui
     * agirait, tous les suivants seraient sans effet, et le raccourci passerait pour cassé.
     */
    const val MIN_MODES = 2

    /**
     * Séquence à parcourir. Toujours exploitable : une valeur illisible, un mode inconnu ou un
     * cycle trop court retombent sur l'ordre d'origine plutôt que de rendre le raccourci muet.
     */
    fun order(ctx: Context): List<RegenLevel> {
        val brut = prefs(ctx).getString(KEY, null) ?: return RegenLevel.CYCLE_ORDER
        // On ne passe PAS par RegenLevel.fromValue : elle rend MEDIUM pour toute valeur inconnue,
        // ce qui transformerait une préférence corrompue en cycle plausible mais faux.
        val lus = brut.split(',').mapNotNull { morceau ->
            morceau.trim().toIntOrNull()?.let { v ->
                RegenLevel.CYCLE_SELECTABLE.firstOrNull { it.value == v }
            }
        }.distinct()
        return if (lus.size >= MIN_MODES) lus else RegenLevel.CYCLE_ORDER
    }

    fun save(ctx: Context, ordre: List<RegenLevel>) {
        prefs(ctx).edit().putString(KEY, ordre.joinToString(",") { it.value.toString() }).apply()
    }

    /** Revient au comportement d'origine en RETIRANT la clé, pas en écrivant l'ordre par défaut :
     *  le réglage suivra ainsi une éventuelle évolution de [RegenLevel.CYCLE_ORDER]. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
