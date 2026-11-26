/*
 * MG4Control — halo de freinage de l'écran conducteur (issue #278).
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

/**
 * Halo de freinage (issue #278, 2026-11-25) : le pourtour de l'écran conducteur rougit quand on
 * freine. Aucun firmware n'expose l'état des feux stop (SWI68 à SWI173 fouillés), mais tous déclarent
 * la pression de la pédale de frein, lue sur SWI133 : halo = pédale enfoncée ([PEDALE_ON]) OU forte
 * décélération tirée de la vitesse (forte récupération sans pédale, et secours quand la pédale est
 * illisible).
 *
 * Décélération mesurée entre le relevé le plus ancien des [FENETRE_MS] dernières millisecondes et
 * le plus récent (au moins [ECART_MIN_MS] d'écart) : le pas d'un km/h de la vitesse ne fait pas
 * clignoter le halo. Seuils avec hystérésis : allumé au-dessus de [SEUIL_ON], éteint sous [SEUIL_OFF]
 * (m/s²). Une vitesse illisible, ou un trou de plus de [TROU_MS], éteint et repart de zéro.
 */
class BrakeGlow {

    private val temps = ArrayDeque<Long>()
    private val vitesses = ArrayDeque<Float>()

    /** Halo allumé. */
    var on = false
        private set

    /** Dernière décélération calculée (m/s², positive au ralentissement), pour le diagnostic. */
    var deceleration: Float? = null
        private set

    /** Décélération au-dessus du seuil (avec hystérésis). */
    var parDeceleration = false
        private set

    /** Pédale de frein enfoncée (avec hystérésis) ; faux quand la pédale est illisible. */
    var parPedale = false
        private set

    /**
     * Un relevé au temps monotone [tMs] : vitesse (km/h) et pression brute de la pédale de frein
     * ([pedale], `null` = illisible). Rend l'état du halo : pédale enfoncée OU forte décélération.
     */
    fun add(tMs: Long, kmh: Float?, pedale: Int? = null): Boolean {
        parPedale = when {
            pedale == null -> false
            parPedale -> pedale >= PEDALE_OFF
            else -> pedale >= PEDALE_ON
        }
        parDeceleration = decel(tMs, kmh)
        on = parPedale || parDeceleration
        return on
    }

    private fun decel(tMs: Long, kmh: Float?): Boolean {
        if (kmh == null || (temps.isNotEmpty() && tMs - temps.last() > TROU_MS) || (temps.isNotEmpty() && tMs < temps.last())) {
            viderVitesses()
            if (kmh == null) return false
        }
        temps.addLast(tMs)
        vitesses.addLast(kmh)
        // Garder le plus ancien relevé qui couvre encore la fenêtre.
        while (temps.size > 2 && tMs - temps[1] >= FENETRE_MS) {
            temps.removeFirst()
            vitesses.removeFirst()
        }
        val dt = tMs - temps.first()
        if (dt < ECART_MIN_MS) return parDeceleration
        val d = (vitesses.first() - kmh) / 3.6f / (dt / 1000f)
        deceleration = d
        return if (parDeceleration) d >= SEUIL_OFF else d >= SEUIL_ON
    }

    private fun viderVitesses() {
        temps.clear()
        vitesses.clear()
        deceleration = null
        parDeceleration = false
    }

    fun reset() {
        viderVitesses()
        parPedale = false
        on = false
    }

    /** Intensité choisie dans les réglages communs de l'écran conducteur. */
    /**
     * [alpha] : opacité de la lueur au bord ; [largeurPx] : jusqu'où elle s'étend vers le centre
     * (carré de 260) ; [bord] : opacité du liseré du contour. Lueur plus large et plus douce depuis
     * la photo du 2026-11-25 (l'ancienne, 14–28 px, se lisait comme un cadre).
     */
    enum class Intensity(val key: String, val alpha: Float, val largeurPx: Float, val bord: Float) {
        SOFT("soft", 0.42f, 30f, 0.55f),
        MEDIUM("medium", 0.62f, 42f, 0.80f),
        STRONG("strong", 0.85f, 56f, 1.00f);

        companion object {
            fun fromKey(key: String?): Intensity = entries.firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    companion object {
        /** ≈ 4,7 km/h perdus par seconde : un freinage franc, pas un lever de pied. */
        const val SEUIL_ON = 1.3f
        const val SEUIL_OFF = 0.7f
        /**
         * Pédale (`SENSOR_BRAKE_PEDAL_DRIVER_APPLIED_PRESSURE`, relevé SWI133 du 2026-11-25) : 0
         * relâchée, ≈ 650–1800 appui léger, ≈ 4300–4700 appui franc, par pas de 75. Allumé dès la
         * moindre pression (2026-11-26 : les feux stop s'allument au moindre effleurement, constaté
         * par le rapporteur de #278), éteint à 0.
         */
        const val PEDALE_ON = 1
        const val PEDALE_OFF = 1
        const val FENETRE_MS = 600L
        const val ECART_MIN_MS = 400L
        const val TROU_MS = 1_500L
        /** Relevé de la vitesse dédié au halo, plus serré que l'actualisation de la jauge. */
        const val RELEVE_MS = 200L
        /** Fondu à l'allumage et à l'extinction. */
        const val FONDU_MS = 330L
    }
}
