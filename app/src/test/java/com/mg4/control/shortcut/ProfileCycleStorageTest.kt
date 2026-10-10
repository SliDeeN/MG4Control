package com.mg4.control.shortcut

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle de profils, tel qu'il survit à un redémarrage. La règle elle-même est dans
 * [ProfileCycleTest] ; ici on vérifie seulement que ce qui est enregistré est bien ce qui est relu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileCycleStorageTest {

    private lateinit var ctx: Context
    private val tous = listOf("quotidien", "autoroute", "hiver")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_shortcuts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `sans reglage tous les profils sont dans le cycle`() {
        assertEquals(tous, ProfileCycle.order(ctx, tous))
    }

    @Test
    fun `le cycle choisi est relu dans le meme ordre`() {
        val choisi = listOf("hiver", "quotidien")
        ProfileCycle.save(ctx, choisi)
        assertEquals(choisi, ProfileCycle.order(ctx, tous))
    }

    @Test
    fun `reinitialiser rend tous les profils`() {
        ProfileCycle.save(ctx, listOf("hiver", "quotidien"))
        ProfileCycle.reset(ctx)
        assertEquals(tous, ProfileCycle.order(ctx, tous))
    }

    @Test
    fun `le cycle ne touche pas au cycle de regeneration`() {
        // Les deux réglages partagent le même fichier : chacun sa clé.
        ProfileCycle.save(ctx, listOf("hiver", "quotidien"))
        assertEquals(com.mg4.control.model.RegenLevel.CYCLE_ORDER, RegenCycle.order(ctx))
    }
}
