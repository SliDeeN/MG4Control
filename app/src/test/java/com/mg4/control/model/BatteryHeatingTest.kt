package com.mg4.control.model

import com.mg4.control.model.BatteryHeatingCountdown.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chauffage intelligent de la batterie : lecture d'origine de chaque plateforme, et décompte de la
 * coupure automatique (au démarrage, à l'activation en route, annulation, grâce après coupure).
 */
class BatteryHeatingTest {

    private val minute = 60_000L
    private val trente = 30 * minute

    @Test
    fun `consigne identique partout`() {
        assertEquals(1, BatteryHeating.SET_ON)
        assertEquals(2, BatteryHeating.SET_OFF)
    }

    @Test
    fun `lecture de l ancienne plateforme 0 active`() {
        assertEquals(true, BatteryHeating.stateFromRaw(0, a9 = false))
        assertEquals(false, BatteryHeating.stateFromRaw(1, a9 = false))
        assertEquals(false, BatteryHeating.stateFromRaw(2, a9 = false))
        assertNull(BatteryHeating.stateFromRaw(-1, a9 = false))
    }

    @Test
    fun `lecture A9 1 active`() {
        assertEquals(true, BatteryHeating.stateFromRaw(1, a9 = true))
        assertEquals(false, BatteryHeating.stateFromRaw(0, a9 = true))
        assertEquals(false, BatteryHeating.stateFromRaw(2, a9 = true))
        assertNull(BatteryHeating.stateFromRaw(-1, a9 = true))
    }

    @Test
    fun `deja actif au demarrage le decompte part du demarrage`() {
        val d = BatteryHeatingCountdown()
        assertEquals(Decision.DEBUT, d.tick(0L, actif = true, dureeMs = trente))
        assertEquals(trente, d.echeanceMs(trente))
        assertEquals(Decision.RIEN, d.tick(trente - 1, actif = true, dureeMs = trente))
        assertEquals(Decision.COUPER, d.tick(trente, actif = true, dureeMs = trente))
        assertNull(d.echeanceMs(trente))
    }

    @Test
    fun `active en route le decompte part de l activation`() {
        val d = BatteryHeatingCountdown()
        assertEquals(Decision.RIEN, d.tick(0L, actif = false, dureeMs = trente))
        assertEquals(Decision.RIEN, d.tick(10 * minute, actif = false, dureeMs = trente))
        assertEquals(Decision.DEBUT, d.tick(12 * minute, actif = true, dureeMs = trente))
        assertEquals(Decision.RIEN, d.tick(41 * minute, actif = true, dureeMs = trente))
        assertEquals(Decision.COUPER, d.tick(42 * minute, actif = true, dureeMs = trente))
    }

    @Test
    fun `coupe avant l echeance puis reactive repart de zero`() {
        val d = BatteryHeatingCountdown()
        d.tick(0L, actif = true, dureeMs = trente)
        assertEquals(Decision.ANNULE, d.tick(20 * minute, actif = false, dureeMs = trente))
        assertEquals(Decision.DEBUT, d.tick(25 * minute, actif = true, dureeMs = trente))
        assertEquals(Decision.RIEN, d.tick(54 * minute, actif = true, dureeMs = trente))
        assertEquals(Decision.COUPER, d.tick(55 * minute, actif = true, dureeMs = trente))
    }

    @Test
    fun `etat illisible ne change rien`() {
        val d = BatteryHeatingCountdown()
        d.tick(0L, actif = true, dureeMs = trente)
        assertEquals(Decision.RIEN, d.tick(10 * minute, actif = null, dureeMs = trente))
        assertEquals(trente, d.echeanceMs(trente))
        assertEquals(Decision.COUPER, d.tick(trente, actif = true, dureeMs = trente))
    }

    @Test
    fun `nouveau trajet le decompte ne survit pas`() {
        val d = BatteryHeatingCountdown()
        d.tick(0L, actif = true, dureeMs = trente)
        assertTrue(d.reset())
        assertFalse(d.reset())
        // Toujours activé au démarrage suivant : nouveau décompte complet.
        assertEquals(Decision.DEBUT, d.tick(3 * 60 * minute, actif = true, dureeMs = trente))
        assertEquals(3 * 60 * minute + trente, d.echeanceMs(trente))
    }

    @Test
    fun `apres une coupure l etat relu active pendant la grace n est pas une reactivation`() {
        val d = BatteryHeatingCountdown()
        d.tick(0L, actif = true, dureeMs = trente)
        assertEquals(Decision.COUPER, d.tick(trente, actif = true, dureeMs = trente))
        val t = trente + BatteryHeatingCountdown.GRACE_MS - 1
        assertEquals(Decision.RIEN, d.tick(t, actif = true, dureeMs = trente))
        assertNull(d.echeanceMs(trente))
        // Toujours activé après la grâce : réactivé par l'utilisateur, ou coupure refusée.
        assertEquals(Decision.DEBUT, d.tick(t + 1, actif = true, dureeMs = trente))
    }

    @Test
    fun `duree changee en route deplace l echeance`() {
        val d = BatteryHeatingCountdown()
        d.tick(0L, actif = true, dureeMs = trente)
        assertEquals(Decision.RIEN, d.tick(10 * minute, actif = true, dureeMs = trente))
        // Ramenée à 10 min alors que 10 min sont déjà écoulées : coupure au passage suivant.
        assertEquals(Decision.COUPER, d.tick(10 * minute + 5_000, actif = true, dureeMs = 10 * minute))
    }
}
