package com.mg4.control.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles de déclenchement de l'automatisation batterie (issue #112).
 *
 * L'enjeu : agir une fois, au bon moment, et jamais en boucle — un profil réappliqué à chaque
 * relevé écraserait tout ce que le conducteur change à la main.
 */
class BatteryTriggerTest {

    private val seuil = 20

    @Test
    fun `franchir le seuil declenche une fois`() {
        val t = BatteryTrigger()
        assertFalse(t.onSoc(24f, seuil, canAct = true))
        assertTrue("passage sous 20 %", t.onSoc(19.8f, seuil, canAct = true))
        assertFalse("pas une seconde fois", t.onSoc(19.5f, seuil, canAct = true))
        assertFalse(t.onSoc(17f, seuil, canAct = true))
    }

    @Test
    fun `la detente de la batterie ne redeclenche pas`() {
        val t = BatteryTrigger()
        assertTrue(t.onSoc(19.9f, seuil, canAct = true))
        // Après l'effort, le pourcentage remonte seul de quelques dixièmes, puis redescend.
        assertFalse(t.onSoc(20.3f, seuil, canAct = true))
        assertFalse("pas de boucle autour du seuil", t.onSoc(19.9f, seuil, canAct = true))
    }

    @Test
    fun `une vraie recharge rouvre un episode`() {
        val t = BatteryTrigger()
        assertTrue(t.onSoc(19f, seuil, canAct = true))
        assertFalse("recharge au-dessus du seuil + marge", t.onSoc(45f, seuil, canAct = true))
        assertTrue("nouveau passage sous le seuil", t.onSoc(19.5f, seuil, canAct = true))
    }

    @Test
    fun `un nouveau demarrage rearme meme batterie toujours basse`() {
        val t = BatteryTrigger()
        assertTrue(t.onSoc(15f, seuil, canAct = true))
        t.rearm()
        assertTrue("démarrage déjà sous le seuil : on applique", t.onSoc(15f, seuil, canAct = true))
    }

    @Test
    fun `la securite conduite fait attendre sans perdre l'episode`() {
        val t = BatteryTrigger()
        assertFalse("en roulant au-delà de la limite", t.onSoc(19f, seuil, canAct = false))
        assertTrue(t.isPending)
        assertFalse(t.onSoc(18.5f, seuil, canAct = false))
        assertTrue("au prochain arrêt", t.onSoc(18.4f, seuil, canAct = true))
        assertFalse(t.isPending)
        assertFalse("et une seule fois", t.onSoc(18.3f, seuil, canAct = true))
    }

    @Test
    fun `une lecture ratee ne conclut rien`() {
        val t = BatteryTrigger()
        assertFalse(t.onSoc(null, seuil, canAct = true))
        assertTrue("l'épisode reste ouvert", t.onSoc(19f, seuil, canAct = true))
    }

    @Test
    fun `au-dessus du seuil rien ne se passe`() {
        val t = BatteryTrigger()
        assertFalse(t.onSoc(20f, seuil, canAct = true))
        assertFalse(t.onSoc(80f, seuil, canAct = true))
    }
}
