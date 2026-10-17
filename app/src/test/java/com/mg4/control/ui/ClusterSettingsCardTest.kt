package com.mg4.control.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.model.ClusterTab
import com.mg4.control.ui.widget.ClusterGaugeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config

/**
 * Carte « Écran conducteur » refondue (proposition A, 2026-10-17) : chaque vue cherchée par
 * [SettingsFragment] existe avec le bon type, et l'aperçu ([ClusterSettingsPreview]) tourne sans
 * erreur, module ou pas. Une vue manquante passe la compilation et ne lève qu'à l'ouverture des
 * Réglages, dans la voiture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterSettingsCardTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private fun gonfler(): View = LayoutInflater.from(themed).inflate(R.layout.fragment_settings, null)

    @Test
    fun `chaque vue de la carte existe et a le bon type`() {
        val v = gonfler()
        listOf(R.id.btn_cluster_nav_none, R.id.btn_cluster_nav_consumption, R.id.btn_cluster_nav_power,
            R.id.btn_cluster_media_none, R.id.btn_cluster_media_consumption, R.id.btn_cluster_media_power,
            R.id.btn_cluster_style_dynamic, R.id.btn_cluster_style_discreet,
            R.id.btn_cluster_pill_mode, R.id.btn_cluster_pill_profile, R.id.btn_cluster_pill_none,
            R.id.btn_cluster_unit_wh, R.id.btn_cluster_unit_kwh,
            R.id.btn_cluster_avg_wh, R.id.btn_cluster_avg_kwh, R.id.btn_cluster_avg_km,
            R.id.btn_cluster_preview_nav, R.id.btn_cluster_preview_media,
        ).forEach { assertNotNull(v.findViewById<MaterialButton>(it)) }
        assertNotNull(v.findViewById<FrameLayout>(R.id.frame_cluster_preview))
        assertNotNull(v.findViewById<TextView>(R.id.tv_cluster_preview_none))
        assertNotNull(v.findViewById<View>(R.id.row_cluster_advanced_toggle))
        assertNotNull(v.findViewById<TextView>(R.id.tv_cluster_advanced))
        assertNotNull(v.findViewById<TextView>(R.id.tv_cluster_advanced_chevron))
        assertNotNull(v.findViewById<View>(R.id.row_cluster_refresh))
        assertNotNull(v.findViewById<Slider>(R.id.slider_cluster_refresh))
        assertNotNull(v.findViewById<Slider>(R.id.slider_cluster_animation))
    }

    @Test
    fun `l apercu tourne et montre la page d origine sans module`() {
        val v = gonfler()
        val apercu = ClusterSettingsPreview(v.findViewById(R.id.frame_cluster_preview), v.findViewById(R.id.tv_cluster_preview_none))
        val cadre = v.findViewById<FrameLayout>(R.id.frame_cluster_preview)
        assertTrue(cadre.getChildAt(0) is ClusterGaugeView)
        apercu.demarrer()
        shadowOf(Looper.getMainLooper()).idle()
        // Musique : aucun module par défaut → la page d'origine.
        apercu.onglet = ClusterTab.MEDIA
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.tv_cluster_preview_none).visibility)
        apercu.arreter()
    }
}
