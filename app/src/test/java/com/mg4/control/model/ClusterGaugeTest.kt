package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Les deux modules de la zone centrale du combiné : ce qu'affiche chaque jauge, et où se place
 * le carré sur l'écran du combiné.
 */
class ClusterGaugeTest {

    private val delta = 0.001f

    // ── A : jauge de consommation ────────────────────────────────────────────

    @Test fun `en roulant, la jauge donne des Wh par km`() {
        val s = ClusterGauge.consumption(kw = 26f, kmh = 130f)
        assertEquals(GaugeUnit.WH_PER_KM, s.unit)
        assertEquals(200f, s.value, delta)
        assertEquals(0.32f, s.fraction, 0.005f)
        assertEquals(GaugeLevel.CONSUMPTION, s.level)
    }

    // ── Échelle (essai en roulant du 2026-10-14 : pointes à plus de 2 500 Wh/km) ──

    @Test fun `l anneau va jusqu a 3000 Wh par km`() {
        assertEquals(0f, ClusterGauge.consumptionFraction(0f), delta)
        assertEquals(1f, ClusterGauge.consumptionFraction(3000f), delta)
        assertEquals(1f, ClusterGauge.consumptionFraction(8000f), delta)
    }

    @Test fun `une pointe a 2500 se lit encore sans remplir l anneau`() {
        val f = ClusterGauge.consumptionFraction(2500f)
        assertTrue(f in 0.9f..0.99f)
    }

    @Test fun `la conduite courante garde de la place sur l anneau`() {
        // Linéaire jusqu'à 3 000, 150 Wh/km n'en ferait que 5 % : l'anneau ne bougerait qu'aux pointes.
        assertTrue(ClusterGauge.consumptionFraction(150f) > 0.25f)
        assertTrue(ClusterGauge.consumptionFraction(500f) in 0.45f..0.55f)
    }

    @Test fun `l anneau monte toujours avec la consommation`() {
        var avant = -1f
        for (wh in 0..3000 step 50) {
            val f = ClusterGauge.consumptionFraction(wh.toFloat())
            assertTrue("$wh Wh/km", f > avant)
            avant = f
        }
    }

    @Test fun `la consommation garde une seule couleur, quelle qu en soit la valeur`() {
        // Demande du 2026-10-14 : orange partout, plus de vert ni de rouge selon les Wh/km.
        assertEquals(GaugeLevel.CONSUMPTION, ClusterGauge.consumption(6f, 50f).level)     // 120 Wh/km
        assertEquals(GaugeLevel.CONSUMPTION, ClusterGauge.consumption(15f, 50f).level)    // 300 Wh/km
        // À l'arrêt aussi : c'est de la consommation (clim, accessoires).
        assertEquals(GaugeLevel.CONSUMPTION, ClusterGauge.consumption(1.8f, 0f).level)
    }

    @Test fun `une forte acceleration remplit l anneau sans le depasser`() {
        val s = ClusterGauge.consumption(95f, 20f)   // 4 750 Wh/km
        assertEquals(1f, s.fraction, delta)
        assertEquals(GaugeLevel.CONSUMPTION, s.level)
    }

    @Test fun `en recuperation, la jauge donne les kW recuperes`() {
        val s = ClusterGauge.consumption(-38f, 50f)
        assertEquals(GaugeUnit.KW_REGEN, s.unit)
        assertEquals(38f, s.value, delta)
        assertEquals(38f / 60f, s.fraction, delta)
        assertEquals(GaugeLevel.REGEN, s.level)
    }

    @Test fun `a l arret, des Wh par km n ont pas de sens - on donne des kW`() {
        val s = ClusterGauge.consumption(1.8f, 0f)
        assertEquals(GaugeUnit.KW_STOPPED, s.unit)
        assertEquals(1.8f, s.value, delta)
        assertEquals(1.8f / 20f, s.fraction, delta)
        // Vitesse illisible : même chose, plutôt qu'une division par une valeur inconnue.
        assertEquals(GaugeUnit.KW_STOPPED, ClusterGauge.consumption(1.8f, null).unit)
        assertEquals(GaugeUnit.KW_STOPPED, ClusterGauge.consumption(1.8f, 4.9f).unit)
    }

    // ── B : puissance ────────────────────────────────────────────────────────

    @Test fun `la puissance remplit l arc vers la droite, jusqu a 320 kW`() {
        // 320 kW : la XPower (2026-10-14). À 150, l'arc restait plein en accélération franche.
        assertEquals(1f, ClusterGauge.power(320f).fraction, delta)
        assertEquals(1f, ClusterGauge.power(400f).fraction, delta)
        assertEquals(0f, ClusterGauge.power(0f).fraction, delta)
        assertEquals(GaugeUnit.KW, ClusterGauge.power(75f).unit)
    }

    @Test fun `en roulant, l arc de puissance bouge visiblement`() {
        // Échelle resserrée (retour du 2026-10-15) : linéaire jusqu'à 320 kW, 20 kW n'en
        // faisaient que 6 % et l'arc ne bougeait presque pas en roulant.
        assertEquals(0.245f, ClusterGauge.power(20f).fraction, 0.005f)
        assertTrue(ClusterGauge.power(50f).fraction in 0.42f..0.46f)
        assertTrue(ClusterGauge.power(100f).fraction in 0.61f..0.65f)
    }

    @Test fun `l arc de puissance monte toujours avec la puissance`() {
        var avant = -1f
        for (kw in 0..320 step 10) {
            val f = ClusterGauge.power(kw.toFloat()).fraction
            assertTrue("$kw kW", f > avant)
            avant = f
        }
    }

    @Test fun `la puissance consommee garde une seule couleur`() {
        assertEquals(GaugeLevel.CONSUMPTION, ClusterGauge.power(30f).level)
        assertEquals(GaugeLevel.CONSUMPTION, ClusterGauge.power(300f).level)
    }

    @Test fun `la recuperation remplit l arc vers la gauche`() {
        val s = ClusterGauge.power(-30f)
        assertEquals(-0.712f, s.fraction, 0.005f)
        assertEquals(GaugeLevel.REGEN, s.level)
        assertEquals(-30f, s.value, delta)
        // Une récupération légère se voit, elle aussi : 10 kW font plus d'un tiers du côté gauche.
        assertEquals(-0.356f, ClusterGauge.power(-10f).fraction, 0.005f)
        assertEquals(-1f, ClusterGauge.power(-60f).fraction, delta)
        assertEquals(-1f, ClusterGauge.power(-90f).fraction, delta)
    }

    // ── Unité de la consommation (2026-10-15) ────────────────────────────────

    @Test fun `la consommation s ecrit en Wh par km ou en kWh aux 100 km`() {
        assertEquals("184", ClusterGauge.formatConsumption(184.4f, ConsumptionUnit.WH_PER_KM, Locale.FRANCE))
        assertEquals("18,4", ClusterGauge.formatConsumption(184.4f, ConsumptionUnit.KWH_PER_100KM, Locale.FRANCE))
        assertEquals("18.4", ClusterGauge.formatConsumption(184.4f, ConsumptionUnit.KWH_PER_100KM, Locale.US))
        // Au-delà de 100 kWh/100 km, la décimale ne dit plus rien : un entier.
        assertEquals("250", ClusterGauge.formatConsumption(2500f, ConsumptionUnit.KWH_PER_100KM, Locale.FRANCE))
    }

    @Test fun `les graduations suivent l unite choisie`() {
        val wh = ClusterGauge.CONSUMPTION_TICKS_WH.map { ClusterGauge.consumptionTickLabel(it, ConsumptionUnit.WH_PER_KM) }
        val kwh = ClusterGauge.CONSUMPTION_TICKS_WH.map { ClusterGauge.consumptionTickLabel(it, ConsumptionUnit.KWH_PER_100KM) }
        assertEquals(listOf("0", "100", "200", "500", "1k", "3k"), wh)
        assertEquals(listOf("0", "10", "20", "50", "100", "300"), kwh)
    }

    @Test fun `l unite se retrouve par sa cle, Wh par km par defaut`() {
        assertEquals(ConsumptionUnit.KWH_PER_100KM, ConsumptionUnit.fromKey("kwh_100km"))
        assertEquals(ConsumptionUnit.WH_PER_KM, ConsumptionUnit.fromKey(null))
        assertEquals(ConsumptionUnit.WH_PER_KM, ConsumptionUnit.fromKey("inconnue"))
    }

    @Test fun `les Wh par km ne valent qu en roulant et en consommant`() {
        assertEquals(200f, ClusterGauge.whPerKm(26f, 130f)!!, delta)
        assertNull(ClusterGauge.whPerKm(26f, 0f))
        assertNull(ClusterGauge.whPerKm(-10f, 80f))
        assertNull(ClusterGauge.whPerKm(26f, null))
    }

    // ── Chiffre qui glisse d'une valeur à l'autre (demande du 2026-10-15) ────

    @Test fun `le chiffre passe par les valeurs intermediaires`() {
        val vers = ClusterGauge.consumption(15f, 50f)   // 300 Wh/km
        assertEquals(100f, ClusterGauge.shownValue(100f, GaugeUnit.WH_PER_KM, vers, 0f)!!, delta)
        assertEquals(200f, ClusterGauge.shownValue(100f, GaugeUnit.WH_PER_KM, vers, 0.5f)!!, delta)
        assertEquals(300f, ClusterGauge.shownValue(100f, GaugeUnit.WH_PER_KM, vers, 1f)!!, delta)
    }

    @Test fun `changer d unite saute directement a la nouvelle valeur`() {
        // De 200 Wh/km à 1,8 kW à l'arrêt : compter de 200 à 1,8 ne voudrait rien dire.
        val arret = ClusterGauge.consumption(1.8f, 0f)
        assertEquals(1.8f, ClusterGauge.shownValue(200f, GaugeUnit.WH_PER_KM, arret, 0.3f)!!, delta)
    }

    @Test fun `sans valeur de depart ou d arrivee, pas de glissement`() {
        val vers = ClusterGauge.consumption(15f, 50f)
        assertEquals(300f, ClusterGauge.shownValue(null, null, vers, 0.2f)!!, delta)
        assertNull(ClusterGauge.shownValue(100f, GaugeUnit.WH_PER_KM, null, 0.5f))
    }

    @Test fun `la puissance glisse aussi d un signe a l autre`() {
        // De 20 kW consommés à 30 kW récupérés : même unité, le chiffre passe par zéro.
        val vers = ClusterGauge.power(-30f)
        assertEquals(-5f, ClusterGauge.shownValue(20f, GaugeUnit.KW, vers, 0.5f)!!, delta)
    }

    // ── Mise en forme et lissage ─────────────────────────────────────────────

    @Test fun `les kW gardent une decimale sous 10, dans la langue de l appli`() {
        assertEquals("1,8", ClusterGauge.formatKw(1.84f, Locale.FRANCE))
        assertEquals("1.8", ClusterGauge.formatKw(1.84f, Locale.US))
        assertEquals("38", ClusterGauge.formatKw(38.4f, Locale.FRANCE))
        // Pas de « 10,0 » : passé l'arrondi, c'est déjà un nombre à deux chiffres.
        assertEquals("10", ClusterGauge.formatKw(9.96f, Locale.FRANCE))
        // Le signe est l'affaire de l'appelant.
        assertEquals("38", ClusterGauge.formatKw(-38.4f, Locale.FRANCE))
    }

    @Test fun `le lissage part de la premiere valeur puis la suit a moitie`() {
        assertEquals(20f, ClusterGauge.smooth(null, 20f), delta)
        assertEquals(15f, ClusterGauge.smooth(10f, 20f), delta)
    }

    // ── Écran et place du module ─────────────────────────────────────────────

    @Test fun `l ecran du combine est le numero 1, celui de SystemUI`() {
        assertEquals(1, ClusterGauge.clusterDisplay(listOf(0 to 1, 1 to 1)))
        assertEquals(1, ClusterGauge.clusterDisplay(listOf(0 to 1, 1 to null)))
    }

    @Test fun `sans ecran 1 physique, le premier autre ecran physique`() {
        assertEquals(3, ClusterGauge.clusterDisplay(listOf(0 to 1, 2 to 5, 3 to 2)))
        // Un écran virtuel (projection d'un téléphone, enregistrement) n'est pas le combiné.
        assertNull(ClusterGauge.clusterDisplay(listOf(0 to 1, 1 to 5)))
        assertNull(ClusterGauge.clusterDisplay(listOf(0 to 1)))
    }

    @Test fun `le carre se place comme celui de l appli du combine`() {
        // Ses marges (424 et 32 px) ne sont pas sa place : le carré se centre dans ce qu'elles
        // laissent. Photo du 2026-10-14 sur SWI133 : posé en (424, 32), le module débordait
        // d'environ 56 px à gauche et 91 px en haut de la zone visible — ce que donne ce
        // centrage sur un écran de 800 × 480.
        assertEquals(ClusterGauge.Placement(482, 126, 260), ClusterGauge.placement(800, 480))
        assertEquals(ClusterGauge.Placement(636, 48, 260), ClusterGauge.placement(1108, 324))
    }

    @Test fun `juste la place des marges et du carre - il se colle aux marges`() {
        assertEquals(ClusterGauge.Placement(424, 32, 260), ClusterGauge.placement(684, 292))
    }

    @Test fun `sur un ecran trop petit, le carre se centre et rapetisse au besoin`() {
        assertEquals(ClusterGauge.Placement(20, 10, 260), ClusterGauge.placement(300, 280))
        assertEquals(ClusterGauge.Placement(0, 0, 200), ClusterGauge.placement(200, 200))
    }

    @Test fun `le module se retrouve par sa cle, consommation par defaut`() {
        assertEquals(ClusterModule.POWER, ClusterModule.fromKey("power"))
        assertEquals(ClusterModule.CONSUMPTION, ClusterModule.fromKey(null))
        assertEquals(ClusterModule.CONSUMPTION, ClusterModule.fromKey("inconnu"))
    }

    // ── Issue #152 : kW du module B, pastille ────────────────────────────────

    @Test fun `en roulant, des kW entiers - a l arret, la decimale`() {
        assertEquals("5", ClusterGauge.formatPowerKw(4.6f, 30f, Locale.FRANCE))
        assertEquals("35", ClusterGauge.formatPowerKw(35.4f, 82f, Locale.FRANCE))
        assertEquals("18", ClusterGauge.formatPowerKw(-18.2f, 60f, Locale.FRANCE))
        // Au seuil STOP_KMH, on roule déjà.
        assertEquals("1", ClusterGauge.formatPowerKw(1.2f, ClusterGauge.STOP_KMH, Locale.FRANCE))
        assertEquals("1,2", ClusterGauge.formatPowerKw(1.2f, 3f, Locale.FRANCE))
        assertEquals("1,2", ClusterGauge.formatPowerKw(1.2f, null, Locale.FRANCE))
    }

    @Test fun `pastille du mode de conduite, l affichage d avant`() {
        assertEquals(PillContent("NORMAL", DriveMode.NORMAL),
            ClusterGauge.pill(ClusterPill.DRIVE_MODE, DriveMode.NORMAL, "Paul", DriveMode.SPORT, Locale.FRANCE))
        assertNull(ClusterGauge.pill(ClusterPill.DRIVE_MODE, null, "Paul", DriveMode.SPORT, Locale.FRANCE))
    }

    @Test fun `pastille du profil, dans la couleur du mode du profil`() {
        // « PAUL » en Normal → bleu, même si la voiture est passée en Sport à la main.
        assertEquals(PillContent("PAUL", DriveMode.NORMAL),
            ClusterGauge.pill(ClusterPill.PROFILE, DriveMode.SPORT, "Paul", DriveMode.NORMAL, Locale.FRANCE))
        // Un profil qui ne règle pas la conduite prend la couleur de la voiture, sinon gris.
        assertEquals(PillContent("PAUL", DriveMode.ECO),
            ClusterGauge.pill(ClusterPill.PROFILE, DriveMode.ECO, "Paul", null, Locale.FRANCE))
        assertEquals(PillContent("PAUL", null),
            ClusterGauge.pill(ClusterPill.PROFILE, null, "Paul", null, Locale.FRANCE))
        // Aucun profil actif (ou un nom vide) : pas de pastille.
        assertNull(ClusterGauge.pill(ClusterPill.PROFILE, DriveMode.ECO, null, null, Locale.FRANCE))
        assertNull(ClusterGauge.pill(ClusterPill.PROFILE, DriveMode.ECO, "  ", null, Locale.FRANCE))
    }

    @Test fun `pastille aucune, et le choix par defaut est le profil`() {
        assertNull(ClusterGauge.pill(ClusterPill.NONE, DriveMode.NORMAL, "Paul", DriveMode.NORMAL, Locale.FRANCE))
        assertEquals(ClusterPill.PROFILE, ClusterPill.fromKey(null))
        assertEquals(ClusterPill.DRIVE_MODE, ClusterPill.fromKey("mode"))
        assertEquals(ClusterPill.PROFILE, ClusterPill.fromKey("inconnu"))
    }
}
