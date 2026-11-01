package com.mg4.control.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le volume en quittant la voiture ne fait que baisser. */
class ExitVolumeTest {

    @Test
    fun `un volume au-dessus du niveau vise est baisse`() {
        assertTrue(ExitVolume.shouldLower(current = 14, target = 6))
    }

    @Test
    fun `un volume deja au niveau ou dessous n'est jamais monte`() {
        assertFalse(ExitVolume.shouldLower(current = 6, target = 6))
        assertFalse(ExitVolume.shouldLower(current = 4, target = 6))
        assertFalse(ExitVolume.shouldLower(current = 0, target = 6))
    }

    @Test
    fun `un volume illisible est baisse quand meme, comme avant`() {
        assertTrue(ExitVolume.shouldLower(current = -1, target = 6))
    }
}
