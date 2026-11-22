package com.mg4.control.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.ui.AndroidRuleWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Libellés des cases de l'écran conducteur (issue #268, variante B) : tous en capitales, et un mot
 * court qui tient dans une demi-case pour chaque information au nom long, dans les 10 langues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterLabelsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val langues = listOf("fr", "en", "de", "es", "it", "pt", "tr", "pl", "nb", "cs")

    private fun localise(langue: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(langue)) })

    @Test
    fun `labels are in capitals`() {
        val loc = localise("fr")
        val mots = AndroidRuleWords(loc)
        assertEquals("TEMP. EXT.", ClusterBlocks.libelle(loc, "OUTSIDE_TEMP", mots, Locale.FRENCH))
        assertEquals("BATTERIE", ClusterBlocks.libelle(loc, "SOC", mots, Locale.FRENCH))
        assertEquals("MOY. TRAJET", ClusterBlocks.libelle(loc, ClusterInfo.TRIP_AVERAGE, mots, Locale.FRENCH))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `short labels fit a half slot in every language`() {
        // Même pinceau que ClusterGaugeView (style « libellé ») ; place = case − 10.
        val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textSize = 11f; letterSpacing = 0.04f }
        val place = (ClusterLayouts.SIDE - 3 * ClusterLayouts.MARGIN) / 2f - 10f
        val cles = ClusterInfo.choices().filter { ClusterBlocks.court(it) != null }
        assertTrue(cles.size >= 19)
        for (langue in langues) {
            val loc = localise(langue)
            val mots = AndroidRuleWords(loc)
            for (k in cles) {
                val l = ClusterBlocks.libelle(loc, k, mots, Locale.forLanguageTag(langue))
                assertTrue("$langue $k « $l » : ${texte.measureText(l)} px pour $place", texte.measureText(l) <= place)
            }
        }
    }
}
