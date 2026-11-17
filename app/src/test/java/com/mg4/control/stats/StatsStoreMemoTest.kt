package com.mg4.control.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.ChargeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Historique gardé en mémoire (2026-11-13) : relu une fois, puis tenu à jour par chaque écriture —
 * y compris par une autre instance du magasin (le service écrit, l'écran lit).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StatsStoreMemoTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun charge(debut: Long) = ChargeSession(startMs = debut, endMs = debut + 3_600_000L, type = null,
        socStart = 40f, socEnd = 80f, energyKwh = 24f, measuredPowerKw = null, outsideTempC = null)

    @Before fun vider() = StatsStore(ctx).clear()

    @Test
    fun `writes from another instance are seen, reads reuse the same history`() {
        val ecran = StatsStore(ctx)
        val service = StatsStore(ctx)
        assertEquals(0, ecran.history().charges.size)
        val maintenant = System.currentTimeMillis()
        service.addCharge(charge(maintenant - 7_200_000L))
        assertEquals(1, ecran.history().charges.size)
        assertSame("pas de relecture tant que rien ne change", ecran.history(), ecran.history())
        service.addCharge(charge(maintenant - 3_600_000L))
        assertEquals(2, ecran.history().charges.size)
        service.overrideTariff(maintenant - 3_600_000L, 0.3f)
        assertEquals(0.3f, ecran.history().charges.last().tariffOverride!!, 0.0001f)
        service.clear()
        assertEquals(0, ecran.history().charges.size)
    }
}
