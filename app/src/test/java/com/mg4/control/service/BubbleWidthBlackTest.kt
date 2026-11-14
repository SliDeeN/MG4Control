package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.BubbleLook
import com.mg4.control.model.FloatingButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Issue #222 (2026-11-14) : fond Noir, et largeur fixe qui ne bouge plus avec la valeur. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class BubbleWidthBlackTest {

    private val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)
    private val base = FloatingButton(id = "t", action = "NONE")

    private fun largeur(b: FloatingButton, texte: String): Int {
        val v = TextView(ctx)
        FloatingButtonsOverlay.peindre(v, b, texte, deplacement = false)
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return v.measuredWidth
    }

    @Test
    fun `a fixed width does not follow the value, automatic does`() {
        val fixe = base.copy(widthDp = 110)
        val d = ctx.resources.displayMetrics.density
        assertEquals((110 * d).toInt(), largeur(fixe, "6 °C"))
        assertEquals("même largeur, valeur plus longue", largeur(fixe, "6 °C"), largeur(fixe, "−3,5 °C · 12 °C"))
        assertNotEquals("automatique : suit son texte", largeur(base, "6 °C"), largeur(base, "−3,5 °C · 12 °C · 8 °C"))
        assertEquals(BubbleLook.WIDTH_MAX, BubbleLook.width(base.copy(widthDp = 999)))
        assertNull(BubbleLook.width(base))
    }

    @Test
    fun `black is real black and is copied with the width`() {
        val noir = base.copy(fill = BubbleLook.FILL_BLACK, opacity = 100, widthDp = 120)
        assertEquals(0xFF000000.toInt(), BubbleLook.paint(noir, 0xFF38BDF8.toInt(), toggle = null).background)
        val copie = BubbleLook.copy(noir, base.copy(id = "u"))
        assertEquals(BubbleLook.FILL_BLACK, copie.fill)
        assertEquals(120, copie.widthDp)
    }
}
