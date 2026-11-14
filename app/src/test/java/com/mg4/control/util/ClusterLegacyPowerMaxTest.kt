package com.mg4.control.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sans aucun profil (2026-11-14) : la puissance maxi choisie s'enregistre, se relit, et revient à Auto. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClusterLegacyPowerMaxTest {

    @Test
    fun `a fixed power max is saved, read back, then back to Auto`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val avant = ClusterModuleSettings.legacy(ctx)
        ClusterModuleSettings.saveLegacy(ctx, avant.copy(powerMax = 180))
        assertEquals(180, ClusterModuleSettings.legacy(ctx).powerMax)
        assertEquals(180, ClusterModuleSettings.powerMaxChoice(ClusterModuleSettings.legacy(ctx)))
        ClusterModuleSettings.saveLegacy(ctx, ClusterModuleSettings.legacy(ctx).copy(powerMax = null))
        assertNull("Auto", ClusterModuleSettings.legacy(ctx).powerMax)
    }
}
