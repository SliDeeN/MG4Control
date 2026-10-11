package com.mg4.control.profile

import android.content.Context
import com.mg4.control.debug.AppLogger

/**
 * Passage en READY : ce que devient un profil choisi à la main.
 *
 * Par défaut il est RÉAPPLIQUÉ à chaque passage en READY, jusqu'à l'extinction de la voiture —
 * c'est le respect du choix explicite de l'utilisateur (voir [ProfileApplier.lastManualProfileId]).
 *
 * L'option « Revenir au profil par défaut à chaque passage en READY » (Réglages avancés) le fait
 * OUBLIER. La chaîne habituelle reprend alors la main, sans rien changer à son ordre : profil lié
 * à la température, puis au téléphone Bluetooth, puis profil par défaut. Demande d'un utilisateur
 * qui voulait retrouver son profil par défaut après chaque arrêt, même court.
 */
object ReadyProfilePolicy {

    private const val TAG = "MG4_PROFILE"

    /** Même fichier que les autres réglages avancés. */
    private const val PREFS = "mg4_settings"

    /** Défaut false : le comportement d'origine ne change pour personne. */
    const val KEY = "ready_back_to_default"

    fun isEnabled(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, false) ?: false

    fun setEnabled(ctx: Context, actif: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, actif).apply()
        AppLogger.i(TAG, "Retour au profil par défaut à chaque READY : ${if (actif) "activé" else "désactivé"}")
    }

    // ── La règle, sans Android ───────────────────────────────────────────────

    /**
     * Profil choisi à la main à réappliquer au passage en READY, ou `null` quand c'est la chaîne
     * automatique qui décide (température, Bluetooth, profil par défaut).
     */
    fun manualToReapply(manualId: String?, backToDefault: Boolean): String? =
        if (backToDefault) null else manualId

    /**
     * Vrai s'il faut OUBLIER le choix manuel, pas seulement l'écarter : resté en mémoire, il
     * reviendrait au READY suivant si l'option était décochée entre-temps.
     */
    fun forgetsManual(manualId: String?, backToDefault: Boolean): Boolean =
        backToDefault && manualId != null
}
