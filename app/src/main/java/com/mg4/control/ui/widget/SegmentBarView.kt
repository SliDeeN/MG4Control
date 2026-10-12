package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Barre à segments : un segment par cran, les [allumes] premiers en couleur. La ventilation du
 * pop-up HVAC « moderne » — où une barre continue ne dirait pas combien de crans il reste.
 *
 * Ne fait que MONTRER ; les couleurs viennent de l'appelant, comme pour [ArcGaugeView].
 */
class SegmentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Nombre de crans ; au-delà de [MAX] les segments ne se distingueraient plus. */
    var total = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var allumes = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var couleurAllume = 0
        set(value) { field = value; invalidate() }
    var couleurEteint = 0
        set(value) { field = value; invalidate() }

    private val densite = resources.displayMetrics.density
    private val ecart = 4f * densite
    private val arrondi = 3f * densite
    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = total.coerceIn(0, MAX)
        if (n == 0) return
        val largeur = (width - ecart * (n - 1)) / n
        if (largeur <= 0f) return
        val rayon = min(arrondi, height / 2f)
        for (i in 0 until n) {
            pinceau.color = if (i < allumes) couleurAllume else couleurEteint
            val gauche = i * (largeur + ecart)
            canvas.drawRoundRect(gauche, 0f, gauche + largeur, height.toFloat(), rayon, rayon, pinceau)
        }
    }

    private companion object {
        const val MAX = 12
    }
}
