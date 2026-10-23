package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pré-climatisation (issue #130) : programme des cycles, étape à un instant, retour du conducteur
 * et réglages de clim rétablis à la fin — sans Android.
 */
class PreClimatePlanTest {

    private val delta = 0.0001
    private val min = 60_000L
    /** 17:18, l'heure de la maquette. */
    private val maintenant = 17 * 60 + 18.0

    private fun req(
        mode: PreClimateMode = PreClimateMode.AT_TIME, maxMin: Int = 60, first: Int = 18 * 60 + 30,
        run: Int = 15, runs: Int = 3, pause: Int = 30,
    ) = PreClimateRequest(mode, maxMin, first, run, runs, pause, tempC = 25, fan = 8, ac = true)

    // ── Programme ────────────────────────────────────────────────────────────

    @Test fun `le premier cycle vient a son heure, ou le lendemain si elle est passee`() {
        assertEquals(72.0, PreClimatePlan.minutesUntil(18 * 60 + 30, maintenant), delta)
        // Déjà passée, ou à moins d'une minute : demain.
        assertEquals(1440.0 - 78, PreClimatePlan.minutesUntil(16 * 60, maintenant), delta)
        assertEquals(1440.5, PreClimatePlan.minutesUntil(18 * 60 + 30, 18 * 60 + 29.5), delta)
        assertTrue(PreClimatePlan.isTomorrow(16 * 60, maintenant))
        assertFalse(PreClimatePlan.isTomorrow(18 * 60, maintenant))
    }

    @Test fun `a une heure, les cycles s'enchainent avec leur pause`() {
        val c = PreClimatePlan.cycles(req(), startMs = 0L, nowMinuteOfDay = maintenant)
        assertEquals(3, c.size)
        assertEquals(72 * min, c[0].startMs)
        assertEquals(87 * min, c[0].endMs)
        assertEquals(117 * min, c[1].startMs)
        assertEquals(162 * min, c[2].startMs)
        // Les heures affichées, de 18:30 à 20:15.
        assertEquals(listOf(1110 to 1125, 1155 to 1170, 1200 to 1215), PreClimatePlan.wallTimes(req(), maintenant))
    }

    @Test fun `tout de suite, un seul cycle, toujours borne`() {
        val borne = PreClimatePlan.cycles(req(PreClimateMode.NOW, maxMin = 60), 1_000L, maintenant).single()
        assertEquals(1_000L, borne.startMs)
        assertEquals(1_000L + 60 * min, borne.endMs)
        assertEquals(listOf(1038 to 1098), PreClimatePlan.wallTimes(req(PreClimateMode.NOW, maxMin = 60), maintenant))
        // L'ancien « Sans limite » (0) enregistré redevient la durée par défaut.
        assertEquals(PreClimatePlan.DEFAULT_MAX_MIN, PreClimatePlan.maxMinutes(req(PreClimateMode.NOW, maxMin = 0)))
        assertFalse(0 in PreClimatePlan.MAX_CHOICES)
    }

    @Test fun `le mode chien impose 2 h, en mode Maintenant seulement`() {
        val chien = req(PreClimateMode.NOW, maxMin = 15).copy(dog = true)
        assertEquals(120, PreClimatePlan.maxMinutes(chien))
        assertEquals(1_000L + 120 * min, PreClimatePlan.cycles(chien, 1_000L, maintenant).single().endMs)
        // Sans le mode chien, le choix de l'utilisateur revient.
        assertEquals(15, PreClimatePlan.maxMinutes(chien.copy(dog = false)))
        assertEquals(60, PreClimatePlan.maxMinutes(req(PreClimateMode.AT_TIME, maxMin = 60).copy(dog = true)))
    }

    @Test fun `l'etape suit le programme`() {
        val c = PreClimatePlan.cycles(req(), 0L, maintenant)
        val verrou = 20_000L
        assertEquals(PreClimatePlan.Step.Waiting(0, verrou, 72 * min), PreClimatePlan.step(c, verrou, 30 * min))
        assertEquals(PreClimatePlan.Step.Running(0, 72 * min, 87 * min), PreClimatePlan.step(c, verrou, 80 * min))
        // Entre deux cycles : l'attente part de la fin du précédent.
        assertEquals(PreClimatePlan.Step.Waiting(1, 87 * min, 117 * min), PreClimatePlan.step(c, verrou, 100 * min))
        assertEquals(PreClimatePlan.Step.Done, PreClimatePlan.step(c, verrou, 177 * min))
    }

    @Test fun `la part ecoulee de l'etape fait l'arc`() {
        assertEquals(0.5f, PreClimatePlan.fraction(PreClimatePlan.Step.Waiting(0, 0L, 10 * min), 5 * min), 0.001f)
        assertEquals(0.25f, PreClimatePlan.fraction(PreClimatePlan.Step.Running(0, 0L, 120 * min), 30 * min), 0.001f)
        assertEquals(1f, PreClimatePlan.fraction(PreClimatePlan.Step.Done, 0L), 0.001f)
    }

    // ── Animation ────────────────────────────────────────────────────────────

    @Test fun `la couleur suit l'ecart entre la consigne et l'exterieur`() {
        assertEquals(PreClimateTrend.COOLING, PreClimatePlan.trend(21, 31f))
        assertEquals(PreClimateTrend.HEATING, PreClimatePlan.trend(22, 4f))
        // Moins d'un demi-degré d'écart, ou extérieur illisible : la clim maintient.
        assertEquals(PreClimateTrend.HOLDING, PreClimatePlan.trend(21, 21.4f))
        assertEquals(PreClimateTrend.HOLDING, PreClimatePlan.trend(21, null))
    }

    @Test fun `l'animation est d'autant plus forte que l'ecart est grand`() {
        assertEquals(0.15f, PreClimatePlan.intensity(21, null), 0.001f)
        assertEquals(0.3f + 2f / 14f, PreClimatePlan.intensity(21, 23f), 0.001f)
        assertEquals(1f, PreClimatePlan.intensity(16, 40f), 0.001f)
        assertTrue(PreClimatePlan.intensity(21, 31f) > PreClimatePlan.intensity(21, 25f))
    }

    // ── Réglages ─────────────────────────────────────────────────────────────

    @Test fun `l'heure du premier cycle avance par graduations de 5 minutes, sur 24 h`() {
        assertEquals(1115, PreClimatePlan.stepFirst(1110, 1))
        assertEquals(1115, PreClimatePlan.stepFirst(1112, 1))
        assertEquals(1110, PreClimatePlan.stepFirst(1112, -1))
        assertEquals(1105, PreClimatePlan.stepFirst(1110, -1))
        assertEquals(1435, PreClimatePlan.stepFirst(0, -1))
        assertEquals(0, PreClimatePlan.stepFirst(1435, 1))
        // Proposée la première fois : dans une heure, aux 5 minutes suivantes (17:18 → 18:20).
        assertEquals(18 * 60 + 20, PreClimatePlan.defaultFirst(maintenant))
    }

    @Test fun `les reglages restent dans leurs bornes`() {
        assertEquals(PreClimatePlan.DEFAULT_MAX_MIN, PreClimatePlan.clampMax(45))
        // L'ancien « Sans limite » n'existe plus.
        assertEquals(PreClimatePlan.DEFAULT_MAX_MIN, PreClimatePlan.clampMax(0))
        assertEquals(120, PreClimatePlan.clampMax(120))
        assertEquals(PreClimatePlan.LOCK_MIN_S, PreClimatePlan.clampLock(5))
        assertEquals(PreClimatePlan.SOC_MAX, PreClimatePlan.clampSoc(50))
        assertEquals(PreClimatePlan.RUNS_MAX, PreClimatePlan.clampRuns(9))
    }

    // ── Retour du conducteur, fins ───────────────────────────────────────────

    @Test fun `deux lectures deverrouille de suite font le retour du conducteur`() {
        val w = UnlockWatch()
        assertFalse(w.observe(true))
        assertFalse(w.observe(false))
        assertFalse(w.observe(null))      // lecture ratée : ni pour ni contre
        assertTrue(w.observe(false))
        val v = UnlockWatch()
        v.observe(false)
        v.observe(true)                   // reverrouillé entre-temps : on repart de zéro
        assertFalse(v.observe(false))
    }

    @Test fun `seules la fin du programme et la batterie eteignent la voiture`() {
        assertTrue(PreClimateEnd.DONE.powersOff)
        assertTrue(PreClimateEnd.BATTERY.powersOff)
        listOf(PreClimateEnd.UNLOCKED, PreClimateEnd.DRIVING, PreClimateEnd.STOPPED, PreClimateEnd.CANCELLED)
            .forEach { assertFalse(it.name, it.powersOff) }
    }

    // ── Clim : réglages appliqués, puis rétablis ─────────────────────────────

    @Test fun `la pre-climatisation met en marche, puis regle consigne, ventilation et compresseur`() {
        assertEquals(
            listOf(ClimateStep.PowerOn, ClimateStep.Temp(25), ClimateStep.Fan(8), ClimateStep.Ac(true)),
            PreClimateClimate.applySteps(req()))
    }

    @Test fun `a la fin, les reglages d'avant reviennent - l'exemple de la demande`() {
        // Avant : 21 °C, ventilation 4, A/C coupé, clim en marche. Pendant : 25 °C / 8.
        val avant = ClimateSnapshot(powerOn = true, tempC = 21, fan = 4, auto = false, ac = false, loopMode = 2, airFlow = 1)
        assertEquals(
            listOf(ClimateStep.Temp(21), ClimateStep.Fan(4), ClimateStep.Ac(false), ClimateStep.Loop(2), ClimateStep.Air(1)),
            PreClimateClimate.restoreSteps(avant, presetApplied = true, climOn = true))
    }

    @Test fun `une clim arretee avant est rallumee le temps de retablir ses reglages, puis arretee`() {
        val avant = ClimateSnapshot(powerOn = false, tempC = 21, fan = 4, auto = false, ac = false, loopMode = null, airFlow = null)
        assertEquals(
            listOf(ClimateStep.PowerOn, ClimateStep.Temp(21), ClimateStep.Fan(4), ClimateStep.Ac(false), ClimateStep.PowerOff),
            PreClimateClimate.restoreSteps(avant, presetApplied = true, climOn = false))
    }

    @Test fun `en AUTO avant, on rallume AUTO sans reecrire ventilation ni sens de l'air`() {
        val avant = ClimateSnapshot(powerOn = true, tempC = 22, fan = null, auto = true, ac = true, loopMode = null, airFlow = 3)
        assertEquals(
            listOf(ClimateStep.Temp(22), ClimateStep.AutoOn, ClimateStep.Ac(true)),
            PreClimateClimate.restoreSteps(avant, presetApplied = true, climOn = true))
    }

    @Test fun `sans reglage ecrit, seul l'etat marche ou arret est rendu`() {
        val enMarche = ClimateSnapshot(true, 21, 4, false, false, null, null)
        assertEquals(listOf(ClimateStep.PowerOn), PreClimateClimate.restoreSteps(enMarche, presetApplied = false, climOn = false))
        assertEquals(emptyList<ClimateStep>(), PreClimateClimate.restoreSteps(enMarche, presetApplied = false, climOn = true))
        val arretee = enMarche.copy(powerOn = false)
        assertEquals(listOf(ClimateStep.PowerOff), PreClimateClimate.restoreSteps(arretee, presetApplied = false, climOn = true))
    }
}
