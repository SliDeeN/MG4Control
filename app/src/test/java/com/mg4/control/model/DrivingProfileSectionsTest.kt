package com.mg4.control.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interrupteur général des trois catégories d'un profil (Conduite, Sécurité, Confort).
 *
 * Une catégorie coupée = le profil n'y touche pas, la voiture garde ses réglages. La contrainte
 * qui commande tout le reste : un profil enregistré AVANT la fonction doit continuer à
 * s'appliquer exactement comme avant. Les profils sont relus par Gson, qui n'appelle pas le
 * constructeur Kotlin — un champ absent ne reçoit donc pas son défaut, d'où les tests sur le
 * JSON plutôt que sur le seul constructeur.
 */
class DrivingProfileSectionsTest {

    private val gson = Gson()

    private fun profil(
        drive: Boolean? = null, safety: Boolean? = null, comfort: Boolean? = null,
        steeringHeatEnabled: Boolean? = null, seatHeatEnabled: Boolean? = null,
        hvacEnabled: Boolean = false, autoHighBeam: Boolean? = null
    ) = DrivingProfile(
        name = "Essai", driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.MEDIUM,
        driveEnabled = drive, safetyEnabled = safety, comfortEnabled = comfort,
        steeringHeatEnabled = steeringHeatEnabled, seatHeatEnabled = seatHeatEnabled,
        hvacEnabled = hvacEnabled, autoHighBeam = autoHighBeam
    )

    /** Le profil tel qu'il ressort de l'enregistrement, relu comme le fait ProfileManager. */
    private fun relu(p: DrivingProfile): DrivingProfile = gson.fromJson(gson.toJson(p), DrivingProfile::class.java)

    // ── Profils existants ────────────────────────────────────────────────────

    @Test
    fun `un profil enregistre avant la fonction garde ses trois categories`() {
        // Ce que contient le stockage d'une version antérieure : aucun des trois champs.
        val ancien = gson.toJsonTree(profil()).asJsonObject
        listOf("driveEnabled", "safetyEnabled", "comfortEnabled").forEach { ancien.remove(it) }

        val p = gson.fromJson(ancien, DrivingProfile::class.java)

        assertTrue("Conduite", p.appliesDrive)
        assertTrue("Sécurité", p.appliesSafety)
        assertTrue("Confort", p.appliesComfort)
    }

    @Test
    fun `un ancien profil ecrit toujours ses chauffages et sa climatisation`() {
        val ancien = gson.toJsonTree(profil(hvacEnabled = true, autoHighBeam = true)).asJsonObject
        listOf("driveEnabled", "safetyEnabled", "comfortEnabled",
               "steeringHeatEnabled", "seatHeatEnabled").forEach { ancien.remove(it) }

        val p = gson.fromJson(ancien, DrivingProfile::class.java)

        assertTrue(p.writesSteeringHeat)
        assertTrue(p.writesSeatHeat)
        assertTrue(p.writesClimate)
        assertEquals(true, p.writesAutoHighBeam)
    }

    // ── Nouveau profil ───────────────────────────────────────────────────────

    @Test
    fun `un nouveau profil a ses trois categories actives`() {
        val p = profil()
        assertTrue(p.appliesDrive)
        assertTrue(p.appliesSafety)
        assertTrue(p.appliesComfort)
    }

    // ── Catégorie coupée ─────────────────────────────────────────────────────

    @Test
    fun `une categorie coupee le reste apres enregistrement`() {
        val p = relu(profil(drive = false, safety = false, comfort = false))
        assertFalse(p.appliesDrive)
        assertFalse(p.appliesSafety)
        assertFalse(p.appliesComfort)
    }

    @Test
    fun `couper une categorie ne touche pas aux deux autres`() {
        val p = relu(profil(comfort = false))
        assertTrue(p.appliesDrive)
        assertTrue(p.appliesSafety)
        assertFalse(p.appliesComfort)
    }

    @Test
    fun `une categorie explicitement active le reste apres enregistrement`() {
        val p = relu(profil(drive = true, safety = true, comfort = true))
        assertTrue(p.appliesDrive && p.appliesSafety && p.appliesComfort)
    }

    // ── Ce que le Confort écrit réellement ───────────────────────────────────

    @Test
    fun `confort coupe - ni chauffages ni climatisation ni feux ne sont ecrits`() {
        // Tous les interrupteurs de la page sont cochés : c'est la catégorie qui l'emporte.
        val p = profil(comfort = false, steeringHeatEnabled = true, seatHeatEnabled = true,
                       hvacEnabled = true, autoHighBeam = true)
        assertFalse(p.writesSteeringHeat)
        assertFalse(p.writesSeatHeat)
        assertFalse(p.writesClimate)
        assertNull(p.writesAutoHighBeam)
    }

    @Test
    fun `confort actif - chaque reglage suit son propre interrupteur`() {
        val p = profil(comfort = true, steeringHeatEnabled = false, seatHeatEnabled = true,
                       hvacEnabled = false, autoHighBeam = false)
        assertFalse(p.writesSteeringHeat)
        assertTrue(p.writesSeatHeat)
        assertFalse(p.writesClimate)
        assertEquals(false, p.writesAutoHighBeam)
    }

    @Test
    fun `couper le confort conserve les reglages de la page`() {
        // Réactiver la catégorie doit retrouver ce qui était réglé : rien n'est effacé.
        val p = relu(profil(comfort = false, steeringHeatEnabled = true, hvacEnabled = true, autoHighBeam = true))
        assertTrue(p.appliesSteeringHeat)
        assertTrue(p.hvacEnabled)
        assertEquals(true, p.autoHighBeam)
    }
}
