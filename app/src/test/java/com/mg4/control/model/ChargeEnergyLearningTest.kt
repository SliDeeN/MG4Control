package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Énergie des charges des Statistiques (issue #156) : table apprise par tranche de 1 %, découpage
 * des tranches pendant une charge vue, et choix de l'énergie retenue — sans Android.
 *
 * Né d'une charge rapide de 6 min : 4,93 kWh à la borne, 3,6 kWh dans l'appli (écart de % ×
 * capacité). Le pourcentage affiché ne suit pas l'énergie en charge rapide.
 */
class ChargeEnergyLearningTest {

    private val delta = 0.001f
    /** 58,9 kWh utiles : 0,589 kWh par % par défaut. */
    private val capacite = 58.9f
    private val defaut = capacite / 100f

    // ── Table apprise ────────────────────────────────────────────────────────

    @Test fun `une tranche apprise s'utilise des la deuxieme mesure, les autres valent le defaut`() {
        val t = SocEnergyTable()
        assertTrue(t.observer(64, 0.80f, defaut))
        // Une seule mesure : pas encore assez sûre.
        assertNull(t.valeur(64))
        assertTrue(t.observer(64, 0.82f, defaut))
        assertEquals(0.81f, t.valeur(64)!!, delta)
        // 64 → 66 : la tranche 64 apprise, la 65 au défaut.
        val e = t.estimer(64f, 66f, defaut)
        assertEquals(0.81f + defaut, e.kwh, delta)
        assertEquals(1, e.tranchesApprises)
        assertEquals(2, e.tranches)
    }

    @Test fun `les tranches commencees comptent au prorata`() {
        val t = SocEnergyTable()
        repeat(2) { t.observer(64, 0.8f, defaut) }
        // 64,5 → 65 : la moitié de la tranche 64.
        assertEquals(0.4f, t.estimer(64.5f, 65f, defaut).kwh, delta)
        // Sans montée, rien.
        assertEquals(0f, t.estimer(70f, 70f, defaut).kwh, delta)
        assertEquals(0f, t.estimer(70f, 65f, defaut).kwh, delta)
    }

    @Test fun `une mesure absurde est refusee`() {
        val t = SocEnergyTable()
        assertFalse(t.observer(50, 0.05f, defaut))   // 10 fois trop peu
        assertFalse(t.observer(50, 5f, defaut))      // 8 fois trop
        assertFalse(t.observer(100, 0.6f, defaut))   // hors des tranches 0..99
        assertEquals(0, t.apprises())
    }

    @Test fun `les mesures recentes comptent davantage`() {
        val t = SocEnergyTable()
        repeat(10) { t.observer(30, 0.60f, defaut) }
        repeat(3) { t.observer(30, 0.70f, defaut) }
        // Une moyenne simple donnerait 0,623 ; la table a déjà bien bougé vers 0,70.
        assertTrue(t.valeur(30)!! > 0.64f)
    }

    @Test fun `la table se garde et se relit a l'identique`() {
        val t = SocEnergyTable()
        repeat(2) { t.observer(10, 0.55f, defaut) }
        repeat(3) { t.observer(79, 0.66f, defaut) }
        val relue = SocEnergyTable.decoder(t.encoder())
        assertEquals(t.valeur(10)!!, relue.valeur(10)!!, delta)
        assertEquals(t.valeur(79)!!, relue.valeur(79)!!, delta)
        assertEquals(2, relue.apprises())
        // Rien, ou un texte abîmé : une table vide, pas une erreur.
        assertEquals(0, SocEnergyTable.decoder(null).apprises())
        assertEquals(0, SocEnergyTable.decoder("n'importe quoi").apprises())
    }

    // ── Découpage des tranches pendant une charge vue ────────────────────────

    @Test fun `chaque pourcentage entier franchi rend l'energie de sa tranche`() {
        val r = SocBandRecorder()
        // Premier passage : il ne fait que poser la référence (la tranche était entamée).
        assertNull(r.observer(0L, 63.6f, 0f))
        assertNull(r.observer(2_000L, 63.9f, 0.10f))
        assertNull(r.observer(4_000L, 64.1f, 0.30f))     // 64 atteint : référence posée là
        assertNull(r.observer(6_000L, 64.6f, 0.60f))
        val tranche = r.observer(8_000L, 65.0f, 0.95f)    // 65 atteint : tranche 64 bouclée
        assertEquals(64, tranche!!.first)
        assertEquals(0.65f, tranche.second, delta)
    }

    @Test fun `un trou, un saut ou un recul repartent de zero`() {
        val r = SocBandRecorder()
        r.observer(0L, 64.1f, 0f)
        r.observer(2_000L, 64.5f, 0.3f)
        // Boîtier endormi 20 s : on ne sait pas ce qui est entré pendant.
        assertNull(r.observer(22_000L, 65.2f, 0.5f))
        // Après le trou, la tranche 65 était entamée : elle ne compte pas, la référence se pose à 66…
        assertNull(r.observer(24_000L, 66.0f, 1.1f))
        // … et la tranche 66, complète, compte.
        val t66 = r.observer(26_000L, 67.0f, 1.7f)
        assertEquals(66, t66!!.first)
        assertEquals(0.6f, t66.second, delta)
        // Deux % d'un coup : rien.
        assertNull(r.observer(28_000L, 69.1f, 2.9f))
        // Le pourcentage redescend : rien non plus.
        assertNull(r.observer(30_000L, 68.0f, 3.0f))
    }

    // ── Ce que le suivi a vu d'une charge ────────────────────────────────────

    @Test fun `une charge suivie de bout en bout est complete`() {
        val log = ChargeLog(debutMs = 0L, debutHorlogeMs = null, socDebut = 64f, autonomieDebutKm = null)
        var t = 0L
        while (t <= 60_000L) { log.ajouter(t, 47f, 64f + t / 10_000f); t += 2_000L }
        log.terminer(62_000L)
        val o = log.observation()
        assertTrue(o.complete)
        assertEquals(47f * 60_000f / 3_600_000f, o.energieKwh, 0.01f)
        assertEquals(64f, o.socDebut!!, delta)
    }

    @Test fun `un sommeil pendant la charge rend l'observation partielle`() {
        val log = ChargeLog(debutMs = 0L, debutHorlogeMs = null, socDebut = 64f, autonomieDebutKm = null)
        log.ajouter(0L, 47f, 64f)
        log.ajouter(2_000L, 47f, 64.2f)
        // Verrouillage : l'écran s'éteint, le suivi reprend dix minutes plus tard, charge finie.
        log.terminer(602_000L)
        val o = log.observation()
        assertFalse(o.complete)
        assertEquals(64.2f, o.socFinVue!!, delta)
        assertEquals(2_000L, o.dureeMs)
    }

    @Test fun `une pause de la borne n'est pas un trou`() {
        val log = ChargeLog(debutMs = 0L, debutHorlogeMs = null, socDebut = 50f, autonomieDebutKm = null)
        log.ajouter(0L, 40f, 50f)
        log.ajouter(2_000L, 40f, 50.1f)
        log.terminer(4_000L)
        log.reprendre()                    // la borne reprend deux minutes plus tard
        log.ajouter(124_000L, 40f, 50.2f)
        log.ajouter(126_000L, 40f, 50.3f)
        log.terminer(128_000L)
        assertTrue(log.observation().complete)
    }

    // ── Énergie retenue ──────────────────────────────────────────────────────

    @Test fun `en AC, toujours le pourcentage, comme avant`() {
        val obs = ChargeObservation(socDebut = 45.7f, energieKwh = 11.5f, dureeMs = 8_640_000L, socFinVue = 67.5f, complete = true)
        val r = ChargeEnergy.calculer(ChargeType.AC, 45.7f, 67.5f, capacite, obs, SocEnergyTable())
        assertEquals(ChargeEnergySource.PERCENT, r.source)
        assertEquals((21.8f / 100f * capacite).roundTenth(), r.kwh!!, delta)
        assertNull(r.puissanceKw)
    }

    @Test fun `en DC vue en entier, l'energie mesuree et sa puissance moyenne`() {
        // La charge de l'issue : 6 min 22 s à ~47 kW.
        val obs = ChargeObservation(socDebut = 63.9f, energieKwh = 4.8f, dureeMs = 382_000L, socFinVue = 70f, complete = true)
        val r = ChargeEnergy.calculer(ChargeType.DC, 64.1f, 70f, capacite, obs, SocEnergyTable())
        assertEquals(ChargeEnergySource.MEASURED, r.source)
        assertEquals(4.8f, r.kwh!!, delta)
        assertEquals(4.8f / (382_000f / 3_600_000f), r.puissanceKw!!, 0.1f)
    }

    @Test fun `en DC vue en partie, la mesure puis la table pour le reste`() {
        val t = SocEnergyTable()
        repeat(2) { (65..69).forEach { b -> t.observer(b, 0.8f, defaut) } }
        // Vue de 64 à 65 % (0,8 kWh), puis verrouillage ; on retrouve 70 % au réveil.
        val obs = ChargeObservation(socDebut = 64f, energieKwh = 0.8f, dureeMs = 60_000L, socFinVue = 65f, complete = false)
        val r = ChargeEnergy.calculer(ChargeType.DC, 64f, 70f, capacite, obs, t)
        assertEquals(ChargeEnergySource.MIXED, r.source)
        assertEquals(0.8f + 5 * 0.8f, r.kwh!!, 0.05f)
    }

    @Test fun `le debut d'une charge vue en cours de route est estime, pas perdu`() {
        // Branchée écran éteint à 30 %, voiture rallumée à 40 % : le suivi la voit de 40 à 70 %.
        val obs = ChargeObservation(socDebut = 40f, energieKwh = 18f, dureeMs = 1_200_000L, socFinVue = 70f, complete = true)
        val r = ChargeEnergy.calculer(ChargeType.DC, 30f, 70f, capacite, obs, SocEnergyTable())
        assertEquals(ChargeEnergySource.MIXED, r.source)
        assertEquals((10f * defaut + 18f).roundTenth(), r.kwh!!, delta)
        // Le suivi a vu le début avant l'onglet Statistiques (2 s contre 30 s) : rien d'estimé.
        val vue = ChargeEnergy.calculer(ChargeType.DC, 63.9f, 70f, capacite, obs.copy(socDebut = 63.9f, energieKwh = 4.8f), SocEnergyTable())
        assertEquals(ChargeEnergySource.MEASURED, vue.source)
    }

    @Test fun `en DC jamais vue, la table, ou le pourcentage tant qu'elle est vide`() {
        val vide = ChargeEnergy.calculer(ChargeType.DC, 64f, 70f, capacite, null, SocEnergyTable())
        assertEquals(ChargeEnergySource.PERCENT, vide.source)
        assertEquals((6f * defaut).roundTenth(), vide.kwh!!, delta)
        val t = SocEnergyTable()
        repeat(2) { (64..69).forEach { b -> t.observer(b, 0.8f, defaut) } }
        val appris = ChargeEnergy.calculer(ChargeType.DC, 64f, 70f, capacite, null, t)
        assertEquals(ChargeEnergySource.LEARNED, appris.source)
        assertEquals(4.8f, appris.kwh!!, 0.05f)
    }

    @Test fun `corriger la batterie ne touche pas une energie mesuree`() {
        val s = ChargeSession(startMs = 0L, endMs = 382_000L, type = ChargeType.DC, socStart = 64f, socEnd = 70f,
            energyKwh = 4.8f, measuredPowerKw = 45f, outsideTempC = null, energySource = ChargeEnergySource.MEASURED)
        assertEquals(4.8f, s.withCapacity(40f).energyKwh!!, delta)
        // Une charge au pourcentage (ou enregistrée avant l'issue #156) suit toujours la batterie.
        assertEquals(2.4f, s.copy(energySource = null).withCapacity(40f).energyKwh!!, delta)
        assertEquals(2.4f, s.copy(energySource = ChargeEnergySource.PERCENT).withCapacity(40f).energyKwh!!, delta)
    }
}
