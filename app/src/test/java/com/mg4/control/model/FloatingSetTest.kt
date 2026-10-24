package com.mg4.control.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boutons flottants par profil (2026-10-24, proposition B) : les bulles d'un profil, celui dont les
 * bulles sont à l'écran, et la copie vers un autre profil.
 */
class FloatingSetTest {

    private fun profil(id: String, floating: FloatingSet? = null) =
        DrivingProfile(id = id, name = id, driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.MEDIUM, floating = floating)

    private fun bulle(id: String, action: String = "HVAC_POPUP", x: Float = 0.9f) =
        FloatingButton(id = id, action = action, actionLabel = action, x = x, y = 0.5f)

    @Test
    fun `un profil sans jeu n a aucune bulle et l affichage reste permis`() {
        val p = profil("neuf")
        assertTrue(FloatingSets.buttonsOf(p).isEmpty())
        assertTrue(FloatingSets.enabledOf(p))
    }

    @Test
    fun `un profil enregistre avant la fonction se relit sans bulle`() {
        val json = """{"id":"v","name":"Vieux","driveMode":"NORMAL","regenLevel":"MEDIUM"}"""
        val lu = Gson().fromJson(json, DrivingProfile::class.java)
        assertTrue(FloatingSets.buttonsOf(lu).isEmpty())
    }

    @Test
    fun `les bulles d un profil survivent a l enregistrement`() {
        val p = FloatingSets.withButtons(profil("p"), listOf(bulle("a", x = 0.3f), bulle("b", "AA_CARPLAY")))
        val gson = Gson()
        val relu = gson.fromJson(gson.toJson(p), DrivingProfile::class.java)
        assertEquals(FloatingSets.buttonsOf(p), FloatingSets.buttonsOf(relu))
    }

    @Test
    fun `six bulles au plus par profil`() {
        val sept = (1..7).map { bulle("b$it") }
        assertEquals(FloatingButtons.MAX, FloatingSets.buttonsOf(FloatingSets.withButtons(profil("p"), sept)).size)
    }

    @Test
    fun `couper l affichage garde les bulles`() {
        val p = FloatingSets.withButtons(profil("p"), listOf(bulle("a")))
        val coupe = FloatingSets.withEnabled(p, false)
        assertEquals(false, FloatingSets.enabledOf(coupe))
        assertEquals(1, FloatingSets.buttonsOf(coupe).size)
    }

    @Test
    fun `la page ouverte montre le profil qu elle regle`() {
        val profils = listOf(profil("a"), profil("b"), profil("c"))
        assertEquals("c", FloatingSets.target(profils, editingId = "c", activeId = "a", defaultId = "b")?.id)
    }

    @Test
    fun `page fermee, le profil en cours, puis le defaut, puis le premier`() {
        val profils = listOf(profil("a"), profil("b"), profil("c"))
        assertEquals("b", FloatingSets.target(profils, editingId = null, activeId = "b", defaultId = "c")?.id)
        assertEquals("c", FloatingSets.target(profils, editingId = null, activeId = null, defaultId = "c")?.id)
        assertEquals("a", FloatingSets.target(profils, editingId = null, activeId = null, defaultId = null)?.id)
        // Profil réglé supprimé depuis : comme page fermée.
        assertEquals("b", FloatingSets.target(profils, editingId = "x", activeId = "b", defaultId = null)?.id)
    }

    @Test
    fun `la copie garde les reglages avec des identifiants neufs`() {
        val source = listOf(bulle("a", x = 0.2f), bulle("b", "AA_CARPLAY", x = 0.7f))
        var n = 0
        val copie = FloatingSets.copyOf(source) { "neuf${n++}" }
        assertEquals(listOf("neuf0", "neuf1"), copie.map { it.id })
        assertEquals(source.map { it.copy(id = "") }, copie.map { it.copy(id = "") })
        assertNotEquals(source.map { it.id }, copie.map { it.id })
    }
}
