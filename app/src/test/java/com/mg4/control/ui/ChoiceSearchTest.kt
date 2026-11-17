package com.mg4.control.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Recherche dans une fenêtre de choix (2026-11-12) : une seule tuile par option, retrouvée aussi par
 * sa seconde ligne et par ses mots cachés (les noms de ses modes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChoiceSearchTest {

    private fun libelles(racine: View): List<String> {
        val lignes = racine.findViewById<LinearLayout>(R.id.choice_rows)
        val out = mutableListOf<String>()
        fun parcourir(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.id == R.id.choice_item_label) out += (v as TextView).text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i))
        }
        parcourir(lignes)
        return out
    }

    @Test
    fun `search finds one tile per option, by its second line and its hidden mode words`() {
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        val choix = (1..12).map { AppDialog.Choix("Raccourci $it") } + listOf(
            AppDialog.Choix("Profil actif", sousLibelle = "3 modes", motsCles = "Devient… N'est plus… Le profil actif change"),
        )
        AppDialog.choisir(activite, "Ajouter un déclencheur", choix, "Annuler") { }
        shadowOf(Looper.getMainLooper()).idle()
        val racine = ShadowDialog.getLatestDialog().findViewById<View>(R.id.choice_rows).rootView
        val tuile = "Profil actif\n3 modes"
        assertTrue(tuile in libelles(racine))

        racine.findViewById<EditText>(findEditText(racine)).setText("profil change")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(tuile), libelles(racine))

        racine.findViewById<EditText>(findEditText(racine)).setText("devient")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(tuile), libelles(racine))

        racine.findViewById<EditText>(findEditText(racine)).setText("raccourci 1")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tuile in libelles(racine))
    }

    private fun findEditText(v: View): Int {
        if (v is EditText) { if (v.id == View.NO_ID) v.id = View.generateViewId(); return v.id }
        if (v is ViewGroup) for (i in 0 until v.childCount) findEditText(v.getChildAt(i)).takeIf { it != View.NO_ID }?.let { return it }
        return View.NO_ID
    }
}
