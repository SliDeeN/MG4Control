package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mg4.control.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Anneau de l'écran de pré-climatisation (issue #130, maquette du 2026-10-23) : fond noir, une
 * animation bleue discrète. L'arc dit la part écoulée de l'étape ([fraction]) ; en attente un
 * point fait lentement le tour ([Anim.ORBITE], 14 s), clim en marche de fines ondes partent du
 * centre ([Anim.ONDES]) ; pendant le décompte l'arc se vide ([Anim.AUCUNE], c'est [fraction] qui
 * bouge). Une image toutes les [IMAGE_MS] au plus, et seulement quand quelque chose bouge.
 *
 * Le disque intérieur est noir : derrière, les particules de [PreClimateParticlesView] passent, et
 * le décompte doit rester net. [couleur] suit celle des particules (bleu, ou orange quand ça chauffe).
 */
class PreClimateRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Anim { AUCUNE, ORBITE, ONDES }

    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var anim: Anim = Anim.AUCUNE
        set(v) { field = v; invalidate() }

    /** Couleur de l'arc, du point et des ondes ; le bleu de l'application par défaut. */
    var couleur: Int = context.getColor(R.color.dash_accent)
        set(v) { if (field != v) { field = v; invalidate() } }

    private val cPiste = context.getColor(R.color.dash_border)
    private val epaisseur = dp(9f)

    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cadre = RectF()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - epaisseur
        if (r <= 0f) return
        val t = SystemClock.uptimeMillis()

        plein.color = Color.BLACK
        plein.alpha = 255
        canvas.drawCircle(cx, cy, r + epaisseur / 2f, plein)
        trait.strokeWidth = epaisseur
        trait.color = cPiste
        trait.alpha = 255
        canvas.drawCircle(cx, cy, r, trait)
        cadre.set(cx - r, cy - r, cx + r, cy + r)
        if (fraction > 0.002f) {
            trait.color = couleur
            canvas.drawArc(cadre, -90f, 360f * fraction, false, trait)
        }

        when (anim) {
            Anim.ORBITE -> {
                val a = -PI / 2 + 2 * PI * ((t % ORBITE_MS).toDouble() / ORBITE_MS)
                val respiration = 0.55f + 0.35f * sin(2 * PI * (t % RESPIRATION_MS) / RESPIRATION_MS).toFloat()
                plein.color = couleur
                plein.alpha = (255 * respiration).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), epaisseur * 0.45f, plein)
            }
            Anim.ONDES -> {
                trait.strokeWidth = dp(1.6f)
                trait.color = couleur
                for (k in 0 until NB_ONDES) {
                    val p = ((t % ONDE_MS).toFloat() / ONDE_MS + k.toFloat() / NB_ONDES) % 1f
                    trait.alpha = ((1f - p) * 0.55f * 255).toInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, r * (0.30f + 0.52f * p), trait)
                }
                trait.alpha = 255
            }
            Anim.AUCUNE -> Unit
        }
        if (anim != Anim.AUCUNE && isAttachedToWindow) postInvalidateDelayed(IMAGE_MS)
    }

    private companion object {
        const val ORBITE_MS = 14_000L
        const val RESPIRATION_MS = 4_800L
        const val ONDE_MS = 3_200L
        const val NB_ONDES = 3
        const val IMAGE_MS = 40L
    }
}
