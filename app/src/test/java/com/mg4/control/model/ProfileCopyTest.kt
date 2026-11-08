package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dupliquer un profil (2026-11-08) : nom de la copie, ce qui est copié, place dans la liste. */
class ProfileCopyTest {

    private fun profil(id: String, name: String, bt: String? = null) =
        DrivingProfile(id = id, name = name, driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.HIGH, btDeviceMac = bt)

    @Test
    fun `nom de la copie, puis numerote si deja pris, sans tenir compte de la casse`() {
        assertEquals("Paul Copie", ProfileCopy.name("Paul", "Copie", listOf("Paul", "Yaya")))
        assertEquals("Paul Copie 2", ProfileCopy.name("Paul", "Copie", listOf("Paul", "paul copie")))
        assertEquals("Paul Copie 3", ProfileCopy.name("Paul", "Copie", listOf("Paul", "Paul Copie", "Paul Copie 2")))
        assertEquals("Paul Copy", ProfileCopy.name("  Paul ", "Copy", listOf("Paul")))
    }

    @Test
    fun `un nom long est raccourci pour tenir en 30 caracteres`() {
        val long = "Profil autoroute été montagne longue"
        val n = ProfileCopy.name(long, "Copie", listOf(long))
        assertTrue(n.length <= ProfileCopy.NAME_MAX)
        assertTrue(n.endsWith(" Copie"))
        val n2 = ProfileCopy.name(long, "Copie", listOf(long, n))
        assertTrue(n2.length <= ProfileCopy.NAME_MAX)
        assertTrue(n2.endsWith(" Copie 2"))
    }

    @Test
    fun `la copie garde les reglages, change d'identifiant et perd le telephone`() {
        val p = profil("a", "Paul", bt = "AA:BB:CC:DD:EE:FF").copy(seatHeatLeft = 3, driveEnabled = false)
        val c = ProfileCopy.duplicate(p, "Paul Copie", newId = "b")
        assertEquals("b", c.id)
        assertEquals("Paul Copie", c.name)
        assertNull(c.btDeviceMac)
        assertEquals(p.copy(id = "b", name = "Paul Copie", btDeviceMac = null), c)
        assertNotEquals(p.id, ProfileCopy.duplicate(p, "x").id)
    }

    @Test
    fun `la copie se range juste apres l'original, a la fin s'il a disparu`() {
        val l = listOf(profil("a", "A"), profil("b", "B"), profil("c", "C"))
        val c = profil("x", "A Copie")
        assertEquals(listOf("a", "x", "b", "c"), ProfileCopy.insertAfter(l, "a", c).map { it.id })
        assertEquals(listOf("a", "b", "c", "x"), ProfileCopy.insertAfter(l, "c", c).map { it.id })
        assertEquals(listOf("a", "b", "c", "x"), ProfileCopy.insertAfter(l, "zz", c).map { it.id })
    }
}
