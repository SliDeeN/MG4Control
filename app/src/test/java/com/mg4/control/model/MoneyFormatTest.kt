package com.mg4.control.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Mise en forme des prix et des montants, pour toutes les monnaies.
 *
 * Le kWh vaut 0,187 en euros et 2 500 en roupies indonésiennes : trois décimales sont
 * indispensables dans un cas, du bruit dans l'autre. Et rien ne doit bouger pour les monnaies
 * fortes, qui étaient les seules possibles tant que le prix était plafonné à 5.
 */
class MoneyFormatTest {

    @Test
    fun `un prix sous dix garde ses trois decimales`() {
        assertEquals("0.187", MoneyFormat.price(0.187f, Locale.US))
        assertEquals("0.450", MoneyFormat.price(0.45f, Locale.US))
        assertEquals("8.990", MoneyFormat.price(8.99f, Locale.US))
    }

    @Test
    fun `un grand prix perd les decimales inutiles`() {
        assertEquals("2500", MoneyFormat.price(2500f, Locale.US))
        assertEquals("31", MoneyFormat.price(31f, Locale.US))
        assertEquals("12.5", MoneyFormat.price(12.5f, Locale.US))
        assertEquals("250.75", MoneyFormat.price(250.75f, Locale.US))
    }

    @Test
    fun `un prix n'a jamais de separateur de milliers`() {
        // Il est relu tel quel par le champ de saisie : « 2 500 » ne se convertirait plus.
        assertEquals("100000", MoneyFormat.price(100_000f, Locale.US))
        assertEquals("2500", MoneyFormat.price(2500f, Locale.FRANCE))
    }

    @Test
    fun `le prix suit le separateur decimal de la langue`() {
        assertEquals("0,187", MoneyFormat.price(0.187f, Locale.FRANCE))
        assertEquals("12,5", MoneyFormat.price(12.5f, Locale.FRANCE))
    }

    @Test
    fun `un montant sous mille garde son dixieme`() {
        assertEquals("0.7", MoneyFormat.amount(0.72f, Locale.US))
        assertEquals("125.3", MoneyFormat.amount(125.3f, Locale.US))
        assertEquals("12,5", MoneyFormat.amount(12.5f, Locale.FRANCE))
    }

    @Test
    fun `un grand montant est entier et groupe par milliers`() {
        // Espace insécable : lisible dans toutes les langues, et jamais coupé en fin de ligne.
        assertEquals("9 000", MoneyFormat.amount(9000f, Locale.US))
        assertEquals("1 250 000", MoneyFormat.amount(1_250_000f, Locale.US))
        assertEquals("1 250", MoneyFormat.amount(1250.4f, Locale.FRANCE))
    }

    @Test
    fun `le plafond du prix laisse passer les monnaies faibles`() {
        assertEquals(2500f, StatsSettings.clampPrice(2500f), 0f)
        assertEquals(0f, StatsSettings.clampPrice(-1f), 0f)
        assertEquals(StatsSettings.MAX_PRICE, StatsSettings.clampPrice(1e9f), 0f)
    }
}
