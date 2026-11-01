package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** États de charge et motifs d'arrêt, noms des lanceurs d'origine (SWI133, SWI69 / SWI132). */
class ChargeStateNamesTest {

    @Test fun `les etats sont ceux des lanceurs d origine`() {
        assertEquals("Unplugged", ChargeStateNames.etat(0))
        assertEquals("Charging complete", ChargeStateNames.etat(2))
        assertEquals("Fast charging (offboard)", ChargeStateNames.etat(10))
        // 11 = charge « super offboard », pas du V2L
        assertEquals("Super offboard charging", ChargeStateNames.etat(11))
        assertEquals("Discharging (V2L)", ChargeStateNames.etat(13))
    }

    @Test fun `une valeur illisible ou hors des tables n a pas de nom`() {
        assertNull(ChargeStateNames.etat(null))
        assertNull(ChargeStateNames.etat(16))
        assertNull(ChargeStateNames.etat(1023))
    }

    @Test fun `les motifs d arret sautent de 10 a 16 comme la table SWI133`() {
        assertNull(ChargeStateNames.raisonArret(0))
        assertEquals("Grid supply abnormal", ChargeStateNames.raisonArret(1))
        assertNull(ChargeStateNames.raisonArret(11))
        assertEquals("Onboard charging system abnormal", ChargeStateNames.raisonArret(16))
        assertEquals("Charging over voltage", ChargeStateNames.raisonArret(23))
    }

    @Test fun `seuls les signaux qui changent sont decrits`() {
        val avant = ChargeStateSignals(statut = 7, prise = 1, ac = 0, dc = 0, raison = 0, v2x = null)
        assertEquals("", ChargeStateNames.changements(avant, avant))
        assertEquals("statut 7 → 1 · AC 0 → 2",
            ChargeStateNames.changements(avant, avant.copy(statut = 1, ac = 2)))
        assertEquals("V2X – → 1", ChargeStateNames.changements(avant, avant.copy(v2x = 1)))
    }
}
