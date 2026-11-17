package com.mg4.control.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Couleur des boutons flottants (2026-11-04, maquette A + sélecteur B) : une couleur générale,
 * et une par bulle au besoin. Elle colore la bordure, le fond de la bulle « activée », l'éclair de
 * la valeur et le halo. Couleurs en ARGB opaque.
 *
 * Calculs sans `android.graphics.Color` : testables hors voiture.
 */
object BubbleColors {

    /** Bleu de la refonte 2.7.0 (`dash_accent` du thème sombre) : la couleur par défaut. */
    const val DEFAULT = 0xFFA6C8FF.toInt()

    /** Les pastilles : lisibles par-dessus la carte, Android Auto ou CarPlay. */
    val PALETTE: List<Int> = listOf(
        0xFFA6C8FF, 0xFF38BDF8, 0xFF22C55E, 0xFFFACC15, 0xFFF97316,
        0xFFF43F5E, 0xFFE879F9, 0xFFA78BFA, 0xFFF1F5FC,
    ).map { it.toInt() }

    /** Texte posé sur une bulle pleine de cette couleur : bleu nuit sur clair, blanc sur foncé. */
    private const val TEXTE_SOMBRE = 0xFF0B1A33.toInt()
    private const val TEXTE_CLAIR = 0xFFFFFFFF.toInt()

    /** « #A6C8FF ». */
    fun hex(c: Int): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    /** « #a6c8ff », « A6C8FF » → la couleur opaque ; null si ce n'est pas un code à 6 chiffres. */
    fun parse(texte: String): Int? {
        val m = Regex("^#?([0-9A-Fa-f]{6})$").find(texte.trim()) ?: return null
        return m.groupValues[1].toInt(16) or 0xFF000000.toInt()
    }

    /** Luminance relative (WCAG), 0 à 1. */
    fun luminance(c: Int): Double {
        fun canal(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * canal(c shr 16 and 0xFF) + 0.7152 * canal(c shr 8 and 0xFF) + 0.0722 * canal(c and 0xFF)
    }

    /** Couleur du texte sur une bulle pleine de couleur [c]. */
    fun textOn(c: Int): Int = if (luminance(c) > 0.35) TEXTE_SOMBRE else TEXTE_CLAIR

    /** Teinte (0–360), saturation et valeur (0–1) → couleur opaque. */
    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1 - abs(hh % 2f - 1))
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun o(k: Float) = ((k + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (o(r) shl 16) or (o(g) shl 8) or o(b)
    }

    /** Couleur → teinte (0–360), saturation, valeur (0–1). */
    fun toHsv(c: Int): FloatArray {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }
}
