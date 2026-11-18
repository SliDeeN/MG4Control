package com.mg4.control.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import androidx.fragment.app.FragmentActivity
import com.mg4.control.R
import com.mg4.control.service.SaicHvacPanel
import com.mg4.control.util.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pages des Réglages mesurées comme sur la voiture (Android 9, 1920 × 720 dp) : une rangée visible
 * doit avoir une vraie hauteur. La carte « Unité de distance » (2026-11-12) était visible mais
 * haute de 0 : seule dans sa rangée en `match_parent`, à côté d'un vide de hauteur nulle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class SettingsPagesLayoutTest {

    @Test
    fun `les options HVAC sont alignees et le remplacement depend du choix au demarrage`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        for (gen in listOf(FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.SWI165, FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI132)) {
            ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
            FirmwareInfo.forceGeneration(ctx, gen)
            val c = Robolectric.buildActivity(FragmentActivity::class.java)
            c.get().setTheme(R.style.Theme_MG4Control)
            c.setup()
            val f = SettingsFragment()
            c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
            val v = f.requireView()
            v.findViewById<View>(R.id.btn_set_cat_advanced).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val row = v.findViewById<View>(R.id.row_hide_hvac_startup)
            val child = v.findViewById<View>(R.id.row_replace_hvac_panel)
            val startup = v.findViewById<Switch>(R.id.switch_hide_hvac_startup)
            val popup = v.findViewById<Switch>(R.id.switch_replace_hvac_panel)
            assertEquals(View.GONE, child.visibility)
            assertFalse(startup.isChecked)
            assertFalse(popup.isChecked)
            if (gen == FirmwareInfo.Gen.SWI132) {
                assertEquals(View.GONE, row.visibility)
            } else {
                assertEquals(View.VISIBLE, row.visibility)
                startup.isChecked = true
                assertEquals(View.VISIBLE, child.visibility)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(row.parent, child.parent)
                val a = IntArray(2)
                val b = IntArray(2)
                startup.getLocationInWindow(a)
                popup.getLocationInWindow(b)
                assertEquals(a[0], b[0])
                popup.isChecked = true
                assertTrue(SaicHvacPanel.isPopupOn(ctx))
                startup.isChecked = false
                assertEquals(View.GONE, child.visibility)
                assertFalse(SaicHvacPanel.isOn(ctx))
                startup.isChecked = true
                assertEquals(View.VISIBLE, child.visibility)
                assertTrue(popup.isChecked)
            }
            c.pause().stop().destroy()
        }
    }

    @Test
    fun `every visible settings row has a real height`() {
        val c = Robolectric.buildActivity(FragmentActivity::class.java)
        c.get().setTheme(R.style.Theme_MG4Control)
        c.setup()
        val f = SettingsFragment()
        c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val v = f.requireView()
        listOf(R.id.btn_set_cat_lang to R.id.page_set_lang, R.id.btn_set_cat_ui to R.id.page_set_ui,
            R.id.btn_set_cat_advanced to R.id.page_set_advanced, R.id.btn_set_cat_info to R.id.page_set_info).forEach { (onglet, id) ->
            v.findViewById<View>(onglet).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val page = v.findViewById<ViewGroup>(id)
            for (i in 0 until page.childCount) {
                val rangee = page.getChildAt(i)
                if (rangee.visibility != View.VISIBLE) continue
                val nom = v.resources.getResourceEntryName(id)
                assertTrue("$nom, rangée $i : ${rangee.height} px", rangee.height > 30)
            }
        }
        // La carte de l'unité de distance, en particulier.
        v.findViewById<View>(R.id.btn_set_cat_ui).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(v.findViewById<View>(R.id.btn_distance_km).height > 30)
    }
}
