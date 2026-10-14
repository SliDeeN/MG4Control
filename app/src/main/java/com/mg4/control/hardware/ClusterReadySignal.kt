package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.util.FirmwareInfo

/**
 * Essai A9 (2026-10-14) : dire au combiné que la navigation a une image à lui montrer.
 *
 * `FICM_NAVI_READY_STS` (0x21407b16) : 1 = image prête, 0 = pas prête — valeurs de l'appli SAIC du
 * combiné (`FicmType`, dump SWI68). Sur SWI68 et SWI133 c'est elle qui l'envoie, et le combiné
 * montre alors son carré sur l'onglet Navigation. Sur SWI69 personne ne l'envoie (valeur lue : 0,
 * Diagnostic du 2026-10-14), et le combiné n'a pas d'onglet Navigation : peut-être à cause de ça.
 *
 * ⚠️ ÉCRITURE vers le combiné, réservée à l'essai de la fenêtre « Onglet du combiné » : un simple
 * indicateur, remis à 0 dès que l'essai s'arrête. Même chemin que le service SAIC des A9
 * (`CarAdapterVendorClusterManager.sendDrawReadyToIPK` → `setProperty(Integer, id, zone 0, valeur)`).
 */
object ClusterReadySignal {

    private const val TAG = "MG4_CLUSTER"

    const val PROP_NAVI_READY = 0x21407b16
    const val READY = 1
    const val NOT_READY = 0

    /** Zone 0 d'abord, celle du service SAIC ; la zone globale des propriétés vendeur sinon. */
    private val ZONES = intArrayOf(0, 0x1000000)

    /**
     * Firmwares A9, où personne n'envoie le signal. Ailleurs l'appli SAIC du combiné s'en charge :
     * l'écrire par-dessus elle n'aurait aucun sens.
     */
    fun applicable(): Boolean = FirmwareInfo.getGeneration() in
        setOf(FirmwareInfo.Gen.SWI69, FirmwareInfo.Gen.SWI131, FirmwareInfo.Gen.SWI132)

    /** Écrit [valeur] ; vrai si le service l'a accepté. Hors du fil principal : appel binder. */
    fun envoyer(valeur: Int): Boolean {
        if (!VehicleWriteGate.allow("FICM_NAVI_READY_STS")) return false
        val cpm = MG4Hardware.carPropertyManager() ?: run {
            AppLogger.w(TAG, "essai A9 : FICM_NAVI_READY_STS ← $valeur impossible, liaison véhicule absente")
            return false
        }
        for (zone in ZONES) {
            try {
                cpm.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java, Int::class.java)
                    .invoke(cpm, PROP_NAVI_READY, zone, valeur)
                AppLogger.i(TAG, "essai A9 : FICM_NAVI_READY_STS ← $valeur (zone 0x${Integer.toHexString(zone)}) accepté")
                return true
            } catch (e: Exception) {
                val cause = e.cause ?: e
                AppLogger.w(TAG, "essai A9 : FICM_NAVI_READY_STS ← $valeur (zone 0x${Integer.toHexString(zone)}) " +
                    "refusé — ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
        return false
    }
}
