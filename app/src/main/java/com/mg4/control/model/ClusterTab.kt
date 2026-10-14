package com.mg4.control.model

/**
 * Onglets du combiné, d'après `IPK_CLUSTER_TAB_BTN_STS` (0x21407b18) : les valeurs de l'appli SAIC
 * du combiné (`ClstrTabReq`, dump SWI68), décodées le 2026-10-14.
 *
 * Le code les prévoit pour tous les modèles et tous les marchés : une voiture n'en montre qu'une
 * partie. Sur SWI133 : ordinateur de bord, navigation, musique et radio, pression des pneus,
 * réglages — l'onglet des pneus n'a pas de nom dans le code, sa valeur reste à relever.
 */
enum class ClusterTab(val value: Int) {
    TRIP_COMPUTER(0),
    NAVIGATION(1),
    MEDIA(2),
    CALL(3),
    WARNING_CENTRE(4),
    SETTINGS(5),
    ACTIVE_SAFETY(6),
    RESERVED(7);

    /**
     * L'appli SAIC y montre sa page — elle signale au combiné « image prête » pour ces trois-là
     * seulement : c'est là que l'écran central, donc notre module, peut se voir. Ailleurs, le
     * combiné dessine lui-même.
     */
    val showsHeadUnit: Boolean get() = this == NAVIGATION || this == MEDIA || this == CALL

    companion object {
        fun fromValue(value: Int?): ClusterTab? = entries.firstOrNull { it.value == value }
    }
}
