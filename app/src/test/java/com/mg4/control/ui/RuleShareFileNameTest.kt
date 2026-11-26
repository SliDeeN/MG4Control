package com.mg4.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nom du fichier d'une automatisation partagée seule (2026-11-26). */
class RuleShareFileNameTest {

    @Test fun `accents, spaces and symbols become a clean name`() {
        assertEquals("mg4control-automatisation-recup-au-demarrage.json", CustomRulesPage.nomFichier("Récup au démarrage"))
        assertEquals("mg4control-automatisation-clim-21-c-si-5-c.json", CustomRulesPage.nomFichier("  Clim 21 °C si < 5 °C !"))
    }

    @Test fun `long or empty names stay usable`() {
        val long = CustomRulesPage.nomFichier("a".repeat(80))
        assertEquals("mg4control-automatisation-" + "a".repeat(40) + ".json", long)
        assertEquals("mg4control-automatisation-1.json", CustomRulesPage.nomFichier("°°°"))
    }

    @Test fun `every shared file is found by the import prefix`() {
        assertTrue(CustomRulesPage.nomFichier("Sons portes").startsWith(CustomRulesPage.PREFIXE))
        assertTrue(CustomRulesPage.FICHIER.startsWith(CustomRulesPage.PREFIXE))
    }
}
