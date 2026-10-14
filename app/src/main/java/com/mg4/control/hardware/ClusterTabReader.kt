package com.mg4.control.hardware

import com.mg4.control.util.FirmwareInfo

/**
 * Onglet choisi au combiné, en **lecture seule** — pour la fenêtre « Onglet du combiné »
 * (Réglages, débloquée avec le Diagnostic). Trois lectures, relues en continu pendant que la
 * fenêtre est ouverte : aucune trace dans le journal à chaque échec, c'est la fenêtre qui dit
 * « illisible ».
 */
object ClusterTabReader {

    /** `IPK_CLUSTER_TAB_BTN_STS`, lu par l'appli SAIC du combiné pour choisir sa page. */
    const val PROP_TAB = 0x21407b18
    /** Le même signal dans la table de l'ancien SDK (`VehiclePropertyID`), lu sur SWI133. */
    const val VPM_TAB = 0x2030013
    /** `IPK_CNTR_AREA`, relevé à côté : suit-il l'onglet, ou dit-il autre chose ? */
    const val PROP_CENTRE = 0x21407b0a
    /** `FICM_START_UP_STS` : « écran central démarré », envoyé à 1 par l'appli du combiné de SWI68. */
    const val PROP_START_UP = 0x21407b00
    /** `LVDS_ELEC_READY` : « liaison vidéo prête », déclaré sur SWI69 et SWI133. */
    const val PROP_LVDS_READY = 0x21407b4a

    private const val AREA_GLOBAL = 0x1000000

    /**
     * `null` = illisible par cette voie. [vpm] n'est lu que sur SWI133. [pret] : « image prête » de
     * la navigation ([ClusterReadySignal.PROP_NAVI_READY]), suivi pendant l'essai A9. [demarre] et
     * [lvds] : les deux autres signaux qu'attend peut-être le combiné avant d'afficher une image.
     */
    data class Lecture(
        val cpm: Int?, val vpm: Int?, val centre: Int?, val pret: Int? = null,
        val demarre: Int? = null, val lvds: Int? = null,
    ) {
        /** Valeur retenue : celle du CarPropertyManager, sinon celle de l'ancien SDK. */
        val valeur: Int? get() = cpm ?: vpm
    }

    /** Hors du fil principal : une dizaine de lectures binder au plus. */
    fun lire(): Lecture = Lecture(
        cpm = cpm(PROP_TAB),
        vpm = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133)
            MG4Hardware.vpmIntForProbe(VPM_TAB).takeIf { it >= 0 } else null,
        centre = cpm(PROP_CENTRE),
        pret = cpm(ClusterReadySignal.PROP_NAVI_READY),
        demarre = cpm(PROP_START_UP),
        lvds = cpm(PROP_LVDS_READY),
    )

    /**
     * Onglet seul, pour le module du combiné qui le relit à chaque relevé : la voie du
     * CarPropertyManager, sinon celle de l'ancien SDK. `null` = illisible.
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
