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
    /** Résumé d'un bloc Si pour la carte : « si X → A ; sinon → B » ([otherwise] nul = pas de Sinon). */
    fun ifSummary(condition: String, then: String, otherwise: String?): String =
        ifThen(condition) + " → " + then + (otherwise?.let { " ; → $it" } ?: "")
    fun repeat(times: Int): String
    // Étape 3
    fun every(minutes: Int): String
    fun changes(signal: String): String
    val manual: String
    fun sun(e: SunEvent, offset: Int): String
    fun random(percent: Int): String
    fun season(from: Int, to: Int): String
    fun variable(name: String, test: String): String
    fun place(id: String, inside: Boolean): String
    fun waitUntil(condition: String, seconds: Int): String
    fun confirm(text: String): String
    fun playSound(name: String): String
    fun openApp(pkg: String): String
    fun customShortcut(id: String): String
    fun ruleEnable(id: String, on: Boolean): String
    fun runRule(id: String): String
    fun setVariable(name: String, value: String): String
    fun speak(text: String): String
    fun navigate(dest: String): String
    fun notify(text: String): String
    /** Envoi continu vers le compte ABRP [id] (issue #188). */
    fun abrpStart(id: String): String = "ABRP → $id"
    val abrpStop: String get() = "ABRP ■"
    val stop: String
    val unknown: String
    /** « oui » / « non » d'une valeur lue (historique) ; l'écran les traduit. */
    val on: String get() = "true"
    val off: String get() = "false"
    // 2026-11-06 : mémoriser une valeur lue, régler avec une variable, bip intégré.
    fun saveValue(signal: String, variable: String): String = "$variable ← $signal"
    fun setFromVariable(key: String, variable: String): String = set(key, "$" + variable)
    val beep: String get() = "beep"
    fun variableAdd(name: String, delta: String): String = "$name $delta"
    fun ruleStop(id: String): String = "stop $id"
    fun media(play: Boolean): String = if (play) "play" else "pause"
    // 2026-11-09 : actions Android.
    fun androidKey(home: Boolean): String = if (home) "home" else "back"
    fun closeApp(pkg: String): String = "close $pkg"
    fun openSettings(page: String): String = "settings $page"
}

/** Les phrases d'une automatisation, pour sa carte, les lignes de l'éditeur et le journal. */
class RuleText(private val w: RuleWords) {

    fun time(minute: Int): String = String.format(Locale.US, "%d:%02d", minute / 60, minute % 60)

    /**
     * Ce qui a été lu pour la condition [c] (2026-11-08, historique : « lue : 12 °C ») ; `null` si
     * rien de lisible ou sans objet (groupe, hasard, saison…).
     */
    fun readValue(c: RuleCondition?, s: RuleState): String? = when (c?.type) {
        RuleCondition.Type.COMPARE -> c.signal?.let { sig ->
            when (val v = s.values[sig]) {
                null -> null
                is Boolean -> if (v) w.on else w.off
                is Number -> v.toDouble().let { n ->
                    (if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.1f", n)) + w.unit(sig)
                }
                is Collection<*> -> v.joinToString(", ") { w.value(sig, it.toString()) }
                else -> w.value(sig, v.toString())
            }
        }
        RuleCondition.Type.VARIABLE -> c.text?.let { s.variables[RuleLogic.variableName(it)] }
        RuleCondition.Type.TIME_RANGE -> s.minuteOfDay?.let { time(it) }
        RuleCondition.Type.WEEKDAYS -> s.weekday?.let { w.days(listOf(it)) }
        RuleCondition.Type.PLACE -> c.text?.let { s.places[it] }?.let { if (it) w.on else w.off }
        else -> null
    }

    fun trigger(t: RuleTrigger): String = when (t.type) {
        RuleTrigger.Type.EVENT -> t.event?.let { w.event(it) } ?: w.unknown
        RuleTrigger.Type.BECOMES -> {
            val c = condition(t.condition)
            if (t.forSeconds > 0) w.forSeconds(c, t.forSeconds) else c
        }
        RuleTrigger.Type.TIME -> t.at?.let { w.at(time(it)) } ?: w.unknown
        RuleTrigger.Type.PERIODIC -> t.every?.let { w.every(it) } ?: w.unknown
        RuleTrigger.Type.CHANGES -> t.condition?.signal?.let { w.changes(w.signal(it)) }
            ?.let { if (t.forSeconds > 0) w.forSeconds(it, t.forSeconds) else it } ?: w.unknown
        RuleTrigger.Type.MANUAL -> w.manual
        RuleTrigger.Type.SUN -> t.sun?.let { w.sun(it, t.offset) } ?: w.unknown
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
            RuleCondition.Type.NOT -> when {
                enfants.isEmpty() -> w.always
                else -> aucune(enfants, nested, depth)
            }
            RuleCondition.Type.COMPARE -> compare(c)
            RuleCondition.Type.TIME_RANGE ->
                if (c.from == null || c.to == null) w.unknown else w.range(time(c.from), time(c.to))
            RuleCondition.Type.WEEKDAYS -> w.days(c.days.orEmpty().sorted())
            RuleCondition.Type.RANDOM -> c.number?.let { w.random(it.toInt()) } ?: w.unknown
            RuleCondition.Type.SEASON -> if (c.from == null || c.to == null) w.unknown else w.season(c.from, c.to)
            RuleCondition.Type.VARIABLE -> c.text?.let { w.variable(it, testVariable(c)) } ?: w.unknown
            RuleCondition.Type.PLACE -> c.text?.let { w.place(it, c.op != RuleOp.IS_FALSE) } ?: w.unknown
            null -> w.unknown
        }
    }

    /**
     * « Aucune de : A, B » écrit à l'endroit quand chaque condition a un contraire simple :
     * « Profil actif ≠ Paul et ≠ Marie » plutôt que « aucune de : Profil actif = Paul ou … ».
     */
    private fun aucune(enfants: List<RuleCondition>, nested: Boolean, depth: Int): String {
        val inverses = enfants.map { contraire(it) }
        if (inverses.any { it == null }) return w.none(enfants.joinToString(w.or) { condition(it, true, depth + 1) })
        val l = inverses.filterNotNull()
        val memeSignal = l.size > 1 && l.all { it.type == RuleCondition.Type.COMPARE && it.signal == l[0].signal }
        val textes = l.mapIndexed { i, c ->
            val t = condition(c, true, depth + 1)
            if (memeSignal && i > 0 && c.signal != null) t.removePrefix(w.signal(c.signal) + " ") else t
        }
        val texte = textes.joinToString(w.and)
        return if (nested && textes.size > 1) "($texte)" else texte
    }

    /** Le contraire d'une condition simple, ou null quand il ne s'écrit pas simplement (>, <, plages…). */
    private fun contraire(c: RuleCondition): RuleCondition? = when (c.type) {
        RuleCondition.Type.COMPARE, RuleCondition.Type.VARIABLE -> when (c.op) {
            RuleOp.EQUALS -> c.copy(op = RuleOp.NOT_EQUALS)
            RuleOp.NOT_EQUALS -> c.copy(op = RuleOp.EQUALS)
            RuleOp.IS_TRUE -> c.copy(op = RuleOp.IS_FALSE)
            RuleOp.IS_FALSE -> c.copy(op = RuleOp.IS_TRUE)
            else -> null
        }
        RuleCondition.Type.PLACE -> c.copy(op = if (c.op == RuleOp.IS_FALSE) RuleOp.IS_TRUE else RuleOp.IS_FALSE)
        else -> null
    }

    private fun nombre(n: Double): String =
        if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.1f", n)

    /** « > 3 », « = vrai »… la partie droite d'une condition de variable. */
    private fun testVariable(c: RuleCondition): String = when (c.op) {
        RuleOp.IS_TRUE -> w.yes("").trim(' ', ':')
        RuleOp.IS_FALSE -> w.no("").trim(' ', ':')
        RuleOp.ABOVE -> "> ${c.number?.let { nombre(it) } ?: "?"}"
        RuleOp.BELOW -> "< ${c.number?.let { nombre(it) } ?: "?"}"
        RuleOp.EQUALS -> "= ${c.number?.let { nombre(it) } ?: c.value ?: "?"}"
        RuleOp.NOT_EQUALS -> "≠ ${c.number?.let { nombre(it) } ?: c.value ?: "?"}"
        null -> w.unknown
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
        RuleAction.Type.SET -> when {
            a.key == null || a.value == null -> w.unknown
            RuleLogic.variableRef(a.value) != null -> w.setFromVariable(a.key, RuleLogic.variableRef(a.value)!!)
            else -> w.set(a.key, a.value)
        }
        RuleAction.Type.SHORTCUT -> a.shortcut?.let { w.shortcut(it) } ?: w.unknown
        RuleAction.Type.PROFILE -> a.profileId?.let { w.profile(it) } ?: w.unknown
        RuleAction.Type.WAIT -> w.waitFor(a.seconds ?: 0)
        RuleAction.Type.IF -> w.ifThen(condition(a.condition))
        RuleAction.Type.NOTIFY -> w.notify(a.text.orEmpty())
        RuleAction.Type.STOP -> w.stop
        RuleAction.Type.REPEAT -> w.repeat(a.count ?: 0)
        RuleAction.Type.WAIT_UNTIL -> w.waitUntil(condition(a.condition), a.seconds ?: 0)
        RuleAction.Type.CONFIRM -> w.confirm(a.text.orEmpty())
        RuleAction.Type.PLAY_SOUND -> w.playSound(if (a.key == RuleLogic.BEEP) w.beep else a.text ?: a.value ?: "?")
        RuleAction.Type.VARIABLE_ADD -> if (a.key == null || a.value == null) w.unknown
            else w.variableAdd(a.key, if (a.value.startsWith("-")) a.value else "+" + a.value)
        RuleAction.Type.RULE_STOP -> a.text?.let { w.ruleStop(it) } ?: w.unknown
        RuleAction.Type.MEDIA -> w.media(a.value == RuleLogic.MEDIA_PLAY)
        RuleAction.Type.SAVE_VALUE -> {
            val sig = RuleSignal.entries.firstOrNull { it.name == a.text }
            if (a.key == null || sig == null) w.unknown else w.saveValue(w.signal(sig), a.key)
        }
        RuleAction.Type.OPEN_APP -> a.text?.let { w.openApp(it) } ?: w.unknown
        RuleAction.Type.CUSTOM_SHORTCUT -> a.text?.let { w.customShortcut(it) } ?: w.unknown
        RuleAction.Type.RULE_ENABLE -> a.text?.let { w.ruleEnable(it, a.value == "1") } ?: w.unknown
        RuleAction.Type.RUN_RULE -> a.text?.let { w.runRule(it) } ?: w.unknown
        RuleAction.Type.SET_VARIABLE -> if (a.key == null || a.value == null) w.unknown else w.setVariable(a.key, a.value)
        RuleAction.Type.SPEAK -> w.speak(a.text.orEmpty())
        RuleAction.Type.NAVIGATE -> a.text?.let { w.navigate(it) } ?: w.unknown
        RuleAction.Type.ABRP_START -> a.text?.let { w.abrpStart(it) } ?: w.unknown
        RuleAction.Type.ABRP_STOP -> w.abrpStop
        RuleAction.Type.ANDROID_KEY -> when (a.value) {
            AndroidActions.KEY_HOME -> w.androidKey(true)
            AndroidActions.KEY_BACK -> w.androidKey(false)
            else -> w.unknown
        }
        RuleAction.Type.CLOSE_APP -> a.text?.let { w.closeApp(it) } ?: w.unknown
        RuleAction.Type.OPEN_SETTINGS -> a.value?.takeIf { it in AndroidActions.SETTINGS_PAGES }?.let { w.openSettings(it) } ?: w.unknown
        null -> w.unknown
    }

    /** Les déclencheurs, joints par « ou » : un seul suffit. */
    fun triggers(r: CustomRule): String =
        if (r.triggers.isEmpty()) w.unknown else r.triggers.joinToString(w.or) { trigger(it) }

    fun conditions(r: CustomRule): String = condition(r.condition)

    /**
     * Les actions de premier niveau, jointes par une virgule ; un bloc Si dit aussi ce qu'il fait
     * (« si X → A ; sinon → B »), ses actions entre parenthèses quand il y en a plusieurs.
     */
    fun actions(r: CustomRule): String =
        if (r.actions.isEmpty()) w.unknown else liste(r.actions, 0)

    private fun liste(l: List<RuleAction>, depth: Int): String = l.joinToString(", ") { resume(it, depth) }

    private fun resume(a: RuleAction, depth: Int): String {
        if (a.type != RuleAction.Type.IF || depth > RuleLogic.MAX_DEPTH) return action(a)
        fun bloc(l: List<RuleAction>?): String? = l?.takeIf { it.isNotEmpty() }?.let {
            val t = liste(it, depth + 1)
            if (it.size > 1) "($t)" else t
        }
        return w.ifSummary(condition(a.condition), bloc(a.then) ?: "—", bloc(a.otherwise))
    }
}
