package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus.Commande
import com.mg4.control.model.HvacPopup.Action
import com.mg4.control.model.HvacPopup.Reglages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // Juste avant, la ventilation était réglée sur 4.
    private val enAuto = milieu.copy(fan = 15, auto = true, dernierNiveau = 4)

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
    }

    // ── Clim éteinte (issue #147) ────────────────────────────────────────────
    // Pas de cran 0 sur la MG4 : « éteint » est l'arrêt de la clim. Éteinte, la voiture annonce
    // une ventilation à 0 (SWI68).

    private val eteinte = milieu.copy(fan = 0, allumee = false)

    @Test
    fun `gauche au cran 1 eteint la clim`() {
        assertEquals(Action.Arret, HvacPopup.action(Commande.GAUCHE, milieu.copy(fan = 1)))
        // Avec ou sans état de marche lisible.
        assertEquals(Action.Arret, HvacPopup.action(Commande.GAUCHE, milieu.copy(fan = 1, allumee = true)))
    }

    @Test
    fun `clim eteinte, gauche ne fait rien`() {
        // Avant : 0 − 1 ramené au minimum écrivait le cran 1, et rallumait la clim.
        assertNull(HvacPopup.action(Commande.GAUCHE, eteinte))
        assertNull(HvacPopup.action(Commande.GAUCHE, milieu.copy(fan = 0)))
    }

    @Test
    fun `clim eteinte, droite la rallume au premier cran`() {
        assertEquals(Action.Ventilation(1), HvacPopup.action(Commande.DROITE, eteinte))
        // Éteinte d'après la voiture, même si elle annonce encore son dernier cran.
        assertEquals(Action.Ventilation(1), HvacPopup.action(Commande.DROITE, milieu.copy(fan = 4, allumee = false)))
    }

    @Test
    fun `en AUTO gauche ne coupe jamais la clim`() {
        // Le dernier niveau réglé était 1 : on sort d'AUTO à 1, sans éteindre.
        assertEquals(Action.Ventilation(1), HvacPopup.action(Commande.GAUCHE, enAuto.copy(dernierNiveau = 1)))
    }

    @Test
    fun `clim eteinte, pas de niveau a montrer`() {
        assertEquals(true, eteinte.eteinte)
        assertNull(eteinte.ventilationReelle)
        assertEquals(false, milieu.eteinte)
    }

    @Test
    fun `le raccourci ventilation moins ne rallume plus la clim eteinte`() {
        // Il ne l'éteint pas non plus au cran 1 : l'action « Clim ON/OFF » existe pour ça.
        assertNull(HvacPopup.pasVentilation(eteinte, -1))
        assertNull(HvacPopup.pasVentilation(milieu.copy(fan = 1), -1))
        assertEquals(1, HvacPopup.pasVentilation(eteinte, +1))
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

    // ── Mode AUTO : la case centrale de la croix ─────────────────────────────
    // L'écran clim d'origine (HvacView, SystemUI SWI165) ne « coupe » jamais AUTO : il en SORT en
    // écrivant un niveau de ventilation, le dernier réglé à la main, plus ou moins un cran.

    @Test
    fun `le clic central active AUTO`() {
        assertEquals(Action.Auto, HvacPopup.action(Commande.VALIDER, milieu))
    }

    @Test
    fun `le clic central en AUTO rend la main au dernier niveau regle`() {
        assertEquals(Action.Ventilation(4), HvacPopup.action(Commande.VALIDER, enAuto))
    }

    @Test
    fun `sans etat AUTO lisible le clic central ne fait rien`() {
        // Activer AUTO sans savoir s'il l'est déjà : sur l'ancien SDK la commande est un APPUI.
        assertNull(HvacPopup.action(Commande.VALIDER, milieu.copy(auto = null)))
    }

    @Test
    fun `en AUTO gauche et droite repartent du dernier niveau regle`() {
        // Surtout pas du 15 annoncé : un « moins » écrirait alors le MAXIMUM.
        assertEquals(Action.Ventilation(5), HvacPopup.action(Commande.DROITE, enAuto))
        assertEquals(Action.Ventilation(3), HvacPopup.action(Commande.GAUCHE, enAuto))
    }

    @Test
    fun `en AUTO le niveau s ecrit meme en butee`() {
        // Hors AUTO, pousser contre la butée n'écrit rien. En AUTO il FAUT écrire : c'est cette
        // écriture qui rend la main, et l'utilisateur vient de demander à régler lui-même.
        assertEquals(Action.Ventilation(7), HvacPopup.action(Commande.DROITE, enAuto.copy(dernierNiveau = 7)))
        assertEquals(Action.Ventilation(1), HvacPopup.action(Commande.GAUCHE, enAuto.copy(dernierNiveau = 1)))
    }

    @Test
    fun `en AUTO sans niveau connu on part du milieu de l echelle`() {
        // Application fraîchement installée, voiture en AUTO depuis toujours : ni le minimum ni
        // le maximum ne feraient un point de départ raisonnable.
        val jamaisVu = enAuto.copy(dernierNiveau = null)
        assertEquals(Action.Ventilation(4), HvacPopup.action(Commande.VALIDER, jamaisVu))
        assertEquals(Action.Ventilation(5), HvacPopup.action(Commande.DROITE, jamaisVu))
        assertEquals(Action.Ventilation(3), HvacPopup.action(Commande.GAUCHE, jamaisVu))
        // Un niveau retenu sur une autre échelle revient dans les bornes de celle-ci.
        assertEquals(Action.Ventilation(7), HvacPopup.action(Commande.VALIDER, enAuto.copy(dernierNiveau = 12)))
    }

    @Test
    fun `le 15 annonce vaut AUTO meme si la voiture ne le dit pas`() {
        // C'est ainsi que les écrans d'origine reconnaissent AUTO : au niveau 15, pas au drapeau.
        val quinze = milieu.copy(fan = 15, auto = false, dernierNiveau = 4)
        assertEquals(true, quinze.enAuto)
        assertEquals(Action.Ventilation(5), HvacPopup.action(Commande.DROITE, quinze))
        assertEquals(Action.Ventilation(4), HvacPopup.action(Commande.VALIDER, quinze))
        assertEquals(false, milieu.enAuto)
        assertEquals(true, enAuto.enAuto)
    }

    @Test
    fun `le niveau affiche est celui de la voiture sauf en AUTO`() {
        // null = pas de chiffre à montrer : l'écran écrit « AUTO » ou des tirets à la place.
        assertEquals(4, milieu.ventilationReelle)
        assertNull(enAuto.ventilationReelle)
        assertNull(milieu.copy(fan = 15).ventilationReelle)
        assertNull(milieu.copy(fan = null).ventilationReelle)
    }

    @Test
    fun `les raccourcis ventilation suivent la meme regle`() {
        // « Clim : ventilation ± » partait du 15 annoncé en AUTO et écrivait le maximum.
        assertEquals(5, HvacPopup.pasVentilation(milieu, +1))
        assertEquals(3, HvacPopup.pasVentilation(enAuto, -1))
        assertNull(HvacPopup.pasVentilation(milieu.copy(fan = 7), +1))
        assertNull(HvacPopup.pasVentilation(milieu.copy(fan = null), +1))
    }

    // ── Taille de la fenêtre ─────────────────────────────────────────────────
    // Carte de 864 × 392 dp, 1108 avec la colonne des chauffages ; l'écran de la voiture annonce
    // 1920 × 720 pixels à 1 pixel par dp (SWI133).

    @Test
    fun `sur l ecran de la voiture la fenetre grandit`() {
        // Limitée par la hauteur : 82 % de 720 px pour 392 dp → × 1,51.
        val echelle = HvacPopup.echelle(1920, 720, 1f)
        assertEquals(0.82f * 720f / 392f, echelle, 0.0005f)
        // La colonne des chauffages élargit la carte sans changer l'échelle : la hauteur limite
        // toujours…
        val avecChauffages = HvacPopup.LARGEUR_DP + HvacPopup.CHAUFFAGES_DP
        assertEquals(echelle, HvacPopup.echelle(1920, 720, 1f, avecChauffages), 0f)
        // … et la carte tient dans ce que le rail de navigation laisse à la fenêtre (≈ 1754 px).
        assertTrue(avecChauffages * echelle < 1754f)
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
        // 96 % de 1400 px pour 1108 dp → × 1,21, sous la limite de hauteur (× 1,51).
        assertEquals(0.96f * 1400f / 1108f, HvacPopup.echelle(1400, 720, 1f, 1108f), 0.0005f)
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

    @Test
    fun `une autre carte donne sa propre echelle`() {
        // Le sélecteur de profils : 1108 × 392 dp, mêmes parts d'écran.
        assertEquals(0.82f * 720f / 392f, HvacPopup.echelle(1920, 720, 1f, 1108f, 392f), 0.0005f)
        // Une carte plus haute grossit moins.
        assertEquals(0.82f * 720f / 500f, HvacPopup.echelle(1920, 720, 1f, 1108f, 500f), 0.0005f)
        // D'autres parts d'écran, pour un pop-up qui doit rester plus discret : ici la largeur limite.
        assertEquals(0.5f * 1920f / 1108f,
            HvacPopup.echelle(1920, 720, 1f, 1108f, 392f, partLargeur = 0.5f, partHauteur = 0.5f), 0.0005f)
    }

    // ── Filet : la fenêtre réellement accordée ───────────────────────────────
    // L'écran annoncé n'est pas la place disponible : barre d'état et rail de navigation en
    // prennent une part (fenêtre d'environ 1754 × 650 px sur SWI133, d'après la photo).

    @Test
    fun `une carte qui tient n est pas reduite`() {
        assertEquals(1f, HvacPopup.reduction(1754, 650, 1669, 590, marge = 12), 0f)
        // Juste à la marge : elle tient encore.
        assertEquals(1f, HvacPopup.reduction(1754, 650, 1730, 626, marge = 12), 0f)
    }

    @Test
    fun `une carte trop large ou trop haute est reduite d un bloc`() {
        // Trop large : (1500 − 2 × 12) / 1669.
        assertEquals(1476f / 1669f, HvacPopup.reduction(1500, 650, 1669, 590, marge = 12), 0.0001f)
        // Trop haute : (560 − 2 × 12) / 590.
        assertEquals(536f / 590f, HvacPopup.reduction(1754, 560, 1669, 590, marge = 12), 0.0001f)
        // Les deux : la plus sévère des deux contraintes.
        assertEquals(536f / 590f, HvacPopup.reduction(1700, 560, 1669, 590, marge = 12), 0.0001f)
    }

    @Test
    fun `sans mesures fiables on ne reduit pas`() {
        assertEquals(1f, HvacPopup.reduction(0, 0, 1669, 590, marge = 12), 0f)
        assertEquals(1f, HvacPopup.reduction(1754, 650, 0, 0, marge = 12), 0f)
        // Fenêtre plus petite que ses marges : rien de sensé à en tirer.
        assertEquals(1f, HvacPopup.reduction(20, 20, 1669, 590, marge = 12), 0f)
    }

    // ── Jauge de température ─────────────────────────────────────────────────

    @Test
    fun `la jauge montre ou se trouve la temperature entre ses bornes`() {
        assertEquals(0f, HvacPopup.part(17, 17, 33)!!, 0f)
        assertEquals(0.25f, HvacPopup.part(21, 17, 33)!!, 0.0001f)
        assertEquals(1f, HvacPopup.part(33, 17, 33)!!, 0f)
    }

    @Test
    fun `une valeur hors bornes reste sur la jauge`() {
        assertEquals(0f, HvacPopup.part(10, 17, 33)!!, 0f)
        assertEquals(1f, HvacPopup.part(40, 17, 33)!!, 0f)
    }

    @Test
    fun `sans valeur ou sans etendue la jauge reste vide`() {
        assertNull(HvacPopup.part(null, 17, 33))
        // Bornes illisibles rendues égales, ou inversées : rien à situer.
        assertNull(HvacPopup.part(20, 20, 20))
        assertNull(HvacPopup.part(20, 33, 17))
    }
}
