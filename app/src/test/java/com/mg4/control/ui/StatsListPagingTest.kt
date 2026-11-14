package com.mg4.control.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.mg4.control.R
import com.mg4.control.model.Trip
import com.mg4.control.stats.StatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Liste des trajets par paquets de 50 (2026-11-14) : Afficher 50 de plus, puis Tout afficher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class StatsListPagingTest {

    private fun textes(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textes(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `trips come 50 at a time, then all`() {
        val c = Robolectric.buildActivity(FragmentActivity::class.java)
        c.get().setTheme(R.style.Theme_MG4Control)
        c.setup()
        val store = StatsStore(c.get())
        store.clear()
        val maintenant = System.currentTimeMillis()
        repeat(120) { i ->
            val fin = maintenant - i * 3_600_000L
            store.addTrip(Trip(startMs = fin - 1_800_000L, endMs = fin, distanceKm = 12, energyKwh = 2f, climateKwh = null,
                accessoriesKwh = null, regenKwh = null, socStart = 60f, socEnd = 58f, outsideTempC = 10f))
        }
        val f = StatsFragment()
        c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val v = f.requireView()
        v.findViewById<View>(R.id.btn_stats_cat_trips).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // Période « Tout » : les 120 trajets (sur 5 jours) comptent.
        textes(v).first { it.text == "Tout" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val liste = v.findViewById<LinearLayout>(R.id.stats_trips_list)
        assertEquals("50 lignes + le bas de liste", 51, liste.childCount)
        val bas = textes(liste.getChildAt(50)).map { it.text.toString() }
        assertEquals(listOf("50 sur 120 trajets affichés", "Afficher 50 de plus", "Tout afficher"), bas)

        textes(liste).first { it.text == "Afficher 50 de plus" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(101, liste.childCount)
        assertEquals("Afficher 20 de plus", textes(liste.getChildAt(100))[1].text.toString())

        textes(liste).first { it.text == "Tout afficher" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("tout, sans bas de liste", 120, liste.childCount)
        assertNull(textes(liste).firstOrNull { it.text == "Tout afficher" })
    }
}
