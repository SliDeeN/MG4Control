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
 * Le bandeau « une mise à jour est disponible » se gonfle, et sa mise en page tient ce que le code
 * lui prête : [UpdateOverlay] cherche chaque vue par son identifiant et lui suppose un type, et une
 * erreur là ne se verrait qu'au moment d'annoncer une mise à jour, dans la voiture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateOverlayLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(): View = LayoutInflater.from(themed).inflate(R.layout.overlay_update, null)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val view = gonfler()
        assertNotNull(view.findViewById<ImageView>(R.id.update_icon))
        assertNotNull(view.findViewById<ImageView>(R.id.update_arrow))
        assertNotNull(view.findViewById<TextView>(R.id.update_version_current))
        assertNotNull(view.findViewById<TextView>(R.id.update_version_new))
        assertNotNull(view.findViewById<TextView>(R.id.update_btn_disable))
        assertNotNull(view.findViewById<View>(R.id.update_btn_later))
        assertNotNull(view.findViewById<TextView>(R.id.update_countdown))
        assertNotNull(view.findViewById<TextView>(R.id.update_btn_skip))
        assertNotNull(view.findViewById<View>(R.id.update_btn_install))
        assertNotNull(view.findViewById<ImageView>(R.id.update_install_icon))
        assertNotNull(view.findViewById<TextView>(R.id.update_install_label))
    }

    /** Le même bandeau que la confirmation OUI / NON : même largeur, même hauteur. */
    @Test
    fun `le bandeau a la taille annoncee`() {
        val densite = themed.resources.displayMetrics.density
        val view = gonfler()
        val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(libre, libre)
        val carte = (view as ViewGroup).getChildAt(0)
        assertEquals(1108f * densite, carte.measuredWidth.toFloat(), 1f)
        assertEquals(80f * densite, carte.measuredHeight.toFloat(), 1f)
    }
}
