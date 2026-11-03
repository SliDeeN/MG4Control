package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Puissance lissée des écrans de pré-climatisation (issue #210). */
class PowerSmootherTest {

    @Test
    fun `moyenne des derniers releves arrondie a 10 W`() {
        val p = PowerSmoother(3)
        listOf(0.231f, 0.244f, 0.252f).forEach { p.add(it) }
        assertEquals(240, p.watts())
    }

    @Test
    fun `fenetre glissante`() {
        val p = PowerSmoother(2)
        listOf(5f, 1.8f, 2.0f).forEach { p.add(it) }
        assertEquals(1900, p.watts())
    }

    @Test
    fun `illisible ou en charge - ignore, rien sans releve valable`() {
        val p = PowerSmoother(3)
        p.add(null); p.add(-3.5f); p.add(Float.NaN)
        assertNull(p.watts())
        p.add(0.5f)
        assertEquals(500, p.watts())
        p.reset()
        assertNull(p.watts())
    }
}
