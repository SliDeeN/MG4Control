package com.mg4.control.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.CampingConfig
import com.mg4.control.model.CampingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mode camping (2026-11-24) : ses réglages sont retenus pour la nuit suivante, pas son choix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CampingSettingsTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `never seen, the defaults`() {
        assertEquals(CampingConfig(), PreClimateSettings.camping(ctx))
    }

    @Test
    fun `everything set in the window comes back`() {
        val c = CampingConfig(delayS = 300, lockDoors = false, minSoc = 45, record = false, screen = CampingScreen.OFF,
            msgCamera = false, msgEcall = true, tempC = 17, fan = 3, ac = false, recircInner = false)
        val r = PreClimateSettings.lastRequest(ctx, 600.0, 21, 4).copy(camping = c)
        PreClimateSettings.saveRequest(ctx, r)
        assertEquals(c, PreClimateSettings.camping(ctx))
        // Le choix du camping n'est pas retenu : la fenêtre rouvre sans lui.
        assertNull(PreClimateSettings.lastRequest(ctx, 600.0, 21, 4).camping)
    }

    @Test
    fun `out of range values come back inside their bounds`() {
        PreClimateSettings.saveCamping(ctx, CampingConfig(delayS = 9_999, minSoc = 99))
        val c = PreClimateSettings.camping(ctx)
        assertEquals(600, c.delayS)
        assertEquals(60, c.minSoc)
    }
}
