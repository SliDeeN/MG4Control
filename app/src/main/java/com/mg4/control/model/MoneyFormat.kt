package com.mg4.control.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Mise en forme des prix au kWh et des montants, quelle que soit la monnaie.
 *
 * Un kWh vaut 0,187 en euros et 2 500 en roupies indonésiennes : les trois décimales indispensables
 * dans le premier cas ne sont que du bruit dans le second. Les seuils sont choisis pour que **rien
 * ne change pour les monnaies fortes**, les seules possibles tant que le prix était plafonné à 5.
 *
 * La langue est un paramètre : l'écran passe celle du boîtier, les tests une langue fixe.
 */
object MoneyFormat {

    /** Sous ce prix, trois décimales : c'est le régime de l'euro, du dollar, de la livre. */
    private const val SMALL_PRICE = 10f

    /** Sous ce montant, un dixième ; au-delà il ne veut plus rien dire. */
    private const val SMALL_AMOUNT = 1000f

    /** Insécable : un grand montant ne doit pas se couper en fin de ligne. */
    private const val GROUP = ' '

    /**
     * Prix d'un kWh. **Jamais de séparateur de milliers** : la valeur est reprise telle quelle par
     * les champs de saisie, et « 2 500 » ne se relirait plus comme un nombre.
     */
    fun price(value: Float, locale: Locale): String =
        if (abs(value) < SMALL_PRICE) String.format(locale, "%.3f", value)
        else DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(value.toDouble())

    /**
     * Montant : coût d'un trajet, d'une recharge, d'une période. Les grands montants sont entiers
     * et groupés par milliers avec une espace — la convention qui se lit dans toutes les langues.
     */
    fun amount(value: Float, locale: Locale): String =
        if (abs(value) < SMALL_AMOUNT) String.format(locale, "%.1f", value)
        else String.format(Locale.US, "%,d", value.roundToLong()).replace(',', GROUP)
}
