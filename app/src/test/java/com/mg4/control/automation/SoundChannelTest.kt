package com.mg4.control.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.SoundChannel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canal des sons : Média par défaut (comme avant), Navigation au choix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SoundChannelTest {

    @Test
    fun `media by default, unknown values fall back to media`() {
        assertEquals(SoundChannel.MEDIA, SoundChannel.of(null))
        assertEquals(SoundChannel.MEDIA, SoundChannel.of("autre"))
        assertEquals(SoundChannel.NAVIGATION, SoundChannel.of("navigation"))
        assertEquals("navigation", SoundChannel.NAVIGATION.key)
    }

    @Test
    fun `the choice is kept`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(SoundChannel.MEDIA, SoundAutomationSettings.channel(ctx))
        SoundAutomationSettings.setChannel(ctx, SoundChannel.NAVIGATION)
        assertEquals(SoundChannel.NAVIGATION, SoundAutomationSettings.channel(ctx))
    }
}
