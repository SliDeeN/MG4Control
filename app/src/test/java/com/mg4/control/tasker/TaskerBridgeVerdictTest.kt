package com.mg4.control.tasker

import com.mg4.control.hardware.VehicleWriteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le verdict est la seule explication que MG4Tasker peut donner à l'utilisateur quand une
 * règle ne s'applique pas. S'il est faux ou vide, l'utilisateur voit « rien ne s'est passé »
 * sans savoir que c'est le verrou de vitesse qui a refusé.
 */
class TaskerBridgeVerdictTest {

    @Test
    fun `chaque decision du verrou a un verdict distinct et non vide`() {
        val verdicts = VehicleWriteGate.Decision.values()
            .map { TaskerBridgeService.verdictOf(it) }

        verdicts.forEach { assertTrue("verdict vide", it.isNotBlank()) }
        assertEquals(
            "deux décisions ne doivent jamais produire le même verdict",
            verdicts.size, verdicts.toSet().size
        )
    }

    @Test
    fun `vitesse nulle autorise, vitesse non nulle refuse`() {
        assertEquals(
            TaskerBridgeService.VERDICT_ALLOWED,
            TaskerBridgeService.verdictOf(VehicleWriteGate.decide(0f))
        )
        assertEquals(
            TaskerBridgeService.VERDICT_MOVING,
            TaskerBridgeService.verdictOf(VehicleWriteGate.decide(42f))
        )
    }

    @Test
    fun `vitesse illisible refuse au lieu de laisser passer`() {
        // Fail closed : une vitesse inconnue ne doit jamais ouvrir la porte au tasker,
        // qui écrirait alors sans supervision humaine sur un véhicule potentiellement roulant.
        assertEquals(
            TaskerBridgeService.VERDICT_UNKNOWN_SPEED,
            TaskerBridgeService.verdictOf(VehicleWriteGate.decide(null))
        )
    }
}
