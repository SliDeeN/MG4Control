package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ce qu'une session de charge a le droit d'annoncer.
 *
 * Toute la difficulté est là : une charge de nuit est **reconstituée** après coup, et ses deux dates
 * encadrent la charge sans la mesurer. Annoncer une puissance dans ces conditions serait faux d'un
 * facteur trois ou quatre — sauf si l'utilisateur donne lui-même les horaires.
 */
class ChargeSessionTest {

    private val nuit = ChargeSession(
        // Fenêtre relevée : dernier échantillon du soir, premier du matin.
        startMs = 1_600_000_000_000L,                    // 22 h 00 (repère arbitraire)
        endMs = 1_600_000_000_000L + 9 * 3_600_000L,     // 9 heures plus tard
        type = null,
        socStart = 40f,
        socEnd = 80f,
        energyKwh = 24f,
        measuredPowerKw = null,
        outsideTempC = 8f,
        reconstructed = true,
    )

    @Test
    fun `sans horaires une charge reconstituee n'annonce aucune puissance`() {
        // 24 kWh sur les neuf heures de la fenêtre donneraient 2,7 kW, alors que la borne a pu
        // charger à 7 kW pendant trois heures et demie.
        assertNull(nuit.powerKw)
    }

    @Test
    fun `les horaires saisis rendent la puissance calculable`() {
        val complete = nuit.copy(
            userStartMs = nuit.startMs,
            userEndMs = nuit.startMs + 3 * 3_600_000L + 30 * 60_000L,
        )
        assertEquals(3.5f * 3_600_000L, complete.durationMs.toFloat(), 1f)
        assertEquals("24 kWh en 3 h 30", 6.86f, complete.powerKw!!, 0.05f)
    }

    @Test
    fun `un seul horaire ne suffit pas`() {
        assertNull("il faut les deux bornes", nuit.copy(userStartMs = nuit.startMs).powerKw)
        assertNull(nuit.copy(userEndMs = nuit.endMs).powerKw)
    }

    @Test
    fun `une charge observee garde sa puissance mesuree`() {
        val vue = nuit.copy(reconstructed = false, measuredPowerKw = 9.3f)
        assertEquals(9.3f, vue.powerKw!!, 0.01f)
    }

    @Test
    fun `le type saisi decide du tarif`() {
        val reglages = StatsSettings(priceAc = 0.2f, priceDc = 0.5f)
        assertEquals(4.8f, nuit.copy(type = ChargeType.AC).cost(reglages)!!, 0.01f)
        assertEquals(12f, nuit.copy(type = ChargeType.DC).cost(reglages)!!, 0.01f)
    }

    @Test
    fun `les bornes relevees ne bougent pas, elles identifient la session`() {
        val complete = nuit.copy(userStartMs = nuit.startMs + 3_600_000L)
        assertEquals(nuit.startMs, complete.startMs)
        assertEquals(nuit.startMs + 3_600_000L, complete.displayStartMs)
    }
}
