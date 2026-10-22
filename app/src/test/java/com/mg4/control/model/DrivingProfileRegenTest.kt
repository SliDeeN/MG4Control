package com.mg4.control.model

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Régénération « inchangée » (issue #140) : un profil peut ne toucher ni au niveau de
 * régénération ni à la conduite à une pédale — c'est le même réglage dans la voiture.
 *
 * Comme pour les chauffages : un profil enregistré AVANT la fonction doit continuer à appliquer
 * sa régénération. Gson n'appelle pas le constructeur Kotlin, d'où les tests sur le JSON.
 */
class DrivingProfileRegenTest {

    private val gson = Gson()

    private fun profil(regenEnabled: Boolean? = null, drive: Boolean? = null) = DrivingProfile(
        name = "Essai", driveMode = DriveMode.NORMAL, regenLevel = RegenLevel.ONE_PEDAL,
        regenEnabled = regenEnabled, driveEnabled = drive
    )

    private fun relu(p: DrivingProfile): DrivingProfile = gson.fromJson(gson.toJson(p), DrivingProfile::class.java)

    @Test fun `un profil enregistre avant la fonction applique toujours sa regeneration`() {
        val ancien = gson.toJsonTree(profil()).asJsonObject
        ancien.remove("regenEnabled")
        val p = gson.fromJson(ancien, DrivingProfile::class.java)
        assertTrue(p.appliesRegen)
        assertTrue(p.writesRegen)
    }

    @Test fun `regeneration inchangee - rien n est ecrit, meme la conduite a une pedale`() {
        val p = relu(profil(regenEnabled = false))
        assertFalse(p.appliesRegen)
        assertFalse(p.writesRegen)
    }

    @Test fun `conduite coupee - la regeneration n est pas ecrite non plus`() {
        assertFalse(profil(regenEnabled = true, drive = false).writesRegen)
    }

    @Test fun `couper la regeneration garde le niveau choisi`() {
        // Réactiver l'interrupteur doit retrouver le niveau réglé : rien n'est effacé.
        assertTrue(relu(profil(regenEnabled = false)).regenLevel == RegenLevel.ONE_PEDAL)
    }
}
