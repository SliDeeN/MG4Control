package com.mg4.control.debug

import com.mg4.hardware.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-906] Le buffer de logs est écrit depuis le thread d'application de profil et lu par
 * l'UI. Ces tests couvrent le plafond sous concurrence et le rendu incrémental.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLoggerTest {

    private val maxEntries = 400

    @Before
    fun setUp() {
        AppLogger.clear()
    }

    @Test
    fun `le buffer ne depasse jamais le plafond`() {
        repeat(maxEntries + 250) { AppLogger.i("T", "msg$it") }
        assertEquals(maxEntries, AppLogger.entries.size)
    }

    @Test
    fun `les entrees les plus anciennes sont evincees en premier`() {
        repeat(maxEntries + 5) { AppLogger.i("T", "msg$it") }
        val entries = AppLogger.entries
        assertEquals("msg5", entries.first().msg)
        assertEquals("msg${maxEntries + 4}", entries.last().msg)
    }

    @Test
    fun `totalCount compte les entrees evincees`() {
        repeat(maxEntries + 50) { AppLogger.i("T", "msg$it") }
        assertEquals((maxEntries + 50).toLong(), AppLogger.totalCount)
        assertEquals(maxEntries, AppLogger.entries.size)
    }

    @Test
    fun `ecritures concurrentes ne depassent pas le plafond`() {
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                try { repeat(perThread) { AppLogger.i("T$t", "m$it") } } finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals(maxEntries, AppLogger.entries.size)
        assertEquals((threads * perThread).toLong(), AppLogger.totalCount)
    }

    // ---- Rendu incrémental ----

    @Test
    fun `entriesSince ne rend que les nouvelles entrees`() {
        repeat(10) { AppLogger.i("T", "msg$it") }
        val seen = AppLogger.totalCount
        AppLogger.i("T", "nouveau1")
        AppLogger.i("T", "nouveau2")

        val since = AppLogger.entriesSince(seen)
        assertNotNull(since)
        assertEquals(2, since!!.size)
        assertEquals("nouveau1", since[0].msg)
        assertEquals("nouveau2", since[1].msg)
    }

    @Test
    fun `entriesSince rend une liste vide quand rien n a bouge`() {
        repeat(3) { AppLogger.i("T", "msg$it") }
        assertEquals(emptyList<AppLogger.Entry>(), AppLogger.entriesSince(AppLogger.totalCount))
    }

    @Test
    fun `entriesSince demande un rendu complet quand des entrees ont ete evincees`() {
        AppLogger.i("T", "premier")
        val seen = AppLogger.totalCount
        repeat(maxEntries + 10) { AppLogger.i("T", "msg$it") }
        // Ce que l'appelant n'a pas encore vu a été évincé : impossible de compléter.
        assertNull(AppLogger.entriesSince(seen))
    }

    @Test
    fun `entriesSince demande un rendu complet apres clear`() {
        repeat(10) { AppLogger.i("T", "msg$it") }
        val seen = AppLogger.totalCount
        AppLogger.clear()
        assertNull(AppLogger.entriesSince(seen))
    }
}
