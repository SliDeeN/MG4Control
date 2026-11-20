package com.mg4.control.ui

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterModuleDef
import com.mg4.control.model.ClusterModuleDefs
import com.mg4.control.model.RuleSignal
import com.mg4.control.ui.widget.ClusterGaugeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Carte « Écran conducteur » refondue le 2026-11-20 (bibliothèque de modules, listes par onglet,
 * réglages communs) et personnalisation d'un module : chaque vue cherchée par [SettingsFragment]
 * existe, et la fenêtre s'ouvre, se règle et rend le module. Une vue manquante passe la compilation
 * et ne lève qu'à l'ouverture des Réglages, dans la voiture.
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
        listOf(R.id.btn_cluster_power_auto, R.id.btn_cluster_power_125, R.id.btn_cluster_power_150,
            R.id.btn_cluster_power_180, R.id.btn_cluster_power_320,
            R.id.btn_cluster_profiles_all, R.id.btn_cluster_profiles_current,
        ).forEach { assertNotNull(v.findViewById<MaterialButton>(it)) }
        listOf(R.id.cluster_tabs, R.id.cluster_library, R.id.cluster_tyre_unit_row, R.id.grid_cluster_profiles)
            .forEach { assertNotNull(v.findViewById<LinearLayout>(it)) }
        listOf(R.id.tv_cluster_tabs_title, R.id.tv_cluster_advanced, R.id.tv_cluster_advanced_chevron,
            R.id.tv_cluster_power_desc, R.id.tv_cluster_refresh, R.id.tv_cluster_animation, R.id.tv_cluster_profiles_info,
            R.id.tv_cluster_module_mixed).forEach { assertNotNull(v.findViewById<TextView>(it)) }
        assertNotNull(v.findViewById<View>(R.id.row_cluster_choice))
        assertNotNull(v.findViewById<View>(R.id.row_cluster_advanced_toggle))
        assertNotNull(v.findViewById<View>(R.id.row_cluster_refresh))
        assertNotNull(v.findViewById<Slider>(R.id.slider_cluster_refresh))
        assertNotNull(v.findViewById<Slider>(R.id.slider_cluster_animation))
    }

    private fun tous(v: View, out: MutableList<View> = mutableListOf()): List<View> {
        out += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) tous(v.getChildAt(i), out); return out
    }

    @Test
    fun `un module s ouvre, se regle et revient a l enregistrement`() {
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        var recu: ClusterModuleDef? = null
        val depart = ClusterModuleDef("m1", "Conso", layout = ClusterModuleDefs.defaultLayout(ClusterModule.CONSUMPTION))
        ClusterLayoutEditor(activite, depart, false, "Paul · Navigation 1", null, ClusterConfig(),
            surEnregistre = { recu = it }).ouvrir()
        shadowOf(Looper.getMainLooper()).idle()
        val fenetre = ShadowDialog.getLatestDialog()
        val panneau = fenetre.findViewById<LinearLayout>(R.id.panel_cluster_layout)
        fun textes() = tous(panneau).filterIsInstance<TextView>().map { it.text.toString() }
        // Le bloc Module en tête : nom, centre, style, unité ; Dupliquer et Supprimer visibles.
        assertTrue(activite.getString(R.string.cluster_module_center) in textes())
        assertTrue(activite.getString(R.string.cluster_module_unit) in textes())
        assertEquals(View.VISIBLE, fenetre.findViewById<View>(R.id.btn_cluster_module_delete).visibility)
        // Centre → Pneus : plus de style ni d'unité de consommation ; l'unité de pression apparaît.
        tous(panneau).filterIsInstance<TextView>().first { it.text.toString() == activite.getString(R.string.settings_cluster_tyres) }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activite.getString(R.string.cluster_module_unit) !in textes())
        assertTrue(activite.getString(R.string.cluster_layout_tyre_unit) in textes())
        tous(panneau).filterIsInstance<android.widget.EditText>().first().setText("Mes pneus")
        fenetre.findViewById<View>(R.id.btn_cluster_layout_save).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("tyres", recu!!.type)
        assertEquals("Mes pneus", recu!!.name)
        assertEquals("m1", recu!!.id)
    }

    @Test
    fun `un nouveau module n a ni dupliquer ni supprimer, et reprend la disposition par defaut`() {
        val activite = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activite.setTheme(R.style.Theme_MG4Control)
        var recu: ClusterModuleDef? = null
        ClusterLayoutEditor(activite, ClusterModuleDef("n", ""), true, "", null, ClusterConfig(),
            surEnregistre = { recu = it }).ouvrir()
        shadowOf(Looper.getMainLooper()).idle()
        val fenetre = ShadowDialog.getLatestDialog()
        assertEquals(View.GONE, fenetre.findViewById<View>(R.id.btn_cluster_module_duplicate).visibility)
        assertEquals(View.GONE, fenetre.findViewById<View>(R.id.btn_cluster_module_delete).visibility)
        fenetre.findViewById<View>(R.id.btn_cluster_layout_reset).performClick()
        fenetre.findViewById<View>(R.id.btn_cluster_layout_save).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // Sans nom : celui du type ; disposition d avant (pastille du profil en Info 1, comme sans réglage).
        assertEquals(activite.getString(R.string.settings_cluster_consumption), recu!!.name)
        assertEquals(RuleSignal.PROFILE.name, ClusterModuleDefs.layout(recu!!).slots!![0]?.info)
        // L'aperçu montre le module réglé.
        val champ = ClusterGaugeView::class.java.getDeclaredField("contenu").also { it.isAccessible = true }
        val vue = tous(fenetre.findViewById(R.id.frame_cluster_layout_preview)).first { it is ClusterGaugeView }
        assertEquals(ClusterModule.CONSUMPTION, (champ.get(vue) as ClusterGaugeView.Contenu?)?.module)
    }
}
