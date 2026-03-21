package com.mg4control.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger en mémoire + fichier.
 * Limites : 200 lignes en mémoire, 200 Ko max sur disque (rotation automatique).
 */
object AppLogger {

    private const val MAX_LINES    = 200
    private const val MAX_FILE_KB  = 200L          // 200 Ko max sur disque
    private const val LOG_FILE     = "mg4control_boot.log"
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        trimFileIfNeeded()
        try {
            val existing = logFile?.readLines() ?: emptyList()
            existing.takeLast(MAX_LINES).forEach { lines.addLast(it) }
        } catch (e: Exception) { }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("I/$tag: $msg") }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add("W/$tag: $msg") }
    fun d(tag: String, msg: String) { Log.d(tag, msg); add("D/$tag: $msg") }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("E/$tag: $msg")
        t?.let {
            add("  → ${it.javaClass.simpleName}: ${it.message}")
            it.cause?.let { c -> add("  caused by: ${c.javaClass.simpleName}: ${c.message}") }
        }
    }

    private fun add(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$ts] $line"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
        }
        try {
            val f = logFile ?: return
            // Rotation : si le fichier dépasse MAX_FILE_KB, on garde seulement la moitié des lignes
            if (f.length() > MAX_FILE_KB * 1024) {
                val kept = f.readLines().takeLast(MAX_LINES / 2)
                f.writeText(kept.joinToString("\n") + "\n")
            }
            f.appendText(entry + "\n")
        } catch (e: Exception) { }
    }

    /** Tronque le fichier au démarrage s'il est trop gros */
    private fun trimFileIfNeeded() {
        try {
            val f = logFile ?: return
            if (f.exists() && f.length() > MAX_FILE_KB * 1024) {
                val kept = f.readLines().takeLast(MAX_LINES)
                f.writeText(kept.joinToString("\n") + "\n")
            }
        } catch (e: Exception) { }
    }

    fun getAll(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        try { logFile?.writeText("") } catch (e: Exception) { }
    }
}
