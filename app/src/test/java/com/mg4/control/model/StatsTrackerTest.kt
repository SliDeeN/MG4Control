package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles de découpage des trajets et des charges.
 *
 * Elles viennent toutes d'un relevé sur la voiture (SWI133, 18-19 septembre 2026) : les compteurs
 * se remettent à zéro au contact, la charge se lit sur un état, et le boîtier peut s'endormir au
 * milieu d'une nuit de charge. Une erreur ici fabrique un historique faux en silence.
 */
class StatsTrackerTest {

    private var horloge = 1_000_000L

    private fun snap(
        soc: Float? = 80f,
        odo: Int? = 10_000,
        energie: Float? = 0f,
        charge: Boolean? = false,
        type: ChargeType? = null,
        puissance: Float? = null,
        regen: Float? = null,
    ): EnergySnapshot {
        horloge += 30_000L
        return EnergySnapshot(
            timestampMs = horloge,
            socPercent = soc,
            odometerKm = odo,
            energySinceStartKwh = energie,
            charging = charge,
            chargeType = type,
            powerKw = puissance,
            regenSinceStartKwh = regen,
        )
    }

    @Test
    fun `un trajet se borne au contact et retient distance et energie`() {
        val t = StatsTracker()
        assertTrue(t.onSnapshot(snap(odo = 10_000, energie = 0f), ready = true).isEmpty())
        t.onSnapshot(snap(odo = 10_020, energie = 3.5f, regen = 0.8f), ready = true)
        val events = t.onSnapshot(snap(odo = 10_020, energie = 3.5f, soc = 74f), ready = false)

        val trip = (events.single() as StatsTracker.Event.TripEnded).trip
        assertEquals(20, trip.distanceKm)
        assertEquals(3.5f, trip.energyKwh, 0.01f)
        assertEquals(0.8f, trip.regenKwh!!, 0.01f)
    }

    @Test
    fun `mettre le contact sans rouler ne cree pas de trajet`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000, energie = 0f), ready = true)
        // Régler la climatisation à l'arrêt : ni distance, ni énergie de traction.
        assertTrue(t.onSnapshot(snap(odo = 10_000, energie = 0f), ready = false).isEmpty())
    }

    @Test
    fun `un compteur remis a zero en cours de route donne le total final`() {
        val t = StatsTracker()
        // L'application démarre alors qu'un trajet est déjà en cours : le compteur vaut déjà 9.
        t.onSnapshot(snap(odo = 10_000, energie = 9f), ready = true)
        // Puis le véhicule le remet à zéro (nouveau contact vu de l'extérieur) et remonte à 2.
        t.onSnapshot(snap(odo = 10_030, energie = 2f), ready = true)
        val trip = (t.onSnapshot(snap(odo = 10_030, energie = 2f), ready = false)
            .single() as StatsTracker.Event.TripEnded).trip
        assertEquals("valeur finale prise telle quelle", 2f, trip.energyKwh, 0.01f)
    }

    @Test
    fun `une lecture manquee du contact ne coupe pas le trajet`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000), ready = true)
        assertTrue(t.onSnapshot(snap(odo = 10_010), ready = null).isEmpty())
        assertTrue("le trajet continue", t.tripInProgress)
    }

    @Test
    fun `une charge se mesure sur la difference de pourcentage`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.onSnapshot(snap(soc = 40f, charge = true, type = ChargeType.AC, puissance = 5f), ready = false)
        t.onSnapshot(snap(soc = 50f, charge = true, type = ChargeType.AC, puissance = 5.4f), ready = false)
        val session = (t.onSnapshot(snap(soc = 60f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session

        assertEquals(ChargeType.AC, session.type)
        assertEquals(40f, session.socStart!!, 0.01f)
        assertEquals(60f, session.socEnd!!, 0.01f)
        assertEquals("20 % de 60 kWh", 12f, session.energyKwh!!, 0.01f)
        assertEquals("moyenne des relevés", 5.4f, session.measuredPowerKw!!, 0.05f)
    }

    @Test
    fun `sans releve de puissance la charge n'en invente pas`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.onSnapshot(snap(soc = 40f, charge = true, type = ChargeType.AC), ready = false)
        horloge += 3_600_000L   // une heure de charge, sans que l'écran ait rien pu relever
        val session = (t.onSnapshot(snap(soc = 70f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session

        assertNull("aucune puissance mesurée", session.measuredPowerKw)
        assertNotNull("mais l'énergie reste connue", session.energyKwh)
        // Le repli énergie ÷ durée reste disponible pour l'affichage.
        assertNotNull(session.powerKw)
    }

    @Test
    fun `un instantane vide n'ouvre ni trajet ni charge`() {
        val t = StatsTracker()
        assertTrue(t.onSnapshot(EnergySnapshot(timestampMs = 1L), ready = true).isEmpty())
        assertEquals(false, t.tripInProgress)
    }
}
