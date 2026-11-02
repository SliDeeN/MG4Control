package com.mg4.control.model

import java.util.Locale

/**
 * Les mots de Mes automatisations, fournis par l'écran (chaînes traduites, noms des profils et des
 * téléphones). Le modèle ne fait que les assembler ([RuleText]).
 */
interface RuleWords {
    fun signal(s: RuleSignal): String
    /** Unité collée après un nombre, espace comprise (« °C » → " °C"), ou "". */
    fun unit(s: RuleSignal): String
    /** Valeur d'un signal texte : « P », nom d'un profil, d'un téléphone, d'un mode de conduite. */
    fun value(s: RuleSignal, text: String): String
    fun yes(label: String): String
    fun no(label: String): String
    fun event(e: RuleEvent): String
    fun forSeconds(text: String, seconds: Int): String
    fun at(time: String): String
    val and: String
    val or: String
    fun none(text: String): String
    fun range(from: String, to: String): String
    fun days(days: List<Int>): String
    val always: String
    fun set(key: String, value: String): String
    fun shortcut(name: String): String
    fun profile(id: String): String
    fun waitFor(seconds: Int): String
    fun ifThen(condition: String): String
    fun notify(text: String): String
    val stop: String
    val unknown: String
}

/** Les phrases d'une automatisation, pour sa carte et (étape 2) les lignes de l'éditeur. */
class RuleText(private val w: RuleWords) {

    fun time(minute: Int): String = String.format(Locale.US, "%d:%02d", minute / 60, minute % 60)

    fun trigger(t: RuleTrigger): String = when (t.type) {
        RuleTrigger.Type.EVENT -> t.event?.let { w.event(it) } ?: w.unknown
        RuleTrigger.Type.BECOMES -> {
            val c = condition(t.condition)
            if (t.forSeconds > 0) w.forSeconds(c, t.forSeconds) else c
        }
        RuleTrigger.Type.TIME -> t.at?.let { w.at(time(it)) } ?: w.unknown
        null -> w.unknown
    }

    /** [nested] : une sous-partie, mise entre parenthèses si elle en assemble plusieurs. */
    fun condition(c: RuleCondition?, nested: Boolean = false, depth: Int = 0): String {
        if (c == null || depth > RuleLogic.MAX_DEPTH) return w.unknown
        val enfants = c.children.orEmpty()
        return when (c.type) {
            RuleCondition.Type.ALL, RuleCondition.Type.ANY -> {
                if (enfants.isEmpty()) return w.always
                val lien = if (c.type == RuleCondition.Type.ALL) w.and else w.or
                val texte = enfants.joinToString(lien) { condition(it, true, depth + 1) }
                if (nested && enfants.size > 1) "($texte)" else texte
            }
            RuleCondition.Type.NOT ->
                if (enfants.isEmpty()) w.always
                else w.none(enfants.joinToString(w.or) { condition(it, true, depth + 1) })
            RuleCondition.Type.COMPARE -> compare(c)
            RuleCondition.Type.TIME_RANGE ->
                if (c.from == null || c.to == null) w.unknown else w.range(time(c.from), time(c.to))
            RuleCondition.Type.WEEKDAYS -> w.days(c.days.orEmpty().sorted())
            null -> w.unknown
        }
    }

    private fun compare(c: RuleCondition): String {
        val s = c.signal ?: return w.unknown
        val nom = w.signal(s)
        fun nombre(): String {
            val n = c.number ?: return "?"
            val texte = if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.1f", n)
            return texte + w.unit(s)
        }
        return when (c.op) {
            RuleOp.IS_TRUE -> w.yes(nom)
            RuleOp.IS_FALSE -> w.no(nom)
            RuleOp.ABOVE -> "$nom > ${nombre()}"
            RuleOp.BELOW -> "$nom < ${nombre()}"
            RuleOp.EQUALS -> "$nom = ${c.text?.let { w.value(s, it) } ?: "?"}"
            RuleOp.NOT_EQUALS -> "$nom ≠ ${c.text?.let { w.value(s, it) } ?: "?"}"
            null -> w.unknown
        }
    }

    fun action(a: RuleAction): String = when (a.type) {
        RuleAction.Type.SET -> if (a.key == null || a.value == null) w.unknown else w.set(a.key, a.value)
        RuleAction.Type.SHORTCUT -> a.shortcut?.let { w.shortcut(it) } ?: w.unknown
        RuleAction.Type.PROFILE -> a.profileId?.let { w.profile(it) } ?: w.unknown
        RuleAction.Type.WAIT -> w.waitFor(a.seconds ?: 0)
        RuleAction.Type.IF -> w.ifThen(condition(a.condition))
        RuleAction.Type.NOTIFY -> w.notify(a.text.orEmpty())
        RuleAction.Type.STOP -> w.stop
        null -> w.unknown
    }

    /** Les déclencheurs, joints par « ou » : un seul suffit. */
    fun triggers(r: CustomRule): String =
        if (r.triggers.isEmpty()) w.unknown else r.triggers.joinToString(w.or) { trigger(it) }

    fun conditions(r: CustomRule): String = condition(r.condition)

    /** Les actions de premier niveau, jointes par une virgule. */
    fun actions(r: CustomRule): String =
        if (r.actions.isEmpty()) w.unknown else r.actions.joinToString(", ") { action(it) }
}
