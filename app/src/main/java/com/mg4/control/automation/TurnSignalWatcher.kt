package com.mg4.control.automation

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.TurnSignal
import com.mg4.control.model.TurnSignalDebounce

/**
 * Suivi rapide du clignotant pour Mes automatisations (2026-11-19) : la seule propriété
 * [TurnSignal.PROP] relue toutes les [TurnSignal.SONDE_MS], par la même voie que « Nuit (feux
 * auto) », tant qu'une automatisation active s'en sert. Le relevé des automatisations (2 s)
 * laisserait passer une impulsion de changement de voie. Fil `mg4-turn`.
 *
 * Journal : les valeurs brutes au début de chaque suivi (pour voir en voiture si la valeur suit la
 * lampe), puis chaque changement de la valeur stable.
 */
object TurnSignalWatcher {

    private const val TAG = "MG4_RULES"
    /** Valeurs brutes journalisées au plus par suivi. */
    private const val BRUTS_JOURNALISES = 30

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-turn").also { it.start() }.looper) }

    @Volatile private var tour = 0
    private var compteur = 0
    @Volatile private var filtre = TurnSignalDebounce()
    @Volatile private var auChangement: ((String) -> Unit)? = null

    /** Valeur stable, `null` hors suivi ou avant le premier relevé. */
    val stable: String? get() = if (tour != 0) filtre.stable else null

    /** Démarre le suivi (sans effet s'il tourne) ; [changement] reçoit chaque valeur stable nouvelle. */
    @Synchronized
    fun start(changement: (String) -> Unit) {
        auChangement = changement
        if (tour != 0) return
        compteur++
        val t = compteur
        tour = t
        filtre = TurnSignalDebounce()
        AppLogger.i(TAG, "clignotant suivi (relevé toutes les ${TurnSignal.SONDE_MS} ms)")
        fil.post { relever(t, null, 0) }
    }

    @Synchronized
    fun stop() {
        if (tour == 0) return
        tour = 0
        auChangement = null
        AppLogger.i(TAG, "clignotant : suivi arrêté")
    }

    private fun relever(t: Int, brutAvant: Int?, journalises: Int) {
        if (tour != t) return
        val brut = runCatching { MG4Hardware.readIntGlobal(TurnSignal.PROP) }.getOrNull()?.takeIf { it >= 0 }
        var n = journalises
        if (brut != brutAvant && n < BRUTS_JOURNALISES) {
            n++
            AppLogger.i(TAG, "clignotant brut : ${brut ?: "illisible"} ($n/$BRUTS_JOURNALISES)")
        }
        if (filtre.onSample(TurnSignal.texte(brut), SystemClock.elapsedRealtime())) {
            val v = filtre.stable ?: return
            AppLogger.i(TAG, "clignotant → $v")
            auChangement?.invoke(v)
        }
        fil.postDelayed({ relever(t, brut, n) }, TurnSignal.SONDE_MS)
    }
}
