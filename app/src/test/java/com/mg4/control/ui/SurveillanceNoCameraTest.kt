package com.mg4.control.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.util.PreClimateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * « Surveillance sans caméra » (2026-11-13) : proposée sans appli dashcam, Oui seulement après
 * l'avertissement « aucune vidéo », refus = retour à Non.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class SurveillanceNoCameraTest {

    private fun ouvrir(): View {
        val c = Robolectric.buildActivity(FragmentActivity::class.java)
        c.get().setTheme(R.style.Theme_MG4Control)
        c.setup()
        val f = ShortcutsFragment()
        c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return f.requireView()
    }

    private fun dialogue() = ShadowDialog.getLatestDialog()

    @Test
    fun `yes needs the warning, cancelling goes back to no`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val v = ouvrir()
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.row_preclim_cam_nocam).visibility)
        val sw = v.findViewById<Switch>(R.id.switch_preclim_cam_nocam)
        assertFalse(sw.isChecked)

        sw.isChecked = true
        shadowOf(Looper.getMainLooper()).idle()
        val d1 = dialogue()
        assertEquals("Aucune vidéo ne sera enregistrée", d1.findViewById<TextView>(R.id.confirm_dialog_title).text.toString())
        assertFalse("rien d'enregistré avant la réponse", PreClimateSettings.survWithoutCamera(ctx))
        d1.findViewById<View>(R.id.confirm_dialog_no).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(sw.isChecked)
        assertFalse(PreClimateSettings.survWithoutCamera(ctx))

        sw.isChecked = true
        shadowOf(Looper.getMainLooper()).idle()
        dialogue().findViewById<View>(R.id.confirm_dialog_yes).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sw.isChecked)
        assertTrue(PreClimateSettings.survWithoutCamera(ctx))

        sw.isChecked = false
        assertFalse(PreClimateSettings.survWithoutCamera(ctx))
    }
}
