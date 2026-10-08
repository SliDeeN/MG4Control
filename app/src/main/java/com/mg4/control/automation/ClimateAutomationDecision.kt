package com.mg4.control.automation

/** Décision pure de l'automatisation climatisation (testable sans Android). */
object ClimateAutomationDecision {

    /** Règle retenue, ou NONE si rien ne s'applique. */
    enum class Outcome { NONE, HOT, COLD }

    /**
     * Choisit la règle à appliquer d'après la température extérieure.
     *
     * Conditions inclusives : chaud si `temp >= seuilChaud`, froid si `temp <= seuilFroid`.
     * Si les deux se déclenchent (seuils qui se chevauchent — configuration incohérente), on
     * retient **CHAUD** de façon déterministe plutôt que de dépendre d'un ordre implicite.
     */
    fun evaluate(config: ClimateAutomationSettings.Config, temp: Float?): Outcome = when {
        !config.enabled              -> Outcome.NONE
        temp == null || temp.isNaN() -> Outcome.NONE
        config.hot.active  && temp >= config.hot.threshold.toFloat()  -> Outcome.HOT
        config.cold.active && temp <= config.cold.threshold.toFloat() -> Outcome.COLD
        else                         -> Outcome.NONE
    }

    /**
     * Faux quand l'option « une seule fois par démarrage de la voiture » retient l'automatisation.
     *
     * [alreadyTriggered] dit si une règle a déjà été **appliquée** depuis que l'application
     * tourne — pas seulement évaluée : un premier contact entre les deux seuils n'applique rien
     * et laisse l'essai disponible pour le contact suivant.
     */
    fun allowed(config: ClimateAutomationSettings.Config, alreadyTriggered: Boolean): Boolean =
        !(config.oncePerStart && alreadyTriggered)
}
