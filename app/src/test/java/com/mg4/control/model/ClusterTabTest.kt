package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Onglets du combiné, valeurs de l'appli SAIC du combiné. */
class ClusterTabTest {

    @Test fun `les valeurs sont celles de l appli du combine`() {
        assertEquals(ClusterTab.TRIP_COMPUTER, ClusterTab.fromValue(0))
        assertEquals(ClusterTab.NAVIGATION, ClusterTab.fromValue(1))
        assertEquals(ClusterTab.MEDIA, ClusterTab.fromValue(2))
        assertEquals(ClusterTab.SETTINGS, ClusterTab.fromValue(5))
        assertEquals(ClusterTab.RESERVED, ClusterTab.fromValue(7))
    }

    @Test fun `une valeur absente du code ou illisible ne devient pas un onglet`() {
        assertNull(ClusterTab.fromValue(8))
        assertNull(ClusterTab.fromValue(-1))
        assertNull(ClusterTab.fromValue(null))
    }
}
