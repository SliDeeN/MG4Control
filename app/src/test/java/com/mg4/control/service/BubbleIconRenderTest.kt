package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.BubbleIcon
import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.FloatingButton
import com.mg4.control.ui.BubbleIcons
import com.mg4.control.ui.BubbleSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Icônes des bulles (2026-11-17) : rendu d'une bulle-icône et d'une information à icône choisie. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class BubbleIconRenderTest {

    private val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)
    private val base = FloatingButton(id = "t", action = "ONE_PEDAL", actionLabel = "1 Pédale")

    private fun peint(b: FloatingButton, titre: String = "1 Pédale") =
        TextView(ctx).also { FloatingButtonsOverlay.peindre(it, b, titre, deplacement = false) }

    @Test fun `every symbol of the library loads`() {
        val tous = BubbleSymbols.CATEGORIES.flatMap { it.symbols }
        assertTrue(tous.size >= 150)
        tous.forEach { assertNotNull(it.name, BubbleIcons.of(ctx, BubbleIcon.symbol(it.name))) }
    }

    @Test fun `icon only shows no text, icon plus title keeps it`() {
        val seule = peint(base.copy(iconContent = true, icon = "sym:ac_unit"))
        assertEquals("", seule.text.toString())
        assertNotNull(seule.compoundDrawables[0])
        val avecTitre = peint(base.copy(iconContent = true, iconTitle = true, icon = "sym:ac_unit"))
        assertEquals("1 Pédale", avecTitre.text.toString())
        assertNotNull(avecTitre.compoundDrawables[0])
    }

    @Test fun `the icon is centred in the bubble`() {
        for (b in listOf(base.copy(iconContent = true, icon = "sym:ac_unit"), base.copy(iconContent = true, icon = "sym:ac_unit", widthDp = 160),
            base.copy(iconContent = true, iconTitle = true, icon = "sym:ac_unit"))) {
            val v = peint(b)
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            assertEquals("marges égales", v.paddingLeft, v.paddingRight)
            val icone = v.compoundDrawables[0]!!.bounds.width()
            val texte = if (v.text.isEmpty()) 0 else v.paint.measureText(v.text.toString()).toInt() + v.compoundDrawablePadding
            assertTrue("contenu au centre (${v.measuredWidth} / ${v.paddingLeft} / $icone / $texte)",
                kotlin.math.abs(v.measuredWidth - (2 * v.paddingLeft + icone + texte)) <= 2)
        }
    }

    @Test fun `no app to show falls back to a symbol, a missing app to the title`() {
        // Sans appli lancée (1 Pédale) : le symbole par défaut.
        assertNotNull(peint(base.copy(iconContent = true)).compoundDrawables[0])
        // Appli désinstallée : la bulle garde son titre.
        val absente = peint(base.copy(iconContent = true, icon = "app:com.absente.nulle_part"))
        assertNull(absente.compoundDrawables[0])
        assertEquals("1 Pédale", absente.text.toString())
    }

    @Test fun `title bubbles are unchanged, information keeps its icon unless another is chosen`() {
        assertNull(peint(base).compoundDrawables[0])
        val info = base.copy(info = "SOC", infoStyle = BubbleInfo.STYLE_ICON)
        assertNotNull(peint(info, "64 %").compoundDrawables[0])
        assertNotNull(peint(info.copy(icon = "sym:bolt"), "64 %").compoundDrawables[0])
        assertEquals("64 %", peint(info.copy(icon = "sym:bolt"), "64 %").text.toString())
    }
}
