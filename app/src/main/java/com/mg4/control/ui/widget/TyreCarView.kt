package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mg4.control.R

/**
 * La voiture vue de dessus de la fenêtre Pression des pneus (maquette du 2026-11-20) : carrosserie,
 * vitres, toit, et les quatre roues à la couleur de leur état ([couleurs] : avant gauche, avant
 * droite, arrière gauche, arrière droite). Dessinée dans une boîte 200 × 400, mise à l'échelle.
 */
class TyreCarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val corps = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.dash_section) }
    private val toit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.dash_border) }
    private val vitre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.dash_text_lo) }
    private val roue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val p = Path()

    var couleurs: IntArray = IntArray(4) { context.getColor(R.color.dash_text_lo) }
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width / 200f, height / 400f)
        canvas.save()
        canvas.translate((width - 200f * s) / 2f, (height - 400f * s) / 2f)
        canvas.scale(s, s)
        val roues = arrayOf(floatArrayOf(14f, 62f), floatArrayOf(156f, 62f), floatArrayOf(14f, 266f), floatArrayOf(156f, 266f))
        roues.forEachIndexed { i, (x, y) ->
            roue.color = couleurs.getOrElse(i) { couleurs.last() }
            r.set(x, y, x + 30f, y + 72f)
            canvas.drawRoundRect(r, 10f, 10f, roue)
        }
        r.set(32f, 14f, 168f, 386f)
        canvas.drawRoundRect(r, 58f, 58f, corps)
        p.reset()
        p.moveTo(50f, 118f); p.quadTo(100f, 92f, 150f, 118f); p.lineTo(142f, 168f); p.quadTo(100f, 156f, 58f, 168f); p.close()
        canvas.drawPath(p, vitre)
        r.set(58f, 176f, 142f, 296f)
        canvas.drawRoundRect(r, 14f, 14f, toit)
        p.reset()
        p.moveTo(60f, 304f); p.quadTo(100f, 312f, 140f, 304f); p.lineTo(148f, 340f); p.quadTo(100f, 356f, 52f, 340f); p.close()
        canvas.drawPath(p, vitre)
        canvas.restore()
    }
}
