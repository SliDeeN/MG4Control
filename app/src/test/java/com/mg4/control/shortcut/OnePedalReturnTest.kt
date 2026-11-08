package com.mg4.control.shortcut

import com.mg4.control.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Raccourci One Pedal, second appui (issue #226) : niveau imposé ou « comme avant ». */
class OnePedalReturnTest {

    @Test
    fun `comme avant par defaut, rien n'est impose`() {
        assertNull(OnePedalReturn.levelFor(OnePedalReturn.PREVIOUS))
    }

    @Test
    fun `les quatre niveaux proposes sont imposes`() {
        assertEquals(RegenLevel.LOW, OnePedalReturn.levelFor(RegenLevel.LOW.value))
        assertEquals(RegenLevel.MEDIUM, OnePedalReturn.levelFor(RegenLevel.MEDIUM.value))
        assertEquals(RegenLevel.HIGH, OnePedalReturn.levelFor(RegenLevel.HIGH.value))
        assertEquals(RegenLevel.ADAPTIVE, OnePedalReturn.levelFor(RegenLevel.ADAPTIVE.value))
    }

    @Test
    fun `Off, One Pedal ou une valeur inconnue reviennent a comme avant`() {
        assertNull(OnePedalReturn.levelFor(RegenLevel.OFF.value))
        assertNull(OnePedalReturn.levelFor(RegenLevel.ONE_PEDAL.value))
        assertNull(OnePedalReturn.levelFor(42))
    }
}
