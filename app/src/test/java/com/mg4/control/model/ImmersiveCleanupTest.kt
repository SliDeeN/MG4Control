package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nettoyage devant un ancien MG4Dash (issue #248, revue du 2026-11-15) : seule notre clé compte. */
class ImmersiveCleanupTest {

    private val D = ImmersivePolicy.MG4DASH
    private val CP = ImmersivePolicy.CARPLAY

    @Test fun `sans cle, la valeur d un ancien MG4Dash n est pas touchee`() {
        // 1.7.3 beta 2 : CarPlay en clair ; aucune trace de MG4Control.
        val v = "immersive.full=$CP"
        assertEquals(v, ImmersiveCleanup.clean(null, v, emptyList()))
    }

    @Test fun `seuls nos paquets partent, le reste ne bouge pas`() {
        // Section morte, doublon, CarPlay, joker sans exclusions : rien de cela n'est à nous.
        val v = "immersive.status=W:immersive.full=$D,X,W,$CP,X:immersive.navigation=apps,W"
        // W en bandeau seul : retiré du bandeau seulement ; la section vidée part.
        assertEquals("immersive.full=$D,X,W,$CP,X:immersive.navigation=apps,W",
            ImmersiveCleanup.clean("W/status", v, emptyList()))
    }

    @Test fun `tout cacher retire des trois sections`() {
        val v = "immersive.full=$D,W:immersive.status=$D,W,Y:immersive.preconfirms=W"
        assertEquals("immersive.full=$D:immersive.status=$D,Y", ImmersiveCleanup.clean("W", v, emptyList()))
    }

    @Test fun `un paquet reclame par notre autre variante reste`() {
        val v = "immersive.full=W,Z"
        assertEquals("immersive.full=W", ImmersiveCleanup.clean("W,Z", v, listOf("W")))
    }

    @Test fun `section de seules exclusions supprimee, valeur vide effacee`() {
        assertEquals("immersive.full=$D", ImmersiveCleanup.clean("W", "immersive.full=$D:immersive.navigation=-Q,W", emptyList()))
        assertNull(ImmersiveCleanup.clean("W", "immersive.full=W", emptyList()))
    }

    @Test fun `rien a nous dans la valeur, chaine identique`() {
        val v = "immersive.full= $D ,X::inconnue"
        assertEquals(v, ImmersiveCleanup.clean("W", v, emptyList()))
    }
}
