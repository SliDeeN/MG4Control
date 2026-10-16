package com.mg4.control.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.mg4.control.R
import com.mg4.control.model.ChargeLimit

/**
 * Barre de batterie du pop-up de charge, devenue curseur de la limite de charge (2026-10-16) :
 * remplie jusqu'au niveau, une poignée sur la limite, les sept crans de 40 à 100 % dessous. On la
 * glisse au doigt : une bulle donne le cran visé, et la limite part à la voiture au relâchement
 * seulement ([surRelache]).
 *
 * Pendant la charge, un reflet court sur la barre — du début jusqu'à la limite, jamais au-delà
 * (même règle que le module du combiné).
 */
class ChargeLimitSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Niveau de la batterie, en %. */
    var niveau: Float? = null
        set(v) { field = v; invalidate() }
    /** Limite de la voiture (ou celle demandée, en attente) ; `null` = inconnue, curseur inactif. */
    var limite: Int? = null
        set(v) { field = v; if (!glisse) invalidate() }
    var enCharge = false
        set(v) { field = v; invalidate() }
    var finie = false
        set(v) { field = v; invalidate() }

    /** Pendant le glissement, à chaque changement de cran. */
    var surGlisse: ((Int) -> Unit)? = null
    /** Au relâchement, si le cran a changé. */
    var surRelache: ((Int) -> Unit)? = null

    private var glisse = false
    private var visee = 0

    private val cAccent = context.getColor(R.color.dash_accent)
    private val cVert = context.getColor(R.color.dash_eco)
    private val cPiste = context.getColor(R.color.dash_border)
    private val cTexte = context.getColor(R.color.text_primary)
    private val cTexte2 = context.getColor(R.color.text_secondary)
    private val cSurAccent = context.getColor(R.color.hvac_on_accent)

    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val gras = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val cadre = RectF()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // Géométrie : la place de la bulle en haut, la piste, puis les crans.
    private val marge get() = dp(16f)
    private val hautPiste get() = dp(34f)
    private val epaisseur get() = dp(12f)
    private fun x(pct: Float) = marge + (width - 2 * marge) * pct.coerceIn(0f, 100f) / 100f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val couleur = if (finie) cVert else cAccent
        val y0 = hautPiste
        val y1 = y0 + epaisseur
        val rayon = epaisseur / 2f

        plein.color = cPiste
        cadre.set(marge, y0, width - marge, y1)
        canvas.drawRoundRect(cadre, rayon, rayon, plein)
        niveau?.takeIf { it > 0f }?.let {
            plein.color = couleur
            cadre.set(marge, y0, x(it), y1)
            canvas.drawRoundRect(cadre, rayon, rayon, plein)
        }
        val cible = if (glisse) visee else limite
        // Reflet : du début de la barre jusqu'à la limite, jamais au-delà.
        if (enCharge && !finie) {
            val fin = x((cible ?: 100).toFloat())
            val largeur = dp(26f)
            val p = (SystemClock.uptimeMillis() % REFLET_MS).toFloat() / REFLET_MS
            val xr = marge + p * (fin - marge + largeur) - largeur
            canvas.save()
            canvas.clipRect(marge, y0, fin, y1)
            plein.color = Color.argb(115, 255, 255, 255)
            cadre.set(xr, y0, xr + largeur, y1)
            canvas.drawRoundRect(cadre, rayon, rayon, plein)
            canvas.restore()
            postInvalidateDelayed(IMAGE_MS)
        }

        // Crans : un trait et son libellé, celui de la limite en clair et en gras.
        trait.strokeWidth = dp(1.5f)
        trait.color = cTexte2
        texte.textSize = sp(13f)
        ChargeLimit.CRANS.forEach { c ->
            val xc = x(c.toFloat())
            canvas.drawLine(xc, y1 + dp(6f), xc, y1 + dp(11f), trait)
            val choisi = c == cible
            texte.typeface = if (choisi) gras else Typeface.DEFAULT
            texte.color = if (choisi) cTexte else cTexte2
            canvas.drawText(c.toString(), xc, y1 + dp(28f), texte)
        }

        // Poignée sur la limite ; plus grosse et auréolée pendant le glissement.
        cible ?: return
        val xp = x(cible.toFloat())
        val yc = (y0 + y1) / 2f
        val r = dp(if (glisse) 15f else 12f)
        if (glisse) {
            plein.color = Color.argb(56, Color.red(couleur), Color.green(couleur), Color.blue(couleur))
            canvas.drawCircle(xp, yc, r + dp(8f), plein)
        }
        plein.color = Color.WHITE
        canvas.drawCircle(xp, yc, r, plein)
        trait.strokeWidth = dp(4f)
        trait.color = couleur
        canvas.drawCircle(xp, yc, r - dp(2f), trait)

        if (glisse) {
            val libelle = "$cible %"
            texte.typeface = gras
            texte.textSize = sp(15f)
            val l = texte.measureText(libelle) + dp(18f)
            val xb = xp.coerceIn(l / 2f, width - l / 2f)
            plein.color = couleur
            cadre.set(xb - l / 2f, 0f, xb + l / 2f, dp(26f))
            canvas.drawRoundRect(cadre, dp(8f), dp(8f), plein)
            texte.color = cSurAccent
            canvas.drawText(libelle, xb, dp(18.5f), texte)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val depart = limite ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                glisse = true
                visee = depart
                viser(event.x)
            }
            MotionEvent.ACTION_MOVE -> if (glisse) viser(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (glisse) {
                glisse = false
                if (event.actionMasked == MotionEvent.ACTION_UP && visee != depart) surRelache?.invoke(visee)
                invalidate()
            }
        }
        return true
    }

    private fun viser(xDoigt: Float) {
        val pct = (xDoigt - marge) / (width - 2 * marge).coerceAtLeast(1f) * 100f
        val cran = ChargeLimit.cran(pct)
        if (cran != visee) {
            visee = cran
            surGlisse?.invoke(cran)
        }
        invalidate()
    }

    /** Vrai pendant que le doigt tient la poignée : le pop-up n'y repeint pas la limite lue. */
    fun enGlissement(): Boolean = glisse

    private companion object {
        const val REFLET_MS = 1_800L
        const val IMAGE_MS = 50L
    }
}
