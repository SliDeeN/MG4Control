package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.ChargePoint
import com.mg4.control.model.ChargeType
import com.mg4.control.model.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
    fun `version A9 (LFP) - barre inerte, heure de fin en grand, dans les sept langues`() {
        for (langue in listOf("fr", "en", "de", "es", "it", "pt", "tr")) {
            val loc = localise(langue)
            val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
            for (e in listOf(etat(), etat(finie = true, restant = null), etat(limite = null, restant = null))) {
                val (vue, vues) = ChargePopupOverlay.preparer(themed, loc, e, lfp = true)
                assertFalse(vues.curseur.reglable)
                assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_hint).visibility)
                assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_lfp_note).visibility)
                val fin = vue.findViewById<TextView>(R.id.charge_end_time).text.toString()
                assertTrue("$langue : « $fin »", fin.isNotEmpty())
                assertFalse(vues.courbe.donnees!!.afficherLimite)
            }
        }
        // Hors A9 : curseur réglable, aide visible, note LFP cachée — et la même heure de fin en bas.
        val loc = localise("fr")
        val (vue, vues) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc, etat(), lfp = false)
        assertTrue(vues.curseur.reglable)
        assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_hint).visibility)
        assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_lfp_note).visibility)
        assertTrue(vue.findViewById<TextView>(R.id.charge_end_time).text.isNotEmpty())
        assertTrue(vues.courbe.donnees!!.afficherLimite)
    }

    /**
     * La colonne de gauche (288 dp de contenu) tient en taille de texte Standard et Grand, sur les
     * deux familles de firmwares et dans les langues aux textes les plus longs : son dernier
     * élément visible finit avant le bas. Compté à la main le 2026-10-18 : ~24 dp de marge en
     * Standard, ~5 en Grand — la version A9 de la veille débordait de 31 dp sans que rien le voie.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `la colonne de gauche tient en Standard et en Grand`() {
        for (echelle in listOf(1f, 1.125f)) for (langue in listOf("fr", "de", "tr")) for (lfp in listOf(false, true)) {
            val loc = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(langue))
                fontScale = echelle
            })
            val (vue, _) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc, etat(), lfp)
            val d = loc.resources.displayMetrics.density
            vue.measure(
                View.MeasureSpec.makeMeasureSpec((1300 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((500 * d).toInt(), View.MeasureSpec.EXACTLY))
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            val colonne = vue.findViewById<ViewGroup>(R.id.charge_left)
            val dernier = (0 until colonne.childCount).map { colonne.getChildAt(it) }.last { it.visibility == View.VISIBLE }
            val bas = colonne.height - colonne.paddingBottom
            assertTrue("$langue ×$echelle lfp=$lfp : finit à ${dernier.bottom} px pour $bas", dernier.bottom <= bas)
        }
    }

    @Test
    fun `l editeur du prix s ouvre et suit les boutons`() {
        val loc = localise("fr")
        val (vue, vues) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc, etat())
        vues.cout.vue.performClick()
        assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_price_editor).visibility)
        // Attendu écrit dans la langue que le pop-up a réellement reçue : sous Robolectric, un
        // contexte « fr » créé par createConfigurationContext peut garder la langue par défaut
        // (« 0.490 » au lieu de « 0,490 », CI rouge du 16 au 17/10). Ce qu'on teste ici, c'est
        // l'éditeur : il s'ouvre sur le prix, et « + » ajoute un centime.
        assertEquals(MoneyFormat.price(0.49f, vues.locale), vue.findViewById<TextView>(R.id.price_value).text.toString())
        vue.findViewById<View>(R.id.price_plus).performClick()
        assertEquals(MoneyFormat.price(0.50f, vues.locale), vue.findViewById<TextView>(R.id.price_value).text.toString())
        vue.findViewById<View>(R.id.price_cancel).performClick()
        assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_price_editor).visibility)
    }
}
