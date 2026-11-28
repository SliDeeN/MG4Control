/*
 * MG4Control — journal AllGo du guidage (issue #285).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ce qu'AllGo écrit de la navigation dans le journal Android (issue #285, 2026-11-28), recopié dans
 * MG4_NAV. Le 28/11, pendant un itinéraire Google Maps, aucun guidage n'est arrivé jusqu'à nous ;
 * le code d'AllGo (SWI133) montre deux causes possibles, que seul son propre journal départage :
 *  - l'abonnement au guidage refusé à la connexion (« Navigation feature is not subscribed… » contre
 *    « Navigation status subscription succeeded ») ;
 *  - les anciens messages de flèche jetés (« navigationNextTurnCallback has been deprecated »).
 *
 * Lecture seule : `logcat` lancé par l'application (uid système, qui a le droit de lire le journal
 * des autres processus), filtré sur les phrases de navigation d'AllGo relevées dans ses
 * bibliothèques. Le tampon déjà présent est relu au démarrage (connexion du téléphone antérieure),
 * puis le flux est suivi. [LIGNES_MAX] lignes au plus par démarrage de l'application.
 */
object AllGoNavLog {

    /**
     * Phrases relevées dans libaap_interface, libaapsdk, AAP_MY24 et RemoteUIService (SWI133), plus
     * le guidage CarPlay. Syntaxe commune à `logcat -e` (ECMAScript) et à Kotlin : pas d'option (?i).
     */
    internal const val MOTIF =
        "Navigation (feature|status|state|current|Callback|message|data|Status:|Road|Distance|Cue|Destination|focus)" +
            "|navigation (status|focus|request|state|cluster|configuration|data|maneuver)" +
            "|NextTurn\\w* has been deprecated|onAAPNavigationStatus|iMinIntervalMs|Nav status|NavStatus|nav config" +
            "|NavigationStatusEndpoint|AANAV|NAVI_METADATA|RouteGuidance"
    private val regex = Regex(MOTIF)
    private const val LIGNES_MAX = 400
    private const val RELANCES_MAX = 5

    @Volatile private var demarre = false
    private var lignes = 0
    private var derniere: String? = null

    /** Une fois, avec la sonde du guidage. */
    @Synchronized
    fun start() {
        if (demarre) return
        demarre = true
        Thread({ boucle() }, "mg4-allgolog").apply { isDaemon = true }.start()
    }

    private fun boucle() {
        var avecFiltre = true
        repeat(RELANCES_MAX) { essai ->
            val debut = System.currentTimeMillis()
            val vu = runCatching { lire(avecFiltre) }
                .onFailure { AppLogger.w(TAG, "journal AllGo illisible : ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(0)
            if (lignes >= LIGNES_MAX) return
            // Un logcat qui s'arrête aussitôt : l'option -e n'est peut-être pas comprise (il écrit alors
            // son erreur et quitte), on refiltre ici.
            if (avecFiltre && System.currentTimeMillis() - debut < 3_000) {
                avecFiltre = false
                AppLogger.w(TAG, "journal AllGo : logcat -e arrêté aussitôt ($vu ligne(s)), filtrage par l'application")
            } else {
                AppLogger.w(TAG, "journal AllGo : logcat arrêté (essai ${essai + 1}/$RELANCES_MAX), relance dans 10 s")
                Thread.sleep(10_000)
            }
        }
    }

    /** Rend le nombre de lignes lues ; bloque tant que logcat tourne. */
    private fun lire(avecFiltre: Boolean): Int {
        val cmd = mutableListOf("logcat", "-v", "time")
        if (avecFiltre) cmd += listOf("-e", MOTIF)
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        AppLogger.i(TAG, "journal AllGo suivi (${if (avecFiltre) "filtre logcat" else "filtre application"}, $LIGNES_MAX lignes au plus)")
        var lues = 0
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val l = r.readLine() ?: break
                    lues++
                    val garde = garder(l) ?: continue
                    if (garde == derniere) continue
                    derniere = garde
                    lignes++
                    AppLogger.i(TAG, "AllGo » $garde ($lignes/$LIGNES_MAX)")
                    if (lignes >= LIGNES_MAX) {
                        AppLogger.i(TAG, "journal AllGo : plafond atteint jusqu'au prochain démarrage")
                        return lues
                    }
                }
            }
        } finally {
            p.destroy()
        }
        return lues
    }

    /**
     * Ligne `logcat -v time` (« 11-28 10:21:27.244 D/Tag( 1234): message ») à recopier, sans la date,
     * ou `null`. Nos propres lignes (MG4_…) sont écartées : elles reviendraient en boucle.
     */
    internal fun garder(ligne: String): String? {
        if (ligne.contains("MG4_") || !regex.containsMatchIn(ligne)) return null
        val sansDate = ligne.substringAfter(' ', ligne).trim()
        return sansDate.take(300)
    }

    private const val TAG = NavGuidanceProbe.TAG
}
