package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règle commune MG4Dash / MG4Control pour `policy_control` (issue #248) : les 20 cas convenus
 * avec le développeur de MG4Dash (1-11 les siens, 12-16 les modes partiels, 17-20 ses précisions),
 * puis R1-R7 de la revue du 2026-11-15 (v2). D = app.mg4dash ; A, W, O, p1… = paquets quelconques.
 */
class ImmersivePolicyTest {

    private val D = ImmersivePolicy.MG4DASH
    private val C = "com.mg4.control"
    private val COFF = "com.mg4.control.offline"
    private val AA = ImmersivePolicy.ANDROID_AUTO
    private val CP = ImmersivePolicy.CARPLAY

    /** Une voiture : la valeur, les clés des propriétaires, la clé commune, ceux qui sont installés. */
    private inner class Voiture(var policy: String? = null) {
        val keys = mutableMapOf<String, String?>()
        var managed: String? = null
        val installed = mutableSetOf(D, C, COFF)

        fun plan(self: String, voulus: String?) =
            ImmersivePolicy.plan(self, ImmersivePolicy.parseKey(voulus), policy, keys, installed, managed)

        fun stake(self: String, voulus: String?) =
            ImmersivePolicy.hasStake(self, ImmersivePolicy.parseKey(voulus), policy, keys, installed, managed)

        /** Un passage de [self] comme dans l'appli : rien sans enjeu, sinon le plan dans l'ordre convenu. */
        fun passe(self: String, voulus: String?): Voiture {
            if (!stake(self, voulus)) return this
            applique(self, plan(self, voulus))
            // Un second passage identique ne change plus rien, et n'a plus d'enjeu sans demande.
            assertFalse(plan(self, voulus).policyChanged)
            if (voulus == null && keys[self] == null) assertFalse(stake(self, voulus))
            return this
        }

        /** Applique un plan déjà calculé (sans recalcul), par exemple interrompu par l'autre appli. */
        fun applique(self: String, p: ImmersivePolicy.Plan) {
            keys[self] = p.keyBefore
            managed = p.managedBefore
            if (p.policyChanged) policy = p.policy
            keys[self] = p.keyAfter
            managed = p.managedAfter
            p.clearKeys.forEach { k -> keys.remove(ImmersivePolicy.OWNER_KEYS.entries.first { it.value == k }.key) }
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
        val v = Voiture("immersive.full=$D,W,$CP").passe(D, "$D,O")
        assertEquals("immersive.full=$D,O", v.policy)
    }

    @Test fun `06 exclusion`() {
        // v2 : apps vise large, Android Auto et CarPlay en sont exclus (R4).
        val v = Voiture("immersive.full=apps,-W").passe(D, "$D,W")
        assertEquals("immersive.full=apps,-W,-$AA,-$CP,$D", v.policy)
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
        val v = Voiture("immersive.full=$CP").passe(C, "A,$AA")
        assertEquals("immersive.full=A", v.policy)
    }

    @Test fun `10 plus rien`() {
        val v = Voiture().passe(C, "A")
        assertEquals("immersive.full=A", v.policy)
        v.passe(C, null)
        assertNull(v.policy)
        assertNull(v.keys[C])
        assertNull(v.managed)
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

    // ── Revue du 2026-11-15 : v2 de la règle commune. ──

    @Test fun `R1 un mode partiel survit au retrait par MG4Dash`() {
        val v = Voiture().passe(C, "W/status").passe(D, "$D,W").passe(D, D)
        assertEquals("immersive.full=$D:immersive.status=$D,W", v.policy)
    }

    @Test fun `R2 deux retraits simultanes ne laissent rien de bloque`() {
        val v = Voiture().passe(C, "W").passe(D, "$D,W")
        // Les deux calculent leur retrait de W sur la même lecture, puis écrivent l'un après l'autre.
        val pc = v.plan(C, null)
        val pd = v.plan(D, D)
        v.applique(C, pc)
        v.applique(D, pd)
        assertEquals("immersive.full=W,$D", v.policy)
        // Les relectures à 5 s : C n'a plus de clé, la clé commune lui donne l'enjeu.
        assertTrue(v.stake(C, null))
        v.passe(C, null).passe(D, D)
        assertEquals("immersive.full=$D", v.policy)
        assertEquals(D, v.managed)
    }

    @Test fun `R3 une section vide a cote de full n est pas supprimee`() {
        // com.x ne cache que le bandeau : la section navigation vide le dit à Android.
        val v = Voiture("immersive.full=com.x:immersive.navigation=").passe(C, "W/status")
        assertEquals("immersive.full=com.x:immersive.status=com.x,W:immersive.navigation=", v.policy)
    }

    @Test fun `R3b preconfirms vide gardee tant que full vise quelque chose`() {
        // L'utilisateur veut le message « plein écran » pour com.x : la section vide le dit.
        val v = Voiture("immersive.full=com.x:immersive.preconfirms=").passe(C, "W")
        assertEquals("immersive.full=com.x,W:immersive.preconfirms=W", v.policy)
        v.passe(C, null)
        assertEquals("immersive.full=com.x:immersive.preconfirms=", v.policy)
        // Sans immersive.full, elle ne dit plus rien : supprimée.
        val w = Voiture("immersive.full=W:immersive.preconfirms=").passe(C, "W").passe(C, null)
        assertNull(w.policy)
    }

    @Test fun `R4 une regle globale exclut Android Auto et CarPlay`() {
        val v = Voiture("immersive.full=apps").passe(D, D)
        assertEquals("immersive.full=apps,-$AA,-$CP,$D", v.policy)
    }

    @Test fun `R5 rien a faire sans demande ni reste`() {
        val tous = setOf(D, C, COFF)
        // Option jamais utilisée : la valeur d'un autre n'est pas « rangée ».
        assertFalse(ImmersivePolicy.hasStake(C, emptyMap(), "immersive.full=com.x,apps", emptyMap(), tous, null))
        assertTrue(ImmersivePolicy.hasStake(C, emptyMap(), null, mapOf(C to "W"), tous, null))
        assertTrue(ImmersivePolicy.hasStake(C, ImmersivePolicy.parseKey("W"), null, emptyMap(), tous, null))
        // Clé laissée par un propriétaire désinstallé : à nettoyer.
        assertTrue(ImmersivePolicy.hasStake(C, emptyMap(), null, mapOf(COFF to "W"), setOf(D, C), null))
        // Clé commune : un paquet que plus personne ne réclame, oui ; réclamé par MG4Dash, non.
        assertTrue(ImmersivePolicy.hasStake(C, emptyMap(), null, emptyMap(), tous, "W"))
        assertFalse(ImmersivePolicy.hasStake(C, emptyMap(), null, mapOf(D to "W"), tous, "W"))
        assertTrue(ImmersivePolicy.hasStake(C, emptyMap(), null, mapOf(D to "W/status"), tous, "W"))
    }

    @Test fun `R6 CarPlay en clair efface sans demande`() {
        val v = Voiture("immersive.full=$CP")
        assertTrue(v.stake(D, null))
        v.passe(D, null)
        assertNull(v.policy)
    }

    @Test fun `R7 nos exclusions partent avec le joker`() {
        val v = Voiture("immersive.full=apps").passe(D, D)
        v.policy = "immersive.full=-$AA,-$CP,$D" // apps retiré entre-temps
        v.passe(D, null)
        assertNull(v.policy)
    }

    @Test fun `lecture des cles`() {
        // Suffixe inconnu ignoré ; W et W/status = tout ; interdits exclus quel que soit le suffixe.
        val k = ImmersivePolicy.parseKey("W,W/status,X/futur,Y/navigation,$CP/status,*,apps,-Z, ,")
        assertEquals(setOf("W", "Y"), k.keys)
        assertEquals("W,Y/navigation", ImmersivePolicy.formatKey(k))
    }
}
