package com.mg4.control.model

/**
 * Réglages de l'écran conducteur d'UN profil (2026-10-24) : chaque conducteur règle le sien, ou le
 * coupe, depuis Réglages › Interface (« Réglage de : », un ou plusieurs profils à la fois).
 *
 * Les valeurs sont celles qu'enregistraient les réglages communs d'avant — clés des énumérations,
 * millisecondes —, lues par les mêmes fonctions ([com.mg4.control.util.ClusterModuleSettings]).
 * Tout est facultatif : un champ absent vaut le défaut d'une installation neuve, ce qui garde les
 * profils lisibles quand un réglage s'ajoute. Un profil créé après la mise à jour n'a pas de
 * réglages du tout : module coupé, c'est à l'utilisateur de l'activer (sa décision).
 */
data class ClusterConfig(
    /** « Module sur le combiné » ; `null` = coupé. */
    val enabled: Boolean? = null,
    /** Module de l'onglet Navigation : clé de [ClusterModule], ou « none » pour Aucun. */
    val navigation: String? = null,
    /** Module de l'onglet Musique, même codage. */
    val media: String? = null,
    val unit: String? = null,
    /** km, miles ou « auto » ; `null` = Auto. */
    val distance: String? = null,
    val style: String? = null,
    val average: String? = null,
    val pill: String? = null,
    val refreshMs: Int? = null,
    val animationMs: Int? = null,
    /** Puissance maxi de la jauge (issue #165) : kW de [PowerScale.CHOIX_KW], `null` = Auto. */
    val powerMax: Int? = null,
)

object ClusterConfigs {

    /**
     * Profil dont le combiné suit les réglages : le profil en cours, à défaut le profil par défaut
     * (rien n'a encore été appliqué), à défaut le premier de la liste ; `null` sans aucun profil —
     * les réglages communs d'avant valent alors.
     */
    fun shown(profiles: List<DrivingProfile>, activeId: String?, defaultId: String?): DrivingProfile? =
        profiles.firstOrNull { it.id == activeId }
            ?: profiles.firstOrNull { it.id == defaultId }
            ?: profiles.firstOrNull()

    /** Réglages de [profile] ; un profil qui n'en a jamais eu : module coupé, le reste par défaut. */
    fun of(profile: DrivingProfile): ClusterConfig = profile.cluster ?: ClusterConfig()

    /**
     * Profils restant sélectionnés dans la carte : ceux de [selected] qui existent encore ; s'il
     * n'en reste aucun, [fallback] (le profil que suit le combiné) — il en faut toujours un.
     */
    fun keepSelection(selected: Collection<String>, existing: List<String>, fallback: String?): Set<String> {
        val restants = selected.filter { it in existing }.toSet()
        return restants.ifEmpty { setOfNotNull(fallback ?: existing.firstOrNull()) }
    }
}
