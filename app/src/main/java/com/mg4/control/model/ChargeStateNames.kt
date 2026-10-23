package com.mg4.control.model

/**
 * Signaux d'état relevés par la fenêtre « État de charge » : ceux dont un changement mérite une
 * ligne dans son historique et dans le journal.
 */
data class ChargeStateSignals(
    val statut: Int?,
    val prise: Int?,
    val ac: Int?,
    val dc: Int?,
    val raison: Int?,
    val v2x: Int?,
)

/**
 * Noms des états de charge `BMS_CHRG_STS` (0x2140f409) et des motifs d'arrêt, tels que les
 * lanceurs d'origine les affichent — relevés le 2026-10-23 dans leurs ressources :
 *  - SWI133 `launcher_my24_P` : `charging_status` 0..12 et `charging_suspend_reason` (1..10, 16..23) ;
 *  - SWI69 / SWI132 `Launcher_*_ll` : `a9_ev_status` 0..15, mêmes 0..12 puis la décharge V2L.
 *
 * L'état 11 s'appelle `super_offboard` (charge) dans les deux lanceurs. Seul le texte anglais du
 * SWI69 (« Discharge connector connected ») le fait passer pour du V2L, et MG4Dash l'a repris
 * ainsi. L'état 2 est bien « Charging complete », que SAIC compte pourtant « en charge »
 * ([ChargeDisplay.enCharge]) : c'est ce que la fenêtre doit permettre de vérifier en voiture.
 *
 * Textes anglais du firmware, laissés tels quels : c'est un outil de relevé, pas de l'affichage.
 * Que le motif d'arrêt 0x2140f415 suive la table du lanceur n'est pas prouvé : la fenêtre le
 * marque d'un « ? ».
 */
object ChargeStateNames {

    private val ETATS = mapOf(
        0 to "Unplugged",
        1 to "Slow charging (onboard)",
        2 to "Charging complete",
        3 to "Battery balancing",
        4 to "Charging failure",
        5 to "Connecting",
        6 to "Connected - Not recognised",
        7 to "Connected - Not charging",
        8 to "Charging stopped",
        9 to "Charging scheduled",
        10 to "Fast charging (offboard)",
        11 to "Super offboard charging",
        12 to "Multiple charging",
        13 to "Discharging (V2L)",
        14 to "Discharge stopped (V2L)",
        15 to "Discharge finished (V2L)",
    )

    private val RAISONS = mapOf(
        1 to "Grid supply abnormal",
        2 to "Charging connector temperature abnormal",
        3 to "Charging connection abnormal",
        4 to "Charging station not up to standard",
        5 to "Foreign metal object detected",
        6 to "Abnormal object detected",
        7 to "Wireless charger fault",
        8 to "Wireless charger wifi communication abnormal",
        9 to "Charging electronic lock failure",
        10 to "Wireless charger ground assembly temperature too high",
        16 to "Onboard charging system abnormal",
        17 to "Onboard charger abnormal",
        18 to "Offboard charging system abnormal",
        19 to "Wireless charging system abnormal",
        20 to "Wireless charger abnormal",
        21 to "Cell temperature abnormal",
        22 to "Charging over current",
        23 to "Charging over voltage",
    )

    /** Nom d'origine de l'état [v], ou `null` s'il est illisible ou absent des deux tables. */
    fun etat(v: Int?): String? = v?.let { ETATS[it] }

    /** Motif d'arrêt d'après la table du lanceur SWI133, ou `null` (0 = aucun motif). */
    fun raisonArret(v: Int?): String? = v?.let { RAISONS[it] }

    /** Ce qui a changé de [avant] à [apres], « statut 7 → 1 · AC 0 → 2 » ; vide si rien. */
    fun changements(avant: ChargeStateSignals, apres: ChargeStateSignals): String {
        fun v(x: Int?) = x?.toString() ?: "–"
        return listOf(
            "statut" to (avant.statut to apres.statut),
            "prise" to (avant.prise to apres.prise),
            "AC" to (avant.ac to apres.ac),
            "DC" to (avant.dc to apres.dc),
            "arrêt" to (avant.raison to apres.raison),
            "V2X" to (avant.v2x to apres.v2x),
        ).filter { (_, p) -> p.first != p.second }
            .joinToString(" · ") { (nom, p) -> "$nom ${v(p.first)} → ${v(p.second)}" }
    }
}
