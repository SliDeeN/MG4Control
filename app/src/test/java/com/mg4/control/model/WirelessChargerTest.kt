package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Écran d'essai de la sonde clé — ce que valent les états du chargeur de téléphone sans fil
 * (`PHONE_WIRELESS_CHAEGER_WORKING_STATE`, 0x2140f445).
 *
 * Seule propriété à avoir bougé quand la clé s'est éloignée (SWI133, 2026-10-11 : 1 → 8). Le
 * code d'origine n'en interprète que deux valeurs ; l'écran doit dire lesquelles il CONNAÎT et
 * ne rien inventer pour les autres — c'est justement le sens de 8 que l'essai doit trouver.
 */
class WirelessChargerTest {

    @Test
    fun `les deux valeurs que les ecrans d origine interpretent sont nommees`() {
        assertEquals("2 = téléphone en charge", WirelessCharger.describe(2))
        assertEquals("5 = objet étranger sur le socle", WirelessCharger.describe(5))
    }

    @Test
    fun `les valeurs de l enumeration du HAL sont donnees comme telles`() {
        // Moins sûres que les deux précédentes : l'énumération ne va que de 0 à 3.
        assertTrue(WirelessCharger.describe(0).endsWith("(énumération du HAL)"))
        assertTrue(WirelessCharger.describe(1).endsWith("(énumération du HAL)"))
        assertTrue(WirelessCharger.describe(3).endsWith("(énumération du HAL)"))
    }

    @Test
    fun `une valeur que le code d origine ne connait pas est dite inconnue`() {
        assertEquals("8 = valeur inconnue du code d\u2019origine", WirelessCharger.describe(8))
        assertEquals("4 = valeur inconnue du code d\u2019origine", WirelessCharger.describe(4))
    }

    @Test
    fun `une lecture impossible n est pas prise pour une valeur`() {
        assertEquals("illisible", WirelessCharger.describe(null))
    }

    // ── Zones d'une propriété de porte ───────────────────────────────────────

    @Test
    fun `les quatre portes sont nommees`() {
        assertEquals("AVG", WirelessCharger.doorLabel(0x1))
        assertEquals("AVD", WirelessCharger.doorLabel(0x4))
        assertEquals("ARG", WirelessCharger.doorLabel(0x10))
        assertEquals("ARD", WirelessCharger.doorLabel(0x40))
    }

    @Test
    fun `une zone inconnue reste lisible en hexadecimal`() {
        assertEquals("0x55", WirelessCharger.doorLabel(0x55))
        assertEquals("0x0", WirelessCharger.doorLabel(0))
    }
}
