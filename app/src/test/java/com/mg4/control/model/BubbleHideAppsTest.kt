package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boutons flottants masqués dans des applications choisies (issue #261). */
class BubbleHideAppsTest {

    private val choisies = setOf("com.allgo.app.androidauto", "com.waze")

    @Test fun `bubbles hide only while a chosen app is in front`() {
        assertTrue(BubbleHideApps.masquer(true, choisies, "com.waze"))
        assertFalse(BubbleHideApps.masquer(true, choisies, "com.spotify.music"))
        assertFalse(BubbleHideApps.masquer(true, choisies, null))
        // Option coupée : la liste ne compte plus.
        assertFalse(BubbleHideApps.masquer(false, choisies, "com.waze"))
    }

    @Test fun `nothing to watch without the option or a list`() {
        assertTrue(BubbleHideApps.surveiller(true, choisies))
        assertFalse(BubbleHideApps.surveiller(true, emptySet()))
        assertFalse(BubbleHideApps.surveiller(false, choisies))
    }

    @Test fun `projections first, then installed apps by name, without MG4Control`() {
        val liste = BubbleHideApps.candidats(
            projections = listOf("com.allgo.app.androidauto" to "Android Auto", "com.allgo.carplay.service" to "CarPlay"),
            lancables = listOf("com.waze" to "Waze", "com.mg4.control" to "MG4Control", "com.abrp" to "ABRP",
                "com.waze" to "Waze", "com.allgo.app.androidauto" to "AA"),
            soi = "com.mg4.control")
        assertEquals(listOf("Android Auto", "CarPlay", "ABRP", "Waze"), liste.map { it.second })
    }
}
