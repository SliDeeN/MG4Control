package com.mg4.control.accessibility

import com.mg4.control.accessibility.JoystickFocus.Commande
import com.mg4.control.accessibility.JoystickFocus.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Déplacement du focus au joystick dans le popup de profils — logique pure, aucune vue.
 *
 * La grille du popup est IRRÉGULIÈRE : 3 préréglages de luminosité, 1 curseur, des lignes de
 * 2 profils (la dernière pouvant n'en compter qu'un), puis 3 boutons en bas. Un déplacement par
 * indice de colonne enverrait « profil de droite ↓ » sur le bouton du MILIEU de la barre du bas,
 * ce qui ne correspond pas à ce que l'œil attend ; d'où la colonne choisie par proximité.
 */
class JoystickFocusTest {

    // Écran de 1000 px : préréglages, curseur, deux lignes de profils (la 2e impaire), barre du bas.
    private val grille = listOf(
        listOf(170, 500, 830),   // Nuit / Moyen / Jour
        listOf(500),             // curseur
        listOf(250, 750),        // profil 1 / profil 2
        listOf(250),             // profil 3 (seul sur sa ligne)
        listOf(120, 500, 880),   // Fermer / Éteindre / Ouvrir l'app
    )

    @Test
    fun `les touches du joystick droit donnent les cinq commandes`() {
        assertEquals(Commande.HAUT,    JoystickFocus.commande(297))
        assertEquals(Commande.BAS,     JoystickFocus.commande(298))
        assertEquals(Commande.GAUCHE,  JoystickFocus.commande(299))
        assertEquals(Commande.DROITE,  JoystickFocus.commande(300))
        assertEquals(Commande.VALIDER, JoystickFocus.commande(301))
    }

    @Test
    fun `les autres touches ne sont pas des commandes de navigation`() {
        // Les étoiles doivent continuer d'agir normalement pendant que le popup est ouvert.
        assertNull(JoystickFocus.commande(17))
        assertNull(JoystickFocus.commande(286))
        assertNull(JoystickFocus.commande(18))
    }

    @Test
    fun `gauche et droite restent sur la ligne et butent aux bords`() {
        assertEquals(Position(2, 1), JoystickFocus.deplacer(grille, Position(2, 0), Commande.DROITE))
        assertEquals(Position(2, 1), JoystickFocus.deplacer(grille, Position(2, 1), Commande.DROITE))
        assertEquals(Position(2, 0), JoystickFocus.deplacer(grille, Position(2, 0), Commande.GAUCHE))
    }

    @Test
    fun `haut et bas butent sur la premiere et la derniere ligne`() {
        // Pas de rebouclage : sauter du haut vers le bas du popup désoriente en conduisant.
        assertEquals(Position(0, 1), JoystickFocus.deplacer(grille, Position(0, 1), Commande.HAUT))
        assertEquals(Position(4, 2), JoystickFocus.deplacer(grille, Position(4, 2), Commande.BAS))
    }

    @Test
    fun `changer de ligne vise la cellule la plus proche horizontalement`() {
        // Préréglage « Jour » ↓ : une seule cellule, le curseur.
        assertEquals(Position(1, 0), JoystickFocus.deplacer(grille, Position(0, 2), Commande.BAS))
        // Curseur ↓ : 500 est à égale distance de 250 et 750 → la première, à gauche.
        assertEquals(Position(2, 0), JoystickFocus.deplacer(grille, Position(1, 0), Commande.BAS))
        // Profil 2 (droite) ↑ : le curseur ; puis ↑ : « Moyen », au-dessus du curseur, et non
        // « Jour » qui serait au-dessus du profil de droite.
        assertEquals(Position(1, 0), JoystickFocus.deplacer(grille, Position(2, 1), Commande.HAUT))
        assertEquals(Position(0, 1), JoystickFocus.deplacer(grille, Position(1, 0), Commande.HAUT))
    }

    @Test
    fun `la ligne impaire recueille les deux colonnes`() {
        assertEquals(Position(3, 0), JoystickFocus.deplacer(grille, Position(2, 1), Commande.BAS))
        assertEquals(Position(3, 0), JoystickFocus.deplacer(grille, Position(2, 0), Commande.BAS))
    }

    @Test
    fun `descendre d un profil de droite vers la barre du bas vise Ouvrir l app`() {
        val sansLigneImpaire = grille.filterIndexed { i, _ -> i != 3 }
        assertEquals(Position(3, 2),
            JoystickFocus.deplacer(sansLigneImpaire, Position(2, 1), Commande.BAS))
    }

    @Test
    fun `valider ne deplace pas le focus`() {
        assertEquals(Position(2, 1), JoystickFocus.deplacer(grille, Position(2, 1), Commande.VALIDER))
    }

    @Test
    fun `une position hors grille est ramenee dans la grille`() {
        // Cas réel : une ligne perd une cellule (bouton masqué) entre deux appuis.
        assertEquals(Position(3, 0), JoystickFocus.deplacer(grille, Position(3, 5), Commande.GAUCHE))
    }
}
