package com.mg4.control.ui

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.mg4.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Statistiques (2026-11-13) : seul l'onglet affiché est dessiné ; changer d'onglet dessine celui
 * qu'on ouvre. Aucun onglet ne doit rester vide en y arrivant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class StatsTabsRenderTest {

    @Test
    fun `each tab is drawn when it is opened`() {
        val c = Robolectric.buildActivity(FragmentActivity::class.java)
        c.get().setTheme(R.style.Theme_MG4Control)
        c.setup()
        val f = StatsFragment()
        c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val v = f.requireView()
        listOf(
            Triple(R.id.btn_stats_cat_trips, R.id.page_stats_trips, R.id.stats_trips_tiles),
            Triple(R.id.btn_stats_cat_charges, R.id.page_stats_charges, R.id.stats_charges_tiles),
            Triple(R.id.btn_stats_cat_trips, R.id.page_stats_trips, R.id.stats_trips_list),
            Triple(R.id.btn_stats_cat_charges, R.id.page_stats_charges, R.id.stats_charges_list),
        ).forEach { (onglet, page, contenu) ->
            v.findViewById<View>(onglet).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, v.findViewById<View>(page).visibility)
            assertTrue("contenu dessiné", v.findViewById<LinearLayout>(contenu).childCount > 0)
        }
    }
}
