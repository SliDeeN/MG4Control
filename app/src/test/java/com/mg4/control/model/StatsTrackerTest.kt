package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règles de découpage des trajets et des charges.
 *
 * Elles viennent toutes d'un relevé sur la voiture (SWI133, 18-19 septembre 2026) : les compteurs
 * se remettent à zéro au contact, la charge se lit sur un état, et le boîtier peut s'endormir au
 * milieu d'une nuit de charge. Une erreur ici fabrique un historique faux en silence.
 */
class StatsTrackerTest {

    /** Septembre 2026 : l'horloge d'usine d'un boîtier (1970, 2019) est désormais refusée. */
    private val depart = 1_790_000_000_000L
    private var horloge = depart

    private fun snap(
        soc: Float? = 80f,
        odo: Int? = 10_000,
        energie: Float? = 0f,
        charge: Boolean? = false,
        type: ChargeType? = null,
        puissance: Float? = null,
        regen: Float? = null,
        vitesse: Float? = null,
        pasMs: Long = 30_000L,
        temp: Float? = null,
    ): EnergySnapshot {
        horloge += pasMs
        return EnergySnapshot(
            timestampMs = horloge,
            socPercent = soc,
            odometerKm = odo,
            energySinceStartKwh = energie,
            charging = charge,
            chargeType = type,
            powerKw = puissance,
            regenSinceStartKwh = regen,
            speedKmh = vitesse,
            outsideTempC = temp,
        )
    }

    /** Fin de trajet d'une suite de relevés, ou null si rien ne s'est terminé. */
    private fun finDe(events: List<StatsTracker.Event>): Trip? =
        (events.firstOrNull { it is StatsTracker.Event.TripEnded }
            as? StatsTracker.Event.TripEnded)?.trip

    @Test
    fun `un trajet se borne au contact et retient distance et energie`() {
        val t = StatsTracker()
        assertTrue(t.onSnapshot(snap(odo = 10_000, energie = 0f), ready = true).isEmpty())
        t.onSnapshot(snap(odo = 10_020, energie = 3.5f, regen = 0.8f), ready = true)
        val events = t.onSnapshot(snap(odo = 10_020, energie = 3.5f, soc = 74f), ready = false)

        val trip = (events.single() as StatsTracker.Event.TripEnded).trip
        assertEquals(20, trip.distanceKm)
        assertEquals(3.5f, trip.energyKwh, 0.01f)
        assertEquals(0.8f, trip.regenKwh!!, 0.01f)
    }

    @Test
    fun `la vitesse integree donne une distance au dixieme`() {
        val t = StatsTracker()
        // Une minute à 60 km/h relevée toutes les dix secondes, puis la coupure du contact.
        t.onSnapshot(snap(odo = 10_000, vitesse = 60f, pasMs = 10_000L), ready = true)
        repeat(6) { t.onSnapshot(snap(odo = 10_000, vitesse = 60f, pasMs = 10_000L), ready = true) }
        val trip = finDe(t.onSnapshot(snap(odo = 10_000, vitesse = 0f, pasMs = 10_000L), ready = false))!!
        // Six intervalles pleins font le kilomètre, le septième descend de 60 km/h à l'arrêt et
        // vaut 83 mètres : c'est bien la fin du trajet, elle doit compter.
        assertEquals(1.08f, trip.integratedKm!!, 0.05f)
        // L'odomètre n'a pas changé de kilomètre : sans intégration, ce trajet n'existerait pas.
        assertEquals(0, trip.distanceKm)
        assertEquals(1.08f, trip.distance, 0.05f)
    }

    @Test
    fun `un trou de releve trop long n'est pas integre`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000, vitesse = 90f, pasMs = 10_000L), ready = true)
        // Cinq minutes sans relevé : prolonger 90 km/h inventerait 7,5 km.
        t.onSnapshot(snap(odo = 10_007, vitesse = 90f, pasMs = 300_000L), ready = true)
        t.onSnapshot(snap(odo = 10_007, vitesse = 90f, pasMs = 10_000L), ready = true)
        val trip = finDe(t.onSnapshot(snap(odo = 10_007, vitesse = 0f, pasMs = 10_000L), ready = false))!!
        // Seuls les deux derniers intervalles comptent : 250 m + 125 m.
        assertEquals(0.4f, trip.integratedKm!!, 0.05f)
        // Et l'écart avec l'odomètre fait retomber l'affichage sur celui-ci.
        assertEquals(7f, trip.distance, 0.01f)
    }

    @Test
    fun `sans vitesse lisible le trajet garde l'odometre seul`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000), ready = true)
        t.onSnapshot(snap(odo = 10_020), ready = true)
        val trip = finDe(t.onSnapshot(snap(odo = 10_020), ready = false))!!
        assertNull("aucun intervalle intégré : pas de fausse précision", trip.integratedKm)
        assertEquals(20f, trip.distance, 0.01f)
    }

    @Test
    fun `la temperature retenue est la moyenne du trajet, pas celle du depart`() {
        val t = StatsTracker()
        // Départ dans un garage à 18°, puis la vraie température dehors.
        t.onSnapshot(snap(odo = 10_000, temp = 18f), ready = true)
        t.onSnapshot(snap(odo = 10_010, temp = 6f), ready = true)
        t.onSnapshot(snap(odo = 10_020, temp = 4f), ready = true)
        val trip = finDe(t.onSnapshot(snap(odo = 10_020, temp = 4f), ready = false))!!
        assertEquals("(18 + 6 + 4) / 3", 9.3f, trip.outsideTempC!!, 0.05f)
    }

    @Test
    fun `une charge de nuit retient la temperature moyenne`() {
        val t = StatsTracker()
        t.onSnapshot(snap(charge = true, soc = 20f, temp = 12f, type = ChargeType.AC), ready = false)
        t.onSnapshot(snap(charge = true, soc = 50f, temp = 6f, pasMs = 3_600_000L), ready = false)
        val session = (t.onSnapshot(snap(charge = false, soc = 80f, temp = 3f, pasMs = 3_600_000L), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals("(12 + 6) / 2 — le relevé de fin clôt la session", 9f, session.outsideTempC!!, 0.05f)
    }

    @Test
    fun `mettre le contact sans rouler ne cree pas de trajet`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000, energie = 0f, vitesse = 0f), ready = true)
        // Régler la climatisation à l'arrêt : ni distance, ni énergie moteur, vitesse nulle.
        assertTrue(t.onSnapshot(snap(odo = 10_000, energie = 0f, vitesse = 0f), ready = false).isEmpty())
    }

    @Test
    fun `un compteur remis a zero en cours de route donne le total final`() {
        val t = StatsTracker()
        // L'application démarre alors qu'un trajet est déjà en cours : le compteur vaut déjà 9.
        t.onSnapshot(snap(odo = 10_000, energie = 9f), ready = true)
        // Puis le véhicule le remet à zéro (nouveau contact vu de l'extérieur) et remonte à 2.
        t.onSnapshot(snap(odo = 10_030, energie = 2f), ready = true)
        val trip = (t.onSnapshot(snap(odo = 10_030, energie = 2f), ready = false)
            .single() as StatsTracker.Event.TripEnded).trip
        assertEquals("valeur finale prise telle quelle", 2f, trip.energyKwh, 0.01f)
    }

    @Test
    fun `une lecture manquee du contact ne coupe pas le trajet`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000), ready = true)
        assertTrue(t.onSnapshot(snap(odo = 10_010), ready = null).isEmpty())
        assertTrue("le trajet continue", t.tripInProgress)
    }

    @Test
    fun `une charge se mesure sur la difference de pourcentage`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.onSnapshot(snap(soc = 40f, charge = true, type = ChargeType.AC, puissance = 5f), ready = false)
        t.onSnapshot(snap(soc = 50f, charge = true, type = ChargeType.AC, puissance = 5.4f), ready = false)
        val session = (t.onSnapshot(snap(soc = 60f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session

        assertEquals(ChargeType.AC, session.type)
        assertEquals(40f, session.socStart!!, 0.01f)
        assertEquals(60f, session.socEnd!!, 0.01f)
        assertEquals("20 % de 60 kWh", 12f, session.energyKwh!!, 0.01f)
        assertEquals("moyenne des relevés", 5.4f, session.measuredPowerKw!!, 0.05f)
    }

    @Test
    fun `sans releve de puissance la charge n'en invente pas`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.onSnapshot(snap(soc = 40f, charge = true, type = ChargeType.AC), ready = false)
        horloge += 3_600_000L   // une heure de charge, sans que l'écran ait rien pu relever
        val session = (t.onSnapshot(snap(soc = 70f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session

        assertNull("aucune puissance mesurée", session.measuredPowerKw)
        assertNotNull("mais l'énergie reste connue", session.energyKwh)
        // Le repli énergie ÷ durée reste disponible pour l'affichage.
        assertNotNull(session.powerKw)
    }

    @Test
    fun `un trajet interrompu par une coupure est clos au demarrage suivant`() {
        // Une session : le trajet commence et roule, puis le boîtier coupe l'application.
        val premier = StatsTracker()
        premier.onSnapshot(snap(odo = 10_000, energie = 0f), ready = true)
        premier.onSnapshot(snap(odo = 10_042, energie = 8.1f, regen = 1.2f), ready = true)
        val enAttente = premier.pendingState()
        assertTrue("le trajet est bien en attente", enAttente.trip != null)

        // Session suivante : le collecteur redémarre et reprend ce qui restait ouvert.
        val second = StatsTracker()
        val trip = (second.recover(enAttente).single() as StatsTracker.Event.TripEnded).trip
        assertEquals(42, trip.distanceKm)
        assertEquals(8.1f, trip.energyKwh, 0.01f)
        assertEquals("daté du dernier relevé, pas de maintenant", enAttente.trip!!.lastMs, trip.endMs)
        assertEquals(false, second.tripInProgress)
    }

    @Test
    fun `une charge interrompue par une coupure se poursuit jusqu'au reveil`() {
        val premier = StatsTracker(capacityKwh = 60f)
        premier.onSnapshot(snap(soc = 30f, charge = true, type = ChargeType.AC), ready = false)
        premier.onSnapshot(snap(soc = 45f, charge = true, type = ChargeType.AC, puissance = 5f), ready = false)

        // La reprise rouvre la session sans la fermer : la nuit entière reste devant elle.
        val second = StatsTracker(capacityKwh = 60f)
        assertTrue(second.recover(premier.pendingState()).isEmpty())
        assertTrue(second.chargeInProgress)

        // Au réveil, la voiture ne charge plus et affiche 90 % : c'est ce relevé qui clôt.
        val session = (second.onSnapshot(snap(soc = 90f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals("30 → 90 %, et non 30 → 45", 36f, session.energyKwh!!, 0.01f)
        assertEquals(ChargeType.AC, session.type)
        assertTrue("l'application n'a pas vu toute la charge", session.reconstructed)
        assertNull("donc aucune puissance annoncée", session.powerKw)
        assertEquals(false, second.chargeInProgress)
    }

    @Test
    fun `une charge de nuit non observee est reconstituee au reveil`() {
        val t = StatsTracker(capacityKwh = 60f)
        val veille = LastReading(timestampMs = depart, socPercent = 40f)
        assertTrue(t.recover(null, veille).isEmpty())

        // Premier relevé du matin : plus de charge en cours, mais la batterie est pleine.
        val session = (t.onSnapshot(snap(soc = 85f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals(27f, session.energyKwh!!, 0.01f)
        assertTrue(session.reconstructed)
        assertNull("aucune durée vraie, donc aucune puissance", session.powerKw)
        assertNull("le type de prise n'est plus lisible après coup", session.type)
        assertEquals("la session couvre l'intervalle entre les deux relevés", depart, session.startMs)
    }

    @Test
    fun `une charge encore en cours au reveil est antidatee`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.recover(null, LastReading(timestampMs = depart, socPercent = 40f))

        // Toujours branchée au matin : rien n'est clos, la session reprend depuis la veille.
        assertTrue(t.onSnapshot(snap(soc = 85f, charge = true, type = ChargeType.AC), ready = false).isEmpty())
        assertTrue(t.chargeInProgress)

        val session = (t.onSnapshot(snap(soc = 86f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals("40 → 86 %, la nuit comprise", 27.6f, session.energyKwh!!, 0.05f)
        assertTrue(session.reconstructed)
    }

    @Test
    fun `une remontee de quelques dixiemes ne fabrique pas une charge`() {
        val t = StatsTracker(capacityKwh = 60f)
        // La batterie se détend après un trajet : le pourcentage remonte tout seul.
        t.recover(null, LastReading(timestampMs = depart, socPercent = 57.3f))
        assertTrue(t.onSnapshot(snap(soc = 57.9f, charge = false), ready = false).isEmpty())
    }

    @Test
    fun `un ecart de plusieurs semaines ne reconstitue rien`() {
        val t = StatsTracker(capacityKwh = 60f)
        // Enregistrement coupé pendant un mois, puis rallumé : la remontée couvre des charges
        // multiples et l'encadrement n'encadre plus rien.
        t.recover(null, LastReading(timestampMs = horloge - 30L * 24 * 3_600_000L, socPercent = 20f))
        assertTrue(t.onSnapshot(snap(soc = 80f, charge = false), ready = false).isEmpty())
    }

    @Test
    fun `un releve sans pourcentage garde le point de comparaison pour le suivant`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.recover(null, LastReading(timestampMs = depart, socPercent = 40f))
        // Lecture ratée du pourcentage : l'odomètre suffit à rendre l'instantané exploitable.
        assertTrue(t.onSnapshot(snap(soc = null, charge = false), ready = false).isEmpty())
        // Le relevé suivant, lui, conclut.
        val session = (t.onSnapshot(snap(soc = 85f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals(27f, session.energyKwh!!, 0.01f)
    }

    @Test
    fun `un releve a l'horloge d'usine n'ouvre rien`() {
        val t = StatsTracker(capacityKwh = 60f)
        // Réveil de charge, horloge pas encore synchronisée : 1er janvier 2019.
        val usine = EnergySnapshot(
            timestampMs = 1_546_300_800_000L, socPercent = 58f, odometerKm = 10_246,
            charging = true, chargeType = ChargeType.AC,
        )
        assertTrue(t.onSnapshot(usine, ready = false).isEmpty())
        assertFalse("rien n'est ouvert sur une date fausse", t.chargeInProgress)
    }

    @Test
    fun `l'horloge d'usine ne consomme pas le point de comparaison`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.recover(null, LastReading(timestampMs = depart, socPercent = 57.9f))
        // D'abord des relevés mal datés, pendant la synchronisation…
        t.onSnapshot(
            EnergySnapshot(timestampMs = 1_546_300_800_000L, socPercent = 80f, odometerKm = 10_246),
            ready = false,
        )
        // … puis le premier bien daté, qui reconstitue la charge de la nuit.
        val session = (t.onSnapshot(snap(soc = 80f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals("57,9 → 80 % sur 60 kWh", 13.3f, session.energyKwh!!, 0.05f)
        assertEquals(depart, session.startMs)
    }

    @Test
    fun `une session en attente datee de 2019 n'est pas reprise`() {
        val t = StatsTracker(capacityKwh = 60f)
        val etat = PendingState(charge = PendingCharge(
            startMs = 1_546_300_800_000L, socStart = 57.9f, type = ChargeType.AC, tempC = null,
            powerSum = 0f, powerCount = 0, lastMs = 1_546_300_860_000L, socLast = 58f,
        ))
        t.recover(etat, LastReading(timestampMs = depart, socPercent = 57.9f))
        assertFalse("la reprise fabriquerait une session de sept ans", t.chargeInProgress)
        // Le point de comparaison bien daté prend le relais.
        val session = (t.onSnapshot(snap(soc = 80f, charge = false), ready = false)
            .single() as StatsTracker.Event.ChargeEnded).session
        assertEquals(depart, session.startMs)
    }

    @Test
    fun `une prise branchee sans rien injecter ne laisse pas de session`() {
        val t = StatsTracker(capacityKwh = 60f)
        t.onSnapshot(snap(soc = 50f, charge = true, type = ChargeType.AC), ready = false)
        // Débranchée aussitôt, au même pourcentage : il n'y a rien à raconter.
        assertTrue(t.onSnapshot(snap(soc = 50f, charge = false), ready = false).isEmpty())
    }

    @Test
    fun `l'etat en attente se vide quand le trajet se termine normalement`() {
        val t = StatsTracker()
        t.onSnapshot(snap(odo = 10_000, energie = 0f), ready = true)
        t.onSnapshot(snap(odo = 10_010, energie = 2f), ready = true)
        assertTrue(t.pendingState().trip != null)
        t.onSnapshot(snap(odo = 10_010, energie = 2f), ready = false)
        assertTrue("plus rien à reprendre", t.pendingState().isEmpty)
    }

    @Test
    fun `un instantane vide n'ouvre ni trajet ni charge`() {
        val t = StatsTracker()
        assertTrue(t.onSnapshot(EnergySnapshot(timestampMs = 1L), ready = true).isEmpty())
        assertEquals(false, t.tripInProgress)
    }
}
