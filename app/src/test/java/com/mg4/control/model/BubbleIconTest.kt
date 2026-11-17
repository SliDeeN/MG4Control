package com.mg4.control.model

import com.mg4.control.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Icônes des bulles flottantes (2026-11-17). */
class BubbleIconTest {

    private fun bulle(action: String = ShortcutAction.CUSTOM.name) = FloatingButton(id = "b", action = action)

    @Test fun `une bulle d avant reste titre ou information`() {
        assertEquals(BubbleIcon.Content.TITLE, BubbleIcon.content(bulle()))
        val info = bulle().copy(info = "SOC")
        assertEquals(BubbleIcon.Content.INFO, BubbleIcon.content(info))
        // L'information l'emporte sur un reste d'icône.
        assertEquals(BubbleIcon.Content.INFO, BubbleIcon.content(info.copy(iconContent = true)))
        assertFalse(BubbleIcon.showsIcon(info))
        assertTrue(BubbleIcon.showsIcon(info.copy(infoStyle = BubbleInfo.STYLE_ICON)))
    }

    @Test fun `bulle-icone, l appli lancee par defaut, sinon un symbole`() {
        val b = bulle().copy(iconContent = true)
        assertEquals("app:com.google.android.youtube", BubbleIcon.resolve(b, listOf("com.google.android.youtube")))
        assertEquals("sym:${BubbleIcon.DEFAULT_SYMBOL}", BubbleIcon.resolve(b, emptyList()))
        assertEquals("sym:ac_unit", BubbleIcon.resolve(b.copy(icon = "sym:ac_unit"), listOf("x")))
        // Icône seule par défaut ; le titre seulement si demandé.
        assertFalse(BubbleIcon.showsTitle(b))
        assertTrue(BubbleIcon.showsTitle(b.copy(iconTitle = true)))
        assertTrue(BubbleIcon.showsTitle(bulle()))
    }

    @Test fun `information, son icone ou un symbole choisi, jamais une appli`() {
        val i = bulle().copy(info = "SOC", infoStyle = BubbleInfo.STYLE_ICON)
        assertNull(BubbleIcon.resolve(i, listOf("x")))
        assertEquals("sym:bolt", BubbleIcon.resolve(i.copy(icon = "sym:bolt"), emptyList()))
        assertNull(BubbleIcon.resolve(i.copy(icon = "app:x"), emptyList()))
    }

    @Test fun `applis d une bulle selon son action`() {
        val proj = listOf("aa", "cp")
        assertEquals(listOf("yt"), BubbleIcon.apps(ShortcutAction.CUSTOM.name, "yt", "me", proj))
        assertEquals(emptyList<String>(), BubbleIcon.apps(ShortcutAction.CUSTOM.name, null, "me", proj))
        assertEquals(proj, BubbleIcon.apps(ShortcutAction.PROJECTION.name, null, "me", proj))
        assertEquals(listOf("me"), BubbleIcon.apps(ShortcutAction.OPEN_APP.name, null, "me", proj))
        // « Lancer une application » sur une bulle (2026-11-17) : l'appli choisie.
        assertEquals(listOf("yt"), BubbleIcon.apps(ShortcutAction.OPEN_CUSTOM_APP.name, "yt", "me", proj))
        assertEquals(emptyList<String>(), BubbleIcon.apps(ShortcutAction.ONE_PEDAL.name, null, "me", proj))
        assertEquals("pkg", BubbleIcon.appPackage("app:pkg"))
        assertNull(BubbleIcon.appPackage("sym:x"))
    }
}
