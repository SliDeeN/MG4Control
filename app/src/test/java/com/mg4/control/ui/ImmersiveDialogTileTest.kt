package com.mg4.control.ui

import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.widget.TextView
import com.mg4.control.R
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Fenêtre des applis en plein écran (2026-11-15) : repeindre son bouton Enregistrer colorait le fond
 * partagé des tuiles (bg_dialog_tile) — recherche, Annuler et tuiles des fenêtres suivantes passaient
 * à la couleur d'accent. [com.mg4.control.service.OverlayKit.fond] travaille désormais sur une copie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImmersiveDialogTileTest {

    @Test
    fun `saving button colour does not leak to the other tiles`() {
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        val accent = activite.getColor(R.color.dash_accent)
        fun fondDe(id: Int): Int? {
            shadowOf(Looper.getMainLooper()).idle()
            val v = ShadowDialog.getLatestDialog().findViewById<TextView>(id)
            return (v.background as? GradientDrawable)?.color?.defaultColor
        }

        ImmersiveAppsDialog.show(activite) {}
        // Dans la fenêtre même : Enregistrer prend l'accent, Annuler garde la tuile.
        assertNotEquals(accent, fondDe(R.id.choice_back))
        ShadowDialog.getLatestDialog().dismiss()

        // Et une fenêtre ouverte ensuite n'en hérite pas.
        AppDialog.choisir(activite, "Choix", (1..3).map { AppDialog.Choix("Option $it") }, "Annuler") { }
        assertNotEquals(accent, fondDe(R.id.choice_cancel))
    }
}
