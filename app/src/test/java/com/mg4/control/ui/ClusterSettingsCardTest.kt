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
            R.id.btn_cluster_layout,
            R.id.btn_cluster_unit_wh, R.id.btn_cluster_unit_kwh,
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

    @Test
    fun `la personnalisation avancee s ouvre, se regle et rend ce qu on enregistre`() {
        val activite = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        var recu: com.mg4.control.model.ClusterLayout? = null
        val editeur = ClusterLayoutEditor(activite, com.mg4.control.model.ClusterConfig(enabled = true, pill = "mode"), null,
            "Profil", ClusterTab.NAVIGATION) { d, _ -> recu = d }
        editeur.ouvrir()
        shadowOf(Looper.getMainLooper()).idle()
        val fenetre = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val panneau = fenetre.findViewById<android.widget.LinearLayout>(R.id.panel_cluster_layout)
        assertTrue(panneau.childCount > 5)
        fenetre.findViewById<View>(R.id.btn_cluster_layout_reset).performClick()
        fenetre.findViewById<View>(R.id.btn_cluster_layout_save).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // La disposition d'avant : la pastille du mode en Info 1.
        assertEquals(com.mg4.control.model.RuleSignal.DRIVE_MODE.name,
            com.mg4.control.model.ClusterLayouts.slots(recu!!)[0]?.info)
    }

    @Test
    fun `gauge options follow the gauge chosen for the preview`() {
        val activite = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        ClusterLayoutEditor(activite, com.mg4.control.model.ClusterConfig(enabled = true, navigation = "consumption"), null,
            "Profil", ClusterTab.NAVIGATION) { _, _ -> }.ouvrir()
        shadowOf(Looper.getMainLooper()).idle()
        val fenetre = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        fun tous(v: View, out: MutableList<View> = mutableListOf()): List<View> {
            out += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) tous(v.getChildAt(i), out); return out
        }
        fun textes() = tous(fenetre.findViewById(R.id.panel_cluster_layout)).filterIsInstance<TextView>().map { it.text.toString() }
        val moyenne = activite.getString(R.string.cluster_layout_average_cursor)
        val kwDessous = activite.getString(R.string.cluster_layout_consumption_kw)
        val consoDessous = activite.getString(R.string.cluster_layout_power_consumption)
        val unite = activite.getString(R.string.cluster_layout_power_unit)
        // Consommation : ses options, pas celles de la puissance ; l'unité seulement avec les kW dessous.
        assertTrue(moyenne in textes() && kwDessous in textes())
        assertTrue(consoDessous !in textes() && unite !in textes())
        tous(fenetre.findViewById(R.id.panel_cluster_layout)).filterIsInstance<android.widget.Switch>()
            .first { it.text.toString() == kwDessous }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(unite in textes())
        tous(fenetre.findViewById(R.id.panel_cluster_layout)).filterIsInstance<TextView>()
            .first { it.text.toString() == activite.getString(R.string.settings_cluster_power) }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(consoDessous in textes() && unite in textes())
        assertTrue(moyenne !in textes() && kwDessous !in textes())
    }
}
