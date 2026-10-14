package com.mg4.control.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Actualisation de la jauge du combiné : 0,1 à 1 s par pas de 0,1 s (Réglages › Interface). */
class ClusterModuleSettingsTest {

    @Test fun `sans reglage enregistre, une demi-seconde`() {
        assertEquals(500, ClusterModuleSettings.sanitizeRefresh(null))
    }

    @Test fun `une valeur aberrante revient dans la plage`() {
        assertEquals(100, ClusterModuleSettings.sanitizeRefresh(0))
        assertEquals(100, ClusterModuleSettings.sanitizeRefresh(-300))
        assertEquals(1000, ClusterModuleSettings.sanitizeRefresh(5000))
    }

    @Test fun `la valeur se cale sur un pas de 100 ms`() {
        assertEquals(100, ClusterModuleSettings.sanitizeRefresh(149))
        assertEquals(500, ClusterModuleSettings.sanitizeRefresh(450))
        assertEquals(300, ClusterModuleSettings.sanitizeRefresh(300))
    }

    @Test fun `le curseur va de 1 a 10 dixiemes de seconde`() {
        assertEquals(100, ClusterModuleSettings.refreshFromSlider(1f))
        assertEquals(300, ClusterModuleSettings.refreshFromSlider(3f))
        assertEquals(1000, ClusterModuleSettings.refreshFromSlider(10f))
        // Un curseur à virgule flottante peut rendre 2,9999 : c'est bien 300 ms.
        assertEquals(300, ClusterModuleSettings.refreshFromSlider(2.9999f))
        assertEquals(3f, ClusterModuleSettings.refreshToSlider(300), 0f)
    }
}
