package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.model.ProfilePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le sélecteur de profils se gonfle, et sa mise en page tient les promesses que le code lui prête.
 *
 * [ProfilePickerOverlay] cherche chaque vue par son identifiant et lui suppose un type : une
 * erreur là passe à la compilation et lève à l'ouverture du popup, dans la voiture. Il calcule
 * aussi son agrandissement d'après une carte de 1108 × 392 dp, et la largeur des cartes de profil
 * d'après une rangée de [ProfilePicker.RANGEE_DP] : ces cotes sont vérifiées ici.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilePickerLayoutTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(): View = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_picker, null)

    @Test
    fun `chaque vue cherchee par le code existe et a le bon type`() {
        val view = gonfler()
        assertNotNull(view.findViewById<View>(R.id.overlay_backdrop))
        assertNotNull(view.findViewById<View>(R.id.overlay_card))
        assertNotNull(view.findViewById<ImageView>(R.id.overlay_title_icon))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_countdown))
        assertNotNull(view.findViewById<ImageView>(R.id.overlay_btn_close))
        assertNotNull(view.findViewById<View>(R.id.overlay_joy_chip))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_joy_title))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_joy_move))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_joy_ok))
        assertNotNull(view.findViewById<HorizontalScrollView>(R.id.overlay_profiles_scroll))
        assertNotNull(view.findViewById<LinearLayout>(R.id.overlay_profiles_container))
        assertNotNull(view.findViewById<View>(R.id.overlay_brightness_section))
        assertNotNull(view.findViewById<ImageView>(R.id.overlay_bri_icon))
        assertNotNull(view.findViewById<View>(R.id.overlay_bri_presets))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_bri_night))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_bri_mid))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_bri_day))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_bri_value))
        assertNotNull(view.findViewById<View>(R.id.overlay_bri_slider_row))
        assertNotNull(view.findViewById<Slider>(R.id.overlay_bri_slider))
        assertNotNull(view.findViewById<View>(R.id.overlay_actions))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_btn_open_app))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_btn_preclim))
        assertNotNull(view.findViewById<TextView>(R.id.overlay_btn_poweroff))
    }

    @Test
    fun `une carte de profil se gonfle dans la rangee`() {
        val container = gonfler().findViewById<LinearLayout>(R.id.overlay_profiles_container)
        val carte = LayoutInflater.from(themed).inflate(R.layout.item_overlay_profile_card, container, false)
        assertNotNull(carte.findViewById<TextView>(R.id.profile_card_mode))
        assertNotNull(carte.findViewById<TextView>(R.id.profile_card_active))
        assertNotNull(carte.findViewById<TextView>(R.id.profile_card_name))
        assertNotNull(carte.findViewById<TextView>(R.id.profile_card_summary))
        assertNotNull(carte.findViewById<TextView>(R.id.profile_card_action))
    }

    /** Les cotes d'après lesquelles le popup calcule son agrandissement et la largeur des cartes. */
    @Test
    fun `la carte a la taille annoncee, quel que soit le nombre de profils`() {
        val densite = themed.resources.displayMetrics.density
        for (nombre in listOf(1, 5, 9)) {
            val view = gonfler()
            val rangee = view.findViewById<HorizontalScrollView>(R.id.overlay_profiles_scroll)
            val container = view.findViewById<LinearLayout>(R.id.overlay_profiles_container)
            val largeur = (ProfilePicker.largeurCarteDp(nombre) * densite).toInt()
            repeat(nombre) {
                val carte = LayoutInflater.from(themed).inflate(R.layout.item_overlay_profile_card, container, false)
                carte.layoutParams = LinearLayout.LayoutParams(largeur, LinearLayout.LayoutParams.MATCH_PARENT)
                container.addView(carte)
            }
            view.measure(
                MeasureSpec.makeMeasureSpec((2400 * densite).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((1200 * densite).toInt(), MeasureSpec.EXACTLY))
            val carte = (view as ViewGroup).getChildAt(0)
            assertEquals("$nombre profil(s)", 1108f * densite, carte.measuredWidth.toFloat(), 1f)
            assertEquals("$nombre profil(s)", 392f * densite, carte.measuredHeight.toFloat(), 1f)
            assertEquals(ProfilePicker.RANGEE_DP * densite, rangee.measuredWidth.toFloat(), 1f)
        }
    }
}
