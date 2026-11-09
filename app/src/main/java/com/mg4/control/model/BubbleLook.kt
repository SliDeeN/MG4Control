package com.mg4.control.model

import kotlin.math.roundToInt

/**
 * Apparence d'un bouton flottant (issue #230, 2026-11-09, maquette validée) : opacité du fond, fond
 * sombre / teinté / clair, bordure, atténuation au repos — réglées bulle par bulle, copiables d'une
 * bulle à l'autre. Sans réglage, une bulle garde l'allure d'avant (fond sombre à 90 %, bordure).
 *
 * Calculs sans `android.graphics.Color` : testables hors voiture. Couleurs en ARGB.
 */
object BubbleLook {

    const val FILL_DARK = "dark"
    const val FILL_TINT = "tint"
    const val FILL_LIGHT = "light"

    /** 0xE6 / 255 ≈ 90 % : le fond d'avant la fonction. */
    const val OPACITY_DEFAULT = 90
    const val OPACITY_STEP = 5
    /** Bascule activée : jamais sous ce seuil, pour que son état se lise. */
    const val TOGGLE_ON_MIN = 60
    /** Sous ce seuil, le texte n'a plus de fond derrière lui : il prend un contour. */
    const val HALO_BELOW = 35
    /** Bascule coupée : fond à 35 % (0x59), comme avant — atténué à proportion de l'opacité choisie. */
    private const val TOGGLE_OFF_RATIO = 0x59 / 255f / 0.9f

    /** Atténuation au repos : toute la bulle à 40 % après 5 s sans toucher. */
    const val DIM_ALPHA = 0.4f
    const val DIM_AFTER_MS = 5_000L

    private const val SOMBRE = 0xFF17171A.toInt()
    private const val CLAIR = 0xFFF4F5F7.toInt()
    private const val TEXTE_CLAIR = 0xFFE5E5E5.toInt()
    private const val TEXTE_SOMBRE = 0xFF17171A.toInt()

    fun opacity(b: FloatingButton): Int = (b.opacity ?: OPACITY_DEFAULT).coerceIn(0, 100)
    fun fill(b: FloatingButton): String = b.fill?.takeIf { it == FILL_TINT || it == FILL_LIGHT } ?: FILL_DARK
    fun border(b: FloatingButton): Boolean = b.border != false
    fun dims(b: FloatingButton): Boolean = b.dimIdle == true

    /** Rendu d'une bulle : fond, texte, bordure (null = aucune), contour du texte. */
    data class Paint(val background: Int, val text: Int, val border: Int?, val halo: Boolean)

    /**
     * [color] : couleur de la bulle ; [toggle] : état d'une bascule (true = activée, pleine), null
     * pour une action simple ; [moving] : en cours de déplacement (bordure orange, fond opaque).
     */
    fun paint(b: FloatingButton, color: Int, toggle: Boolean?, moving: Boolean = false): Paint {
        val op = if (moving) 100 else opacity(b)
        val bordure = if (moving) MOVING_BORDER else color.takeIf { border(b) }
        if (toggle == true) {
            // 10 points de plus que le fond, entre 60 et 100 % : à 90 % (défaut), pleine comme avant.
            val a = (op + 10).coerceIn(TOGGLE_ON_MIN, 100)
            return Paint(withAlpha(color, a), BubbleColors.textOn(color), bordure, halo = false)
        }
        val base = when (fill(b)) { FILL_TINT -> color; FILL_LIGHT -> CLAIR; else -> SOMBRE }
        val a = if (toggle == false) (op * TOGGLE_OFF_RATIO).roundToInt() else op
        val texte = when {
            fill(b) == FILL_LIGHT -> TEXTE_SOMBRE
            fill(b) == FILL_TINT && a > 55 -> BubbleColors.textOn(color)
            else -> TEXTE_CLAIR
        }
        // Presque sans fond, le texte se lit sur la carte grâce à un contour sombre.
        return Paint(withAlpha(base, a), texte, bordure, halo = a < HALO_BELOW && fill(b) != FILL_LIGHT)
    }

    /** [c] avec une opacité de [percent] %. */
    fun withAlpha(c: Int, percent: Int): Int =
        ((percent.coerceIn(0, 100) * 255 / 100f).roundToInt() shl 24) or (c and 0xFFFFFF)

    /** Orange du déplacement : inchangé. */
    const val MOVING_BORDER = 0xFFF59E0B.toInt()

    /** Les quatre réglages de [from] recopiés sur [to] — ni titre, ni action, ni couleur. */
    fun copy(from: FloatingButton, to: FloatingButton): FloatingButton =
        to.copy(opacity = from.opacity, fill = from.fill, border = from.border, dimIdle = from.dimIdle)

    /** Résumé de la liste : opacité · fond · options, dans la langue de l'appli. */
    fun summary(b: FloatingButton, fillName: (String) -> String, noBorder: String, dimmed: String): String =
        listOfNotNull("${opacity(b)} %", fillName(fill(b)), noBorder.takeIf { !border(b) }, dimmed.takeIf { dims(b) })
            .joinToString(" · ")
}
