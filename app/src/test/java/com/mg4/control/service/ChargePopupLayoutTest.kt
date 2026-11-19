package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.ChargeLimitSupport
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

    private fun etat(finie: Boolean = false, limite: Int? = 80, restant: Int? = 21, type: ChargeType = ChargeType.DC,
                     courant: Int? = 4, courantEnAttente: Boolean = false, courantRefuse: Int? = null,
                     controle: Int? = null, controleEnAttente: Boolean = false, controleRefuse: Int? = null,
                     branchee: Boolean? = true, arretee: Boolean = false) = ChargeMonitor.Etat(
        enCharge = !finie, finie = finie, type = type, soc = 62.4f, kw = 90f, kwMax = 134f,
        energieKwh = 27.4f, dureeMin = 14, restantMin = restant, limitePct = limite, limiteEnAttente = false,
        limiteRefusee = null, autonomieKm = 237, autonomieGagneeKm = 169, prix = 0.49f, prixCorrige = false,
        prixDefaut = 0.49f, devise = "€", debutHorlogeMs = 1_760_621_460_000L, horlogeMs = 1_760_622_300_000L,
        ecouleMs = 14 * 60_000L, points = (0..168).map { ChargePoint(it * 5_000L, 120f - it * 0.2f, 18f + it * 0.26f) },
        courantCode = courant, courantEnAttente = courantEnAttente, courantRefuse = courantRefuse,
        controle = controle, controleEnAttente = controleEnAttente, controleRefuse = controleRefuse,
        branchee = if (finie) branchee else true, arretee = arretee,
    )

    @Test
    fun `se peint dans les sept langues`() {
        for (langue in listOf("fr", "en", "de", "es", "it", "pt", "tr", "pl", "nb", "cs")) {
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
    fun `A9 en LFP - barre inerte, heure de fin en grand, dans les sept langues`() {
        for (langue in listOf("fr", "en", "de", "es", "it", "pt", "tr", "pl", "nb", "cs")) {
            val loc = localise(langue)
            val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
            for (e in listOf(etat(), etat(finie = true, restant = null), etat(limite = null, restant = null))) {
                val (vue, vues) = ChargePopupOverlay.preparer(themed, loc, e, a9 = true, codeBatterie = ChargeLimitSupport.CODE_LFP)
                assertFalse(vues.curseur.reglable)
                assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_hint).visibility)
                assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_lfp_note).visibility)
                val fin = vue.findViewById<TextView>(R.id.charge_end_time).text.toString()
                assertTrue("$langue : « $fin »", fin.isNotEmpty())
                assertFalse(vues.courbe.donnees!!.afficherLimite)
            }
        }
        // Ancien SDK, et A9 Confort (NMC, code 7) : curseur réglable, aide visible, note cachée —
        // et la même heure de fin en bas.
        val loc = localise("fr")
        val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
        for ((a9, code) in listOf(false to null, true to 7, true to 9)) {
            val (vue, vues) = ChargePopupOverlay.preparer(themed, loc, etat(), a9 = a9, codeBatterie = code)
            assertTrue("a9=$a9 code=$code", vues.curseur.reglable)
            assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_hint).visibility)
            assertEquals(View.GONE, vue.findViewById<View>(R.id.charge_lfp_note).visibility)
            assertTrue(vue.findViewById<TextView>(R.id.charge_end_time).text.isNotEmpty())
            assertTrue(vues.courbe.donnees!!.afficherLimite)
        }
        // A9 au type de batterie illisible : pas de curseur, et une note qui ne parle pas de LFP.
        val (vue, vues) = ChargePopupOverlay.preparer(themed, loc, etat(), a9 = true, codeBatterie = null)
        assertFalse(vues.curseur.reglable)
        assertEquals(loc.getString(R.string.charge_popup_limit_fixed),
            vue.findViewById<TextView>(R.id.charge_lfp_note_text).text.toString())
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
            val (vue, _) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc, etat(),
                a9 = lfp, codeBatterie = if (lfp) ChargeLimitSupport.CODE_LFP else null)
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

    /** Courant de charge AC (#215) : choisi en surbrillance en AC, grisé et inerte en DC, titre selon l'état. */
    @Test
    fun `le courant AC se peint selon la prise et l envoi`() {
        val loc = localise("fr")
        val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
        val titre = { v: View -> v.findViewById<TextView>(R.id.charge_current_title).text.toString() }

        val (ac, vuesAc) = ChargePopupOverlay.preparer(themed, loc, etat(type = ChargeType.AC, courant = 3))
        assertEquals(4, vuesAc.casesCourant.size)
        assertEquals(listOf("6 A", "8 A", "16 A", loc.getString(R.string.charge_current_max)), vuesAc.casesCourant.map { it.text.toString() })
        assertTrue(vuesAc.casesCourant.all { it.isEnabled })
        assertEquals(loc.getString(R.string.charge_popup_current), titre(ac))
        assertEquals(vues(ac).getColor(R.color.text_primary), vuesAc.casesCourant[2].currentTextColor)

        val (dc, vuesDc) = ChargePopupOverlay.preparer(themed, loc, etat(type = ChargeType.DC))
        assertTrue(vuesDc.casesCourant.none { it.isEnabled })
        assertEquals(loc.getString(R.string.charge_popup_current_ac_only), titre(dc))

        val (envoi, _) = ChargePopupOverlay.preparer(themed, loc, etat(type = ChargeType.AC, courant = 2, courantEnAttente = true))
        assertEquals(loc.getString(R.string.charge_popup_current_sending, "8 A"), titre(envoi))
        val (refus, _) = ChargePopupOverlay.preparer(themed, loc, etat(type = ChargeType.AC, courant = 4, courantRefuse = 1))
        assertEquals(loc.getString(R.string.charge_popup_current_refused, "6 A"), titre(refus))
    }

    private fun vues(v: View): Context = v.context

    /** Commencer / Arrêter la charge (variante A) : selon la valeur lue, la prise et l'envoi. */
    @Test
    fun `le bouton de charge suit la voiture et la prise`() {
        val loc = localise("fr")
        val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
        fun bouton(e: ChargeMonitor.Etat): TextView =
            ChargePopupOverlay.preparer(themed, loc, e).first.findViewById(R.id.charge_control)

        val enCharge = bouton(etat(controle = 1))
        assertEquals(View.VISIBLE, enCharge.visibility)
        assertEquals(loc.getString(R.string.charge_control_stop), enCharge.text.toString())
        assertTrue(enCharge.isEnabled)

        val arretee = ChargePopupOverlay.preparer(themed, loc, etat(finie = true, restant = null, controle = 2, arretee = true)).first
        assertEquals(loc.getString(R.string.charge_control_start), arretee.findViewById<TextView>(R.id.charge_control).text.toString())
        assertEquals(loc.getString(R.string.charge_popup_title_stopped), arretee.findViewById<TextView>(R.id.charge_title).text.toString())
        // Terminée (pas le statut 8) : le titre d'avant.
        val terminee = ChargePopupOverlay.preparer(themed, loc, etat(finie = true, restant = null, controle = 2)).first
        assertEquals(loc.getString(R.string.charge_popup_title_done), terminee.findViewById<TextView>(R.id.charge_title).text.toString())

        val prep = bouton(etat(controle = 3))
        assertEquals(loc.getString(R.string.charge_control_preparing), prep.text.toString())
        assertFalse(prep.isEnabled)
        assertFalse(bouton(etat(controle = 2, controleEnAttente = true)).isEnabled)
        assertEquals(loc.getString(R.string.charge_control_refused), bouton(etat(controle = 1, controleRefuse = 2)).text.toString())

        assertEquals(View.GONE, bouton(etat(finie = true, restant = null, controle = 2, branchee = false)).visibility)
    }

    /** Ouvert au raccourci sans charge suivie (2026-11-19) : message à la place de la courbe, réglages actifs. */
    private fun sansCharge(branchee: Boolean? = true, verrou: Int? = 1) = ChargeMonitor.vide().copy(
        soc = 74.5f, limitePct = 80, autonomieKm = 281, prixDefaut = 0.25f, prix = 0.25f, type = ChargeType.AC,
        courantCode = 3, branchee = branchee, verrou = verrou)

    @Test
    fun `sans charge suivie, le pop-up s ouvre quand meme dans les dix langues`() {
        for (langue in listOf("fr", "en", "de", "es", "it", "pt", "tr", "pl", "nb", "cs")) {
            val loc = localise(langue)
            val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
            for (e in listOf(ChargeMonitor.vide(), sansCharge(), sansCharge(branchee = false), sansCharge(branchee = null, verrou = null))) {
                val (vue, vues) = ChargePopupOverlay.preparer(themed, loc, e)
                assertEquals(loc.getString(R.string.charge_popup_none), vue.findViewById<TextView>(R.id.charge_title).text.toString())
                assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_idle).visibility)
                assertEquals(View.INVISIBLE, vues.courbe.visibility)
                assertEquals("–", vues.energie.valeur.text.toString())
                assertEquals(View.GONE, vues.cout.crayon.visibility)
            }
        }
        val loc = localise("fr")
        val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
        val (branchee, vb) = ChargePopupOverlay.preparer(themed, loc, sansCharge())
        assertEquals(loc.getString(R.string.charge_popup_plugged), branchee.findViewById<TextView>(R.id.charge_pill).text.toString())
        assertEquals(loc.getString(R.string.charge_control_start), branchee.findViewById<TextView>(R.id.charge_control).text.toString())
        assertEquals(loc.getString(R.string.charge_popup_range_now), branchee.findViewById<TextView>(R.id.charge_end_title).text.toString())
        assertTrue(branchee.findViewById<TextView>(R.id.charge_end_time).text.toString().startsWith("281"))
        assertTrue(vb.casesCourant.all { it.isEnabled })
        assertTrue(vb.curseur.reglable)
        // Le prix n'est pas modifiable : pas de charge à corriger.
        vb.cout.vue.performClick()
        assertEquals(View.GONE, branchee.findViewById<View>(R.id.charge_price_editor).visibility)

        val (debranchee, _) = ChargePopupOverlay.preparer(themed, loc, sansCharge(branchee = false))
        assertEquals(loc.getString(R.string.charge_popup_unplugged), debranchee.findViewById<TextView>(R.id.charge_pill).text.toString())
        assertEquals(View.GONE, debranchee.findViewById<View>(R.id.charge_control).visibility)
        assertEquals(View.VISIBLE, debranchee.findViewById<View>(R.id.charge_port_lock).visibility)

        val (inconnue, _) = ChargePopupOverlay.preparer(themed, loc, sansCharge(branchee = null))
        assertEquals(View.GONE, inconnue.findViewById<View>(R.id.charge_pill).visibility)

        // Avec une charge suivie : la courbe, pas le message.
        val (charge, vc) = ChargePopupOverlay.preparer(themed, loc, etat())
        assertEquals(View.GONE, charge.findViewById<View>(R.id.charge_idle).visibility)
        assertEquals(View.VISIBLE, vc.courbe.visibility)
    }

    /** Verrou de la prise (variante A) : dit ce qu'il fera, envoi, refus ; caché quand la voiture ne le donne pas. */
    @Test
    fun `le bouton du verrou de la prise suit la voiture`() {
        val loc = localise("fr")
        val themed = ContextThemeWrapper(loc, R.style.Theme_MG4Control)
        fun bouton(e: ChargeMonitor.Etat, a9: Boolean = false): TextView =
            ChargePopupOverlay.preparer(themed, loc, e, a9 = a9, codeBatterie = if (a9) 7 else null).first.findViewById(R.id.charge_port_lock)

        val verrouillee = bouton(etat().copy(verrou = 1))
        assertEquals(View.VISIBLE, verrouillee.visibility)
        assertEquals(loc.getString(R.string.charge_port_unlock), verrouillee.text.toString())
        assertTrue(verrouillee.isEnabled)
        assertEquals(loc.getString(R.string.charge_port_lock), bouton(etat().copy(verrou = 2)).text.toString())

        assertEquals(View.GONE, bouton(etat().copy(verrou = 0)).visibility)
        assertEquals(View.GONE, bouton(etat().copy(verrou = null)).visibility)
        assertEquals(loc.getString(R.string.charge_port_lock), bouton(etat().copy(verrou = 0), a9 = true).text.toString())

        val envoi = bouton(etat().copy(verrou = 2, verrouEnAttente = true))
        assertEquals(loc.getString(R.string.charge_port_sending), envoi.text.toString())
        assertFalse(envoi.isEnabled)
        assertEquals(loc.getString(R.string.charge_control_refused), bouton(etat().copy(verrou = 1, verrouRefuse = 2)).text.toString())
    }

    /**
     * Barre du haut au plus chargé (verrou, marche / arrêt, pastille) en Standard et en Grand, dans
     * les langues aux textes les plus longs : le titre garde de la place, la croix reste dans la carte.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `la barre du haut tient avec le verrou et la marche de la charge`() {
        for (echelle in listOf(1f, 1.125f)) for (langue in listOf("fr", "de", "tr", "pt", "cs")) {
            val loc = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(langue))
                fontScale = echelle
            })
            val (vue, _) = ChargePopupOverlay.preparer(ContextThemeWrapper(loc, R.style.Theme_MG4Control), loc,
                sansCharge(verrou = 1))
            val d = loc.resources.displayMetrics.density
            vue.measure(
                View.MeasureSpec.makeMeasureSpec((1300 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((500 * d).toInt(), View.MeasureSpec.EXACTLY))
            vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
            val titre = vue.findViewById<View>(R.id.charge_title)
            val croix = vue.findViewById<View>(R.id.charge_close)
            val barre = croix.parent as View
            assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_port_lock).visibility)
            assertEquals(View.VISIBLE, vue.findViewById<View>(R.id.charge_control).visibility)
            assertTrue("$langue ×$echelle : titre de ${titre.width / d} dp", titre.width >= 150 * d)
            assertTrue("$langue ×$echelle : croix à ${croix.right} pour ${barre.width}", croix.right <= barre.width)
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
