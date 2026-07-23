package com.mg4.control.profile

import androidx.test.core.app.ApplicationProvider
import com.mg4.hardware.model.DriveMode
import com.mg4.hardware.model.DrivingProfile
import com.mg4.hardware.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-903] Le bouton volant, la connexion Bluetooth et le passage en READY peuvent muter les
 * profils en même temps, depuis l'UI et depuis le service — chacun avec sa propre instance de
 * ProfileManager. Ces tests échouent si la sérialisation saute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileConcurrencyTest {

    private fun profile(name: String) = DrivingProfile(
        name = name,
        driveMode = DriveMode.NORMAL,
        regenLevel = RegenLevel.MEDIUM,
        aebMode = 2
    )

    /** Lance [count] threads en parallèle et attend qu'ils aient tous terminé. */
    private fun runConcurrently(count: Int, block: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        repeat(count) { i ->
            Thread {
                start.await()
                try { block(i) } finally { done.countDown() }
            }.start()
        }
        start.countDown()   // départ simultané
        assertTrue("threads bloqués", done.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `sauvegardes concurrentes ne perdent aucun profil`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Une instance par thread : c'est la situation réelle (UI + service).
        val profiles = (0 until ProfileManager.MAX_PROFILES).map { profile("P$it") }

        runConcurrently(profiles.size) { i ->
            ProfileManager(context).save(profiles[i])
        }

        val saved = ProfileManager(context).getAll()
        assertEquals("une sauvegarde a été perdue", profiles.size, saved.size)
        profiles.forEach { p ->
            assertNotNull("profil ${p.name} absent", saved.firstOrNull { it.id == p.id })
        }
    }

    @Test
    fun `mises a jour concurrentes du meme profil en conservent une`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = profile("Base")
        ProfileManager(context).save(base)

        runConcurrently(8) { i ->
            ProfileManager(context).save(base.copy(name = "Rename$i"))
        }

        val all = ProfileManager(context).getAll()
        assertEquals("l'update concurrent a dupliqué le profil", 1, all.size)
        assertTrue("nom incohérent : ${all[0].name}", all[0].name.startsWith("Rename"))
    }

    @Test
    fun `sauvegardes fichier concurrentes ne corrompent jamais le backup`() {
        val manager = ProfileBackupManager()
        val profiles = (0 until 5).map { profile("P$it") }

        runConcurrently(12) { i ->
            manager.writeBackup(profiles.take((i % 5) + 1), profiles[0].id)
        }

        // Le fichier final doit être lisible et cohérent — jamais un JSON tronqué
        // ou l'entrelacement de deux écritures.
        val backup = manager.readBackup()
        assertNotNull("sauvegarde illisible après écritures concurrentes", backup)
        assertTrue(backup!!.profiles.isNotEmpty())
        assertEquals(profiles[0].id, backup.defaultId)
    }

    @Test
    fun `une lecture concurrente voit toujours un backup complet`() {
        val manager = ProfileBackupManager()
        val profiles = (0 until 5).map { profile("P$it") }
        manager.writeBackup(profiles, profiles[0].id)

        // Écritures et lectures entrelacées : le remplacement se fait sans fenêtre
        // pendant laquelle le fichier est absent ou partiel.
        runConcurrently(12) { i ->
            if (i % 2 == 0) {
                manager.writeBackup(profiles, profiles[0].id)
            } else {
                assertNotNull("backup absent pendant une réécriture", manager.readBackup())
            }
        }
    }
}
