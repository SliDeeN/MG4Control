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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Raccourci « Cycle de sources audio » (2026-10-15) : chaque appui vise la source suivante du
 * cycle composé dans l'onglet Raccourcis, l'annonce à l'écran, et y passe après un court délai
 * sans nouvel appui — deux appuis rapprochés sautent une source. Calqué sur [ProfileCycleShortcut].
 *
 * Depuis le 2026-10-16, le cycle repart de la source qui joue ([MG4Hardware.currentAudioSource]),
 * sauf quand notre dernière bascule n'a pas pris : chaque bascule est vérifiée
 * [AudioSourceCycle.CONFIRM_MS] plus tard. La règle est dans [AudioSourceCycle.depart].
 *
 * Pas de verrou de vitesse : changer de source n'est pas un réglage de conduite.
 */
object AudioSourceCycleShortcut {

    private const val TAG = "MG4_SVC"

    private val main = Handler(Looper.getMainLooper())

    // Ces champs ne se lisent et ne s'écrivent que sur le thread principal.

    /** Source visée, tant qu'elle attend d'être appliquée. */
    private var cible: AudioSource? = null
    private var application: Runnable? = null
    private var message: Toast? = null
    /** Vrai de la bascule à sa vérification : la source active n'a pas encore eu le temps de changer. */
    private var enVerification = false
    /** Source qui jouait encore à la vérification de notre dernière bascule, si ce n'était pas la visée. */
    private var echecDepuis: AudioSource? = null
    /** Numéro de la dernière bascule : seule sa vérification compte. */
    private var bascules = 0

    fun press(context: Context) {
        val app = context.applicationContext
        // La source active se lit hors du thread principal : sur l'ancien SDK, c'est un appel au
        // service média SAIC, qui peut attendre sa liaison au premier appui.
        CoroutineScope(Dispatchers.IO).launch {
            val actif = MG4Hardware.currentAudioSource()
            main.post { appui(app, actif) }
        }
    }

    private fun appui(app: Context, actif: AudioSource?) {
        val cycle = AudioSourceCycle.order(app)
        val derniere = AudioSourceCycle.last(app)
        val depart = AudioSourceCycle.depart(actif, derniere, cible, echecDepuis, enVerification)
        val visee = AudioSourceCycle.next(cycle, depart) ?: return
        val pourquoi = when {
            cible != null -> "source en attente"
            enVerification -> "bascule en vérification"
            actif == null -> "source active illisible"
            depart != actif -> "la dernière bascule n'a pas pris"
            else -> "source active"
        }
        AppLogger.i(TAG, "SHORTCUT cycle de sources → ${visee.name} (${cycle.indexOf(visee) + 1}/${cycle.size}) — " +
            "départ ${depart?.name ?: "aucun"} ($pourquoi ; active ${actif?.name ?: "illisible"}, " +
            "dernière choisie ${derniere?.name ?: "aucune"})")
        cible = visee
        annoncer(app, visee)

        application?.let(main::removeCallbacks)
        application = Runnable { appliquer(app, visee) }
            .also { main.postDelayed(it, AudioSourceCycle.APPLY_DELAY_MS) }
    }

    private fun appliquer(app: Context, source: AudioSource) {
        cible = null
        application = null
        // Retenue même si la source n'a rien à jouer : c'est d'elle que repart l'appui suivant
        // quand la bascule n'a pas pris.
        AudioSourceCycle.setLast(app, source)
        val numero = ++bascules
        enVerification = true
        CoroutineScope(Dispatchers.IO).launch {
            val ok = MG4Hardware.switchAudioSource(source)
            AppLogger.i(TAG, "SHORTCUT cycle de sources — ${source.name} : commande ${if (ok) "envoyée" else "sans voie"}")
            delay(AudioSourceCycle.CONFIRM_MS)
            val actif = MG4Hardware.currentAudioSource()
            main.post {
                if (numero != bascules) return@post   // une bascule plus récente vérifiera la sienne
                enVerification = false
                // Illisible : rien à conclure, l'appui suivant repartira de la dernière choisie.
                echecDepuis = actif?.takeIf { it != source }
                AppLogger.i(TAG, "SHORTCUT cycle de sources — vérification : " + when {
                    actif == null -> "source active illisible"
                    actif == source -> "${source.name} joue"
                    else -> "${actif.name} joue toujours, ${source.name} n'a pas pris"
                })
            }
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
