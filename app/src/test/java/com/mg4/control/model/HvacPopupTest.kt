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

    // Bornes d'un ancien SDK : 16–32 °C, ventilation 1–7. Ventilation réglée à la main.
    private val milieu = Reglages(
        temp = 22, tempMin = 16, tempMax = 32, fan = 4, fanMin = 1, fanMax = 7, auto = false)

    // En AUTO, la voiture n'annonce plus un niveau mais 15 (constaté sur SWI133 le 2026-10-09).
    private val enAuto = milieu.copy(fan = 15, auto = true)

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

    // ── Ventilation AUTO : la case centrale de la croix ──────────────────────

    @Test
    fun `le clic central bascule la ventilation AUTO`() {
        assertEquals(Action.Auto(true), HvacPopup.action(Commande.VALIDER, milieu))
        assertEquals(Action.Auto(false), HvacPopup.action(Commande.VALIDER, enAuto))
    }

    @Test
    fun `sans etat AUTO lisible le clic central ne fait rien`() {
        // Écrire « AUTO actif » sans savoir s'il l'est déjà : c'est l'inverse qui pourrait partir.
        assertNull(HvacPopup.action(Commande.VALIDER, milieu.copy(auto = null)))
    }

    @Test
    fun `en AUTO gauche et droite quittent AUTO sans ecrire de niveau`() {
        // Le 15 annoncé en AUTO n'est pas un niveau : en partir écrirait le MAXIMUM, même pour
        // un « moins ». On sort d'AUTO ; la voiture annonce alors son vrai niveau, et c'est le
        // geste suivant qui règle.
        assertEquals(Action.Auto(false), HvacPopup.action(Commande.DROITE, enAuto))
        assertEquals(Action.Auto(false), HvacPopup.action(Commande.GAUCHE, enAuto))
    }

    @Test
    fun `un niveau au-dela du maximum n est pas un niveau a regler`() {
        // AUTO vient d'être coupé mais la voiture annonce encore 15 un instant : rien à écrire.
        val traine = milieu.copy(fan = 15, auto = false)
        assertNull(HvacPopup.action(Commande.DROITE, traine))
        assertNull(HvacPopup.action(Commande.GAUCHE, traine))
    }

    @Test
    fun `le niveau affiche est celui de la voiture sauf en AUTO`() {
        // null = pas de chiffre à montrer : l'écran écrit « AUTO » ou des tirets à la place.
        assertEquals(4, milieu.ventilationReelle)
        assertNull(enAuto.ventilationReelle)
        assertNull(milieu.copy(fan = 15).ventilationReelle)
        assertNull(milieu.copy(fan = null).ventilationReelle)
    }

    // ── Taille de la fenêtre ─────────────────────────────────────────────────
    // Dessinée pour 780 × 390 dp ; l'écran de la voiture fait 1920 × 720 pixels à 1 pixel par dp
    // (mesuré sur une photo, SWI133, 2026-10-09).

    @Test
    fun `sur l ecran de la voiture la fenetre grandit`() {
        // Limitée par la hauteur : 72 % de 720 px pour 390 dp → × 1,33.
        assertEquals(1.33f, HvacPopup.echelle(1920, 720, 1f), 0.01f)
    }

    @Test
    fun `la place prise a l ecran ne depend pas de la densite`() {
        // La densité des firmwares A9 n'est pas connue. Si l'un d'eux annonce 1280 × 480 dp
        // (1,5 pixel par dp), la fenêtre doit couvrir les mêmes pixels, pas déborder.
        val pixelsParDpDeBase = HvacPopup.echelle(1920, 720, 1f) * 1f
        assertEquals(pixelsParDpDeBase, HvacPopup.echelle(1920, 720, 1.5f) * 1.5f, 0.001f)
    }

    @Test
    fun `un ecran moins large limite par la largeur`() {
        // 56 % de 1400 px pour 780 dp → × 1,01, sous la limite de hauteur (× 1,33).
        assertEquals(1.01f, HvacPopup.echelle(1400, 720, 1f), 0.005f)
    }

    @Test
    fun `l echelle reste bornee`() {
        assertEquals(2f, HvacPopup.echelle(8000, 4000, 1f), 0f)
        assertEquals(0.75f, HvacPopup.echelle(400, 300, 1f), 0f)
    }

    @Test
    fun `des mesures absurdes laissent la fenetre telle quelle`() {
        assertEquals(1f, HvacPopup.echelle(0, 0, 1f), 0f)
        assertEquals(1f, HvacPopup.echelle(1920, 720, 0f), 0f)
        assertEquals(1f, HvacPopup.echelle(1920, 720, Float.NaN), 0f)
    }
}
