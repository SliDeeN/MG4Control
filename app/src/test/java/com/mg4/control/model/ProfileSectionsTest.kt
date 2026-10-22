package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catégories d'un profil — ce que l'éditeur et la liste en disent, sans véhicule ni Android.
 */
class ProfileSectionsTest {

    private fun profil(
        drive: Boolean? = null, safety: Boolean? = null, comfort: Boolean? = null, regen: Boolean? = null
    ) =
        DrivingProfile(
            name = "Essai", driveMode = DriveMode.SPORT, regenLevel = RegenLevel.HIGH,
            driveEnabled = drive, safetyEnabled = safety, comfortEnabled = comfort, regenEnabled = regen
        )

    private fun resume(p: DrivingProfile, adas: String = "ACC") =
        ProfileSections.summary(p, adas, "Conduite", "Sécurité", "Confort", regenUnchanged = "Régén. inchangée")

    // ── Enregistrement ───────────────────────────────────────────────────────

    @Test
    fun `un profil sans aucune categorie active n est pas enregistrable`() {
        assertFalse(ProfileSections.anyEnabled(drive = false, safety = false, comfort = false))
    }

    @Test
    fun `une seule categorie active suffit`() {
        assertTrue(ProfileSections.anyEnabled(drive = false, safety = true, comfort = false))
        assertTrue(ProfileSections.anyEnabled(drive = true, safety = false, comfort = false))
        assertTrue(ProfileSections.anyEnabled(drive = false, safety = false, comfort = true))
    }

    // ── Résumé dans la liste des profils ─────────────────────────────────────

    @Test
    fun `resume inchange pour un profil complet ou anterieur a la fonction`() {
        assertEquals("Sport · High · ACC", resume(profil()))
        assertEquals("Sport · High · ACC", resume(profil(drive = true, safety = true, comfort = true)))
    }

    @Test
    fun `conduite coupee - le resume ne cite ni mode ni regeneration`() {
        assertEquals("ACC", resume(profil(drive = false)))
    }

    @Test
    fun `regeneration inchangee - le resume le dit a la place du niveau`() {
        // Issue #140 : annoncer « High » serait faux, le profil n'y touchera pas.
        assertEquals("Sport · Régén. inchangée · ACC", resume(profil(regen = false)))
    }

    @Test
    fun `securite coupee - le resume ne cite pas le mode d aide a la conduite`() {
        assertEquals("Sport · High", resume(profil(safety = false)))
    }

    @Test
    fun `sans rien a citer - le resume nomme les categories actives`() {
        assertEquals("Confort", resume(profil(drive = false, safety = false)))
        // Sécurité active mais sans libellé sur ce firmware.
        assertEquals("Sécurité · Confort", resume(profil(drive = false), adas = ""))
    }

    @Test
    fun `firmware sans libelle d aide - le resume complet reste celui d avant`() {
        assertEquals("Sport · High", resume(profil(), adas = ""))
    }

    // ── Rail de l'éditeur ────────────────────────────────────────────────────

    @Test
    fun `le bouton du rail signale une categorie coupee`() {
        assertEquals("Conduite", ProfileSections.railLabel("Conduite", enabled = true, offTag = "OFF"))
        assertEquals("Conduite\nOFF", ProfileSections.railLabel("Conduite", enabled = false, offTag = "OFF"))
    }
}
