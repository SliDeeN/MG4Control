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

    private const val AREA_GLOBAL = 0x1000000

    /** `null` = illisible par cette voie. [vpm] n'est lu que sur SWI133. */
    data class Lecture(val cpm: Int?, val vpm: Int?, val centre: Int?) {
        /** Valeur retenue : celle du CarPropertyManager, sinon celle de l'ancien SDK. */
        val valeur: Int? get() = cpm ?: vpm
    }

    /** Hors du fil principal : jusqu'à cinq lectures binder. */
    fun lire(): Lecture = Lecture(
        cpm = cpm(PROP_TAB),
        vpm = if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133)
            MG4Hardware.vpmIntForProbe(VPM_TAB).takeIf { it >= 0 } else null,
        centre = cpm(PROP_CENTRE),
    )

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
