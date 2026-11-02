package com.mg4.control.model

/**
 * Modèles de Mes automatisations : le moyen d'en créer une tant que l'éditeur n'existe pas
 * (étape 1, 2026-11-02), puis un point de départ. Les noms et les messages viennent des chaînes
 * traduites ; les valeurs écrites sont celles de l'API externe (`ExternalApi.SET_*`).
 */
object CustomRuleTemplates {

    enum class Template { STARTUP_MESSAGE, WINTER_MORNING, HOT_DAY, WINDOWS_ON_LEAVING }

    private fun compare(s: RuleSignal, op: RuleOp, number: Double? = null, text: String? = null) =
        RuleCondition(RuleCondition.Type.COMPARE, signal = s, op = op, number = number, text = text)

    private fun set(key: String, value: String) = RuleAction(RuleAction.Type.SET, key = key, value = value)

    private fun tous(vararg c: RuleCondition) = RuleCondition(RuleCondition.Type.ALL, children = c.toList())

    /** [message] : le texte affiché par le modèle, quand il en affiche un. */
    fun build(t: Template, name: String, message: String): CustomRule = when (t) {
        // Le plus simple, pour vérifier que le moteur tourne : un message quelques secondes
        // après le démarrage de l'écran.
        Template.STARTUP_MESSAGE -> CustomRule(
            name = name,
            triggers = listOf(RuleTrigger(RuleTrigger.Type.EVENT, event = RuleEvent.APP_START)),
            actions = listOf(
                RuleAction(RuleAction.Type.WAIT, seconds = 5),
                RuleAction(RuleAction.Type.NOTIFY, text = message),
            ),
        )
        // La maquette du 2026-11-02 : en semaine, froid ou tôt le matin ; sièges et volant sous
        // 0 °C, sinon la clim.
        Template.WINTER_MORNING -> CustomRule(
            name = name,
            triggers = listOf(RuleTrigger(RuleTrigger.Type.BECOMES, condition = compare(RuleSignal.READY, RuleOp.IS_TRUE))),
            condition = tous(
                RuleCondition(RuleCondition.Type.WEEKDAYS, days = listOf(1, 2, 3, 4, 5)),
                RuleCondition(RuleCondition.Type.ANY, children = listOf(
                    compare(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 5.0),
                    RuleCondition(RuleCondition.Type.TIME_RANGE, from = 5 * 60, to = 9 * 60),
                )),
            ),
            actions = listOf(
                RuleAction(
                    RuleAction.Type.IF,
                    condition = compare(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 0.0),
                    then = listOf(set("seat_heat_left", "3"), set("seat_heat_right", "3"), set("steering_heat", "1")),
                    otherwise = listOf(set("hvac_power", "1"), set("hvac_temp", "21"), set("hvac_auto", "1")),
                ),
                RuleAction(RuleAction.Type.WAIT, seconds = 30),
                RuleAction(RuleAction.Type.NOTIFY, text = message),
            ),
            cooldownMinutes = 30,
        )
        // Plus de 27 °C dehors pendant une minute, voiture en marche et clim éteinte.
        Template.HOT_DAY -> CustomRule(
            name = name,
            triggers = listOf(RuleTrigger(
                RuleTrigger.Type.BECOMES, condition = compare(RuleSignal.OUTSIDE_TEMP, RuleOp.ABOVE, 27.0), forSeconds = 60,
            )),
            condition = tous(compare(RuleSignal.HVAC_ON, RuleOp.IS_FALSE)),
            actions = listOf(set("hvac_power", "1"), set("ac", "1"), set("hvac_temp", "20"),
                RuleAction(RuleAction.Type.NOTIFY, text = message)),
            cooldownMinutes = 60,
            onlyWhenReady = true,
        )
        // Sortie de READY en P : toutes les vitres fermées (raccourci id 37).
        Template.WINDOWS_ON_LEAVING -> CustomRule(
            name = name,
            triggers = listOf(RuleTrigger(RuleTrigger.Type.BECOMES, condition = compare(RuleSignal.READY, RuleOp.IS_FALSE))),
            condition = tous(compare(RuleSignal.GEAR, RuleOp.EQUALS, text = RuleSignal.GEAR_PARK)),
            actions = listOf(RuleAction(RuleAction.Type.SHORTCUT, shortcut = "WINDOWS_CLOSE_ALL")),
        )
    }
}
