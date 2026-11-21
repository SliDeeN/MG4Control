package com.mg4.control.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Étoiles du volant et capture avancée (2026-11-21) : capture active, une étoile réglée sur le
 * chemin classique laissait la voiture exécuter aussi sa propre fonction d'étoile. Ses réglages
 * passent à la capture, et l'étoile droite est reconnue sous ses deux codes (286 et 18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarCaptureTest {

    private lateinit var ctx: Context
    private val classique get() = ctx.getSharedPreferences("mg4_shortcuts", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
        classique.edit().clear().commit()
    }

    @Test
    fun `turning capture on takes over the classic star settings with their targets`() {
        classique.edit()
            .putInt("shortcut_btn2_single", ShortcutAction.REGEN_CYCLE.id)
            .putInt("shortcut_btn1_long", ShortcutAction.OPEN_APP.id)
            .putString("shortcut_btn1_long_custom_app", "com.exemple")
            .commit()
        AdvancedShortcuts.setEnabled(ctx, true)
        assertEquals(ShortcutAction.REGEN_CYCLE, AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE))
        assertEquals(ShortcutAction.OPEN_APP, AdvancedShortcuts.actionFor(ctx, 17, PressType.LONG))
        assertEquals("com.exemple", classique.getString("shortcut_adv_17_long_custom_app", null))
        assertTrue("l'étoile droite est prise par la capture", AdvancedShortcuts.isClaimedNow(ctx, 286))
    }

    @Test
    fun `an action removed under capture does not come back at the next activation`() {
        classique.edit().putInt("shortcut_btn2_single", ShortcutAction.REGEN_CYCLE.id).commit()
        AdvancedShortcuts.setEnabled(ctx, true)
        AdvancedShortcuts.remove(ctx, 286, PressType.SINGLE)
        AdvancedShortcuts.mirrorStarToClassic(ctx, 286, PressType.SINGLE)
        AdvancedShortcuts.setEnabled(ctx, false)
        AdvancedShortcuts.setEnabled(ctx, true)
        assertNull(AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE))
    }

    @Test
    fun `a classic change made while capture was off is taken at the next activation`() {
        AdvancedShortcuts.setEnabled(ctx, true)
        AdvancedShortcuts.setEnabled(ctx, false)
        classique.edit().putInt("shortcut_btn1_single", ShortcutAction.MEDIA_NEXT.id).commit()
        AdvancedShortcuts.setEnabled(ctx, true)
        assertEquals(ShortcutAction.MEDIA_NEXT, AdvancedShortcuts.actionFor(ctx, 17, PressType.SINGLE))
    }

    @Test
    fun `the mirror copies the action and its target to the classic slot`() {
        AdvancedShortcuts.set(ctx, 286, PressType.LONG, ShortcutAction.OPEN_APP)
        classique.edit().putString("shortcut_adv_286_long_custom_app", "com.exemple").commit()
        AdvancedShortcuts.mirrorStarToClassic(ctx, 286, PressType.LONG)
        assertEquals(ShortcutAction.OPEN_APP.id, classique.getInt("shortcut_btn2_long", 0))
        assertEquals("com.exemple", classique.getString("shortcut_btn2_long_custom_app", null))
    }

    @Test
    fun `the right star is one key under both codes`() {
        assertEquals(286, AdvancedShortcuts.canonical(18))
        assertEquals(17, AdvancedShortcuts.canonical(17))
        // Un raccourci enregistré sous 18 est rangé sous 286.
        AdvancedShortcuts.set(ctx, 18, PressType.DOUBLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.setEnabled(ctx, true)
        assertEquals(ShortcutAction.MEDIA_NEXT, AdvancedShortcuts.actionFor(ctx, 286, PressType.DOUBLE))
        assertNull(AdvancedShortcuts.actionFor(ctx, 18, PressType.DOUBLE))
    }
}
