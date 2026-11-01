package com.mg4.control.model

import com.google.gson.Gson
import com.mg4.control.util.ClusterModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Écran conducteur par profil (2026-10-24) : quel profil suit le combiné, ce que vaut un profil
 * sans réglages, et ce que garde la sélection de la carte.
 */
class ClusterConfigTest {

    private fun profil(id: String, cluster: ClusterConfig? = null) =
        DrivingProfile(id = id, name = id, driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.MEDIUM, cluster = cluster)

    private val a = profil("a")
    private val b = profil("b")
    private val c = profil("c")

    @Test
    fun `le combine suit le profil en cours`() {
        assertEquals("b", ClusterConfigs.shown(listOf(a, b, c), activeId = "b", defaultId = "c")?.id)
    }

    @Test
    fun `sans profil applique, le profil par defaut`() {
        assertEquals("c", ClusterConfigs.shown(listOf(a, b, c), activeId = null, defaultId = "c")?.id)
        // Profil en cours supprimé depuis : idem.
        assertEquals("c", ClusterConfigs.shown(listOf(a, b, c), activeId = "x", defaultId = "c")?.id)
    }

    @Test
    fun `ni profil en cours ni defaut, le premier de la liste`() {
        assertEquals("a", ClusterConfigs.shown(listOf(a, b, c), activeId = null, defaultId = null)?.id)
    }

    @Test
    fun `sans aucun profil, rien`() {
        assertNull(ClusterConfigs.shown(emptyList(), activeId = "a", defaultId = "a"))
    }

    @Test
    fun `un profil sans reglages a le module coupe et le reste par defaut`() {
        val reglages = ClusterConfigs.of(a)
        assertFalse(ClusterModuleSettings.isEnabled(reglages))
        // Activé ensuite, il montre ce que montrait une installation neuve.
        assertEquals(ClusterModule.CONSUMPTION, ClusterModuleSettings.moduleFor(reglages, ClusterTab.NAVIGATION))
        assertNull(ClusterModuleSettings.moduleFor(reglages, ClusterTab.MEDIA))
        assertEquals(ClusterModuleSettings.DEFAULT_REFRESH_MS, ClusterModuleSettings.refreshMs(reglages))
    }

    @Test
    fun `un profil enregistre avant la fonction se relit sans reglages`() {
        val json = """{"id":"v","name":"Vieux","driveMode":"NORMAL","regenLevel":"MEDIUM"}"""
        val lu = Gson().fromJson(json, DrivingProfile::class.java)
        assertNull(lu.cluster)
        assertFalse(ClusterModuleSettings.isEnabled(ClusterConfigs.of(lu)))
    }

    @Test
    fun `les reglages d un profil survivent a l enregistrement`() {
        val reglages = ClusterConfig(enabled = true, navigation = "power", media = "none", style = "discreet", refreshMs = 300)
        val gson = Gson()
        val relu = gson.fromJson(gson.toJson(profil("p", reglages)), DrivingProfile::class.java)
        assertEquals(reglages, relu.cluster)
    }

    @Test
    fun `la selection garde les profils qui existent encore`() {
        assertEquals(setOf("a", "c"), ClusterConfigs.keepSelection(setOf("a", "c", "x"), listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `selection videe, le profil que suit le combine`() {
        assertEquals(setOf("b"), ClusterConfigs.keepSelection(setOf("x"), listOf("a", "b"), "b"))
        assertEquals(setOf("b"), ClusterConfigs.keepSelection(emptySet(), listOf("a", "b"), "b"))
    }

    @Test
    fun `selection videe sans profil suivi, le premier`() {
        assertEquals(setOf("a"), ClusterConfigs.keepSelection(emptySet(), listOf("a", "b"), null))
        assertTrue(ClusterConfigs.keepSelection(emptySet(), emptyList(), null).isEmpty())
    }
}
