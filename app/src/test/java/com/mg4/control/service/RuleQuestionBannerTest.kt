package com.mg4.control.service

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.ConfirmAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Questions d'automatisation (2026-11-14) : chacune se termine une seule fois — remplacée par une
 * autre ou fermée parce que sa règle s'arrête, elle répond « interrompue » ; sans réponse, « délai ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-mdpi")
class RuleQuestionBannerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a replaced or closed question ends interrupted, exactly once`() {
        val a = mutableListOf<ConfirmAnswer>()
        val b = mutableListOf<ConfirmAnswer>()
        ProfileConfirmOverlay.showQuestion(ctx, "A", "Clim ?", 15) { a += it }
        idle()
        assertTrue("bandeau A bien affiché", ProfileConfirmOverlay.isShowing() && a.isEmpty())
        val idB = ProfileConfirmOverlay.showQuestion(ctx, "B", "Sièges ?", 15) { b += it }
        idle()
        assertTrue("bandeau B bien affiché", ProfileConfirmOverlay.isShowing() && b.isEmpty())
        assertEquals("A remplacée", listOf(ConfirmAnswer.INTERRUPTED), a)
        ProfileConfirmOverlay.closeQuestion(ctx, idB)
        idle()
        assertEquals("B fermée avec sa règle", listOf(ConfirmAnswer.INTERRUPTED), b)
        assertFalse("bandeau retiré", ProfileConfirmOverlay.isShowing())
        // Rien de plus ensuite, même après le délai.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(1, a.size); assertEquals(1, b.size)
    }

    @Test
    fun `no answer ends with timeout`() {
        val r = mutableListOf<ConfirmAnswer>()
        ProfileConfirmOverlay.showQuestion(ctx, "A", "Clim ?", 5) { r += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf(ConfirmAnswer.TIMEOUT), r)
    }

    @Test
    fun `a rule question replacing a profile banner declines it once, so its fallback still applies`() {
        var confirme = 0; var decline = 0
        val profil = com.mg4.control.model.DrivingProfile(name = "Hiver", driveMode = com.mg4.control.model.DriveMode.ECO,
            regenLevel = com.mg4.control.model.RegenLevel.ONE_PEDAL)
        ProfileConfirmOverlay.show(ctx, profil, 5, 3f, com.mg4.control.automation.AutomationSettings.Direction.BELOW,
            onConfirmed = { confirme++ }, onDeclined = { decline++ })
        idle()
        assertTrue("bandeau de profil bien affiché", ProfileConfirmOverlay.isShowing() && decline == 0)
        val r = mutableListOf<ConfirmAnswer>()
        ProfileConfirmOverlay.showQuestion(ctx, "A", "Clim ?", 15) { r += it }
        idle()
        assertEquals("repli du profil appliqué", 1, decline)
        assertEquals(0, confirme)
        assertTrue("la question est affichée", ProfileConfirmOverlay.isShowing() && r.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals("une seule fin", 1, decline)
    }
}
