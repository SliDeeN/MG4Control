package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Jauge en arc, ouverte vers le bas : la température du pop-up HVAC « moderne ».
 *
 * Une piste de trois quarts de tour, remplie jusqu'à [part], avec un curseur rond au bout. Elle
 * ne fait que MONTRER : on règle avec les boutons à côté, ou au joystick. L'arc est collé en
 * haut de la vue et laisse, sous ses extrémités, la place des deux bornes écrites par la mise en
 * page.
 *
 * Les couleurs viennent de l'appelant : la fenêtre suit le thème de l'application, pas celui du
 * système.
 */
class ArcGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Part de l'arc à remplir, de 0 à 1 ; null = rien de lu, la piste reste seule. */
    var part: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var couleurPiste = 0
        set(value) { field = value; invalidate() }
    var couleurValeur = 0
        set(value) { field = value; invalidate() }
    var couleurCurseur = 0
        set(value) { field = value; invalidate() }

    // La densité est celle du contexte qui a gonflé la mise en page : le pop-up la corrige pour
    // prendre la même part de l'écran partout, et la jauge doit grossir avec lui.
    private val densite = resources.displayMetrics.density
    private val epaisseur = 10f * densite
    private val rayonCurseur = 9f * densite
    private val cerclage = 3f * densite
    private val placeDesBornes = 18f * densite

    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cadre = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val marge = rayonCurseur + densite
        val rayon = min(width / 2f - marge, (height - 2 * marge - placeDesBornes) / (1f + SOUS_LE_CENTRE))
        if (rayon <= 0f) return
        val cx = width / 2f
        val cy = marge + rayon
        cadre.set(cx - rayon, cy - rayon, cx + rayon, cy + rayon)

        pinceau.style = Paint.Style.STROKE
        pinceau.strokeCap = Paint.Cap.ROUND
        pinceau.strokeWidth = epaisseur
        pinceau.color = couleurPiste
        canvas.drawArc(cadre, DEBUT, ETENDUE, false, pinceau)

        val p = part?.coerceIn(0f, 1f) ?: return
        pinceau.color = couleurValeur
        if (p > 0f) canvas.drawArc(cadre, DEBUT, ETENDUE * p, false, pinceau)

        val angle = Math.toRadians((DEBUT + ETENDUE * p).toDouble())
        val x = cx + rayon * cos(angle).toFloat()
        val y = cy + rayon * sin(angle).toFloat()
        pinceau.style = Paint.Style.FILL
        pinceau.color = couleurCurseur
        canvas.drawCircle(x, y, rayonCurseur, pinceau)
        pinceau.style = Paint.Style.STROKE
        pinceau.strokeWidth = cerclage
        pinceau.color = couleurValeur
        canvas.drawCircle(x, y, rayonCurseur - cerclage / 2f, pinceau)
    }

    private companion object {
        /** Angles d'Android : 0° à trois heures, sens horaire. L'arc part d'en bas à gauche. */
        const val DEBUT = 135f
        const val ETENDUE = 270f

        /** sin 45° : de combien les extrémités de l'arc descendent sous son centre, en rayons. */
        const val SOUS_LE_CENTRE = 0.7071f
    }
}
