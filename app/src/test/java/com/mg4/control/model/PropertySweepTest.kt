package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sonde clé — comparer deux relevés de TOUTES les propriétés du véhicule, clé dans la voiture
 * puis clé éloignée, pour voir si quoi que ce soit bouge.
 *
 * Aucune propriété ne porte le nom d'un signal de clé (recherche du 2026-10-11 sur cinq
 * firmwares) : seul un balayage à l'aveugle peut dire s'il en existe une sous un autre nom.
 */
class PropertySweepTest {

    // ── Quelles propriétés relire ────────────────────────────────────────────

    @Test
    fun `les entiers et les booleens du constructeur sont balayes`() {
        assertTrue(PropertySweep.sweepable(0x21401582))    // SENSOR_PEPSAVLBLY, entier
        assertTrue(PropertySweep.sweepable(0x2540c617))    // DLOCK_UNLOCK_WITH_KEY, entier par siège
        assertTrue(PropertySweep.sweepable(0x21209320))    // feux de croisement, booléen
    }

    @Test
    fun `les proprietes du systeme ne sont pas balayees`() {
        // Groupe « système » : on y trouve les commandes d'usine (EOL) et d'alimentation, que
        // personne ne relit en usage normal. On s'en tient à ce que lisent les écrans d'origine.
        assertFalse(PropertySweep.sweepable(0x11408a07))   // PMS_SYSTEM_RESTART
        assertFalse(PropertySweep.sweepable(0x11400409))   // IGNITION_STATE
    }

    @Test
    fun `les flottants et les blocs d octets ne sont pas balayes`() {
        // Un flottant (vitesse, énergie) bouge tout seul ; un bloc d'octets est un code d'équipement.
        assertFalse(PropertySweep.sweepable(0x2160f404))   // SoC, flottant
        assertFalse(PropertySweep.sweepable(0x21704230))   // CAL_REMOTE_KEYLESS_ENTRY_SYSTEM, octets
    }

    // ── Ce qui a changé entre deux relevés ───────────────────────────────────

    @Test
    fun `une valeur qui change est signalee avec ses deux valeurs`() {
        val avant = mapOf("0x21401582@0x1000000" to "1", "0x2140157f@0x1000000" to "1")
        val apres = mapOf("0x21401582@0x1000000" to "0", "0x2140157f@0x1000000" to "1")
        assertEquals(listOf("0x21401582@0x1000000 : 1 \u2192 0"), PropertySweep.changes(avant, apres))
    }

    @Test
    fun `rien n est signale quand rien ne bouge`() {
        val releve = mapOf("0x21401582@0x1000000" to "1")
        assertEquals(emptyList<String>(), PropertySweep.changes(releve, releve))
    }

    @Test
    fun `une propriete lue une seule des deux fois n est pas un changement`() {
        // Une lecture qui échoue une fois sur deux n'apprend rien sur la clé.
        val avant = mapOf("a" to "1")
        val apres = mapOf("b" to "1")
        assertEquals(emptyList<String>(), PropertySweep.changes(avant, apres))
    }

    @Test
    fun `les changements sortent dans un ordre stable`() {
        val avant = mapOf("b" to "0", "a" to "0", "c" to "0")
        val apres = mapOf("c" to "1", "a" to "1", "b" to "1")
        assertEquals(
            listOf("a : 0 \u2192 1", "b : 0 \u2192 1", "c : 0 \u2192 1"),
            PropertySweep.changes(avant, apres))
    }

    // ── Affichage ────────────────────────────────────────────────────────────

    @Test
    fun `la cle d un releve porte l identifiant et la zone en hexadecimal`() {
        assertEquals("0x21401582@0x1000000", PropertySweep.key(0x21401582, 0x1000000))
        assertEquals("0x2540c617@0x1", PropertySweep.key(0x2540c617, 1))
    }

    @Test
    fun `un bloc d octets s affiche en hexadecimal et reste court`() {
        assertEquals("01 ff 00", PropertySweep.hex(byteArrayOf(1, -1, 0)))
        assertEquals("(vide)", PropertySweep.hex(byteArrayOf()))
        // Un code d'équipement peut faire des dizaines d'octets : le début suffit à le comparer.
        assertEquals("00 01 02 03\u2026 (6 octets)", PropertySweep.hex(byteArrayOf(0, 1, 2, 3, 4, 5), max = 4))
    }
}
