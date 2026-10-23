package com.mg4.control.model

import kotlin.math.abs

/**
 * Boutons de raccourci flottants (2026-10-23) — les règles, sans Android.
 *
 * L'utilisateur pose des bulles par-dessus toutes les applications : appui COURT = le raccourci
 * associé (même chemin que les raccourcis du volant), appui LONG = la déplacer. Il choisit
 * l'action, la taille de la bulle (curseur à trois repères aimantés, valeurs intermédiaires
 * permises), la taille du texte et, s'il le veut, le titre — par défaut le nom du raccourci, qui
 * suit l'action tant qu'il n'a pas été modifié (le clavier tactile est peu pratique en voiture).
 *
 * Positions : le CENTRE de la bulle, en proportion de l'écran — la bulle garde sa place quelle
 * que soit la taille de l'écran ou la sienne.
 */
data class FloatingButton(
    val id: String,
    /** [ShortcutAction.name] ; un nom inconnu (action retirée) rend la bulle inerte. */
    val action: String,
    /** Titre saisi à la main ; `null` = le nom du raccourci, qui suit l'action. */
    val customTitle: String? = null,
    /**
     * Nom du raccourci, dans la langue de l'appli : retenu au choix de l'action et remis à jour à
     * chaque ouverture de la page de réglage — le service n'a pas la liste des libellés.
     */
    val actionLabel: String = "",
    /** Taille de la bulle, 0 à 100 ([FloatingButtons.heightDp]). */
    val size: Int = FloatingButtons.SIZE_DEFAULT,
    /** Taille du texte, en sp. */
    val textSp: Int = FloatingButtons.TEXT_DEFAULT,
    val x: Float = 0.92f,
    val y: Float = 0.5f,
)

object FloatingButtons {

    const val MAX = 6
    const val SIZE_DEFAULT = 50
    const val TEXT_MIN = 10
    const val TEXT_MAX = 30
    const val TEXT_DEFAULT = 16
    /** Assez pour « Clim : A/C (ON/OFF) » (19), sur deux lignes au plus. */
    const val TITLE_MAX = 24

    /** Repères du curseur de taille : Petit, Moyen, Grand. */
    val SIZE_PRESETS = listOf(0, 50, 100)
    /** À moins de ce nombre de crans d'un repère, le curseur s'y cale. */
    const val SIZE_SNAP = 3

    /** Valeur de taille retenue : le repère le plus proche s'il est à portée, sinon la valeur même. */
    fun snapSize(value: Int): Int {
        val v = value.coerceIn(0, 100)
        return SIZE_PRESETS.firstOrNull { abs(it - v) <= SIZE_SNAP } ?: v
    }

    /** Rang du repère (0 Petit, 1 Moyen, 2 Grand) si [size] en est un ; `null` entre deux. */
    fun presetIndex(size: Int): Int? = SIZE_PRESETS.indexOf(size).takeIf { it >= 0 }

    /** Hauteur de la bulle, en dp : 44 (Petit) à 96 (Grand). */
    fun heightDp(size: Int): Float = 44f + 0.52f * size.coerceIn(0, 100)

    /** Largeur minimale : une bulle étroite resterait difficile à toucher. */
    fun minWidthDp(size: Int): Float = heightDp(size) * 1.6f

    fun clampText(sp: Int): Int = sp.coerceIn(TEXT_MIN, TEXT_MAX)

    /** Titre affiché : celui saisi, ou le nom du raccourci ([actionLabel]). */
    fun title(button: FloatingButton, actionLabel: String): String =
        button.customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: actionLabel

    /**
     * Titre à enregistrer après saisie : vide ou identique au nom du raccourci = « suit l'action »
     * (`null`), sinon le texte, coupé à [TITLE_MAX].
     */
    fun customTitleFor(typed: String, actionLabel: String): String? {
        val t = typed.trim().take(TITLE_MAX)
        return t.takeIf { it.isNotEmpty() && it != actionLabel }
    }

    /** Place d'une nouvelle bulle : en colonne sur la droite, sous les précédentes. */
    fun defaultPosition(index: Int): Pair<Float, Float> = 0.92f to (0.22f + 0.13f * index).coerceAtMost(0.9f)

    /**
     * Coin haut-gauche (px) d'une bulle de [w]×[h] dont le centre est en ([cx], [cy]) (proportions),
     * gardée entière dans un écran de [ecranW]×[ecranH].
     */
    fun topLeft(cx: Float, cy: Float, w: Int, h: Int, ecranW: Int, ecranH: Int): Pair<Int, Int> {
        val x = (cx * ecranW - w / 2f).toInt().coerceIn(0, (ecranW - w).coerceAtLeast(0))
        val y = (cy * ecranH - h / 2f).toInt().coerceIn(0, (ecranH - h).coerceAtLeast(0))
        return x to y
    }

    /** Centre (proportions) d'une bulle posée en ([x], [y]) px — l'inverse de [topLeft]. */
    fun centre(x: Int, y: Int, w: Int, h: Int, ecranW: Int, ecranH: Int): Pair<Float, Float> =
        ((x + w / 2f) / ecranW.coerceAtLeast(1)).coerceIn(0f, 1f) to
            ((y + h / 2f) / ecranH.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Rapport engagé en marche arrière, valeur SAIC (P = 1, R = 2, N = 3, D = 4 ; R à confirmer). */
    const val GEAR_REVERSE = 2
}
