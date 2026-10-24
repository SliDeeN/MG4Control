package com.mg4.control.model

import com.mg4.control.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Style des bulles selon l'action (maquette A, 2026-10-24). */
class FloatingButtonStyleTest {

    @Test fun `les bascules montrent leur etat`() {
        for (a in listOf(ShortcutAction.HVAC_AC_TOGGLE, ShortcutAction.HVAC_TOGGLE, ShortcutAction.ONE_PEDAL,
                ShortcutAction.STEERING_HEAT_TOGGLE, ShortcutAction.BATTERY_HEAT_TOGGLE)) {
            assertEquals(a.name, FloatingKind.TOGGLE, FloatingButtonStyle.kind(a))
        }
    }

    @Test fun `cycles et reglages pas a pas montrent leur valeur`() {
        for (a in listOf(ShortcutAction.REGEN_CYCLE, ShortcutAction.SEAT_HEAT_LEFT_CYCLE,
                ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_FAN_DOWN, ShortcutAction.VOLUME_UP)) {
            assertEquals(a.name, FloatingKind.VALUE, FloatingButtonStyle.kind(a))
        }
    }

    @Test fun `le reste n a que le halo`() {
        // Les cycles de profils et de sources annoncent déjà leur choix dans un message.
        for (a in listOf(ShortcutAction.PROFILE_CYCLE, ShortcutAction.AUDIO_SOURCE_CYCLE,
                ShortcutAction.HVAC_POPUP, ShortcutAction.PROJECTION, ShortcutAction.MEDIA_NEXT)) {
            assertEquals(a.name, FloatingKind.NONE, FloatingButtonStyle.kind(a))
        }
        assertEquals(FloatingKind.NONE, FloatingButtonStyle.kind("ACTION_RETIREE"))
        assertEquals(FloatingKind.TOGGLE, FloatingButtonStyle.kind("HVAC_AC_TOGGLE"))
    }
}
