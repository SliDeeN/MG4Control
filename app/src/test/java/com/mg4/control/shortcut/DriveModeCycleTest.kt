package com.mg4.control.shortcut

import com.mg4.control.model.DriveMode.CUSTOM
import com.mg4.control.model.DriveMode.ECO
import com.mg4.control.model.DriveMode.NORMAL
import com.mg4.control.model.DriveMode.SNOW
import com.mg4.control.model.DriveMode.SPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cycle des modes de conduite (issue #207). */
class DriveModeCycleTest {

    @Test
    fun `sans composition - Eco, Normal, Sport`() {
        assertEquals(listOf(ECO, NORMAL, SPORT), DriveModeCycle.resolve(null))
        assertEquals(listOf(ECO, NORMAL, SPORT), DriveModeCycle.resolve(""))
    }

    @Test
    fun `ordre enregistre relu tel quel, Neige et Personnalise compris`() {
        val ordre = listOf(SPORT, CUSTOM, SNOW, ECO)
        assertEquals(ordre, DriveModeCycle.resolve(DriveModeCycle.encode(ordre)))
    }

    @Test
    fun `valeurs illisibles et doublons ecartes, cycle trop court = defaut`() {
        assertEquals(listOf(ECO, SPORT), DriveModeCycle.resolve("2, x ,4,2,99"))
        assertEquals(listOf(ECO, NORMAL, SPORT), DriveModeCycle.resolve("4"))
    }

    @Test
    fun `suivant dans le cycle, puis retour au premier`() {
        val cycle = listOf(ECO, SPORT)
        assertEquals(SPORT, DriveModeCycle.next(cycle, ECO))
        assertEquals(ECO, DriveModeCycle.next(cycle, SPORT))
    }

    @Test
    fun `mode hors du cycle ou illisible - le premier du cycle`() {
        val cycle = listOf(ECO, SPORT)
        assertEquals(ECO, DriveModeCycle.next(cycle, NORMAL))
        assertEquals(ECO, DriveModeCycle.next(cycle, null))
        assertNull(DriveModeCycle.next(emptyList(), ECO))
    }
}
