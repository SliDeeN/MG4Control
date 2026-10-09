package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus.Commande
import com.mg4.control.model.HvacPopup.Action
import com.mg4.control.model.HvacPopup.Reglages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pop-up HVAC — ce que fait le joystick droit, sans vue ni véhicule.
 *
 * Contrairement aux popups de profils, le joystick n'y déplace AUCUN focus : chaque direction
 * agit directement (issues #120 et #125). Se tromper d'axe ou de sens ferait monter la température
 * quand le conducteur veut plus d'air, sans qu'il regarde l'écran — d'où ces tests.
 */
class HvacPopupTest {

    // Bornes d'un ancien SDK : 16–32 °C, ventilation 1–7.
    private val milieu = Reglages(temp = 22, tempMin = 16, tempMax = 32, fan = 4, fanMin = 1, fanMax = 7)

    @Test
    fun `haut et bas reglent la temperature d un degre`() {
        assertEquals(Action.Temperature(23), HvacPopup.action(Commande.HAUT, milieu))
        assertEquals(Action.Temperature(21), HvacPopup.action(Commande.BAS, milieu))
    }

    @Test
    fun `droite et gauche reglent la ventilation d un cran`() {
        assertEquals(Action.Ventilation(5), HvacPopup.action(Commande.DROITE, milieu))
        assertEquals(Action.Ventilation(3), HvacPopup.action(Commande.GAUCHE, milieu))
    }

    @Test
    fun `le clic central ferme le popup`() {
        assertEquals(Action.Fermer, HvacPopup.action(Commande.VALIDER, milieu))
    }

    @Test
    fun `en butee il n y a rien a ecrire`() {
        // On ne boucle pas : passer de 32 °C à 16 en poussant sur le volant serait une mauvaise
        // surprise. Et réécrire la valeur déjà en place ne servirait qu'à solliciter la voiture.
        val haut = milieu.copy(temp = 32, fan = 7)
        assertNull(HvacPopup.action(Commande.HAUT, haut))
        assertNull(HvacPopup.action(Commande.DROITE, haut))
        val bas = milieu.copy(temp = 16, fan = 1)
        assertNull(HvacPopup.action(Commande.BAS, bas))
        assertNull(HvacPopup.action(Commande.GAUCHE, bas))
    }

    @Test
    fun `une valeur illisible ne s ecrit pas mais l autre axe reste reglable`() {
        // Partir d'une consigne supposée écrirait une température que personne n'a demandée.
        val sansTemp = milieu.copy(temp = null)
        assertNull(HvacPopup.action(Commande.HAUT, sansTemp))
        assertNull(HvacPopup.action(Commande.BAS, sansTemp))
        assertEquals(Action.Ventilation(5), HvacPopup.action(Commande.DROITE, sansTemp))

        val sansVentilation = milieu.copy(fan = null)
        assertNull(HvacPopup.action(Commande.DROITE, sansVentilation))
        assertEquals(Action.Temperature(23), HvacPopup.action(Commande.HAUT, sansVentilation))
    }

    @Test
    fun `une valeur lue hors bornes revient dans les bornes`() {
        // A9 : 17 et 33 sont les positions LO et HI ; une lecture en dehors ne doit pas s'éloigner
        // davantage, ni rester coincée faute de pouvoir « faire un pas ».
        val hors = milieu.copy(temp = 35, fan = 0)
        assertEquals(Action.Temperature(32), HvacPopup.action(Commande.BAS, hors))
        assertEquals(Action.Ventilation(1), HvacPopup.action(Commande.DROITE, hors))
    }

    @Test
    fun `le clic central ferme meme si rien n est lisible`() {
        val rien = milieu.copy(temp = null, fan = null)
        assertEquals(Action.Fermer, HvacPopup.action(Commande.VALIDER, rien))
    }
}
