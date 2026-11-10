package com.mg4.control.util

import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.RuleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unité de distance générale (2026-11-10) : reprise du choix du combiné, conversions. */
class DistanceSettingTest {

    @Test
    fun `the driver-screen choice is kept, otherwise Auto`() {
        // Déjà réglé : on n'y touche plus.
        assertEquals("km", DistanceSetting.migrated("km", listOf("mi")))
        // Choix du profil montré, puis des réglages communs.
        assertEquals("mi", DistanceSetting.migrated(null, listOf("mi", "km")))
        assertEquals("km", DistanceSetting.migrated(null, listOf(null, "km")))
        assertEquals("mi", DistanceSetting.migrated(null, listOf(DistanceUnit.AUTO, "mi")))
        // Rien ou Auto partout : Auto par défaut.
        assertEquals(DistanceUnit.AUTO, DistanceSetting.migrated(null, listOf(null, DistanceUnit.AUTO)))
        assertEquals(DistanceUnit.AUTO, DistanceSetting.migrated(null, emptyList()))
    }

    @Test
    fun `values convert to miles and stay as they are in km`() {
        val mi = DistanceUnit.MILES
        assertEquals(62.14f, mi.fromKm(100f), 0.01f)
        assertEquals(32.19f, mi.per100FromKm(20f), 0.01f)
        assertEquals(31.07f, mi.speedFromKmh(50f), 0.01f)
        assertEquals("mph", mi.speedShort)
        val km = DistanceUnit.KM
        assertEquals(100f, km.fromKm(100f), 0f)
        assertEquals(20f, km.per100FromKm(20f), 0f)
        assertEquals("km/h", km.speedShort)
    }

    @Test
    fun `information bubbles show speed and range in the chosen unit`() {
        assertEquals(31 to " mph", BubbleInfo.inDistanceUnit(RuleSignal.SPEED, 50.0, DistanceUnit.MILES))
        assertEquals(249 to " mi", BubbleInfo.inDistanceUnit(RuleSignal.RANGE, 400, DistanceUnit.MILES))
        // En km, la valeur lue passe telle quelle.
        assertEquals(50.0 to " km/h", BubbleInfo.inDistanceUnit(RuleSignal.SPEED, 50.0, DistanceUnit.KM))
        // Illisible : rien à convertir, l'unité suit quand même.
        assertEquals(null to " mi", BubbleInfo.inDistanceUnit(RuleSignal.RANGE, null, DistanceUnit.MILES))
        // Les autres informations ne sont pas des distances.
        assertNull(BubbleInfo.inDistanceUnit(RuleSignal.SOC, 64.0, DistanceUnit.MILES))
    }
}
