package com.mg4.control.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Raccourcis avancés associés à un profil.
 *
 * Deux choses sont réellement à risque ici. D'abord la COMPATIBILITÉ : un raccourci valable pour
 * tous les profils doit continuer de s'écrire sous exactement la même clé qu'avant, sinon la mise
 * à jour effacerait silencieusement ce que les utilisateurs ont configuré. Ensuite l'ORDRE de
 * résolution : le profil actif d'abord, le repli ensuite, et rien d'autre — un raccourci réservé
 * à « Sport » ne doit jamais se déclencher sous un autre profil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvancedShortcutProfileTest {

    private lateinit var ctx: Context

    private val SPORT = "11111111-1111-1111-1111-111111111111"
    private val HIVER = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("mg4_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `un raccourci tous profils garde la cle historique`() {
        // Le format d'avant l'arrivée des profils, écrit à la main : il doit être relu tel quel.
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE)
            .edit().putInt("adv_sc_286_single", ShortcutAction.MEDIA_NEXT.id).commit()

        assertEquals(
            ShortcutAction.MEDIA_NEXT,
            AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE)
        )
        val tout = AdvancedShortcuts.all(ctx)
        assertEquals(1, tout.size)
        assertNull("un raccourci historique vaut pour tous les profils", tout[0].profileId)
    }

    @Test
    fun `les deux portees cohabitent sur la meme touche`() {
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)

        // C'est tout l'objet de la fonctionnalité : même touche, même appui, deux raccourcis.
        assertEquals(2, AdvancedShortcuts.all(ctx).size)
        assertEquals(ShortcutAction.MEDIA_NEXT,
            AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE))
        assertEquals(ShortcutAction.REGEN_CYCLE,
            AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE, SPORT))
    }

    @Test
    fun `le profil actif l emporte sur le repli`() {
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)
        ActiveProfile.set(ctx, SPORT)

        val m = AdvancedShortcuts.resolve(ctx, 286, PressType.SINGLE)
        assertEquals(ShortcutAction.REGEN_CYCLE, m?.action)
        assertEquals(SPORT, m?.profileId)
    }

    @Test
    fun `un profil sans variante retombe sur tous les profils`() {
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)
        ActiveProfile.set(ctx, HIVER)

        val m = AdvancedShortcuts.resolve(ctx, 286, PressType.SINGLE)
        assertEquals(ShortcutAction.MEDIA_NEXT, m?.action)
        assertNull(m?.profileId)
    }

    @Test
    fun `sans profil actif on prend le repli`() {
        // Installation neuve, ou rien appliqué depuis le Mode Garage.
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)

        assertEquals(ShortcutAction.MEDIA_NEXT,
            AdvancedShortcuts.resolve(ctx, 286, PressType.SINGLE)?.action)
    }

    @Test
    fun `une variante seule ne fuit pas sur les autres profils`() {
        // Le piège que la fonctionnalité doit éviter : « réservé à Sport » veut dire réservé.
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)
        ActiveProfile.set(ctx, HIVER)

        assertNull(AdvancedShortcuts.resolve(ctx, 286, PressType.SINGLE))
    }

    @Test
    fun `la touche reste reclamee meme si aucune variante ne s applique`() {
        // Conséquence assumée : une touche se réclame en bloc. Elle ne retombe pas sur le
        // launcher parce que le profil courant n'a rien à y faire.
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)
        ActiveProfile.set(ctx, HIVER)

        assertTrue(AdvancedShortcuts.isClaimed(ctx, 286))
        assertFalse("une autre touche n'est pas concernée", AdvancedShortcuts.isClaimed(ctx, 17))
    }

    @Test
    fun `une touche a prefixe commun ne repond pas pour une autre`() {
        // 17 et 170 : le tiret bas final de la clé est le seul rempart.
        AdvancedShortcuts.set(ctx, 170, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        assertTrue(AdvancedShortcuts.isClaimed(ctx, 170))
        assertFalse(AdvancedShortcuts.isClaimed(ctx, 17))
    }

    @Test
    fun `supprimer une variante laisse le repli en place`() {
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)

        AdvancedShortcuts.remove(ctx, 286, PressType.SINGLE, SPORT)

        assertEquals(1, AdvancedShortcuts.all(ctx).size)
        assertEquals(ShortcutAction.MEDIA_NEXT,
            AdvancedShortcuts.actionFor(ctx, 286, PressType.SINGLE))
    }

    @Test
    fun `tous les profils vient en tete de son groupe`() {
        // L'ordre de la liste est celui de la résolution : c'est ce qui la rend lisible.
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.REGEN_CYCLE, SPORT)
        AdvancedShortcuts.set(ctx, 286, PressType.SINGLE, ShortcutAction.MEDIA_NEXT)

        assertNull(AdvancedShortcuts.all(ctx).first().profileId)
    }

    @Test
    fun `deux variantes ont des emplacements de cible distincts`() {
        // Sans le profil dans la clé d'emplacement, deux « ouvrir une application » sur la même
        // touche partageraient l'application choisie.
        val a = AdvancedShortcuts.slotKey(286, PressType.SINGLE, SPORT)
        val b = AdvancedShortcuts.slotKey(286, PressType.SINGLE, HIVER)
        val repli = AdvancedShortcuts.slotKey(286, PressType.SINGLE)
        assertEquals(3, setOf(a, b, repli).size)
        assertEquals("adv_286_single", repli)
    }
}
