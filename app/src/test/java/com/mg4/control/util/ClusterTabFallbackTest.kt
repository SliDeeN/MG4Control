package com.mg4.control.util

import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #245 : une lecture d'onglet manquée garde le dernier onglet lu, pas le module de la navigation partout. */
class ClusterTabFallbackTest {

    private val nav = ClusterTab.NAVIGATION.value
    private val media = ClusterTab.MEDIA.value

    @Test
    fun `a missed read keeps the last tab for a while, then falls back`() {
        assertEquals(media, ClusterModuleSettings.tabToFollow(media, nav, 0))
        assertEquals(media, ClusterModuleSettings.tabToFollow(null, media, 2_000))
        assertNull(ClusterModuleSettings.tabToFollow(null, media, ClusterModuleSettings.ONGLET_GARDE_MS + 1))
        assertNull("jamais lu", ClusterModuleSettings.tabToFollow(null, null, 0))
    }

    @Test
    fun `on the music page with only a navigation module, a missed read shows nothing`() {
        val onglet = ClusterModuleSettings.tabToFollow(null, media, 1_000)
        assertNull(ClusterModuleSettings.moduleToShow(onglet, ClusterModule.POWER, null))
        // Jamais lu : le secours d'avant reste, pour qu'un combiné illisible garde son module.
        assertEquals(ClusterModule.POWER, ClusterModuleSettings.moduleToShow(null, ClusterModule.POWER, null))
    }
}
