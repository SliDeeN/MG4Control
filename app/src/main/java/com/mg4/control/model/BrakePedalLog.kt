/*
 * MG4Control — relevé de la pédale de frein pour le journal (issue #278).
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

package com.mg4.control.model

import java.util.Locale

/**
 * Ce que le journal retient de la pédale de frein pendant un cycle READY (2026-11-25) : les
 * premiers changements bruts tels quels, puis un résumé par appui (pression non nulle), et un bilan
 * à la sortie de READY. Échelle et unité inconnues : rien n'est interprété. Sans Android.
 *
 * Depuis le 2026-11-26 (issue #278, recherche de ce qui allume les feux stop), le relevé porte aussi
 * l'accélération longitudinale mesurée par la voiture, le mode du système électrique (7 = freinage
 * par récupération) et les deux états du freinage intégré (IBS) : leurs changements sont écrits,
 * chaque épisode de récupération est résumé, et le bilan donne les plages vues.
 *
 * [add] rend les lignes à écrire pour ce relevé (souvent aucune).
 */
class BrakePedalLog(
    private val brutsMax: Int = BRUTS_MAX,
    private val appuisMax: Int = APPUIS_MAX,
    private val signauxMax: Int = SIGNAUX_MAX,
    private val recupsMax: Int = RECUPS_MAX,
) {
    /**
     * Un relevé : pression et indicateur de validité bruts (`null` = illisible), vitesse en km/h ;
     * accélération longitudinale mesurée et accélération « rapide » (brutes, unité à confirmer) avec
     * leurs validités ; mode du système électrique ; états IBS de récupération et de pédale.
     */
    data class Releve(
        val tMs: Long, val pression: Int?, val valide: Int?, val kmh: Float?,
        val accel: Float? = null, val accelValide: Int? = null,
        val accelRapide: Float? = null, val accelRapideValide: Int? = null,
        val mode: Int? = null, val ibsRecup: Int? = null, val ibsPedale: Int? = null,
    )

    private var avant: Releve? = null
    private var bruts = 0
    private var appuis = 0
    private var appuisJournalises = 0
    private var releves = 0
    private var lisibles = 0
    private var mini: Int? = null
    private var maxi: Int? = null
    private val valeursValide = sortedSetOf<Int>()

    // Nouveaux signaux (2026-11-26) : changements écrits, plages et valeurs vues.
    private var signaux = 0
    private var accelMin: Float? = null
    private var accelMax: Float? = null
    private var rapideMin: Float? = null
    private var rapideMax: Float? = null
    private val modesVus = sortedSetOf<Int>()
    private val ibsRecupVus = sortedSetOf<Int>()
    private val ibsPedaleVus = sortedSetOf<Int>()

    // Appui en cours.
    private var debut: Releve? = null
    private var pointe = 0
    private var vitessePrecedente: Releve? = null
    private var decelMax = 0f
    private var accelMinAppui: Float? = null

    // Épisode de récupération (mode 7) en cours.
    private var recup: Releve? = null
    private var recups = 0
    private var recupsJournalises = 0
    private var accelMinRecup: Float? = null
    private var pedaleDansRecup = false

    fun add(r: Releve): List<String> {
        val lignes = mutableListOf<String>()
        releves++
        r.pression?.let { p ->
            lisibles++
            mini = minOf(mini ?: p, p)
            maxi = maxOf(maxi ?: p, p)
        }
        r.valide?.let { valeursValide += it }
        r.accel?.let { a -> accelMin = minOf(accelMin ?: a, a); accelMax = maxOf(accelMax ?: a, a) }
        r.accelRapide?.let { a -> rapideMin = minOf(rapideMin ?: a, a); rapideMax = maxOf(rapideMax ?: a, a) }
        r.mode?.let { modesVus += it }
        r.ibsRecup?.let { ibsRecupVus += it }
        r.ibsPedale?.let { ibsPedaleVus += it }
        val a = avant
        if (a == null || a.pression != r.pression || a.valide != r.valide) {
            if (bruts < brutsMax) {
                bruts++
                lignes += "pédale brut : pression=${txt(r.pression)} · valide=${txt(r.valide)} · ${contexte(r)} ($bruts/$brutsMax)"
            }
        }
        // Signaux à valeurs discrètes : chaque changement, avec le contexte du moment.
        if (a != null) {
            val changes = buildList {
                if (a.mode != r.mode) add("mode élec. ${txt(a.mode)}→${txt(r.mode)}")
                if (a.ibsRecup != r.ibsRecup) add("IBS récup ${txt(a.ibsRecup)}→${txt(r.ibsRecup)}")
                if (a.ibsPedale != r.ibsPedale) add("IBS pédale ${txt(a.ibsPedale)}→${txt(r.ibsPedale)}")
                if (a.accelValide != r.accelValide) add("accél. valide ${txt(a.accelValide)}→${txt(r.accelValide)}")
                if (a.accelRapideValide != r.accelRapideValide) add("accél. rapide valide ${txt(a.accelRapideValide)}→${txt(r.accelRapideValide)}")
            }
            if (changes.isNotEmpty() && signaux < signauxMax) {
                signaux++
                lignes += "signaux : ${changes.joinToString(" · ")} | pédale ${txt(r.pression)} · ${contexte(r)} ($signaux/$signauxMax)"
            }
        }
        // Décélération entre deux relevés de vitesse espacés d'au moins 400 ms, pendant l'appui.
        if (debut != null && r.kmh != null) {
            val pv = vitessePrecedente
            if (pv?.kmh == null) vitessePrecedente = r
            else if (r.tMs - pv.tMs >= 400L) {
                val d = (pv.kmh - r.kmh) / 3.6f / ((r.tMs - pv.tMs) / 1000f)
                if (d > decelMax) decelMax = d
                vitessePrecedente = r
            }
        }
        val presse = (r.pression ?: 0) > 0
        val d = debut
        if (presse && d == null) {
            debut = r
            pointe = r.pression ?: 0
            vitessePrecedente = r
            decelMax = 0f
            accelMinAppui = r.accel
        } else if (presse && d != null) {
            pointe = maxOf(pointe, r.pression ?: 0)
            r.accel?.let { x -> accelMinAppui = minOf(accelMinAppui ?: x, x) }
        } else if (!presse && d != null) {
            appuis++
            if (appuisJournalises < appuisMax) {
                appuisJournalises++
                lignes += String.format(Locale.US,
                    "pédale : appui de %d ms, pression maxi %d · %s → %s · décélération maxi %.1f m/s² · accél. mesurée mini %s (%d/%d)",
                    r.tMs - d.tMs, pointe, vitesse(d.kmh), vitesse(r.kmh), decelMax, flottant(accelMinAppui), appuisJournalises, appuisMax)
            }
            debut = null
        }
        // Épisode de récupération : mode 7 (« REGEN_BRAKE_MODE »).
        val enRecup = r.mode == MODE_RECUP
        val rc = recup
        if (enRecup && rc == null) {
            recup = r
            accelMinRecup = r.accel
            pedaleDansRecup = presse
        } else if (enRecup && rc != null) {
            r.accel?.let { x -> accelMinRecup = minOf(accelMinRecup ?: x, x) }
            if (presse) pedaleDansRecup = true
        } else if (!enRecup && rc != null) {
            recups++
            if (recupsJournalises < recupsMax) {
                recupsJournalises++
                lignes += "récupération (mode 7) : ${r.tMs - rc.tMs} ms · ${vitesse(rc.kmh)} → ${vitesse(r.kmh)} · " +
                    "accél. mesurée mini ${flottant(accelMinRecup)} · pédale ${if (pedaleDansRecup) "appuyée" else "jamais"} " +
                    "($recupsJournalises/$recupsMax)"
            }
            recup = null
        }
        avant = r
        return lignes
    }

    /** Bilan du cycle (sortie de READY ou arrêt). */
    fun bilan(): String =
        "pédale : bilan — $releves relevés, $lisibles lisibles, pression ${txt(mini)}…${txt(maxi)}, " +
            "$appuis appui(s), valide vu ${vus(valeursValide)}" +
            (if (debut != null) ", appui en cours" else "") +
            " | accél. mesurée ${flottant(accelMin)}…${flottant(accelMax)}, accél. rapide ${flottant(rapideMin)}…${flottant(rapideMax)}, " +
            "modes élec. vus ${vus(modesVus)}, $recups récupération(s), IBS récup vu ${vus(ibsRecupVus)}, IBS pédale vu ${vus(ibsPedaleVus)}"

    private fun contexte(r: Releve) = "accél. ${flottant(r.accel)} · mode ${txt(r.mode)} · ${vitesse(r.kmh)}"
    private fun vus(s: Set<Int>) = if (s.isEmpty()) "jamais" else s.joinToString("/")
    private fun txt(v: Int?) = v?.toString() ?: "illisible"
    private fun flottant(v: Float?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "illisible"
    private fun vitesse(kmh: Float?) = kmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "vitesse illisible"

    companion object {
        const val BRUTS_MAX = 40
        const val APPUIS_MAX = 60
        const val SIGNAUX_MAX = 120
        const val RECUPS_MAX = 60
        /** `ELECVEHSYSMD_REGEN_BRAKE_MODE` du mode du système électrique (0x2140a15a). */
        const val MODE_RECUP = 7
    }
}
