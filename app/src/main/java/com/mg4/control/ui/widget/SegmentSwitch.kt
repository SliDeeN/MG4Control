package com.mg4.control.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.widget.Switch
import com.mg4.control.R
import kotlin.math.max

/**
 * Interrupteur dessiné en deux segments « Non | Oui » (refonte 2.7.0, style DiAuto retenu sur
 * maquette) : plus gros, et l'état se lit en toutes lettres au lieu d'une position de curseur.
 *
 * C'est un [Switch] : les écrans le cherchent toujours en `findViewById<Switch>`, lisent
 * `isChecked` et écoutent `setOnCheckedChangeListener` sans rien savoir de son apparence. Seuls
 * la mesure, le dessin et le toucher changent : un appui sur « Oui » coche, sur « Non » décoche, un
 * appui sur le segment déjà choisi ne fait rien. L'accessibilité garde le comportement du Switch
 * (un « clic » bascule).
 */
@SuppressLint("UseSwitchCompatOrMaterialCode")   // voulu : les écrans attendent un android.widget.Switch
class SegmentSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.switchStyle,
) : Switch(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val non = context.getString(R.string.segment_no)
    private val oui = context.getString(R.string.segment_yes)

    private val cAccent = context.getColor(R.color.dash_accent)
    private val cSurAccent = context.getColor(R.color.hvac_on_accent)
    private val cBord = context.getColor(R.color.dash_border)
    private val cTexte = context.getColor(R.color.text_secondary)

    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
    }
    private val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccent }
    private val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp
        color = cBord
    }
    private val cadre = RectF()
    private val forme = Path()

    init {
        // Le fond par défaut du Switch (ondulation rectangulaire) jurerait avec les coins arrondis.
        background = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // La mesure du Switch prépare sa mise en page interne (lue ailleurs par TextView) ; seule
        // la taille retenue change.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val segment = max(CELLULE_MIN_DP * dp, max(texte.measureText(non), texte.measureText(oui)) + 28 * dp)
        val largeur = (2 * segment).toInt() + paddingLeft + paddingRight
        val hauteur = (HAUTEUR_DP * dp).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(largeur, widthMeasureSpec), resolveSize(hauteur, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val demi = bord.strokeWidth / 2
        cadre.set(paddingLeft + demi, paddingTop + demi, width - paddingRight - demi, height - paddingBottom - demi)
        val rayon = RAYON_DP * dp
        val milieu = cadre.centerX()
        val opacite = if (isEnabled) 255 else 90

        // Segment choisi : la moitié gauche (Non) ou droite (Oui), découpée dans le cadre arrondi.
        forme.reset()
        forme.addRoundRect(cadre, rayon, rayon, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(forme)
        fond.alpha = opacite
        if (isChecked) canvas.drawRect(milieu, cadre.top, cadre.right, cadre.bottom, fond)
        else canvas.drawRect(cadre.left, cadre.top, milieu, cadre.bottom, fond)
        canvas.restore()

        bord.alpha = opacite
        canvas.drawRoundRect(cadre, rayon, rayon, bord)
        canvas.drawLine(milieu, cadre.top, milieu, cadre.bottom, bord)

        val y = cadre.centerY() - (texte.descent() + texte.ascent()) / 2
        dessinerLibelle(canvas, non, (cadre.left + milieu) / 2, y, choisi = !isChecked, opacite)
        dessinerLibelle(canvas, oui, (milieu + cadre.right) / 2, y, choisi = isChecked, opacite)
    }

    private fun dessinerLibelle(canvas: Canvas, libelle: String, x: Float, y: Float, choisi: Boolean, opacite: Int) {
        texte.color = if (choisi) cSurAccent else cTexte
        texte.typeface = if (choisi) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        texte.alpha = opacite
        canvas.drawText(libelle, x, y, texte)
    }

    @SuppressLint("ClickableViewAccessibility")   // performClick reste celui du Switch (bascule)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
            val veutOui = event.x >= width / 2f
            if (veutOui != isChecked) {
                playSoundEffect(SoundEffectConstants.CLICK)
                isChecked = veutOui
            }
        }
        // Tout le geste est pris : un défilement de la page reprend la main par ACTION_CANCEL.
        return true
    }

    private companion object {
        const val HAUTEUR_DP = 52f
        const val CELLULE_MIN_DP = 82f
        const val RAYON_DP = 16f
    }
}
