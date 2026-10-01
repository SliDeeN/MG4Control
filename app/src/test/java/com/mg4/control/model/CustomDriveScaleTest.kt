package com.mg4.control.model

import com.mg4.control.model.CustomDriveScale.Family
import com.mg4.control.model.CustomDriveScale.Setting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mode de conduite Personnalisé : correspondance index de l'app ↔ valeur envoyée au véhicule.
 *
 * Trois familles, trois échelles relevées dans le code d'origine : une erreur ici met la voiture
 * dans le mauvais réglage sans rien signaler.
 */
class CustomDriveScaleTest {

    private val indexes = 0..2

    @Test
    fun `puissance - 1-2-3 partout sauf A9 en 2-3-4`() {
        assertEquals(listOf(1, 2, 3), indexes.map { CustomDriveScale.value(Setting.POWER, it, Family.VPM_133) })
        assertEquals(listOf(2, 3, 4), indexes.map { CustomDriveScale.value(Setting.POWER, it, Family.A9) })
        assertEquals(listOf(1, 2, 3), indexes.map { CustomDriveScale.value(Setting.POWER, it, Family.VSM_68) })
    }

    @Test
    fun `direction - 1-2-3 partout`() {
        Family.entries.forEach { family ->
            assertEquals("famille $family", listOf(1, 2, 3),
                indexes.map { CustomDriveScale.value(Setting.STEERING, it, family) })
        }
    }

    @Test
    fun `pedale - Normal vaut ZERO sur les trois familles`() {
        // Relevé dans les écrans d'origine (SWI133, dispatch A9, DrivingSettingsViewModel SWI68/165).
        Family.entries.forEach { family ->
            assertEquals("famille $family", listOf(1, 0, 2),
                indexes.map { CustomDriveScale.value(Setting.PEDAL, it, family) })
        }
    }

    @Test
    fun `toute valeur ecrite se relit en le meme index`() {
        Family.entries.forEach { family ->
            Setting.entries.forEach { setting ->
                indexes.forEach { index ->
                    val v = CustomDriveScale.value(setting, index, family)!!
                    assertEquals("$family/$setting/$index", index, CustomDriveScale.index(setting, v, family))
                }
            }
        }
    }

    @Test
    fun `index hors 0-2 refuse`() {
        assertNull(CustomDriveScale.value(Setting.POWER, -1, Family.VPM_133))
        assertNull(CustomDriveScale.value(Setting.PEDAL, 3, Family.A9))
    }

    @Test
    fun `valeur hors echelle = inconnue`() {
        // Le véhicule qui ne porte pas l'équipement rend une valeur hors échelle (ou -1 en échec).
        assertNull(CustomDriveScale.index(Setting.POWER, -1, Family.VPM_133))
        assertNull(CustomDriveScale.index(Setting.POWER, 1, Family.A9))       // A9 commence à 2
        assertNull(CustomDriveScale.index(Setting.PEDAL, 3, Family.VPM_133))  // pédale : 1/0/2 seulement
        assertNull(CustomDriveScale.index(Setting.PEDAL, 4, Family.VSM_68))
        assertNull(CustomDriveScale.index(Setting.STEERING, 255, Family.VSM_68))
    }
}
