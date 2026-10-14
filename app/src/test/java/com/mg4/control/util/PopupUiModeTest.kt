package com.mg4.control.util

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thème des fenêtres affichées par le SERVICE (sélecteur de profils, confirmations, mise à jour,
 * pop-up HVAC).
 *
 * Le thème choisi dans l'application n'atteint que ses écrans ; ces fenêtres prenaient donc le
 * réglage jour/nuit du système — figé sur « nuit » sur SWI133 (0x23 mesuré, que le launcher soit
 * clair ou sombre). Elles restaient sombres quel que soit le thème de l'application.
 */
class PopupUiModeTest {

    // Valeurs relevées sur la voiture : type « voiture » (0x03) + nuit (0x20) ou jour (0x10).
    private val nuit = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES
    private val jour = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO

    @Test
    fun `le theme clair de l application s impose a un systeme fige en nuit`() {
        assertEquals(jour, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_NO, nuit))
    }

    @Test
    fun `le theme sombre de l application s impose a un systeme en jour`() {
        assertEquals(nuit, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_YES, jour))
    }

    @Test
    fun `le reste du mode d interface n est pas touche`() {
        // Seuls les deux bits jour/nuit changent : le type d'appareil doit survivre.
        val tele = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
        assertEquals(
            Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO,
            PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_NO, tele))
    }

    @Test
    fun `un systeme sans reglage jour nuit recoit celui de l application`() {
        val indefini = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_UNDEFINED
        assertEquals(jour, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_NO, indefini))
        assertEquals(nuit, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_YES, indefini))
    }

    @Test
    fun `quand l application suit le systeme rien ne change`() {
        // Thème « Auto » sans valeur exploitable : l'application laisse faire le système, la
        // fenêtre aussi.
        assertEquals(nuit, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, nuit))
        assertEquals(jour, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, jour))
        assertEquals(nuit, PopupUiMode.apply(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED, nuit))
    }
}
