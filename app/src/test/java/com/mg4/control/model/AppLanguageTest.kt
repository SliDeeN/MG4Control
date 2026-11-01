package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Langue au premier lancement : celle de la voiture si l'appli la parle (2026-10-23). */
class AppLanguageTest {

    @Test fun `une langue traduite de la voiture est reprise`() {
        assertEquals("de", AppLanguage.fromCar("de"))
        assertEquals("tr", AppLanguage.fromCar("TR"))
        assertEquals("it", AppLanguage.effective(null, "it"))
    }

    @Test fun `une langue non traduite demande un choix`() {
        assertNull(AppLanguage.fromCar("nl"))
        assertNull(AppLanguage.fromCar(null))
        // En attendant le choix (pop-ups du service avant la première ouverture) : l'anglais.
        assertEquals("en", AppLanguage.effective(null, "nl"))
        assertEquals("en", AppLanguage.effective(null, null))
    }

    @Test fun `le choix enregistre l emporte sur la voiture`() {
        assertEquals("fr", AppLanguage.effective("fr", "de"))
    }
}
