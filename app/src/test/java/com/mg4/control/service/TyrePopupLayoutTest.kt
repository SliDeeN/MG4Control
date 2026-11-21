package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.hardware.TyreProbe
import com.mg4.control.hardware.TyreProbe.Roue
import com.mg4.control.model.TyrePressure.PressureUnit
import com.mg4.control.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Le pop-up Pression des pneus (2026-11-20) se gonfle, se peint (unités, codes d'état, sans
 * valeur) et tient dans sa carte dans les langues aux textes les plus longs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TyrePopupLayoutTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun localise(langue: String, echelle: Float = 1f): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(langue))
            fontScale = echelle
        })

    /** Relevé SWI133 du 2026-11-20, avec une alerte à l'arrière gauche et un 6 seul à l'avant gauche. */
    private fun releve(arriereDroit: TyreProbe.Pneu = TyreProbe.Pneu(256, 16, 0)) = TyreProbe.Pneus(
        roues = mapOf(
            Roue.AVANT_GAUCHE to TyreProbe.Pneu(252, 16, 6),
            Roue.AVANT_DROITE to TyreProbe.Pneu(248, 16, 0),
            Roue.ARRIERE_GAUCHE to TyreProbe.Pneu(198, 16, 3),
            Roue.ARRIERE_DROITE to arriereDroit,
        ),
        uniteCombine = 0,
    )

    private fun prepare(loc: Context) = TyrePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc)

    @Test
    fun `values, units and car codes are painted`() {
        val loc = localise("fr")
        val (_, v) = prepare(loc)
        // Sans relevé : pas de valeur partout.
        v.cartes.values.forEach {
            assertEquals("–", it.valeur.text.toString())
            assertEquals(loc.getString(R.string.tyre_state_none), it.etat.text.toString())
        }

        TyrePopupOverlay.peindre(v, releve(), PressureUnit.BAR)
        assertEquals("2,5", v.cartes.getValue(Roue.AVANT_GAUCHE).valeur.text.toString())
        assertEquals("2,6", v.cartes.getValue(Roue.ARRIERE_DROITE).valeur.text.toString())
        assertEquals("bar", v.cartes.getValue(Roue.ARRIERE_DROITE).unite.text.toString())
        assertEquals("16 °C", v.cartes.getValue(Roue.AVANT_DROITE).temp.text.toString())
        assertEquals(loc.getString(R.string.tyre_state_normal), v.cartes.getValue(Roue.AVANT_DROITE).etat.text.toString())
        assertEquals(View.GONE, v.cartes.getValue(Roue.AVANT_DROITE).pourquoi.visibility)
        val ag = v.cartes.getValue(Roue.ARRIERE_GAUCHE)
        assertEquals(loc.getString(R.string.tyre_state_alert, 3), ag.etat.text.toString())
        assertEquals(loc.getString(R.string.tyre_why_alert), ag.pourquoi.text.toString())
        val avg = v.cartes.getValue(Roue.AVANT_GAUCHE)
        assertEquals(loc.getString(R.string.tyre_state_code, 6), avg.etat.text.toString())
        assertEquals(loc.getString(R.string.tyre_why_lone_six), avg.pourquoi.text.toString())

        TyrePopupOverlay.peindre(v, releve(), PressureUnit.PSI)
        assertEquals("37", avg.valeur.text.toString())
        assertEquals("PSI", avg.unite.text.toString())
        TyrePopupOverlay.peindre(v, releve(), PressureUnit.KPA)
        assertEquals("252", avg.valeur.text.toString())

        // 6 sur les deux pneus de l'essieu : alerte. Pneu sans valeur : le dit.
        val essieu = TyreProbe.Pneus(releve().roues + (Roue.AVANT_DROITE to TyreProbe.Pneu(248, 16, 6)), 0)
        TyrePopupOverlay.peindre(v, essieu, PressureUnit.BAR)
        assertEquals(loc.getString(R.string.tyre_state_alert, 6), avg.etat.text.toString())
        assertEquals(loc.getString(R.string.tyre_why_axle), avg.pourquoi.text.toString())
        TyrePopupOverlay.peindre(v, releve(TyreProbe.Pneu(null, null, null)), PressureUnit.BAR)
        val ad = v.cartes.getValue(Roue.ARRIERE_DROITE)
        assertEquals("–", ad.valeur.text.toString())
        assertEquals("– °C", ad.temp.text.toString())
        assertEquals(loc.getString(R.string.tyre_why_none), ad.pourquoi.text.toString())
    }

    @Test
    fun `everything faces the car`() {
        val (_, v) = prepare(localise("fr"))
        val gauche = v.cartes.getValue(Roue.AVANT_GAUCHE)
        val droite = v.cartes.getValue(Roue.AVANT_DROITE)
        assertEquals(0, gauche.rangee.indexOfChild(gauche.temp))
        assertEquals(droite.rangee.childCount - 1, droite.rangee.indexOfChild(droite.temp))
        assertEquals(android.view.Gravity.END, gauche.nom.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
    }

    @Test
    fun `the shortcut keeps its identifier`() {
        assertEquals(55, ShortcutAction.TYRE_POPUP.id)
        assertEquals(ShortcutAction.TYRE_POPUP, ShortcutAction.fromId(55))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the cards fit in long languages and larger text`() {
        for (echelle in listOf(1f, 1.125f, 1.25f)) for (langue in listOf("fr", "de", "tr", "pt", "es", "it", "cs", "pl", "nb")) {
            val loc = localise(langue, echelle)
            val (vue, v) = prepare(loc)
            TyrePopupOverlay.peindre(v, releve(TyreProbe.Pneu(null, null, null)), PressureUnit.KPA)
            val d = loc.resources.displayMetrics.density
            vue.measure(
                View.MeasureSpec.makeMeasureSpec((1300 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((560 * d).toInt(), View.MeasureSpec.EXACTLY))
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            v.cartes.forEach { (r, c) ->
                val carte = c.racine as ViewGroup
                val dernier = (0 until carte.childCount).map { carte.getChildAt(it) }.last { it.visibility == View.VISIBLE }
                val bas = carte.height - carte.paddingBottom
                assertTrue("$langue ×$echelle $r : finit à ${dernier.bottom} px pour $bas", dernier.bottom <= bas)
                assertEquals("$langue ×$echelle $r", 0, (c.etat as TextView).layout?.getEllipsisCount(0) ?: 0)
                val large = carte.width - carte.paddingStart - carte.paddingEnd
                assertTrue("$langue ×$echelle $r : pression + température débordent (${c.rangee.width} px pour $large)",
                    c.rangee.width <= large && c.temp.right <= c.rangee.width)
                assertTrue("$langue ×$echelle $r : pastille trop large", c.etat.width <= large)
            }
        }
    }
    /** Capture pour comparer à la maquette : `CAPTURE_DIR=… gradlew test…`. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `capture when asked`() {
        val dossier = System.getenv("CAPTURE_DIR") ?: return
        for ((langue, unite) in listOf("fr" to PressureUnit.BAR, "it" to PressureUnit.KPA)) {
            val loc = localise(langue)
            val (vue, v) = prepare(loc)
            TyrePopupOverlay.peindre(v, releve(), unite)
            vue.measure(View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            val b = android.graphics.Bitmap.createBitmap(vue.measuredWidth, vue.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            vue.draw(android.graphics.Canvas(b))
            java.io.File(dossier, "tyre_popup_$langue.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
