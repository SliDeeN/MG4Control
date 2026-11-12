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
