package com.mg4.control.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.shortcut.AudioSource
import com.mg4.control.shortcut.AudioSourceCycle
import com.mg4.control.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Raccourci « Cycle de sources audio » (2026-10-15) : chaque appui vise la source suivante du
 * cycle composé dans l'onglet Raccourcis, l'annonce à l'écran, et y passe après un court délai
 * sans nouvel appui — deux appuis rapprochés sautent une source. Calqué sur [ProfileCycleShortcut].
 *
 * Pas de verrou de vitesse : changer de source n'est pas un réglage de conduite.
 * La règle — quelle source vient ensuite — est dans [AudioSourceCycle], sans Android.
 */
object AudioSourceCycleShortcut {

    private const val TAG = "MG4_SVC"

    private val main = Handler(Looper.getMainLooper())

    // Ces trois champs ne se lisent et ne s'écrivent que sur le thread principal.

    /** Source visée, tant qu'elle attend d'être appliquée. */
    private var cible: AudioSource? = null
    private var application: Runnable? = null
    private var message: Toast? = null

    fun press(context: Context) {
        val app = context.applicationContext
        main.post { appui(app) }
    }

    private fun appui(app: Context) {
        val cycle = AudioSourceCycle.order(app)
        val visee = AudioSourceCycle.target(cycle, AudioSourceCycle.last(app), cible) ?: return
        AppLogger.i(TAG, "SHORTCUT cycle de sources → ${visee.name} (${cycle.indexOf(visee) + 1}/${cycle.size})")
        cible = visee
        annoncer(app, visee)

        application?.let(main::removeCallbacks)
        application = Runnable { appliquer(app, visee) }
            .also { main.postDelayed(it, AudioSourceCycle.APPLY_DELAY_MS) }
    }

    private fun appliquer(app: Context, source: AudioSource) {
        cible = null
        application = null
        // Retenue même si la source n'a rien à jouer : c'est d'elle que repart l'appui suivant.
        AudioSourceCycle.setLast(app, source)
        CoroutineScope(Dispatchers.IO).launch {
            val ok = MG4Hardware.switchAudioSource(source)
            AppLogger.i(TAG, "SHORTCUT cycle de sources — ${source.name} : commande ${if (ok) "envoyée" else "sans voie"}")
        }
    }

    /** Un seul message à la fois : le nouveau REMPLACE l'ancien au lieu d'attendre son tour. */
    private fun annoncer(app: Context, source: AudioSource) {
        // Langue choisie dans l'application : le contexte du service garde celle du système.
        val loc = LocaleHelper.applyLocale(app)
        message?.cancel()
        message = runCatching {
            Toast.makeText(loc, loc.getString(R.string.audio_cycle_target, loc.getString(source.label)),
                Toast.LENGTH_SHORT).also { it.show() }
        }.getOrNull()
    }
}
