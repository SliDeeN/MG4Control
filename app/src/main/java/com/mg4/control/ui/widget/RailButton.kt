package com.mg4.control.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton

/**
 * Onglet d'un rail de catégories (refonte 2.7.0) : un [MaterialButton] dont le pictogramme prend
 * toujours la couleur du texte.
 *
 * Chaque écran allume l'onglet choisi en changeant sa couleur de texte (`setTextColor`) ; sans
 * ce relais, le pictogramme resterait gris sur l'onglet actif. Les écrans continuent de le
 * chercher en `findViewById<MaterialButton>`.
 */
class RailButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = MaterialR.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    // Faux tant que le constructeur parent tourne : TextView y appelle déjà setTextColor, avant
    // que le bouton sache porter un pictogramme.
    private var pret = false

    init {
        pret = true
        iconTint = textColors
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        if (pret) iconTint = ColorStateList.valueOf(color)
    }

    override fun setTextColor(colors: ColorStateList?) {
        super.setTextColor(colors)
        if (pret && colors != null) iconTint = colors
    }
}
