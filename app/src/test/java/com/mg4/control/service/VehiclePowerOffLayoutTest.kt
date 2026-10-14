package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le bandeau « Éteindre le véhicule » se gonfle, et sa mise en page tient ce que le code lui prête :
 * [VehiclePowerOffOverlay] cherche chaque vue par son identifiant et lui suppose un type, et une
 * erreur là ne se verrait qu'au moment d'éteindre la voiture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehiclePowerOffLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(): View = LayoutInflater.from(themed).inflate(R.layout.overlay_power_off, null)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val view = gonfler()
        assertNotNull(view.findViewById<ImageView>(R.id.power_icon))
        assertNotNull(view.findViewById<TextView>(R.id.power_title))
        assertNotNull(view.findViewById<TextView>(R.id.power_message))
        assertNotNull(view.findViewById<View>(R.id.power_btn_cancel))
        assertNotNull(view.findViewById<TextView>(R.id.power_cancel_label))
        assertNotNull(view.findViewById<TextView>(R.id.power_countdown))
        assertNotNull(view.findViewById<View>(R.id.power_btn_confirm))
        assertNotNull(view.findViewById<ImageView>(R.id.power_confirm_icon))
        assertNotNull(view.findViewById<TextView>(R.id.power_confirm_label))
    }

    /** Le même bandeau que les deux autres, que le bouton Éteindre y soit ou non. */
    @Test
    fun `le bandeau a la taille annoncee dans ses deux etats`() {
        val densite = themed.resources.displayMetrics.density
        for (impossible in listOf(false, true)) {
            val view = gonfler()
            if (impossible) view.findViewById<View>(R.id.power_btn_confirm).visibility = View.GONE
            val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            view.measure(libre, libre)
            val carte = (view as ViewGroup).getChildAt(0)
            assertEquals(1108f * densite, carte.measuredWidth.toFloat(), 1f)
            assertEquals(80f * densite, carte.measuredHeight.toFloat(), 1f)
        }
    }
}
