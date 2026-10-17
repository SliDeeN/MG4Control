package com.mg4.control.shortcut

import com.mg4.control.shortcut.AudioSource.BLUETOOTH
import com.mg4.control.shortcut.AudioSource.ONLINE
import com.mg4.control.shortcut.AudioSource.PROJECTION
import com.mg4.control.shortcut.AudioSource.RADIO
import com.mg4.control.shortcut.AudioSource.USB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cycle de sources audio (2026-10-15) — la règle, sans Android. */
class AudioSourceCycleTest {

    @Test
    fun `sans cycle compose, radio puis Bluetooth`() {
        assertEquals(listOf(RADIO, BLUETOOTH), AudioSourceCycle.resolve(null))
        assertEquals(listOf(RADIO, BLUETOOTH), AudioSourceCycle.resolve(""))
    }

    @Test
    fun `le cycle enregistre se relit dans le meme ordre`() {
        val ordre = listOf(PROJECTION, USB, RADIO)
        assertEquals(ordre, AudioSourceCycle.resolve(AudioSourceCycle.encode(ordre)))
    }

    @Test
    fun `une valeur inconnue ou un doublon est ignore`() {
        assertEquals(listOf(BLUETOOTH, ONLINE), AudioSourceCycle.resolve("bluetooth, cassette ,online,bluetooth"))
        // Moins de deux sources connues : ce n'est plus un cycle, on retombe sur le défaut.
        assertEquals(listOf(RADIO, BLUETOOTH), AudioSourceCycle.resolve("usb,cassette"))
    }

    @Test
    fun `chaque appui passe a la source suivante, en rebouclant`() {
        val cycle = listOf(RADIO, BLUETOOTH, PROJECTION)
        assertEquals(BLUETOOTH, AudioSourceCycle.next(cycle, RADIO))
        assertEquals(PROJECTION, AudioSourceCycle.next(cycle, BLUETOOTH))
        assertEquals(RADIO, AudioSourceCycle.next(cycle, PROJECTION))
    }

    @Test
    fun `au premier appui ou hors du cycle, on entre par la premiere`() {
        val cycle = listOf(RADIO, BLUETOOTH)
        assertEquals(RADIO, AudioSourceCycle.next(cycle, null))
        assertEquals(RADIO, AudioSourceCycle.next(cycle, USB))
        assertNull(AudioSourceCycle.next(emptyList(), RADIO))
    }

    @Test
    fun `une source indisponible ne bloque pas le cycle`() {
        // On repart de la DERNIÈRE SOURCE CHOISIE, pas de celle qui joue : sans clé USB, la radio
        // continue de jouer, et partir d'elle viserait l'USB à chaque appui.
        val cycle = listOf(RADIO, USB, BLUETOOTH)
        assertEquals(BLUETOOTH, AudioSourceCycle.target(cycle, derniere = USB, enAttente = null))
    }

    @Test
    fun `deux appuis rapproches sautent une source`() {
        val cycle = listOf(RADIO, BLUETOOTH, PROJECTION)
        assertEquals(PROJECTION, AudioSourceCycle.target(cycle, derniere = RADIO, enAttente = BLUETOOTH))
    }

    @Test
    fun `les codes de source voiture donnent nos sources`() {
        assertEquals(RADIO, AudioSource.fromCarCode(1))       // FM
        assertEquals(RADIO, AudioSource.fromCarCode(3))       // DAB (et FM sur SWI133)
        assertEquals(BLUETOOTH, AudioSource.fromCarCode(5))
        assertEquals(USB, AudioSource.fromCarCode(6))         // port 1
        assertEquals(USB, AudioSource.fromCarCode(7))         // port 2
        assertEquals(ONLINE, AudioSource.fromCarCode(14))
        assertEquals(PROJECTION, AudioSource.fromCarCode(50)) // CarPlay
        assertEquals(PROJECTION, AudioSource.fromCarCode(70)) // Android Auto
        assertNull(AudioSource.fromCarCode(0))
        assertNull(AudioSource.fromCarCode(-1))
        assertNull(AudioSource.fromCarCode(0x12))             // vidéo USB : pas une de nos sources
    }

    @Test
    fun `le cycle repart de la source qui joue`() {
        // Passé sur la radio à l'écran après avoir choisi l'USB : le cycle suit la radio.
        val d = AudioSourceCycle.depart(actif = RADIO, derniere = USB, enAttente = null, avant = null)
        assertEquals(RADIO, d)
        assertEquals(BLUETOOTH, AudioSourceCycle.next(listOf(RADIO, BLUETOOTH, USB), d))
    }

    @Test
    fun `une bascule qui n a rien change ne coince pas le cycle`() {
        // Pas de clé : l'USB n'a pas pris, la radio d'avant joue toujours → on repart de l'USB, on le saute.
        val d = AudioSourceCycle.depart(actif = RADIO, derniere = USB, enAttente = null, avant = RADIO)
        assertEquals(USB, d)
        assertEquals(BLUETOOTH, AudioSourceCycle.next(listOf(RADIO, USB, BLUETOOTH), d))
        // Même règle quand on réappuie pendant que la source visée démarre encore.
    }

    @Test
    fun `une source changee a la main n est pas prise pour un echec`() {
        // Journal SWI133 du 2026-10-16 : USB → radio (prise), puis Bluetooth choisi à l'écran.
        // La source d'avant (USB) ne joue plus : le cycle part du Bluetooth, il vise l'USB.
        val cycle = listOf(RADIO, BLUETOOTH, USB)
        val d = AudioSourceCycle.depart(actif = BLUETOOTH, derniere = RADIO, enAttente = null, avant = USB)
        assertEquals(BLUETOOTH, d)
        assertEquals(USB, AudioSourceCycle.next(cycle, d))
    }

    @Test
    fun `source illisible ou appui en attente gardent les regles d avant`() {
        assertEquals(USB, AudioSourceCycle.depart(actif = null, derniere = USB, enAttente = null, avant = RADIO))
        assertEquals(BLUETOOTH, AudioSourceCycle.depart(actif = RADIO, derniere = USB, enAttente = BLUETOOTH, avant = RADIO))
    }

    @Test
    fun `pas de musique en ligne sur A9`() {
        assertEquals(listOf(RADIO, BLUETOOTH, USB, PROJECTION), AudioSourceCycle.offertes(a9 = true))
        assertEquals(listOf(RADIO, BLUETOOTH, USB, ONLINE, PROJECTION), AudioSourceCycle.offertes(a9 = false))
    }

    @Test
    fun `une source enregistree mais absente du firmware est ignoree`() {
        val a9 = AudioSourceCycle.offertes(a9 = true)
        assertEquals(listOf(RADIO, USB), AudioSourceCycle.resolve("radio,online,usb", a9))
        // Reste moins de deux sources : on retombe sur le défaut.
        assertEquals(listOf(RADIO, BLUETOOTH), AudioSourceCycle.resolve("online,usb", a9))
        // Hors A9, rien ne change.
        assertEquals(listOf(ONLINE, USB), AudioSourceCycle.resolve("online,usb"))
    }
}
