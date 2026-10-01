package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hauteur du soleil, sur des valeurs de référence faciles à recouper : aux solstices, à midi
 * solaire, la hauteur vaut 90° − latitude ± 23,44°.
 */
class SunPositionTest {

    private val paris = 48.8566 to 2.3522

    private fun hauteur(lat: Double, lon: Double, ms: Long) = SunPosition.elevationDeg(lat, lon, ms)

    @Test
    fun `paris au solstice d ete a midi solaire`() {
        // 2024-06-21 11:51 UTC : 90 − 48,86 + 23,44 = 64,58°
        assertEquals(64.58, hauteur(paris.first, paris.second, 1_718_970_660_000L), 0.1)
    }

    @Test
    fun `paris au solstice d hiver a midi solaire`() {
        // 2024-12-21 11:53 UTC : 90 − 48,86 − 23,44 = 17,70°
        assertEquals(17.70, hauteur(paris.first, paris.second, 1_734_781_980_000L), 0.1)
    }

    @Test
    fun `paris en pleine nuit d ete`() {
        // 2024-06-21 23:51 UTC, culmination inférieure : 48,86 + 23,44 − 90 = −17,7°
        assertEquals(-17.71, hauteur(paris.first, paris.second, 1_719_013_860_000L), 0.1)
    }

    @Test
    fun `lever officiel a paris`() {
        // 2024-06-21 03:47 UTC : le lever officiel correspond à −0,83° sans réfraction.
        assertEquals(-0.83, hauteur(paris.first, paris.second, 1_718_941_620_000L), 0.2)
    }

    @Test
    fun `equateur a l equinoxe`() {
        assertEquals(89.8, hauteur(0.0, 0.0, 1_710_936_420_000L), 0.3)
    }

    @Test
    fun `hemisphere sud en hiver austral`() {
        // Sydney, 2024-06-21 02:00 UTC (midi local) : 90 − 33,87 − 23,44 = 32,69°
        assertEquals(32.69, hauteur(-33.87, 151.21, 1_718_935_200_000L), 0.2)
    }
}
