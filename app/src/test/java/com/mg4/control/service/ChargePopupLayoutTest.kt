package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.ChargePoint
import com.mg4.control.model.ChargeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Le pop-up de charge se gonfle et se peint dans les sept langues, en charge comme une fois la
 * charge finie, et son éditeur de prix s'ouvre.
 *
 * Né d'une panne : « Batterie (%) » passée à la mise en forme levait une
 * UnknownFormatConversionException, et le pop-up ne s'est jamais affiché dans la voiture
 * (diag SWI133 du 2026-10-16). Rien ne le voyait à la compilation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargePopupLayoutTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun localise(langue: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(langue)) })

    private fun etat(finie: Boolean = false, limite: Int? = 80, restant: Int? = 21) = ChargeMonitor.Etat(
        enCharge = !finie, finie = finie, type = ChargeType.DC, soc = 62.4f, kw = 90f, kwMax = 134f,
        energieKwh = 27.4f, dureeMin = 14, restantMin = restant, limitePct = limite, limiteEnAttente = false,
        limiteRefusee = null, autonomieKm = 237, autonomieGagneeKm = 169, prix = 0.49f, prixCorrige = false,
        prixDefaut = 0.49f, devise = "€", debutHorlogeMs = 1_760_621_460_000L, horlogeMs = 1_760_622_300_000L,
        ecouleMs = 14 * 60_000L, points = (0..168).map { ChargePoint(it * 5_000L, 120f - it * 0.2f, 18f + it * 0.26f) },
    )

    @Test
    fun `se peint dans les sept langues`() {
        for (langue in listOf("fr", "en", "de", "es", "it", "pt", "tr")) {
            val loc = localise(langue)
            val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
            for (e in listOf(etat(), etat(finie = true, restant = null), etat(limite = null, restant = null))) {
                val (vue, _) = ChargePopupOverlay.preparer(themed, loc, e)
                val soc = vue.findViewById<TextView>(R.id.charge_soc).text.toString()
                assertTrue("$langue : $soc", soc.startsWith("62"))
            }
        }
    }

    @Test
    fun `l editeur du prix s ouvre et suit les boutons`() {
        val loc = localise("fr")
        val (vue, vues) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc, etat())
        vues.cout.vue.performClick()
        assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_price_editor).visibility)
        assertEquals("0,490", vue.findViewById<TextView>(R.id.price_value).text.toString())
        vue.findViewById<View>(R.id.price_plus).performClick()
        assertEquals("0,500", vue.findViewById<TextView>(R.id.price_value).text.toString())
        vue.findViewById<View>(R.id.price_cancel).performClick()
        assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_price_editor).visibility)
    }
}
