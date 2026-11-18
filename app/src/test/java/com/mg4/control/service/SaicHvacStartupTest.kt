package com.mg4.control.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SaicHvacStartupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx
        .getSharedPreferences("saic_hvac_startup_test", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `les nouvelles options sont coupees par defaut et se reglent independamment`() {
        assertFalse(SaicHvacPanel.isOn(ctx))
        assertFalse(SaicHvacPanel.isPopupOn(ctx))
        SaicHvacPanel.setOn(ctx, true)
        assertTrue(SaicHvacPanel.isOn(ctx))
        assertFalse(SaicHvacPanel.isPopupOn(ctx))
        SaicHvacPanel.setPopupOn(ctx, true)
        SaicHvacPanel.setOn(ctx, false)
        assertFalse(SaicHvacPanel.isOn(ctx))
        assertTrue(SaicHvacPanel.isPopupOn(ctx))
        SaicHvacPanel.setOn(ctx, true)
        assertTrue(SaicHvacPanel.isOn(ctx))
        assertTrue(SaicHvacPanel.isPopupOn(ctx))
    }

    @Test fun `le choix de remplacement d une ancienne version est conserve`() {
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("replace_saic_hvac_panel", true).commit()
        assertTrue(SaicHvacPanel.isOn(ctx))
        assertTrue(SaicHvacPanel.isPopupOn(ctx))
        SaicHvacPanel.setPopupOn(ctx, false)
        assertTrue(SaicHvacPanel.isOn(ctx))
        assertFalse(SaicHvacPanel.isPopupOn(ctx))
        SaicHvacPanel.setOn(ctx, false)
        assertFalse(SaicHvacPanel.isOn(ctx))
    }

    @Test fun `fermeture seule au demarrage puis conservation ou remplacement selon le second choix`() {
        val demarrage = SaicHvacPanel.Demarrage(prefs)
        assertEquals(SaicHvacPanel.Action.FERMER, demarrage.action(12, false))
        assertEquals(SaicHvacPanel.Action.FERMER, demarrage.action(12, true))
        demarrage.confirmerFermeture(12)
        assertEquals(SaicHvacPanel.Action.AUCUNE, demarrage.action(12, false))
        assertEquals(SaicHvacPanel.Action.REMPLACER, demarrage.action(12, true))
        assertEquals(SaicHvacPanel.Action.FERMER, demarrage.action(13, true))
        assertEquals(SaicHvacPanel.Action.FERMER, demarrage.action(13, false))
        assertEquals(SaicHvacPanel.Action.AUCUNE, demarrage.action(-1, false))
        assertEquals(SaicHvacPanel.Action.REMPLACER, demarrage.action(-1, true))
    }

    @Test fun `seule la premiere fermeture reussie du demarrage est silencieuse`() {
        val demarrage = SaicHvacPanel.Demarrage(prefs)
        assertTrue(demarrage.attendPremiereFermeture(12))
        // Une lecture, une liaison ou une fermeture échouée ne consomme pas la première fois.
        assertTrue(demarrage.attendPremiereFermeture(12))
        demarrage.confirmerFermeture(12)
        assertFalse(demarrage.attendPremiereFermeture(12))
    }

    @Test fun `relancer le service ou recharger les preferences ne rearme pas le meme demarrage`() {
        SaicHvacPanel.Demarrage(prefs).confirmerFermeture(12)
        val relues = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("saic_hvac_startup_test", Context.MODE_PRIVATE)
        assertFalse(SaicHvacPanel.Demarrage(relues).attendPremiereFermeture(12))
        assertTrue(SaicHvacPanel.Demarrage(relues).attendPremiereFermeture(13))
    }

    @Test fun `un compteur indisponible garde le remplacement habituel sans consommer le prochain demarrage`() {
        val demarrage = SaicHvacPanel.Demarrage(prefs)
        assertFalse(demarrage.attendPremiereFermeture(-1))
        demarrage.confirmerFermeture(-1)
        assertTrue(demarrage.attendPremiereFermeture(0))
        demarrage.confirmerFermeture(0)
        assertFalse(demarrage.attendPremiereFermeture(0))
        assertFalse(demarrage.attendPremiereFermeture(-1))
        assertFalse(demarrage.attendPremiereFermeture(0))
    }
}
