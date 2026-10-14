package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D'où vient l'énergie d'un trajet (issue #117).
 *
 * Les compteurs d'énergie du véhicule ne sont alimentés que sur SWI132 et SWI133 : ailleurs ils
 * restent à zéro. Le relais est l'intégration de la puissance batterie, et « climatisation et
 * autres » vient du compteur de l'écran d'origine, qui n'avance que par kWh entier.
 */
class StatsTrackerEnergySourceTest {

    private var horloge = 1_790_000_000_000L

    private fun snap(
        odo: Int = 10_000,
        compteur: Float? = 0f,
        recup: Float? = 0f,
        integre: Float? = null,
        integreRecup: Float? = null,
        origineAux: Float? = null,
    ): EnergySnapshot {
        horloge += 10_000L
        return EnergySnapshot(
            timestampMs = horloge,
            socPercent = 80f,
            odometerKm = odo,
            energySinceStartKwh = compteur,
            climateSinceStartKwh = 0f,
            accessoriesSinceStartKwh = 0f,
            regenSinceStartKwh = recup,
            tripConsumedKwh = integre,
            tripRegenKwh = integreRecup,
            auxSinceStartKwh = origineAux,
        )
    }

    private fun trajet(events: List<StatsTracker.Event>): Trip =
        (events.first { it is StatsTracker.Event.TripEnded } as StatsTracker.Event.TripEnded).trip

    @Test
    fun `compteurs muets l energie vient de l integration`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = false))
        assertTrue(trip.energyIntegrated)
        assertEquals(1.7f, trip.energyKwh, 0.001f)
        assertEquals(0.4f, trip.regenKwh!!, 0.001f)
        assertEquals(1.3f, trip.netEnergyKwh, 0.001f)
    }

    @Test
    fun `compteurs muets les postes a zero ne sont pas presentes comme des mesures`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = false))
        assertNull(trip.climateKwh)
        assertNull(trip.accessoriesKwh)
        assertNull(trip.motorKwh)
    }

    @Test
    fun `compteurs vivants rien ne change`() {
        val t = StatsTracker(countersExpected = true)
        t.onSnapshot(snap(origineAux = 0f), ready = true)
        t.onSnapshot(snap(odo = 10_007, compteur = 1.7f, recup = 0.5f, integre = 1.69f, integreRecup = 0.43f, origineAux = 1f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, compteur = 1.7f, recup = 0.5f, integre = 1.69f, integreRecup = 0.43f), ready = false))
        assertFalse(trip.energyIntegrated)
        assertEquals(1.7f, trip.energyKwh, 0.001f)
        assertEquals(0.5f, trip.regenKwh!!, 0.001f)
        assertEquals(0f, trip.climateKwh!!, 0f)
        assertNull(trip.auxiliaryKwh)
    }

    @Test
    fun `des compteurs qui avancent l emportent meme sur un firmware repute muet`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_007, compteur = 1.7f, recup = 0.5f, integre = 1.69f, integreRecup = 0.43f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, compteur = 1.7f, recup = 0.5f, integre = 1.69f), ready = false))
        assertFalse(trip.energyIntegrated)
        assertEquals(1.7f, trip.energyKwh, 0.001f)
    }

    @Test
    fun `firmware aux compteurs fiables un petit trajet a zero reste aux compteurs`() {
        val t = StatsTracker(countersExpected = true)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_001, integre = 0.2f, integreRecup = 0f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_001, integre = 0.2f, integreRecup = 0f), ready = false))
        assertFalse(trip.energyIntegrated)
        assertEquals(0f, trip.energyKwh, 0f)
    }

    @Test
    fun `firmware aux compteurs fiables un vrai trajet reste a zero bascule sur l integration`() {
        val t = StatsTracker(countersExpected = true)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_005, integre = 0.9f, integreRecup = 0.2f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_005, integre = 0.9f, integreRecup = 0.2f), ready = false))
        assertTrue(trip.energyIntegrated)
        assertEquals(0.9f, trip.energyKwh, 0.001f)
    }

    @Test
    fun `compteurs muets et rien d integre le trajet garde son zero`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_003), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_003), ready = false))
        assertFalse(trip.energyIntegrated)
        assertEquals(0f, trip.energyKwh, 0f)
    }

    @Test
    fun `climatisation et autres par difference du compteur d origine`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(origineAux = 3f), ready = true)
        t.onSnapshot(snap(odo = 10_060, integre = 11f, integreRecup = 2f, origineAux = 5f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_060, integre = 11f, integreRecup = 2f), ready = false))
        assertEquals(2f, trip.auxiliaryKwh!!, 0f)
    }

    @Test
    fun `compteur d origine remis a zero en route sa valeur finale fait foi`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(origineAux = 3f), ready = true)
        t.onSnapshot(snap(odo = 10_060, integre = 11f, integreRecup = 2f, origineAux = 1f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_060, integre = 11f), ready = false))
        assertEquals(1f, trip.auxiliaryKwh!!, 0f)
    }

    @Test
    fun `compteur d origine illisible pas de ligne climatisation et autres`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_007, integre = 1.69f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, integre = 1.69f), ready = false))
        assertTrue(trip.energyIntegrated)
        assertNull(trip.auxiliaryKwh)
    }

    @Test
    fun `le dernier releve hors contact apporte la fin de l integration`() {
        val t = StatsTracker(countersExpected = false)
        t.onSnapshot(snap(), ready = true)
        t.onSnapshot(snap(odo = 10_006, integre = 1.5f, integreRecup = 0.3f), ready = true)
        val trip = trajet(t.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = false))
        assertEquals(1.7f, trip.energyKwh, 0.001f)
        assertEquals(0.4f, trip.regenKwh!!, 0.001f)
    }

    @Test
    fun `un trajet repris au demarrage garde son energie integree`() {
        val avant = StatsTracker(countersExpected = false)
        avant.onSnapshot(snap(), ready = true)
        avant.onSnapshot(snap(odo = 10_007, integre = 1.69f, integreRecup = 0.43f), ready = true)
        val apres = StatsTracker(countersExpected = false)
        val trip = trajet(apres.recover(avant.pendingState()))
        assertTrue(trip.energyIntegrated)
        assertEquals(1.7f, trip.energyKwh, 0.001f)
    }

    @Test
    fun `une consommation integree n est pas annoncee approchee pour le pas des compteurs`() {
        // 0,6 kWh sur 3 km : au dixième de kWh des compteurs ce serait ±17 %, donc « ≈ ». L'énergie
        // intégrée n'a pas ce pas : son incertitude est relative.
        val base = Trip(
            startMs = 0L, endMs = 600_000L, distanceKm = 3, energyKwh = 0.8f, climateKwh = null,
            accessoriesKwh = null, regenKwh = 0.2f, socStart = 80f, socEnd = 79f, outsideTempC = null,
            integratedKm = 3.0f,
        )
        assertTrue(base.consumptionApproximate)
        assertFalse(base.copy(energyIntegrated = true).consumptionApproximate)
    }
}
