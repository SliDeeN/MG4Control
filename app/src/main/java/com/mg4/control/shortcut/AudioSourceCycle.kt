package com.mg4.control.shortcut

import android.content.Context
import androidx.annotation.StringRes
import com.mg4.control.R
import com.mg4.control.util.FirmwareInfo

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

        /**
         * Source d'un code de source voiture, ou `null` s'il n'est pas une de nos sources.
         * Même espace de codes sur tous les firmwares : `getCurrentMediaSource` du service média
         * SAIC (ancien SDK, relevés SWI133 et SWI68) et `AudioManager.getLastSource()` du système
         * (A9, décompilé) — 1 FM, 2 AM, 3 DAB (4 tenu pour radio), 5 Bluetooth, 6 et 7 USB
         * (port 1 et 2), 14 en ligne, 50 CarPlay, 70 Android Auto (DiAuto-MG4 compris).
         */
        fun fromCarCode(code: Int): AudioSource? = when (code) {
            in 1..4 -> RADIO
            5 -> BLUETOOTH
            6, 7 -> USB
            0xe -> ONLINE
            0x32, 0x46 -> PROJECTION
            else -> null
        }
    }
}

/**
 * Cycle de sources audio — les sources que parcourt le raccourci du même nom, dans l'ordre choisi
 * par l'utilisateur (page « Cycle sources » de l'onglet Raccourcis). Calqué sur [ProfileCycle].
 *
 * Choix de l'utilisateur (2026-10-15) : on NE VÉRIFIE PAS qu'une source est disponible. Une source
 * sans rien à jouer (pas de clé USB, pas de téléphone) est visée quand même ; l'appui suivant
 * passe à la suivante.
 *
 * Point de départ (2026-10-16, à sa demande) : la source qui JOUE, pour qu'un changement fait
 * ailleurs (écran, touche d'origine) soit suivi. Sauf quand notre dernière bascule n'a pas pris :
 * partir de la source audible coincerait alors le cycle sur la source indisponible (elle ne prend
 * pas la main, on repartirait toujours d'avant elle) — on repart de la DERNIÈRE SOURCE CHOISIE,
 * comme avant. Voir [depart].
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

    /**
     * Sources proposées sur le firmware. La musique en ligne n'existe pas sur les A9 (SWI69, 131,
     * 132 — constat de l'utilisateur, 2026-10-15) : elle n'y est ni proposée ni visée. C'est la
     * seule exception au principe de ne pas vérifier la disponibilité : là, la source n'existe pas.
     */
    fun offertes(a9: Boolean): List<AudioSource> =
        if (a9) AudioSource.entries - AudioSource.ONLINE else AudioSource.entries

    /**
     * Cycle enregistré, ou [DEFAULT] s'il n'y en a pas — ou s'il ne compte pas deux sources connues
     * et [offertes]. Une source enregistrée mais absente du firmware est ignorée.
     */
    fun resolve(enregistre: String?, offertes: List<AudioSource> = AudioSource.entries): List<AudioSource> {
        val choisies = enregistre.orEmpty().split(',')
            .mapNotNull { AudioSource.fromKey(it.trim()) }
            .filter { it in offertes }
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

    /** Délai après une bascule avant de regarder si elle a pris (la source met un moment à démarrer). */
    const val CONFIRM_MS = 3_000L

    /**
     * Source d'où repart un appui, dans cet ordre :
     *  1. la source [enAttente] d'être appliquée (deux appuis rapprochés) ;
     *  2. la [derniere] choisie si notre bascule est [enVerification] (la source active n'a pas
     *     encore eu le temps de changer) ou si la source active est illisible — le fonctionnement
     *     d'avant le 2026-10-16 ;
     *  3. la [derniere] choisie si la source active est restée celle d'où notre dernière bascule a
     *     échoué ([echecDepuis]) : la source visée n'a pas pris, on la saute ;
     *  4. sinon la source [actif] — elle-même ou un changement fait ailleurs.
     */
    fun depart(
        actif: AudioSource?, derniere: AudioSource?, enAttente: AudioSource?,
        echecDepuis: AudioSource?, enVerification: Boolean,
    ): AudioSource? = when {
        enAttente != null -> enAttente
        enVerification || actif == null -> derniere
        echecDepuis != null && actif == echecDepuis -> derniere
        else -> actif
    }

    // ── Le réglage enregistré ────────────────────────────────────────────────

    fun order(ctx: Context): List<AudioSource> =
        resolve(prefs(ctx).getString(KEY, null), offertes(FirmwareInfo.isA9()))

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
