package com.mg4.control.model

import com.mg4.control.shortcut.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sons d'automatisation (issue #161) : un événement par son, garde-fou, rapport, volume. */
class SoundAutomationRulesTest {

    private val demarrage = SoundSlot(path = "/a.mp3", durationMs = 3_200, events = setOf(SoundEvent.READY_ON, SoundEvent.APP_START))
    private val extinction = SoundSlot(path = "/b.mp3", durationMs = 2_400, events = setOf(SoundEvent.READY_OFF))

    @Test fun `chaque evenement va au son qui l'a choisi`() {
        val sons = listOf(demarrage, extinction)
        assertEquals(0, SoundAutomationRules.slotFor(SoundEvent.APP_START, sons))
        assertEquals(1, SoundAutomationRules.slotFor(SoundEvent.READY_OFF, sons))
        assertNull(SoundAutomationRules.slotFor(SoundEvent.DRIVE, sons))
        // Sans fichier, l'événement ne joue rien.
        assertNull(SoundAutomationRules.slotFor(SoundEvent.READY_OFF, listOf(demarrage, extinction.copy(path = null))))
    }

    @Test fun `autant de sons que d'evenements possibles`() {
        assertEquals(7, SoundAutomationRules.maxSounds(doorReadable = true))
        assertEquals(6, SoundAutomationRules.maxSounds(doorReadable = false))
        assertEquals(7, SoundAutomationRules.MAX_SOUNDS)
    }

    @Test fun `un evenement n'appartient qu'a un son`() {
        val sons = listOf(demarrage, extinction)
        // Pris par le son 1 : le son 2 ne peut pas le cocher.
        assertEquals(sons, SoundAutomationRules.toggle(sons, 1, SoundEvent.READY_ON))
        // Libre : coché, puis décoché.
        val avecD = SoundAutomationRules.toggle(sons, 1, SoundEvent.DRIVE)
        assertTrue(SoundEvent.DRIVE in avecD[1].events)
        assertFalse(SoundEvent.DRIVE in SoundAutomationRules.toggle(avecD, 1, SoundEvent.DRIVE)[1].events)
        assertEquals(0, SoundAutomationRules.owner(SoundEvent.READY_ON, sons))
    }

    @Test fun `le garde-fou ignore ce qui arrive pendant le son`() {
        val g = SoundGuard()
        assertTrue(g.claim(0L, 3_200))
        assertFalse(g.claim(1_000L, 2_400))
        assertEquals(2_200L, g.remainingMs(1_000L))
        assertTrue(g.claim(3_200L, 2_400))
        g.release()
        assertTrue(g.claim(3_300L, 1_000))
    }

    @Test fun `le rapport ne compte qu'aux changements vers P ou D`() {
        assertNull(SoundAutomationRules.gearEvent(null, 1))
        assertNull(SoundAutomationRules.gearEvent(1, 1))
        assertNull(SoundAutomationRules.gearEvent(4, null))
        assertEquals(SoundEvent.DRIVE, SoundAutomationRules.gearEvent(1, 4))
        assertEquals(SoundEvent.PARK, SoundAutomationRules.gearEvent(4, 1))
        assertNull(SoundAutomationRules.gearEvent(1, 2))
    }

    @Test fun `profils, volume et restauration`() {
        assertTrue(SoundAutomationRules.profileAllowed(emptySet(), null))
        assertTrue(SoundAutomationRules.profileAllowed(setOf("a"), "a"))
        assertFalse(SoundAutomationRules.profileAllowed(setOf("a"), "b"))
        assertFalse(SoundAutomationRules.profileAllowed(setOf("a"), null))
        assertEquals(19, SoundAutomationRules.volumeFor(SoundSlot(), 39))
        assertEquals(39, SoundAutomationRules.volumeFor(SoundSlot(volume = 50), 39))
        assertTrue(SoundAutomationRules.shouldRestore(20, 20))
        assertFalse(SoundAutomationRules.shouldRestore(14, 20))
    }

    @Test fun `les evenements s'enregistrent et se relisent`() {
        val e = setOf(SoundEvent.PROFILE, SoundEvent.APP_START)
        assertEquals("app_start,profile", SoundAutomationRules.eventsCsv(e))
        assertEquals(e, SoundAutomationRules.parseEvents("app_start, profile,inconnu"))
        assertEquals(emptySet<SoundEvent>(), SoundAutomationRules.parseEvents(null))
    }

    // ── Issue #190 : le son a fait passer la voiture sur « En ligne » ──

    @Test
    fun `la source d'avant est relancee quand le son a laisse la voiture sur En ligne`() {
        assertEquals(AudioSource.RADIO, SoundAutomationRules.sourceToRestore(AudioSource.RADIO, AudioSource.ONLINE))
        assertEquals(AudioSource.BLUETOOTH, SoundAutomationRules.sourceToRestore(AudioSource.BLUETOOTH, AudioSource.ONLINE))
    }

    @Test
    fun `rien n'est relance sans bascule vers En ligne`() {
        assertNull(SoundAutomationRules.sourceToRestore(AudioSource.RADIO, AudioSource.RADIO))
        // Le conducteur a choisi une autre source pendant le son : on n'y touche pas.
        assertNull(SoundAutomationRules.sourceToRestore(AudioSource.RADIO, AudioSource.USB))
        // Déjà sur « En ligne » avant le son, ou source illisible avant ou après.
        assertNull(SoundAutomationRules.sourceToRestore(AudioSource.ONLINE, AudioSource.ONLINE))
        assertNull(SoundAutomationRules.sourceToRestore(null, AudioSource.ONLINE))
        assertNull(SoundAutomationRules.sourceToRestore(AudioSource.RADIO, null))
    }
}
