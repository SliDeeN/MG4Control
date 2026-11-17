package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.QuickSettings
import com.mg4.control.model.QuickTile
import com.mg4.control.service.OverlayKit
import com.mg4.control.service.QuickTileViews
import com.mg4.control.service.QuickTiles
import java.util.concurrent.Executors

/**
 * Page Raccourcis › Réglages rapides (issue #197, maquette du 2026-11-05) : à gauche les tuiles du
 * pop-up dans l'ordre (glisser ⋮⋮ pour déplacer, « − » pour retirer), à droite le catalogue par
 * catégories, dessous l'aperçu du pop-up avec les valeurs lues sur la voiture. Tout s'enregistre
 * aussitôt, comme les boutons flottants.
 */
class QuickSettingsPage {

    private var page: LinearLayout? = null
    private var tuiles: MutableList<QuickTile> = mutableListOf()
    private var categorie = QuickTiles.Cat.ADAS
    private val valeurs = HashMap<QuickTile, Int?>()
    private val lecteur = Executors.newSingleThreadExecutor { r -> Thread(r, "mg4-quick-page") }

    private var compteur: TextView? = null
    private var liste: RecyclerView? = null
    private var adaptateur: Adaptateur? = null
    private var pastilles: LinearLayout? = null
    private var catalogue: LinearLayout? = null
    private var apercu: LinearLayout? = null

    private fun dp(ctx: Context, v: Float) = OverlayKit.dp(ctx, v)

    fun bind(container: LinearLayout) {
        page = container
        val ctx = container.context
        tuiles = QuickTiles.tiles(ctx).toMutableList()
        container.removeAllViews()

        val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        // Carte « Dans la fenêtre ».
        val mienne = carte(ctx, R.string.quick_card_mine).also { (c, droite) ->
            compteur = droite
            c.addView(aide(ctx, R.string.quick_hint_mine))
            val rv = ListeEntiere(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), 0)
                clipToPadding = false
            }
            val a = Adaptateur()
            rv.adapter = a
            adaptateur = a
            glisser.attachToRecyclerView(rv)
            liste = rv
            c.addView(rv)
        }.first
        rangee.addView(mienne, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).also { it.marginEnd = dp(ctx, 14f) })

        // Carte « Ajouter une tuile ».
        val ajout = carte(ctx, R.string.quick_card_add, droiteRes = R.string.quick_by_firmware).first
        val defil = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        pastilles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), 0) }
        defil.addView(pastilles)
        ajout.addView(defil)
        catalogue = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), 0) }
        ajout.addView(catalogue)
        ajout.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), 0)
            addView(bouton(ctx, ctx.getString(R.string.quick_defaults)) {
                tuiles = QuickSettings.DEFAULT.filter { QuickTiles.disponible(it) }.toMutableList()
                enregistrer(ctx, "tuiles par défaut")
            })
        })
        rangee.addView(ajout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        container.addView(rangee)

        // Aperçu.
        val (cartePrev, _) = carte(ctx, R.string.quick_card_preview)
        apercu = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), 0) }
        cartePrev.addView(apercu)
        container.addView(cartePrev, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(ctx, 14f); it.marginEnd = dp(ctx, 5f) })
        peindre()
    }

    /** La page s'affiche : liste relue (elle a pu changer ailleurs) et valeurs lues sur la voiture. */
    fun refresh() {
        val ctx = page?.context ?: return
        tuiles = QuickTiles.tiles(ctx).toMutableList()
        peindre()
        val aLire = QuickTile.entries.filter { QuickTiles.disponible(it) }
        lecteur.execute {
            val lus = aLire.associateWith { QuickTiles.lire(it) }
            page?.post { valeurs.clear(); valeurs.putAll(lus); peindreApercu() }
        }
    }

    private fun enregistrer(ctx: Context, quoi: String) {
        QuickTiles.save(ctx, tuiles)
        AppLogger.i("MG4_QUICK", "Réglages rapides ($quoi) : ${tuiles.joinToString { it.name }}")
        peindre()
    }

    // ── Dessin ────────────────────────────────────────────────────────────────

    private fun peindre() {
        val ctx = page?.context ?: return
        compteur?.text = "${tuiles.size} / ${QuickSettings.MAX_TILES}"
        @Suppress("NotifyDataSetChanged")
        adaptateur?.notifyDataSetChanged()
        peindreCatalogue(ctx)
        peindreApercu()
    }

    private fun peindreCatalogue(ctx: Context) {
        val p = pastilles ?: return
        val c = catalogue ?: return
        val dispo = QuickTile.entries.filter { QuickTiles.disponible(it) }
        val cats = QuickTiles.Cat.entries.filter { cat -> dispo.any { QuickTiles.cat(it) == cat } }
        if (categorie !in cats) categorie = cats.firstOrNull() ?: categorie
        p.removeAllViews()
        cats.forEach { cat ->
            val on = cat == categorie
            p.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 18f), 0, dp(ctx, 18f), 0)
                OverlayKit.fond(this, ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn),
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border), dp(ctx, 1f), dp(ctx, 26f).toFloat())
                // Une RESSOURCE de couleur : [texte] la résout lui-même. Lui passer la couleur déjà
                // résolue la faisait chercher comme ressource → plantage de l'onglet (2026-11-06).
                val couleurRes = if (on) R.color.dash_accent else R.color.text_primary
                addView(ImageView(ctx).apply {
                    setImageResource(cat.icone)
                    imageTintList = ColorStateList.valueOf(ctx.getColor(couleurRes))
                }, LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)))
                addView(texte(ctx, ctx.getString(cat.libelle), 18f, couleurRes).apply { setPadding(dp(ctx, 8f), 0, 0, 0) })
                setOnClickListener { categorie = cat; peindreCatalogue(ctx) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 52f)).also { it.marginEnd = dp(ctx, 8f) })
        }
        c.removeAllViews()
        val plein = tuiles.size >= QuickSettings.MAX_TILES
        dispo.filter { QuickTiles.cat(it) == categorie }.chunked(3).forEachIndexed { i, ligne ->
            val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            repeat(3) { col ->
                val t = ligne.getOrNull(col)
                val vue: View = if (t == null) View(ctx) else {
                    val deja = t in tuiles
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(ctx, 14f), 0, dp(ctx, 8f), 0)
                        OverlayKit.fond(this, ctx.getColor(R.color.dash_btn), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
                        alpha = if (deja || plein) 0.45f else 1f
                        addView(texte(ctx, ctx.getString(QuickTiles.nom(t)), 18f, R.color.text_primary, lignes = 2),
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        if (QuickTiles.aLArret(t)) addView(etiquette(ctx))
                        addView(texte(ctx, if (deja) "✓" else "+", 22f, R.color.dash_accent, gras = true).apply {
                            gravity = Gravity.CENTER
                            if (!deja) OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), rayon = dp(ctx, 12f).toFloat())
                        }, LinearLayout.LayoutParams(dp(ctx, 40f), dp(ctx, 40f)).also { it.marginStart = dp(ctx, 8f) })
                        setOnClickListener {
                            if (deja || plein) return@setOnClickListener
                            tuiles = QuickSettings.ajouter(tuiles, t).toMutableList()
                            enregistrer(ctx, "ajout ${t.name}")
                        }
                    }
                }
                rangee.addView(vue, LinearLayout.LayoutParams(0, dp(ctx, 64f), 1f).also { if (col > 0) it.marginStart = dp(ctx, 10f) })
            }
            c.addView(rangee, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { if (i > 0) it.topMargin = dp(ctx, 10f) })
        }
    }

    private fun peindreApercu() {
        val ctx = page?.context ?: return
        val a = apercu ?: return
        a.removeAllViews()
        val cases: List<QuickTile?> = tuiles + (if (tuiles.size < QuickSettings.MAX_TILES) listOf(null) else emptyList())
        cases.chunked(4).forEachIndexed { i, ligne ->
            val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            repeat(4) { col ->
                val v: View = when {
                    col >= ligne.size -> View(ctx)
                    ligne[col] == null -> QuickTileViews.ajout(ctx) {}
                    else -> {
                        val t = ligne[col]!!
                        val val_ = valeurs[t]?.let { v -> QuickTiles.options(t).firstOrNull { it.valeur == v } }
                            ?.let { QuickTiles.libelle(ctx, t, it) } ?: "—"
                        QuickTileViews.tuile(ctx, t, val_, style = QuickTileViews.style(ctx, t, valeurs[t]))
                    }
                }
                rangee.addView(v, LinearLayout.LayoutParams(0, dp(ctx, 104f), 1f).also { if (col > 0) it.marginStart = dp(ctx, 10f) })
            }
            a.addView(rangee, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { if (i > 0) it.topMargin = dp(ctx, 10f) })
        }
    }

    // ── Petites vues ──────────────────────────────────────────────────────────

    /** Carte de la page, comme les autres de l'onglet : titre en capitales, trait, contenu. Rend (carte, texte de droite). */
    private fun carte(ctx: Context, titre: Int, droiteRes: Int? = null): Pair<LinearLayout, TextView> {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(0, 0, 0, dp(ctx, 12f))
        }
        val droite = texte(ctx, droiteRes?.let { ctx.getString(it) } ?: "", 18f, R.color.text_secondary)
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 8f))
            addView(texte(ctx, ctx.getString(titre), 19f, R.color.dash_text_lo, gras = true).apply {
                isAllCaps = true
                letterSpacing = 0.10f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(droite)
        })
        c.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.dash_border)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f)))
        return c to droite
    }

    private fun aide(ctx: Context, res: Int) = texte(ctx, ctx.getString(res), 18f, R.color.text_secondary, lignes = 3).apply {
        setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), 0)
    }

    private fun texte(ctx: Context, t: String, sp: Float, @androidx.annotation.ColorRes couleur: Int, gras: Boolean = false, lignes: Int = 1) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ctx.getColor(couleur))
        maxLines = lignes
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (gras) setTypeface(typeface, Typeface.BOLD)
    }

    private fun etiquette(ctx: Context) = texte(ctx, ctx.getString(R.string.quick_stopped_only), 14f, R.color.dash_warn).apply {
        setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), dp(ctx, 2f))
        OverlayKit.fond(this, ctx.getColor(R.color.dash_warn_dim), rayon = dp(ctx, 9f).toFloat())
    }

    private fun bouton(ctx: Context, t: String, onClick: () -> Unit) = texte(ctx, t, 19f, R.color.text_secondary).apply {
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 24f), 0, dp(ctx, 24f), 0)
        OverlayKit.fond(this, ctx.getColor(R.color.bg_button), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
        minHeight = dp(ctx, 64f)
        setOnClickListener { onClick() }
    }

    // ── Valeurs proposées ─────────────────────────────────────────────────────

    /**
     * Au-delà de deux valeurs, une puce par valeur : allumée = proposée dans le pop-up. Deux au
     * moins ; à deux, la tuile bascule d'un toucher (variante B du 2026-11-06).
     */
    private fun peindreValeurs(h: Ligne, t: QuickTile) {
        val ctx = h.vue.context
        val toutes = QuickTiles.options(t)
        h.puces.removeAllViews()
        if (toutes.size <= 2) { h.valeurs.visibility = View.GONE; return }
        h.valeurs.visibility = View.VISIBLE
        val gardees = QuickTiles.proposees(ctx, t).map { it.valeur }
        h.mode.text = if (gardees.size == 2) ctx.getString(R.string.quick_values_toggle) else ""
        toutes.forEach { o ->
            val on = o.valeur in gardees
            h.puces.addView(texte(ctx, QuickTiles.libelle(ctx, t, o), 17f, if (on) R.color.dash_accent else R.color.text_secondary, gras = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 18f), 0, dp(ctx, 18f), 0)
                OverlayKit.fond(this, ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_card),
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border), dp(ctx, 1f), dp(ctx, 24f).toFloat())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 48f))
                setOnClickListener {
                    QuickTiles.basculerValeur(ctx, t, o.valeur)
                    AppLogger.i("MG4_QUICK", "Réglages rapides — ${t.name} : valeurs proposées ${QuickTiles.proposees(ctx, t).joinToString { "${it.valeur}" }}")
                    peindreValeurs(h, t)
                }
            })
        }
    }

    // ── Liste réordonnable ────────────────────────────────────────────────────

    /**
     * Liste qui affiche TOUTES ses rangées dans la page qui défile (retour 2026-11-06 : seules 4 des
     * 8 tuiles s'affichaient, jusqu'au premier glisser). Depuis Android 6, un ScrollView passe à
     * ses enfants une hauteur « libre » qui porte quand même la hauteur de l'écran ; RecyclerView
     * s'en sert comme limite et ne pose que les rangées qui y tiennent. Les pastilles de valeurs
     * ont grandi les rangées au-delà. Une hauteur libre à zéro lui fait tout poser.
     */
    private class ListeEntiere(ctx: Context) : RecyclerView(ctx) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED)
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else heightSpec
            super.onMeasure(widthSpec, h)
        }
    }

    private val glisser = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var avant: List<QuickTile>? = null
        override fun isLongPressDragEnabled() = false
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, cible: RecyclerView.ViewHolder): Boolean {
            val de = vh.bindingAdapterPosition
            val vers = cible.bindingAdapterPosition
            tuiles = QuickSettings.deplacer(tuiles, de, vers).toMutableList()
            adaptateur?.notifyItemMoved(de, vers)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                avant = tuiles.toList()
                vh.itemView.alpha = 0.85f
            }
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            val ctx = rv.context
            if (avant != null && avant != tuiles) rv.post { enregistrer(ctx, "ordre") }
            avant = null
        }
    })

    private inner class Ligne(val vue: LinearLayout, val poignee: View, val rang: TextView, val icone: ImageView,
                              val nom: TextView, val arret: View, val retirer: View,
                              val valeurs: LinearLayout, val puces: FlowRow, val mode: TextView) : RecyclerView.ViewHolder(vue)

    private inner class Adaptateur : RecyclerView.Adapter<Ligne>() {
        override fun getItemCount() = tuiles.size

        @Suppress("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Ligne {
            val ctx = parent.context
            // Bloc de la tuile : sa rangée (poignée, nom, −) puis, au-delà de deux valeurs, celles proposées.
            val bloc = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                OverlayKit.fond(this, ctx.getColor(R.color.dash_btn), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = dp(ctx, 8f) }
            }
            val rangee = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4f), 0, dp(ctx, 6f), 0)
            }
            bloc.addView(rangee, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 64f)))
            val poignee = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_indicator)
                imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.dash_text_lo))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f))
            }
            rangee.addView(poignee, LinearLayout.LayoutParams(dp(ctx, 46f), LinearLayout.LayoutParams.MATCH_PARENT))
            val rang = texte(ctx, "", 17f, R.color.dash_accent, gras = true).apply {
                gravity = Gravity.CENTER
                OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), rayon = dp(ctx, 10f).toFloat())
            }
            rangee.addView(rang, LinearLayout.LayoutParams(dp(ctx, 34f), dp(ctx, 34f)))
            val icone = ImageView(ctx).apply { imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_secondary)) }
            rangee.addView(icone, LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f)).also { it.marginStart = dp(ctx, 14f) })
            val nom = texte(ctx, "", 19f, R.color.text_primary)
            rangee.addView(nom, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(ctx, 14f) })
            val arret = etiquette(ctx)
            rangee.addView(arret, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = dp(ctx, 10f) })
            val retirer = texte(ctx, "−", 24f, R.color.text_secondary).apply {
                gravity = Gravity.CENTER
                OverlayKit.fond(this, ctx.getColor(R.color.dash_card), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
            }
            rangee.addView(retirer, LinearLayout.LayoutParams(dp(ctx, 52f), dp(ctx, 52f)))
            val valeurs = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 94f), 0, dp(ctx, 14f), dp(ctx, 12f))
            }
            val tete = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            tete.addView(texte(ctx, ctx.getString(R.string.quick_values_label), 16f, R.color.text_secondary),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val mode = texte(ctx, "", 16f, R.color.dash_accent)
            tete.addView(mode)
            valeurs.addView(tete)
            val puces = FlowRow(ctx)
            valeurs.addView(puces, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(ctx, 8f) })
            bloc.addView(valeurs)
            val ligne = Ligne(bloc, poignee, rang, icone, nom, arret, retirer, valeurs, puces, mode)
            // Le glisser part de la poignée seulement : le reste de la rangée garde ses touchers.
            poignee.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) glisser.startDrag(ligne)
                false
            }
            return ligne
        }

        override fun onBindViewHolder(h: Ligne, position: Int) {
            val ctx = h.vue.context
            val t = tuiles[position]
            h.rang.text = "${position + 1}"
            h.icone.setImageResource(QuickTiles.cat(t).icone)
            h.nom.text = ctx.getString(QuickTiles.nom(t))
            h.arret.visibility = if (QuickTiles.aLArret(t)) View.VISIBLE else View.GONE
            peindreValeurs(h, t)
            h.retirer.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i !in tuiles.indices) return@setOnClickListener
                val retiree = tuiles.removeAt(i)
                enregistrer(ctx, "retrait ${retiree.name}")
            }
        }
    }
}
