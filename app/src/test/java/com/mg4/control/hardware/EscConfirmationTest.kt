package com.mg4.control.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscConfirmationTest {
    @Test fun `trois lectures concordantes donnent un etat exploitable`() {
        assertEquals(true, MG4Hardware.escConfirme(listOf(true, true, true)))
        assertEquals(false, MG4Hardware.escConfirme(listOf(false, false, false)))
    }

    @Test fun `une lecture illisible invalide la serie`() {
        assertNull(MG4Hardware.escConfirme(listOf(false, null, false)))
    }

    @Test fun `une confirmation transitoire ne valide pas la bascule`() {
        assertNull(MG4Hardware.escConfirme(listOf(true, false)))
        assertEquals(true, MG4Hardware.escConfirme(listOf(true, true)))
    }
}
