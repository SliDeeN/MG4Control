package com.mg4.control.model

import com.mg4.control.model.WindowCommand.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position estimée d'une vitre sans capteur, à partir des durées de course calibrées.
 *
 * L'estimation ne voit que les commandes de l'app : elle part « inconnue » et ne devient connue
 * qu'après une course complète (butée atteinte quel que soit le point de départ).
 */
class WindowEstimatorTest {

    private val cal = WindowCalibration(downMs = 4_000, upMs = 5_000)

    private fun closed() = WindowEstimator(cal).apply { setClosed() }

    @Test
    fun `position inconnue au depart`() {
        assertNull(WindowEstimator(cal).current(0))
    }

    @Test
    fun `descente partielle depuis une position inconnue reste inconnue`() {
        val e = WindowEstimator(cal)
        e.start(Direction.DOWN, 0)
        e.stop(3_999)
        assertNull(e.current(3_999))
    }

    @Test
    fun `descente complete depuis une position inconnue = ouverte`() {
        val e = WindowEstimator(cal)
        e.start(Direction.DOWN, 0)
        e.stop(4_000)
        assertEquals(100f, e.current(4_000))
    }

    @Test
    fun `montee complete depuis une position inconnue = fermee`() {
        val e = WindowEstimator(cal)
        e.start(Direction.UP, 0)
        e.stop(5_000)
        assertEquals(0f, e.current(5_000))
    }

    @Test
    fun `descente d'un quart de course depuis fermee = 25 pourcents`() {
        val e = closed()
        e.start(Direction.DOWN, 1_000)
        e.stop(2_000)
        assertEquals(25f, e.current(2_000)!!, 0.01f)
    }

    @Test
    fun `la montee utilise sa propre duree`() {
        val e = WindowEstimator(cal).apply { setOpen() }
        e.start(Direction.UP, 0)
        e.stop(2_500)   // moitié de la montée (5 s), pas de la descente
        assertEquals(50f, e.current(2_500)!!, 0.01f)
    }

    @Test
    fun `position bornee entre 0 et 100`() {
        val e = closed()
        e.start(Direction.UP, 0)
        e.stop(10_000)
        assertEquals(0f, e.current(10_000))
        e.start(Direction.DOWN, 10_000)
        e.stop(30_000)
        assertEquals(100f, e.current(30_000))
    }

    @Test
    fun `position en direct pendant le mouvement`() {
        val e = closed()
        e.start(Direction.DOWN, 0)
        assertEquals(50f, e.current(2_000)!!, 0.01f)
    }

    @Test
    fun `commande repetee dans le meme sens ne relance pas le chrono`() {
        // Le maintien renvoie la commande toutes les 120 ms : seul le premier envoi compte.
        val e = closed()
        e.start(Direction.DOWN, 0)
        e.start(Direction.DOWN, 1_000)
        e.start(Direction.DOWN, 1_900)
        e.stop(2_000)
        assertEquals(50f, e.current(2_000)!!, 0.01f)
    }

    @Test
    fun `changement de sens applique d'abord le premier mouvement`() {
        val e = closed()
        e.start(Direction.DOWN, 0)
        e.start(Direction.UP, 2_000)     // 50 % ouverte à cet instant
        e.stop(3_000)                    // 1 s de montée = 20 %
        assertEquals(30f, e.current(3_000)!!, 0.01f)
    }

    @Test
    fun `stop sans mouvement ne change rien`() {
        val e = closed()
        e.stop(5_000)
        assertEquals(0f, e.current(5_000))
    }

    @Test
    fun `reset rend la position inconnue`() {
        val e = closed()
        e.reset()
        assertNull(e.current(0))
    }

    @Test
    fun `mesures de calibration acceptees entre 0,8 et 15 s`() {
        assertFalse(WindowCalibration.isValidMeasure(799))
        assertTrue(WindowCalibration.isValidMeasure(800))
        assertTrue(WindowCalibration.isValidMeasure(15_000))
        assertFalse(WindowCalibration.isValidMeasure(15_001))
    }

    @Test
    fun `courses emulees = course mesuree plus la marge`() {
        assertEquals(4_000 + WindowCalibration.COURSE_MARGIN_MS, cal.emulatedOpenMs)
        assertEquals(5_000 + WindowCalibration.COURSE_MARGIN_MS, cal.emulatedCloseMs)
    }

    @Test
    fun `fermeture emulee complete depuis ouverte = fermee`() {
        // La course émulée dure la montée + la marge : l'estimation finit recalée à 0.
        val e = WindowEstimator(cal).apply { setOpen() }
        e.start(Direction.UP, 0)
        e.stop(cal.emulatedCloseMs)
        assertEquals(0f, e.current(cal.emulatedCloseMs))
    }
}
