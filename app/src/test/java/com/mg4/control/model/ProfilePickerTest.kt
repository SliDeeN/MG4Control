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

    @Test
    fun `un nombre absurde ne casse rien`() {
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(0), 0f)
        assertEquals(ProfilePicker.CARTE_MAX_DP, ProfilePicker.largeurCarteDp(-3), 0f)
    }
}
