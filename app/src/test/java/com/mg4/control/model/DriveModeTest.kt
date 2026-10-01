package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Valeurs véhicule du mode de conduite.
 *
 * L'enjeu n'est pas le barème (stable sur les six firmwares) mais la distinction entre une
 * valeur inconnue et « Normal » : c'est elle qui décide si la carte du mode Personnalisé
 * s'ouvre. Confondre les deux la masquait sur les firmwares dont la propriété reste muette.
 */
class DriveModeTest {

    @Test
    fun `bareme releve dans les ecrans d'origine`() {
        assertEquals(DriveMode.ECO,    DriveMode.fromValueOrNull(2))
        assertEquals(DriveMode.NORMAL, DriveMode.fromValueOrNull(3))
        assertEquals(DriveMode.SPORT,  DriveMode.fromValueOrNull(4))
        assertEquals(DriveMode.SNOW,   DriveMode.fromValueOrNull(6))
        assertEquals(DriveMode.CUSTOM, DriveMode.fromValueOrNull(7))
    }

    @Test
    fun `valeur hors bareme = inconnue, jamais Normal`() {
        // -1 = lecture en échec, 0 = signal pas encore publié, 255 = capteur absent.
        listOf(-1, 0, 1, 5, 8, 255).forEach {
            assertNull("valeur $it", DriveMode.fromValueOrNull(it))
        }
    }

    @Test
    fun `le repli reste disponible pour l'affichage`() {
        assertEquals(DriveMode.NORMAL, DriveMode.fromValue(255))
        assertEquals(DriveMode.CUSTOM, DriveMode.fromValue(7))
    }
}
