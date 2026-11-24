package com.mg4.control.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.RuleKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #275 : l'action « Sens de l'air » se lit en sorties d'air, plus en chiffre 0–6. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class AirFlowWordsTest {

    private val mots = AndroidRuleWords(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `every car value reads as its vents`() {
        assertEquals("Visage", mots.airFlow("0"))
        assertEquals("Visage + Pieds", mots.airFlow("1"))
        assertEquals("Pieds", mots.airFlow("2"))
        assertEquals("Pieds + Pare-brise AV", mots.airFlow("3"))
        assertEquals("Pare-brise AV", mots.airFlow("4"))
        assertEquals("Visage + Pieds + Pare-brise AV", mots.airFlow("5"))
        assertEquals("Visage + Pare-brise AV", mots.airFlow("6"))
    }

    @Test
    fun `out of scale or variable values stay as they are`() {
        assertEquals("7", mots.airFlow("7"))
        assertEquals("\$sens", mots.airFlow("\$sens"))
        assertTrue(mots.set(RuleKeys.AIR_FLOW, "6").endsWith("Visage + Pare-brise AV"))
    }
}
