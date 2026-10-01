package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Courbe « lumière extérieure → luminosité » : quatre points, interpolés en log de l'éclairement. */
class BrightnessCurveTest {

    private val courbe = BrightnessCurve.DEFAULT   // 15 / 35 / 70 / 100

    @Test
    fun `chaque point de reference rend sa valeur`() {
        assertEquals(15, courbe.percentFor(BrightnessCurve.LUX_NIGHT))
        assertEquals(35, courbe.percentFor(BrightnessCurve.LUX_TWILIGHT))
        assertEquals(70, courbe.percentFor(BrightnessCurve.LUX_OVERCAST))
        assertEquals(100, courbe.percentFor(BrightnessCurve.LUX_SUNNY))
    }

    @Test
    fun `au dela des points extremes la valeur reste celle du point`() {
        assertEquals(15, courbe.percentFor(0.01))
        assertEquals(15, courbe.percentFor(0.0))
        assertEquals(100, courbe.percentFor(150_000.0))
    }

    @Test
    fun `interpolation en log entre deux points`() {
        // 1 000 lx : log10 = 3, soit 28,5 % du chemin entre 400 lx (2,602) et 10 000 lx (4).
        assertEquals(45, courbe.percentFor(1_000.0))
    }

    @Test
    fun `une courbe non monotone reste interpolee telle quelle`() {
        val c = BrightnessCurve(night = 60, twilight = 20, overcast = 20, sunny = 20)
        assertEquals(60, c.percentFor(5.0))
        assertEquals(20, c.percentFor(5_000.0))
    }
}
