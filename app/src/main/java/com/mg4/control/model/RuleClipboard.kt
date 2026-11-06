package com.mg4.control.model

/**
 * Copier / Couper / Coller dans l'éditeur de Mes automatisations (2026-11-06) : ce qui est copié,
 * et où on a le droit de le coller. Sans Android, pour être testé.
 *
 * Un seul élément à la fois : un déclencheur, une condition (ou un groupe, avec son contenu), une
 * action (ou un bloc Si / Répéter, avec son contenu). Il reste en mémoire tant que l'appli tourne :
 * copié dans une automatisation, il se colle dans une autre.
 */
object RuleClipboard {

    @Volatile var content: Any? = null

    val trigger: RuleTrigger? get() = content as? RuleTrigger
    val condition: RuleCondition? get() = content as? RuleCondition
    val action: RuleAction? get() = content as? RuleAction

    /**
     * Niveaux de groupes qu'une condition ajoute là où on la pose : 0 pour une condition simple,
     * 1 pour un groupe de conditions simples, 2 s'il contient lui-même un groupe…
     */
    fun conditionDepth(c: RuleCondition?, guard: Int = 0): Int {
        if (c == null || !c.isGroup || guard > 16) return 0
        return 1 + (c.children.orEmpty().maxOfOrNull { conditionDepth(it, guard + 1) } ?: 0)
    }

    /** Niveaux qu'ajoute la racine d'un bloc Si : ses sous-groupes seulement (la racine n'en est pas un). */
    private fun rootDepth(c: RuleCondition?): Int =
        c?.children.orEmpty().maxOfOrNull { conditionDepth(it) } ?: 0

    /** Niveaux de blocs qu'une action ajoute : 0 pour une action simple, 1 pour un Si ou un Répéter… */
    fun actionDepth(a: RuleAction?, guard: Int = 0): Int {
        if (a == null || guard > 16) return 0
        fun liste(l: List<RuleAction>?) = l.orEmpty().maxOfOrNull { actionDepth(it, guard + 1) } ?: 0
        return when (a.type) {
            RuleAction.Type.IF -> 1 + maxOf(liste(a.then), liste(a.otherwise), rootDepth(a.condition))
            RuleAction.Type.REPEAT -> 1 + liste(a.then)
            else -> 0
        }
    }

    /**
     * Une condition copiée tient-elle dans un groupe de niveau [depth] ? L'éditeur propose un
     * sous-groupe tant que `depth < maxDepth - 1` : la même règle, appliquée à tout ce qu'on colle.
     */
    fun fitsCondition(c: RuleCondition, depth: Int, maxDepth: Int): Boolean = depth + conditionDepth(c) <= maxDepth - 1

    /** Une action copiée tient-elle dans une liste d'actions de niveau [depth] ? Même règle que pour ajouter un bloc. */
    fun fitsAction(a: RuleAction, depth: Int, maxDepth: Int): Boolean = depth + actionDepth(a) <= maxDepth - 1

    /** L'action (ou une action de son bloc) lance-t-elle l'automatisation [ruleId] ? Coller ça dans elle-même la ferait boucler. */
    fun runsRule(a: RuleAction, ruleId: String, guard: Int = 0): Boolean {
        if (guard > 16) return false
        if (a.type == RuleAction.Type.RUN_RULE && a.text == ruleId) return true
        return (a.then.orEmpty() + a.otherwise.orEmpty()).any { runsRule(it, ruleId, guard + 1) }
    }
}
