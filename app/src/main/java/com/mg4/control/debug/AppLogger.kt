package com.mg4.control.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log buffer — mirrors every Log.* call to an in-memory ring buffer
 * so the Diagnostic report can include them without ADB.
 *
 * Le buffer est un ArrayDeque sous verrou : la CopyOnWriteArrayList précédente recopiait
 * les 400 entrées deux fois par ligne de log, sur le thread appelant — c'est-à-dire
 * pendant l'application d'un profil.
 */
object AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val time: String,
        val tag: String,
        val level: Level,
        val msg: String
    )

    private const val MAX_ENTRIES = 400
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    /**
     * Nombre total d'entrées ajoutées depuis le démarrage du processus — l'éviction ne le
     * décrémente pas. Permet à l'UI de savoir ce qui est nouveau sans comparer des tailles
     * (la taille cesse de bouger dès que le buffer est plein).
     */
    @Volatile
    var totalCount: Long = 0L
        private set

    /** Copie instantanée du buffer, de la plus ancienne à la plus récente entrée. */
    val entries: List<Entry>
        get() = synchronized(lock) { buffer.toList() }

    // ---- Public log methods (mirror android.util.Log) ----

    fun d(tag: String, msg: String) { add(tag, Level.DEBUG, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { add(tag, Level.INFO,  msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { add(tag, Level.WARN,  msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { add(tag, Level.ERROR, msg); Log.e(tag, msg) }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            totalCount = 0L
        }
    }

    /**
     * Entrées ajoutées depuis que l'appelant en avait vu [sinceTotal], ou null si les
     * entrées manquantes ont déjà été évincées — dans ce cas l'appelant doit tout
     * redessiner.
     */
    fun entriesSince(sinceTotal: Long): List<Entry>? = synchronized(lock) {
        val missing = totalCount - sinceTotal
        when {
            missing < 0L          -> null          // buffer vidé entre-temps (clear)
            missing == 0L         -> emptyList()
            missing > buffer.size -> null          // trop tard : entrées évincées
            else                  -> buffer.toList().takeLast(missing.toInt())
        }
    }

    // ---- Internal ----

    private fun add(tag: String, level: Level, msg: String) {
        val entry = Entry(sdf.format(Date()), tag, level, msg)
        synchronized(lock) {
            // while et non if : le plafond tient même en cas d'ajouts concurrents.
            while (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            totalCount++
        }
    }
}
