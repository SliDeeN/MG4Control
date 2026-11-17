package com.mg4.control.model

/** Ce qu'une automatisation désigne par identifiant : propre à une voiture, à refaire à l'import. */
enum class RefKind { RULE, PLACE, PROFILE, SOUND, SHORTCUT, ABRP }

data class RuleRef(val kind: RefKind, val id: String) {
    /** Clé de [CustomRule.exportNames] : « PLACE:<id> ». */
    val key: String get() = "${kind.name}:$id"
}

/**
 * Liens d'une automatisation vers d'autres objets (2026-11-14, revue du moteur, point 3) : autres
 * automatisations (Lancer, Arrêter, Activer / Couper), lieux, profils, sons et raccourcis perso.
 * Tous sont désignés par identifiant ; à l'import, ces identifiants ne valent plus rien sur une
 * autre voiture, et sur la même ils désignent les originaux au lieu des copies. Partie sans Android.
 */
object RuleRefs {

    private const val MAX_DEPTH = RuleLogic.MAX_DEPTH + 2

    /** Préfixe d'un lien laissé sans cible à l'import : ne correspond à aucun objet, jamais. */
    const val UNRESOLVED = "unresolved:"

    /** Les liens de [rule], sans doublon, dans l'ordre où ils apparaissent. */
    fun of(rule: CustomRule): List<RuleRef> {
        val out = LinkedHashSet<RuleRef>()
        rewrite(rule) { out += it; null }
        return out.toList()
    }

    /**
     * [rule] dont chaque lien passe par [map] : un nouvel identifiant, ou `null` pour le garder.
     * Blocs Si, Sinon et Attendre que compris, et conditions des déclencheurs.
     */
    fun rewrite(rule: CustomRule, map: (RuleRef) -> String?): CustomRule {
        fun cond(c: RuleCondition?, d: Int): RuleCondition? {
            if (c == null || d > MAX_DEPTH) return c
            val texte = when {
                c.type == RuleCondition.Type.PLACE && c.text != null -> map(RuleRef(RefKind.PLACE, c.text)) ?: c.text
                c.type == RuleCondition.Type.COMPARE && c.signal == RuleSignal.PROFILE && c.text != null ->
                    map(RuleRef(RefKind.PROFILE, c.text)) ?: c.text
                else -> c.text
            }
            val enfants = c.children?.map { cond(it, d + 1) ?: it }
            return if (texte == c.text && enfants == c.children) c else c.copy(text = texte, children = enfants)
        }
        fun actions(l: List<RuleAction>?, d: Int): List<RuleAction>? {
            if (l == null || d > MAX_DEPTH) return l
            return l.map { a ->
                var b = a
                when (a.type) {
                    RuleAction.Type.RUN_RULE, RuleAction.Type.RULE_STOP, RuleAction.Type.RULE_ENABLE ->
                        a.text?.let { id -> map(RuleRef(RefKind.RULE, id))?.let { b = b.copy(text = it) } }
                    RuleAction.Type.CUSTOM_SHORTCUT ->
                        a.text?.let { id -> map(RuleRef(RefKind.SHORTCUT, id))?.let { b = b.copy(text = it) } }
                    RuleAction.Type.PROFILE ->
                        a.profileId?.let { id -> map(RuleRef(RefKind.PROFILE, id))?.let { b = b.copy(profileId = it) } }
                    // Compte ABRP (2026-11-14) : ses clés ne voyagent pas avec l'export, seul son nom.
                    RuleAction.Type.ABRP_START ->
                        a.text?.let { id -> map(RuleRef(RefKind.ABRP, id))?.let { b = b.copy(text = it) } }
                    // « Naviguer vers » : un lieu enregistré, ou des coordonnées « lat,lng » laissées telles quelles.
                    RuleAction.Type.NAVIGATE -> a.text?.takeUnless { isCoordinates(it) }?.let { id ->
                        map(RuleRef(RefKind.PLACE, id))?.let { b = b.copy(text = it) }
                    }
                    RuleAction.Type.PLAY_SOUND -> a.key?.takeIf { it.startsWith(RuleLogic.RULE_SOUND) }?.let { k ->
                        map(RuleRef(RefKind.SOUND, k.removePrefix(RuleLogic.RULE_SOUND)))?.let { b = b.copy(key = RuleLogic.RULE_SOUND + it) }
                    }
                    else -> {}
                }
                val c = cond(b.condition, d + 1)
                val oui = actions(b.then, d + 1)
                val non = actions(b.otherwise, d + 1)
                if (c === b.condition && oui === b.then && non === b.otherwise) b
                else b.copy(condition = c, then = oui, otherwise = non)
            }
        }
        return rule.copy(
            triggers = rule.triggers.map { t -> cond(t.condition, 0).let { if (it === t.condition) t else t.copy(condition = it) } },
            condition = cond(rule.condition, 0) ?: rule.condition,
            actions = actions(rule.actions, 0) ?: rule.actions,
        )
    }

    /** « 48.85,2.35 » : des coordonnées, pas un identifiant de lieu. */
    fun isCoordinates(text: String): Boolean =
        text.split(",").let { p -> p.size == 2 && p.all { it.trim().toDoubleOrNull() != null } }

    /** Noms des objets liés, pour l'export : relier par le nom sur une autre voiture. */
    fun names(rule: CustomRule, nameOf: (RuleRef) -> String?): Map<String, String> =
        of(rule).mapNotNull { r -> nameOf(r)?.takeIf { it.isNotBlank() }?.let { r.key to it } }.toMap()

    /** Liens introuvables sur cette voiture (carte « ⚠ N liens à réassocier »). */
    fun missing(rule: CustomRule, exists: (RuleRef) -> Boolean): List<RuleRef> = of(rule).filterNot(exists)

    enum class Status { FROM_FILE, BY_NAME, MISSING }

    /** Un lien remarquable d'une automatisation importée ; [ruleId] : son NOUVEL identifiant. */
    data class Link(val ruleId: String, val ruleName: String, val ref: RuleRef, val name: String?, val status: Status, val target: String?)

    data class Plan(val rules: List<CustomRule>, val links: List<Link>)

    /**
     * Prépare un import : nouveaux identifiants ([newId]), coupées, et chaque lien refait.
     * Vers une automatisation du fichier → sa copie ; vers un objet de cette voiture ([exists]) →
     * gardé ; sinon relié par le nom exporté ([byName]), ou noté manquant (gardé tel quel).
     */
    fun planImport(
        rules: List<CustomRule>,
        newId: (String) -> String,
        exists: (RuleRef) -> Boolean,
        byName: (RefKind, String) -> String?,
        /**
         * Automatisations du fichier écartées (liste pleine) : un lien vers l'une d'elles demande
         * toujours un choix — jamais l'original de cette voiture en silence, ni un homonyme.
         */
        leftOut: Set<String> = emptySet(),
    ): Plan {
        val ids = rules.associate { it.id to newId(it.id) }
        val noms = rules.associate { it.id to it.name }
        val liens = LinkedHashMap<Pair<String, RuleRef>, Link>()
        val sortie = rules.map { r ->
            val nouvel = ids.getValue(r.id)
            val exportes = r.exportNames.orEmpty()
            val refaite = rewrite(r) { ref ->
                val nom = exportes[ref.key]
                val cle = nouvel to ref
                when {
                    ref.kind == RefKind.RULE && ref.id in ids -> ids.getValue(ref.id).also {
                        liens[cle] = Link(nouvel, r.name, ref, nom ?: noms[ref.id], Status.FROM_FILE, it)
                    }
                    ref.kind == RefKind.RULE && ref.id in leftOut -> {
                        // Cible NON RÉSOLUE jusqu'au choix (2026-11-14) : l'ancien identifiant viserait
                        // l'original de cette voiture si la règle était activée sans passer par le rapport.
                        val nonResolu = UNRESOLVED + ref.id
                        liens[cle] = Link(nouvel, r.name, RuleRef(ref.kind, nonResolu), nom, Status.MISSING, null)
                        nonResolu
                    }
                    exists(ref) -> null
                    else -> {
                        val trouve = nom?.let { byName(ref.kind, it) }
                        liens[cle] = Link(nouvel, r.name, ref, nom, if (trouve != null) Status.BY_NAME else Status.MISSING, trouve)
                        trouve
                    }
                }
            }
            refaite.copy(id = nouvel, enabled = false, exportNames = null)
        }
        return Plan(sortie, liens.values.toList())
    }
}
