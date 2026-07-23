package com.mg4.control.api

import com.mg4.hardware.VehicleWriteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict is the only explanation an external caller can surface to the user when a
 * profile write is refused. If it were wrong or empty, the user would see "nothing happened"
 * without knowing that the speed gate declined the write.
 */
class ProfileControlVerdictTest {

    @Test
    fun `each gate decision has a distinct, non-empty verdict`() {
        val verdicts = VehicleWriteGate.Decision.values()
            .map { ProfileControlService.verdictOf(it) }

        verdicts.forEach { assertTrue("empty verdict", it.isNotBlank()) }
        assertEquals(
            "two decisions must never produce the same verdict",
            verdicts.size, verdicts.toSet().size
        )
    }

    @Test
    fun `zero speed allows, non-zero speed refuses`() {
        assertEquals(
            ProfileControlService.VERDICT_ALLOWED,
            ProfileControlService.verdictOf(VehicleWriteGate.decide(0f))
        )
        assertEquals(
            ProfileControlService.VERDICT_MOVING,
            ProfileControlService.verdictOf(VehicleWriteGate.decide(42f))
        )
    }

    @Test
    fun `unreadable speed refuses instead of letting the write through`() {
        // Fail closed: an unknown speed must never open the gate to an external caller,
        // which would then write unsupervised on a potentially moving vehicle.
        assertEquals(
            ProfileControlService.VERDICT_UNKNOWN_SPEED,
            ProfileControlService.verdictOf(VehicleWriteGate.decide(null))
        )
    }
}
