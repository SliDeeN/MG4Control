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
    /**
     * Bouton de la barre noire du haut (2026-10-24) : à la hauteur de la barre, déplacé de gauche
     * à droite seulement, jamais sur la languette de la clim. [x] est alors le centre en proportion
     * de la largeur de la barre ([FloatingButtons.TOP_AUTO] : place à choisir à la pose), [y] ne
     * sert plus. `null` = bouton sur l'écran (Gson : un bouton d'avant la fonction n'en a pas).
     */
    val topBar: Boolean? = null,
    /** Texte en gras (2026-10-24, sa demande) ; `null` = normal. */
    val bold: Boolean? = null,
    /** Raccourci perso lancé ([action] = CUSTOM, issue #172) ; son nom sert de [actionLabel]. */
    val customId: String? = null,
)

object FloatingButtons {

    const val MAX = 6
    const val SIZE_DEFAULT = 50
    const val TEXT_MIN = 10
    /** 40 depuis le 2026-10-24 (30 avant) : texte plus grand demandé après essai. */
    const val TEXT_MAX = 40
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

    // ── Barre du haut (2026-10-24) ───────────────────────────────────────────

    /**
     * Texte au plus, dans la barre : la bulle n'y a que la hauteur de la barre (36 dp sur SWI133,
     * moins les marges), soit une ligne de 22 sp. 14 au premier essai : trop petit (2026-10-24).
     */
    const val TOP_TEXT_MAX = 22
    /**
     * Largeur gardée libre au centre de la barre : la languette de la clim — la même que la zone
     * clim pendant Android Auto / CarPlay, pour qu'ils ne se gênent jamais.
     */
    const val TOP_HVAC_DP = 320f
    /** Écart gardé avec les bords de la barre, la languette et les boutons voisins. */
    const val TOP_GAP_DP = 6f
    /** Marge en haut et en bas de la bulle, dans la barre. */
    const val TOP_INSET_DP = 2f
    /** [FloatingButton.x] d'un bouton dont la place dans la barre est à choisir à la pose. */
    const val TOP_AUTO = -1f

    fun inTopBar(button: FloatingButton): Boolean = button.topBar == true

    /** Taille du texte affichée : bornée, et [TOP_TEXT_MAX] au plus dans la barre. */
    fun textFor(button: FloatingButton): Int =
        clampText(button.textSp).let { if (inTopBar(button)) it.coerceAtMost(TOP_TEXT_MAX) else it }

    /** La languette de la clim, en px, dans une barre de [barW] px : [zoneW] px au centre. */
    fun topBarHvac(barW: Float, zoneW: Float): ClosedFloatingPointRange<Float> =
        (barW / 2f - zoneW / 2f)..(barW / 2f + zoneW / 2f)

    /**
     * Bord gauche (px) d'un bouton de [w] px dans une barre de [barW] px : au plus près de [want],
     * sans toucher [bloques] (la languette, les boutons voisins) ni les bords, à [gap] près. Pendant
     * le glissé, le bouton saute ainsi par-dessus ce qu'il ne doit pas recouvrir. Plus aucune place
     * libre : [want] ramené dans la barre — un chevauchement plutôt qu'un bouton perdu.
     */
    fun placeInTopBar(
        want: Float, w: Float, barW: Float, bloques: List<ClosedFloatingPointRange<Float>>, gap: Float,
    ): Float {
        val obstacles = bloques.map { (it.start - gap)..(it.endInclusive + gap) }.sortedBy { it.start }
        val libres = mutableListOf<ClosedFloatingPointRange<Float>>()
        var debut = gap
        for (o in obstacles) {
            if (o.start > debut) libres += debut..o.start
            debut = maxOf(debut, o.endInclusive)
        }
        if (barW - gap > debut) libres += debut..(barW - gap)
        return libres.filter { it.endInclusive - it.start >= w }
            .map { want.coerceIn(it.start, it.endInclusive - w) }
            .minByOrNull { abs(it - want) }
            ?: want.coerceIn(0f, (barW - w).coerceAtLeast(0f))
    }

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

    /** Fenêtre du halo, en px : position à l'écran et taille. */
    data class HaloFrame(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Fenêtre du halo d'une bulle posée en ([x], [y]) px (2026-10-23) : la bulle agrandie de [marge]
     * de chaque côté, rognée aux bords de l'écran — l'anneau d'une bulle collée au bord est coupé,
     * plutôt que décalé par le gestionnaire de fenêtres.
     */
    fun haloFrame(x: Int, y: Int, w: Int, h: Int, marge: Int, ecranW: Int, ecranH: Int): HaloFrame {
        val gauche = (x - marge).coerceAtLeast(0)
        val haut = (y - marge).coerceAtLeast(0)
        val droite = (x + w + marge).coerceAtMost(ecranW)
        val bas = (y + h + marge).coerceAtMost(ecranH)
        return HaloFrame(gauche, haut, (droite - gauche).coerceAtLeast(1), (bas - haut).coerceAtLeast(1))
    }

    /** Rapport engagé en marche arrière, valeur SAIC (P = 1, R = 2, N = 3, D = 4 ; R à confirmer). */
    const val GEAR_REVERSE = 2
}
