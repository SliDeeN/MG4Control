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
 * Halo de freinage (issue #278, 2026-11-25) : le pourtour de l'écran conducteur rougit quand la
 * voiture ralentit fortement. Aucun firmware n'expose l'état des feux stop (SWI68 à SWI173 fouillés),
 * et la pression de la pédale n'a jamais été lue : on s'en tient à la décélération, tirée de la
 * vitesse — freinage ou forte récupération, ce que voit le conducteur qui suit.
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

    /** Un relevé de vitesse (km/h) au temps monotone [tMs] ; rend l'état du halo. */
    fun add(tMs: Long, kmh: Float?): Boolean {
        if (kmh == null || (temps.isNotEmpty() && tMs - temps.last() > TROU_MS) || (temps.isNotEmpty() && tMs < temps.last())) {
            reset()
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
        if (dt < ECART_MIN_MS) return on
        val d = (vitesses.first() - kmh) / 3.6f / (dt / 1000f)
        deceleration = d
        on = if (on) d >= SEUIL_OFF else d >= SEUIL_ON
        return on
    }

    fun reset() {
        temps.clear()
        vitesses.clear()
        deceleration = null
        on = false
    }

    /** Intensité choisie dans les réglages communs de l'écran conducteur. */
    enum class Intensity(val key: String, val alpha: Float, val largeurPx: Float) {
        SOFT("soft", 0.45f, 14f),
        MEDIUM("medium", 0.70f, 20f),
        STRONG("strong", 1.00f, 28f);

        companion object {
            fun fromKey(key: String?): Intensity = entries.firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    companion object {
        /** ≈ 4,7 km/h perdus par seconde : un freinage franc, pas un lever de pied. */
        const val SEUIL_ON = 1.3f
        const val SEUIL_OFF = 0.7f
        const val FENETRE_MS = 600L
        const val ECART_MIN_MS = 400L
        const val TROU_MS = 1_500L
        /** Relevé de la vitesse dédié au halo, plus serré que l'actualisation de la jauge. */
        const val RELEVE_MS = 200L
        /** Fondu à l'allumage et à l'extinction. */
        const val FONDU_MS = 330L
    }
}
