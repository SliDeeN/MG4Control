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
     * Faux quand le mode de déclenchement retient l'automatisation.
     *
     * Deux faits sont suivis depuis le démarrage de l'application : [evaluated], une température
     * lisible a déjà été évaluée ; [triggered], une règle a déjà été appliquée. Le premier ferme
     * la porte en mode « au démarrage seulement », le second en mode « une fois par démarrage ».
     */
    fun allowed(config: ClimateAutomationSettings.Config, evaluated: Boolean, triggered: Boolean): Boolean =
        when (config.trigger) {
            ClimateAutomationSettings.Trigger.START_ONLY -> !evaluated
            ClimateAutomationSettings.Trigger.ONCE_PER_START -> !triggered
            ClimateAutomationSettings.Trigger.EVERY_READY -> true
        }

    /**
     * Vrai quand l'automatisation s'efface devant le profil : il a été appliqué DEPUIS le démarrage
     * de l'application et il règle lui-même la climatisation.
     *
     * [sessionProfileWritesClimate] est nul tant qu'aucun profil n'a été appliqué depuis le
     * démarrage. Le profil actif, lui, est mémorisé d'une session à l'autre : au démarrage du
     * service il désigne encore celui du trajet précédent, qui ne sera peut-être pas appliqué
     * cette fois. S'effacer devant lui laissait la climatisation sans personne pour la régler —
     * et, en mode « au démarrage seulement », consommait l'unique occasion (issue #121).
     */
    fun yieldsToProfile(sessionProfileWritesClimate: Boolean?): Boolean = sessionProfileWritesClimate == true

    /**
     * Vrai quand l'évaluation du démarrage doit attendre le profil du démarrage : un profil
     * enregistré règle la climatisation — lui seul peut avoir priorité — et aucun profil n'a encore
     * été appliqué depuis le démarrage de l'application. Sinon il n'y a personne à attendre.
     */
    fun waitsForStartProfile(anyProfileWritesClimate: Boolean, appliedThisSession: Boolean): Boolean =
        anyProfileWritesClimate && !appliedThisSession

    /**
     * Vrai quand une évaluation vaut décision : la température a pu être lue. Illisible, elle ne
     * dit ni « chaud » ni « pas chaud » — l'occasion du démarrage n'est pas consommée.
     */
    fun decides(temp: Float?): Boolean = temp != null && !temp.isNaN()
}
