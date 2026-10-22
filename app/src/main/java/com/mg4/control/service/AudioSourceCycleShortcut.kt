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
 * Depuis le 2026-10-16, le cycle repart de la source qui joue ([MG4Hardware.currentAudioSource],
 * lue à chaque appui), sauf quand notre dernière bascule n'a encore rien changé : la source qui
 * jouait avant elle est retenue jusqu'à ce qu'une autre joue. La règle est dans
 * [AudioSourceCycle.depart].
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
    /** Source qui jouait à l'appui qui a choisi [cible] : celle d'avant la bascule. */
    private var actifAuChoix: AudioSource? = null
    /**
     * Source qui jouait avant notre dernière bascule, tant qu'elle joue encore : la bascule n'a
     * rien changé (elle démarre, ou elle n'a pas pris). `null` dès qu'une autre source a joué.
     */
    private var avant: AudioSource? = null
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
        val depart = AudioSourceCycle.depart(actif, derniere, cible, avant)
        val visee = AudioSourceCycle.next(cycle, depart) ?: return
        val pourquoi = when {
            cible != null -> "source en attente"
            actif == null -> "source active illisible"
            depart != actif -> "notre dernière bascule n'a encore rien changé"
            else -> "source active"
        }
        AppLogger.i(TAG, "SHORTCUT cycle de sources → ${visee.name} (${cycle.indexOf(visee) + 1}/${cycle.size}) — " +
            "départ ${depart?.name ?: "aucun"} ($pourquoi ; active ${actif?.name ?: "illisible"}, " +
            "dernière choisie ${derniere?.name ?: "aucune"}, avant la bascule ${avant?.name ?: "—"})")
        // Un appui en attente garde la source d'avant de la série : aucune bascule n'a eu lieu entre-temps.
        if (cible == null) actifAuChoix = actif
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
        avant = actifAuChoix?.takeIf { it != source }
        CoroutineScope(Dispatchers.IO).launch {
            val ok = MG4Hardware.switchAudioSource(source)
            AppLogger.i(TAG, "SHORTCUT cycle de sources — ${source.name} : commande ${if (ok) "envoyée" else "sans voie"}")
            delay(AudioSourceCycle.CONFIRM_MS)
            val actif = MG4Hardware.currentAudioSource()
            main.post {
                if (numero != bascules) return@post   // une bascule plus récente vérifiera la sienne
                val avantBascule = avant
                // Une autre source que celle d'avant joue : la bascule a pris, ou la source a été
                // changée ailleurs — dans les deux cas, plus rien à retenir. Illisible : on garde.
                if (actif != null && actif != avantBascule) avant = null
                AppLogger.i(TAG, "SHORTCUT cycle de sources — vérification : " + when {
                    actif == null -> "source active illisible"
                    actif == source -> "${source.name} joue"
                    actif == avantBascule -> "${actif.name} joue toujours, ${source.name} n'a pas pris"
                    else -> "${actif.name} joue (changée ailleurs)"
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
