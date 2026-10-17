package com.mg4.control.model

/**
 * Onglets du combiné, d'après `IPK_CLUSTER_TAB_BTN_STS` (0x21407b18) : les valeurs de l'appli SAIC
 * du combiné (`ClstrTabReq`, dump SWI68), décodées le 2026-10-14.
 *
 * Le code les prévoit pour tous les modèles et tous les marchés : une voiture n'en montre qu'une
 * partie. Relevé en voiture le 2026-10-14 : SWI133 a 0, 1, 2, 4 (pression des pneus, rangée par le
 * code sous [WARNING_CENTRE]) et 5 ; SWI69 n'a que 0, 4 et 5.
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

    companion object {
        fun fromValue(value: Int?): ClusterTab? = entries.firstOrNull { it.value == value }
    }
}
