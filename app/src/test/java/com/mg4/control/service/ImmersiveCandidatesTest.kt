package com.mg4.control.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Applications en plein écran (#248) : la liste ne propose jamais MG4Control (online ou offline). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImmersiveCandidatesTest {

    @Test
    fun `MG4Control, launchers, Android Auto and CarPlay are never offered`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pm = shadowOf(ctx.packageManager)
        fun appli(pkg: String, categorie: String = Intent.CATEGORY_LAUNCHER) {
            val c = ComponentName(pkg, "$pkg.Main")
            pm.addActivityIfNotPresent(c)
            pm.addIntentFilterForActivity(c, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(categorie) })
        }
        listOf("com.mg4.control", "com.mg4.control.offline", "com.waze", "app.mg4dash",
            "com.allgo.app.androidauto", "com.allgo.carplay.service", "com.spotify.music").forEach { appli(it) }
        appli("com.saic.launcher", Intent.CATEGORY_HOME)
        appli("com.saic.launcher")

        assertEquals(listOf("com.spotify.music", "com.waze"), ImmersiveApps.candidates(ctx).map { it.first }.sorted())
    }
}
