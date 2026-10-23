package com.mg4.control.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.FloatingButtons
import kotlin.math.roundToInt

/**
 * Halo des boutons flottants (2026-10-23, variante D choisie sur maquette) : au relâchement d'un
 * appui court, un anneau sort de la bulle et s'efface en [DUREE_MS]. Le doigt cache la bulle
 * pendant l'appui ; l'anneau, lui, se voit autour.
 *
 * Il se dessine dans une fenêtre à part, posée le temps de l'animation et qui ne prend aucun appui
 * (`FLAG_NOT_TOUCHABLE`) : la fenêtre de la bulle garde sa taille, donc sa place enregistrée et son
 * déplacement. Près d'un bord, la fenêtre est rognée à l'écran et l'anneau coupé
 * ([FloatingButtons.haloFrame]). Thread principal.
 */
object FloatingHalo {

    private const val DUREE_MS = 550L
    /** Distance parcourue par l'anneau hors de la bulle. */
    private const val PORTEE_DP = 18f
    /** Marge de la fenêtre autour de la bulle : la portée, plus de quoi ne pas couper le trait. */
    private const val MARGE_DP = 22f
    private const val TRAIT_DP = 2f

    /** Bulle posée en ([x], [y]) px, de [largeur] × [hauteur] px, sur un écran de taille [ecran]. */
    fun jouer(ctx: Context, wm: WindowManager, x: Int, y: Int, largeur: Int, hauteur: Int, ecran: Point, couleur: Int) {
        if (largeur <= 0 || hauteur <= 0) return
        val d = ctx.resources.displayMetrics.density
        val cadre = FloatingButtons.haloFrame(x, y, largeur, hauteur, (MARGE_DP * d).roundToInt(), ecran.x, ecran.y)
        val vue = Anneau(ctx, x - cadre.left, y - cadre.top, largeur, hauteur, PORTEE_DP * d, TRAIT_DP * d, couleur)
        val params = WindowManager.LayoutParams(
            cadre.width,
            cadre.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = cadre.left
            this.y = cadre.top
            title = "MG4Control halo"
        }
        try {
            wm.addView(vue, params)
        } catch (e: Exception) {
            AppLogger.w(FloatingButtonsOverlay.TAG, "halo non posé : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DUREE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                vue.avancement = it.animatedValue as Float
                vue.invalidate()
            }
            // Fin normale ou animation coupée : la fenêtre part dans les deux cas.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { wm.removeViewImmediate(vue) }
                }
            })
            start()
        }
    }

    /** L'anneau : la forme de la bulle, agrandie de [portee] × avancement, de plus en plus pâle. */
    private class Anneau(
        ctx: Context,
        private val gauche: Int,
        private val haut: Int,
        private val largeur: Int,
        private val hauteur: Int,
        private val portee: Float,
        trait: Float,
        couleur: Int,
    ) : View(ctx) {

        var avancement = 0f

        private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = trait
            color = couleur
        }
        private val forme = RectF()

        override fun onDraw(canvas: Canvas) {
            val ecart = portee * avancement
            pinceau.alpha = ((1f - avancement) * 255).roundToInt().coerceIn(0, 255)
            forme.set(gauche - ecart, haut - ecart, gauche + largeur + ecart, haut + hauteur + ecart)
            val rayon = hauteur / 2f + ecart
            canvas.drawRoundRect(forme, rayon, rayon, pinceau)
        }
    }
}
