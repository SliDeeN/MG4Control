package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Période des compteurs de trajet (2026-11-22) : READY par défaut, totaux, charge vue au SOC. */
class TripPeriodTest {

    @Test fun `ready by default and stored as null`() {
        assertEquals(TripPeriod.READY, TripPeriod.of(null))
        assertEquals(TripPeriod.READY, TripPeriod.of("inconnu"))
        assertEquals(TripPeriod.CHARGE, TripPeriod.of("charge"))
        assertNull(TripPeriod.keyOf(TripPeriod.READY))
        assertEquals("boot", TripPeriod.keyOf(TripPeriod.BOOT))
    }

    @Test fun `trip counters and drive time have a period, the rest does not`() {
        ClusterInfo.TRIP_KEYS.forEach { assertTrue(TripPeriod.applies(it)) }
        assertTrue(TripPeriod.applies(RuleSignal.DRIVE_MINUTES.name))
        assertFalse(TripPeriod.applies(RuleSignal.SOC.name))
        assertFalse(TripPeriod.applies(ClusterInfo.POWER))
    }

    @Test fun `a charge is seen when the battery gained two points`() {
        assertTrue(TripPeriod.chargedMeanwhile(41f, 80f))
        assertTrue(TripPeriod.chargedMeanwhile(41f, 43f))
        assertFalse(TripPeriod.chargedMeanwhile(41f, 42f))
        assertFalse(TripPeriod.chargedMeanwhile(null, 80f))
    }

    @Test fun `totals add up and give the net average`() {
        val t = TripTotals(3.0, 0.5, 20.0, 600_000) + TripTotals(1.0, 0.5, 5.0, 300_000)
        assertEquals(25.0, t.km, 1e-9)
        assertEquals(15.0, t.minutes, 1e-9)
        val trip = t.toTrip(null)
        assertEquals(120f, trip.averageWhPerKm!!, 0.01f)   // (4 − 1) kWh / 25 km
        assertEquals(4f, trip.consumedKwh!!, 0.001f)
        assertEquals(1000f, trip.regenWh!!, 0.01f)
        assertNull("trop court pour une moyenne", TripTotals(0.1, 0.0, 0.1, 0).toTrip(null).averageWhPerKm)
    }

    @Test fun `trip counters are offered to the bubbles once on the driver display`() {
        ClusterInfo.TRIP_KEYS.forEach { assertTrue(it in BubbleInfo.choices()) }
        val combine = ClusterInfo.choices()
        assertEquals(combine.size, combine.distinct().size)
    }
}
