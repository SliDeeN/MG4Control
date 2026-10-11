package com.mg4.control.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passage en READY : que devient un profil choisi à la main ?
 *
 * Comportement d'origine, et toujours le défaut : il est RÉAPPLIQUÉ à chaque passage en READY,
 * jusqu'à l'extinction de la voiture. L'option « Revenir au profil par défaut à chaque passage en
 * READY » le fait oublier, et la chaîne habituelle reprend la main — profil lié à la température,
 * puis au téléphone Bluetooth, puis profil par défaut.
 */
class ReadyProfilePolicyTest {

    @Test
    fun `option decochee - le profil choisi a la main est reapplique`() {
        assertEquals("sport", ReadyProfilePolicy.manualToReapply("sport", backToDefault = false))
    }

    @Test
    fun `option cochee - le profil choisi a la main n est plus reapplique`() {
        // null = la chaîne automatique décide : température, Bluetooth, puis profil par défaut.
        assertNull(ReadyProfilePolicy.manualToReapply("sport", backToDefault = true))
    }

    @Test
    fun `sans choix manuel la chaine automatique decide dans les deux cas`() {
        assertNull(ReadyProfilePolicy.manualToReapply(null, backToDefault = false))
        assertNull(ReadyProfilePolicy.manualToReapply(null, backToDefault = true))
    }

    @Test
    fun `option cochee - le choix manuel est oublie pour de bon`() {
        // Seulement écarté, il reviendrait au READY suivant si l'option était décochée entre-temps.
        assertTrue(ReadyProfilePolicy.forgetsManual("sport", backToDefault = true))
    }

    @Test
    fun `option decochee - le choix manuel est garde`() {
        assertFalse(ReadyProfilePolicy.forgetsManual("sport", backToDefault = false))
    }

    @Test
    fun `sans choix manuel il n y a rien a oublier`() {
        assertFalse(ReadyProfilePolicy.forgetsManual(null, backToDefault = true))
        assertFalse(ReadyProfilePolicy.forgetsManual(null, backToDefault = false))
    }
}
