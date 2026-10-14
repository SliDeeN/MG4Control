package com.mg4.control.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparaison de versions — logique pure, aucun accès réseau ni Android. */
class UpdateCheckerTest {

    // ── Notes de version ────────────────────────────────────────────────────

    /** Forme réelle du texte de la release 2.6.7 : titre, changelog, puis le bloc de la CI. */
    private val notes267 = """
        ## MG4Control v2.6.7

        Fix : The new advanced shortcut system now allows you to keep the default shortcut.
        Added : There is a new “Regen Cycle” shortcut that lets you choose which modes to cycle.
        Added: Turkish language

        Two variants are provided — **pick one**:

        | Variant | File | Network |
        |---|---|---|
        | **Online** | `MG4Control-online-*.apk` | yes |
    """.trimIndent()

    @Test
    fun `les notes ne sont plus coupees a 400 caracteres`() {
        val long = (1..60).joinToString("\n") { "Added : feature number $it" }
        assertEquals("tout le changelog arrive au popup", long, UpdateChecker.cleanNotes(long))
    }

    @Test
    fun `le bloc des variantes de la CI est retire`() {
        val propre = UpdateChecker.cleanNotes(notes267)
        assertFalse(propre.contains("Two variants"))
        assertFalse("pas de tableau Markdown brut", propre.contains("|"))
        assertTrue("le changelog, lui, est entier", propre.endsWith("Added: Turkish language"))
    }

    @Test
    fun `les marques Markdown ne s'affichent pas litteralement`() {
        val propre = UpdateChecker.cleanNotes(notes267)
        assertTrue(propre.startsWith("MG4Control v2.6.7"))
        assertFalse(propre.contains("#"))
        assertFalse(propre.contains("**"))
    }

    @Test
    fun `un texte demesure est coupe entre deux lignes`() {
        val enorme = (1..2_000).joinToString("\n") { "Ligne de changelog numéro $it" }
        val propre = UpdateChecker.cleanNotes(enorme)
        assertTrue(propre.length <= 10_002)
        assertTrue("marque de coupure visible", propre.endsWith("\n…"))
        assertTrue("jamais au milieu d'une ligne", propre.removeSuffix("\n…").lines().last().startsWith("Ligne de changelog"))
    }

    @Test
    fun `remote patch superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4"))
    }

    @Test
    fun `remote minor superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("2.7.0", "2.6.9"))
    }

    @Test
    fun `remote major superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.99.99"))
    }

    @Test
    fun `versions egales ne sont pas plus recentes`() {
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4"))
    }

    @Test
    fun `remote inferieur n est pas plus recent`() {
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4"))
    }

    @Test
    fun `prefixe v est ignore des deux cotes`() {
        assertTrue(UpdateChecker.isNewer("v2.6.5", "v2.6.4"))
        assertFalse(UpdateChecker.isNewer("v2.6.4", "2.6.4"))
    }

    @Test
    fun `segments manquants comptent comme zero`() {
        // "2.7" == "2.7.0" : pas une mise à jour.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.0"))
        // "2.7.1" > "2.7".
        assertTrue(UpdateChecker.isNewer("2.7.1", "2.7"))
        // "2.7" < "2.7.1" : le segment absent vaut 0.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.1"))
    }

    // ── Suffixes de build (T-907) ────────────────────────────────────────────

    @Test
    fun `le suffixe de flavor est ignore`() {
        // Le flavor offline s'appelle "2.6.4-offline". L'ancienne implémentation lisait
        // [2, 6] et trouvait donc TOUTE release distante plus récente.
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.4-offline", "2.6.4"))
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4-offline"))
    }

    @Test
    fun `les suffixes de pre-release et de build sont ignores`() {
        assertFalse(UpdateChecker.isNewer("2.7.0-rc1", "2.7.0"))
        assertFalse(UpdateChecker.isNewer("2.7.0+build42", "2.7.0"))
        assertTrue(UpdateChecker.isNewer("2.7.1-rc1", "2.7.0"))
    }

    @Test
    fun `un segment non numerique vaut zero sans decaler les suivants`() {
        // "2.x.5" doit valoir [2, 0, 5] — surtout pas [2, 5], qui ferait passer
        // le patch pour un minor.
        assertEquals(listOf(2, 0, 5), UpdateChecker.segments("2.x.5"))
        assertFalse(UpdateChecker.isNewer("2.x.5", "2.1.0"))
    }

    @Test
    fun `segments extrait le coeur numerique`() {
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("v2.6.4"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4-offline"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4+build9"))
    }

    // ── Canal beta : precedence des pre-releases (semver §11) ────────────────

    @Test
    fun `beta d une version future remplace la stable actuelle`() {
        assertTrue(UpdateChecker.isNewer("2.6.6-beta1", "2.6.5"))
    }

    @Test
    fun `beta suivante remplace la precedente`() {
        assertTrue(UpdateChecker.isNewer("2.6.6-beta2", "2.6.6-beta1"))
    }

    @Test
    fun `beta 10 est posterieure a beta 9 - comparaison numerique et non lexicale`() {
        assertTrue(UpdateChecker.isNewer("2.6.6-beta10", "2.6.6-beta9"))
    }

    @Test
    fun `la stable remplace la derniere beta du meme numero`() {
        assertTrue(UpdateChecker.isNewer("2.6.6", "2.6.6-beta9"))
    }

    @Test
    fun `une beta n ecrase JAMAIS la stable de meme numero`() {
        assertFalse(UpdateChecker.isNewer("2.6.6-beta9", "2.6.6"))
    }

    @Test
    fun `beta anterieure a la stable installee est ignoree`() {
        assertFalse(UpdateChecker.isNewer("2.6.5-beta3", "2.6.5"))
    }

    @Test
    fun `meme beta - pas de mise a jour`() {
        assertFalse(UpdateChecker.isNewer("2.6.6-beta1", "2.6.6-beta1"))
    }

    @Test
    fun `suffixe du workflow beta - label point numero de run`() {
        assertTrue(UpdateChecker.isNewer("2.6.6-beta.42", "2.6.6-beta.41"))
        assertFalse(UpdateChecker.isNewer("2.6.6-beta.41", "2.6.6-beta.42"))
    }

    @Test
    fun `rc est posterieure a beta - ordre alphabetique des identifiants`() {
        assertTrue(UpdateChecker.isNewer("2.6.6-rc.1", "2.6.6-beta.9"))
    }

    @Test
    fun `extraction du suffixe de pre-release`() {
        assertEquals("beta.42", UpdateChecker.preRelease("v2.6.6-beta.42"))
        assertEquals("", UpdateChecker.preRelease("2.6.6"))
        assertEquals("", UpdateChecker.preRelease("v2.6.6+build7"))
    }

    /**
     * Collision des deux systemes de suffixe : "-offline" est un marqueur de flavor, "-beta.2"
     * une vraie pre-release, et AGP les concatene dans cet ordre. Confondre les deux faisait
     * passer la stable pour posterieure a l'offline de meme numero.
     */
    @Test
    fun `le suffixe de flavor ne masque pas la pre-release`() {
        assertEquals("beta.2", UpdateChecker.preRelease("2.6.6-beta.2-offline"))
        assertEquals("", UpdateChecker.preRelease("2.6.4-offline"))
        // La beta suivante remplace la precedente, meme en flavor offline.
        assertTrue(UpdateChecker.isNewer("2.6.6-beta.3", "2.6.6-beta.2-offline"))
        // La stable remplace la derniere beta offline...
        assertTrue(UpdateChecker.isNewer("2.6.6", "2.6.6-beta.2-offline"))
        // ...mais deux stables de meme numero restent identiques (T-907).
        assertFalse(UpdateChecker.isNewer("2.6.6", "2.6.6-offline"))
    }

}
