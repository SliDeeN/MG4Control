package com.mg4.control.hardware

import com.mg4.control.util.FirmwareInfo

/**
 * Onglet choisi au volant sur le combiné, en **lecture seule** : relu à chaque relevé par le module
 * du combiné, qui suit l'onglet ([com.mg4.control.service.ClusterModuleOverlay]).
 *
 * Valeurs de [com.mg4.control.model.ClusterTab], relevées en voiture le 2026-10-14 sur SWI133 et
 * SWI69 : 0 ordinateur de bord, 1 navigation, 2 musique, 4 pression des pneus, 5 réglages.
 */
object ClusterTabReader {

    /** `IPK_CLUSTER_TAB_BTN_STS`, lu par l'appli SAIC du combiné pour choisir sa page. */
    const val PROP_TAB = 0x21407b18
    /** Le même signal dans la table de l'ancien SDK (`VehiclePropertyID`), lu sur SWI133. */
    const val VPM_TAB = 0x2030013
    /** `IPK_CNTR_AREA`, relevé à côté : suit-il l'onglet, ou dit-il autre chose ? */
    const val PROP_CENTRE = 0x21407b0a
    /** `FICM_NAVI_READY_STS` : « image prête » de la navigation, signalée au combiné. */
    const val PROP_NAVI_READY = 0x21407b16
    /** `FICM_START_UP_STS` : « écran central démarré », envoyé à 1 par l'appli du combiné de SWI68. */
    const val PROP_START_UP = 0x21407b00
    /** `LVDS_ELEC_READY` : « liaison vidéo prête », déclaré sur SWI69 et SWI133. */
    const val PROP_LVDS_READY = 0x21407b4a

    private const val AREA_GLOBAL = 0x1000000

    /**
     * Un relevé de la fenêtre « Onglet du combiné » ([com.mg4.control.ui.ClusterTabDialog]) ;
     * `null` = illisible par cette voie. [vpm] n'est lu que sur SWI133.
     */
    data class Lecture(
        val cpm: Int?, val vpm: Int?, val centre: Int?, val pret: Int?, val demarre: Int?, val lvds: Int?,
    ) {
        /** Valeur retenue : celle du CarPropertyManager, sinon celle de l'ancien SDK. */
        val valeur: Int? get() = cpm ?: vpm
    }

    /** Hors du fil principal : une demi-douzaine de lectures binder. */
    fun lire(): Lecture = Lecture(
        cpm = cpm(PROP_TAB),
        vpm = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133)
            MG4Hardware.vpmIntForProbe(VPM_TAB).takeIf { it >= 0 } else null,
        centre = cpm(PROP_CENTRE),
        pret = cpm(PROP_NAVI_READY),
        demarre = cpm(PROP_START_UP),
        lvds = cpm(PROP_LVDS_READY),
    )

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
