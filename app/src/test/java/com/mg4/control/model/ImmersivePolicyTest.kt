package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Règle commune MG4Dash / MG4Control pour `policy_control` (issue #248) : les 20 cas convenus
 * avec le développeur de MG4Dash (1-11 les siens, 12-16 les modes partiels, 17-20 ses précisions).
 * D = app.mg4dash ; A, W, O, p1… = paquets quelconques.
 */
class ImmersivePolicyTest {

    private val D = ImmersivePolicy.MG4DASH
    private val C = "com.mg4.control"
    private val COFF = "com.mg4.control.offline"

    /** Une voiture : la valeur, les clés des propriétaires, ceux qui sont installés. */
    private inner class Voiture(var policy: String? = null) {
        val keys = mutableMapOf<String, String?>()
        val installed = mutableSetOf(D, C, COFF)

        /** Un passage de [self], appliqué dans l'ordre convenu. */
        fun passe(self: String, voulus: String?): Voiture {
            val p = ImmersivePolicy.plan(self, ImmersivePolicy.parseKey(voulus), policy, keys, installed)
            keys[self] = p.keyBefore
            if (p.policyChanged) policy = p.policy
            keys[self] = p.keyAfter
            p.clearKeys.forEach { k -> keys.remove(ImmersivePolicy.OWNER_KEYS.entries.first { it.value == k }.key) }
            // Un second passage identique ne change plus rien.
            assertFalse(ImmersivePolicy.plan(self, ImmersivePolicy.parseKey(voulus), policy, keys, installed).policyChanged)
            return this
        }
    }

    @Test fun `01 meme paquet des deux cotes`() {
        val v = Voiture().passe(C, "A,W").passe(D, "$D,W").passe(D, "$D,O")
        assertEquals("immersive.full=A,W,$D,O", v.policy)
    }

    @Test fun `02 plein ecran de MG4Dash coupe`() {
        val v = Voiture().passe(C, "A,W").passe(D, "$D,W").passe(D, "$D,O").passe(D, null)
        assertEquals("immersive.full=A,W", v.policy)
    }

    @Test fun `03 MG4Control desinstalle`() {
        val v = Voiture().passe(C, "A,W").passe(D, "$D,W").passe(D, "$D,O")
        v.installed.remove(C)
        v.passe(D, "$D,O")
        assertEquals("immersive.full=$D,O", v.policy)
        assertNull(v.keys[C])
    }

    @Test fun `04 section etrangere`() {
        val v = Voiture("immersive.full=com.x:immersive.navigation=com.y").passe(D, "$D,W")
        assertEquals("immersive.full=com.x,$D,W:immersive.navigation=com.y,$D,W", v.policy)
    }

    @Test fun `05 ancienne valeur de MG4Dash sans cle`() {
        val v = Voiture("immersive.full=$D,W,com.allgo.carplay.service").passe(D, "$D,O")
        assertEquals("immersive.full=$D,O", v.policy)
    }

    @Test fun `06 exclusion`() {
        val v = Voiture("immersive.full=apps,-W").passe(D, "$D,W")
        assertEquals("immersive.full=apps,-W,$D", v.policy)
    }

    @Test fun `07 deux sections full`() {
        val v = Voiture("immersive.full=com.a:immersive.full=com.b").passe(D, D)
        assertEquals("immersive.full=com.a,com.b,$D", v.policy)
    }

    @Test fun `08 liste longue`() {
        val v = Voiture().passe(C, "p1,p2,p3,p4,p5").passe(D, "$D,p3").passe(C, "p1,p2,p4")
        assertEquals("immersive.full=p1,p2,p3,p4,$D", v.policy)
    }

    @Test fun `09 Android Auto et CarPlay`() {
        val v = Voiture("immersive.full=com.allgo.carplay.service").passe(C, "A,com.allgo.app.androidauto")
        assertEquals("immersive.full=A", v.policy)
    }

    @Test fun `10 plus rien`() {
        val v = Voiture().passe(C, "A")
        assertEquals("immersive.full=A", v.policy)
        v.passe(C, null)
        assertNull(v.policy)
        assertNull(v.keys[C])
    }

    @Test fun `11 preconfirms seule`() {
        val v = Voiture("immersive.preconfirms=com.z").passe(D, "$D,W")
        assertEquals("immersive.full=$D,W:immersive.preconfirms=com.z,$D,W", v.policy)
    }

    @Test fun `12 bandeau seul`() {
        assertEquals("immersive.status=W", Voiture().passe(C, "W/status").policy)
    }

    @Test fun `13 bandeau seul puis MG4Dash`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,O")
        assertEquals("immersive.full=$D,O:immersive.status=W,$D,O", v.policy)
    }

    @Test fun `14 changement de mode`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,O").passe(C, "W")
        assertEquals("immersive.full=$D,O,W", v.policy)
    }

    @Test fun `15 meme appli deux modes, tout l emporte`() {
        val v = Voiture().passe(C, "W/navigation").passe(D, "$D,W")
        assertEquals("immersive.full=$D,W", v.policy)
    }

    @Test fun `16 retrait du mode partiel`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,O").passe(C, null)
        assertEquals("immersive.full=$D,O", v.policy)
    }

    @Test fun `17 section morte`() {
        val v = Voiture("immersive.status=com.y:immersive.full=com.x").passe(C, "W/status")
        assertEquals("immersive.full=com.x:immersive.status=com.x,W", v.policy)
    }

    @Test fun `18 deux variantes, deux modes`() {
        val v = Voiture().passe(C, "W/status").passe(COFF, "W/navigation")
        assertEquals("immersive.status=W:immersive.navigation=W", v.policy)
    }

    @Test fun `19 MG4Dash retire une appli`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,O").passe(D, D)
        assertEquals("immersive.full=$D:immersive.status=W,$D", v.policy)
    }

    @Test fun `20 section etrangere identique`() {
        val v = Voiture("immersive.full=com.x:immersive.status=com.x").passe(D, D)
        assertEquals("immersive.full=com.x,$D", v.policy)
    }

    @Test fun `lecture des cles`() {
        // Suffixe inconnu ignoré ; W et W/status = tout ; interdits exclus quel que soit le suffixe.
        val k = ImmersivePolicy.parseKey("W,W/status,X/futur,Y/navigation,com.allgo.carplay.service/status,*,apps,-Z, ,")
        assertEquals(setOf("W", "Y"), k.keys)
        assertEquals("W,Y/navigation", ImmersivePolicy.formatKey(k))
    }
}
