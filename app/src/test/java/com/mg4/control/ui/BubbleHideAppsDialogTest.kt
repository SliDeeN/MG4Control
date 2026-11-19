package com.mg4.control.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.util.FloatingButtonSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Choix des applications où les boutons flottants se cachent (issue #261). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-night-mdpi")
class BubbleHideAppsDialogTest {

    private fun installer(pm: android.content.pm.PackageManager, paquet: String, nom: String, lancable: Boolean) {
        val app = ApplicationInfo().apply { packageName = paquet; nonLocalizedLabel = nom; name = nom }
        shadowOf(pm).installPackage(PackageInfo().apply { packageName = paquet; applicationInfo = app })
        if (lancable) shadowOf(pm).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply {
                nonLocalizedLabel = nom
                activityInfo = ActivityInfo().apply { packageName = paquet; name = "$paquet.Main"; applicationInfo = app }
            })
    }

    @Test
    fun `Android Auto first, a tap ticks, saving keeps the choice`() {
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        val pm = activite.packageManager
        installer(pm, "com.allgo.app.androidauto", "AA", lancable = false)
        installer(pm, "com.waze", "Waze", lancable = true)
        installer(pm, "com.abrp", "ABRP", lancable = true)
        FloatingButtonSettings.setHideApps(activite, setOf("com.waze"))

        BubbleHideAppsDialog.show(activite) {}
        shadowOf(Looper.getMainLooper()).idle()
        val dialogue = ShadowDialog.getLatestDialog()
        val rangees = dialogue.findViewById<LinearLayout>(R.id.choice_rows)
        val lignes = (0 until rangees.childCount).map { rangees.getChildAt(it) }.filterIsInstance<LinearLayout>()
        val noms = lignes.map { (it.getChildAt(2) as TextView).text.toString() }
        assertEquals(listOf("Android Auto", "ABRP", "Waze"), noms)
        val cases = lignes.map { it.getChildAt(0) as CheckBox }
        assertEquals(listOf(false, false, true), cases.map { it.isChecked })

        lignes[0].performClick()
        assertTrue(cases[0].isChecked)

        System.getenv("CAPTURE_DIR")?.let { dossier ->
            val decor = dialogue.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            val b = Bitmap.createBitmap(decor.measuredWidth, decor.measuredHeight, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(b))
            File(dossier, "hide_apps_dialog.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        dialogue.findViewById<TextView>(R.id.choice_cancel).performClick()
        assertEquals(setOf("com.allgo.app.androidauto", "com.waze"), FloatingButtonSettings.hideApps(activite))
    }
}
