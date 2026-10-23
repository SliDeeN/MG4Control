package com.mg4.control.hardware

import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DistanceUnit

/**
 * Unité de distance réglée dans la voiture (2026-10-23), pour le choix « Auto » de l'écran
 * conducteur ([com.mg4.control.util.ClusterModuleSettings.distanceUnit]). Idée reprise de MG4Dash,
 * propriété vérifiée dans les firmwares : voir [MG4Hardware.readDistanceUnitsRaw].
 *
 * Relue au plus une fois par minute par [actualiser], hors du fil principal (appel binder), et
 * gardée en mémoire : les Réglages et l'aperçu la lisent sur le fil principal par [connue]. Une
 * lecture ratée garde la dernière unité connue plutôt que de repasser en km.
 */
object VehicleDistanceUnit {

    private const val TAG = "MG4_CLUSTER"

    /** On change rarement l'unité de la voiture : une minute de retard au pire. */
    private const val RELECTURE_MS = 60_000L

    @Volatile private var unite: DistanceUnit? = null
    @Volatile private var brut: Int? = null
    @Volatile private var lueMs = 0L
    @Volatile private var dejaLue = false

    /** Dernière unité lue, ou `null` si la voiture ne l'a jamais donnée. Sans appel binder. */
    fun connue(): DistanceUnit? = unite

    /** Relit la voiture si la lecture précédente date de plus d'une minute. Appel binder. */
    @Synchronized
    fun actualiser(): DistanceUnit? {
        val maintenant = SystemClock.elapsedRealtime()
        if (dejaLue && maintenant - lueMs < RELECTURE_MS) return unite
        lueMs = maintenant
        val r = runCatching { MG4Hardware.readDistanceUnitsRaw() }.getOrNull()
        val u = DistanceUnit.fromVehicle(r)
        // Une ligne à la première lecture et à chaque changement : de quoi vérifier « 1 = miles ».
        if (!dejaLue || r != brut) {
            AppLogger.i(TAG, "unité de distance de la voiture (0x2140f439) : brut ${r ?: "illisible"} → " +
                (u?.name ?: if (unite != null) "inconnue, ${unite?.name} gardé" else "inconnue (km)"))
        }
        dejaLue = true
        brut = r
        if (u != null) unite = u
        return unite
    }
}
