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
            null -> null
        }
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
                x.children.orEmpty().any { c(it, d + 1) })
        fun a(l: List<RuleAction>?, d: Int): Boolean = l != null && d <= MAX_DEPTH &&
            l.any { c(it.condition, d + 1) || a(it.then, d + 1) || a(it.otherwise, d + 1) }
        return rule.triggers.any { it.type == RuleTrigger.Type.TIME || c(it.condition, 0) } ||
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
}

/**
 * Une exécution des actions, de haut en bas. Le [journal] garde une ligne par action (le Journal
 * de l'éditeur, étape 2). Garde-fous : [MAX_STEPS] actions au plus, attentes plafonnées à
 * [MAX_WAIT_S], imbrication à [RuleLogic.MAX_DEPTH].
 */
class RuleRun(private val fx: RuleEffects) {

    companion object {
        const val MAX_STEPS = 200
        const val MAX_WAIT_S = 3_600
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

    private suspend fun bloc(actions: List<RuleAction>?, depth: Int) {
        if (actions == null) return
        if (depth > RuleLogic.MAX_DEPTH) {
            journal += "bloc trop imbriqué : sauté"
            return
        }
        for (a in actions) {
            if (arret) return
            if (++etapes > MAX_STEPS) {
                journal += "plus de $MAX_STEPS actions : arrêt"
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
                if (k == null || v == null) return note(false, "écriture incomplète")
                note(fx.set(k, v), "$k = $v")
            }
            RuleAction.Type.SHORTCUT -> {
                val n = a.shortcut ?: return note(false, "raccourci sans nom")
                note(fx.shortcut(n), "raccourci $n")
            }
            RuleAction.Type.PROFILE -> {
                val id = a.profileId ?: return note(false, "profil non choisi")
                note(fx.profile(id), "profil $id")
            }
            RuleAction.Type.WAIT -> {
                val s = (a.seconds ?: 0).coerceIn(0, MAX_WAIT_S)
                journal += "attente $s s"
                fx.pause(s * 1_000L)
            }
            RuleAction.Type.IF -> {
                val r = RuleLogic.evaluate(a.condition, fx.state())
                journal += "si → ${r ?: "inconnu"}"
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
                note(true, "message")
            }
            RuleAction.Type.STOP -> {
                journal += "arrêt demandé"
                arret = true
            }
            null -> journal += "action inconnue : sautée"
        }
    }

    private fun note(ok: Boolean, quoi: String) {
        if (ok) done++ else failed++
        journal += (if (ok) "ok " else "échec ") + quoi
    }
}
