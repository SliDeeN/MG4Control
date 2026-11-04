package com.mg4.control.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.model.BubbleColors
import com.mg4.control.service.OverlayKit

/**
 * Choix de la couleur des boutons flottants (2026-11-04, maquette A + sélecteur de la B) :
 * des pastilles en un toucher, une pastille « Personnalisée… » qui ouvre [CouleurDialog], le code
 * couleur lisible et modifiable, et « Par défaut ».
 *
 * [surChoix] reçoit la couleur, ou null pour « Par défaut ».
 */
internal class CouleurRangee(
    private val ctx: Context,
    conteneur: ViewGroup,
    private val defaut: () -> Int,
    private val surChoix: (Int?) -> Unit,
) {
    private fun dp(v: Float) = OverlayKit.dp(ctx, v)
    private val pastilles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val perso = PastillePerso(ctx)
    private val code = EditText(ctx)
    private val parDefaut = MaterialButton(ctx)
    private var courante: Int? = null
    private var majCode = false

    init {
        conteneur.removeAllViews()
        BubbleColors.PALETTE.forEach { c ->
            pastilles.addView(View(ctx).apply {
                tag = c
                contentDescription = BubbleColors.hex(c)
                setOnClickListener { choisir(c) }
            }, LinearLayout.LayoutParams(dp(44f), dp(44f)).also { it.marginEnd = dp(8f) })
        }
        perso.contentDescription = ctx.getString(R.string.color_custom)
        perso.setOnClickListener {
            CouleurDialog.ouvrir(ctx, courante ?: defaut()) { c -> choisir(c) }
        }
        pastilles.addView(perso, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        conteneur.addView(pastilles)

        val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ligne.addView(TextView(ctx).apply {
            setText(R.string.color_code)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        code.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (majCode) return
                    BubbleColors.parse(s?.toString().orEmpty())?.let { if (it != courante) choisir(it, depuisCode = true) }
                }
            })
        }
        ligne.addView(code, LinearLayout.LayoutParams(dp(150f), ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(10f) })
        parDefaut.apply {
            setText(R.string.color_default)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            insetTop = 0; insetBottom = 0
            cornerRadius = dp(14f)
            strokeWidth = dp(1f)
            setOnClickListener { courante = null; peindre(null); surChoix(null) }
        }
        ligne.addView(parDefaut, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56f)).also { it.marginStart = dp(12f) })
        conteneur.addView(ligne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(10f) })
    }

    private fun choisir(c: Int, depuisCode: Boolean = false) {
        courante = c
        peindre(c, repeindreCode = !depuisCode)
        surChoix(c)
    }

    /** La couleur en cours (null = par défaut) : pastille cochée, code, « Par défaut » allumé. */
    fun peindre(c: Int?, repeindreCode: Boolean = true) {
        courante = c
        val vue = c ?: defaut()
        for (i in 0 until pastilles.childCount - 1) {
            val p = pastilles.getChildAt(i)
            val coul = p.tag as Int
            val on = c != null && coul == c
            p.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(coul)
                setStroke(dp(if (on) 4f else 1f), if (on) Color.WHITE else ctx.getColor(R.color.dash_border))
            }
        }
        perso.couleur = c?.takeIf { it !in BubbleColors.PALETTE }
        if (repeindreCode) {
            majCode = true
            code.setText(BubbleColors.hex(vue))
            majCode = false
        }
        ProfileChips.peindre(parDefaut, c == null)
    }
}

/** Pastille « Personnalisée… » : un cercle arc-en-ciel, la couleur choisie au centre. */
@SuppressLint("ViewConstructor")
internal class PastillePerso(ctx: Context) : View(ctx) {
    var couleur: Int? = null
        set(v) { field = v; invalidate() }
    private val anneau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        anneau.shader = SweepGradient(cx, cy, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED), null)
        canvas.drawCircle(cx, cy, r, anneau)
        val c = couleur
        centre.color = c ?: Color.argb(0xE6, 0x17, 0x17, 0x1A)
        canvas.drawCircle(cx, cy, r * 0.55f, centre)
        if (c != null) {
            trait.strokeWidth = r * 0.16f
            canvas.drawCircle(cx, cy, r - trait.strokeWidth / 2, trait)
        }
    }
}

/**
 * Sélecteur de la maquette B : carré clair / foncé en haut, barre de teinte en bas, au doigt.
 * [surChange] reçoit la couleur à chaque mouvement.
 */
internal class SelecteurTeinte(ctx: Context, initiale: Int, private val surChange: (Int) -> Unit) : View(ctx) {
    private val hsv = BubbleColors.toHsv(initiale)
    private val barre = OverlayKit.dp(ctx, 40f).toFloat()
    private val ecart = OverlayKit.dp(ctx, 16f).toFloat()
    private val rayon = OverlayKit.dp(ctx, 12f).toFloat()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val repere = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val ombre = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private var zone = 0   // 1 = carré, 2 = barre de teinte

    private fun carre() = RectF(0f, 0f, width.toFloat(), height - barre - ecart)
    private fun teinte() = RectF(0f, height - barre, width.toFloat(), height.toFloat())

    fun couleur(): Int = BubbleColors.fromHsv(hsv[0], hsv[1], hsv[2])

    fun regler(c: Int) {
        BubbleColors.toHsv(c).copyInto(hsv)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val q = carre()
        val pur = BubbleColors.fromHsv(hsv[0], 1f, 1f)
        p.shader = LinearGradient(q.left, 0f, q.right, 0f, Color.WHITE, pur, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(q, rayon, rayon, p)
        p.shader = LinearGradient(0f, q.top, 0f, q.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(q, rayon, rayon, p)
        val t = teinte()
        p.shader = LinearGradient(t.left, 0f, t.right, 0f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(t, barre / 2, barre / 2, p)
        p.shader = null
        val d = OverlayKit.dp(context, 3f).toFloat()
        repere.strokeWidth = d; ombre.strokeWidth = d + 2
        val sx = q.left + hsv[1] * q.width()
        val sy = q.top + (1 - hsv[2]) * q.height()
        canvas.drawCircle(sx, sy, rayon, ombre); canvas.drawCircle(sx, sy, rayon, repere)
        val hx = t.left + hsv[0] / 360f * t.width()
        canvas.drawCircle(hx, t.centerY(), barre / 2 - d, ombre); canvas.drawCircle(hx, t.centerY(), barre / 2 - d, repere)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                zone = if (e.y >= height - barre - ecart / 2) 2 else 1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { zone = 0; return true }
        }
        if (zone == 1) {
            val q = carre()
            hsv[1] = ((e.x - q.left) / q.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((e.y - q.top) / q.height()).coerceIn(0f, 1f)
        } else if (zone == 2) {
            val t = teinte()
            hsv[0] = ((e.x - t.left) / t.width()).coerceIn(0f, 1f) * 359.9f
        }
        invalidate()
        surChange(couleur())
        return true
    }
}

/** Fenêtre « Couleur personnalisée » : aperçu de la bulle, sélecteur, code ; OK rend la couleur. */
internal object CouleurDialog {

    fun ouvrir(ctx: Context, initiale: Int, surOk: (Int) -> Unit) {
        fun dp(v: Float) = OverlayKit.dp(ctx, v)
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        vue.findViewById<TextView>(R.id.rule_value_title).setText(R.string.color_custom_title)
        vue.findViewById<TextView>(R.id.rule_value_subtitle).apply {
            setText(R.string.color_custom_sub)
            visibility = View.VISIBLE
        }
        val corps = vue.findViewById<LinearLayout>(R.id.rule_value_body)
        val apercus = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val contour = TextView(ctx)
        val plein = TextView(ctx)
        listOf(contour, plein).forEach { t ->
            t.setText(R.string.color_preview)
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            t.setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            apercus.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = dp(16f) })
        }
        val code = EditText(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        var majCode = false
        fun apercu(c: Int) {
            contour.setTextColor(Color.rgb(0xE5, 0xE5, 0xE5))
            contour.background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat(); setColor(Color.argb(0xE6, 0x17, 0x17, 0x1A)); setStroke(dp(2f), c)
            }
            plein.setTextColor(BubbleColors.textOn(c))
            plein.background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat(); setColor(c); setStroke(dp(2f), c)
            }
        }
        val selecteur = SelecteurTeinte(ctx, initiale) { c ->
            apercu(c)
            majCode = true; code.setText(BubbleColors.hex(c)); majCode = false
        }
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (majCode) return
                BubbleColors.parse(s?.toString().orEmpty())?.let { selecteur.regler(it); apercu(it) }
            }
        })
        corps.addView(apercus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        corps.addView(selecteur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260f)).also { it.topMargin = dp(16f) })
        val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ligne.addView(TextView(ctx).apply {
            setText(R.string.color_code)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        ligne.addView(code, LinearLayout.LayoutParams(dp(150f), ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(10f) })
        corps.addView(ligne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(12f) })
        apercu(initiale)
        majCode = true; code.setText(BubbleColors.hex(initiale)); majCode = false

        var dialog: AlertDialog? = null
        vue.findViewById<TextView>(R.id.rule_value_cancel).apply {
            setText(R.string.profile_cancel)
            setOnClickListener { dialog?.dismiss() }
        }
        vue.findViewById<TextView>(R.id.rule_value_ok).apply {
            setText(android.R.string.ok)
            setOnClickListener { surOk(selecteur.couleur()); dialog?.dismiss() }
        }
        dialog = AppDialog.afficher(ctx, vue, 760f)
    }
}
