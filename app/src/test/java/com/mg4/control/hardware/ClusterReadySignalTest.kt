package com.mg4.control.hardware

import com.mg4.control.model.ClusterTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #245 : mêmes signaux, dans le même ordre, que l'appli SAIC du combiné (`sendFicmReady`). */
class ClusterReadySignalTest {

    @Test fun musique_navigation_puis_media() =
        assertEquals(listOf(0x21407b16, 0x21407b01), ClusterReadySignal.signauxPour(ClusterTab.MEDIA.value))

    @Test fun navigation_media_puis_navigation() =
        assertEquals(listOf(0x21407b01, 0x21407b16), ClusterReadySignal.signauxPour(ClusterTab.NAVIGATION.value))

    @Test fun autres_onglets_rien() {
        for (t in listOf(null, 0, 3, 4, 5, 6, 7, 42)) assertNull("onglet $t", ClusterReadySignal.signauxPour(t))
    }
}
