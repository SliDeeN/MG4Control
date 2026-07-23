package com.mg4.control.debug

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.mg4.hardware.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écrit la stack trace d'un crash non-géré dans un fichier sur le stockage interne,
 * accompagnée des 60 dernières lignes du buffer AppLogger pour contexte.
 *
 * Le fichier est lu par SettingsFragment → showDiagnosticDialog().
 * Utilisation : installer dans MG4App.onCreate() via Thread.setDefaultUncaughtExceptionHandler.
 */
object CrashLogger {

    private const val CRASH_FILE     = "crash_log.txt"
    private const val MAX_FILE_BYTES = 48_000   // ~48 Ko max pour éviter de saturer filesDir

    // ── Écriture (appelé depuis le UncaughtExceptionHandler) ─────────────────

    fun write(context: Context, thread: Thread, throwable: Throwable) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val sb  = StringBuilder()

        sb.appendLine("╔══════════════════════════════════════════╗")
        sb.appendLine("║           CRASH REPORT MG4Control        ║")
        sb.appendLine("╚══════════════════════════════════════════╝")
        sb.appendLine("Date    : ${sdf.format(Date())}")
        sb.appendLine("Thread  : ${thread.name}")
        sb.appendLine("App ver : ${getVersion(context)}")
        sb.appendLine()

        // ── Exception principale ──────────────────────────────────────────
        sb.appendLine("─── Exception ───────────────────────────────")
        sb.appendLine(throwable.toString())
        throwable.stackTrace.forEach { sb.appendLine("  at $it") }

        // ── Causes (chaîne de causes) ─────────────────────────────────────
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.appendLine()
            sb.appendLine("─── Caused by ───────────────────────────────")
            sb.appendLine(cause.toString())
            cause.stackTrace.take(20).forEach { sb.appendLine("  at $it") }
            cause = cause.cause
            depth++
        }

        // ── Dernières entrées AppLogger ───────────────────────────────────
        sb.appendLine()
        sb.appendLine("─── AppLogger (60 dernières lignes) ─────────")
        val logEntries = AppLogger.entries
        if (logEntries.isEmpty()) {
            sb.appendLine("  (buffer vide)")
        } else {
            logEntries.takeLast(60).forEach { e ->
                sb.appendLine("  ${e.time} [${e.level.name[0]}] ${e.tag}: ${e.msg}")
            }
        }

        // ── Écriture sur disque ───────────────────────────────────────────
        try {
            File(context.filesDir, CRASH_FILE).writeBytes(truncate(sb.toString()))
        } catch (_: Exception) {
            // Dernier recours — on ne peut rien faire de plus ici
        }
    }

    /**
     * Tronque le rapport à [MAX_FILE_BYTES] en gardant le DÉBUT.
     *
     * L'implémentation précédente gardait la fin (`takeLast`), c'est-à-dire qu'un rapport
     * trop gros perdait exactement l'en-tête, l'exception et la stack trace — les seules
     * lignes qui expliquent le crash. Elle comptait aussi des caractères alors que la
     * constante parle d'octets : en UTF-8 les accents des logs comptent double.
     */
    @VisibleForTesting
    internal fun truncate(content: String): ByteArray {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_FILE_BYTES) return bytes

        val marker = "\n… rapport tronqué (${bytes.size} octets) …\n".toByteArray(Charsets.UTF_8)
        val keep = MAX_FILE_BYTES - marker.size
        // Ne pas couper au milieu d'un caractère multi-octets : on recule jusqu'au
        // début du caractère suivant (les octets de continuation UTF-8 valent 10xxxxxx).
        var end = keep
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end) + marker
    }

    // ── Lecture (appelé depuis SettingsFragment) ──────────────────────────────

    /** Retourne le contenu du crash log ou null si aucun crash n'a été enregistré. */
    fun read(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        return if (file.exists()) file.readText() else null
    }

    /** Supprime le fichier de crash log. */
    fun clear(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private fun getVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
