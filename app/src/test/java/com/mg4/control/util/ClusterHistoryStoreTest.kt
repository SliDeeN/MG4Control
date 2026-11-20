package com.mg4.control.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.model.ClusterHistory
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Historique du module « Graphique » (issue #217) : il survit à un redémarrage, et une coupure
 * pendant l'écriture ne le fait pas repartir de zéro (écriture atomique, 2026-11-20). Android 9,
 * comme les écrans des voitures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClusterHistoryStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val fichier get() = File(ctx.filesDir, "cluster_history.json")

    /** Comme au redémarrage de l'application : plus rien en mémoire, tout se relit du fichier. */
    private fun redemarrer() {
        ClusterHistoryStore::class.java.getDeclaredField("bins").apply { isAccessible = true }.set(ClusterHistoryStore, null)
        ClusterHistoryStore::class.java.getDeclaredField("nonEnregistrees").apply { isAccessible = true }.setInt(ClusterHistoryStore, 0)
    }

    private fun tranche(n: Int) = ClusterHistory.Bin(0.2f, 30f + n, 0f, 10f, alt = n.toFloat())

    @Before
    fun vider() {
        fichier.delete()
        File(fichier.path + ".bak").delete()
        redemarrer()
    }

    @Test
    fun `the history is read back after a restart`() {
        repeat(7) { ClusterHistoryStore.add(ctx, tranche(it)) }
        ClusterHistoryStore.flush(ctx)
        redemarrer()
        val relues = ClusterHistoryStore.bins(ctx)
        assertEquals(7, relues.size)
        assertEquals(6f, relues.last().alt)
    }

    @Test
    fun `a write cut short keeps the previous history`() {
        repeat(5) { ClusterHistoryStore.add(ctx, tranche(it)) }   // enregistré à la 5e tranche
        // Coupure en pleine écriture : un fichier commencé, jamais terminé. L'application coupée,
        // le système ferme le fichier (le laisser ouvert bloquerait la reprise sous Windows seulement).
        val f = android.util.AtomicFile(fichier)
        f.startWrite().use { it.write("[{\"km\":0.2,\"wh\":".toByteArray()) }
        redemarrer()
        val relues = ClusterHistoryStore.bins(ctx)
        assertEquals(5, relues.size)
        assertEquals(4f, relues.last().alt)
    }
}
