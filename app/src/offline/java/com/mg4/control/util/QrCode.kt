package com.mg4.control.util

import android.graphics.Bitmap

/** Flavor offline : ZXing n'est pas embarqué → aucun QR généré. */
object QrCode {
    // Même signature que la version online, dont elle prend la place : paramètres ignorés et
    // retour toujours null sont voulus. L'IDE signale « Redundant suppression » quel que soit
    // l'emplacement, alors que la suppression agit bien : faux positif connu, laisser tel quel.
    @Suppress("unused", "SameReturnValue")
    fun generate(content: String, sizePx: Int): Bitmap? = null
}
