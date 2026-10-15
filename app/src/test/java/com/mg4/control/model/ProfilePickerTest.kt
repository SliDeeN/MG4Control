package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sélecteur de profils — largeur des cartes de la rangée.
 *
 * La rangée fait 1076 dp. Jusqu'à cinq profils, toutes les cartes y tiennent ; au-delà elle
 * défile, et il faut que cela se VOIE : la sixième carte doit dépasser du bord.
 */
class ProfilePickerTest {

    private fun largeurRangee(nombre: Int) =
        nombre * ProfilePicker.largeurCarteDp(nombre) + (nombre - 1) * ProfilePicker.ECART_DP

    @Test
    fun `jusqu a cinq profils la rangee tient sans defiler`() {
        for (nombre in 1..5) {
            assertTrue("$nombre profil(s)", largeurRangee(nombre) <= ProfilePicker.RANGEE_DP + 0.01f)
        }
    }

    @Test
    fun `quatre ou cinq cartes se partagent toute la rangee`() {
        assertEquals(ProfilePicker.RANGEE_DP, largeurRangee(4), 0.01f)
        assertEquals(ProfilePicker.RANGEE_DP, largeurRangee(5), 0.01f)
    }

    @Test
    fun `peu de profils ne donnent pas des cartes demesurees`() {
        // Un seul profil remplirait la rangée d'une carte de 1076 dp : elle est plafonnée.
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(1), 0f)
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(2), 0f)
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(3), 0f)
    }

    @Test
    fun `au dela de cinq la sixieme carte depasse pour montrer qu on peut defiler`() {
        for (nombre in 6..12) {
            val carte = ProfilePicker.largeurCarteDp(nombre)
            val cinq = 5 * carte + 5 * ProfilePicker.ECART_DP
            // Cinq cartes entières sont visibles…
            assertTrue(cinq < ProfilePicker.RANGEE_DP)
            // … et la sixième se devine sur 30 à 70 dp, ni cachée ni presque entière.
            val depassement = ProfilePicker.RANGEE_DP - cinq
            assertTrue("dépassement de $depassement dp", depassement in 30f..70f)
        }
    }

    // ── Ouverture sur le profil actif (2026-10-15) ───────────────────────────

    @Test
    fun `le joystick part du profil actif`() {
        assertEquals(7, ProfilePicker.carteDeDepart(rangActif = 7, nombre = 10, restreint = false))
        assertEquals(2, ProfilePicker.carteDeDepart(rangActif = 2, nombre = 3, restreint = false))
    }

    @Test
    fun `en conflit Bluetooth le joystick part de la carte qu applique le delai`() {
        assertEquals(0, ProfilePicker.carteDeDepart(rangActif = 1, nombre = 2, restreint = true))
    }

    @Test
    fun `sans profil actif dans la liste on part du premier`() {
        assertEquals(0, ProfilePicker.carteDeDepart(rangActif = -1, nombre = 10, restreint = false))
        assertEquals(0, ProfilePicker.carteDeDepart(rangActif = 12, nombre = 10, restreint = false))
    }

    @Test
    fun `une carte deja visible ne fait pas defiler la rangee`() {
        // Jusqu'à cinq profils rien ne défile ; au-delà, les cinq premières cartes tiennent.
        assertEquals(0f, ProfilePicker.defilementInitialDp(rang = 3, nombre = 5), 0f)
        for (rang in 0..4) assertEquals("carte $rang", 0f, ProfilePicker.defilementInitialDp(rang, 10), 0f)
    }

    @Test
    fun `le profil actif au dela du cinquieme est amene entier a l ecran avec sa voisine`() {
        val carte = ProfilePicker.largeurCarteDp(10)
        for (rang in 5..8) {
            val defile = ProfilePicker.defilementInitialDp(rang, 10)
            val gauche = rang * (carte + ProfilePicker.ECART_DP) - defile
            val droite = gauche + carte
            assertTrue("carte $rang coupée à gauche", gauche >= 0f)
            assertTrue("carte $rang coupée à droite", droite <= ProfilePicker.RANGEE_DP + 0.01f)
            // Un quart de la suivante se devine.
            assertEquals("carte $rang", ProfilePicker.RANGEE_DP - carte / 4f, droite, 0.01f)
        }
    }

    @Test
    fun `la derniere carte colle au bord sans defiler dans le vide`() {
        val carte = ProfilePicker.largeurCarteDp(10)
        val total = 10 * carte + 9 * ProfilePicker.ECART_DP
        assertEquals(total - ProfilePicker.RANGEE_DP, ProfilePicker.defilementInitialDp(9, 10), 0.01f)
    }

    @Test
    fun `un nombre absurde ne casse rien`() {
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(0), 0f)
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(-3), 0f)
    }
}
