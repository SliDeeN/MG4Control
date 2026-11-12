package com.mg4.control.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.accessibility.AdvancedShortcuts
import com.mg4.control.model.CustomRule
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.RuleAction
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FloatingButtonSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ce qui lance une automatisation « à la demande » (2026-11-12) : touches du volant (capture
 * avancée et ★ classiques), bulles, autres automatisations — même dans un bloc si / sinon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr")
class RuleLaunchersTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `wheel buttons, bubbles and other automations are listed`() {
        val id = "regle-cible"
        val cibles = ctx.getSharedPreferences("mg4_shortcuts", Context.MODE_PRIVATE).edit()
        // ★ gauche classique, appui long.
        cibles.putInt("shortcut_btn1_long", ShortcutAction.RUN_RULE.id).putString("shortcut_btn1_long_rule_id", id)
        // Joystick haut en capture avancée, appui simple.
        AdvancedShortcuts.setEnabled(ctx, true)
        AdvancedShortcuts.set(ctx, 297, PressType.SINGLE, ShortcutAction.RUN_RULE)
        cibles.putString("shortcut_${AdvancedShortcuts.slotKey(297, PressType.SINGLE)}_rule_id", id).commit()
        FloatingButtonSettings.save(ctx, listOf(
            FloatingButton(id = "b1", action = ShortcutAction.RUN_RULE.name, customTitle = "Clim", customId = id),
            FloatingButton(id = "b2", action = ShortcutAction.RUN_RULE.name, customTitle = "Autre", customId = "autre"),
        ))
        val regles = listOf(
            CustomRule(id = id, name = "Cible"),
            CustomRule(id = "r2", name = "Départ matin", actions = listOf(RuleAction(RuleAction.Type.IF,
                then = listOf(RuleAction(RuleAction.Type.RUN_RULE, text = id))))),
            CustomRule(id = "r3", name = "Sans lien", actions = listOf(RuleAction(RuleAction.Type.RUN_RULE, text = "autre"))),
        )
        val l = RuleLaunchers.of(ctx, id, regles)
        assertEquals(4, l.size)
        assertTrue(l.any { it.contains("Joystick") && it.contains("simple") })
        assertTrue(l.any { it.contains("gauche") && it.contains("long") })
        assertTrue("bulle « Clim »" in l)
        assertTrue("automatisation « Départ matin »" in l)
    }

    @Test
    fun `nothing configured gives an empty list`() {
        assertEquals(emptyList<String>(), RuleLaunchers.of(ctx, "seule", listOf(CustomRule(id = "seule"))))
    }
}
