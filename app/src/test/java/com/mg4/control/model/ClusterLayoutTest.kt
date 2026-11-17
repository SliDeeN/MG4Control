package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Disposition de l'écran conducteur (issue #229) : places, glisser-déposer, disposition d'avant, chiffres. */
class ClusterLayoutTest {

    private val moyenne = ClusterBlock(ClusterInfo.TRIP_AVERAGE, ClusterInfo.STYLE_LABEL)
    private val batterie = ClusterBlock(RuleSignal.SOC.name, ClusterInfo.STYLE_VALUE)
    private val profil = ClusterBlock(RuleSignal.PROFILE.name, ClusterInfo.STYLE_PILL)

    private fun avec(vararg s: ClusterBlock?, jauge: String = ClusterLayouts.GAUGE_MIDDLE) =
        ClusterLayout(gauge = jauge, slots = s.toList())

    @Test
    fun `gauge in the middle keeps Info 1-2 above and 3-4 below`() {
        val g = ClusterLayouts.geometry(avec(profil, null, moyenne, batterie))
        assertEquals(40f, g.gauge.y, 0f)
        assertEquals(180f, g.gauge.h, 0f)
        assertEquals(0f, g.cells.first { it.slot == 0 }.box.y, 0f)
        assertEquals(220f, g.cells.first { it.slot == 3 }.box.y, 0f)
        assertTrue(g.cells.first { it.slot == 3 }.box.x > 130f)
    }

    @Test
    fun `an empty row disappears on the car and stays while editing`() {
        val l = avec(null, null, moyenne, null)
        val surLaVoiture = ClusterLayouts.geometry(l)
        assertEquals(0f, surLaVoiture.gauge.y, 0f)
        assertEquals(220f, surLaVoiture.gauge.h, 0f)
        assertEquals(listOf(2, 3), surLaVoiture.cells.map { it.slot })
        val enEdition = ClusterLayouts.geometry(l, showEmpty = true)
        assertEquals(4, enEdition.cells.size)
        assertEquals(180f, enEdition.gauge.h, 0f)
        // Aucune information : la jauge a tout le carré.
        assertEquals(260f, ClusterLayouts.geometry(ClusterLayout()).gauge.h, 0f)
    }

    @Test
    fun `gauge on top puts both rows below, at the bottom both rows above`() {
        val haut = ClusterLayouts.geometry(avec(profil, null, moyenne, null, jauge = ClusterLayouts.GAUGE_TOP))
        assertEquals(0f, haut.gauge.y, 0f)
        assertEquals(180f, haut.cells.first { it.slot == 0 }.box.y, 0f)
        assertEquals(220f, haut.cells.first { it.slot == 2 }.box.y, 0f)
        val bas = ClusterLayouts.geometry(avec(profil, null, moyenne, null, jauge = ClusterLayouts.GAUGE_BOTTOM))
        assertEquals(80f, bas.gauge.y, 0f)
        assertEquals(0f, bas.cells.first { it.slot == 0 }.box.y, 0f)
        assertEquals(40f, bas.cells.first { it.slot == 2 }.box.y, 0f)
        assertEquals(ClusterLayouts.GAUGE_TOP, ClusterLayouts.gaugeDrop(40f))
        assertEquals(ClusterLayouts.GAUGE_MIDDLE, ClusterLayouts.gaugeDrop(130f))
        assertEquals(ClusterLayouts.GAUGE_BOTTOM, ClusterLayouts.gaugeDrop(240f))
    }

    @Test
    fun `a wide block takes its whole row only when the neighbour is free`() {
        val large = avec(null, null, moyenne.copy(wide = true), null)
        val cells = ClusterLayouts.geometry(large).cells
        assertEquals(1, cells.size)
        assertEquals(252f, cells[0].box.w, 0f)
        // Voisine occupée : la largeur retombe.
        assertFalse(ClusterLayouts.isWide(avec(null, null, moyenne.copy(wide = true), batterie), 2))
        // Une case de droite n'est jamais « toute la largeur ».
        assertFalse(ClusterLayouts.isWide(avec(null, moyenne.copy(wide = true)), 1))
    }

    @Test
    fun `drag and drop swaps two slots and drops an impossible full width`() {
        val l = avec(profil, null, moyenne.copy(wide = true), null)
        val echange = ClusterLayouts.swap(l, 0, 3)
        val s = ClusterLayouts.slots(echange)
        assertNull(s[0])
        assertEquals(RuleSignal.PROFILE.name, s[3]?.info)
        // La moyenne garde sa largeur… tant que sa voisine ne se remplit pas.
        assertNull(s[2]?.wide)
        assertEquals(moyenne.info, s[2]?.info)
        val vers1 = ClusterLayouts.swap(l, 2, 1)
        assertNull(ClusterLayouts.slots(vers1)[1]?.wide)
        assertEquals(l, ClusterLayouts.swap(l, 2, 2))
    }

    @Test
    fun `without a saved layout the driver screen looks as before`() {
        val d = ClusterLayouts.defaults(ClusterConfig(pill = "mode", average = "km_kwh"))
        val s = ClusterLayouts.slots(d)
        assertEquals(RuleSignal.DRIVE_MODE.name, s[0]?.info)
        assertEquals(ClusterInfo.STYLE_PILL, s[0]?.style)
        assertEquals(ClusterInfo.TRIP_AVERAGE, s[2]?.info)
        assertEquals("km_kwh", s[2]?.unit)
        assertEquals(ClusterInfo.TRIP_REGEN, s[3]?.info)
        assertNull(ClusterLayouts.slots(ClusterLayouts.defaults(ClusterConfig(pill = "none")))[0])
        // Profil par défaut, comme la pastille d'avant.
        assertEquals(RuleSignal.PROFILE.name, ClusterLayouts.slots(ClusterLayouts.defaults(ClusterConfig()))[0]?.info)
        val enregistree = ClusterLayout(gauge = ClusterLayouts.GAUGE_TOP)
        assertEquals(enregistree, ClusterLayouts.of(ClusterConfig(layout = enregistree)))
    }

    @Test
    fun `gauge look keeps the old behaviour by default`() {
        val l = ClusterLayout()
        assertTrue(ClusterLayouts.averageCursor(l, ClusterGaugeStyle.DISCREET))
        assertFalse(ClusterLayouts.averageCursor(l, ClusterGaugeStyle.DYNAMIC))
        assertTrue(ClusterLayouts.averageCursor(l.copy(averageCursor = true), ClusterGaugeStyle.DYNAMIC))
        assertTrue(ClusterLayouts.powerConsumption(l))
        assertEquals(1f, ClusterLayouts.thicknessFactor(l), 0f)
        assertTrue(ClusterLayouts.thicknessFactor(l.copy(thickness = ClusterLayouts.THICK)) > 1f)
    }

    @Test
    fun `only profile and drive mode offer the pill, and units belong to each info`() {
        assertEquals(ClusterInfo.STYLE_PILL, ClusterInfo.style(ClusterBlock(RuleSignal.PROFILE.name)))
        assertEquals(ClusterInfo.STYLE_LABEL, ClusterInfo.style(ClusterBlock(RuleSignal.SOC.name, ClusterInfo.STYLE_PILL)))
        assertEquals(listOf(ClusterInfo.UNIT_KW, ClusterInfo.UNIT_HP), ClusterInfo.units(ClusterInfo.POWER))
        assertTrue(ClusterInfo.hasDistance(RuleSignal.RANGE.name))
        assertFalse(ClusterInfo.hasDistance(RuleSignal.SOC.name))
        assertEquals(ClusterInfo.CLUSTER, ClusterInfo.choices().take(ClusterInfo.CLUSTER.size))
        assertTrue(BubbleInfo.TIME in ClusterInfo.choices())
        // Retirées de l'écran conducteur (2026-11-12), gardées par les bulles.
        for (s in listOf(RuleSignal.PROJECTION, RuleSignal.MEDIA_PLAYING, RuleSignal.IN_CALL)) {
            assertFalse(s.name in ClusterInfo.choices())
            assertTrue(s.name in BubbleInfo.choices())
        }
        // Le volant chauffant reste proposé là où la voiture le lit.
        assertTrue(RuleSignal.STEERING_HEAT.name in ClusterInfo.choices())
        assertFalse(RuleSignal.STEERING_HEAT.name in ClusterInfo.choices { it != RuleSignal.STEERING_HEAT })
    }

    @Test
    fun `the gauge can count in horsepower, with round marks`() {
        assertFalse(ClusterLayouts.horsepower(ClusterLayout()))
        assertTrue(ClusterLayouts.horsepower(ClusterLayout(powerUnit = ClusterInfo.UNIT_HP)))
        // En kW, rien ne change.
        assertEquals(ClusterGauge.powerTicks(320f).map { Math.round(it) }, ClusterGauge.powerTickLabels(320f).map { it.second })
        // En chevaux : des chiffres ronds, placés à leur puissance en kW, puis la fin de l'échelle.
        val ch = ClusterGauge.powerTickLabels(320f, hp = true)
        assertEquals(listOf(25, 50, 100, 200, 300, 435), ch.map { it.second })
        assertEquals(100f / 1.36f, ch[2].first, 0.01f)
        assertEquals(320f, ch.last().first, 0f)
        assertEquals(listOf(15, 40, 80), ClusterGauge.regenTickLabels(hp = true).map { it.second })
        assertEquals(68f, ClusterGauge.inPowerUnit(50f, true), 0.01f)
        val barre = ClusterGauge.discreetPowerTickLabels(320f, hp = true)
        assertEquals(listOf(-80, -25, 0), barre.take(3).map { it.second })
        assertEquals(435, barre.last().second)
        assertEquals("50 ch trop près de 25 : trait seul", listOf(-80, -25, 0, 25, 100, 200, 435), barre.mapNotNull { it.second })
        // En kW, les mêmes chiffres qu'avant.
        assertEquals(ClusterGauge.discreetPowerLabels(320f).map { Math.round(it) }.sorted(),
            ClusterGauge.discreetPowerTickLabels(320f).mapNotNull { it.second }.sorted())
    }

    @Test
    fun `trip values are written in each block's own unit`() {
        val fr = Locale.FRANCE
        val t = ClusterInfo.Trip(averageWhPerKm = 164f, distanceKm = 42.3f, consumedKwh = 6.94f, regenWh = 1210f, powerKw = 50f)
        val km = DistanceUnit.KM
        assertEquals("16,4" to "kWh/100 km", ClusterInfo.tripValue(ClusterBlock(ClusterInfo.TRIP_AVERAGE, unit = "kwh_100km"), t, km, fr))
        assertEquals("26,3" to "mi", ClusterInfo.tripValue(ClusterBlock(ClusterInfo.TRIP_DISTANCE, distance = "mi"), t, km, fr))
        assertEquals("6,94" to "kWh", ClusterInfo.tripValue(ClusterBlock(ClusterInfo.TRIP_ENERGY), t, km, fr))
        assertEquals("+1210" to "Wh", ClusterInfo.tripValue(ClusterBlock(ClusterInfo.TRIP_REGEN, unit = "wh"), t, km, fr))
        assertEquals("68" to ClusterInfo.UNIT_HP, ClusterInfo.tripValue(ClusterBlock(ClusterInfo.POWER, unit = "hp"), t, km, fr))
        // Rien de mesuré : un tiret, jamais un zéro inventé.
        val vide = ClusterInfo.Trip(null, null, null, null, null)
        assertEquals(BubbleInfo.UNKNOWN to null, ClusterInfo.tripValue(ClusterBlock(ClusterInfo.TRIP_AVERAGE), vide, km, fr))
        assertNull(ClusterInfo.tripValue(ClusterBlock(RuleSignal.SOC.name), t, km, fr))
    }
}
