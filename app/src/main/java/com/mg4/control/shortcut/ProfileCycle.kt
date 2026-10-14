package com.mg4.control.shortcut

import android.content.Context

/**
 * Cycle de profils — les profils que parcourt le raccourci du même nom, dans l'ordre choisi par
 * l'utilisateur.
 *
 * Réglage GLOBAL, comme [RegenCycle] et pour la même raison : deux boutons qui déclenchent le
 * raccourci doivent parcourir la même séquence, puisque le cycle repart toujours du profil actif.
 *
 * Les profils sont désignés par leur IDENTIFIANT : renommer un profil ne le sort pas du cycle.
 * Rien n'est écrit tant que l'utilisateur n'a rien composé — l'absence de clé signifie « tous les
 * profils, dans l'ordre de leur liste », et un profil créé ensuite y entre de lui-même.
 */
object ProfileCycle {

    /** Même fichier que le reste des raccourcis — un seul endroit à sauvegarder. */
    private const val PREFS = "mg4_shortcuts"
    private const val KEY   = "shortcut_profile_cycle_order"

    /** Sous deux profils il n'y a plus de cycle : l'écran refuse d'enregistrer. */
    const val MIN_PROFILES = 2

    /**
     * Délai sans nouvel appui avant d'appliquer le profil visé. Appliquer un profil écrit une
     * série de réglages sur la voiture : sans ce délai, passer du premier au troisième profil
     * appliquerait le deuxième au passage.
     */
    const val APPLY_DELAY_MS = 1_000L

    /** Longueur d'un nom sur son bouton, points de suspension compris. */
    const val LABEL_MAX = 16

    // ── La règle, sans Android ───────────────────────────────────────────────

    /**
     * Cycle à parcourir : le réglage [enregistre] confronté aux profils qui [existants] encore.
     *
     *  - aucun réglage → tous les profils, dans l'ordre de leur liste ;
     *  - un profil supprimé sort du cycle, les autres gardent leur rang ;
     *  - s'il n'en reste qu'UN, le cycle est ce profil seul : le compléter appliquerait des
     *    profils que l'utilisateur avait laissés dehors ;
     *  - s'il n'en reste AUCUN, le réglage ne désigne plus rien et vaut « aucun réglage ».
     */
    fun resolve(enregistre: String?, existants: List<String>): List<String> {
        val choisis = enregistre.orEmpty().split(',')
            .map { it.trim() }
            .filter { it in existants }
            .distinct()
        return choisis.ifEmpty { existants }
    }

    /** Forme enregistrée d'un cycle — les identifiants de profil ne contiennent pas de virgule. */
    fun encode(ordre: List<String>): String = ordre.joinToString(",")

    /**
     * Profil qui suit [depuis] dans [cycle], en rebouclant. Un profil absent du cycle — appliqué
     * par Bluetooth, par une automatisation, ou aucun profil appliqué — fait entrer par le
     * premier : sur une touche de volant, ne rien faire passerait pour une panne.
     *
     * `null` seulement s'il n'existe aucun profil.
     */
    fun next(cycle: List<String>, depuis: String?): String? {
        if (cycle.isEmpty()) return null
        val rang = cycle.indexOf(depuis)
        return if (rang < 0) cycle.first() else cycle[(rang + 1) % cycle.size]
    }

    /**
     * Profil visé par un appui. Tant qu'un profil est [enAttente] d'application (voir
     * [APPLY_DELAY_MS]), le profil [actif] n'a pas encore changé : on repart du profil visé,
     * sinon deux appuis rapprochés viseraient deux fois le même.
     */
    fun target(cycle: List<String>, actif: String?, enAttente: String?): String? =
        next(cycle, enAttente ?: actif)

    /**
     * Nom d'un profil tel qu'il tient sur son bouton. Un nom fait jusqu'à 30 caractères ; le
     * bouton n'a que deux lignes et la seconde porte le rang, qui est tout l'objet de l'écran.
     */
    fun label(nom: String): String {
        val net = nom.trim()
        return if (net.length <= LABEL_MAX) net else net.take(LABEL_MAX - 1).trimEnd() + "\u2026"
    }

    // ── Le réglage enregistré ────────────────────────────────────────────────

    /** Cycle en vigueur, relu à chaque appui : le réglage vit dans un autre écran. */
    fun order(ctx: Context, existants: List<String>): List<String> =
        resolve(prefs(ctx).getString(KEY, null), existants)

    fun save(ctx: Context, ordre: List<String>) {
        prefs(ctx).edit().putString(KEY, encode(ordre)).apply()
    }

    /** Revient à « tous les profils » en RETIRANT la clé, comme [RegenCycle.reset]. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
