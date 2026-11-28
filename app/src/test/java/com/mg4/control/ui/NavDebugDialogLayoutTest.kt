package com.mg4.control.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.hardware.NavGuidanceProbe
import com.mg4.control.hardware.ProjectionReader
import com.mg4.control.model.NavGuidanceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * La fenêtre « Guidage AA / CarPlay » (issue #285, 2026-11-28) se peint dans ses trois situations
 * et tient dans la hauteur de l'écran (≈ 720 dp) dans les langues aux textes les plus longs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavDebugDialogLayoutTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun localise(langue: String, echelle: Float = 1f): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(langue))
            fontScale = echelle
        })

    private fun etat(guidage: Boolean, abonnement: NavGuidanceProbe.Abonnement = NavGuidanceProbe.Abonnement.ABONNE) =
        NavGuidanceProbe.Etat(
            abonnement = abonnement,
            aa = if (guidage) 42 else 0, carplay = 0, autres = 3,
            rappels = listOf("onSessionStarted", "onScreenStarted", "onPhoneStateUpdate"),
            guidageMs = if (guidage) 9_000L else null,
            source = if (guidage) NavGuidanceProbe.Source.ANDROID_AUTO else null,
            resume = if (guidage) NavGuidanceText.Resume("Tournez à droite · rue de la République prolongée", "1 : 350 m\n2 : 14:38", null) else null,
            image = if (guidage) Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888) else null,
            detail = if (guidage) "Android Auto · type 3 · image 128×128" else null,
            brut = "com.allgo.action.AANAV : " + "AANAV_icon=image 128×128 · AANAV_message=Tournez à droite · AANAV_message_type=3 · ".repeat(4),
        )

    private val aa = ProjectionReader.Lecture.Lue(ProjectionReader.Appareil(type = 2, transport = 1, session = 1))

    @Test
    fun `each situation is painted with its chips and texts`() {
        val loc = localise("fr")
        val (_, v) = NavDebugDialog.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control))

        NavDebugDialog.montrer(loc, etat(false), ProjectionReader.Lecture.Lue(null), 10_000L, v)
        assertEquals(loc.getString(R.string.nav_debug_session_none), v.session.text.toString())
        assertEquals(loc.getString(R.string.nav_debug_guidance_none), v.guidage.text.toString())
        assertEquals(loc.getString(R.string.nav_debug_last_empty), v.ligne1.text.toString())
        assertEquals(View.GONE, v.fleche.visibility)

        NavDebugDialog.montrer(loc, etat(false), aa, 10_000L, v)
        assertEquals(loc.getString(R.string.nav_debug_session, "Android Auto"), v.session.text.toString())
        assertEquals(loc.getString(R.string.nav_debug_guidance_wait), v.guidage.text.toString())
        assertEquals(loc.getString(R.string.nav_debug_last_wait), v.ligne1.text.toString())

        NavDebugDialog.montrer(loc, etat(true), aa, 10_000L, v)
        assertEquals(loc.getString(R.string.nav_debug_guidance_age, "1 s"), v.guidage.text.toString())
        assertEquals(View.VISIBLE, v.fleche.visibility)
        assertEquals("Tournez à droite · rue de la République prolongée", v.grand.text.toString())
        assertTrue(v.compteurs.text.toString(), v.compteurs.text.contains("Android Auto : 42"))

        NavDebugDialog.montrer(loc, etat(false, NavGuidanceProbe.Abonnement.ECHEC), ProjectionReader.Lecture.Injoignable, 0L, v)
        assertEquals(loc.getString(R.string.nav_debug_service_ko), v.service.text.toString())
        assertEquals(loc.getString(R.string.nav_debug_session_unknown), v.session.text.toString())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the window fits the screen height in long languages and larger text`() {
        for (echelle in listOf(1f, 1.125f, 1.25f)) for (langue in listOf("fr", "de", "cs", "pt", "tr", "pl")) {
            val loc = localise(langue, echelle)
            val (vue, v) = NavDebugDialog.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control))
            NavDebugDialog.montrer(loc, etat(true), aa, 200_000L, v)
            v.heure.text = "12:34:56"
            val d = loc.resources.displayMetrics.density
            vue.measure(
                View.MeasureSpec.makeMeasureSpec((1240 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val hauteurDp = vue.measuredHeight / d
            assertTrue("$langue ×$echelle : $hauteurDp dp", hauteurDp <= 680f)
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            // Les trois pastilles restent dans la largeur.
            val droite = v.guidage.right + (v.guidage.parent as View).left
            assertTrue("$langue ×$echelle : pastilles jusqu'à ${droite / d} dp", droite <= vue.measuredWidth)
        }
    }
}
