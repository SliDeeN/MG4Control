package com.mg4.control.profile

import com.mg4.control.profile.OnePedalGuard.Decision
import com.mg4.control.profile.OnePedalGuard.Watch
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

    @Test
    fun `the short watch rewrites a lost One Pedal twice at most, within 30 s (#277)`() {
        assertEquals(Watch.CONTINUE, OnePedalGuard.watch(on = true, ageMs = 1_000, rewrites = 0))
        // Illisible : on attend la relecture suivante plutôt que d'écrire chaque seconde.
        assertEquals(Watch.CONTINUE, OnePedalGuard.watch(on = null, ageMs = 1_000, rewrites = 0))
        assertEquals(Watch.REWRITE, OnePedalGuard.watch(on = false, ageMs = 1_000, rewrites = 0))
        assertEquals(Watch.REWRITE, OnePedalGuard.watch(on = false, ageMs = 5_000, rewrites = 1))
        // Coupé encore après deux réécritures : peut-être le conducteur, on le laisse.
        assertEquals(Watch.GIVE_UP, OnePedalGuard.watch(on = false, ageMs = 8_000, rewrites = 2))
        assertEquals(Watch.END, OnePedalGuard.watch(on = false, ageMs = OnePedalGuard.WATCH_MS + 1, rewrites = 0))
    }
}
