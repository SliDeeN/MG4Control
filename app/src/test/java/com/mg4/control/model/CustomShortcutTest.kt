package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raccourcis perso (issue #172) : ce qui est accepté, ce qui est refusé, et le journal. */
class CustomShortcutTest {

    private val userApps = setOf("com.tommasov.mg4dashcam")
    private fun estUtilisateur(p: String) = p in userApps

    private fun diffusion(action: String?, pkg: String? = "com.tommasov.mg4dashcam") =
        CustomShortcut(id = "1", name = "Dashcam", kind = CustomKind.BROADCAST.key, packageName = pkg, action = action)

    @Test
    fun `la diffusion de l issue 172 est acceptée`() {
        assertNull(CustomShortcuts.problem(diffusion("com.drivehub.kamera.action.TRIGGER_DASHCAM_EVENT"), ::estUtilisateur))
    }

    @Test
    fun `une action réservée au système est refusée`() {
        assertEquals(CustomShortcuts.Problem.ACTION_RESERVED,
            CustomShortcuts.problem(diffusion("android.intent.action.MASTER_CLEAR"), ::estUtilisateur))
        assertEquals(CustomShortcuts.Problem.ACTION_RESERVED,
            CustomShortcuts.problem(diffusion("com.mg4.control.action.ONE_PEDAL"), ::estUtilisateur))
        assertEquals(CustomShortcuts.Problem.ACTION_RESERVED,
            CustomShortcuts.problem(diffusion("com.saicmotor.x.y"), ::estUtilisateur))
    }

    @Test
    fun `une appli système ne peut pas recevoir de diffusion`() {
        assertEquals(CustomShortcuts.Problem.TARGET_SYSTEM,
            CustomShortcuts.problem(diffusion("a.b.C", pkg = "com.android.settings"), ::estUtilisateur))
        assertEquals(CustomShortcuts.Problem.TARGET,
            CustomShortcuts.problem(diffusion("a.b.C", pkg = null), ::estUtilisateur))
    }

    @Test
    fun `un nom vide est refusé`() {
        assertEquals(CustomShortcuts.Problem.NAME,
            CustomShortcuts.problem(diffusion("a.b.C").copy(name = "  "), ::estUtilisateur))
    }

    @Test
    fun `les liens intent sont refusés`() {
        assertTrue(CustomShortcuts.linkAllowed("waze://?favorite=home&navigate=yes"))
        assertTrue(CustomShortcuts.linkAllowed("https://example.com"))
        assertFalse(CustomShortcuts.linkAllowed("intent:#Intent;component=com.android.settings/.X;end"))
        assertFalse(CustomShortcuts.linkAllowed("pas un lien"))
        assertFalse(CustomShortcuts.linkAllowed("waze:"))
    }

    @Test
    fun `webhook - https seulement`() {
        assertTrue(CustomShortcuts.urlAllowed("https://ha.local/api/webhook/abc"))
        assertFalse(CustomShortcuts.urlAllowed("http://192.168.1.10:8123/api/webhook/abc"))
        assertFalse(CustomShortcuts.urlAllowed("ftp://x"))
        assertFalse(CustomShortcuts.urlAllowed("https://"))
    }

    @Test
    fun `webhook - le jeton n apparaît pas dans le journal`() {
        assertEquals("https://ha.local/•••", CustomShortcuts.maskUrl("https://ha.local/api/webhook/portail-7f3a?x=1"))
        assertEquals("https://h.com/•••", CustomShortcuts.maskUrl("https://user:secret@h.com/path"))
    }

    @Test
    fun `webhook - méthode et type du corps`() {
        val w = CustomShortcut(id = "w", name = "Portail", kind = CustomKind.WEBHOOK.key, url = "https://h")
        assertEquals("GET", CustomShortcuts.methodOf(w))
        assertEquals("POST", CustomShortcuts.methodOf(w.copy(body = "{}")))
        assertEquals("GET", CustomShortcuts.methodOf(w.copy(body = "{}", method = "get")))
        assertTrue(CustomShortcuts.contentType(" {\"a\":1}").startsWith("application/json"))
        assertTrue(CustomShortcuts.contentType("ouvrir").startsWith("text/plain"))
    }

    @Test
    fun `extras - une paire clé=valeur par ligne`() {
        assertEquals(listOf("mode" to "1", "nom" to "a=b"),
            CustomShortcuts.parseExtras("mode = 1\n\n=sans clé\nsans égal\nnom=a=b"))
    }
}
