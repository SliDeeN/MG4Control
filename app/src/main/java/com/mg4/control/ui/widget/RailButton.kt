package com.mg4.control.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.util.NavSide

/**
 * Onglet de navigation (menu principal, sous-onglets en pastilles) : un [MaterialButton] dont le
 * pictogramme prend toujours la couleur du texte.
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

/**
 * Refonte B : allume ou éteint un sous-onglet en pastille (style `Widget.MG4.CategoryTab`).
 * Choisi : fond d'accent plein et texte foncé ; sinon fond de carte et bordure. Quand la rangée
 * défile (Raccourcis : jusqu'à onze sous-onglets), le choisi est amené en vue. Menu à droite
 * ([NavSide]) : les pastilles se rangent contre le bord droit, du côté du conducteur.
 */
fun MaterialButton.setCategoryTabActive(on: Boolean) {
    val c = context
    backgroundTintList = ColorStateList.valueOf(c.getColor(if (on) R.color.dash_accent else R.color.dash_card))
    setTextColor(c.getColor(if (on) R.color.dash_on_accent else R.color.text_primary))
    strokeColor = ColorStateList.valueOf(c.getColor(if (on) R.color.dash_accent else R.color.dash_border))
    val rangee = parent?.parent as? HorizontalScrollView ?: return
    if (NavSide.right(c) && !rangee.isFillViewport) {
        rangee.isFillViewport = true
        (parent as? LinearLayout)?.gravity = Gravity.END
    }
    if (!on) return
    rangee.post {
        // Un peu de marge : le bord de la rangée s'estompe, le libellé y serait à moitié lisible.
        val marge = (48 * resources.displayMetrics.density).toInt()
        val gauche = left - marge
        val droite = right + marge
        when {
            gauche < rangee.scrollX -> rangee.smoothScrollTo(gauche.coerceAtLeast(0), 0)
            droite > rangee.scrollX + rangee.width -> rangee.smoothScrollTo(droite - rangee.width, 0)
        }
    }
}
