package com.mg4.control.shortcut

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Séquence du cycle de régénération, telle qu'elle survit à un redémarrage.
 *
 * Ce qui est à risque ici n'est pas l'écriture mais la RELECTURE : le service la refait à chaque
 * appui sur la touche, et une préférence vide, tronquée ou corrompue ne doit jamais produire un
 * cycle absurde — au pire l'ordre d'origine, jamais un raccourci muet ou un mode surgi de nulle
 * part.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegenCycleStorageTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_shortcuts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `sans reglage on garde le comportement d origine`() {
        assertEquals(RegenLevel.CYCLE_ORDER, RegenCycle.order(ctx))
    }

    @Test
    fun `la sequence choisie est relue dans le meme ordre`() {
        val choisi = listOf(RegenLevel.HIGH, RegenLevel.LOW, RegenLevel.ONE_PEDAL)
        RegenCycle.save(ctx, choisi)
        assertEquals(choisi, RegenCycle.order(ctx))
    }

    @Test
    fun `reinitialiser rend l ordre d origine`() {
        RegenCycle.save(ctx, listOf(RegenLevel.ADAPTIVE, RegenLevel.LOW))
        RegenCycle.reset(ctx)
        assertEquals(RegenLevel.CYCLE_ORDER, RegenCycle.order(ctx))
    }

    @Test
    fun `une preference corrompue retombe sur l ordre d origine`() {
        // Le piège évité : RegenLevel.fromValue rend MEDIUM pour toute valeur inconnue. Passer
        // par elle transformerait ces trois entrées en un cycle plausible mais inventé.
        ecrire("42,,Faible")
        assertEquals(RegenLevel.CYCLE_ORDER, RegenCycle.order(ctx))
    }

    @Test
    fun `un cycle a un seul mode est refuse`() {
        // Un seul mode : le premier appui agirait, tous les suivants seraient sans effet. Mieux
        // vaut l'ordre d'origine qu'un raccourci qui semble tombé en panne.
        ecrire("${RegenLevel.HIGH.value}")
        assertEquals(RegenLevel.CYCLE_ORDER, RegenCycle.order(ctx))
    }

    @Test
    fun `Off ne peut pas entrer dans le cycle par la porte de derriere`() {
        // Off n'est pas proposé à l'écran ; une préférence écrite à la main ne doit pas
        // l'introduire non plus, sous peine de couper la régénération à chaque tour.
        ecrire("${RegenLevel.OFF.value},${RegenLevel.HIGH.value},${RegenLevel.LOW.value}")
        assertEquals(listOf(RegenLevel.HIGH, RegenLevel.LOW), RegenCycle.order(ctx))
    }

    @Test
    fun `un mode repete ne l est qu une fois`() {
        ecrire("${RegenLevel.HIGH.value},${RegenLevel.LOW.value},${RegenLevel.HIGH.value}")
        assertEquals(listOf(RegenLevel.HIGH, RegenLevel.LOW), RegenCycle.order(ctx))
    }

    private fun ecrire(brut: String) {
        ctx.getSharedPreferences("mg4_shortcuts", Context.MODE_PRIVATE)
            .edit().putString("shortcut_regen_cycle_order", brut).commit()
    }
}
