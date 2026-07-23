package com.mg4.control.hardware

import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.VehicleWriteGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-904] Politique : écriture véhicule autorisée uniquement à 0 km/h, refus si la vitesse
 * est illisible. Logique pure — pas de véhicule, pas d'Android.
 */
class VehicleWriteGateTest {

    @Test
    fun `a l arret l ecriture est autorisee`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f))
    }

    @Test
    fun `en mouvement l ecriture est refusee`() {
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(1f))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(50f))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(130f))
    }

    @Test
    fun `la moindre vitesse non nulle refuse - pas de tolerance`() {
        // Pas de seuil "presque à l'arrêt" : la politique dit 0.
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(0.1f))
    }

    @Test
    fun `vitesse illisible refuse - fail closed`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null))
    }

    @Test
    fun `vitesse NaN refuse - fail closed`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(Float.NaN))
    }

    @Test
    fun `vitesse negative refuse - valeur aberrante, pas une autorisation`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-1f))
    }
}
