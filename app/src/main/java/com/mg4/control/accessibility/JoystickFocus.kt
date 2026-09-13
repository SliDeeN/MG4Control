package com.mg4.control.accessibility

import kotlin.math.abs

/**
 * Navigation au joystick droit du volant dans un popup — la partie sans vue.
 *
 * Revenue en septembre 2026 grâce à [KeyCaptureService]. La première version (juin) marchait mais
 * le joystick changeait AUSSI le volume et la piste : SystemUI réagit au broadcast
 * `hardkey.report`, qu'un récepteur ne peut pas empêcher. Le service d'accessibilité, lui, avale
 * la touche assez tôt pour que ce broadcast ne parte pas — vérifié sur véhicule (SWI133).
 *
 * La grille est décrite par les centres horizontaux de ses cellules, ligne par ligne : c'est tout
 * ce qu'il faut pour choisir une cible, et ça se teste sans instancier une seule vue.
 */
object JoystickFocus {

    enum class Commande { HAUT, BAS, GAUCHE, DROITE, VALIDER }

    data class Position(val ligne: Int, val colonne: Int)

    /** Commande portée par une touche du joystick droit, ou null pour toute autre touche. */
    fun commande(keyCode: Int): Commande? = when (keyCode) {
        297  -> Commande.HAUT
        298  -> Commande.BAS
        299  -> Commande.GAUCHE
        300  -> Commande.DROITE
        301  -> Commande.VALIDER
        else -> null
    }

    /**
     * Position atteinte depuis [depuis] par [commande].
     *
     * Pas de rebouclage aux bords : sauter du bas du popup vers le haut désoriente en conduisant.
     * En changeant de ligne, la cible est la cellule la plus proche HORIZONTALEMENT, pas celle de
     * même indice — les lignes n'ont pas le même nombre de cellules (voir le test).
     *
     * @param centres centres horizontaux des cellules, ligne par ligne ; aucune ligne vide.
     */
    fun deplacer(centres: List<List<Int>>, depuis: Position, commande: Commande): Position {
        require(centres.isNotEmpty() && centres.none { it.isEmpty() }) { "grille vide" }

        // Une ligne peut avoir perdu des cellules depuis le dernier appui : on repart d'une
        // position valide plutôt que de lever une exception au volant.
        val ligne   = depuis.ligne.coerceIn(0, centres.lastIndex)
        val colonne = depuis.colonne.coerceIn(0, centres[ligne].lastIndex)

        return when (commande) {
            Commande.GAUCHE  -> Position(ligne, (colonne - 1).coerceAtLeast(0))
            Commande.DROITE  -> Position(ligne, (colonne + 1).coerceAtMost(centres[ligne].lastIndex))
            Commande.VALIDER -> Position(ligne, colonne)
            Commande.HAUT, Commande.BAS -> {
                val cible = (ligne + if (commande == Commande.HAUT) -1 else 1)
                    .coerceIn(0, centres.lastIndex)
                if (cible == ligne) return Position(ligne, colonne)
                val x = centres[ligne][colonne]
                // minByOrNull garde la PREMIÈRE à égalité : la cellule de gauche, par convention.
                val plusProche = centres[cible].indices.minByOrNull { abs(centres[cible][it] - x) }!!
                Position(cible, plusProche)
            }
        }
    }
}
