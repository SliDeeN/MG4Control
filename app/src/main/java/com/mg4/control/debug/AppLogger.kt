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
 * tout le buffer deux fois par ligne de log, sur le thread appelant — c'est-à-dire
 * pendant l'application d'un profil.
 *
 * 2000 lignes depuis le 2026-11-12 (400 avant : le journal de la discussion #219 s'arrêtait
 * avant le passage en READY). La mémoire reste bornée : l'heure est gardée en `Long` (mise en
 * forme à la lecture), un message trop long est coupé à [MAX_MSG_CHARS], et le total des
 * messages ne dépasse pas [MAX_TOTAL_CHARS] — les plus anciens partent d'abord. Au pire
 * ~2 Mo, en pratique quelques centaines de Ko.
 */
object AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val at: Long,
        val tag: String,
        val level: Level,
        val msg: String
    ) {
        /** Heure de la ligne, « HH:mm:ss.SSS ». */
        val time: String get() = formatTime(at)
    }

    internal const val MAX_ENTRIES = 2000
    internal const val MAX_MSG_CHARS = 4_000
    internal const val MAX_TOTAL_CHARS = 1_000_000

    // SimpleDateFormat n'est pas sûr entre threads : on le partage sous son propre verrou.
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private fun formatTime(at: Long): String = synchronized(sdf) { sdf.format(Date(at)) }

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private var chars = 0

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
            chars = 0
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
        val texte = if (msg.length <= MAX_MSG_CHARS) msg
            else msg.take(MAX_MSG_CHARS) + " … (+${msg.length - MAX_MSG_CHARS} caractères)"
        val entry = Entry(System.currentTimeMillis(), tag, level, texte)
        synchronized(lock) {
            // while et non if : le plafond tient même en cas d'ajouts concurrents.
            while (buffer.isNotEmpty() &&
                (buffer.size >= MAX_ENTRIES || chars + texte.length > MAX_TOTAL_CHARS)) {
                chars -= buffer.removeFirst().msg.length
            }
            buffer.addLast(entry)
            chars += texte.length
            totalCount++
        }
    }
}
