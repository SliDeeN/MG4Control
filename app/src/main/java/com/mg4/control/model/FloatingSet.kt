package com.mg4.control.model

/**
 * Boutons flottants d'UN profil (2026-10-24, proposition B) : chaque profil a son jeu complet —
 * raccourci, titre, tailles et place de chaque bulle —, réglé un profil à la fois dans Raccourcis ›
 * Boutons flottants et copiable vers d'autres profils. Le masquage en marche arrière et la zone
 * clim pendant la projection restent communs.
 *
 * Tout est facultatif, pour Gson : un profil créé après la mise à jour n'a pas de jeu du tout, donc
 * aucune bulle — c'est à l'utilisateur d'en ajouter (sa décision, comme le combiné coupé).
 */
data class FloatingSet(
    /** « Afficher les boutons flottants » ; `null` = affichés, le défaut d'avant. */
    val enabled: Boolean? = null,
    val buttons: List<FloatingButton>? = null,
)

object FloatingSets {

    fun enabledOf(profile: DrivingProfile): Boolean = profile.floating?.enabled ?: true

    /** Bulles de [profile] ; un profil qui n'en a jamais eu n'en a aucune. */
    fun buttonsOf(profile: DrivingProfile): List<FloatingButton> =
        profile.floating?.buttons.orEmpty().filterNotNull().take(FloatingButtons.MAX)

    fun withButtons(profile: DrivingProfile, buttons: List<FloatingButton>): DrivingProfile =
        profile.copy(floating = (profile.floating ?: FloatingSet()).copy(buttons = buttons.take(FloatingButtons.MAX)))

    fun withEnabled(profile: DrivingProfile, enabled: Boolean): DrivingProfile =
        profile.copy(floating = (profile.floating ?: FloatingSet()).copy(enabled = enabled))

    /**
     * Profil dont les bulles sont à l'écran : celui que règle la page des boutons flottants tant
     * qu'elle est ouverte ([editingId]), sinon celui que suit l'écran conducteur — le profil en
     * cours, à défaut le profil par défaut, à défaut le premier ([ClusterConfigs.shown]).
     */
    fun target(profiles: List<DrivingProfile>, editingId: String?, activeId: String?, defaultId: String?): DrivingProfile? =
        profiles.firstOrNull { it.id == editingId } ?: ClusterConfigs.shown(profiles, activeId, defaultId)

    /** [buttons] pour un autre profil : mêmes réglages, identifiants neufs — aucune bulle partagée. */
    fun copyOf(buttons: List<FloatingButton>, newId: () -> String): List<FloatingButton> =
        buttons.map { it.copy(id = newId()) }
}
