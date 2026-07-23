package com.mg4.control.debug

import com.mg4.hardware.AppLogger
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-908] Un rapport de crash trop gros doit perdre sa QUEUE, pas sa tête : l'exception et
 * la stack trace sont en haut du fichier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLoggerTest {

    private val maxFileBytes = 48_000

    @Test
    fun `un rapport surdimensionne conserve l exception et la stack trace`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val boom = IllegalStateException("BOOM identifiant unique")
        // Le volume vient de la section AppLogger, qui est en QUEUE de rapport : c'est
        // elle qui doit être sacrifiée, pas l'exception qui la précède.
        AppLogger.clear()
        repeat(100) { AppLogger.i("BRUIT", "x".repeat(2_000)) }

        CrashLogger.write(context, Thread.currentThread(), boom)
        val written = CrashLogger.read(context) ?: error("aucun rapport écrit")

        assertTrue("l'en-tête a été tronqué", written.contains("CRASH REPORT MG4Control"))
        assertTrue("l'exception a été tronquée",
            written.contains("BOOM identifiant unique"))
        assertTrue("la stack trace a été tronquée",
            written.contains("CrashLoggerTest"))
        assertTrue("la troncature n'est pas signalée", written.contains("tronqué"))
    }

    @Test
    fun `un rapport de taille normale n est pas modifie`() {
        val content = "petit rapport"
        assertEquals(content, String(CrashLogger.truncate(content), Charsets.UTF_8))
    }

    @Test
    fun `la troncature respecte le plafond en octets`() {
        // Caractères accentués : 2 octets chacun en UTF-8. L'ancienne version comptait
        // des caractères, donc écrivait presque le double du plafond annoncé.
        val content = "é".repeat(maxFileBytes)
        val truncated = CrashLogger.truncate(content)
        assertTrue("plafond dépassé : ${truncated.size}", truncated.size <= maxFileBytes)
    }

    @Test
    fun `la troncature garde le debut et non la fin`() {
        val content = "DEBUT_UNIQUE" + "x".repeat(maxFileBytes * 2) + "FIN_UNIQUE"
        val truncated = String(CrashLogger.truncate(content), Charsets.UTF_8)
        assertTrue(truncated.startsWith("DEBUT_UNIQUE"))
        assertTrue("la fin aurait dû être coupée", !truncated.contains("FIN_UNIQUE"))
    }

    @Test
    fun `la troncature ne coupe pas un caractere multi-octets en deux`() {
        val content = "é".repeat(maxFileBytes)
        val truncated = CrashLogger.truncate(content)
        // Un découpage au milieu d'un caractère produirait un caractère de remplacement.
        assertTrue("caractère coupé en deux",
            !String(truncated, Charsets.UTF_8).contains('�'))
    }
}
