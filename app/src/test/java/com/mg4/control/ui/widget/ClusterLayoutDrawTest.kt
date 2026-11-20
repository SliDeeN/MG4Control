package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.ClusterBlock
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.DriveMode
import com.mg4.control.model.PillContent
import com.mg4.control.model.RuleSignal
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Disposition de l'écran conducteur (issue #229) : la jauge en haut, au milieu, en bas, les cases
 * dans chaque style, l'allure réglée et l'édition se dessinent. Les images vont dans
 * `build/cluster229/`, pour les regarder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterLayoutDrawTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dossier = File("build/cluster229").also { it.mkdirs() }

    private val blocs = listOf(
        ClusterGaugeView.Bloc("PAUL", null, "Profil", R.drawable.ic_cat_star, PillContent("PAUL", DriveMode.ECO), ClusterInfo.STYLE_PILL, "M"),
        ClusterGaugeView.Bloc("64", "%", "Batterie", R.drawable.ic_cat_battery, null, ClusterInfo.STYLE_ICON, "M"),
        ClusterGaugeView.Bloc("16,4", "kWh/100 km", "Moyenne trajet", R.drawable.ic_info_chart, null, ClusterInfo.STYLE_LABEL, "M"),
        ClusterGaugeView.Bloc("+1210", "Wh", "Récup.", R.drawable.ic_info_regen, null, ClusterInfo.STYLE_LABEL, "L", couleur = 0xFF378ADD.toInt()),
    )
    private val disposition = ClusterLayout(slots = listOf(
        ClusterBlock(RuleSignal.PROFILE.name, ClusterInfo.STYLE_PILL), ClusterBlock(RuleSignal.SOC.name, ClusterInfo.STYLE_ICON),
        ClusterBlock(ClusterInfo.TRIP_AVERAGE), ClusterBlock(ClusterInfo.TRIP_REGEN),
    ))

    private fun dessiner(nom: String, c: ClusterGaugeView.Contenu, edition: ClusterGaugeView.Edition? = null): Bitmap {
        val vue = ClusterGaugeView(ctx)
        vue.edition = edition
        val spec = View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY)
        vue.measure(spec, spec)
        vue.layout(0, 0, 520, 520)
        vue.montrer(c, 0L)
        val image = Bitmap.createBitmap(520, 520, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(image))
        File(dossier, "$nom.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    private fun contenu(module: ClusterModule, style: ClusterGaugeStyle, d: ClusterLayout, b: List<ClusterGaugeView.Bloc?> = blocs) =
        ClusterGaugeView.Contenu(module = module, kw = 32f, kmh = 88f, pastille = null, moyenneWhKm = 164f, recupWh = 1210f,
            style = style, disposition = d, blocs = b)

    @Test
    fun `every gauge position, gauge and style is drawn`() {
        for (pos in listOf(ClusterLayouts.GAUGE_TOP, ClusterLayouts.GAUGE_MIDDLE, ClusterLayouts.GAUGE_BOTTOM))
            for (module in ClusterModule.entries) for (style in ClusterGaugeStyle.entries) {
                dessiner("${pos}_${module.key}_${style.key}", contenu(module, style, disposition.copy(gauge = pos)))
            }
    }

    /** Module « Pneus » (variante A, 2026-11-20) : valeurs, alerte en orange, sans relevé ; cases gardées. */
    @Test
    fun `the tyre module is drawn in place of the gauge`() {
        fun pneus(codes: List<Int?>, kpa: List<Int?> = listOf(252, 248, 248, 256)) =
            com.mg4.control.model.TyrePressure.display(kpa, codes, com.mg4.control.model.TyrePressure.PressureUnit.BAR, java.util.Locale.FRANCE,
                listOf(16, 16, 18, null))
        fun c(p: com.mg4.control.model.TyrePressure.Display?, d: ClusterLayout? = disposition) =
            contenu(ClusterModule.TYRES, ClusterGaugeStyle.DYNAMIC, d ?: disposition).copy(disposition = d, pneus = p)
        val normal = dessiner("tyres_normal", c(pneus(listOf(0, 0, 0, 0))))
        val alerte = dessiner("tyres_alert", c(pneus(listOf(0, 0, 3, 0), listOf(252, 248, 198, 256))))
        val rien = dessiner("tyres_none", c(null))
        dessiner("tyres_no_layout", c(pneus(listOf(0, 0, 0, 0)), d = null))
        dessiner("tyres_top", c(pneus(listOf(6, 6, 0, 0)), disposition.copy(gauge = ClusterLayouts.GAUGE_TOP)))
        // Sans case (2026-11-20) : le schéma prend tout le carré ; une case en haut, une en bas : comme la photo.
        val seul = dessiner("tyres_full", c(pneus(listOf(0, 0, 0, 0)), ClusterLayout(slots = listOf(null, null, null, null))))
        dessiner("tyres_two_rows", c(pneus(listOf(0, 0, 0, 0)), disposition.copy(slots = listOf(
            ClusterBlock(RuleSignal.PROFILE.name, ClusterInfo.STYLE_PILL), null, ClusterBlock(ClusterInfo.TRIP_AVERAGE), null))))
        assertFalse(normal.sameAs(seul))
        assertFalse(normal.sameAs(alerte))
        assertFalse(normal.sameAs(rien))
    }

    /** Module « Graphique » (issue #217) : barres, courbe, sans historique, autre information. */
    @Test
    fun `the graph module is drawn in place of the gauge`() {
        val sim = ClusterGraphs.SIMULE
        fun c(def: com.mg4.control.model.ClusterModuleDef, bins: List<com.mg4.control.model.ClusterHistory.Bin> = sim,
              d: ClusterLayout = disposition) =
            contenu(ClusterModule.GRAPH, ClusterGaugeStyle.DYNAMIC, d)
                .copy(graphe = ClusterGraphs.build(ctx, def, bins, java.util.Locale.FRANCE))
        val base = com.mg4.control.model.ClusterModuleDef("g", "G", type = "graph", unit = "kwh100")
        val barres = dessiner("graph_bars", c(base))
        val courbe = dessiner("graph_line", c(base.copy(graphLine = true, graphAverage = false)))
        val vide = dessiner("graph_empty", c(base, emptyList()))
        dessiner("graph_full_altitude", c(base.copy(graphInfo = "altitude"), d = ClusterLayout(slots = listOf(null, null, null, null))))
        dessiner("graph_regen_20", c(base.copy(graphInfo = "regen", graphKm = 20)))
        // Historique court (2,4 km) : l'axe s'arrête à 3 km, la courbe prend toute la largeur.
        dessiner("graph_short_line", c(base.copy(graphLine = true), sim.takeLast(12)))
        dessiner("graph_short_bars", c(base, sim.takeLast(12)))
        // Saut brusque puis chute : courbe arrondie, sans pointe au-delà du max ni du min.
        val saut = List(60) { n -> val whKm = when (n) { in 20..24 -> 460f; in 25..29 -> 120f; else -> 150f }
            com.mg4.control.model.ClusterHistory.Bin(0.2f, whKm * 0.2f, 0f, 10f) }
        dessiner("graph_line_20_points", c(base.copy(graphLine = true, graphPoints = 20)))
        dessiner("graph_line_jump", c(base.copy(graphLine = true, graphKm = 20), saut))
        assertFalse(barres.sameAs(courbe))
        assertFalse(barres.sameAs(vide))
        // Min et max (pointillés), et couleur du graphique hors consommation (2026-11-20).
        val sansMinMax = dessiner("graph_bars_no_minmax", c(base.copy(graphMinMax = false)))
        assertFalse(barres.sameAs(sansMinMax))
        val vitesse = dessiner("graph_speed", c(base.copy(graphInfo = "speed")))
        val vitesseVerte = dessiner("graph_speed_green", c(base.copy(graphInfo = "speed", graphColor = 0xFF3DDC84.toInt())))
        assertFalse(vitesse.sameAs(vitesseVerte))
    }

    @Test
    fun `look settings change the drawing`() {
        val base = dessiner("base", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, disposition))
        val epaisse = dessiner("epaisse_couleurs", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC,
            disposition.copy(thickness = ClusterLayouts.THICK, consumptionColor = 0xFF639922.toInt(), averageCursor = true, consumptionKw = true)))
        assertFalse(base.sameAs(epaisse))
        val sansPied = dessiner("puissance_sans_conso", contenu(ClusterModule.POWER, ClusterGaugeStyle.DYNAMIC,
            disposition.copy(powerConsumption = false)))
        assertFalse(sansPied.sameAs(dessiner("puissance", contenu(ClusterModule.POWER, ClusterGaugeStyle.DYNAMIC, disposition))))
        // Une seule rangée : la jauge prend la place.
        dessiner("une_rangee_large", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC,
            ClusterLayout(slots = listOf(null, null, ClusterBlock(ClusterInfo.TRIP_AVERAGE, wide = true), null)),
            listOf(null, null, blocs[2].copy(taille = "L"), null)))
        dessiner("vide", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, ClusterLayout(), emptyList()))
        dessiner("tailles_S", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, disposition, blocs.map { it.copy(taille = "S") }))
        // Chevaux (2026-11-12) : chiffre, graduations et unité changent, sur l'arc comme sur la barre.
        val ch = disposition.copy(powerUnit = ClusterInfo.UNIT_HP)
        for (style in ClusterGaugeStyle.entries) {
            val enCh = dessiner("puissance_ch_${style.key}", contenu(ClusterModule.POWER, style, ch))
            assertFalse(style.key, enCh.sameAs(dessiner("puissance_kw_${style.key}", contenu(ClusterModule.POWER, style, disposition))))
        }
        dessiner("conso_ch_kw_dessous", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, ch.copy(consumptionKw = true)))
    }

    @Test
    fun `editing shows empty slots, the selection and what is dragged`() {
        val partielle = disposition.copy(slots = listOf(disposition.slots!![0], null, disposition.slots!![2], null))
        val b = listOf(blocs[0], null, blocs[2], null)
        dessiner("edition_selection", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, partielle, b),
            ClusterGaugeView.Edition(selection = 2))
        dessiner("edition_glisse_bloc", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, partielle, b),
            ClusterGaugeView.Edition(selection = 0, glisse = 0, doigtX = 190f, doigtY = 232f, cible = 3))
        dessiner("edition_glisse_jauge", contenu(ClusterModule.CONSUMPTION, ClusterGaugeStyle.DYNAMIC, partielle, b),
            ClusterGaugeView.Edition(selection = ClusterGaugeView.JAUGE, glisse = ClusterGaugeView.JAUGE, doigtY = 30f,
                cibleJauge = ClusterLayouts.GAUGE_TOP))
    }
}
