package com.mg4.control.util

import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réglages du module du combiné (Réglages › Interface) : actualisation de la jauge, 0,1 à 1 s par
 * pas de 0,1 s, et un module par onglet du combiné.
 */
class ClusterModuleSettingsTest {

    @Test fun `sans reglage enregistre, une seconde`() {
        assertEquals(1000, ClusterModuleSettings.sanitizeRefresh(null))
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

    @Test fun `la fonction n existe que la ou le combine montre l ecran central`() {
        assertTrue(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI133))
        assertTrue(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI68))
        assertTrue(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI165))
        // A9 : essai SWI69 du 2026-10-14, rien ne s'affiche.
        assertFalse(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI69))
        assertFalse(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI131))
        assertFalse(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.SWI132))
        assertFalse(ClusterModuleSettings.supportedOn(FirmwareInfo.Gen.UNKNOWN))
    }

    // ── Un module par onglet (2026-10-14) ────────────────────────────────────

    @Test fun `sans choix enregistre, l ancien reglage vaut pour la navigation et rien pour la musique`() {
        // C'est sur la navigation que le module s'affichait jusque-là.
        assertEquals(ClusterModule.POWER, ClusterModuleSettings.resolveTab(ClusterTab.NAVIGATION, null, "power"))
        assertEquals(ClusterModule.CONSUMPTION, ClusterModuleSettings.resolveTab(ClusterTab.NAVIGATION, null, null))
        assertNull(ClusterModuleSettings.resolveTab(ClusterTab.MEDIA, null, "power"))
    }

    @Test fun `un choix enregistre l emporte, aucun compris`() {
        assertEquals(ClusterModule.CONSUMPTION, ClusterModuleSettings.resolveTab(ClusterTab.MEDIA, "consumption", null))
        assertNull(ClusterModuleSettings.resolveTab(ClusterTab.NAVIGATION, "none", "power"))
    }

    @Test fun `les onglets dessines par le combine n ont jamais de module`() {
        assertNull(ClusterModuleSettings.resolveTab(ClusterTab.TRIP_COMPUTER, "power", "power"))
        assertNull(ClusterModuleSettings.resolveTab(ClusterTab.SETTINGS, "power", "power"))
    }

    @Test fun `le module montre suit l onglet du volant`() {
        val nav = ClusterModule.CONSUMPTION
        val musique = ClusterModule.POWER
        assertEquals(nav, ClusterModuleSettings.moduleToShow(1, nav, musique))
        assertEquals(musique, ClusterModuleSettings.moduleToShow(2, nav, musique))
        // Ordinateur de bord, pneus, réglages : le combiné dessine, rien à montrer.
        assertNull(ClusterModuleSettings.moduleToShow(0, nav, musique))
        assertNull(ClusterModuleSettings.moduleToShow(4, nav, musique))
        assertNull(ClusterModuleSettings.moduleToShow(5, nav, musique))
        // Musique laissée à sa page d'origine.
        assertNull(ClusterModuleSettings.moduleToShow(2, nav, null))
    }

    @Test fun `onglet illisible - le module de la navigation, a defaut celui de la musique`() {
        assertEquals(ClusterModule.CONSUMPTION, ClusterModuleSettings.moduleToShow(null, ClusterModule.CONSUMPTION, ClusterModule.POWER))
        assertEquals(ClusterModule.POWER, ClusterModuleSettings.moduleToShow(null, null, ClusterModule.POWER))
        assertNull(ClusterModuleSettings.moduleToShow(null, null, null))
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
