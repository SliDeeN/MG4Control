package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.mg4.control.R
import com.mg4.control.service.OverlayKit
import java.util.Locale

/**
 * Fenêtres de réglage de l'éditeur de Mes automatisations (issue #201, étape 2) : un nombre, une
 * heure, une plage horaire, des jours, un texte. Pas de clavier sauf pour le texte : des − / + que
 * l'on peut tenir appuyés ([AppDialog.repeter]), plus sûrs à manier dans une voiture.
 */
internal object RuleValueDialogs {

    private const val LARGEUR_DP = 760f
    private const val LARGEUR_LARGE_DP = 1000f

    /**
     * Pose la fenêtre : titre, explication, corps rempli par [remplir], puis (Retour) / Annuler / OK.
     * [remplir] rend ce que fait OK : vrai referme la fenêtre, faux la laisse ouverte (saisie à
     * corriger). Dans une fenêtre à étapes ([Assistant.courant]), elle remplace l'étape d'avant et
     * Retour y ramène ; sinon, une fenêtre à elle.
     */
    private fun fenetre(
        ctx: Context, titre: CharSequence, sousTitre: CharSequence?, largeurDp: Float,
        remplir: (LinearLayout) -> () -> Boolean,
    ): AlertDialog? {
        fun construire(surAnnule: () -> Unit, surRetour: (() -> Unit)?, surOk: (() -> Boolean) -> Unit): View {
            val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
            vue.findViewById<TextView>(R.id.rule_value_title).text = titre
            vue.findViewById<TextView>(R.id.rule_value_subtitle).apply {
                text = sousTitre
                visibility = if (sousTitre.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            val ok = remplir(vue.findViewById(R.id.rule_value_body))
            vue.findViewById<TextView>(R.id.rule_value_cancel).apply {
                text = ctx.getString(R.string.profile_cancel)
                setOnClickListener { surAnnule() }
            }
            vue.findViewById<TextView>(R.id.rule_value_back).apply {
                visibility = if (surRetour == null) View.GONE else View.VISIBLE
                setOnClickListener { surRetour?.invoke() }
            }
            vue.findViewById<TextView>(R.id.rule_value_ok).apply {
                text = ctx.getString(android.R.string.ok)
                setOnClickListener { surOk(ok) }
            }
            return vue
        }
        val a = Assistant.courant
        if (a != null) {
            a.etape {
                a.afficher(construire(
                    surAnnule = { a.fermer() },
                    surRetour = if (a.peutRetour) ({ a.retour() }) else null,
                    surOk = { ok -> a.valider(ok) }), largeurDp)
            }
            return a.dialog
        }
        lateinit var dialog: AlertDialog
        dialog = AppDialog.afficher(ctx, construire(
            surAnnule = { dialog.dismiss() },
            surRetour = null,
            surOk = { ok -> if (ok()) dialog.dismiss() }), largeurDp)
        return dialog
    }

    private fun dp(ctx: Context, v: Float) = AppDialog.dp(ctx, v)

    private fun bouton(ctx: Context, @DrawableRes icone: Int): ImageView = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 76f), dp(ctx, 76f))
        setBackgroundResource(R.drawable.bg_dialog_tile)
        val p = dp(ctx, 20f)
        setPadding(p, p, p, p)
        setImageResource(icone)
        imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** − valeur + ; [pas] reçoit le sens (−1 / +1) et change la valeur, [montrer] la réaffiche. */
    private fun pasAPas(ctx: Context, largeurValeurDp: Float, pas: (Int) -> Unit): Pair<LinearLayout, TextView> {
        val rangee = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val moins = bouton(ctx, R.drawable.ic_hvac_minus)
        val plus = bouton(ctx, R.drawable.ic_hvac_plus)
        val valeur = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, largeurValeurDp), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            paint.isFakeBoldText = true
            maxLines = 1
        }
        AppDialog.repeter(moins) { pas(-1) }
        AppDialog.repeter(plus) { pas(1) }
        rangee.addView(moins)
        rangee.addView(valeur)
        rangee.addView(plus)
        return rangee to valeur
    }

    private fun legende(ctx: Context, texte: CharSequence): TextView = TextView(ctx).apply {
        text = texte
        setTextColor(ctx.getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isAllCaps = true
        letterSpacing = 0.06f
        setPadding(0, dp(ctx, 12f), 0, dp(ctx, 6f))
    }

    /**
     * Un nombre entier entre [min] et [max]. [pas] donne la valeur suivante dans un sens (le pas
     * peut grandir avec la valeur : secondes puis minutes) ; [texte] la met en mots, unité comprise.
     */
    fun nombre(
        ctx: Context, titre: CharSequence, sousTitre: CharSequence?, valeur: Int, min: Int, max: Int,
        texte: (Int) -> String, pas: (Int, Int) -> Int = { v, sens -> v + sens }, onOk: (Int) -> Unit,
    ) {
        var v = valeur.coerceIn(min, max)
        fenetre(ctx, titre, sousTitre, LARGEUR_DP) { corps ->
            lateinit var affichage: TextView
            val (rangee, t) = pasAPas(ctx, 300f) { sens ->
                v = pas(v, sens).coerceIn(min, max)
                affichage.text = texte(v)
            }
            affichage = t
            affichage.text = texte(v)
            corps.addView(rangee)
            return@fenetre { onOk(v); true }
        }
    }

    private fun hhmm(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    /** Une heure de la journée, en minutes depuis minuit ; les heures et les minutes à part. */
    private fun horloge(ctx: Context, corps: LinearLayout, depart: Int, onChange: (Int) -> Unit) {
        var m = depart.coerceIn(0, 24 * 60 - 1)
        val rangee = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lateinit var heures: TextView
        lateinit var minutes: TextView
        fun montrer() {
            heures.text = String.format(Locale.US, "%02d", m / 60)
            minutes.text = String.format(Locale.US, "%02d", m % 60)
            onChange(m)
        }
        val (h, th) = pasAPas(ctx, 110f) { sens -> m = ((m / 60 + sens + 24) % 24) * 60 + m % 60; montrer() }
        val (mn, tm) = pasAPas(ctx, 110f) { sens -> m = (m / 60) * 60 + (m % 60 + sens + 60) % 60; montrer() }
        heures = th
        minutes = tm
        val deuxPoints = TextView(ctx).apply {
            text = ":"
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setPadding(dp(ctx, 18f), 0, dp(ctx, 18f), 0)
        }
        rangee.addView(h)
        rangee.addView(deuxPoints)
        rangee.addView(mn)
        corps.addView(rangee)
        montrer()
    }

    fun heure(ctx: Context, titre: CharSequence, minute: Int, onOk: (Int) -> Unit) {
        var m = minute
        fenetre(ctx, titre, null, LARGEUR_DP) { corps ->
            horloge(ctx, corps, minute) { m = it }
            return@fenetre { onOk(m); true }
        }
    }

    /** Une plage horaire ; elle peut passer minuit (22:00 → 6:00). */
    fun plage(ctx: Context, titre: CharSequence, de: Int, a: Int, onOk: (Int, Int) -> Unit) {
        var d = de
        var f = a
        fenetre(ctx, titre, ctx.getString(R.string.rule_range_hint), LARGEUR_DP) { corps ->
            corps.addView(legende(ctx, ctx.getString(R.string.rule_range_from)))
            horloge(ctx, corps, de) { d = it }
            corps.addView(legende(ctx, ctx.getString(R.string.rule_range_to)))
            horloge(ctx, corps, a) { f = it }
            return@fenetre { onOk(d, f); true }
        }
    }

    /**
     * Une période de l'année, de [de] à [a] (mois × 100 + jour) ; elle peut passer l'an (1er décembre
     * → 28 février). Jour et mois à part, chacun entre − et + ; [nom] écrit « 1 déc. ».
     */
    fun periode(ctx: Context, titre: CharSequence, de: Int, a: Int, nom: (Int) -> String, onOk: (Int, Int) -> Unit) {
        var d = de
        var f = a
        fenetre(ctx, titre, null, LARGEUR_DP) { corps ->
            fun ligne(depart: Int, maj: (Int) -> Unit) {
                var v = depart
                val rangee = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                lateinit var aff: TextView
                fun montrer() { aff.text = nom(v); maj(v) }
                fun jours(m: Int) = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
                val (jour, tj) = pasAPas(ctx, 60f) { sens ->
                    val m = v / 100
                    v = m * 100 + ((v % 100 - 1 + sens + jours(m)) % jours(m) + 1)
                    montrer()
                }
                val (mois, tm) = pasAPas(ctx, 60f) { sens ->
                    val m = ((v / 100 - 1 + sens + 12) % 12) + 1
                    v = m * 100 + minOf(v % 100, jours(m))
                    montrer()
                }
                tj.visibility = View.GONE
                tm.visibility = View.GONE
                aff = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextColor(ctx.getColor(R.color.text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                    paint.isFakeBoldText = true
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 220f), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                // − jour + | date | − mois +
                rangee.addView(jour)
                rangee.addView(aff)
                rangee.addView(mois)
                corps.addView(rangee)
                montrer()
            }
            corps.addView(legende(ctx, ctx.getString(R.string.rule_range_from)))
            ligne(de) { d = it }
            corps.addView(legende(ctx, ctx.getString(R.string.rule_range_to)))
            ligne(a) { f = it }
            corps.addView(legende(ctx, ctx.getString(R.string.rule_season_hint)))
            return@fenetre { onOk(d, f); true }
        }
    }

    /** Des jours, 1 = lundi … 7 = dimanche ; au moins un. */
    fun jours(ctx: Context, titre: CharSequence, choisis: List<Int>, nom: (Int) -> String, onOk: (List<Int>) -> Unit) {
        val actifs = choisis.filter { it in 1..7 }.toMutableSet()
        fenetre(ctx, titre, null, LARGEUR_LARGE_DP) { corps ->
            val rangee = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            val cases = (1..7).map { j ->
                TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 76f), 1f).also { if (j > 1) it.marginStart = dp(ctx, 8f) }
                    gravity = Gravity.CENTER
                    text = nom(j)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    paint.isFakeBoldText = true
                    maxLines = 1
                }
            }
            fun peindre() = cases.forEachIndexed { i, c ->
                val on = (i + 1) in actifs
                OverlayKit.fond(c,
                    ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn),
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border),
                    dp(ctx, if (on) 2f else 1f), dp(ctx, 12f).toFloat())
                c.setTextColor(ctx.getColor(if (on) R.color.text_primary else R.color.text_secondary))
            }
            cases.forEachIndexed { i, c ->
                c.setOnClickListener {
                    if (!actifs.remove(i + 1)) actifs += i + 1
                    peindre()
                }
                rangee.addView(c)
            }
            peindre()
            corps.addView(rangee)
            return@fenetre {
                if (actifs.isEmpty()) false else { onOk(actifs.sorted()); true }
            }
        }
    }

    /** Un texte d'une ligne ; vide refusé. */
    fun texte(ctx: Context, titre: CharSequence, valeur: String, onOk: (String) -> Unit) {
        lateinit var champ: EditText
        val dialog = fenetre(ctx, titre, null, LARGEUR_LARGE_DP) { corps ->
            champ = EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setText(valeur)
                setSelection(text.length)
                setTextColor(ctx.getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                maxLines = 1
                filters = arrayOf(android.text.InputFilter.LengthFilter(120))
            }
            corps.addView(champ)
            return@fenetre {
                val t = champ.text.toString().trim()
                if (t.isEmpty()) false else { onOk(t); true }
            }
        }
        // La carte de la fenêtre : le corps y est posé directement.
        dialog?.let { AppDialog.saisie(it, champ.parent.parent as View) }
    }
}
