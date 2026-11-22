package com.mg4.control.ui.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.model.ClusterBlock
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.RuleSignal
import com.mg4.control.ui.AndroidRuleWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Couleur de la valeur d'une case (2026-11-22) : choisie, sinon blanc ou la couleur de la récupération. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterValueColorTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val rouge = 0xFFF43F5E.toInt()
    private val vert = 0xFF22C55E.toInt()

    private fun blocs(slots: List<ClusterBlock?>, soc: Any? = 64.0) = ClusterBlocks.build(ctx, ClusterLayout(slots = slots),
        ClusterBlocks.Lecture(ClusterInfo.Trip(150f, 12f, 2f, 300f, 10f), 50f, null, null, null,
            mapOf(RuleSignal.SOC.name to soc)), DistanceUnit.KM, AndroidRuleWords(ctx), Locale.FRENCH)

    @Test fun `the chosen colour replaces white and the regen colour`() {
        val b = blocs(listOf(ClusterBlock(RuleSignal.SOC.name), ClusterBlock(ClusterInfo.TRIP_REGEN),
            ClusterBlock(RuleSignal.SOC.name, color = rouge), ClusterBlock(ClusterInfo.TRIP_REGEN, color = vert)))
        assertNull("blanc par défaut", b[0]!!.couleur)
        assertEquals(ctx.getColor(R.color.cluster_accent), b[1]!!.couleur)
        assertEquals(rouge, b[2]!!.couleur)
        assertEquals(vert, b[3]!!.couleur)
    }

    @Test fun `no colour on a missing value`() {
        val b = blocs(listOf(ClusterBlock(RuleSignal.SOC.name, color = rouge)), soc = null)
        assertNull(b[0]!!.couleur)
    }
}
