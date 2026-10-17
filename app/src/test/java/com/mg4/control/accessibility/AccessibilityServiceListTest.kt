package com.mg4.control.accessibility

import com.mg4.control.accessibility.AccessibilityServiceList.Forme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lecture de la liste des services d'accessibilité activés — logique pure.
 *
 * Né d'un faux « service inactif » (2026-10-17) : après une MAJ ou un redémarrage de la voiture,
 * la liste revenait en forme COURTE, que l'ancienne comparaison (forme longue seule) ne
 * reconnaissait pas, alors que le service tournait.
 */
class AccessibilityServiceListTest {

    private val paquet = "com.mg4.control"
    private val classe = "com.mg4.control.accessibility.KeyCaptureService"

    private fun forme(liste: String?) = AccessibilityServiceList.forme(liste, paquet, classe)

    @Test
    fun `la forme longue des Reglages est reconnue`() {
        assertEquals(Forme.LONGUE, forme("com.mg4.control/com.mg4.control.accessibility.KeyCaptureService"))
    }

    @Test
    fun `la forme courte reecrite par le systeme est reconnue`() {
        // Le cas de la voiture : le service tourne, l'ancienne lecture le disait inactif.
        assertEquals(Forme.COURTE, forme("com.mg4.control/.accessibility.KeyCaptureService"))
        assertTrue(AccessibilityServiceList.contient("com.mg4.control/.accessibility.KeyCaptureService", paquet, classe))
    }

    @Test
    fun `parmi d autres services, espaces compris`() {
        val liste = "com.autre.appli/.MonService: com.mg4.control/.accessibility.KeyCaptureService :com.saic.x/com.saic.x.Y"
        assertEquals(Forme.COURTE, forme(liste))
        // Une entrée longue l'emporte sur une courte du même composant.
        assertEquals(Forme.LONGUE, forme("com.mg4.control/.accessibility.KeyCaptureService:" +
            "com.mg4.control/com.mg4.control.accessibility.KeyCaptureService"))
    }

    @Test
    fun `ni un autre paquet ni une autre classe ne comptent`() {
        // Même classe dans une autre variante de l'appli (offline) : pas nous.
        assertEquals(Forme.ABSENTE, forme("com.mg4.control.offline/com.mg4.control.accessibility.KeyCaptureService"))
        assertEquals(Forme.ABSENTE, forme("com.mg4.control/.accessibility.AutreService"))
        assertEquals(Forme.ABSENTE, forme("com.autre/.accessibility.KeyCaptureService"))
        // La variante offline se reconnaît elle-même, en forme longue (sa classe n'est pas sous son paquet).
        assertEquals(Forme.LONGUE, AccessibilityServiceList.forme(
            "com.mg4.control.offline/com.mg4.control.accessibility.KeyCaptureService", "com.mg4.control.offline", classe))
    }

    @Test
    fun `reglage vide ou abime`() {
        listOf(null, "", ":", "  ", "com.mg4.control", "/.accessibility.KeyCaptureService", "com.mg4.control/")
            .forEach { assertFalse("« $it »", AccessibilityServiceList.contient(it, paquet, classe)) }
    }
}
