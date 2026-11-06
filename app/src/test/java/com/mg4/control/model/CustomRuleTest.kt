package com.mg4.control.model

import com.mg4.control.api.ExternalApi
import com.mg4.control.automation.CustomRuleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mes automatisations (issue #201, étapes 1 et 2) : conditions à trois valeurs, déclencheurs,
 * garde de lancement, exécution (Répéter, journal), enregistrement et modèles.
 *
 * L'enjeu : ne toucher la voiture que sur un VRAI franc, une fois, et jamais en boucle.
 */
class CustomRuleTest {

    private fun cmp(s: RuleSignal, op: RuleOp, n: Double? = null, t: String? = null) =
        RuleCondition(RuleCondition.Type.COMPARE, signal = s, op = op, number = n, text = t)

    private fun groupe(type: RuleCondition.Type, vararg c: RuleCondition) = RuleCondition(type, children = c.toList())

    private fun etat(vararg v: Pair<RuleSignal, Any?>, minute: Int? = null, jour: Int? = null) =
        RuleState(mapOf(*v), minute, jour)

    // ── Conditions ──────────────────────────────────────────────────────────

    @Test
    fun `comparaisons numeriques et booleennes`() {
        val s = etat(RuleSignal.OUTSIDE_TEMP to 3.0, RuleSignal.READY to true)
        assertEquals(true, RuleLogic.evaluate(cmp(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 5.0), s))
        assertEquals(false, RuleLogic.evaluate(cmp(RuleSignal.OUTSIDE_TEMP, RuleOp.ABOVE, 5.0), s))
        assertEquals(true, RuleLogic.evaluate(cmp(RuleSignal.READY, RuleOp.IS_TRUE), s))
        assertEquals(false, RuleLogic.evaluate(cmp(RuleSignal.READY, RuleOp.IS_FALSE), s))
    }

    @Test
    fun `un signal illisible rend la condition inconnue`() {
        val s = etat(RuleSignal.OUTSIDE_TEMP to null)
        assertNull(RuleLogic.evaluate(cmp(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 5.0), s))
        assertNull("signal absent", RuleLogic.evaluate(cmp(RuleSignal.SOC, RuleOp.BELOW, 5.0), s))
    }

    @Test
    fun `logique a trois valeurs des groupes`() {
        val vrai = cmp(RuleSignal.READY, RuleOp.IS_TRUE)
        val faux = cmp(RuleSignal.READY, RuleOp.IS_FALSE)
        val inconnu = cmp(RuleSignal.SOC, RuleOp.BELOW, 20.0)
        val s = etat(RuleSignal.READY to true)
        assertEquals("ET : un faux suffit", false, RuleLogic.evaluate(groupe(RuleCondition.Type.ALL, inconnu, faux), s))
        assertNull("ET : inconnu sans faux", RuleLogic.evaluate(groupe(RuleCondition.Type.ALL, vrai, inconnu), s))
        assertEquals("OU : un vrai suffit", true, RuleLogic.evaluate(groupe(RuleCondition.Type.ANY, inconnu, vrai), s))
        assertNull("OU : inconnu sans vrai", RuleLogic.evaluate(groupe(RuleCondition.Type.ANY, faux, inconnu), s))
        assertEquals("NON : aucune vraie", true, RuleLogic.evaluate(groupe(RuleCondition.Type.NOT, faux), s))
        assertEquals(false, RuleLogic.evaluate(groupe(RuleCondition.Type.NOT, faux, vrai), s))
        assertNull("NON d'un inconnu reste inconnu", RuleLogic.evaluate(groupe(RuleCondition.Type.NOT, inconnu), s))
        assertEquals("groupe vide : aucune contrainte", true, RuleLogic.evaluate(RuleCondition(RuleCondition.Type.ALL), s))
    }

    @Test
    fun `groupes imbriques`() {
        // lun–ven ET (temp < 5 OU 5:00–9:00)
        val c = groupe(RuleCondition.Type.ALL,
            RuleCondition(RuleCondition.Type.WEEKDAYS, days = listOf(1, 2, 3, 4, 5)),
            groupe(RuleCondition.Type.ANY,
                cmp(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 5.0),
                RuleCondition(RuleCondition.Type.TIME_RANGE, from = 300, to = 540)))
        assertEquals(true, RuleLogic.evaluate(c, etat(RuleSignal.OUTSIDE_TEMP to 12.0, minute = 7 * 60, jour = 2)))
        assertEquals(false, RuleLogic.evaluate(c, etat(RuleSignal.OUTSIDE_TEMP to 12.0, minute = 12 * 60, jour = 2)))
        assertEquals("samedi", false, RuleLogic.evaluate(c, etat(RuleSignal.OUTSIDE_TEMP to 1.0, minute = 7 * 60, jour = 6)))
        assertNull("horloge pas plausible", RuleLogic.evaluate(c, etat(RuleSignal.OUTSIDE_TEMP to 12.0)))
    }

    @Test
    fun `plage horaire qui passe minuit`() {
        assertTrue(RuleLogic.inRange(23 * 60, 22 * 60, 6 * 60))
        assertTrue(RuleLogic.inRange(5 * 60 + 59, 22 * 60, 6 * 60))
        assertFalse("6:00 exclu", RuleLogic.inRange(6 * 60, 22 * 60, 6 * 60))
        assertFalse(RuleLogic.inRange(12 * 60, 22 * 60, 6 * 60))
        assertTrue("début inclus", RuleLogic.inRange(8 * 60, 8 * 60, 9 * 60))
    }

    @Test
    fun `textes et telephones`() {
        val s = etat(RuleSignal.GEAR to "P", RuleSignal.BT_DEVICE to setOf("AA:BB:CC:DD:EE:FF"))
        assertEquals(true, RuleLogic.evaluate(cmp(RuleSignal.GEAR, RuleOp.EQUALS, t = "p"), s))
        assertEquals(true, RuleLogic.evaluate(cmp(RuleSignal.BT_DEVICE, RuleOp.EQUALS, t = "aa:bb:cc:dd:ee:ff"), s))
        assertEquals(false, RuleLogic.evaluate(cmp(RuleSignal.BT_DEVICE, RuleOp.NOT_EQUALS, t = "AA:BB:CC:DD:EE:FF"), s))
        assertEquals("P", RuleSignal.gearText(1))
        assertEquals("D", RuleSignal.gearText(4))
        assertEquals(RuleSignal.GEAR_OTHER, RuleSignal.gearText(2))
    }

    @Test
    fun `imbrication trop profonde inconnue`() {
        var c = cmp(RuleSignal.READY, RuleOp.IS_TRUE)
        repeat(RuleLogic.MAX_DEPTH + 2) { c = groupe(RuleCondition.Type.ALL, c) }
        assertNull(RuleLogic.evaluate(c, etat(RuleSignal.READY to true)))
    }

    @Test
    fun `seuls les signaux utiles sont lus`() {
        val r = CustomRuleTemplates.build(CustomRuleTemplates.Template.WINTER_MORNING, "x", "y")
        assertEquals(setOf(RuleSignal.READY, RuleSignal.OUTSIDE_TEMP), RuleLogic.signals(r))
        assertTrue(RuleLogic.usesClock(r))
        val m = CustomRuleTemplates.build(CustomRuleTemplates.Template.STARTUP_MESSAGE, "x", "y")
        assertTrue(RuleLogic.signals(m).isEmpty())
        assertFalse(RuleLogic.usesClock(m))
    }

    @Test
    fun `modeles des issues 173 et 198`() = runBlocking {
        val v = CustomRuleTemplates.build(CustomRuleTemplates.Template.STEERING_HEAT_TIMER, "v", "coupé")
        assertEquals(setOf(RuleSignal.STEERING_HEAT), RuleLogic.signals(v))
        assertEquals(15 * 60, v.triggers.single().forSeconds)
        val faitV = Faux(etat())
        RuleRun(faitV).run(v.actions)
        assertEquals(listOf("steering_heat=0", "msg:coupé"), faitV.faits)

        val s = CustomRuleTemplates.build(CustomRuleTemplates.Template.SEAT_HEAT_TIMER, "s", "m")
        assertEquals(setOf(RuleSignal.SEAT_HEAT_LEFT), RuleLogic.signals(s))

        val l = CustomRuleTemplates.build(CustomRuleTemplates.Template.BRIGHTNESS_SUN, "l", "l")
        assertEquals(3, l.triggers.size)
        val jour = Faux(etat(RuleSignal.SUN_UP to true))
        RuleRun(jour).run(l.actions)
        assertEquals(listOf("${RuleKeys.BRIGHTNESS}=80"), jour.faits)
        val nuit = Faux(etat(RuleSignal.SUN_UP to false))
        RuleRun(nuit).run(l.actions)
        assertEquals(listOf("${RuleKeys.BRIGHTNESS}=25"), nuit.faits)
    }

    // ── Déclencheurs ────────────────────────────────────────────────────────

    @Test
    fun `devient vrai part au passage seulement`() {
        val w = TriggerWatch()
        assertFalse("déjà vrai au démarrage : rien", w.onValue(true, 0, 0))
        assertFalse(w.onValue(false, 1, 0))
        assertTrue("faux → vrai", w.onValue(true, 2, 0))
        assertFalse("pas une seconde fois", w.onValue(true, 3, 0))
        assertFalse(w.onValue(false, 4, 0))
        assertTrue("nouveau passage", w.onValue(true, 5, 0))
    }

    @Test
    fun `devient vrai pendant une duree`() {
        val w = TriggerWatch()
        w.onValue(false, 0, 60_000)
        assertFalse(w.onValue(true, 1_000, 60_000))
        assertFalse(w.onValue(true, 30_000, 60_000))
        assertFalse("interrompu", w.onValue(false, 40_000, 60_000))
        assertFalse(w.onValue(true, 50_000, 60_000))
        assertFalse(w.onValue(true, 100_000, 60_000))
        assertTrue("une minute sans interruption", w.onValue(true, 110_000, 60_000))
    }

    @Test
    fun `une lecture ratee ne rearme pas et n'interrompt pas`() {
        val w = TriggerWatch()
        assertFalse(w.onValue(null, 0, 0))
        assertFalse("inconnu ne compte pas comme faux", w.onValue(true, 1, 0))
        w.onValue(false, 2, 10)
        w.onValue(true, 3, 10)
        assertFalse(w.onValue(null, 8, 10))
        assertTrue("l'attente continue", w.onValue(true, 13, 10))
    }

    @Test
    fun `declencheur horaire une fois a la minute`() {
        val w = TimeWatch()
        assertFalse(w.onMinute(419, 420))
        assertTrue(w.onMinute(420, 420))
        assertFalse("même minute, relevé suivant", w.onMinute(420, 420))
        assertFalse(w.onMinute(null, 420))
        assertFalse(w.onMinute(421, 420))
    }

    // ── Garde de lancement ──────────────────────────────────────────────────

    @Test
    fun `garde de lancement`() {
        val r = CustomRule(cooldownMinutes = 10)
        assertEquals(RuleGate.Decision.START, RuleGate.decide(r, false, null, 0, null))
        assertEquals(RuleGate.Decision.BUSY, RuleGate.decide(r, true, null, 0, null))
        assertEquals(RuleGate.Decision.RESTART,
            RuleGate.decide(r.copy(ifRunning = CustomRule.IfRunning.RESTART), true, null, 0, null))
        assertEquals(RuleGate.Decision.COOLDOWN, RuleGate.decide(r, false, 0, 9 * 60_000L, null))
        assertEquals(RuleGate.Decision.START, RuleGate.decide(r, false, 0, 10 * 60_000L, null))
        val ready = CustomRule(onlyWhenReady = true)
        assertEquals("READY inconnu = refusé", RuleGate.Decision.NOT_READY, RuleGate.decide(ready, false, null, 0, null))
        assertEquals(RuleGate.Decision.START, RuleGate.decide(ready, false, null, 0, true))
    }

    // ── Exécution ───────────────────────────────────────────────────────────

    private class Faux(var etat: RuleState = RuleState(), val refuse: Set<String> = emptySet()) : RuleEffects {
        val faits = mutableListOf<String>()
        var attente = 0L
        override fun state() = etat
        override fun set(key: String, value: String) = (key !in refuse).also { faits += "$key=$value" }
        override fun shortcut(name: String) = true.also { faits += "sc:$name" }
        override suspend fun profile(id: String) = true.also { faits += "profil:$id" }
        override fun notify(text: String) { faits += "msg:$text" }
        override suspend fun pause(ms: Long) { attente += ms }
        var reponse: Boolean? = true
        /** Appelé à chaque pause d'« attendre que » : de quoi faire évoluer l'état. */
        var surPause: (() -> Unit)? = null
        override suspend fun confirm(text: String, seconds: Int): Boolean? = reponse.also { faits += "q:$text" }
        override suspend fun perform(a: RuleAction): Boolean = true.also { faits += "${a.type}:${a.text ?: a.key}" }
    }

    @Test
    fun `si sinon selon l'etat lu`() = runBlocking {
        val r = CustomRuleTemplates.build(CustomRuleTemplates.Template.WINTER_MORNING, "Matin", "Bonne route")
        val froid = Faux(etat(RuleSignal.OUTSIDE_TEMP to -2.0))
        RuleRun(froid).run(r.actions)
        assertEquals(listOf("seat_heat_left=3", "seat_heat_right=3", "steering_heat=1", "msg:Bonne route"), froid.faits)
        assertEquals(30_000L, froid.attente)

        val doux = Faux(etat(RuleSignal.OUTSIDE_TEMP to 3.0))
        RuleRun(doux).run(r.actions)
        assertEquals(listOf("hvac_power=1", "hvac_temp=21", "hvac_auto=1", "msg:Bonne route"), doux.faits)

        val illisible = Faux(etat(RuleSignal.OUTSIDE_TEMP to null))
        RuleRun(illisible).run(r.actions)
        assertEquals("inconnu : ni alors ni sinon", listOf("msg:Bonne route"), illisible.faits)
    }

    @Test
    fun `arreter, echecs, actions inconnues`() = runBlocking {
        val fx = Faux(refuse = setOf("ac"))
        val run = RuleRun(fx)
        run.run(listOf(
            RuleAction(RuleAction.Type.SET, key = "ac", value = "1"),
            RuleAction(type = null),
            RuleAction(RuleAction.Type.SET, key = "hvac_power"),
            RuleAction(RuleAction.Type.STOP),
            RuleAction(RuleAction.Type.NOTIFY, text = "jamais"),
        ))
        assertEquals(listOf("ac=1"), fx.faits)
        assertEquals(0, run.done)
        assertEquals("refusée + incomplète", 2, run.failed)
    }

    @Test
    fun `garde-fous d'execution`() = runBlocking {
        val fx = Faux()
        RuleRun(fx).run(listOf(RuleAction(RuleAction.Type.WAIT, seconds = 99_999)))
        assertEquals(RuleRun.MAX_WAIT_S * 1_000L, fx.attente)

        val beaucoup = Faux()
        RuleRun(beaucoup).run(List(RuleRun.MAX_STEPS + 50) { RuleAction(RuleAction.Type.NOTIFY, text = "x") })
        assertEquals(RuleRun.MAX_STEPS, beaucoup.faits.size)
    }

    @Test
    fun `repeter, plafonne, et arrete au milieu`() = runBlocking {
        val fx = Faux()
        RuleRun(fx).run(listOf(RuleAction(RuleAction.Type.REPEAT, count = 3,
            then = listOf(RuleAction(RuleAction.Type.NOTIFY, text = "x"), RuleAction(RuleAction.Type.WAIT, seconds = 2)))))
        assertEquals(List(3) { "msg:x" }, fx.faits)
        assertEquals(6_000L, fx.attente)

        val trop = Faux()
        RuleRun(trop).run(listOf(RuleAction(RuleAction.Type.REPEAT, count = 1_000,
            then = listOf(RuleAction(RuleAction.Type.NOTIFY, text = "x")))))
        assertEquals(RuleRun.MAX_REPEAT, trop.faits.size)

        val stop = Faux()
        RuleRun(stop).run(listOf(
            RuleAction(RuleAction.Type.REPEAT, count = 5, then = listOf(
                RuleAction(RuleAction.Type.NOTIFY, text = "x"), RuleAction(RuleAction.Type.STOP))),
            RuleAction(RuleAction.Type.NOTIFY, text = "après"),
        ))
        assertEquals("Arrêter sort de tout, pas seulement du tour", listOf("msg:x"), stop.faits)
    }

    @Test
    fun `journal dans la langue de l'appli`() = runBlocking {
        val t = RuleText(Mots)
        val run = RuleRun(Faux(etat(RuleSignal.OUTSIDE_TEMP to 3.0), refuse = setOf("ac"))) { t.action(it) }
        run.run(listOf(
            RuleAction(RuleAction.Type.SET, key = "hvac_power", value = "1"),
            RuleAction(RuleAction.Type.SET, key = "ac", value = "1"),
            RuleAction(RuleAction.Type.IF, condition = cmp(RuleSignal.OUTSIDE_TEMP, RuleOp.BELOW, 0.0)),
            RuleAction(RuleAction.Type.WAIT, seconds = 5),
            RuleAction(RuleAction.Type.STOP),
        ))
        assertEquals(listOf("✓ hvac_power=1", "✗ ac=1", "si OUTSIDE_TEMP < 0 °C → ✗", "… attendre 5 s", "■ arrêter"), run.journal)
        assertEquals("répéter 4 fois", t.action(RuleAction(RuleAction.Type.REPEAT, count = 4)))

        val long = RuleRun(Faux())
        long.run(List(RuleRun.MAX_JOURNAL + 30) { RuleAction(RuleAction.Type.NOTIFY, text = "x") })
        assertEquals("journal plafonné, puis …", RuleRun.MAX_JOURNAL + 1, long.journal.size)
    }

    // ── Étape 3 ─────────────────────────────────────────────────────────────

    @Test
    fun `periode de l'annee, hasard, variable et lieu`() {
        val hiver = RuleCondition(RuleCondition.Type.SEASON, from = 1201, to = 228)
        assertEquals(true, RuleLogic.evaluate(hiver, RuleState(monthDay = 1225)))
        assertEquals(true, RuleLogic.evaluate(hiver, RuleState(monthDay = 115)))
        assertEquals(false, RuleLogic.evaluate(hiver, RuleState(monthDay = 615)))
        assertNull("horloge inconnue", RuleLogic.evaluate(hiver, RuleState()))

        val chance = RuleCondition(RuleCondition.Type.RANDOM, number = 30.0)
        assertEquals(true, RuleLogic.evaluate(chance, RuleState(chance = 12.0)))
        assertEquals(false, RuleLogic.evaluate(chance, RuleState(chance = 30.0)))

        val vars = RuleState(variables = mapOf("COMPTEUR" to "3", "FAIT" to "true"))
        assertEquals(true, RuleLogic.evaluate(RuleCondition(RuleCondition.Type.VARIABLE, text = " compteur ", op = RuleOp.ABOVE, number = 2.0), vars))
        assertEquals(true, RuleLogic.evaluate(RuleCondition(RuleCondition.Type.VARIABLE, text = "fait", op = RuleOp.IS_TRUE), vars))
        assertNull("jamais définie", RuleLogic.evaluate(RuleCondition(RuleCondition.Type.VARIABLE, text = "autre", op = RuleOp.IS_TRUE), vars))

        val maison = RuleCondition(RuleCondition.Type.PLACE, text = "m", op = RuleOp.IS_FALSE)
        assertEquals(true, RuleLogic.evaluate(maison, RuleState(places = mapOf("m" to false))))
        assertNull("position inconnue", RuleLogic.evaluate(maison, RuleState(places = mapOf("m" to null))))
        assertEquals(setOf("m"), RuleLogic.places(CustomRule(condition = RuleCondition(RuleCondition.Type.ALL, children = listOf(maison)))))
    }

    @Test
    fun `soleil et distances`() {
        // Paris, 21 juin, heure d'été (UTC+2) : lever ≈ 5:47, coucher ≈ 21:58.
        val lever = SunCalc.minuteOfDay(172, 48.8566, 2.3522, 120, rising = true)!!
        val coucher = SunCalc.minuteOfDay(172, 48.8566, 2.3522, 120, rising = false)!!
        assertTrue("lever $lever", lever in (5 * 60 + 40)..(5 * 60 + 55))
        assertTrue("coucher $coucher", coucher in (21 * 60 + 50)..(22 * 60 + 5))
        assertEquals(true, SunCalc.isUp(12 * 60, 172, 48.8566, 2.3522, 120))
        assertEquals(false, SunCalc.isUp(23 * 60, 172, 48.8566, 2.3522, 120))
        assertNull("nuit polaire", SunCalc.minuteOfDay(355, 78.2, 15.6, 60, rising = true))
        // Paris → Lyon ≈ 392 km.
        assertTrue(Geo.distanceM(48.8566, 2.3522, 45.764, 4.8357) in 385_000.0..400_000.0)
        assertTrue(RulePlace(lat = 48.8566, lng = 2.3522, radius = 200).contains(48.8575, 2.3522))
    }

    @Test
    fun `declencheurs change et periodique`() {
        val c = ChangeWatch()
        assertFalse("première lecture = départ", c.onValue("P"))
        assertFalse(c.onValue("P"))
        assertFalse("illisible ne compte pas", c.onValue(null))
        assertTrue(c.onValue("D"))
        val p = PeriodicWatch(debut = 0)
        assertFalse("pas de salve au démarrage", p.onTick(1_000, 60_000))
        assertTrue(p.onTick(60_000, 60_000))
        assertFalse(p.onTick(90_000, 60_000))
        assertTrue(p.onTick(120_000, 60_000))
    }

    @Test
    fun `attendre que, question et actions deleguees`() = runBlocking {
        val fx = Faux(etat(RuleSignal.READY to false))
        val run = RuleRun(fx)
        run.run(listOf(RuleAction(RuleAction.Type.WAIT_UNTIL, condition = cmp(RuleSignal.READY, RuleOp.IS_TRUE), seconds = 3),
            RuleAction(RuleAction.Type.NOTIFY, text = "suite")))
        assertEquals("délai écoulé : on continue", listOf("msg:suite"), fx.faits)
        assertEquals(3_000L, fx.attente)

        val non = Faux().also { it.reponse = false }
        RuleRun(non).run(listOf(RuleAction(RuleAction.Type.CONFIRM, text = "Clim ?"), RuleAction(RuleAction.Type.NOTIFY, text = "x")))
        assertEquals("Non arrête", listOf("q:Clim ?"), non.faits)
        val muet = Faux().also { it.reponse = null }
        RuleRun(muet).run(listOf(RuleAction(RuleAction.Type.CONFIRM, text = "Clim ?", value = "yes"), RuleAction(RuleAction.Type.NOTIFY, text = "x")))
        assertEquals("sans réponse = oui si demandé", listOf("q:Clim ?", "msg:x"), muet.faits)

        val d = Faux()
        val r = RuleRun(d)
        r.run(listOf(RuleAction(RuleAction.Type.SET_VARIABLE, key = "N", value = "1"), RuleAction(RuleAction.Type.SPEAK, text = "Bonjour")))
        assertEquals(listOf("SET_VARIABLE:N", "SPEAK:Bonjour"), d.faits)
        assertEquals(2, r.done)
    }

    // ── Enregistrement ──────────────────────────────────────────────────────

    @Test
    fun `aller-retour JSON`() {
        val regles = CustomRuleTemplates.Template.entries.map { CustomRuleTemplates.build(it, it.name, "m") }
        assertEquals(regles, CustomRuleStore.decode(CustomRuleStore.encode(regles)))
    }

    @Test
    fun `JSON ancien ou d'une version plus recente`() {
        // Champs absents : valeurs par défaut. Valeur d'énumération inconnue : null, donc inconnue.
        val json = """[{"id":"a","name":"x","triggers":[{"type":"FUTURE","forSeconds":3}],
            "condition":{"type":"COMPARE","signal":"WARP","op":"IS_TRUE"},
            "actions":[{"type":"TELEPORT"}]}]"""
        val r = CustomRuleStore.decode(json).single()
        assertTrue(r.enabled)
        assertEquals(0, r.cooldownMinutes)
        assertEquals(CustomRule.IfRunning.IGNORE, r.ifRunning)
        assertNull(r.triggers.single().type)
        assertNull(RuleLogic.evaluate(r.condition, RuleState()))
        assertNull(r.actions.single().type)
        assertTrue("illisible : liste vide", CustomRuleStore.decode("{pas du json").isEmpty())
    }

    @Test
    fun `les modeles n'ecrivent que des cles connues`() {
        val cles = setOf(ExternalApi.SET_DRIVE_MODE, ExternalApi.SET_REGEN, ExternalApi.SET_SEAT_HEAT_LEFT,
            ExternalApi.SET_SEAT_HEAT_RIGHT, ExternalApi.SET_STEERING_HEAT, ExternalApi.SET_PROFILE,
            ExternalApi.SET_HVAC_POWER, ExternalApi.SET_HVAC_AC, ExternalApi.SET_HVAC_AUTO, ExternalApi.SET_HVAC_TEMP,
            ExternalApi.SET_HVAC_FAN, ExternalApi.SET_HVAC_RECIRC, ExternalApi.SET_DEFROST_FRONT, ExternalApi.SET_DEFROST_REAR) +
            // Plus les clés propres aux automatisations (lot 1), que RuleWrites sait écrire.
            RuleKeys.ALL
        fun toutes(l: List<RuleAction>?): List<RuleAction> = l.orEmpty().flatMap { listOf(it) + toutes(it.then) + toutes(it.otherwise) }
        for (t in CustomRuleTemplates.Template.entries) {
            val r = CustomRuleTemplates.build(t, "n", "m")
            assertTrue(t.name, r.triggers.isNotEmpty() && r.actions.isNotEmpty())
            toutes(r.actions).filter { it.type == RuleAction.Type.SET }.forEach { assertTrue("${t.name} ${it.key}", it.key in cles) }
            toutes(r.actions).filter { it.type == RuleAction.Type.SHORTCUT }.forEach { a ->
                assertTrue(a.shortcut!!, com.mg4.control.shortcut.ShortcutAction.entries.any { it.name == a.shortcut })
            }
        }
    }

    // ── Textes ──────────────────────────────────────────────────────────────

    private object Mots : RuleWords {
        override fun signal(s: RuleSignal) = s.name
        override fun unit(s: RuleSignal) = if (s == RuleSignal.OUTSIDE_TEMP) " °C" else ""
        override fun value(s: RuleSignal, text: String) = text
        override fun yes(label: String) = "$label : oui"
        override fun no(label: String) = "$label : non"
        override fun event(e: RuleEvent) = e.name
        override fun forSeconds(text: String, seconds: Int) = "$text pendant $seconds s"
        override fun at(time: String) = "à $time"
        override val and = " et "
        override val or = " ou "
        override fun none(text: String) = "aucune de : $text"
        override fun range(from: String, to: String) = "$from–$to"
        override fun days(days: List<Int>) = days.joinToString("")
        override val always = "toujours"
        override fun set(key: String, value: String) = "$key=$value"
        override fun shortcut(name: String) = name
        override fun profile(id: String) = "profil $id"
        override fun waitFor(seconds: Int) = "attendre $seconds s"
        override fun ifThen(condition: String) = "si $condition"
        override fun repeat(times: Int) = "répéter $times fois"
        override fun every(minutes: Int) = "toutes les $minutes min"
        override fun changes(signal: String) = "$signal change"
        override val manual = "à la main"
        override fun sun(e: SunEvent, offset: Int) = "$e $offset"
        override fun random(percent: Int) = "$percent %"
        override fun season(from: Int, to: Int) = "$from-$to"
        override fun variable(name: String, test: String) = "$name $test"
        override fun place(id: String, inside: Boolean) = (if (inside) "dans " else "hors ") + id
        override fun waitUntil(condition: String, seconds: Int) = "attendre que $condition ($seconds s)"
        override fun confirm(text: String) = "demander $text"
        override fun playSound(name: String) = "son $name"
        override fun openApp(pkg: String) = "appli $pkg"
        override fun customShortcut(id: String) = "perso $id"
        override fun ruleEnable(id: String, on: Boolean) = "$id $on"
        override fun runRule(id: String) = "lancer $id"
        override fun setVariable(name: String, value: String) = "$name = $value"
        override fun speak(text: String) = "dire $text"
        override fun navigate(dest: String) = "aller $dest"
        override fun notify(text: String) = "« $text »"
        override val stop = "arrêter"
        override val unknown = "?"
    }

    @Test
    fun `resume lisible`() {
        val t = RuleText(Mots)
        val r = CustomRuleTemplates.build(CustomRuleTemplates.Template.WINTER_MORNING, "Matin", "Bonne route")
        assertEquals("READY : oui", t.triggers(r))
        assertEquals("12345 et (OUTSIDE_TEMP < 5 °C ou 5:00–9:00)", t.conditions(r))
        assertEquals("si OUTSIDE_TEMP < 0 °C → (seat_heat_left=3, seat_heat_right=3, steering_heat=1) ; → (hvac_power=1, hvac_temp=21, hvac_auto=1), attendre 30 s, « Bonne route »", t.actions(r))
        val chaud = CustomRuleTemplates.build(CustomRuleTemplates.Template.HOT_DAY, "x", "y")
        assertEquals("OUTSIDE_TEMP > 27 °C pendant 60 s", t.triggers(chaud))
        assertEquals("sans condition", "toujours",
            t.conditions(CustomRuleTemplates.build(CustomRuleTemplates.Template.STARTUP_MESSAGE, "x", "y")))
        assertEquals("7:05", t.time(425))
    }

    @Test
    fun `bloc si resume avec ce qu'il fait, aucune ecrit a l'endroit`() {
        val t = RuleText(Mots)
        fun profil(id: String) = RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.PROFILE, op = RuleOp.EQUALS, text = id)
        val si = RuleAction(RuleAction.Type.IF, condition = RuleCondition(RuleCondition.Type.ALL, children = listOf(profil("Paul"))),
            then = listOf(RuleAction(RuleAction.Type.ABRP_START, text = "Paul")),
            otherwise = listOf(RuleAction(RuleAction.Type.ABRP_STOP), RuleAction(RuleAction.Type.WAIT, seconds = 5)))
        val r = CustomRule(name = "x", actions = listOf(si))
        assertEquals("si PROFILE = Paul → ABRP → Paul ; → (ABRP ■, attendre 5 s)", t.actions(r))
        // Le journal garde la forme courte : les actions du bloc y ont leurs propres lignes.
        assertEquals("si PROFILE = Paul", t.action(si))
        val aucune = RuleCondition(RuleCondition.Type.NOT, children = listOf(profil("Paul"), profil("Marie")))
        assertEquals("PROFILE ≠ Paul et ≠ Marie", t.condition(aucune))
        // Sans contraire simple (>, plage…) : l'ancienne forme.
        val temp = RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.OUTSIDE_TEMP, op = RuleOp.ABOVE, number = 5.0)
        assertEquals("aucune de : OUTSIDE_TEMP > 5 °C", t.condition(RuleCondition(RuleCondition.Type.NOT, children = listOf(temp))))
        assertEquals("READY : non", t.condition(RuleCondition(RuleCondition.Type.NOT,
            children = listOf(RuleCondition(RuleCondition.Type.COMPARE, signal = RuleSignal.READY, op = RuleOp.IS_TRUE)))))
    }

    @Test
    fun `renommer une variable partout`() {
        val v = { n: String -> RuleCondition(RuleCondition.Type.VARIABLE, text = n, op = RuleOp.EQUALS, value = "1") }
        val r = CustomRule(name = "x",
            triggers = listOf(RuleTrigger(RuleTrigger.Type.BECOMES, condition = v("degivre"))),
            condition = RuleCondition(RuleCondition.Type.ALL, children = listOf(v("DEGIVRE"), v("AUTRE"))),
            actions = listOf(RuleAction(RuleAction.Type.IF, condition = v("DEGIVRE"),
                then = listOf(RuleAction(RuleAction.Type.SET_VARIABLE, key = "DEGIVRE", value = "0")))))
        val n = RuleLogic.renameVariable(r, "DEGIVRE", "fait")
        assertEquals("FAIT", n.triggers[0].condition?.text)
        assertEquals(listOf("FAIT", "AUTRE"), n.condition?.children?.map { it.text })
        assertEquals("FAIT", n.actions[0].condition?.text)
        assertEquals("FAIT", n.actions[0].then?.single()?.key)
    }

    @Test
    fun `memoriser une valeur et regler avec une variable`() {
        assertEquals("1", RuleLogic.storedValue(true))
        assertEquals("0", RuleLogic.storedValue(false))
        assertEquals("12", RuleLogic.storedValue(12.0))
        assertEquals("21.5", RuleLogic.storedValue(21.5))
        assertEquals("P", RuleLogic.storedValue("P"))
        assertEquals(null, RuleLogic.storedValue(null))
        assertEquals("VOL", RuleLogic.variableRef("\$vol"))
        assertEquals(null, RuleLogic.variableRef("12"))
        assertEquals(null, RuleLogic.variableRef("\$"))
        val t = RuleText(Mots)
        assertEquals("volume=\$VOL", t.action(RuleAction(RuleAction.Type.SET, key = "volume", value = "\$VOL")))
        assertEquals("VOL ← VOLUME", t.action(RuleAction(RuleAction.Type.SAVE_VALUE, key = "VOL", text = "VOLUME")))
        assertEquals("son beep", t.action(RuleAction(RuleAction.Type.PLAY_SOUND, key = RuleLogic.BEEP, count = 70)))
        // Renommer la variable suit aussi « mémoriser » et les réglages qui la lisent.
        val r = CustomRule(name = "x", actions = listOf(
            RuleAction(RuleAction.Type.SAVE_VALUE, key = "VOL", text = "VOLUME"),
            RuleAction(RuleAction.Type.SET, key = "volume", value = "\$VOL"),
            RuleAction(RuleAction.Type.SET, key = "volume", value = "12")))
        val n = RuleLogic.renameVariable(r, "VOL", "avant")
        assertEquals("AVANT", n.actions[0].key)
        assertEquals("\$AVANT", n.actions[1].value)
        assertEquals("12", n.actions[2].value)
    }
}
