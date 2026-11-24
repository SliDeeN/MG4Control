package com.mg4.control.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.api.ExternalApi
import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.RuleCondition
import com.mg4.control.model.RuleOp
import com.mg4.control.model.RuleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sièges chauffants en mots (2026-11-24) : Arrêt / Faible / Moyen / Fort, plus 0–3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class SeatLevelWordsTest {

    private val mots = AndroidRuleWords(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `each level reads as a word`() {
        assertEquals("Arrêt", mots.seatLevel(0))
        assertEquals("Faible", mots.seatLevel(1))
        assertEquals("Moyen", mots.seatLevel(2))
        assertEquals("Fort", mots.seatLevel(3))
        assertNull(mots.seatLevel(4))
    }

    @Test
    fun `automation action and condition use the words`() {
        assertTrue(mots.set(ExternalApi.SET_SEAT_HEAT_LEFT, "2").endsWith("Moyen"))
        assertTrue(mots.set(ExternalApi.SET_SEAT_HEAT_RIGHT, "0").endsWith("Arrêt"))
        assertEquals("Faible", mots.number(RuleSignal.SEAT_HEAT_LEFT, 1.0))
        val c = RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.SEAT_HEAT_RIGHT, op = RuleOp.ABOVE, number = 1.0)
        assertTrue(com.mg4.control.model.RuleText(mots).condition(c).contains("Faible"))
    }

    @Test
    fun `bubbles and cluster slots only convert seat values`() {
        assertEquals(3, BubbleInfo.seatLevel(RuleSignal.SEAT_HEAT_LEFT, 3))
        assertEquals(2, BubbleInfo.seatLevel(RuleSignal.SEAT_HEAT_RIGHT, 2.0))
        assertNull(BubbleInfo.seatLevel(RuleSignal.SEAT_HEAT_LEFT, null))
        assertNull(BubbleInfo.seatLevel(RuleSignal.SEAT_HEAT_LEFT, 7))
        assertNull(BubbleInfo.seatLevel(RuleSignal.FAN, 2))
    }
}
