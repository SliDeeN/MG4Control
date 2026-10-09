package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus

/**
 * Pop-up HVAC (issues #120 et #125) — ce que fait le joystick droit, sans vue ni véhicule.
 *
 * Ici le joystick ne déplace aucun focus, contrairement aux popups de profils : chaque direction
 * agit directement, comme dans la fenêtre clim d'origine de la voiture.
 *
 * |          | Action           |
 * |----------|------------------|
 * | Haut     | température + 1  |
 * | Bas      | température − 1  |
 * | Droite   | ventilation + 1  |
 * | Gauche   | ventilation − 1  |
 * | Clic     | fermer           |
 */
object HvacPopup {

    sealed class Action {
        data class Temperature(val cible: Int) : Action()
        data class Ventilation(val cible: Int) : Action()
        object Fermer : Action()
    }

    /** Ce qui est lu sur la voiture ; les bornes en viennent aussi. null = illisible. */
    data class Reglages(
        val temp: Int?, val tempMin: Int, val tempMax: Int,
        val fan: Int?, val fanMin: Int, val fanMax: Int,
    )

    /** Action portée par [commande], ou null s'il n'y a rien à écrire (butée, valeur illisible). */
    fun action(commande: JoystickFocus.Commande, reglages: Reglages): Action? = when (commande) {
        JoystickFocus.Commande.HAUT    -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, +1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.BAS     -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, -1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.DROITE  -> pas(reglages.fan, reglages.fanMin, reglages.fanMax, +1)?.let { Action.Ventilation(it) }
        JoystickFocus.Commande.GAUCHE  -> pas(reglages.fan, reglages.fanMin, reglages.fanMax, -1)?.let { Action.Ventilation(it) }
        JoystickFocus.Commande.VALIDER -> Action.Fermer
    }

    /**
     * On CLAMPE, on ne boucle pas : arriver à 32 °C et repartir à 16 en poussant sur le volant
     * serait une très mauvaise surprise. Une cible égale à la valeur lue ne s'écrit pas.
     */
    private fun pas(actuel: Int?, min: Int, max: Int, sens: Int): Int? {
        actuel ?: return null
        return (actuel + sens).coerceIn(min, max).takeIf { it != actuel }
    }
}
