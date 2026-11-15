package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Ignore
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

    // ── Revue du 2026-11-15 : défauts de la règle commune, reproduits ici. ──

    /** Applique un plan déjà calculé (sans recalcul), comme un passage interrompu par l'autre appli. */
    private fun Voiture.applique(self: String, p: ImmersivePolicy.Plan) {
        keys[self] = p.keyBefore
        if (p.policyChanged) policy = p.policy
        keys[self] = p.keyAfter
    }

    @Ignore("Défaut reproduit (revue du 2026-11-15) : la règle commune doit changer, en attente de l'accord du développeur de MG4Dash")
    @Test fun `R1 un mode partiel survit au retrait par MG4Dash`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,W").passe(D, D)
        assertEquals("immersive.full=$D:immersive.status=$D,W", v.policy)
    }

    @Ignore("Défaut reproduit (revue du 2026-11-15) : la règle commune doit changer, en attente de l'accord du développeur de MG4Dash")
    @Test fun `R2 deux retraits simultanes ne laissent rien de bloque`() {
        val v = Voiture().passe(C, "W").passe(D, "$D,W")
        // Les deux calculent leur retrait de W sur la même lecture, puis écrivent l'un après l'autre.
        val pc = ImmersivePolicy.plan(C, emptyMap(), v.policy, v.keys, v.installed)
        val pd = ImmersivePolicy.plan(D, ImmersivePolicy.parseKey(D), v.policy, v.keys, v.installed)
        v.applique(C, pc)
        v.applique(D, pd)
        // Les relectures à 5 s.
        v.passe(C, null).passe(D, D)
        assertEquals("immersive.full=$D", v.policy)
    }

    @Ignore("Défaut reproduit (revue du 2026-11-15) : la règle commune doit changer, en attente de l'accord du développeur de MG4Dash")
    @Test fun `R3 une section vide a cote de full n est pas supprimee`() {
        // com.x ne cache que le bandeau : la section navigation vide le dit à Android.
        val v = Voiture("immersive.full=com.x:immersive.navigation=").passe(C, "W/status")
        assertEquals("immersive.full=com.x:immersive.status=com.x,W:immersive.navigation=", v.policy)
    }

    @Ignore("Défaut reproduit (revue du 2026-11-15) : la règle commune doit changer, en attente de l'accord du développeur de MG4Dash")
    @Test fun `R4 une regle globale exclut Android Auto et CarPlay`() {
        val v = Voiture("immersive.full=apps").passe(D, D)
        assertEquals("immersive.full=apps,-com.allgo.app.androidauto,-com.allgo.carplay.service,$D", v.policy)
    }

    @Test fun `R5 rien a faire sans demande ni reste`() {
        // Option jamais utilisée : la valeur d'un autre (même avec CarPlay) n'est pas « rangée ».
        val cles = mapOf<String, String?>()
        assertFalse(ImmersivePolicy.hasStake(C, emptyMap(), cles, setOf(D, C, COFF)))
        assertEquals(true, ImmersivePolicy.hasStake(C, emptyMap(), mapOf(C to "W"), setOf(D, C, COFF)))
        assertEquals(true, ImmersivePolicy.hasStake(C, ImmersivePolicy.parseKey("W"), cles, setOf(D, C, COFF)))
        // Clé laissée par un propriétaire désinstallé : à nettoyer.
        assertEquals(true, ImmersivePolicy.hasStake(C, emptyMap(), mapOf(COFF to "W"), setOf(D, C)))
    }

    @Test fun `lecture des cles`() {
        // Suffixe inconnu ignoré ; W et W/status = tout ; interdits exclus quel que soit le suffixe.
        val k = ImmersivePolicy.parseKey("W,W/status,X/futur,Y/navigation,com.allgo.carplay.service/status,*,apps,-Z, ,")
        assertEquals(setOf("W", "Y"), k.keys)
        assertEquals("W,Y/navigation", ImmersivePolicy.formatKey(k))
    }
}
