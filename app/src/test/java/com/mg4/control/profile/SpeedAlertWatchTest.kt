package com.mg4.control.profile

import com.mg4.control.profile.SpeedAlertWatch.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedAlertWatchTest {

    @Test fun rienNeChange() {
        val s = Snapshot(overspeed = false, tone = false, tsr = true)
        assertEquals(emptyList<String>(), SpeedAlertWatch.changes(s, s.copy()))
    }

    @Test fun survitesseRallumee() {
        val avant = Snapshot(overspeed = false, tone = false, tsr = true)
        assertEquals(listOf("survitesse OFF → ON"),
            SpeedAlertWatch.changes(avant, avant.copy(overspeed = true)))
    }

    @Test fun plusieursChangementsEtIllisible() {
        val avant = Snapshot(overspeed = true, tone = false, tsr = true)
        val apres = Snapshot(overspeed = true, tone = null, tsr = false)
        assertEquals(listOf("changement de limitation OFF → illisible", "TSR ON → OFF"),
            SpeedAlertWatch.changes(avant, apres))
    }
}
