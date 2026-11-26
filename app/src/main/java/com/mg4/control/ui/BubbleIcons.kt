package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import android.app.AlertDialog
import com.mg4.control.R
import com.mg4.control.model.BubbleIcon
import com.mg4.control.model.CustomKind
import com.mg4.control.model.CustomShortcuts
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.ProjectionKind
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.CustomShortcutSettings

/**
 * Icônes des bulles flottantes (2026-11-17) — le côté Android : icônes d'applis, symboles
 * ([BubbleSymbols]) et la fenêtre de choix (catégories à gauche, recherche, grille ; maquette
 * validée le 2026-11-17). Les règles sont dans [BubbleIcon].
 */
object BubbleIcons {

    /** Une icône prête à poser ; [tint] : `false` pour l'icône d'une appli (ses couleurs d'origine). */
    class Icone(val drawable: Drawable, val tint: Boolean)

    /** Applis que lance [b] et qui sont installées (la première sert par défaut). */
    fun apps(ctx: Context, b: FloatingButton): List<String> {
        val perso = if (b.action == com.mg4.control.shortcut.ShortcutAction.OPEN_CUSTOM_APP.name) b.customId
            else CustomShortcutSettings.get(ctx, b.customId)?.takeIf { CustomShortcuts.kindOf(it) in APPLIS }?.packageName
        return BubbleIcon.apps(b.action, perso, ctx.packageName, ProjectionKind.entries.map { it.packageName })
            .filter { installe(ctx, it) }
    }

    private val APPLIS = setOf(CustomKind.APP, CustomKind.APP_SHORTCUT, CustomKind.FUNCTION)

    private fun installe(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** L'icône d'une référence ([BubbleIcon.APP_PREFIX] / [BubbleIcon.SYM_PREFIX]) ; `null` si introuvable. */
    fun of(ctx: Context, ref: String?): Icone? {
        BubbleIcon.appPackage(ref)?.let { pkg ->
            return runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { Icone(it.mutate(), tint = false) }
        }
        val sym = BubbleSymbols.byName(BubbleIcon.symbolName(ref)) ?: return null
        return runCatching { ctx.getDrawable(sym.res) }.getOrNull()?.let { Icone(it.mutate(), tint = true) }
    }

    /**
     * L'icône que montre [b], ou `null` (aucune, ou l'appli a disparu : la bulle montre alors son
     * titre). Une information sans icône choisie garde la sienne ([InfoIcons]).
     */
    fun forBubble(ctx: Context, b: FloatingButton): Icone? {
        if (!BubbleIcon.showsIcon(b)) return null
        val ref = BubbleIcon.resolve(b, apps(ctx, b))
            ?: return runCatching { ctx.getDrawable(InfoIcons.of(b.info)) }.getOrNull()?.let { Icone(it.mutate(), tint = true) }
        return of(ctx, ref)
    }

    // ── Fenêtre de choix ────────────────────────────────────────────────────────────────────

    private const val LARGEUR_DP = 1240f
    private const val HAUTEUR_GRILLE_DP = 420f
    private const val TUILE_DP = 72f
    private const val COLONNES = 10
    private const val ECART_DP = 10f

    private class Cat(val libelle: String, @DrawableRes val icone: Int, val refs: List<String>)

    /**
     * Choisir l'icône de [b] : « Application » (les applis que lance la bulle, pas pour une
     * information), « Récentes » (les icônes des autres bulles), puis les symboles par catégorie.
     * [actuelle] est marquée ; [surChoix] reçoit la référence choisie.
     */
    fun choisir(ctx: Context, b: FloatingButton, actuelle: String?, recentes: List<String>, surChoix: (String) -> Unit) {
        fenetre(ctx, if (BubbleIcon.isIcon(b)) apps(ctx, b) else emptyList(), actuelle,
            recentes.filter { BubbleIcon.isIcon(b) || BubbleIcon.symbolName(it) != null }, surChoix)
    }

    /**
     * Choisir un symbole seul (2026-11-26) — cases « Icône » de l'écran conducteur, qui dessine tout
     * en gris : pas d'icônes d'applis. « Récentes » = les symboles des autres cases.
     */
    fun choisirSymbole(ctx: Context, actuelle: String?, recentes: List<String>, surChoix: (String) -> Unit) =
        fenetre(ctx, emptyList(), actuelle, recentes.filter { BubbleIcon.symbolName(it) != null }, surChoix)

    private fun fenetre(ctx: Context, apps: List<String>, actuelle: String?, recentes: List<String>, surChoix: (String) -> Unit) {
        fun s(id: Int) = ctx.getString(id)
        val symboles = BubbleSymbols.CATEGORIES.associate { c -> c.key to c.symbols.map { BubbleIcon.symbol(it.name) } }
        val cats = buildList {
            apps.takeIf { it.isNotEmpty() }
                ?.let { add(Cat(s(R.string.floating_icon_cat_app), R.drawable.ic_cat_screen, it.map(BubbleIcon::app))) }
            recentes.distinct()
                .takeIf { it.isNotEmpty() }?.let { add(Cat(s(R.string.rule_cat_recent), R.drawable.ic_cat_clock, it)) }
            add(Cat(s(R.string.rule_cat_car), R.drawable.ic_cat_car, symboles["car"].orEmpty()))
            add(Cat(s(R.string.rule_cat_climate), R.drawable.ic_cat_snow, symboles["climate"].orEmpty()))
            add(Cat(s(R.string.rule_cat_battery), R.drawable.ic_cat_battery, symboles["battery"].orEmpty()))
            add(Cat(s(R.string.rule_cat_media), R.drawable.ic_cat_music, symboles["media"].orEmpty()))
            add(Cat(s(R.string.rule_cat_places), R.drawable.ic_cat_pin, symboles["places"].orEmpty()))
            add(Cat(s(R.string.floating_icon_cat_misc), R.drawable.ic_cat_star, symboles["misc"].orEmpty()))
        }
        // Mots cherchés : le nom du symbole (anglais : « ac unit », « music note »…) et sa catégorie.
        val mots = cats.flatMap { c -> c.refs.map { it to c.libelle } }.groupBy({ it.first }, { it.second })
            .mapValues { (ref, libelles) ->
                sansAccents((listOf(BubbleIcon.symbolName(ref)?.replace('_', ' ')
                    ?: BubbleIcon.appPackage(ref)?.let { nomAppli(ctx, it) }.orEmpty()) + libelles).joinToString(" "))
            }
        var dialog: AlertDialog? = null
        val vue = vue(ctx, cats, mots, actuelle, { dialog }, surAnnule = { dialog?.dismiss() }) { ref -> dialog?.dismiss(); surChoix(ref) }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
    }

    private fun nomAppli(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun sansAccents(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun vue(
        ctx: Context, cats: List<Cat>, mots: Map<String, String>, actuelle: String?,
        fenetre: () -> android.app.Dialog?, surAnnule: () -> Unit, surChoix: (String) -> Unit,
    ): View {
        fun px(v: Float) = AppDialog.dp(ctx, v)
        val accent = ctx.getColor(R.color.dash_accent)
        val racine = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_hvac_modern_card)
            setPadding(px(22f), px(20f), px(22f), px(20f))
        }
        // ── En-tête : pictogramme, titre, recherche, Annuler ──
        val haut = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        haut.addView(FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_dialog_icon)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_cat_star)
                imageTintList = ColorStateList.valueOf(accent)
            }, FrameLayout.LayoutParams(px(28f), px(28f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(px(52f), px(52f)))
        haut.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.floating_icon_pick_title)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.text_primary))
            setPadding(px(14f), 0, px(14f), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val lignes = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val defil = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(lignes) }
        val colonneCats = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var courante = cats.indexOfFirst { actuelle != null && actuelle in it.refs }.takeIf { it >= 0 } ?: 0
        var filtre = ""
        lateinit var champ: EditText

        fun tuile(ref: String): View {
            val choisie = ref == actuelle
            return FrameLayout(ctx).apply {
                OverlayKit.fond(this, ctx.getColor(if (choisie) R.color.dash_accent_dim else R.color.dash_btn),
                    if (choisie) accent else ctx.getColor(R.color.dash_border), px(if (choisie) 2f else 1f), rayon = px(14f).toFloat())
                val icone = of(ctx, ref)
                addView(ImageView(ctx).apply {
                    icone?.let { setImageDrawable(it.drawable) }
                    if (icone?.tint == true) imageTintList = ColorStateList.valueOf(if (choisie) accent else ctx.getColor(R.color.text_primary))
                }, FrameLayout.LayoutParams(px(if (icone?.tint == false) 44f else 34f), px(if (icone?.tint == false) 44f else 34f), Gravity.CENTER))
                contentDescription = BubbleIcon.symbolName(ref)?.replace('_', ' ') ?: BubbleIcon.appPackage(ref)
                setOnClickListener { surChoix(ref) }
            }
        }

        fun dessiner() {
            val cle = sansAccents(filtre.trim())
            fun trouve(ref: String) = cle.split(' ').filter { it.isNotEmpty() }.all { mots[ref].orEmpty().contains(it) }
            val refs = if (cle.isNotEmpty()) cats.flatMap { it.refs }.distinct().filter(::trouve) else cats.getOrNull(courante)?.refs.orEmpty()
            lignes.removeAllViews()
            if (refs.isEmpty()) lignes.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.dialog_search_none)
                textSize = 18f
                setTextColor(ctx.getColor(R.color.text_secondary))
                setPadding(0, px(12f), 0, px(12f))
            })
            refs.chunked(COLONNES).forEachIndexed { i, ligne ->
                val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                repeat(COLONNES) { c ->
                    val ref = ligne.getOrNull(c)
                    rangee.addView(if (ref != null) tuile(ref) else View(ctx),
                        LinearLayout.LayoutParams(0, px(TUILE_DP), 1f).also { if (c > 0) it.marginStart = px(ECART_DP) })
                }
                lignes.addView(rangee, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { if (i > 0) it.topMargin = px(ECART_DP) })
            }
            defil.scrollTo(0, 0)
            colonneCats.removeAllViews()
            cats.forEachIndexed { i, c ->
                val n = if (cle.isEmpty()) c.refs.size else c.refs.count(::trouve)
                val on = cle.isEmpty() && i == courante
                colonneCats.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(14f), 0, px(14f), 0)
                    alpha = if (cle.isNotEmpty() && n == 0) 0.4f else 1f
                    if (on) OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), rayon = px(14f).toFloat())
                    addView(ImageView(ctx).apply {
                        setImageResource(c.icone)
                        imageTintList = ColorStateList.valueOf(if (on) accent else ctx.getColor(R.color.text_secondary))
                    }, LinearLayout.LayoutParams(px(24f), px(24f)))
                    addView(TextView(ctx).apply {
                        text = c.libelle
                        textSize = 18f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(if (on) accent else ctx.getColor(R.color.text_primary))
                        setPadding(px(12f), 0, px(8f), 0)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(ctx).apply {
                        text = n.toString()
                        textSize = 15f
                        setTextColor(ctx.getColor(R.color.text_secondary))
                    })
                    setOnClickListener {
                        courante = i
                        if (filtre.isNotEmpty()) champ.setText("") else dessiner()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56f)).also { if (i > 0) it.topMargin = px(6f) })
            }
        }

        champ = AppDialog.champRecherche(ctx, fenetre) { filtre = it; dessiner() } as EditText
        haut.addView(champ, LinearLayout.LayoutParams(px(380f), px(56f)).also { it.marginStart = px(14f) })
        haut.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.profile_cancel)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dialog_tile)
            setPadding(px(22f), 0, px(22f), 0)
            setOnClickListener { surAnnule() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(56f)).also { it.marginStart = px(14f) })
        racine.addView(haut)

        val corps = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        corps.addView(ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(colonneCats)
        }, LinearLayout.LayoutParams(px(300f), LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = px(18f) })
        corps.addView(defil, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        racine.addView(corps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(HAUTEUR_GRILLE_DP)).also { it.topMargin = px(16f) })
        dessiner()
        return racine
    }
}
