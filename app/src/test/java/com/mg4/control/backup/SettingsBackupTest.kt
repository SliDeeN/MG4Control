package com.mg4.control.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sauvegarde des réglages (issue #269) : ce qui part dans le fichier, ce qui n'y va jamais, et
 * l'import qui remplace le reste sans toucher aux comptes ABRP ni aux webhooks de l'écran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupTest {

    private lateinit var ctx: Context
    private fun p(f: String) = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
    private val webhook = """{"id":"w1","name":"Portail","kind":"webhook","url":"https://x/y?token=SECRET","method":"POST"}"""
    private val appli = """{"id":"a1","name":"Waze","kind":"app","packageName":"com.waze"}"""

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        SettingsBackup.FICHIERS.forEach { p(it).edit().clear().commit() }
        File(ctx.filesDir, "stats_history.json").delete()
    }

    private fun remplir() {
        p("mg4_settings").edit()
            .putString("custom_shortcuts", "[$appli,$webhook]")
            .putBoolean("floating_enabled", true)
            .putInt("text_size", 2)
            .putFloat("cluster_scale", 1.5f)
            .putStringSet("immersive_apps", setOf("com.waze", "com.spotify"))
            .putString("nav_side_detected", "right")
            .putLong("autobri_last_at", 123L)
            .putString("autobri_forecast", "[{\"h\":1}]")
            .commit()
        p("mg4_shortcuts").edit()
            .putString("shortcut_audio_cycle_order", "FM,USB,BT")
            .putString("shortcut_audio_cycle_last", "USB")
            .commit()
        p("mg4_profiles").edit()
            .putString("profiles_json", """[{"id":"p1","name":"Paul"},{"id":"p2","name":"Yaya"}]""")
            .putString("default_profile_id", "p1").commit()
        p("mg4_custom_rules").edit().putString("rules_json", "[{},{},{}]").putString("last_runs_json", "{}").commit()
        p("mg4_abrp").edit().putString("accounts_json", "[{\"token\":\"SECRET\"}]").putInt("cadence_driving_s", 10).commit()
        p("mg4_stats").edit().putBoolean("enabled", true).putString("energy_checks", "[1]").putLong("last_reading_ms", 5L).commit()
        File(ctx.filesDir, "stats_history.json").writeText("""{"trips":[1,2]}""")
    }

    @Test
    fun `secrets and state never leave, settings do`() {
        remplir()
        val texte = SettingsBackup.export(ctx, stats = false, app = "2.7.1", firmware = "SWI133", dateIso = "2026-11-23 10:00")
        assertFalse("jeton ABRP", texte.contains("SECRET"))
        assertFalse(texte.contains("accounts_json"))
        assertFalse(texte.contains("nav_side_detected"))
        assertFalse(texte.contains("autobri_last_at"))
        assertFalse(texte.contains("last_runs_json"))
        assertFalse(texte.contains("last_reading_ms"))
        assertFalse("météo en cache", texte.contains("autobri_forecast"))
        assertFalse("dernière source audio", texte.contains("shortcut_audio_cycle_last"))
        assertTrue("ordre du cycle : un réglage", texte.contains("shortcut_audio_cycle_order"))
        assertTrue(texte.contains("Waze"))
        assertTrue(texte.contains("cadence_driving_s"))
        assertFalse("historique seulement sur demande", texte.contains("energy_checks"))
        assertFalse(JSONObject(texte).getJSONObject("files").has("stats_history.json"))
        val avec = SettingsBackup.export(ctx, stats = true, app = "2.7.1", firmware = "SWI133", dateIso = "2026-11-23 10:00")
        assertTrue(avec.contains("energy_checks"))
        assertTrue(JSONObject(avec).getJSONObject("files").has("stats_history.json"))
    }

    @Test
    fun `the summary tells what the file holds`() {
        remplir()
        val r = SettingsBackup.lire(SettingsBackup.export(ctx, true, "2.7.1", "SWI133", "2026-11-23 10:00"))
        assertNotNull(r)
        assertEquals(2, r!!.profils)
        assertEquals("Paul", r.defaut)
        assertEquals(3, r.automatisations)
        assertEquals(1, r.raccourcis)
        assertTrue(r.stats)
        assertFalse(r.plusRecent)
        assertNull(SettingsBackup.lire("pas du json"))
        assertNull(SettingsBackup.lire("""{"a":1}"""))
    }

    @Test
    fun `import replaces settings and keeps what the file never carries`() {
        remplir()
        val texte = SettingsBackup.export(ctx, true, "2.7.1", "SWI133", "2026-11-23 10:00")
        // L'écran réinitialisé : d'autres réglages, un autre compte ABRP, un webhook à lui.
        SettingsBackup.FICHIERS.forEach { p(it).edit().clear().commit() }
        File(ctx.filesDir, "stats_history.json").delete()
        p("mg4_settings").edit().putBoolean("floating_enabled", false).putString("old_key", "x")
            .putString("nav_side_detected", "left")
            .putString("custom_shortcuts", """[{"id":"w9","name":"Garage","kind":"webhook","url":"https://h"}]""").commit()
        p("mg4_abrp").edit().putString("accounts_json", "[{\"token\":\"LOCAL\"}]").commit()

        assertTrue(SettingsBackup.import(ctx, texte))
        val s = p("mg4_settings")
        assertTrue(s.getBoolean("floating_enabled", false))
        assertEquals(2, s.getInt("text_size", 0))
        assertEquals(1.5f, s.getFloat("cluster_scale", 0f), 0f)
        assertEquals(setOf("com.waze", "com.spotify"), s.getStringSet("immersive_apps", null))
        assertFalse("remplacé, pas fusionné", s.contains("old_key"))
        assertEquals("état de cet écran gardé", "left", s.getString("nav_side_detected", null))
        val raccourcis = s.getString("custom_shortcuts", "")!!
        assertTrue(raccourcis.contains("Waze"))
        assertTrue("webhook de cet écran gardé", raccourcis.contains("Garage"))
        assertFalse(raccourcis.contains("Portail"))
        assertEquals("[{\"token\":\"LOCAL\"}]", p("mg4_abrp").getString("accounts_json", null))
        assertEquals(10, p("mg4_abrp").getInt("cadence_driving_s", 0))
        assertEquals("Paul", p("mg4_profiles").getString("profiles_json", "")!!.let { Regex("Paul").find(it)?.value })
        assertEquals("""{"trips":[1,2]}""", File(ctx.filesDir, "stats_history.json").readText())
        assertFalse(SettingsBackup.import(ctx, "pas du json"))
    }

    @Test
    fun `import is refused between firmware families, allowed within one`() {
        assertEquals(SettingsBackup.Compat.SAME, SettingsBackup.compat("SWI133", "SWI133"))
        assertEquals(SettingsBackup.Compat.SAME_FAMILY, SettingsBackup.compat("SWI69", "SWI131"))
        assertEquals(SettingsBackup.Compat.SAME_FAMILY, SettingsBackup.compat("SWI68", "SWI165"))
        assertEquals(SettingsBackup.Compat.OTHER_FAMILY, SettingsBackup.compat("SWI133", "SWI68"))
        assertEquals(SettingsBackup.Compat.OTHER_FAMILY, SettingsBackup.compat("SWI132", "SWI131"))
        assertEquals(SettingsBackup.Compat.UNKNOWN, SettingsBackup.compat("UNKNOWN", "SWI133"))
        assertEquals(SettingsBackup.Compat.UNKNOWN, SettingsBackup.compat(null, "SWI133"))

        remplir()
        p("mg4_settings").edit().putString("forced_firmware_gen", "SWI133").commit()
        val texte = SettingsBackup.export(ctx, false, "2.7.1", "SWI133", "2026-11-23 10:00")
        assertFalse("mode forcé jamais exporté", texte.contains("forced_firmware_gen"))
        p("mg4_settings").edit().putBoolean("floating_enabled", false).commit()
        assertFalse("autre famille : refusé", SettingsBackup.import(ctx, texte, "SWI69"))
        assertFalse("rien n'a été touché", p("mg4_settings").getBoolean("floating_enabled", true))
        assertTrue(SettingsBackup.import(ctx, texte, "SWI133"))
        assertTrue(p("mg4_settings").getBoolean("floating_enabled", false))
    }
}
