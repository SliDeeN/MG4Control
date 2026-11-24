package com.mg4.control.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RuleAction
import com.mg4.control.model.RuleLogic
import com.mg4.control.model.SoundAutomationRules
import com.mg4.control.model.SoundChannel
import com.mg4.control.model.SoundSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canal des sons : Navigation par défaut depuis le 2026-11-24, Média en repli ; volumes convertis une fois. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SoundChannelTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("mg4_custom_rules", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `navigation by default, an explicit media choice is kept`() {
        assertEquals(SoundChannel.NAVIGATION, SoundChannel.of(null))
        assertEquals(SoundChannel.MEDIA, SoundChannel.of("media"))
        assertEquals(SoundChannel.NAVIGATION, SoundAutomationSettings.channel(ctx))
        assertTrue(SoundAutomationSettings.needsNavigationMigration(ctx))
        SoundAutomationSettings.setChannel(ctx, SoundChannel.MEDIA)
        assertEquals(SoundChannel.MEDIA, SoundAutomationSettings.channel(ctx))
        assertFalse("un choix fait n'est plus converti", SoundAutomationSettings.needsNavigationMigration(ctx))
    }

    @Test
    fun `volumes move from one scale to the other in proportion`() {
        assertEquals(15, SoundAutomationRules.rescale(20, 40, 30))
        assertEquals(30, SoundAutomationRules.rescale(40, 40, 30))
        assertEquals(0, SoundAutomationRules.rescale(0, 40, 30))
        assertEquals("échelles égales", 17, SoundAutomationRules.rescale(17, 30, 30))
        assertEquals("échelle illisible", 17, SoundAutomationRules.rescale(17, -1, 30))
    }

    @Test
    fun `the migration converts page sounds and nested automation sounds once`() {
        SoundAutomationSettings.ajouter(ctx, SoundSlot(title = "a", volume = 20), 7)
        SoundAutomationSettings.ajouter(ctx, SoundSlot(title = "b", volume = null), 7)
        val son = RuleAction(RuleAction.Type.PLAY_SOUND, key = RuleLogic.RULE_SOUND + "x", count = 40)
        val bip = RuleAction(RuleAction.Type.PLAY_SOUND, key = RuleLogic.BEEP, count = 70)
        val si = RuleAction(RuleAction.Type.IF, then = listOf(son), otherwise = listOf(bip))
        CustomRuleStore.add(ctx, CustomRule(id = "r1", name = "r", actions = listOf(son, si)))

        SoundAutomationSettings.migrateToNavigation(ctx, mediaMax = 40, navMax = 30)

        val sons = SoundAutomationSettings.slots(ctx)
        assertEquals(15, sons[0].volume)
        assertNull("volume par défaut : la moitié de l'échelle, rien à convertir", sons[1].volume)
        val actions = CustomRuleStore.all(ctx).single().actions
        assertEquals(30, actions[0].count)
        assertEquals(30, actions[1].then!!.single().count)
        assertEquals("le bip est en %, pas sur l'échelle de la voiture", 70, actions[1].otherwise!!.single().count)
        assertFalse(SoundAutomationSettings.needsNavigationMigration(ctx))
        assertEquals(SoundChannel.NAVIGATION, SoundAutomationSettings.channel(ctx))
    }
}
