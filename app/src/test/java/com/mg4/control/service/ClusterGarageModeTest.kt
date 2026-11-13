package com.mg4.control.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #246 : en mode Garage, rien sur le combiné — ni au démarrage, ni au recollage du contact
 * suivant, ni pendant une pré-climatisation.
 */
class ClusterGarageModeTest {

    @Test
    fun `garage mode keeps the module off the cluster on every path`() {
        assertTrue(ClusterModuleOverlay.doitEtreLa(supporte = true, garage = false, active = true, preClim = false))
        assertTrue(ClusterModuleOverlay.doitEtreLa(supporte = true, garage = false, active = false, preClim = true))
        assertFalse(ClusterModuleOverlay.doitEtreLa(supporte = true, garage = true, active = true, preClim = false))
        assertFalse(ClusterModuleOverlay.doitEtreLa(supporte = true, garage = true, active = false, preClim = true))
        assertFalse(ClusterModuleOverlay.doitEtreLa(supporte = true, garage = false, active = false, preClim = false))
        assertFalse(ClusterModuleOverlay.doitEtreLa(supporte = false, garage = false, active = true, preClim = true))
    }
}
