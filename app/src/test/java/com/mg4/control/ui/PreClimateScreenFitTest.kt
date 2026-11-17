package com.mg4.control.ui

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.service.OverlayKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Écran du Garde-voiture « format téléphone » (2026-11-12, photos d'un utilisateur) : ouvert dans
 * une fenêtre d'abord étroite, le cadre recevait la largeur de son contenu (686 dp au lieu de
 * 1280) et une réduction qui restait. Il doit garder 1280 × 480 dp, réduit d'un bloc, puis
 * revenir à sa taille quand la fenêtre prend tout l'écran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class PreClimateScreenFitTest {

    @Test
    fun `a narrow first window scales the frame, which keeps its size and recovers`() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)
        val racine = LayoutInflater.from(ctx).inflate(R.layout.activity_preclim, null)
        val d = ctx.resources.displayMetrics.density
        val cadre = racine.findViewById<View>(R.id.preclim_frame)
        OverlayKit.tenirDansLaFenetre(racine, (8 * d).toInt(), "test", carte = cadre)
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val hote = FrameLayout(activite)
        activite.setContentView(hote)
        // D'abord une fenêtre plus étroite que le contenu (686 dp), comme sur la voiture où 686 dp font 977 px.
        hote.addView(racine, FrameLayout.LayoutParams(600, 1000))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("largeur gardée", 1280 * d, cadre.layoutParams.width.toFloat(), 1f)
        assertEquals("hauteur gardée", 480 * d, cadre.layoutParams.height.toFloat(), 1f)
        assertTrue("réduite d'un bloc : ${cadre.scaleX}", cadre.scaleX < 0.6f && cadre.scaleX == cadre.scaleY)
        // Puis tout l'écran : la réduction disparaît.
        racine.layoutParams = FrameLayout.LayoutParams(1920, 720)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1280 * d, cadre.layoutParams.width.toFloat(), 1f)
        assertEquals("taille retrouvée", 1f, cadre.scaleX, 0.001f)
    }
}
