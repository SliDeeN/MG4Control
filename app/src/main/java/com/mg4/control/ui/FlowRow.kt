package com.mg4.control.ui

import android.content.Context
import android.view.ViewGroup

/** Une rangée qui passe à la ligne quand la place manque (puces des données ABRP, valeurs des Réglages rapides). */
class FlowRow(ctx: Context, ecartDp: Float = 8f) : ViewGroup(ctx) {
    private val ecart = AppDialog.dp(ctx, ecartDp)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val largeur = MeasureSpec.getSize(wSpec)
        var x = 0; var y = 0; var ligne = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(largeur, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > largeur) { x = 0; y += ligne + ecart; ligne = 0 }
            x += c.measuredWidth + ecart
            ligne = maxOf(ligne, c.measuredHeight)
        }
        setMeasuredDimension(largeur, y + ligne)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val largeur = r - l
        var x = 0; var y = 0; var ligne = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > largeur) { x = 0; y += ligne + ecart; ligne = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + ecart
            ligne = maxOf(ligne, c.measuredHeight)
        }
    }
}
