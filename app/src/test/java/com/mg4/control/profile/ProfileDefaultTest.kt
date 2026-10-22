package com.mg4.control.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Profil par défaut après une suppression (2026-10-16) — la règle, sans Android. */
class ProfileDefaultTest {

    @Test
    fun `le profil par defaut supprime est remplace par le premier de la liste`() {
        assertEquals("A", ProfileManager.defautApresSuppression("C", "C", listOf("A", "B")))
    }

    @Test
    fun `supprimer un autre profil ne change pas le defaut`() {
        assertEquals("B", ProfileManager.defautApresSuppression("B", "A", listOf("B", "C")))
    }

    @Test
    fun `plus aucun profil, plus de defaut`() {
        assertNull(ProfileManager.defautApresSuppression("A", "A", emptyList()))
    }

    @Test
    fun `sans defaut au depart, une suppression n en cree pas`() {
        assertNull(ProfileManager.defautApresSuppression(null, "A", listOf("B")))
    }
}
