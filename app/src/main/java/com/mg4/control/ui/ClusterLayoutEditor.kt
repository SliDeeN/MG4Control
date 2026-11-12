package com.mg4.control.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterBlock
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.DrivingProfile
import com.mg4.control.service.OverlayKit
import com.mg4.control.ui.widget.ClusterBlocks
import com.mg4.control.ui.widget.ClusterGaugeView
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.DistanceSetting

/**
 * Personnalisation avancée de l'écran conducteur (issue #229, 2026-11-12, maquette validée) : un
 * popup qui prend la plus grande part de l'écran. À gauche, l'aperçu animé du carré du combiné : on
 * touche un bloc ou la jauge pour le régler, on le presse longuement pour le déplacer — un bloc
 * s'échange avec la case où on le dépose, la jauge se pose en haut, au milieu ou en bas. À droite,
 * les réglages de l'élément choisi.
 *
 * Rien n'est enregistré avant « Enregistrer » : [surEnregistre] reçoit la disposition et le style.
 *
 * @param depart     réglages de départ (ceux du premier profil sélectionné dans la carte)
 * @param profil     profil nommé dans l'aperçu
 * @param onglet     onglet montré dans l'aperçu de la carte : sa jauge, au départ
 */
class ClusterLayoutEditor(
    private val ctx: Context,
    private val depart: ClusterConfig,
    private val profil: DrivingProfile?,
    private val sousTitre: CharSequence,
    onglet: ClusterTab,
    private val surEnregistre: (ClusterLayout, String?) -> Unit,
) {
    private val racine = LayoutInflater.from(ctx).inflate(R.layout.dialog_cluster_layout, null)
    private val panneau = racine.findViewById<LinearLayout>(R.id.panel_cluster_layout)
    private val cadre = racine.findViewById<FrameLayout>(R.id.frame_cluster_layout_preview)
    private val apercu = ClusterSettingsPreview(cadre, racine.findViewById(R.id.tv_cluster_layout_preview_none))
    private val mots = AndroidRuleWords(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    private var disposition: ClusterLayout = ClusterModuleSettings.layout(depart)
    private var style: String? = depart.style
    private var module: ClusterModule =
        ClusterModuleSettings.moduleFor(depart, onglet) ?: ClusterModuleSettings.moduleFor(depart, ClusterTab.NAVIGATION)
            ?: ClusterModule.CONSUMPTION
    /** Élément réglé à droite : une case (0 à 3) ou [ClusterGaugeView.JAUGE]. */
    private var selection: Int = ClusterGaugeView.JAUGE

    private fun dp(v: Float) = OverlayKit.dp(ctx, v)

    fun ouvrir() {
        racine.findViewById<TextView>(R.id.tv_cluster_layout_subtitle).text = sousTitre
        apercu.reglages = depart
        apercu.profilMontre = profil
        apercu.moduleForce = module
        apercu.disposition = disposition
        apercu.demarrer()
        brancherToucher()
        racine.findViewById<View>(R.id.btn_cluster_layout_reset).setOnClickListener {
            disposition = ClusterLayouts.defaults(depart.copy(layout = null))
            selection = ClusterGaugeView.JAUGE
            maj()
        }
        racine.findViewById<View>(R.id.btn_cluster_layout_cancel).setOnClickListener { dialog?.dismiss() }
        racine.findViewById<View>(R.id.btn_cluster_layout_save).setOnClickListener {
            AppLogger.i("MG4_SETTINGS", "Écran conducteur, personnalisation avancée enregistrée : $disposition, style $style")
            surEnregistre(disposition, style)
            dialog?.dismiss()
        }
        maj()
        dialog = AppDialog.afficher(ctx, racine, LARGEUR_DP).also { it.setOnDismissListener { apercu.arreter() } }
    }

    private fun maj() {
        apercu.disposition = disposition
        apercu.reglages = depart.copy(style = style, layout = disposition)
        apercu.jauge.edition = (apercu.jauge.edition ?: ClusterGaugeView.Edition()).copy(selection = selection)
        panneau()
    }

    // ── Aperçu : toucher pour choisir, appui long pour déplacer ─────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun brancherToucher() {
        val seuil = ViewConfiguration.get(ctx).scaledTouchSlop
        var departX = 0f
        var departY = 0f
        var cible: Int? = null
        var glisse = false
        var annule = false
        val appuiLong = Runnable {
            val c = cible ?: return@Runnable
            if (c != ClusterGaugeView.JAUGE && ClusterLayouts.slots(disposition)[c] == null) return@Runnable
            glisse = true
            cadre.parent?.requestDisallowInterceptTouchEvent(true)
            cadre.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val (x, y) = carre(departX, departY)
            apercu.jauge.edition = ClusterGaugeView.Edition(selection = selection, glisse = c, doigtX = x, doigtY = y,
                cible = if (c == ClusterGaugeView.JAUGE) null else c,
                cibleJauge = if (c == ClusterGaugeView.JAUGE) ClusterLayouts.gaugeDrop(y) else null)
        }
        cadre.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    departX = e.x; departY = e.y
                    glisse = false; annule = false
                    val (x, y) = carre(e.x, e.y)
                    cible = ClusterLayouts.cellAt(disposition, x, y)
                        ?: ClusterGaugeView.JAUGE.takeIf { ClusterLayouts.geometry(disposition, true).gauge.contains(x, y) }
                    handler.postDelayed(appuiLong, APPUI_LONG_MS)
                    cadre.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!glisse) {
                        if (kotlin.math.hypot(e.x - departX, e.y - departY) > seuil) {
                            annule = true
                            handler.removeCallbacks(appuiLong)
                        }
                    } else {
                        val (x, y) = carre(e.x, e.y)
                        val ed = apercu.jauge.edition ?: ClusterGaugeView.Edition()
                        apercu.jauge.edition = if (ed.glisse == ClusterGaugeView.JAUGE)
                            ed.copy(doigtX = x, doigtY = y, cibleJauge = ClusterLayouts.gaugeDrop(y))
                        else ed.copy(doigtX = x, doigtY = y, cible = ClusterLayouts.cellAt(disposition, x, y))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(appuiLong)
                    val ed = apercu.jauge.edition
                    if (glisse && ed != null && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (ed.glisse == ClusterGaugeView.JAUGE) {
                            ed.cibleJauge?.let { disposition = disposition.copy(gauge = it) }
                            selection = ClusterGaugeView.JAUGE
                        } else {
                            val de = ed.glisse
                            val vers = ed.cible
                            if (de != null && vers != null && vers != de) {
                                disposition = ClusterLayouts.swap(disposition, de, vers)
                                selection = vers
                            }
                        }
                    } else if (!annule && e.actionMasked == MotionEvent.ACTION_UP) {
                        cible?.let { selection = it }
                    }
                    glisse = false
                    apercu.jauge.edition = ClusterGaugeView.Edition(selection = selection)
                    maj()
                }
            }
            true
        }
    }

    /** Point de l'aperçu, en px de la vue, dans le carré de 260 du combiné. */
    private fun carre(x: Float, y: Float): Pair<Float, Float> {
        val e = minOf(cadre.width, cadre.height) / ClusterLayouts.SIDE
        return if (e <= 0f) 0f to 0f else x / e to y / e
    }

    // ── Panneau de droite ───────────────────────────────────────────────────

    private fun panneau() {
        panneau.removeAllViews()
        if (selection == ClusterGaugeView.JAUGE) panneauJauge() else panneauBloc(selection)
    }

    private fun panneauJauge() {
        titre(ctx.getString(R.string.cluster_layout_gauge))
        rangee(R.string.cluster_layout_gauge_preview, listOf(
            ClusterModule.CONSUMPTION to R.string.settings_cluster_consumption,
            ClusterModule.POWER to R.string.settings_cluster_power,
        ).map { (m, t) -> Option(ctx.getString(t), module == m) { module = m; apercu.moduleForce = m; maj() } })
        note(R.string.cluster_layout_gauge_preview_note)
        rangee(R.string.settings_cluster_row_style, listOf(
            ClusterGaugeStyle.DYNAMIC to R.string.settings_cluster_style_dynamic,
            ClusterGaugeStyle.DISCREET to R.string.settings_cluster_style_discreet,
        ).map { (st, t) -> Option(ctx.getString(t), ClusterGaugeStyle.fromKey(style) == st) { style = st.key; maj() } })
        val pos = ClusterLayouts.gaugePosition(disposition)
        rangee(R.string.cluster_layout_position, listOf(
            ClusterLayouts.GAUGE_TOP to R.string.cluster_layout_position_top,
            ClusterLayouts.GAUGE_MIDDLE to R.string.cluster_layout_position_middle,
            ClusterLayouts.GAUGE_BOTTOM to R.string.cluster_layout_position_bottom,
        ).map { (p, t) -> Option(ctx.getString(t), pos == p) { disposition = disposition.copy(gauge = p); maj() } })
        val ep = disposition.thickness ?: ClusterLayouts.NORMAL
        rangee(R.string.cluster_layout_thickness, listOf(
            ClusterLayouts.THIN to R.string.cluster_layout_thickness_thin,
            ClusterLayouts.NORMAL to R.string.cluster_layout_thickness_normal,
            ClusterLayouts.THICK to R.string.cluster_layout_thickness_thick,
        ).map { (v, t) -> Option(ctx.getString(t), ep == v) {
            disposition = disposition.copy(thickness = v.takeIf { it != ClusterLayouts.NORMAL }); maj()
        } })
        etiquette(R.string.cluster_layout_color_consumption)
        couleur(disposition.consumptionColor, { ctx.getColor(R.color.dash_warn) }) { disposition = disposition.copy(consumptionColor = it) }
        etiquette(R.string.cluster_layout_color_regen)
        couleur(disposition.regenColor, { ctx.getColor(R.color.cluster_accent) }) { disposition = disposition.copy(regenColor = it) }
        interrupteur(R.string.cluster_layout_average_cursor,
            ClusterLayouts.averageCursor(disposition, ClusterGaugeStyle.fromKey(style))) {
            disposition = disposition.copy(averageCursor = it)
        }
        rangee(R.string.cluster_layout_power_unit, listOf(null to "kW", ClusterInfo.UNIT_HP to ctx.getString(R.string.cluster_unit_hp))
            .map { (u, t) -> Option(t, disposition.powerUnit == u) { disposition = disposition.copy(powerUnit = u); maj() } })
        interrupteur(R.string.cluster_layout_consumption_kw, disposition.consumptionKw == true) {
            disposition = disposition.copy(consumptionKw = it.takeIf { v -> v })
        }
        interrupteur(R.string.cluster_layout_power_consumption, ClusterLayouts.powerConsumption(disposition)) {
            disposition = disposition.copy(powerConsumption = it.takeIf { v -> !v })
        }
    }

    private fun panneauBloc(slot: Int) {
        titre(ctx.getString(R.string.cluster_layout_slot_fmt, slot + 1))
        val b = ClusterLayouts.slots(disposition)[slot]
        etiquette(R.string.cluster_layout_info)
        bouton(b?.info?.let { ClusterBlocks.nom(ctx, it, mots) } ?: ctx.getString(R.string.cluster_layout_info_none)) {
            choisirInfo(slot, b)
        }
        if (b == null) {
            note(R.string.cluster_layout_empty_note)
            return
        }
        fun change(nouveau: ClusterBlock) { disposition = ClusterLayouts.withSlot(disposition, slot, nouveau); maj() }
        val st = ClusterInfo.style(b)
        rangee(R.string.cluster_layout_display, ClusterInfo.styles(b.info).map { s ->
            Option(ctx.getString(when (s) {
                ClusterInfo.STYLE_PILL -> R.string.cluster_layout_style_pill
                ClusterInfo.STYLE_VALUE -> R.string.floating_info_style_value
                ClusterInfo.STYLE_ICON -> R.string.floating_info_style_icon
                else -> R.string.floating_info_style_label
            }), st == s) { change(b.copy(style = s)) }
        })
        val taille = ClusterInfo.size(b)
        rangee(R.string.cluster_layout_size, listOf(ClusterInfo.SIZE_S, ClusterInfo.SIZE_M, ClusterInfo.SIZE_L).map { t ->
            Option(t, taille == t) { change(b.copy(size = t)) }
        })
        val unites = ClusterInfo.units(b.info)
        if (unites.isNotEmpty()) {
            val u = ClusterInfo.unit(b)
            val general = DistanceSetting.unit(ctx)
            rangee(R.string.cluster_layout_unit, unites.map { k ->
                Option(nomUnite(k, ClusterInfo.distance(b, general)), u == k) { change(b.copy(unit = k)) }
            })
        }
        if (ClusterInfo.hasDistance(b.info)) {
            val d = DistanceUnit.entries.firstOrNull { it.key == b.distance }
            rangee(R.string.settings_distance_unit, listOf(
                Option(ctx.getString(R.string.cluster_layout_distance_auto), d == null) { change(b.copy(distance = null)) },
                Option(ctx.getString(R.string.cluster_distance_km), d == DistanceUnit.KM) { change(b.copy(distance = DistanceUnit.KM.key)) },
                Option(ctx.getString(R.string.cluster_distance_miles), d == DistanceUnit.MILES) { change(b.copy(distance = DistanceUnit.MILES.key)) },
            ))
        }
        if (slot % 2 == 0) {
            val voisine = ClusterLayouts.slots(disposition)[slot + 1]
            if (voisine == null) {
                interrupteur(R.string.cluster_layout_wide, b.wide == true) { change(b.copy(wide = it.takeIf { v -> v })) }
            } else {
                note(ctx.getString(R.string.cluster_layout_wide_busy, slot + 2))
            }
        }
        bouton(ctx.getString(R.string.cluster_layout_clear)) {
            disposition = ClusterLayouts.withSlot(disposition, slot, null); maj()
        }
    }

    private fun nomUnite(k: String, d: DistanceUnit): String = when (k) {
        ClusterInfo.UNIT_KWH -> "kWh"
        ClusterInfo.UNIT_WH -> "Wh"
        ClusterInfo.UNIT_KW -> "kW"
        ClusterInfo.UNIT_HP -> ctx.getString(R.string.cluster_unit_hp)
        else -> AverageUnit.entries.firstOrNull { it.key == k }?.labelFor(d) ?: k
    }

    private fun choisirInfo(slot: Int, avant: ClusterBlock?) {
        val cles = ClusterInfo.choices(InfoIcons::readable)
        val choix = listOf(AppDialog.Choix(ctx.getString(R.string.cluster_layout_info_none))) +
            cles.map { AppDialog.Choix(ClusterBlocks.nom(ctx, it, mots), ctx.getDrawable(InfoIcons.of(it))?.mutate()?.apply {
                setTint(ctx.getColor(R.color.text_secondary))
            }) }
        AppDialog.choisir(ctx, titre = ctx.getString(R.string.floating_info_pick_title), choix = choix,
            annuler = ctx.getString(R.string.profile_cancel),
            choisi = avant?.info?.let { cles.indexOf(it) + 1 } ?: 0) { rang ->
            val nouveau = if (rang == 0) null else cles[rang - 1].let { k ->
                if (avant?.info == k) avant else ClusterInfo.newBlock(k, wide = avant?.wide)
            }
            disposition = ClusterLayouts.withSlot(disposition, slot, nouveau)
            maj()
        }
    }

    // ── Briques du panneau ───────────────────────────────────────────────────

    private class Option(val texte: String, val choisie: Boolean, val surClic: () -> Unit)

    private fun titre(t: String) {
        panneau.addView(TextView(ctx).apply {
            text = t
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun etiquette(res: Int) {
        panneau.addView(TextView(ctx).apply {
            setText(res)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(14f) })
    }

    private fun note(res: Int) = note(ctx.getString(res))

    private fun note(t: String) {
        panneau.addView(TextView(ctx).apply {
            text = t
            setTextColor(ctx.getColor(R.color.dash_text_lo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(6f) })
    }

    private fun materiel(texte: String) = MaterialButton(ctx).apply {
        text = texte
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        insetTop = 0; insetBottom = 0
        cornerRadius = dp(14f)
        strokeWidth = dp(1f)
        maxLines = 1
    }

    private fun rangee(res: Int, options: List<Option>) {
        etiquette(res)
        val ligne = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            ligne.addView(materiel(o.texte).apply {
                ProfileChips.peindre(this, o.choisie)
                setOnClickListener { o.surClic() }
            }, LinearLayout.LayoutParams(0, dp(52f), 1f).also { if (i > 0) it.marginStart = dp(8f) })
        }
        panneau.addView(ligne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(6f) })
    }

    private fun bouton(texte: String, surClic: () -> Unit) {
        panneau.addView(materiel(texte).apply {
            ProfileChips.peindre(this, false)
            setOnClickListener { surClic() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).also { it.topMargin = dp(6f) })
    }

    private fun interrupteur(res: Int, coche: Boolean, surChange: (Boolean) -> Unit) {
        panneau.addView(Switch(ctx).apply {
            setText(res)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isChecked = coche
            // Un même id regonflé en boucle se recopie à la restauration : pas de sauvegarde d'état.
            isSaveEnabled = false
            gravity = Gravity.CENTER_VERTICAL
            setOnCheckedChangeListener { _, v -> surChange(v); maj() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).also { it.topMargin = dp(8f) })
    }

    private fun couleur(actuelle: Int?, defaut: () -> Int, surChoix: (Int?) -> Unit) {
        val conteneur = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panneau.addView(conteneur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(6f) })
        CouleurRangee(ctx, conteneur, defaut) { c ->
            surChoix(c)
            // Pas de maj() : le panneau se referait sous le doigt pendant la saisie du code.
            apercu.disposition = disposition
            apercu.reglages = depart.copy(style = style, layout = disposition)
        }.peindre(actuelle)
    }

    private companion object {
        const val LARGEUR_DP = 1440f
        const val APPUI_LONG_MS = 400L
    }
}
