package com.mg4.control.profile

import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ordre des profils (glisser-déposer de la liste, 2026-10-15) : c'est celui du sélecteur, du
 * cycle par défaut et, en conflit Bluetooth, la priorité. Sans Android.
 */
class ProfileOrderTest {

    private fun p(nom: String) =
        DrivingProfile(id = nom, name = nom, driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.MEDIUM)

    private val abcd = listOf(p("A"), p("B"), p("C"), p("D"))

    private fun noms(liste: List<DrivingProfile>) = liste.joinToString("") { it.name }

    @Test
    fun `les profils prennent l ordre donne`() {
        assertEquals("CABD", noms(ProfileManager.ordonner(abcd, listOf("C", "A", "B", "D"))))
    }

    @Test
    fun `un profil absent de l ordre garde sa place relative en fin de liste`() {
        // Créé pendant qu'on glissait, par exemple : il n'est pas perdu.
        assertEquals("DBAC", noms(ProfileManager.ordonner(abcd, listOf("D", "B"))))
    }

    @Test
    fun `un identifiant inconnu est ignore`() {
        assertEquals("BACD", noms(ProfileManager.ordonner(abcd, listOf("Z", "B", "A"))))
    }

    @Test
    fun `le meme ordre ne change rien`() {
        assertEquals("ABCD", noms(ProfileManager.ordonner(abcd, listOf("A", "B", "C", "D"))))
        assertEquals("ABCD", noms(ProfileManager.ordonner(abcd, emptyList())))
    }
}
