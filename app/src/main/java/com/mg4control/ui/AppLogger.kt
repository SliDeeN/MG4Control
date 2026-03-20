package com.mg4control.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger en mémoire + fichier.
 * Les logs en mémoire disparaissent à la mort du processus.
 * Les logs fichier persistent entre les redémarrages → utile pour déboguer le boot.
 */
object AppLogger {

    private const val MAX_LINES = 200
    private const val LOG_FILE  = "mg4control_boot.log"
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var logFile: File? = null

    /** Initialiser avec un contexte pour activer les logs fichier */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE)
        // Charger les logs fichier existants en mémoire au démarrage
        try {
            val existing = logFile?.readLines() ?: emptyList()
            existing.takeLast(MAX_LINES).forEach { lines.addLast(it) }
        } catch (e: Exception) { /* fichier absent au premier lancement */ }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I/$tag: $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        add("W/$tag: $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("E/$tag: $msg")
        t?.let {
            add("  → ${it.javaClass.simpleName}: ${it.message}")
            it.cause?.let { c -> add("  caused by: ${c.javaClass.simpleName}: ${c.message}") }
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add("D/$tag: $msg")
    }

    private fun add(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$ts] $line"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
        }
        // Écrire aussi dans le fichier
        try {
            logFile?.appendText(entry + "\n")
        } catch (e: Exception) { /* ignore */ }
    }

    fun getAll(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        try { logFile?.writeText("") } catch (e: Exception) { }
    }
}
