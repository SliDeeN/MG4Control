package com.mg4.control.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Correction d'un tarif aux boutons − / + : les pas proposés, et ce que vaut un appui. */
class PriceStepperTest {

    private fun assertPas(attendus: List<Float>, reference: Float) {
        val pas = PriceStepper.steps(reference)
        assertEquals("nombre de pas pour $reference", attendus.size, pas.size)
        attendus.zip(pas).forEach { (attendu, obtenu) -> assertEquals(attendu, obtenu, attendu / 1000f) }
    }

    @Test
    fun `une monnaie forte se regle au millieme`() {
        assertPas(listOf(0.001f, 0.01f, 0.1f, 1f), 0.187f)
        assertPas(listOf(0.001f, 0.01f, 0.1f, 1f), 0.45f)
        // Couronne, zloty, livre turque : quelques unités le kWh, même finesse.
        assertPas(listOf(0.001f, 0.01f, 0.1f, 1f), 2.49f)
    }

    @Test
    fun `les pas suivent l'ordre de grandeur du prix`() {
        assertPas(listOf(0.01f, 0.1f, 1f, 10f), 45f)
        // Roupie indonésienne : 2 466 le kWh — le millième n'y veut rien dire, l'unité si.
        assertPas(listOf(1f, 10f, 100f, 1000f), 2466f)
    }

    @Test
    fun `un prix nul ou minuscule garde les pas les plus fins`() {
        assertPas(listOf(0.001f, 0.01f, 0.1f, 1f), 0f)
        assertPas(listOf(0.001f, 0.01f, 0.1f, 1f), 0.004f)
    }

    @Test
    fun `un appui deplace le prix d'un pas exactement`() {
        assertEquals("0.188", MoneyFormat.price(PriceStepper.move(0.187f, 0.001f, 1), Locale.US))
        assertEquals("0.177", MoneyFormat.price(PriceStepper.move(0.187f, 0.01f, -1), Locale.US))
    }

    @Test
    fun `cent appuis ne laissent aucune poussiere de flottant`() {
        var prix = 0.187f
        repeat(100) { prix = PriceStepper.move(prix, 0.001f, 1) }
        assertEquals("0.287", MoneyFormat.price(prix, Locale.US))
        repeat(100) { prix = PriceStepper.move(prix, 0.001f, -1) }
        assertEquals("0.187", MoneyFormat.price(prix, Locale.US))
    }

    @Test
    fun `le prix reste dans ses bornes`() {
        // Gratuit, pas négatif.
        assertEquals(0f, PriceStepper.move(0.005f, 0.01f, -1), 0f)
        assertEquals(StatsSettings.MAX_PRICE, PriceStepper.move(StatsSettings.MAX_PRICE, 1000f, 1), 0f)
    }
}
