package com.mg4.control.model

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Correction d'un prix aux boutons − / +, sans clavier : les pas proposés, et ce que vaut un appui.
 * Pur et testable à dessein.
 */
object PriceStepper {

    /** Le plus fin des pas : le dernier chiffre qu'affiche [MoneyFormat.price]. */
    private const val FINEST = 0.001

    private const val STEPS = 4

    /**
     * Les pas proposés pour un prix de l'ordre de [reference], du plus fin au plus gros, de dix en
     * dix. Ils suivent l'ordre de grandeur du prix : un kWh se règle au millième en euros, à
     * l'unité en roupies indonésiennes — où le millième ne voudrait rien dire.
     */
    fun steps(reference: Float): List<Float> {
        val exposant = if (reference > 0f) floor(log10(reference.toDouble())) else 0.0
        val plusFin = 10.0.pow(exposant - 3).coerceAtLeast(FINEST)
        return List(STEPS) { (plusFin * 10.0.pow(it)).toFloat() }
    }

    /**
     * [value] après un appui : déplacé de [step] dans le sens de [direction] (−1 ou +1), ramené
     * dans ses bornes ([StatsSettings.clampPrice]) et arrondi au millième — sans quoi cent appuis
     * laisseraient derrière eux la poussière des nombres flottants.
     */
    fun move(value: Float, step: Float, direction: Int): Float {
        val brut = value.toDouble() + direction * step.toDouble()
        val net = (brut / FINEST).roundToLong() * FINEST
        return StatsSettings.clampPrice(net.toFloat())
    }
}
