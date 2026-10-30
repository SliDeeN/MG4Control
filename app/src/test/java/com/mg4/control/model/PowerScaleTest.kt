package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Échelle du module Puissance (issue #165) : paliers, montée immédiate, descente lente. */
class PowerScaleTest {

    @Test fun `le palier contient la pointe avec sa marge`() {
        assertEquals(100, PowerScale.palier(60f))
        assertEquals(125, PowerScale.palier(110f))
        assertEquals(180, PowerScale.palier(150f))
        assertEquals(320, PowerScale.palier(310f))
        assertEquals(320, PowerScale.palier(500f))
    }

    @Test fun `une pointe au dela du plafond le fait monter tout de suite`() {
        assertEquals(150, PowerScale.apresReleve(150, 140f))
        assertEquals(180, PowerScale.apresReleve(150, 160f))
        assertEquals(320, PowerScale.apresReleve(320, 330f))
        // Il ne descend jamais en roulant.
        assertEquals(250, PowerScale.apresReleve(250, 40f))
    }

    @Test fun `il ne redescend qu'apres cinq trajets calmes, d'un palier`() {
        assertEquals(180, PowerScale.apresTrajet(180, listOf(60f, 60f, 60f, 60f)))
        assertEquals(150, PowerScale.apresTrajet(180, listOf(60f, 60f, 60f, 60f, 60f)))
        // Un trajet à 70 % ou plus du plafond le garde.
        assertEquals(180, PowerScale.apresTrajet(180, listOf(60f, 130f, 60f, 60f, 60f)))
        // Jamais sous le palier de la plus forte pointe récente, ni sous 100 kW.
        assertEquals(100, PowerScale.apresTrajet(125, listOf(30f, 30f, 30f, 30f, 30f)))
        assertEquals(100, PowerScale.apresTrajet(100, listOf(30f, 30f, 30f, 30f, 30f)))
    }

    @Test fun `le choix l'emporte sur l'appris`() {
        assertEquals(150f, PowerScale.pleineEchelle(null, 150))
        assertEquals(180f, PowerScale.pleineEchelle(180, 125))
        assertEquals(125f, PowerScale.pleineEchelle(999, 125))
    }
}
