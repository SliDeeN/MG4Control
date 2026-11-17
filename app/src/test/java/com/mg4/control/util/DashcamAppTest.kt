package com.mg4.control.util

import com.mg4.control.model.DashcamContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surveillance sans appli dashcam (2026-11-13) : seulement si l'utilisateur l'a permise. */
class DashcamAppTest {

    private val pilotable = DashcamApp.Detection.Pilotable(
        DashcamContract.Commands("p", "p.action.", "S", "T", null, null), "Caméra")
    private val ancienne = DashcamApp.Detection.Ancienne(DashcamApp.ORIGINE, "Caméra")

    @Test
    fun `surveillance starts with a camera app, or without one only when allowed`() {
        assertTrue(DashcamApp.surveillancePossible(pilotable, sansCamera = false))
        assertTrue(DashcamApp.surveillancePossible(ancienne, sansCamera = false))
        assertFalse(DashcamApp.surveillancePossible(DashcamApp.Detection.Absente, sansCamera = false))
        assertTrue(DashcamApp.surveillancePossible(DashcamApp.Detection.Absente, sansCamera = true))
    }
}
