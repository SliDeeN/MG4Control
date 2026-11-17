package com.mg4.control.profile

import com.mg4.control.profile.OnePedalGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** One Pedal relu au passage en READY (discussion #219) : quand réécrire. */
class OnePedalGuardTest {

    @Test
    fun `rewrites when off or unreadable, leaves it when on, gives up after the window`() {
        assertEquals(Decision.OK, OnePedalGuard.decide(on = true, ageMs = 3_000))
        assertEquals(Decision.REWRITE, OnePedalGuard.decide(on = false, ageMs = 3_000))
        // Illisible : réécrire One Pedal déjà actif ne change rien.
        assertEquals(Decision.REWRITE, OnePedalGuard.decide(on = null, ageMs = 3_000))
        // Allumage par le pré-chauffage, conducteur arrivé 20 min plus tard : encore vérifié.
        assertEquals(Decision.REWRITE, OnePedalGuard.decide(on = false, ageMs = 20 * 60_000L))
        assertEquals(Decision.EXPIRED, OnePedalGuard.decide(on = false, ageMs = OnePedalGuard.WINDOW_MS + 1))
    }
}
