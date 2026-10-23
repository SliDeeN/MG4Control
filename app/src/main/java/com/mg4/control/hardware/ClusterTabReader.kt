package com.mg4.control.hardware

import com.mg4.control.util.FirmwareInfo

/**
 * Onglet choisi au volant sur le combiné, en **lecture seule** : relu à chaque relevé par le module
 * du combiné, qui suit l'onglet ([com.mg4.control.service.ClusterModuleOverlay]).
 *
 * Valeurs de [com.mg4.control.model.ClusterTab], relevées en voiture le 2026-10-14 sur SWI133 et
 * SWI69 : 0 ordinateur de bord, 1 navigation, 2 musique, 4 pression des pneus, 5 réglages.
 * La fenêtre de relevé « Onglet du combiné » a été retirée le 2026-10-23, remplacée par
 * « État de charge ».
 */
object ClusterTabReader {

    /** `IPK_CLUSTER_TAB_BTN_STS`, lu par l'appli SAIC du combiné pour choisir sa page. */
    const val PROP_TAB = 0x21407b18
    /** Le même signal dans la table de l'ancien SDK (`VehiclePropertyID`), lu sur SWI133. */
    const val VPM_TAB = 0x2030013

    private const val AREA_GLOBAL = 0x1000000

    /**
     * Onglet, ou `null` s'il est illisible : la voie du CarPropertyManager, sinon celle de l'ancien
     * SDK. Hors du fil principal : appels binder.
     */
    fun onglet(): Int? = cpm(PROP_TAB)
        ?: if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133)
            MG4Hardware.vpmIntForProbe(VPM_TAB).takeIf { it >= 0 } else null

    /** Zone globale d'abord, comme les autres propriétés vendeur ; zone 0 sinon. */
    private fun cpm(id: Int): Int? {
        val manager = MG4Hardware.carPropertyManager() ?: return null
        for (zone in intArrayOf(AREA_GLOBAL, 0)) {
            runCatching {
                manager.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                    .invoke(manager, id, zone) as? Int
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
