package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbrpTelemetryTest {

    private val tous = AbrpAccount(name = "Paul", token = "abcdef123456", off = emptyList())

    @Test
    fun `utc et soc seuls quand rien d'autre n'est lu`() {
        assertEquals("{\"utc\":1700000000,\"soc\":62.0}", AbrpSample(1_700_000_000L, soc = 62f).json(tous))
    }

    @Test
    fun `sans niveau de batterie le releve ne part pas`() {
        assertFalse(AbrpSample(1L).canSend)
        assertTrue(AbrpSample(1L, soc = 0f).canSend)
    }

    @Test
    fun `champs complets en notation anglaise`() {
        val j = AbrpSample(10L, soc = 55.5f, speedKmh = 87.4f, powerKw = 41.236f, charging = false, parked = false,
            outsideTempC = 12.6f, rangeKm = 250, odometerKm = 12_345, capacityKwh = 61.7f, hvacSetpointC = 21f,
            lat = 48.858370, lon = 2.294481).json(tous)
        assertEquals("{\"utc\":10,\"soc\":55.5,\"speed\":87,\"power\":41.24,\"is_charging\":0,\"is_parked\":0,\"ext_temp\":13," +
            "\"est_battery_range\":250,\"odometer\":12345,\"capacity\":61.70,\"soe\":34.24,\"hvac_setpoint\":21," +
            "\"lat\":48.858370,\"lon\":2.294481}", j)
    }

    @Test
    fun `valeur illisible ou invraisemblable omise jamais zero`() {
        val j = AbrpSample(1L, soc = 50f, outsideTempC = 0f, rangeKm = 0, odometerKm = 0, capacityKwh = 0f, lat = 0.0, lon = 0.0).json(tous)
        assertEquals("{\"utc\":1,\"soc\":50.0}", j)
    }

    @Test
    fun `DC et energie chargee seulement en charge`() {
        assertEquals("{\"utc\":1,\"soc\":50.0,\"is_charging\":0}", AbrpSample(1L, soc = 50f, charging = false, dcfc = true, kwhCharged = 3f).json(tous))
        assertEquals("{\"utc\":1,\"soc\":50.0,\"is_charging\":1,\"is_dcfc\":1,\"kwh_charged\":12.50}",
            AbrpSample(1L, soc = 50f, charging = true, dcfc = true, kwhCharged = 12.5f).json(tous))
    }

    @Test
    fun `groupes coupes non envoyes, altitude coupee par defaut`() {
        val e = AbrpSample(1L, soc = 50f, speedKmh = 30f, lat = 45.0, lon = 5.0, elevationM = 300.0, heading = 90f)
        assertEquals("{\"utc\":1,\"soc\":50.0,\"speed\":30,\"lat\":45.000000,\"lon\":5.000000}", e.json(AbrpAccount(token = "x")))
        assertEquals("{\"utc\":1,\"soc\":50.0,\"speed\":30}", e.json(AbrpAccount(token = "x", off = listOf("POSITION"))))
        assertEquals("{\"utc\":1,\"soc\":50.0,\"speed\":30,\"lat\":45.000000,\"lon\":5.000000,\"elevation\":300,\"heading\":90}", e.json(tous))
        // Le niveau de batterie ne se coupe pas.
        assertTrue(AbrpAccount(off = listOf("SOC")).sends(AbrpField.SOC))
    }

    @Test
    fun `cadence selon l'etat`() {
        val c = AbrpCadence(drivingSeconds = 30, chargingSeconds = 60, parkedMinutes = 15)
        assertEquals(30_000L, c.intervalMs(parked = false, charging = false))
        assertEquals(30_000L, c.intervalMs(parked = null, charging = null))   // inconnu = en marche
        assertEquals(60_000L, c.intervalMs(parked = true, charging = true))
        assertEquals(900_000L, c.intervalMs(parked = true, charging = false))
        assertNull(AbrpCadence(parkedMinutes = 0).intervalMs(parked = true, charging = false))
    }

    @Test
    fun `a l'arret jusqu'a une minute, 15 min par defaut, et estimation des donnees`() {
        assertEquals(listOf(1, 5, 15, 0), AbrpCadence.PARKED_CHOICES)
        assertEquals(15, AbrpCadence().parkedMinutes)
        val uneMinute = AbrpCadence(parkedMinutes = 1)
        assertEquals(60_000L, uneMinute.intervalMs(parked = true, charging = false))
        assertFalse(uneMinute.due(59_000L, 1L, parked = true, charging = false, changed = false))
        assertTrue(uneMinute.due(61_000L, 1L, parked = true, charging = false, changed = false))
        // Même calcul qu'avant pour la route (30 s ≈ 144 Ko/h) ; à l'arrêt coupé : rien.
        assertEquals(144, AbrpCadence.kbPerHour(30))
        assertEquals(72, AbrpCadence.kbPerHour(60))
        assertEquals(0, AbrpCadence.kbPerHour(0))
    }

    @Test
    fun `premier releve et transitions partent tout de suite`() {
        val c = AbrpCadence()
        assertTrue(c.due(1_000L, 0L, parked = true, charging = false, changed = false))
        assertTrue(c.due(1_000L, 999L, parked = true, charging = false, changed = true))
        assertFalse(c.due(20_000L, 1L, parked = false, charging = false, changed = false))
        assertTrue(c.due(31_000L, 1L, parked = false, charging = false, changed = false))
        assertFalse(AbrpCadence(parkedMinutes = 0).due(99_999_999L, 1L, parked = true, charging = false, changed = false))
    }

    @Test
    fun `fichier de comptes par sections`() {
        val l = AbrpConfigFile.parse("""
            # commentaire
            [Paul]
            token = aaa-111
            [Marie]
            TOKEN=bbb-222
            api_key = "kkk"
            [Vide]
            # pas de jeton
        """.trimIndent())
        assertEquals(listOf(AbrpConfigFile.Entry("Paul", "aaa-111", null), AbrpConfigFile.Entry("Marie", "bbb-222", "kkk")), l)
    }

    @Test
    fun `fichier sans section comme EVABRPUploader`() {
        val l = AbrpConfigFile.parse("﻿api_key = k1\r\ntoken = t1\r\ninterval_sec = 60\r\n", defaultName = "ABRP")
        assertEquals(listOf(AbrpConfigFile.Entry("ABRP", "t1", "k1")), l)
    }

    @Test
    fun `modele relu sans compte`() {
        val m = AbrpConfigFile.template(listOf("ligne"), listOf("Paul", "Marie"))
        assertTrue(m.startsWith("# ligne\n"))
        assertTrue(m.contains("[Paul]\ntoken = \napi_key = \n"))
        // La clé commune, avant les sections.
        assertTrue(m.contains("\napi_key = \n\n[Paul]"))
        // Jetons et clés vides : rien d'importable tant qu'il n'est pas rempli.
        assertTrue(AbrpConfigFile.parse(m).isEmpty())
    }

    @Test
    fun `cle commune, cle propre, retour a la cle MG4Control`() {
        val l = AbrpConfigFile.parse("api_key = commune\n[Paul]\ntoken = t1\n[Marie]\ntoken = t2\napi_key = perso\n" +
            "[Luc]\ntoken = t3\napi_key = MG4Control\n[Zoe]\n")
        assertEquals(listOf(
            AbrpConfigFile.Entry("Paul", "t1", "commune"),
            AbrpConfigFile.Entry("Marie", "t2", "perso"),
            AbrpConfigFile.Entry("Luc", "t3", AbrpConfigFile.USE_APP_KEY),
            // Sans jeton : seulement la clé, pour un compte qui existe déjà.
            AbrpConfigFile.Entry("Zoe", null, "commune"),
        ), l)
        // Sans clé commune, une section vide n'est rien.
        assertTrue(AbrpConfigFile.parse("[Zoe]\n").isEmpty())
    }

    @Test
    fun `journal espace - premier, transitions, nouvelle erreur, bilan`() {
        val p = AbrpLogPolicy
        val dix = AbrpLogPolicy.SUMMARY_MS
        // Premier envoi : toujours écrit.
        assertTrue(p.shouldLog(1_000L, null, ok = true, lastOk = null, error = null, lastError = null, stateChanged = false))
        // Réussites suivantes : tues jusqu'au bilan.
        assertFalse(p.shouldLog(31_000L, 1_000L, true, true, null, null, false))
        assertTrue(p.shouldLog(1_000L + dix, 1_000L, true, true, null, null, false))
        // Passage en P ou en charge : écrit.
        assertTrue(p.shouldLog(31_000L, 1_000L, true, true, null, null, stateChanged = true))
        // Échec après réussite, puis même erreur tue, puis erreur différente écrite, puis retour.
        assertTrue(p.shouldLog(31_000L, 1_000L, false, true, "réseau", null, false))
        assertFalse(p.shouldLog(61_000L, 31_000L, false, false, "réseau", "réseau", false))
        assertTrue(p.shouldLog(91_000L, 31_000L, false, false, "HTTP 401", "réseau", false))
        assertTrue(p.shouldLog(121_000L, 91_000L, true, false, null, "HTTP 401", false))
    }

    @Test
    fun `masque du jeton`() {
        assertEquals("••••3456", maskSecret("abcdef123456"))
        assertEquals("••••", maskSecret("abc"))
    }

    @Test
    fun `modele ABRP selon le profil`() {
        val r = CustomRuleTemplates.abrpByProfile("ABRP", listOf("p1" to "a1", "p2" to "a2"), "a1")
        assertTrue(r.onlyWhenReady)
        assertEquals(listOf(RuleTrigger.Type.BECOMES, RuleTrigger.Type.CHANGES), r.triggers.map { it.type })
        assertEquals(RuleSignal.PROFILE, r.triggers[1].condition?.signal)
        // Un bloc Si par paire, puis l'arrêt pour un profil sans compte : rien d'imbriqué.
        assertEquals(3, r.actions.size)
        assertEquals("a2", r.actions[1].then?.single()?.text)
        assertEquals(RuleAction.Type.ABRP_START, r.actions[1].then?.single()?.type)
        assertEquals(RuleCondition.Type.NOT, r.actions[2].condition?.type)
        assertEquals(listOf("p1", "p2"), r.actions[2].condition?.children?.map { it.text })
        assertEquals(RuleAction.Type.ABRP_STOP, r.actions[2].then?.single()?.type)
        // Un seul profil : un bloc Si / Sinon.
        val un = CustomRuleTemplates.abrpByProfile("ABRP", listOf("p1" to "a1"), "a1").actions.single()
        assertEquals(RuleAction.Type.ABRP_START, un.then?.single()?.type)
        assertEquals(RuleAction.Type.ABRP_STOP, un.otherwise?.single()?.type)
        // Sans profil : le premier compte, sans condition.
        assertEquals(listOf(RuleAction(RuleAction.Type.ABRP_START, text = "a1")), CustomRuleTemplates.abrpByProfile("ABRP", emptyList(), "a1").actions)
    }
}
