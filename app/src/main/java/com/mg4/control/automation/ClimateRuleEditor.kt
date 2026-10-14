package com.mg4.control.automation

/**
 * Carte « A/C selon la température » : ce que ses curseurs et ses boutons enregistrent — la partie
 * sans vue, donc testable.
 *
 * La carte est passée des champs de saisie aux curseurs sans toucher à [ClimateAutomationSettings] :
 * mêmes clés, mêmes valeurs, aucune reprise à faire chez ceux qui l'avaient déjà réglée.
 */
object ClimateRuleEditor {

    // ── Recirculation ────────────────────────────────────────────────────────
    // Un seul choix à quatre positions remplace la case « Forcer la recirculation » et ses trois
    // boutons : 0 = inchangée (la case décochée), puis 1 = intérieur, 2 = extérieur, 3 = auto —
    // soit le mode de la voiture (0, 1, 2) décalé d'un rang.

    /** Position à allumer pour les réglages enregistrés. */
    fun recircChoice(force: Boolean, mode: Int): Int = if (force) mode.coerceIn(0, 2) + 1 else 0

    /** Vrai si le choix demande à piloter la recirculation. */
    fun recircForce(choice: Int): Boolean = choice != 0

    /**
     * Mode à enregistrer, ou `null` pour « inchangée » : le mode déjà enregistré reste alors en
     * place, comme quand on décochait la case.
     */
    fun recircMode(choice: Int): Int? = if (choice in 1..3) choice - 1 else null

    // ── Seuils ───────────────────────────────────────────────────────────────
    // Le seuil froid reste sous le seuil chaud. Croisés, ils déclenchent les deux règles à la
    // fois et [ClimateAutomationDecision] retient « chaud » — ce que rien à l'écran ne disait.

    /** Seuil chaud à retenir quand l'utilisateur vise [wanted], le seuil froid étant à [cold]. */
    fun hotThreshold(wanted: Int, cold: Int): Int =
        maxOf(wanted, cold + 1).coerceIn(ClimateAutomationSettings.MIN_TEMP, ClimateAutomationSettings.MAX_TEMP)

    /** Seuil froid à retenir quand l'utilisateur vise [wanted], le seuil chaud étant à [hot]. */
    fun coldThreshold(wanted: Int, hot: Int): Int =
        minOf(wanted, hot - 1).coerceIn(ClimateAutomationSettings.MIN_TEMP, ClimateAutomationSettings.MAX_TEMP)

    // ── Curseurs ─────────────────────────────────────────────────────────────

    /**
     * Valeur à poser sur un curseur borné à [min]..[max]. Les bornes sont celles de la voiture
     * (16–32 °C et 7 crans sur l'ancien SDK, 17–33 °C et 11 crans sur A9) : une valeur saisie
     * autrefois peut en sortir, et un Slider posé hors de ses bornes fait planter l'écran.
     */
    fun sliderValue(stored: Int, min: Int, max: Int): Int = stored.coerceIn(min, maxOf(min, max))

    /** Seuil tel qu'il se lit à droite de son curseur : « ≥ 28 °C » ou « ≤ 5 °C ». */
    fun thresholdLabel(hot: Boolean, value: Int): String = (if (hot) "\u2265 " else "\u2264 ") + value + " °C"

    // ── Boutons de recirculation ─────────────────────────────────────────────

    /** Largeur en dessous de laquelle un bouton ne tient plus son libellé dans les sept langues. */
    const val MIN_BUTTON_DP = 130f

    /**
     * Nombre de boutons par ligne pour une rangée de [rowWidthDp] : les quatre côte à côte quand
     * la place le permet, sinon trois — « Auto » passe alors dessous, sous « Inchangée ».
     */
    fun recircColumns(rowWidthDp: Float): Int = if (rowWidthDp >= 4 * MIN_BUTTON_DP) 4 else 3
}
