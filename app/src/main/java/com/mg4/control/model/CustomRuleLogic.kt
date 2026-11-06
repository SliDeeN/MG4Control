package com.mg4.control.model

/**
 * Mes automatisations — évaluation des conditions, suivi des déclencheurs, garde de lancement et
 * exécution des actions. Sans Android : le moteur ([com.mg4.control.automation.CustomRules])
 * fournit les lectures et les effets.
 *
 * **Logique à trois valeurs** : une condition vaut vrai, faux ou `null` (inconnu — signal
 * illisible, horloge pas encore plausible, nœud d'une version plus récente). Une règle ne part
 * que sur un VRAI franc : dans le doute, la voiture n'est pas touchée.
 */
object RuleLogic {

    /** Imbrication maximale des groupes et des blocs Si : au-delà, le nœud est inconnu / sauté. */
    const val MAX_DEPTH = 6

    fun evaluate(c: RuleCondition?, s: RuleState, depth: Int = 0): Boolean? {
        if (c == null || depth > MAX_DEPTH) return null
        val enfants = c.children.orEmpty()
        return when (c.type) {
            // Un groupe vide ne contraint rien : c'est l'état d'un groupe que l'on vient d'ajouter.
            RuleCondition.Type.ALL -> if (enfants.isEmpty()) true else tous(enfants.map { evaluate(it, s, depth + 1) })
            RuleCondition.Type.ANY -> if (enfants.isEmpty()) true else unAuMoins(enfants.map { evaluate(it, s, depth + 1) })
            RuleCondition.Type.NOT -> if (enfants.isEmpty()) true else unAuMoins(enfants.map { evaluate(it, s, depth + 1) })?.not()
            RuleCondition.Type.COMPARE -> compare(c, s)
            RuleCondition.Type.TIME_RANGE -> {
                val m = s.minuteOfDay ?: return null
                val de = c.from ?: return null
                val a = c.to ?: return null
                inRange(m, de, a)
            }
            RuleCondition.Type.WEEKDAYS -> {
                val j = s.weekday ?: return null
                j in c.days.orEmpty()
            }
            RuleCondition.Type.RANDOM -> {
                val tirage = s.chance ?: return null
                val p = c.number ?: return null
                tirage < p
            }
            RuleCondition.Type.SEASON -> {
                val d = s.monthDay ?: return null
                val de = c.from ?: return null
                val a = c.to ?: return null
                // Bornes incluses : du 1er décembre au 28 février compris.
                if (de <= a) d in de..a else d >= de || d <= a
            }
            RuleCondition.Type.VARIABLE -> variable(c, s)
            RuleCondition.Type.PLACE -> {
                val dedans = s.places[c.text ?: return null] ?: return null
                when (c.op) {
                    RuleOp.IS_TRUE -> dedans
                    RuleOp.IS_FALSE -> !dedans
                    else -> null
                }
            }
            null -> null
        }
    }

    /** Variable inconnue (jamais définie) : condition inconnue, comme un signal illisible. */
    private fun variable(c: RuleCondition, s: RuleState): Boolean? {
        val v = s.variables[c.text?.let { variableName(it) } ?: return null] ?: return null
        val n = v.toDoubleOrNull()
        val b = v.toBooleanStrictOrNull()
        return when (c.op) {
            RuleOp.IS_TRUE -> b ?: n?.let { it != 0.0 }
            RuleOp.IS_FALSE -> (b ?: n?.let { it != 0.0 })?.not()
            RuleOp.ABOVE -> n?.let { x -> c.number?.let { x > it } }
            RuleOp.BELOW -> n?.let { x -> c.number?.let { x < it } }
            RuleOp.EQUALS -> c.number?.let { k -> n?.let { it == k } } ?: c.value?.let { v.equals(it, true) }
            RuleOp.NOT_EQUALS -> (c.number?.let { k -> n?.let { it == k } } ?: c.value?.let { v.equals(it, true) })?.not()
            null -> null
        }
    }

    /** Nom de variable normalisé : sans espaces autour, en majuscules, 32 caractères au plus. */
    fun variableName(raw: String): String = raw.trim().uppercase().take(32)

    /** Action MEDIA : « play » (reprendre) ou « pause ». */
    const val MEDIA_PLAY = "play"
    const val MEDIA_PAUSE = "pause"

    /** La valeur d'une variable augmentée de [delta] (absente ou non numérique = 0). */
    fun addTo(current: String?, delta: Double): String =
        storedValue((current?.toDoubleOrNull() ?: 0.0) + delta)!!

    /** Son « bip intégré » de l'action PLAY_SOUND ([RuleAction.key]). */
    const val BEEP = "beep"
    /** Préfixe d'un son importé dans l'éditeur ([RuleAction.key] = « rule:<id> »). */
    const val RULE_SOUND = "rule:"

    /** « $NOM » : la valeur d'un SET qui vient de la variable NOM ; `null` pour une valeur fixe. */
    fun variableRef(value: String?): String? =
        value?.takeIf { it.length > 1 && it.startsWith("$") }?.let { variableName(it.substring(1)) }

    /**
     * Une valeur lue, telle qu'une variable la garde et qu'un réglage la relira : vrai/faux en
     * « 1 » / « 0 » (les réglages oui/non s'écrivent ainsi), nombre entier sans décimale, texte tel quel.
     */
    fun storedValue(v: Any?): String? = when (v) {
        null -> null
        is Boolean -> if (v) "1" else "0"
        is Number -> v.toDouble().let { d ->
            if (d % 1.0 == 0.0) d.toLong().toString() else String.format(java.util.Locale.US, "%.1f", d)
        }
        is Set<*> -> v.joinToString(",")
        else -> v.toString()
    }

    /** ET : un faux suffit ; sinon un inconnu rend inconnu. */
    fun tous(v: List<Boolean?>): Boolean? = when {
        v.any { it == false } -> false
        v.any { it == null } -> null
        else -> true
    }

    /** OU : un vrai suffit ; sinon un inconnu rend inconnu. */
    fun unAuMoins(v: List<Boolean?>): Boolean? = when {
        v.any { it == true } -> true
        v.any { it == null } -> null
        else -> false
    }

    /** [from] inclus, [to] exclu ; une plage qui passe minuit (22:00 → 6:00) est comprise. */
    fun inRange(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    private fun compare(c: RuleCondition, s: RuleState): Boolean? {
        val signal = c.signal ?: return null
        val v = s.values[signal] ?: return null
        return when (c.op) {
            RuleOp.IS_TRUE -> v as? Boolean
            RuleOp.IS_FALSE -> (v as? Boolean)?.not()
            RuleOp.ABOVE -> (v as? Number)?.toDouble()?.let { x -> c.number?.let { x > it } }
            RuleOp.BELOW -> (v as? Number)?.toDouble()?.let { x -> c.number?.let { x < it } }
            RuleOp.EQUALS -> egal(v, c.text)
            RuleOp.NOT_EQUALS -> egal(v, c.text)?.not()
            null -> null
        }
    }

    private fun egal(v: Any, attendu: String?): Boolean? {
        if (attendu == null) return null
        return when (v) {
            is Set<*> -> v.any { (it as? String).equals(attendu, ignoreCase = true) }
            is String -> v.equals(attendu, ignoreCase = true)
            else -> null
        }
    }

    // ── Signaux à lire ─────────────────────────────────────────────────────

    /** Les signaux dont une règle a besoin (déclencheurs, conditions, blocs Si) : seuls ceux-là sont lus. */
    fun signals(rule: CustomRule): Set<RuleSignal> {
        val out = mutableSetOf<RuleSignal>()
        rule.triggers.forEach { out += signals(it) }
        collect(rule.condition, out, 0)
        collectActions(rule.actions, out, 0)
        if (rule.onlyWhenReady) out += RuleSignal.READY
        return out
    }

    /**
     * La règle avec la variable [old] renommée [new] partout : conditions « variable » (déclencheurs,
     * Et si, blocs) et actions « définir une variable ». Les noms sont comparés normalisés.
     */
    fun renameVariable(rule: CustomRule, old: String, new: String): CustomRule {
        val o = variableName(old)
        val n = variableName(new)
        fun c(x: RuleCondition?, d: Int): RuleCondition? {
            if (x == null || d > MAX_DEPTH) return x
            val t = if (x.type == RuleCondition.Type.VARIABLE && x.text != null && variableName(x.text) == o) n else x.text
            return x.copy(text = t, children = x.children?.map { c(it, d + 1)!! })
        }
        fun a(l: List<RuleAction>?, d: Int): List<RuleAction>? {
            if (l == null || d > MAX_DEPTH) return l
            return l.map {
                val k = if ((it.type == RuleAction.Type.SET_VARIABLE || it.type == RuleAction.Type.SAVE_VALUE ||
                        it.type == RuleAction.Type.VARIABLE_ADD) &&
                    it.key != null && variableName(it.key) == o) n else it.key
                val v = if (it.type == RuleAction.Type.SET && variableRef(it.value) == o) "$" + n else it.value
                it.copy(key = k, value = v, condition = c(it.condition, d + 1), then = a(it.then, d + 1), otherwise = a(it.otherwise, d + 1))
            }
        }
        return rule.copy(
            triggers = rule.triggers.map { it.copy(condition = c(it.condition, 0)) },
            condition = c(rule.condition, 0) ?: rule.condition,
            actions = a(rule.actions, 0).orEmpty(),
        )
    }

    /** Les lieux dont une règle a besoin : seule leur appartenance est calculée. */
    fun places(rule: CustomRule): Set<String> {
        val out = mutableSetOf<String>()
        fun c(x: RuleCondition?, d: Int) {
            if (x == null || d > MAX_DEPTH) return
            if (x.type == RuleCondition.Type.PLACE) x.text?.let { out += it }
            x.children.orEmpty().forEach { c(it, d + 1) }
        }
        fun a(l: List<RuleAction>?, d: Int) {
            if (l == null || d > MAX_DEPTH) return
            l.forEach { c(it.condition, d + 1); a(it.then, d + 1); a(it.otherwise, d + 1) }
        }
        rule.triggers.forEach { c(it.condition, 0) }
        c(rule.condition, 0)
        a(rule.actions, 0)
        return out
    }

    /** Les signaux d'un seul déclencheur : le relevé périodique ne lit que ceux-là. */
    fun signals(t: RuleTrigger): Set<RuleSignal> = mutableSetOf<RuleSignal>().also { collect(t.condition, it, 0) }

    private fun collect(c: RuleCondition?, out: MutableSet<RuleSignal>, depth: Int) {
        if (c == null || depth > MAX_DEPTH) return
        c.signal?.let { out += it }
        c.children.orEmpty().forEach { collect(it, out, depth + 1) }
    }

    private fun collectActions(a: List<RuleAction>?, out: MutableSet<RuleSignal>, depth: Int) {
        if (a == null || depth > MAX_DEPTH) return
        a.forEach {
            collect(it.condition, out, depth + 1)
            collectActions(it.then, out, depth + 1)
            collectActions(it.otherwise, out, depth + 1)
        }
    }

    /** Vrai si l'horloge sert à la règle : l'heure et le jour ne se lisent pas comme les signaux. */
    fun usesClock(rule: CustomRule): Boolean {
        fun c(x: RuleCondition?, d: Int): Boolean = x != null && d <= MAX_DEPTH &&
            (x.type == RuleCondition.Type.TIME_RANGE || x.type == RuleCondition.Type.WEEKDAYS ||
                x.type == RuleCondition.Type.SEASON || x.children.orEmpty().any { c(it, d + 1) })
        fun a(l: List<RuleAction>?, d: Int): Boolean = l != null && d <= MAX_DEPTH &&
            l.any { c(it.condition, d + 1) || a(it.then, d + 1) || a(it.otherwise, d + 1) }
        return rule.triggers.any { it.type == RuleTrigger.Type.TIME || it.type == RuleTrigger.Type.SUN || c(it.condition, 0) } ||
            c(rule.condition, 0) || a(rule.actions, 0)
    }
}

/**
 * Un déclencheur « devient vrai » : part au passage de faux à vrai, après [forMs] passées vrai sans
 * interruption. Il faut avoir VU la condition fausse : une condition déjà vraie au démarrage du
 * service (READY déjà là après un redémarrage de l'appli) ne part pas — comme dans Home Assistant.
 * Une lecture inconnue ne change rien : un relevé raté ne réarme pas et n'interrompt pas l'attente.
 */
class TriggerWatch {
    private var arme = false
    private var depuis: Long? = null

    fun onValue(v: Boolean?, now: Long, forMs: Long): Boolean {
        when (v) {
            null -> return false
            false -> {
                arme = true
                depuis = null
                return false
            }
            true -> {
                if (!arme) return false
                val d = depuis ?: now.also { depuis = it }
                if (now - d < forMs) return false
                arme = false
                depuis = null
                return true
            }
        }
    }
}

/**
 * Un déclencheur horaire : part quand l'horloge ARRIVE sur la minute voulue. Pas de rattrapage :
 * écran éteint à 7:00, l'automatisation de 7:00 ne part pas à 7:20.
 */
class TimeWatch {
    private var derniere: Int? = null

    fun onMinute(minute: Int?, at: Int): Boolean {
        if (minute == null) return false
        val part = minute == at && derniere != at
        derniere = minute
        return part
    }
}

/**
 * Un déclencheur « change » : part quand le signal passe d'une valeur LUE à une autre. La première
 * lecture sert de point de départ ; une lecture inconnue ne compte pas.
 */
class ChangeWatch {
    private var derniere: Any? = null
    private var vue = false

    fun onValue(v: Any?): Boolean {
        if (v == null) return false
        val change = vue && v != derniere
        derniere = v
        vue = true
        return change
    }
}

/**
 * Un déclencheur périodique : part toutes les [everyMs], la première fois une période après son
 * chargement (pas de salve au démarrage de l'écran). Horloge monotone : l'horloge d'usine du réveil
 * n'y change rien.
 */
class PeriodicWatch(private val debut: Long) {
    private var dernier: Long? = null

    fun onTick(now: Long, everyMs: Long): Boolean {
        if (everyMs <= 0) return false
        val ref = dernier ?: debut
        if (now - ref < everyMs) return false
        dernier = now
        return true
    }
}

/** Que faire d'un déclenchement, selon l'état de la règle. */
object RuleGate {
    enum class Decision { START, RESTART, BUSY, COOLDOWN, NOT_READY }

    /**
     * @param running   la règle tourne encore
     * @param lastStart instant (horloge monotone, ms) de son dernier départ, `null` = jamais
     * @param ready     état READY lu, `null` = inconnu (refusé quand la règle l'exige)
     */
    fun decide(rule: CustomRule, running: Boolean, lastStart: Long?, now: Long, ready: Boolean?): Decision {
        if (rule.onlyWhenReady && ready != true) return Decision.NOT_READY
        if (running) return if (rule.ifRunning == CustomRule.IfRunning.RESTART) Decision.RESTART else Decision.BUSY
        val pause = rule.cooldownMinutes.coerceAtLeast(0) * 60_000L
        if (pause > 0 && lastStart != null && now - lastStart < pause) return Decision.COOLDOWN
        return Decision.START
    }
}

/** Les effets d'une exécution : fournis par le moteur, simulés par les tests. */
interface RuleEffects {
    /** Lecture fraîche, pour les blocs Si : la voiture a pu changer pendant une attente. */
    fun state(): RuleState
    /** Écriture `ExternalApi.SET_*` ; faux = clé ou valeur refusée. Bloquant. */
    fun set(key: String, value: String): Boolean
    fun shortcut(name: String): Boolean
    suspend fun profile(id: String): Boolean
    fun notify(text: String)
    suspend fun pause(ms: Long)
    /** Question Oui / Non ; `null` = pas de réponse dans le délai. */
    suspend fun confirm(text: String, seconds: Int): Boolean?
    /** Les autres actions (son, appli, raccourci perso, automatisations, variable, voix, navigation). */
    suspend fun perform(a: RuleAction): Boolean
}

/**
 * Une exécution des actions, de haut en bas. Le [journal] garde une ligne par action (le Journal
 * de l'éditeur) : un signe (✓ fait, ✗ refusé, → résultat d'un Si, ↻ Répéter, ■ arrêt) puis
 * l'action dite par [describe], dans la langue de l'appli. Garde-fous : [MAX_STEPS] actions au
 * plus, attentes plafonnées à [MAX_WAIT_S], [MAX_REPEAT] tours de Répéter, imbrication à
 * [RuleLogic.MAX_DEPTH], [MAX_JOURNAL] lignes de journal.
 */
class RuleRun(
    private val fx: RuleEffects,
    private val describe: (RuleAction) -> String = { it.type?.name ?: "?" },
) {

    companion object {
        const val MAX_STEPS = 200
        const val MAX_WAIT_S = 3_600
        const val MAX_REPEAT = 20
        const val MAX_JOURNAL = 80
    }

    val journal = mutableListOf<String>()
    var done = 0
        private set
    var failed = 0
        private set
    private var etapes = 0
    private var arret = false

    suspend fun run(actions: List<RuleAction>) {
        bloc(actions, 0)
    }

    private fun ligne(texte: String) {
        if (journal.size < MAX_JOURNAL) journal += texte
        else if (journal.size == MAX_JOURNAL) journal += "…"
    }

    private suspend fun bloc(actions: List<RuleAction>?, depth: Int) {
        if (actions == null) return
        if (depth > RuleLogic.MAX_DEPTH) {
            ligne("✗ …")
            return
        }
        for (a in actions) {
            if (arret) return
            if (++etapes > MAX_STEPS) {
                ligne("■ $MAX_STEPS+")
                arret = true
                return
            }
            une(a, depth)
        }
    }

    private suspend fun une(a: RuleAction, depth: Int) {
        when (a.type) {
            RuleAction.Type.SET -> {
                val k = a.key
                val v = a.value
                if (k == null || v == null) return note(false, a)
                note(fx.set(k, v), a)
            }
            RuleAction.Type.SHORTCUT -> {
                val n = a.shortcut ?: return note(false, a)
                note(fx.shortcut(n), a)
            }
            RuleAction.Type.PROFILE -> {
                val id = a.profileId ?: return note(false, a)
                note(fx.profile(id), a)
            }
            RuleAction.Type.WAIT -> {
                val s = (a.seconds ?: 0).coerceIn(0, MAX_WAIT_S)
                ligne("… ${describe(a)}")
                fx.pause(s * 1_000L)
            }
            RuleAction.Type.IF -> {
                val r = RuleLogic.evaluate(a.condition, fx.state())
                ligne("${describe(a)} → ${when (r) { true -> "✓"; false -> "✗"; null -> "?" }}")
                // Inconnu : ni l'un ni l'autre. « Sinon » veut dire « la condition est fausse »,
                // pas « on n'a pas pu lire ».
                when (r) {
                    true -> bloc(a.then, depth + 1)
                    false -> bloc(a.otherwise, depth + 1)
                    null -> {}
                }
            }
            RuleAction.Type.NOTIFY -> {
                fx.notify(a.text.orEmpty())
                note(true, a)
            }
            RuleAction.Type.STOP -> {
                ligne("■ ${describe(a)}")
                arret = true
            }
            RuleAction.Type.REPEAT -> {
                val n = (a.count ?: 0).coerceIn(0, MAX_REPEAT)
                ligne("↻ ${describe(a)}")
                repeat(n) { if (!arret) bloc(a.then, depth + 1) }
            }
            RuleAction.Type.WAIT_UNTIL -> {
                val max = (a.seconds ?: 0).coerceIn(0, MAX_WAIT_S)
                var ecoule = 0
                while (true) {
                    if (RuleLogic.evaluate(a.condition, fx.state()) == true) {
                        ligne("✓ ${describe(a)}")
                        break
                    }
                    // Délai écoulé : on continue quand même, comme Home Assistant par défaut.
                    if (ecoule >= max) {
                        ligne("… ${describe(a)} → ${max} s")
                        break
                    }
                    fx.pause(1_000L)
                    ecoule++
                }
            }
            RuleAction.Type.CONFIRM -> {
                val r = fx.confirm(a.text.orEmpty(), (a.seconds ?: 15).coerceIn(5, 120))
                val oui = r ?: (a.value == "yes")
                ligne("${describe(a)} → ${when (r) { true -> "✓"; false -> "✗"; null -> if (oui) "✓ …" else "✗ …" }}")
                // Non (ou pas de réponse, par défaut) : la suite n'a pas été voulue.
                if (!oui) arret = true
            }
            RuleAction.Type.PLAY_SOUND, RuleAction.Type.OPEN_APP, RuleAction.Type.CUSTOM_SHORTCUT,
            RuleAction.Type.RULE_ENABLE, RuleAction.Type.RUN_RULE, RuleAction.Type.SET_VARIABLE,
            RuleAction.Type.SPEAK, RuleAction.Type.NAVIGATE,
            RuleAction.Type.ABRP_START, RuleAction.Type.ABRP_STOP, RuleAction.Type.SAVE_VALUE,
            RuleAction.Type.VARIABLE_ADD, RuleAction.Type.RULE_STOP, RuleAction.Type.MEDIA -> note(fx.perform(a), a)
            null -> ligne("? ${describe(a)}")
        }
    }

    private fun note(ok: Boolean, a: RuleAction) {
        if (ok) done++ else failed++
        ligne((if (ok) "✓ " else "✗ ") + describe(a))
    }
}
