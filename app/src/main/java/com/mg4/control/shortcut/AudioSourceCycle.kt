package com.mg4.control.shortcut

import android.content.Context
import androidx.annotation.StringRes
import com.mg4.control.R

/** Sources audio que parcourt le raccourci « Cycle de sources audio » (2026-10-15). */
enum class AudioSource(val key: String, @StringRes val label: Int) {
    RADIO("radio", R.string.audio_source_radio),
    BLUETOOTH("bluetooth", R.string.audio_source_bluetooth),
    USB("usb", R.string.audio_source_usb),
    ONLINE("online", R.string.audio_source_online),
    /** CarPlay et Android Auto : une seule voie, celle de la projection en cours. */
    PROJECTION("projection", R.string.audio_source_projection);

    companion object {
        fun fromKey(key: String?): AudioSource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Cycle de sources audio — les sources que parcourt le raccourci du même nom, dans l'ordre choisi
 * par l'utilisateur (page « Cycle sources » de l'onglet Raccourcis). Calqué sur [ProfileCycle].
 *
 * Choix de l'utilisateur (2026-10-15) : on NE VÉRIFIE PAS qu'une source est disponible. Une source
 * sans rien à jouer (pas de clé USB, pas de téléphone) est visée quand même ; l'appui suivant
 * passe à la suivante. C'est aussi pourquoi on repart de la DERNIÈRE SOURCE CHOISIE par le
 * raccourci, et non de celle qui joue : partir de la source audible coincerait le cycle sur une
 * source indisponible (elle ne prend pas la main, on repartirait toujours d'avant elle).
 */
object AudioSourceCycle {

    /** Même fichier que le reste des raccourcis — un seul endroit à sauvegarder. */
    private const val PREFS    = "mg4_shortcuts"
    private const val KEY      = "shortcut_audio_cycle_order"
    private const val KEY_LAST = "shortcut_audio_cycle_last"

    /** Sous deux sources il n'y a plus de cycle : l'écran refuse d'enregistrer. */
    const val MIN_SOURCES = 2

    /** Délai sans nouvel appui avant de changer de source : deux appuis rapprochés en sautent une. */
    const val APPLY_DELAY_MS = 1_000L

    /**
     * Cycle d'un raccourci attribué mais jamais composé : radio et Bluetooth, les deux sources
     * que presque tout le monde a. « Toutes » ferait des appuis dans le vide (USB, en ligne).
     */
    val DEFAULT = listOf(AudioSource.RADIO, AudioSource.BLUETOOTH)

    // ── La règle, sans Android ───────────────────────────────────────────────

    /** Cycle enregistré, ou [DEFAULT] s'il n'y en a pas — ou s'il ne compte pas deux sources connues. */
    fun resolve(enregistre: String?): List<AudioSource> {
        val choisies = enregistre.orEmpty().split(',')
            .mapNotNull { AudioSource.fromKey(it.trim()) }
            .distinct()
        return if (choisies.size >= MIN_SOURCES) choisies else DEFAULT
    }

    fun encode(ordre: List<AudioSource>): String = ordre.joinToString(",") { it.key }

    /**
     * Source qui suit [depuis] dans [cycle], en rebouclant. Une source absente du cycle — ou aucune,
     * au tout premier appui — fait entrer par la première. `null` seulement si le cycle est vide.
     */
    fun next(cycle: List<AudioSource>, depuis: AudioSource?): AudioSource? {
        if (cycle.isEmpty()) return null
        val rang = cycle.indexOf(depuis)
        return if (rang < 0) cycle.first() else cycle[(rang + 1) % cycle.size]
    }

    /** Source visée par un appui : tant qu'une source attend d'être appliquée, on repart d'elle. */
    fun target(cycle: List<AudioSource>, derniere: AudioSource?, enAttente: AudioSource?): AudioSource? =
        next(cycle, enAttente ?: derniere)

    // ── Le réglage enregistré ────────────────────────────────────────────────

    fun order(ctx: Context): List<AudioSource> = resolve(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, ordre: List<AudioSource>) {
        prefs(ctx).edit().putString(KEY, encode(ordre)).apply()
    }

    /** Dernière source choisie par le raccourci, d'où repart l'appui suivant. */
    fun last(ctx: Context): AudioSource? = AudioSource.fromKey(prefs(ctx).getString(KEY_LAST, null))

    fun setLast(ctx: Context, source: AudioSource) {
        prefs(ctx).edit().putString(KEY_LAST, source.key).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
