package com.mg4.control.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Journal AllGo du guidage (issue #285) : quelles lignes du journal Android sont recopiées. */
class AllGoNavLogTest {

    @Test
    fun `AllGo navigation sentences are kept without the date`() {
        assertEquals("10:21:27.244 E/AAP_IF  ( 1234): Navigation feature is not subscribed by application",
            AllGoNavLog.garder("11-28 10:21:27.244 E/AAP_IF  ( 1234): Navigation feature is not subscribed by application"))
        assertEquals("10:22:00.001 W/GAL( 99): navigationNextTurnCallback has been deprecated",
            AllGoNavLog.garder("11-28 10:22:00.001 W/GAL( 99): navigationNextTurnCallback has been deprecated"))
        assertEquals("10:22:01.000 D/RemoteUI( 5): onAAPNavigationStatus: Tournez navigationStatus: 1",
            AllGoNavLog.garder("11-28 10:22:01.000 D/RemoteUI( 5): onAAPNavigationStatus: Tournez navigationStatus: 1"))
        assertEquals("10:22:02.000 I/AAP( 7): Navigation status subscription succeeded",
            AllGoNavLog.garder("11-28 10:22:02.000 I/AAP( 7): Navigation status subscription succeeded"))
    }

    @Test
    fun `our own lines and unrelated lines are dropped`() {
        assertNull(AllGoNavLog.garder("11-28 10:22:03.000 I/MG4_NAV( 42): AllGo » Navigation status callback called. Status 1"))
        assertNull(AllGoNavLog.garder("11-28 10:22:04.000 D/SystemUI( 8): NavigationBarView updated"))
        assertNull(AllGoNavLog.garder("11-28 10:22:05.000 I/ActivityManager( 9): Start proc com.allgo.app.androidauto"))
    }
}
