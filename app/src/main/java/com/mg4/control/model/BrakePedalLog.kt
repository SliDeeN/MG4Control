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
 * premiers changements bruts, un résumé par appui (pression non nulle) et un bilan à la sortie de
 * READY. Sans Android. [add] rend les lignes à écrire pour ce relevé (souvent aucune).
 *
 * Allégé le 2026-11-27 (relevés SWI133 du 27/11, issue #278) : seuls la pression de la pédale et
 * l'accélération longitudinale mesurée servent au halo. L'indicateur de validité, l'accélération
 * « rapide » et les deux états du freinage intégré (toujours à 0) ont été retirés, comme le mode
 * « freinage par récupération » (7 au moindre lever de pied).
 */
class BrakePedalLog(
    private val brutsMax: Int = BRUTS_MAX,
    private val appuisMax: Int = APPUIS_MAX,
) {
    /** Un relevé : pression brute (`null` = illisible), vitesse en km/h, accélération mesurée en m/s². */
    data class Releve(val tMs: Long, val pression: Int?, val kmh: Float?, val accel: Float? = null)

    private var avant: Releve? = null
    private var bruts = 0
    private var appuis = 0
    private var appuisJournalises = 0
    private var releves = 0
    private var lisibles = 0
    private var mini: Int? = null
    private var maxi: Int? = null
    private var accelMin: Float? = null
    private var accelMax: Float? = null

    // Appui en cours.
    private var debut: Releve? = null
    private var pointe = 0
    private var accelMinAppui: Float? = null

    fun add(r: Releve): List<String> {
        val lignes = mutableListOf<String>()
        releves++
        r.pression?.let { p ->
            lisibles++
            mini = minOf(mini ?: p, p)
            maxi = maxOf(maxi ?: p, p)
        }
        r.accel?.let { a -> accelMin = minOf(accelMin ?: a, a); accelMax = maxOf(accelMax ?: a, a) }
        val a = avant
        if ((a == null || a.pression != r.pression) && bruts < brutsMax) {
            bruts++
            lignes += "pédale brut : pression=${txt(r.pression)} · accél. ${flottant(r.accel)} · ${vitesse(r.kmh)} ($bruts/$brutsMax)"
        }
        val presse = (r.pression ?: 0) > 0
        val d = debut
        if (presse && d == null) {
            debut = r
            pointe = r.pression ?: 0
            accelMinAppui = r.accel
        } else if (presse && d != null) {
            pointe = maxOf(pointe, r.pression ?: 0)
            r.accel?.let { x -> accelMinAppui = minOf(accelMinAppui ?: x, x) }
        } else if (!presse && d != null) {
            appuis++
            if (appuisJournalises < appuisMax) {
                appuisJournalises++
                lignes += String.format(Locale.US,
                    "pédale : appui de %d ms, pression maxi %d · %s → %s · accél. mesurée mini %s (%d/%d)",
                    r.tMs - d.tMs, pointe, vitesse(d.kmh), vitesse(r.kmh), flottant(accelMinAppui), appuisJournalises, appuisMax)
            }
            debut = null
        }
        avant = r
        return lignes
    }

    /** Bilan du cycle (sortie de READY ou arrêt). */
    fun bilan(): String =
        "pédale : bilan — $releves relevés, $lisibles lisibles, pression ${txt(mini)}…${txt(maxi)}, $appuis appui(s)" +
            (if (debut != null) ", appui en cours" else "") +
            " | accél. mesurée ${flottant(accelMin)}…${flottant(accelMax)}"

    private fun txt(v: Int?) = v?.toString() ?: "illisible"
    private fun flottant(v: Float?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "illisible"
    private fun vitesse(kmh: Float?) = kmh?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "vitesse illisible"

    companion object {
        const val BRUTS_MAX = 40
        const val APPUIS_MAX = 60
    }
}
