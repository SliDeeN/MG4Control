package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce que la fenêtre « Guidage AA / CarPlay » met en grand (issue #285). */
class NavGuidanceTextTest {

    @Test
    fun `Android Auto shows the latest text big and the other types below`() {
        val r = NavGuidanceText.androidAuto(mapOf(3 to "Tournez à droite\nrue Victor Hugo", 1 to "350 m"), dernierType = 3)
        assertEquals("Tournez à droite · rue Victor Hugo", r.grand)
        assertEquals("1 : 350 m", r.ligne1)
        assertNull(r.ligne2)
    }

    @Test
    fun `Android Auto without any text is empty`() {
        assertTrue(NavGuidanceText.androidAuto(mapOf(2 to "  "), dernierType = 2).vide)
        assertTrue(NavGuidanceText.androidAuto(emptyMap(), dernierType = null).vide)
    }

    @Test
    fun `CarPlay puts the distance with its unit big, then the road and the details`() {
        val r = NavGuidanceText.carplay(mapOf(
            "DIST_TO_NEXT_JUNCTION" to "300", "DIST_TO_NEXT_JUNCTION_UNIT" to "2",
            "ROAD_AFTER_JUNCTION" to "A6", "JUNCTION_ANGLE" to "90", "EST_TIME_ARRIVAL" to "14:38",
        ))
        assertEquals("300 2", r.grand)
        assertEquals("A6", r.ligne1)
        assertEquals("angle 90° · ETA 14:38", r.ligne2)
        assertTrue(NavGuidanceText.carplay(mapOf("ICON_ID" to "4")).vide)
    }

    @Test
    fun `the age reads in seconds then minutes`() {
        assertEquals("0 s", NavGuidanceText.age(-5))
        assertEquals("45 s", NavGuidanceText.age(45_400))
        assertEquals("3 min", NavGuidanceText.age(200_000))
    }
}
