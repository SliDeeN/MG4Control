package com.mg4.control.ui

import android.os.Looper
import android.util.SparseArray
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
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
 * Tableau de bord (2026-11-14) : en revenant sur l'écran, Android remettait l'état sauvegardé des
 * interrupteurs après la pose des écouteurs, qui écrivaient alors dans la voiture (TSR, AEB, ELK).
 * Aucun interrupteur ne doit plus être remis par la sauvegarde de la vue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class DashboardSwitchRestoreTest {

    private fun interrupteurs(v: View): List<CompoundButton> = when (v) {
        is CompoundButton -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { interrupteurs(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `restoring the view state never flips a switch`() {
        val c = Robolectric.buildActivity(FragmentActivity::class.java)
        c.get().setTheme(R.style.Theme_MG4Control)
        c.setup()
        val f = DashboardFragment()
        c.get().supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        val v = f.requireView()
        val tous = interrupteurs(v)
        assertTrue("des interrupteurs existent", tous.isNotEmpty())
        tous.forEach { assertEquals("${v.resources.getResourceEntryName(it.id.takeIf { i -> i != View.NO_ID } ?: R.id.switch_tsr)} sauvegardé", false, it.isSaveEnabled) }
        // Ce que faisait Android au retour sur l'écran : sauvegarde avec tout coché, puis restauration.
        val etat = SparseArray<Parcelable>()
        tous.forEach { it.isChecked = true }
        v.saveHierarchyState(etat)
        tous.forEach { it.isChecked = false }
        var declenches = 0
        tous.forEach { it.setOnCheckedChangeListener { _, _ -> declenches++ } }
        v.restoreHierarchyState(etat)
        assertEquals("aucun écouteur déclenché par la restauration", 0, declenches)
    }
}
