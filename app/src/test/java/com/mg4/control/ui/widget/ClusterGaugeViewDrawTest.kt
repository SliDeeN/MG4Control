package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DriveMode
import com.mg4.control.model.PillContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Les modules du combiné se dessinent dans tous leurs états : deux styles (dynamique, discret —
 * issue #153), deux modules, en roulant, en récupération, à l'arrêt, et avec chaque unité de la
 * moyenne. Une erreur de dessin passe la compilation et ne se verrait que sur le combiné.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Rendu réel des pixels : sans lui, Robolectric ne dessine rien et toutes les images se ressemblent.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterGaugeViewDrawTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun dessiner(c: ClusterGaugeView.Contenu): Bitmap {
        val vue = ClusterGaugeView(ctx)
        val spec = View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY)
        vue.measure(spec, spec)
        vue.layout(0, 0, 260, 260)
        vue.montrer(c, 0L)
        val image = Bitmap.createBitmap(260, 260, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(image))
        return image
    }

    @Test
    fun `chaque style, chaque module, chaque situation se dessine`() {
        var dessins = 0
        for (style in ClusterGaugeStyle.entries) for (module in ClusterModule.entries) for (moyenne in AverageUnit.entries) {
            for ((kw, kmh) in listOf(35f to 82f, -18f to 60f, 1.2f to 0f, null to null)) {
                dessiner(ClusterGaugeView.Contenu(
                    module = module, kw = kw, kmh = kmh,
                    pastille = PillContent("PAUL", DriveMode.NORMAL),
                    moyenneWhKm = 168f, recupWh = 120f,
                    unite = ConsumptionUnit.KWH_PER_100KM, uniteMoyenne = moyenne, style = style,
                ))
                dessins++
            }
        }
        assertTrue(dessins == 2 * 2 * 3 * 4)
    }

    @Test
    fun `changer d unite change le dessin, pour la jauge comme pour la moyenne`() {
        for (style in ClusterGaugeStyle.entries) for (module in ClusterModule.entries) {
            fun avec(unite: ConsumptionUnit, moyenne: AverageUnit) = dessiner(ClusterGaugeView.Contenu(
                module = module, kw = 20f, kmh = 90f, pastille = null, moyenneWhKm = 168f, recupWh = 50f,
                unite = unite, uniteMoyenne = moyenne, style = style,
            ))
            val reference = avec(ConsumptionUnit.KWH_PER_100KM, AverageUnit.KWH_PER_100KM)
            assertFalse("$style $module : moyenne", reference.sameAs(avec(ConsumptionUnit.KWH_PER_100KM, AverageUnit.KM_PER_KWH)))
            // La jauge de puissance discrète ne montre pas l'unité de la jauge : seule la moyenne compte.
            if (!(style == ClusterGaugeStyle.DISCREET && module == ClusterModule.POWER)) {
                assertFalse("$style $module : jauge", reference.sameAs(avec(ConsumptionUnit.WH_PER_KM, AverageUnit.KWH_PER_100KM)))
            }
        }
    }
}
