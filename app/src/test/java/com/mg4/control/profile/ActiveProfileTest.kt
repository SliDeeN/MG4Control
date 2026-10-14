package com.mg4.control.profile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le profil appliqué DEPUIS le démarrage de l'application ([ActiveProfile.sessionId]), à ne pas
 * confondre avec le profil actif mémorisé d'une session à l'autre ([ActiveProfile.id]).
 *
 * Issue #121, journal du 2026-10-13 : au démarrage du service, le profil actif mémorisé désignait
 * encore celui du trajet précédent, et l'automatisation A/C s'effaçait devant un profil qui
 * n'allait pas être appliqué.
 *
 * Sans contexte Android : seule la mémoire de la session est en jeu ici.
 */
class ActiveProfileTest {

    @After fun oublier() = ActiveProfile.set(null, null)

    @Test fun `le profil applique est celui de la session`() {
        ActiveProfile.set(null, "eco")
        assertEquals("eco", ActiveProfile.sessionId)
    }

    @Test fun `le dernier profil applique remplace le precedent`() {
        ActiveProfile.set(null, "eco")
        ActiveProfile.set(null, "sport")
        assertEquals("sport", ActiveProfile.sessionId)
    }

    @Test fun `un profil oublie ne reste pas celui de la session`() {
        // Profil supprimé : forgetIfMissing passe par set(null).
        ActiveProfile.set(null, "eco")
        ActiveProfile.set(null, null)
        assertNull(ActiveProfile.sessionId)
    }
}
