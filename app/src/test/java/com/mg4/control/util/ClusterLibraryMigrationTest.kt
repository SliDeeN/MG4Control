package com.mg4.control.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterModuleDefs
import com.mg4.control.model.ClusterTab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reprise des modules en bibliothèque (2026-11-20). Né d'une panne : la reprise par profil, déjà
 * faite chez l'utilisateur, quittait toute la fonction par un `return` dans son runCatching ; la
 * bibliothèque n'était jamais reprise et les modules réglés avaient disparu à la mise à jour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClusterLibraryMigrationTest {

    @Test
    fun `the library is taken over even when the per-profile migration was done long ago`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // L application de test a déjà fait sa reprise au démarrage, sur des réglages vides : on repart de zéro.
        ctx.getSharedPreferences("mg4_settings", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cluster_per_profile_migrated", true)
            .putBoolean(ClusterModuleSettings.KEY_ENABLED, true)
            .putString(ClusterModuleSettings.KEY_TAB_NAVIGATION, "tyres")
            .putString(ClusterModuleSettings.KEY_TAB_MEDIA, "power")
            .putInt(ClusterModuleSettings.KEY_REFRESH, 500)
            .commit()

        ClusterProfiles.migrateIfNeeded(ctx)

        val biblio = ClusterModuleSettings.library(ctx)
        assertEquals(listOf(ClusterModule.TYRES, ClusterModule.POWER), biblio.map(ClusterModuleDefs::module))
        val communs = ClusterModuleSettings.legacy(ctx)
        assertEquals(listOf(biblio[0].id), ClusterModuleDefs.ids(communs, ClusterTab.NAVIGATION))
        assertEquals(listOf(biblio[1].id), ClusterModuleDefs.ids(communs, ClusterTab.MEDIA))
        assertEquals(500, ClusterModuleSettings.refreshMs(ctx))
        // Une seule fois : un second passage ne recrée rien.
        ClusterProfiles.migrateIfNeeded(ctx)
        assertEquals(2, ClusterModuleSettings.library(ctx).size)
    }
}
