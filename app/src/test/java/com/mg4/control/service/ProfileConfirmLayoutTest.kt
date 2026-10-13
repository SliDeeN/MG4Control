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
 * Le bandeau de confirmation OUI / NON se gonfle, et sa mise en page tient ce que le code lui prête.
 *
 * [ProfileConfirmOverlay] cherche chaque vue par son identifiant et lui suppose un type : une
 * erreur là passe à la compilation et lève quand la question se pose, dans la voiture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileConfirmLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(): View = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_confirm, null)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val view = gonfler()
        assertNotNull(view.findViewById<ImageView>(R.id.confirm_icon))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_value))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_unit))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_threshold))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_profile))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_mode))
        assertNotNull(view.findViewById<View>(R.id.confirm_btn_no))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_countdown))
        assertNotNull(view.findViewById<TextView>(R.id.confirm_btn_yes))
    }

    /** Un bandeau : la largeur des deux grands pop-ups, et à peine un cinquième de leur hauteur. */
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
