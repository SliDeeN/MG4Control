package com.mg4.control.automation

import com.mg4.control.model.AirFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ligne « Air » de l'automatisation A/C (issue #177) et reprise des anciens dégivrages. */
class ClimateAutomationAirTest {

    @Test fun `la ligne Air enregistree fait foi`() {
        assertEquals(AirFlow.FACE or AirFlow.FEET, ClimateAutomationSettings.airFlowFrom(AirFlow.FACE or AirFlow.FEET, true, true))
        // 0 = Inchangé, même si un ancien dégivrage était coché.
        assertNull(ClimateAutomationSettings.airFlowFrom(0, true, false))
    }

    @Test fun `un ancien degivrage coche devient son bouton`() {
        assertEquals(AirFlow.WINDSHIELD, ClimateAutomationSettings.airFlowFrom(null, true, false))
        assertEquals(AirFlow.WINDSHIELD or AirFlow.REAR_DEFROST, ClimateAutomationSettings.airFlowFrom(null, true, true))
    }

    @Test fun `sans degivrage la regle ne touche plus au sens de l'air`() {
        assertNull(ClimateAutomationSettings.airFlowFrom(null, false, false))
    }
}
