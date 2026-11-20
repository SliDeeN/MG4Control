package com.mg4.control.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.hardware.TyreProbe
import com.mg4.control.hardware.TyreProbe.Lu
import com.mg4.control.hardware.TyreProbe.Roue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * La fenêtre Pneus (outil de relevé, 2026-11-20) se gonfle, se peint avec et sans valeurs, et tient
 * dans la hauteur de l'écran (≈ 720 dp) dans les langues aux textes les plus longs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TyreDebugDialogLayoutTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun localise(langue: String, echelle: Float = 1f): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(langue))
            fontScale = echelle
        })

    /** Le pire cas pour la place : chaque voie échoue avec un long message, sauf la pression. */
    private fun releve() = TyreProbe.Releve(
        roues = Roue.values().map { r ->
            val refus = Lu.echec("IllegalArgumentException (property 0x${Integer.toHexString(r.pression)} not supported)")
            TyreProbe.RoueLue(
                r,
                TyreProbe.Voies(Lu.de(290), refus, Lu.de(290)),
                TyreProbe.Voies(refus, refus, Lu.de(34)),
                TyreProbe.Voies(refus, Lu.de(if (r == Roue.ARRIERE_GAUCHE) 3 else 0), refus),
                Lu.de(2.9f),
            )
        },
        unite = TyreProbe.Voies(Lu.de(1), Lu.de(1), Lu.echec("SecurityException")),
    )

    @Test
    fun `the four wheels are painted, with a dash when nothing answers`() {
        val loc = localise("fr")
        val (_, vues) = TyreDebugDialog.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control))
        assertEquals(4, vues.cartes.size)
        assertEquals(loc.getString(R.string.win_rear_left), vues.cartes.getValue(Roue.ARRIERE_GAUCHE).nom.text.toString())

        TyreDebugDialog.montrer(loc, releve(), vues)
        val ag = vues.cartes.getValue(Roue.ARRIERE_GAUCHE)
        assertEquals("290", ag.valeur.text.toString())
        assertEquals(loc.getString(R.string.tyre_debug_state, "3"), ag.etat.text.toString())
        assertTrue(ag.detail.text.toString(), ag.detail.text.contains("std 2.9"))
        assertEquals(loc.getString(R.string.tyre_debug_unit, "1"), vues.unite.text.toString())

        TyreDebugDialog.montrer(loc, null, vues)
        assertEquals("–", ag.valeur.text.toString())
        assertEquals("—", ag.detail.text.toString())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the window fits the screen height in long languages and larger text`() {
        for (echelle in listOf(1f, 1.125f, 1.25f)) for (langue in listOf("fr", "de", "cs", "pt", "tr")) {
            val loc = localise(langue, echelle)
            val (vue, vues) = TyreDebugDialog.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control))
            TyreDebugDialog.montrer(loc, releve(), vues)
            vues.heure.text = "12:34:56"
            val d = loc.resources.displayMetrics.density
            vue.measure(
                View.MeasureSpec.makeMeasureSpec((1240 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val hauteurDp = vue.measuredHeight / d
            assertTrue("$langue ×$echelle : $hauteurDp dp", hauteurDp <= 680f)
            // Le grand chiffre tient sur sa ligne dans chaque carte.
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            vues.cartes.values.forEach { c ->
                assertEquals("$langue ×$echelle", 1, (c.valeur as TextView).lineCount)
                assertTrue((c.nom.parent as ViewGroup).width > 0)
            }
        }
    }
}
