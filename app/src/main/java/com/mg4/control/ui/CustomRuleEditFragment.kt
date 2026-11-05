package com.mg4.control.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mg4.control.R
import com.mg4.control.api.ExternalApi
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.automation.CustomRules
import com.mg4.control.bluetooth.BluetoothProfileManager
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.CustomRule
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.model.RuleAction
import com.mg4.control.model.RuleCondition
import com.mg4.control.model.RuleEvent
import com.mg4.control.model.RuleOp
import com.mg4.control.model.RuleRun
import com.mg4.control.model.RuleSignal
import com.mg4.control.model.RuleText
import com.mg4.control.model.RuleKeys
import com.mg4.control.model.RuleLogic
import com.mg4.control.model.RuleTrigger
import com.mg4.control.model.SunEvent
import com.mg4.control.automation.AndroidControls
import com.mg4.control.automation.RulePlaces
import com.mg4.control.automation.RuleVariables
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.profile.ProfileManager
import com.mg4.control.ui.widget.SegmentSwitch
import java.text.DateFormat
import java.util.Date

/**
 * Éditeur de Mes automatisations (issue #201, étape 2) : une colonne façon Home Assistant —
 * **Quand** (déclencheurs), **Et si** (arbre de conditions, groupes Toutes / Au moins une / Aucune),
 * **Alors** (actions, blocs Si / Sinon et Répéter imbriqués), **Options**.
 *
 * L'automatisation affichée est une valeur ([regle]) : chaque modification en construit une
 * nouvelle par des « mises à jour » (`put`) passées de bloc en bloc, puis redessine tout. Rien
 * n'est écrit avant « Enregistrer » ; « Tester » lance la version affichée.
 *
 * Ouvert depuis la page Mes automatisations : [ARG_RULE_ID] pour en modifier une, ou
 * [ARG_NEW_RULE] (JSON) pour une nouvelle, vide ou venue d'un modèle.
 */
class CustomRuleEditFragment : Fragment(R.layout.fragment_custom_rule_edit) {

    companion object {
        const val ARG_RULE_ID = "rule_id"
        const val ARG_NEW_RULE = "new_rule"
        private const val STATE_RULE = "rule"
        private const val STATE_INITIAL = "initial"

        /** Imbrication proposée dans l'éditeur : groupes et blocs au plus à ce niveau. */
        private const val PROFONDEUR_MAX = 3
        private const val LARGEUR_SEGMENTS_DP = 460f
    }

    private lateinit var regle: CustomRule
    /** La version à l'ouverture : quitter sans enregistrer ne demande confirmation que si elle a changé. */
    private lateinit var initiale: CustomRule
    private lateinit var mots: AndroidRuleWords
    private lateinit var texte: RuleText
    private var corps: LinearLayout? = null
    private var defilement: ScrollView? = null

    private val ctx: Context get() = requireContext()
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mots = AndroidRuleWords(ctx)
        texte = RuleText(mots)
        corps = view.findViewById(R.id.rule_edit_body)
        defilement = view.findViewById(R.id.rule_edit_scroll)

        fun decode(json: String?) = json?.let { CustomRuleStore.decode("[$it]").firstOrNull() }
        val enregistree = arguments?.getString(ARG_RULE_ID)?.let { id -> CustomRuleStore.all(ctx).firstOrNull { it.id == id } }
        val ouverte = enregistree ?: decode(arguments?.getString(ARG_NEW_RULE)) ?: CustomRule(name = s(R.string.rule_name_default_fmt, 1))
        regle = decode(savedInstanceState?.getString(STATE_RULE)) ?: ouverte
        initiale = decode(savedInstanceState?.getString(STATE_INITIAL)) ?: ouverte

        view.findViewById<EditText>(R.id.rule_edit_name).apply {
            setText(regle.name)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { regle = regle.copy(name = e?.toString().orEmpty()) }
            })
        }
        view.findViewById<Switch>(R.id.rule_edit_enabled).apply {
            isChecked = regle.enabled
            setOnCheckedChangeListener { _, on -> regle = regle.copy(enabled = on) }
        }
        view.findViewById<View>(R.id.rule_edit_save).setOnClickListener { enregistrer() }
        view.findViewById<View>(R.id.rule_edit_cancel).setOnClickListener { quitter() }
        view.findViewById<View>(R.id.rule_edit_test).setOnClickListener { tester() }
        view.findViewById<View>(R.id.rule_edit_journal).setOnClickListener { journal() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = quitter()
        })
        dessiner()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::regle.isInitialized) return
        outState.putString(STATE_RULE, CustomRuleStore.encode(listOf(regle)).removeSurrounding("[", "]"))
        outState.putString(STATE_INITIAL, CustomRuleStore.encode(listOf(initiale)).removeSurrounding("[", "]"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        corps = null
        defilement = null
    }

    // ── Pied de page ─────────────────────────────────────────────────────────

    private fun nomFinal(): String = regle.name.trim().ifEmpty { s(R.string.rule_name_default_fmt, CustomRuleStore.all(ctx).size + 1) }

    private fun enregistrer() {
        val manque = when {
            regle.triggers.isEmpty() -> R.string.rule_need_trigger
            regle.actions.isEmpty() -> R.string.rule_need_action
            else -> null
        }
        if (manque != null) {
            AppDialog.confirmer(ctx, titre = s(manque), ton = AppDialog.Ton.ALERTE, oui = s(R.string.nav_close))
            return
        }
        val finale = regle.copy(name = nomFinal())
        if (!CustomRuleStore.upsert(ctx, finale)) {
            Toast.makeText(ctx, s(R.string.rule_max_reached, CustomRuleStore.MAX_RULES), Toast.LENGTH_LONG).show()
            return
        }
        CustomRules.refresh(ctx)
        findNavController().popBackStack()
    }

    private fun quitter() {
        if (regle == initiale) {
            findNavController().popBackStack()
            return
        }
        AppDialog.confirmer(ctx,
            titre = s(R.string.rule_discard_title),
            ton = AppDialog.Ton.ALERTE,
            oui = s(R.string.rule_discard),
            non = s(R.string.rule_keep_editing),
            onOui = { findNavController().popBackStack() })
    }

    private fun tester() {
        if (regle.actions.isEmpty()) {
            AppDialog.confirmer(ctx, titre = s(R.string.rule_need_action), ton = AppDialog.Ton.ALERTE, oui = s(R.string.nav_close))
            return
        }
        val essai = regle.copy(name = nomFinal())
        CustomRules.test(ctx, essai)
        Toast.makeText(ctx, s(R.string.rule_test_started, essai.name), Toast.LENGTH_SHORT).show()
    }

    private fun journal() {
        val run = CustomRules.lastRun(ctx, regle.id)
        val message = if (run == null) s(R.string.rule_never_run) else {
            val quand = run.atMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                ?: s(R.string.rule_run_time_unknown)
            val entete = s(R.string.rule_last_run_fmt, quand, run.done, run.failed)
            val lignes = run.journal.orEmpty()
            if (lignes.isEmpty()) entete else entete + "\n\n" + lignes.joinToString("\n")
        }
        AppDialog.confirmer(ctx,
            titre = s(R.string.rule_journal_title_fmt, nomFinal()),
            message = message,
            icone = R.drawable.ic_dialog_list,
            oui = s(R.string.nav_close))
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

    /** Remplace l'automatisation affichée et redessine, en gardant la position dans la page. */
    private fun maj(f: (CustomRule) -> CustomRule) {
        regle = f(regle)
        dessiner()
    }

    private fun dessiner() {
        val box = corps ?: return
        val y = defilement?.scrollY ?: 0
        box.removeAllViews()

        section(box, R.string.rule_sec_when, R.string.rule_sec_when_hint) { s ->
            if (regle.triggers.isEmpty()) vide(s, R.string.rule_none_triggers)
            val put: (List<RuleTrigger>) -> Unit = { l -> maj { it.copy(triggers = l) } }
            regle.triggers.forEachIndexed { i, t ->
                val editer = { declencheur(t) { n -> put(regle.triggers.remplace(i, n)) } }
                ligne(s, texte.trigger(t), editer) { menu(texte.trigger(t), regle.triggers, i, put, editer) }
            }
            ajouter(s, R.string.rule_add_trigger) { declencheur(null) { n -> put(regle.triggers + n) } }
        }

        section(box, R.string.rule_sec_if, R.string.rule_sec_if_hint) { s ->
            groupe(s, regle.condition, 0, racine = true) { c -> maj { it.copy(condition = c) } }
        }

        section(box, R.string.rule_sec_then, R.string.rule_sec_then_hint) { s ->
            actions(s, regle.actions, 0, R.string.rule_none_actions) { l -> maj { it.copy(actions = l) } }
        }

        section(box, R.string.rule_sec_options, null) { s -> options(s) }

        defilement?.post { defilement?.scrollTo(0, y) }
    }

    private fun dp(v: Float) = AppDialog.dp(ctx, v)

    private fun lp(haut: Float = 0f, debut: Float = 0f) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(haut); it.marginStart = dp(debut) }

    private fun texteVue(t: CharSequence, sp: Float, couleur: Int, gras: Boolean = false) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ctx.getColor(couleur))
        if (gras) paint.isFakeBoldText = true
    }

    private fun section(box: LinearLayout, titre: Int, aide: Int?, remplir: (LinearLayout) -> Unit) {
        val carte = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            val p = dp(18f)
            setPadding(p, p, p, p)
            layoutParams = lp(haut = if (box.childCount == 0) 0f else 14f)
        }
        carte.addView(texteVue(s(titre), 22f, R.color.text_primary, gras = true))
        aide?.let { carte.addView(texteVue(s(it), 18f, R.color.text_secondary).apply { layoutParams = lp(haut = 2f) }) }
        remplir(carte)
        box.addView(carte)
    }

    /** Un bloc imbriqué (groupe, Si, Répéter) : en retrait, sur un fond plus sombre. */
    private fun bloc(box: LinearLayout): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_section_rounded)
        val p = dp(14f)
        setPadding(p, p, p, p)
        layoutParams = lp(haut = 10f, debut = 24f)
        box.addView(this)
    }

    private fun etiquette(box: LinearLayout, t: Int) {
        box.addView(texteVue(s(t), 17f, R.color.text_secondary, gras = true).apply {
            isAllCaps = true
            letterSpacing = 0.08f
            layoutParams = lp(haut = 12f)
        })
    }

    private fun vide(box: LinearLayout, t: Int) {
        box.addView(texteVue(s(t), 18f, R.color.dash_text_lo).apply { layoutParams = lp(haut = 10f) })
    }

    /** Une ligne : son texte (toucher = modifier, ou le menu s'il n'y a rien à modifier) et ⋮. */
    private fun ligne(box: LinearLayout, t: String, editer: (() -> Unit)?, menu: () -> Unit) {
        val rangee = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_tile)
            minimumHeight = dp(64f)
            layoutParams = lp(haut = 8f)
        }
        rangee.addView(texteVue(t, 19f, R.color.text_primary).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(18f), dp(12f), dp(8f), dp(12f))
        })
        rangee.addView(boutonMenu(menu))
        rangee.setOnClickListener { enchainement(editer ?: menu) }
        box.addView(rangee)
    }

    private fun boutonMenu(menu: () -> Unit) = texteVue("⋮", 30f, R.color.text_secondary, gras = true).apply {
        layoutParams = LinearLayout.LayoutParams(dp(64f), dp(64f))
        gravity = Gravity.CENTER
        contentDescription = s(R.string.rule_menu_title)
        setOnClickListener { enchainement(menu) }
    }

    private fun ajouter(box: LinearLayout, t: Int, onClick: () -> Unit) {
        box.addView(texteVue("+  " + s(t), 19f, R.color.dash_accent, gras = true).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56f)).also {
                it.topMargin = dp(10f)
                it.marginEnd = dp(10f)
            }
            gravity = Gravity.CENTER
            setPadding(dp(22f), 0, dp(22f), 0)
            setBackgroundResource(R.drawable.bg_dialog_tile)
            setOnClickListener { enchainement(onClick) }
        })
    }

    /**
     * Un enchaînement de choix (ajouter, modifier, ⋮) : ses fenêtres se succèdent dans une seule,
     * avec Retour à partir de la deuxième étape ([Assistant]).
     */
    private fun enchainement(f: () -> Unit) = Assistant.dans(Assistant(ctx), f)

    private fun rangeeBoutons(box: LinearLayout): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = lp()
        box.addView(this)
    }

    // ── Menu ⋮ ───────────────────────────────────────────────────────────────

    private enum class Geste { EDIT, UP, DOWN, DUPLICATE, DELETE }

    private fun <T> menu(titre: String, liste: List<T>, i: Int, put: (List<T>) -> Unit, editer: (() -> Unit)?) {
        val gestes = buildList {
            if (editer != null) add(Geste.EDIT)
            if (i > 0) add(Geste.UP)
            if (i < liste.lastIndex) add(Geste.DOWN)
            add(Geste.DUPLICATE)
            add(Geste.DELETE)
        }
        AppDialog.choisir(ctx,
            titre = titre,
            icone = R.drawable.ic_dialog_edit,
            choix = gestes.map {
                AppDialog.Choix(s(when (it) {
                    Geste.EDIT -> R.string.rule_menu_edit
                    Geste.UP -> R.string.rule_menu_up
                    Geste.DOWN -> R.string.rule_menu_down
                    Geste.DUPLICATE -> R.string.rule_menu_duplicate
                    Geste.DELETE -> R.string.rule_delete
                }))
            },
            annuler = s(R.string.profile_cancel),
        ) { rang ->
            when (gestes[rang]) {
                Geste.EDIT -> editer?.invoke()
                Geste.UP -> put(liste.toMutableList().also { java.util.Collections.swap(it, i, i - 1) })
                Geste.DOWN -> put(liste.toMutableList().also { java.util.Collections.swap(it, i, i + 1) })
                Geste.DUPLICATE -> put(liste.toMutableList().also { it.add(i + 1, liste[i]) })
                Geste.DELETE -> put(liste.toMutableList().also { it.removeAt(i) })
            }
        }
    }

    private fun <T> List<T>.remplace(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }

    // ── Conditions ───────────────────────────────────────────────────────────

    private fun typeGroupe(t: RuleCondition.Type?) = when (t) {
        RuleCondition.Type.ANY -> 1
        RuleCondition.Type.NOT -> 2
        else -> 0
    }

    private val typesGroupe = listOf(RuleCondition.Type.ALL, RuleCondition.Type.ANY, RuleCondition.Type.NOT)

    /** Le sélecteur Toutes / Au moins une / Aucune d'un groupe. */
    private fun segmentsGroupe(box: LinearLayout, g: RuleCondition, menu: (() -> Unit)?, put: (RuleCondition) -> Unit) {
        val rangee = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(haut = 10f)
        }
        val segments = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_dialog_segments)
            val p = dp(4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(LARGEUR_SEGMENTS_DP), dp(60f))
        }
        AppDialog.segments(segments,
            listOf(s(R.string.rule_group_all), s(R.string.rule_group_any), s(R.string.rule_group_not)),
            typeGroupe(g.type)) { rang -> put(g.copy(type = typesGroupe[rang])) }
        rangee.addView(segments)
        rangee.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        if (menu != null) rangee.addView(boutonMenu(menu))
        box.addView(rangee)
    }

    /**
     * Un groupe et ses conditions. La racine (« Et si ») n'a ni cadre ni menu ; un sous-groupe est
     * un bloc en retrait, avec son propre ⋮ (rendu par la liste qui le contient).
     */
    private fun groupe(
        box: LinearLayout, g: RuleCondition, profondeur: Int, racine: Boolean,
        siVide: Int = R.string.rule_none_conditions, put: (RuleCondition) -> Unit,
    ) {
        val enfants = g.children.orEmpty()
        if (racine) {
            if (enfants.isEmpty()) vide(box, siVide)
            else segmentsGroupe(box, g, null, put)
        }
        val putEnfants: (List<RuleCondition>) -> Unit = { l -> put(g.copy(children = l)) }
        enfants.forEachIndexed { i, c ->
            val titre = texte.condition(c)
            if (c.isGroup) {
                val b = bloc(box)
                segmentsGroupe(b, c, { menu(titre, enfants, i, putEnfants, null) }) { n -> putEnfants(enfants.remplace(i, n)) }
                groupe(b, c, profondeur + 1, racine = false) { n -> putEnfants(enfants.remplace(i, n)) }
            } else {
                val editer = { condition(c) { n -> putEnfants(enfants.remplace(i, n)) } }
                ligne(box, titre, editer) { menu(titre, enfants, i, putEnfants, editer) }
            }
        }
        if (!racine && enfants.isEmpty()) vide(box, R.string.rule_none_in_group)
        val boutons = rangeeBoutons(box)
        ajouter(boutons, R.string.rule_add_condition) { nouvelleCondition(profondeur) { n -> putEnfants(enfants + n) } }
    }

    private fun nouvelleCondition(profondeur: Int, ok: (RuleCondition) -> Unit) {
        val choix = buildList {
            add(R.string.rule_cond_compare to { condition(null, ok) })
            add(R.string.rule_cond_time to { plage(null, ok) })
            add(R.string.rule_cond_days to { jours(null, ok) })
            add(R.string.rule_cond_season to { saison(null, ok) })
            add(R.string.rule_cond_place to { lieu(null, ok) })
            add(R.string.rule_cond_variable to { variable(null, ok) })
            add(R.string.rule_cond_random to { hasard(null, ok) })
            if (profondeur < PROFONDEUR_MAX - 1) {
                add(R.string.rule_cond_group_all to { ok(RuleCondition(RuleCondition.Type.ALL, children = emptyList())) })
                add(R.string.rule_cond_group_any to { ok(RuleCondition(RuleCondition.Type.ANY, children = emptyList())) })
                add(R.string.rule_cond_group_not to { ok(RuleCondition(RuleCondition.Type.NOT, children = emptyList())) })
            }
        }
        liste(R.string.rule_choose_condition, choix)
    }

    /** Modifie une condition simple ; un nœud inconnu repart d'une valeur. */
    private fun condition(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) = when (actuelle?.type) {
        RuleCondition.Type.TIME_RANGE -> plage(actuelle, ok)
        RuleCondition.Type.WEEKDAYS -> jours(actuelle, ok)
        RuleCondition.Type.SEASON -> saison(actuelle, ok)
        RuleCondition.Type.PLACE -> lieu(actuelle, ok)
        RuleCondition.Type.VARIABLE -> variable(actuelle, ok)
        RuleCondition.Type.RANDOM -> hasard(actuelle, ok)
        else -> comparer(actuelle?.takeIf { it.type == RuleCondition.Type.COMPARE }, ok)
    }

    private fun plage(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        RuleValueDialogs.plage(ctx, s(R.string.rule_cond_time), actuelle?.from ?: 7 * 60, actuelle?.to ?: 9 * 60) { de, a ->
            ok(RuleCondition(RuleCondition.Type.TIME_RANGE, from = de, to = a))
        }
    }

    private fun jours(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        RuleValueDialogs.jours(ctx, s(R.string.rule_days_title), actuelle?.days ?: listOf(1, 2, 3, 4, 5), mots::dayName) { j ->
            ok(RuleCondition(RuleCondition.Type.WEEKDAYS, days = j))
        }
    }

    private fun saison(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        RuleValueDialogs.periode(ctx, s(R.string.rule_cond_season), actuelle?.from ?: 1201, actuelle?.to ?: 228, mots::monthDay) { de, a ->
            ok(RuleCondition(RuleCondition.Type.SEASON, from = de, to = a))
        }
    }

    private fun hasard(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        RuleValueDialogs.nombre(ctx, s(R.string.rule_cond_random), s(R.string.rule_random_hint), actuelle?.number?.toInt() ?: 50, 1, 99,
            texte = { "$it %" }) { p -> ok(RuleCondition(RuleCondition.Type.RANDOM, number = p.toDouble())) }
    }

    /** Une variable : son nom (une existante, ou une nouvelle), puis le test. */
    private fun variable(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        choisirVariable(actuelle?.text) { nom ->
            valeurs(nom, listOf(
                s(R.string.rule_var_true) to RuleOp.IS_TRUE, s(R.string.rule_var_false) to RuleOp.IS_FALSE,
                s(R.string.rule_op_above) to RuleOp.ABOVE, s(R.string.rule_op_below) to RuleOp.BELOW,
                s(R.string.rule_op_is) to RuleOp.EQUALS, s(R.string.rule_op_is_not) to RuleOp.NOT_EQUALS,
            ), actuelle?.op) { op ->
                val base = RuleCondition(RuleCondition.Type.VARIABLE, text = nom, op = op)
                when (op) {
                    RuleOp.IS_TRUE, RuleOp.IS_FALSE -> ok(base)
                    else -> RuleValueDialogs.nombre(ctx, nom, null, actuelle?.number?.toInt() ?: 0, -9999, 9999,
                        texte = { "$it" }) { n -> ok(base.copy(number = n.toDouble())) }
                }
            }
        }
    }

    /** Choisir une variable : celles déjà définies, ou une nouvelle (nom saisi). */
    private fun choisirVariable(deja: String?, ok: (String) -> Unit) {
        val connues = RuleVariables.all(ctx).keys.sorted()
        val nouvelle = s(R.string.rule_var_new)
        valeurs(s(R.string.rule_choose_variable), connues.map { it to it } + (nouvelle to ""), deja?.let { RuleLogic.variableName(it) }) { v ->
            if (v.isNotEmpty()) ok(v)
            else RuleValueDialogs.texte(ctx, s(R.string.rule_var_name), "") { ok(RuleLogic.variableName(it)) }
        }
    }

    /** Dans / hors d'un lieu : choisir le lieu (ou enregistrer la position actuelle), puis le sens. */
    private fun lieu(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        choisirLieu(actuelle?.text) { id ->
            valeurs(mots.placeName(id), listOf(s(R.string.rule_place_inside) to RuleOp.IS_TRUE, s(R.string.rule_place_outside) to RuleOp.IS_FALSE),
                actuelle?.op) { op -> ok(RuleCondition(RuleCondition.Type.PLACE, text = id, op = op)) }
        }
    }

    private fun choisirLieu(deja: String?, ok: (String) -> Unit) {
        val lieux = RulePlaces.all(ctx)
        val ici = "+ " + s(R.string.rule_place_here)
        valeurs(s(R.string.rule_choose_place), lieux.map { it.name to it.id } + (ici to ""), deja) { id ->
            if (id.isNotEmpty()) return@valeurs ok(id)
            Toast.makeText(ctx, s(R.string.rule_place_locating), Toast.LENGTH_SHORT).show()
            AndroidControls.locate(ctx) { l ->
                if (!isAdded) return@locate
                if (l == null) return@locate Toast.makeText(ctx, s(R.string.rule_place_no_fix), Toast.LENGTH_LONG).show()
                enregistrerLieu(l, ok)
            }
        }
    }

    /** Nom et rayon du lieu à la position [l], puis enregistrement. */
    private fun enregistrerLieu(l: android.location.Location, ok: (String) -> Unit) {
        RuleValueDialogs.texte(ctx, s(R.string.rule_place_name), "") { nom ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_place_radius), null, 200, 50, 5_000,
                texte = { "$it m" }, pas = { v, sens -> v + sens * (if (sens > 0 && v < 500 || sens < 0 && v <= 500) 50 else 250) }) { r ->
                val p = com.mg4.control.model.RulePlace(name = nom, lat = l.latitude, lng = l.longitude, radius = r)
                RulePlaces.save(ctx, RulePlaces.all(ctx) + p)
                ok(p.id)
            }
        }
    }

    /**
     * Une grille de choix dont chaque tuile lance son action. [rangement] range chaque choix (par son
     * libellé) dans une catégorie de la grande fenêtre ; [recents] nomme la liste pour ses « Récents ».
     */
    private fun liste(titre: Int, choix: List<Pair<Int, () -> Unit>>, choisi: Int = -1,
                      rangement: ((Int) -> Cat)? = null, recents: String? = null) {
        AppDialog.choisir(ctx, titre = s(titre), choix = choix.map { AppDialog.Choix(s(it.first)) },
            annuler = s(R.string.profile_cancel), choisi = choisi,
            categories = rangement?.let { r -> categories(choix.map { r(it.first) }) },
            recents = recents, cles = choix.map { resources.getResourceEntryName(it.first) }) { rang -> choix[rang].second() }
    }

    /** Une grille de valeurs libellées ; [rangement] et [recents] comme pour [liste]. */
    private fun <T> valeurs(titre: CharSequence, choix: List<Pair<String, T>>, choisi: T?,
                            rangement: ((T) -> Cat)? = null, recents: String? = null, ok: (T) -> Unit) {
        if (choix.isEmpty()) return
        AppDialog.choisir(ctx, titre = titre, choix = choix.map { AppDialog.Choix(it.first) },
            annuler = s(R.string.profile_cancel), choisi = choix.indexOfFirst { it.second == choisi },
            categories = rangement?.let { r -> categories(choix.map { r(it.second) }) },
            recents = recents, cles = choix.map { it.second.toString() }) { rang -> ok(choix[rang].second) }
    }

    /**
     * Catégories de la grande fenêtre (maquette B, 2026-11-05). L'ordre de l'énumération est celui
     * de la colonne ; une catégorie sans choix n'apparaît pas.
     */
    private enum class Cat(val libelle: Int, val icone: Int) {
        CAR(R.string.rule_cat_car, R.drawable.ic_cat_car),
        CHARGE(R.string.rule_cat_charge, R.drawable.ic_cat_plug),
        TIME(R.string.rule_cat_time, R.drawable.ic_cat_clock),
        PLACES(R.string.rule_cat_places, R.drawable.ic_cat_pin),
        DRIVING(R.string.rule_cat_driving, R.drawable.ic_cat_steering),
        BATTERY(R.string.rule_cat_battery, R.drawable.ic_cat_battery),
        CLIMATE(R.string.rule_cat_climate, R.drawable.ic_cat_snow),
        COMFORT(R.string.rule_cat_comfort, R.drawable.ic_cat_seat),
        ADAS(R.string.rule_cat_adas, R.drawable.ic_cat_shield),
        MEDIA(R.string.rule_cat_media, R.drawable.ic_cat_music),
        SCREEN_SOUND(R.string.rule_cat_screen_sound, R.drawable.ic_cat_screen),
        SCREEN_APPS(R.string.rule_cat_screen_apps, R.drawable.ic_cat_screen),
        CONNECTIONS(R.string.rule_cat_connections, R.drawable.ic_cat_wifi),
        SHORTCUTS(R.string.rule_cat_shortcuts, R.drawable.ic_cat_bolt),
        FLOW(R.string.rule_cat_flow, R.drawable.ic_cat_flow),
        SYSTEM(R.string.rule_cat_system, R.drawable.ic_cat_system),
        ADVANCED(R.string.rule_cat_advanced, R.drawable.ic_cat_adjust),
    }

    private fun categories(rangs: List<Cat>): List<AppDialog.Categorie> =
        Cat.entries.mapNotNull { c ->
            val r = rangs.indices.filter { rangs[it] == c }
            if (r.isEmpty()) null else AppDialog.Categorie(s(c.libelle), c.icone, r)
        }

    private fun catDeclencheur(res: Int): Cat = when (res) {
        R.string.rule_event_app_start, R.string.rule_event_door, R.string.rule_trg_locked, R.string.rule_trg_unlocked -> Cat.CAR
        R.string.rule_trg_charge_start, R.string.rule_trg_charge_end, R.string.rule_trg_plugged, R.string.rule_trg_unplugged -> Cat.CHARGE
        R.string.rule_trg_time, R.string.rule_trg_every, R.string.rule_trg_sun -> Cat.TIME
        R.string.rule_trg_place, R.string.rule_trg_phone -> Cat.PLACES
        else -> Cat.ADVANCED
    }

    private fun catAction(res: Int): Cat = when (res) {
        R.string.rule_act_set, R.string.rule_act_profile, R.string.rule_act_windows -> Cat.CAR
        R.string.rule_act_media, R.string.rule_act_sound, R.string.rule_act_speak -> Cat.MEDIA
        R.string.rule_act_notify, R.string.rule_act_open, R.string.rule_act_app, R.string.rule_act_navigate -> Cat.SCREEN_APPS
        R.string.rule_act_shortcut, R.string.rule_act_custom, R.string.rule_act_rule -> Cat.SHORTCUTS
        else -> Cat.FLOW
    }

    private fun catSignal(sig: RuleSignal): Cat = when (sig) {
        RuleSignal.READY, RuleSignal.GEAR, RuleSignal.SPEED, RuleSignal.DRIVE_MODE, RuleSignal.REGEN, RuleSignal.ONE_PEDAL,
        RuleSignal.ENERGY_SAVING, RuleSignal.DRIVE_MINUTES -> Cat.DRIVING
        RuleSignal.SOC, RuleSignal.RANGE, RuleSignal.CHARGING, RuleSignal.PLUGGED, RuleSignal.CHARGE_DC, RuleSignal.CHARGE_LIMIT -> Cat.BATTERY
        RuleSignal.OUTSIDE_TEMP, RuleSignal.HVAC_ON, RuleSignal.AC_ON, RuleSignal.HVAC_AUTO, RuleSignal.HVAC_ECO, RuleSignal.HVAC_TEMP,
        RuleSignal.FAN, RuleSignal.RECIRC, RuleSignal.DEFROST_FRONT, RuleSignal.DEFROST_REAR -> Cat.CLIMATE
        RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT, RuleSignal.STEERING_HEAT, RuleSignal.LOCKED, RuleSignal.KEY_AWAY -> Cat.COMFORT
        RuleSignal.AEB, RuleSignal.TSR, RuleSignal.ESC, RuleSignal.DROWSINESS, RuleSignal.OVERSPEED_ALARM -> Cat.ADAS
        RuleSignal.BRIGHTNESS, RuleSignal.VOLUME, RuleSignal.MEDIA_PLAYING, RuleSignal.NIGHT, RuleSignal.SUN_UP -> Cat.SCREEN_SOUND
        RuleSignal.BT_DEVICE, RuleSignal.BT_ON, RuleSignal.WIFI_ON, RuleSignal.WIFI_SSID, RuleSignal.HOTSPOT_ON, RuleSignal.PROJECTION,
        RuleSignal.IN_CALL, RuleSignal.DATA_TODAY_MB, RuleSignal.DATA_MONTH_MB -> Cat.CONNECTIONS
        else -> Cat.SYSTEM
    }

    private fun catReglage(cle: String): Cat = when (cle) {
        ExternalApi.SET_DRIVE_MODE, ExternalApi.SET_REGEN, RuleKeys.ONE_PEDAL, RuleKeys.ENERGY_SAVING -> Cat.DRIVING
        ExternalApi.SET_SEAT_HEAT_LEFT, ExternalApi.SET_SEAT_HEAT_RIGHT, ExternalApi.SET_STEERING_HEAT -> Cat.COMFORT
        RuleKeys.BRIGHTNESS, RuleKeys.VOLUME, RuleKeys.VOLUME_STEP -> Cat.SCREEN_SOUND
        RuleKeys.AEB, RuleKeys.TSR, RuleKeys.ESC, RuleKeys.DROWSINESS, RuleKeys.OVERSPEED_ALARM, RuleKeys.SPEED_LIMIT_TONE,
        RuleKeys.AUTO_HIGH_BEAM -> Cat.ADAS
        RuleKeys.BATTERY_HEAT, RuleKeys.CHARGE_LIMIT -> Cat.BATTERY
        RuleKeys.WIFI, RuleKeys.BLUETOOTH, RuleKeys.HOTSPOT -> Cat.CONNECTIONS
        else -> Cat.CLIMATE
    }

    /** Les signaux proposés, dans un ordre de lecture (conduite, énergie, clim, confort, ADAS, appareil). */
    private fun signaux(): List<RuleSignal> {
        val clim = MG4Hardware.hasClimateControl()
        val climat = setOf(RuleSignal.HVAC_ON, RuleSignal.AC_ON, RuleSignal.HVAC_AUTO, RuleSignal.HVAC_ECO, RuleSignal.HVAC_TEMP,
            RuleSignal.FAN, RuleSignal.RECIRC, RuleSignal.DEFROST_FRONT, RuleSignal.DEFROST_REAR)
        return listOf(
            RuleSignal.READY, RuleSignal.GEAR, RuleSignal.SPEED, RuleSignal.DRIVE_MODE, RuleSignal.REGEN, RuleSignal.ONE_PEDAL,
            RuleSignal.ENERGY_SAVING, RuleSignal.DRIVE_MINUTES,
            RuleSignal.SOC, RuleSignal.RANGE, RuleSignal.CHARGING, RuleSignal.PLUGGED, RuleSignal.CHARGE_DC, RuleSignal.CHARGE_LIMIT,
            RuleSignal.OUTSIDE_TEMP, RuleSignal.NIGHT, RuleSignal.SUN_UP,
            RuleSignal.HVAC_ON, RuleSignal.AC_ON, RuleSignal.HVAC_AUTO, RuleSignal.HVAC_ECO, RuleSignal.HVAC_TEMP, RuleSignal.FAN,
            RuleSignal.RECIRC, RuleSignal.DEFROST_FRONT, RuleSignal.DEFROST_REAR,
            RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT, RuleSignal.STEERING_HEAT,
            RuleSignal.AEB, RuleSignal.TSR, RuleSignal.ESC, RuleSignal.DROWSINESS, RuleSignal.OVERSPEED_ALARM,
            RuleSignal.LOCKED,
            RuleSignal.PROFILE, RuleSignal.BT_DEVICE, RuleSignal.BT_ON, RuleSignal.WIFI_ON, RuleSignal.WIFI_SSID, RuleSignal.HOTSPOT_ON,
            RuleSignal.PROJECTION, RuleSignal.MEDIA_PLAYING, RuleSignal.IN_CALL, RuleSignal.BRIGHTNESS, RuleSignal.VOLUME,
            RuleSignal.DATA_TODAY_MB, RuleSignal.DATA_MONTH_MB, RuleSignal.FIRMWARE,
        ).filter { s ->
            when {
                s in climat -> clim
                s == RuleSignal.ESC || s == RuleSignal.DROWSINESS -> MG4Hardware.hasDrowsinessAndEsc()
                s == RuleSignal.BRIGHTNESS -> MG4Hardware.hasBrightnessControl()
                else -> true
            }
        }
    }

    private class Borne(val min: Int, val max: Int, val defaut: Int, val pas: Int)

    private fun borne(s: RuleSignal) = when (s) {
        RuleSignal.SPEED -> Borne(0, 200, 50, 5)
        RuleSignal.SOC -> Borne(0, 100, 50, 1)
        RuleSignal.RANGE -> Borne(0, 700, 100, 5)
        RuleSignal.OUTSIDE_TEMP -> Borne(-30, 50, 20, 1)
        RuleSignal.HVAC_TEMP -> Borne(16, 30, 21, 1)
        RuleSignal.FAN -> Borne(0, 7, 3, 1)
        RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT -> Borne(0, 3, 0, 1)
        RuleSignal.BRIGHTNESS -> Borne(0, 100, 50, 5)
        RuleSignal.VOLUME -> Borne(0, 40, 10, 1)
        RuleSignal.DRIVE_MINUTES -> Borne(0, 600, 30, 5)
        RuleSignal.DATA_TODAY_MB, RuleSignal.DATA_MONTH_MB -> Borne(0, 50_000, 500, 50)
        RuleSignal.CHARGE_LIMIT -> Borne(40, 100, 80, 10)
        else -> Borne(0, 100, 0, 1)
    }

    /** Les valeurs possibles d'un signal texte ; vide = rien à proposer, `null` = valeur à saisir. */
    private fun choixTexte(sig: RuleSignal): List<Pair<String, String>>? = when (sig) {
        RuleSignal.GEAR -> listOf(RuleSignal.GEAR_PARK, RuleSignal.GEAR_DRIVE, RuleSignal.GEAR_OTHER).map { mots.value(sig, it) to it }
        RuleSignal.PROFILE -> ProfileManager(ctx).getAll().map { it.name to it.id }
        RuleSignal.DRIVE_MODE -> DriveMode.entries.map { mots.driveMode(it) to it.name }
        RuleSignal.REGEN -> RegenLevel.entries.map { mots.regen(it) to it.name }
        RuleSignal.RECIRC -> listOf("INNER", "OUTSIDE", "AUTO").map { mots.value(sig, it) to it }
        RuleSignal.FIRMWARE -> com.mg4.control.util.FirmwareInfo.Gen.entries.map { it.name to it.name }
        RuleSignal.BT_DEVICE -> runCatching { BluetoothProfileManager.getBondedDevices(ctx) }.getOrNull().orEmpty()
            .map { it.name to it.mac.uppercase() }
        RuleSignal.WIFI_SSID -> null
        else -> emptyList()
    }

    /** Une comparaison : le signal, puis comment le comparer, puis la valeur. */
    private fun comparer(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        valeurs(s(R.string.rule_choose_signal), signaux().map { mots.signal(it) to it }, actuelle?.signal,
            rangement = ::catSignal, recents = "sig") { sig ->
            comparerSignal(sig, actuelle?.takeIf { it.signal == sig }, ok)
        }
    }

    private fun comparerSignal(sig: RuleSignal, meme: RuleCondition?, ok: (RuleCondition) -> Unit) {
        fun base(op: RuleOp) = RuleCondition(RuleCondition.Type.COMPARE, signal = sig, op = op)
        when (sig.kind) {
            RuleSignal.Kind.BOOL -> valeurs(mots.signal(sig),
                listOf(s(R.string.segment_yes) to RuleOp.IS_TRUE, s(R.string.segment_no) to RuleOp.IS_FALSE), meme?.op) { op ->
                ok(base(op))
            }
            RuleSignal.Kind.NUMBER -> valeurs(mots.signal(sig),
                listOf(s(R.string.rule_op_above) to RuleOp.ABOVE, s(R.string.rule_op_below) to RuleOp.BELOW), meme?.op) { op ->
                val b = borne(sig)
                RuleValueDialogs.nombre(ctx, mots.signal(sig) + " " + s(if (op == RuleOp.ABOVE) R.string.rule_op_above else R.string.rule_op_below).lowercase(),
                    null, meme?.number?.toInt() ?: b.defaut, b.min, b.max,
                    texte = { "$it" + mots.unit(sig) }, pas = { v, sens -> v + sens * b.pas }) { n ->
                    ok(base(op).copy(number = n.toDouble()))
                }
            }
            RuleSignal.Kind.TEXT, RuleSignal.Kind.TEXT_SET -> {
                val choix = choixTexte(sig)
                if (choix != null && choix.isEmpty()) {
                    Toast.makeText(ctx, s(if (sig == RuleSignal.BT_DEVICE) R.string.rule_no_phones else R.string.rule_no_profiles), Toast.LENGTH_LONG).show()
                    return
                }
                valeurs(mots.signal(sig),
                    listOf(s(R.string.rule_op_is) to RuleOp.EQUALS, s(R.string.rule_op_is_not) to RuleOp.NOT_EQUALS), meme?.op) { op ->
                    if (choix == null) RuleValueDialogs.texte(ctx, mots.signal(sig), meme?.text ?: AndroidControls.wifiSsid(ctx).orEmpty()) { v ->
                        ok(base(op).copy(text = v))
                    }
                    else valeurs(mots.signal(sig), choix, meme?.text) { v -> ok(base(op).copy(text = v)) }
                }
            }
        }
    }

    // ── Déclencheurs ─────────────────────────────────────────────────────────

    private fun quand(sig: RuleSignal, op: RuleOp, texte: String? = null) =
        RuleTrigger(RuleTrigger.Type.BECOMES, condition = RuleCondition(RuleCondition.Type.COMPARE, signal = sig, op = op, text = texte))

    private fun declencheur(actuel: RuleTrigger?, ok: (RuleTrigger) -> Unit) {
        fun evenement(t: RuleTrigger) = valeurs(s(R.string.rule_choose_event),
            RuleEvent.entries.filter { it != RuleEvent.FRONT_DOOR_OPEN || MG4Hardware.hasDoorDetection() }
                .map { mots.event(it) to it }, t.event) { e -> ok(t.copy(type = RuleTrigger.Type.EVENT, event = e)) }
        fun devient(t: RuleTrigger) = condition(t.condition) { c ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_for_title), s(R.string.rule_for_hint), t.forSeconds, 0, RuleRun.MAX_WAIT_S,
                texte = { if (it == 0) s(R.string.rule_for_now) else duree(it) }, pas = ::pasDuree) { sec ->
                ok(t.copy(type = RuleTrigger.Type.BECOMES, condition = c, forSeconds = sec))
            }
        }
        fun change(t: RuleTrigger) = valeurs(s(R.string.rule_choose_signal), signaux().filter { it.kind != RuleSignal.Kind.NUMBER || it in CHANGE_NOMBRES }
            .map { mots.signal(it) to it }, t.condition?.signal, rangement = ::catSignal, recents = "sig") { sig ->
            ok(t.copy(type = RuleTrigger.Type.CHANGES, condition = RuleCondition(RuleCondition.Type.COMPARE, signal = sig)))
        }
        fun horaire(t: RuleTrigger) = RuleValueDialogs.heure(ctx, s(R.string.rule_time_title), t.at ?: 7 * 60) { m ->
            ok(t.copy(type = RuleTrigger.Type.TIME, at = m))
        }
        fun periodique(t: RuleTrigger) = RuleValueDialogs.nombre(ctx, s(R.string.rule_every_title), s(R.string.rule_every_hint), t.every ?: 15, 1, 1_440,
            texte = { if (it < 60) s(R.string.rule_minutes_fmt, it) else duree(it * 60) },
            pas = { v, sens -> v + sens * (if (sens > 0 && v < 15 || sens < 0 && v <= 15) 1 else if (sens > 0 && v < 120 || sens < 0 && v <= 120) 5 else 30) }) { m ->
            ok(t.copy(type = RuleTrigger.Type.PERIODIC, every = m))
        }
        fun soleil(t: RuleTrigger) = valeurs(s(R.string.rule_trg_sun), listOf(s(R.string.rule_sunrise) to SunEvent.SUNRISE, s(R.string.rule_sunset) to SunEvent.SUNSET), t.sun) { e ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_sun_offset), s(R.string.rule_sun_offset_hint), t.offset, -180, 180,
                texte = { if (it == 0) s(R.string.rule_for_now) else (if (it > 0) "+" else "−") + s(R.string.rule_minutes_fmt, kotlin.math.abs(it)) },
                pas = { v, sens -> v + sens * 5 }) { o -> ok(t.copy(type = RuleTrigger.Type.SUN, sun = e, offset = o)) }
        }
        when (actuel?.type) {
            RuleTrigger.Type.EVENT -> evenement(actuel)
            RuleTrigger.Type.BECOMES -> devient(actuel)
            RuleTrigger.Type.TIME -> horaire(actuel)
            RuleTrigger.Type.PERIODIC -> periodique(actuel)
            RuleTrigger.Type.CHANGES -> change(actuel)
            RuleTrigger.Type.SUN -> soleil(actuel)
            RuleTrigger.Type.MANUAL -> {}
            null -> liste(R.string.rule_choose_trigger, buildList {
                add(R.string.rule_event_app_start to { ok(RuleTrigger(RuleTrigger.Type.EVENT, event = RuleEvent.APP_START)) })
                if (MG4Hardware.hasDoorDetection())
                    add(R.string.rule_event_door to { ok(RuleTrigger(RuleTrigger.Type.EVENT, event = RuleEvent.FRONT_DOOR_OPEN)) })
                addAll(listOf(
                R.string.rule_trg_becomes to { devient(RuleTrigger()) },
                R.string.rule_trg_changes to { change(RuleTrigger()) },
                R.string.rule_trg_time to { horaire(RuleTrigger()) },
                R.string.rule_trg_every to { periodique(RuleTrigger()) },
                R.string.rule_trg_sun to { soleil(RuleTrigger()) },
                R.string.rule_trg_manual to { ok(RuleTrigger(RuleTrigger.Type.MANUAL)) },
                R.string.rule_trg_charge_start to { ok(quand(RuleSignal.CHARGING, RuleOp.IS_TRUE)) },
                R.string.rule_trg_charge_end to { ok(quand(RuleSignal.CHARGING, RuleOp.IS_FALSE)) },
                R.string.rule_trg_plugged to { ok(quand(RuleSignal.PLUGGED, RuleOp.IS_TRUE)) },
                R.string.rule_trg_unplugged to { ok(quand(RuleSignal.PLUGGED, RuleOp.IS_FALSE)) },
                R.string.rule_trg_locked to { ok(quand(RuleSignal.LOCKED, RuleOp.IS_TRUE)) },
                R.string.rule_trg_unlocked to { ok(quand(RuleSignal.LOCKED, RuleOp.IS_FALSE)) },
                R.string.rule_trg_phone to { comparerSignal(RuleSignal.BT_DEVICE, null) { c -> ok(RuleTrigger(RuleTrigger.Type.BECOMES, condition = c)) } },
                R.string.rule_trg_place to { lieu(null) { c -> ok(RuleTrigger(RuleTrigger.Type.BECOMES, condition = c)) } },
                ))
            }, rangement = ::catDeclencheur, recents = "trg")
        }
    }

    /** Signaux numériques qu'un déclencheur « change » suit (les autres bougent sans cesse). */
    private val CHANGE_NOMBRES = setOf(RuleSignal.HVAC_TEMP, RuleSignal.FAN, RuleSignal.SEAT_HEAT_LEFT, RuleSignal.SEAT_HEAT_RIGHT,
        RuleSignal.CHARGE_LIMIT, RuleSignal.BRIGHTNESS, RuleSignal.VOLUME)

    /** Une durée lisible : « 45 s », « 2 min », « 2 min 30 s ». */
    private fun duree(sec: Int): String = when {
        sec < 60 -> s(R.string.rule_seconds_fmt, sec)
        sec % 60 == 0 -> s(R.string.rule_minutes_fmt, sec / 60)
        else -> s(R.string.rule_minutes_fmt, sec / 60) + " " + s(R.string.rule_seconds_fmt, sec % 60)
    }

    /** Seconde par seconde jusqu'à une minute, puis par 15 s, puis par minute au-delà de 10 min. */
    private fun pasDuree(v: Int, sens: Int): Int {
        val pas = when {
            sens > 0 && v < 60 || sens < 0 && v <= 60 -> 1
            sens > 0 && v < 600 || sens < 0 && v <= 600 -> 15
            else -> 60
        }
        return v + sens * pas
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun actions(box: LinearLayout, liste: List<RuleAction>, profondeur: Int, siVide: Int?, put: (List<RuleAction>) -> Unit) {
        if (liste.isEmpty() && siVide != null) vide(box, siVide)
        liste.forEachIndexed { i, a ->
            val titre = texte.action(a)
            val remplacer: (RuleAction) -> Unit = { n -> put(liste.remplace(i, n)) }
            when (a.type) {
                RuleAction.Type.IF -> {
                    val b = bloc(box)
                    ligne(b, titre, null) { menu(titre, liste, i, put, null) }
                    etiquette(b, R.string.rule_if_condition)
                    groupe(b, a.condition ?: RuleCondition(RuleCondition.Type.ALL), profondeur + 1, racine = true,
                        siVide = R.string.rule_none_if) { c ->
                        remplacer(a.copy(condition = c))
                    }
                    etiquette(b, R.string.rule_sec_then)
                    actions(b, a.then.orEmpty(), profondeur + 1, R.string.rule_none_in_block) { l -> remplacer(a.copy(then = l)) }
                    etiquette(b, R.string.rule_if_else)
                    actions(b, a.otherwise.orEmpty(), profondeur + 1, R.string.rule_none_in_block) { l -> remplacer(a.copy(otherwise = l)) }
                }
                RuleAction.Type.REPEAT -> {
                    val b = bloc(box)
                    val editer = { action(a, profondeur, remplacer) }
                    ligne(b, titre, editer) { menu(titre, liste, i, put, editer) }
                    actions(b, a.then.orEmpty(), profondeur + 1, R.string.rule_none_in_block) { l -> remplacer(a.copy(then = l)) }
                }
                RuleAction.Type.STOP, null -> ligne(box, titre, null) { menu(titre, liste, i, put, null) }
                else -> {
                    val editer = { action(a, profondeur, remplacer) }
                    ligne(box, titre, editer) { menu(titre, liste, i, put, editer) }
                }
            }
        }
        ajouter(rangeeBoutons(box), R.string.rule_add_action) { action(null, profondeur) { n -> put(liste + n) } }
    }

    /** Raccourcis regroupés sous une entrée à eux dans le choix des actions. */
    private val MEDIA = listOf(ShortcutAction.MEDIA_PLAY_PAUSE, ShortcutAction.MEDIA_NEXT, ShortcutAction.MEDIA_PREVIOUS, ShortcutAction.AUDIO_SOURCE_CYCLE)
    private val VITRES = listOf(ShortcutAction.WINDOWS_CLOSE_ALL, ShortcutAction.WINDOWS_OPEN_ALL)
    private val OUVRIR = listOf(ShortcutAction.HVAC_POPUP, ShortcutAction.CHARGE_POPUP, ShortcutAction.PROFILE_PICKER,
        ShortcutAction.PRE_CLIMATE, ShortcutAction.PROJECTION, ShortcutAction.ANDROID_SETTINGS)

    /** Ajoute ([actuelle] nulle) ou modifie une action. */
    private fun action(actuelle: RuleAction?, profondeur: Int, ok: (RuleAction) -> Unit) {
        val catalogue = ShortcutCatalog.items(ctx).filter { it.action !in CustomRules.INTERDITS }
        fun raccourcis(a: RuleAction, parmi: List<ShortcutAction>?, titre: Int) = valeurs(s(titre),
            catalogue.filter { parmi == null || it.action in parmi }.map { it.label to it.action.name }, a.shortcut) { n ->
            ok(a.copy(type = RuleAction.Type.SHORTCUT, shortcut = n))
        }
        fun profil(a: RuleAction) {
            val p = ProfileManager(ctx).getAll()
            if (p.isEmpty()) return Toast.makeText(ctx, s(R.string.rule_no_profiles), Toast.LENGTH_LONG).show()
            valeurs(s(R.string.rule_choose_profile), p.map { it.name to it.id }, a.profileId) { id ->
                ok(a.copy(type = RuleAction.Type.PROFILE, profileId = id))
            }
        }
        fun attente(a: RuleAction) = RuleValueDialogs.nombre(ctx, s(R.string.rule_wait_title), null, a.seconds ?: 10, 1, RuleRun.MAX_WAIT_S,
            texte = ::duree, pas = ::pasDuree) { sec -> ok(a.copy(type = RuleAction.Type.WAIT, seconds = sec)) }
        fun attenteQue(a: RuleAction) = condition(a.condition) { c ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_wait_until_title), s(R.string.rule_wait_until_hint), a.seconds ?: 60, 1, RuleRun.MAX_WAIT_S,
                texte = ::duree, pas = ::pasDuree) { sec -> ok(a.copy(type = RuleAction.Type.WAIT_UNTIL, condition = c, seconds = sec)) }
        }
        fun message(a: RuleAction) = RuleValueDialogs.texte(ctx, s(R.string.rule_notify_title), a.text.orEmpty()) { t ->
            ok(a.copy(type = RuleAction.Type.NOTIFY, text = t))
        }
        fun question(a: RuleAction) = RuleValueDialogs.texte(ctx, s(R.string.rule_confirm_title), a.text.orEmpty()) { t ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_confirm_delay), null, a.seconds ?: 15, 5, 120, texte = { s(R.string.rule_seconds_fmt, it) }) { sec ->
                valeurs(s(R.string.rule_confirm_no_answer), listOf(s(R.string.rule_confirm_no_answer_stop) to "no", s(R.string.rule_confirm_no_answer_go) to "yes"), a.value) { v ->
                    ok(a.copy(type = RuleAction.Type.CONFIRM, text = t, seconds = sec, value = v))
                }
            }
        }
        fun son(a: RuleAction) {
            val sons = com.mg4.control.automation.SoundAutomationSettings.slots(ctx)
            if (sons.isEmpty()) return Toast.makeText(ctx, s(R.string.rule_no_sounds), Toast.LENGTH_LONG).show()
            valeurs(s(R.string.rule_choose_sound), sons.mapIndexed { i, so -> (so.title?.takeIf { it.isNotBlank() } ?: "${i + 1}") to "$i" }, a.value) { v ->
                ok(a.copy(type = RuleAction.Type.PLAY_SOUND, value = v, text = sons[v.toInt()].title?.takeIf { it.isNotBlank() } ?: "${v.toInt() + 1}"))
            }
        }
        fun appli(a: RuleAction) {
            val pm = ctx.packageManager
            val apps = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.sortedBy { it.first.lowercase() }
            valeurs(s(R.string.shortcuts_pick_app_title), apps, a.text) { p -> ok(a.copy(type = RuleAction.Type.OPEN_APP, text = p)) }
        }
        fun perso(a: RuleAction) = CustomShortcutDialog.choisir(ctx, a.text) { c -> ok(a.copy(type = RuleAction.Type.CUSTOM_SHORTCUT, text = c.id)) }
        fun automatisation(a: RuleAction) = valeurs(s(R.string.rule_act_rule), listOf(
            s(R.string.rule_rule_run) to "run", s(R.string.rule_rule_enable) to "1", s(R.string.rule_rule_disable) to "0"),
            when (a.type) { RuleAction.Type.RUN_RULE -> "run"; RuleAction.Type.RULE_ENABLE -> a.value; else -> null }) { quoi ->
            RuleChooser.choisir(ctx, a.text, sauf = if (quoi == "run") regle.id else null) { r ->
                ok(if (quoi == "run") a.copy(type = RuleAction.Type.RUN_RULE, text = r.id, value = null)
                   else a.copy(type = RuleAction.Type.RULE_ENABLE, text = r.id, value = quoi))
            }
        }
        fun definir(a: RuleAction) = choisirVariable(a.key) { nom ->
            valeurs(nom, listOf(s(R.string.rule_var_true) to "true", s(R.string.rule_var_false) to "false", s(R.string.rule_var_number) to ""), a.value) { v ->
                if (v.isNotEmpty()) ok(a.copy(type = RuleAction.Type.SET_VARIABLE, key = nom, value = v))
                else RuleValueDialogs.nombre(ctx, nom, null, a.value?.toIntOrNull() ?: 0, -9999, 9999, texte = { "$it" }) { n ->
                    ok(a.copy(type = RuleAction.Type.SET_VARIABLE, key = nom, value = "$n"))
                }
            }
        }
        fun parler(a: RuleAction) = RuleValueDialogs.texte(ctx, s(R.string.rule_speak_title), a.text.orEmpty()) { t ->
            ok(a.copy(type = RuleAction.Type.SPEAK, text = t))
        }
        fun naviguer(a: RuleAction) = choisirLieu(a.text) { id -> ok(a.copy(type = RuleAction.Type.NAVIGATE, text = id)) }
        fun repeter(a: RuleAction) = RuleValueDialogs.nombre(ctx, s(R.string.rule_repeat_title), null, a.count ?: 2, 2, RuleRun.MAX_REPEAT,
            texte = { "×$it" }) { n -> ok(a.copy(type = RuleAction.Type.REPEAT, count = n)) }

        when (actuelle?.type) {
            RuleAction.Type.SET -> choisirReglage(actuelle, ok)
            RuleAction.Type.SHORTCUT -> raccourcis(actuelle, null, R.string.rule_choose_shortcut)
            RuleAction.Type.PROFILE -> profil(actuelle)
            RuleAction.Type.WAIT -> attente(actuelle)
            RuleAction.Type.WAIT_UNTIL -> attenteQue(actuelle)
            RuleAction.Type.NOTIFY -> message(actuelle)
            RuleAction.Type.CONFIRM -> question(actuelle)
            RuleAction.Type.PLAY_SOUND -> son(actuelle)
            RuleAction.Type.OPEN_APP -> appli(actuelle)
            RuleAction.Type.CUSTOM_SHORTCUT -> perso(actuelle)
            RuleAction.Type.RULE_ENABLE, RuleAction.Type.RUN_RULE -> automatisation(actuelle)
            RuleAction.Type.SET_VARIABLE -> definir(actuelle)
            RuleAction.Type.SPEAK -> parler(actuelle)
            RuleAction.Type.NAVIGATE -> naviguer(actuelle)
            RuleAction.Type.REPEAT -> repeter(actuelle)
            RuleAction.Type.IF, RuleAction.Type.STOP -> {}
            null -> liste(R.string.rule_choose_action, buildList {
                add(R.string.rule_act_set to { choisirReglage(RuleAction(), ok) })
                add(R.string.rule_act_media to { raccourcis(RuleAction(), MEDIA, R.string.rule_act_media) })
                add(R.string.rule_act_windows to { raccourcis(RuleAction(), VITRES, R.string.rule_act_windows) })
                add(R.string.rule_act_open to { raccourcis(RuleAction(), OUVRIR, R.string.rule_act_open) })
                add(R.string.rule_act_shortcut to { raccourcis(RuleAction(), null, R.string.rule_choose_shortcut) })
                add(R.string.rule_act_profile to { profil(RuleAction()) })
                add(R.string.rule_act_sound to { son(RuleAction()) })
                add(R.string.rule_act_app to { appli(RuleAction()) })
                add(R.string.rule_act_custom to { perso(RuleAction()) })
                add(R.string.rule_act_rule to { automatisation(RuleAction()) })
                add(R.string.rule_act_variable to { definir(RuleAction()) })
                add(R.string.rule_act_wait to { attente(RuleAction()) })
                add(R.string.rule_act_wait_until to { attenteQue(RuleAction()) })
                add(R.string.rule_act_confirm to { question(RuleAction()) })
                add(R.string.rule_act_notify to { message(RuleAction()) })
                add(R.string.rule_act_speak to { parler(RuleAction()) })
                add(R.string.rule_act_navigate to { naviguer(RuleAction()) })
                if (profondeur < PROFONDEUR_MAX - 1) {
                    add(R.string.rule_act_if to {
                        ok(RuleAction(RuleAction.Type.IF, condition = RuleCondition(RuleCondition.Type.ALL, children = emptyList()),
                            then = emptyList(), otherwise = emptyList()))
                    })
                    add(R.string.rule_act_repeat to { repeter(RuleAction(then = emptyList())) })
                }
                add(R.string.rule_act_stop to { ok(RuleAction(RuleAction.Type.STOP)) })
            }, rangement = ::catAction, recents = "act")
        }
    }

    /** Les réglages de la voiture (et d'Android) qu'une action peut écrire, et la façon de choisir leur valeur. */
    private fun choisirReglage(a: RuleAction, ok: (RuleAction) -> Unit) {
        val clim = MG4Hardware.hasClimateControl()
        val cles = buildList {
            add(ExternalApi.SET_DRIVE_MODE)
            add(ExternalApi.SET_REGEN)
            add(RuleKeys.ONE_PEDAL)
            add(RuleKeys.ENERGY_SAVING)
            add(ExternalApi.SET_SEAT_HEAT_LEFT)
            add(ExternalApi.SET_SEAT_HEAT_RIGHT)
            add(ExternalApi.SET_STEERING_HEAT)
            if (clim) {
                add(ExternalApi.SET_HVAC_POWER)
                add(ExternalApi.SET_HVAC_AC)
                add(ExternalApi.SET_HVAC_AUTO)
                add(RuleKeys.HVAC_ECO)
                add(ExternalApi.SET_HVAC_TEMP)
                add(ExternalApi.SET_HVAC_FAN)
                add(RuleKeys.AIR_FLOW)
                add(RuleKeys.AUTO_VOLUME)
                add(ExternalApi.SET_HVAC_RECIRC)
                add(ExternalApi.SET_DEFROST_FRONT)
                add(ExternalApi.SET_DEFROST_REAR)
            }
            if (MG4Hardware.hasBrightnessControl()) add(RuleKeys.BRIGHTNESS)
            add(RuleKeys.VOLUME)
            add(RuleKeys.VOLUME_STEP)
            add(RuleKeys.AEB)
            add(RuleKeys.TSR)
            if (MG4Hardware.hasDrowsinessAndEsc()) { add(RuleKeys.ESC); add(RuleKeys.DROWSINESS) }
            add(RuleKeys.OVERSPEED_ALARM)
            add(RuleKeys.SPEED_LIMIT_TONE)
            if (MG4Hardware.hasAutoHighBeam()) add(RuleKeys.AUTO_HIGH_BEAM)
            if (MG4Hardware.hasBatteryHeating()) add(RuleKeys.BATTERY_HEAT)
            add(RuleKeys.CHARGE_LIMIT)
            add(RuleKeys.WIFI)
            add(RuleKeys.BLUETOOTH)
            add(RuleKeys.HOTSPOT)
        }
        valeurs(s(R.string.rule_choose_setting), cles.map { k -> (AndroidRuleWords.keyRes(k)?.let { s(it) } ?: k) to k }, a.key,
            rangement = ::catReglage, recents = "set") { k ->
            val nom = AndroidRuleWords.keyRes(k)?.let { s(it) } ?: k
            val meme = a.value.takeIf { a.key == k }
            fun fini(v: String) = ok(RuleAction(RuleAction.Type.SET, key = k, value = v))
            fun nombre(min: Int, max: Int, defaut: Int, unite: String = "", pas: Int = 1) =
                RuleValueDialogs.nombre(ctx, nom, null, meme?.toIntOrNull() ?: defaut, min, max,
                    texte = { "$it$unite" }, pas = { v, sens -> v + sens * pas }) { fini("$it") }
            val ouiNon = listOf(s(R.string.segment_yes) to "1", s(R.string.segment_no) to "0")
            when (k) {
                ExternalApi.SET_DRIVE_MODE -> valeurs(nom, DriveMode.entries.map { mots.driveMode(it) to it.name }, meme, ok = ::fini)
                ExternalApi.SET_REGEN -> valeurs(nom, RegenLevel.entries.map { mots.regen(it) to it.name }, meme, ok = ::fini)
                ExternalApi.SET_SEAT_HEAT_LEFT, ExternalApi.SET_SEAT_HEAT_RIGHT ->
                    valeurs(nom, listOf(s(R.string.rule_seat_off) to "0", "1" to "1", "2" to "2", "3" to "3"), meme, ok = ::fini)
                ExternalApi.SET_HVAC_TEMP -> nombre(16, 30, 21, " °C")
                ExternalApi.SET_HVAC_FAN -> nombre(1, 7, 3)
                RuleKeys.AIR_FLOW -> nombre(0, 6, 1)
                RuleKeys.AUTO_VOLUME -> valeurs(nom, listOf(s(R.string.rule_level_high) to "0", s(R.string.rule_level_medium) to "1",
                    s(R.string.rule_level_low) to "2"), meme, ok = ::fini)
                RuleKeys.BRIGHTNESS -> nombre(0, 100, 50, " %", 5)
                RuleKeys.VOLUME -> nombre(0, MG4Hardware.getMediaVolumeMax().takeIf { it > 0 } ?: 40, 10)
                RuleKeys.VOLUME_STEP -> RuleValueDialogs.nombre(ctx, nom, null, meme?.toIntOrNull() ?: 2, -10, 10,
                    texte = { if (it > 0) "+$it" else "$it" }) { fini("$it") }
                RuleKeys.CHARGE_LIMIT -> nombre(40, 100, 80, " %", 10)
                ExternalApi.SET_HVAC_RECIRC -> valeurs(nom, listOf("INNER", "OUTSIDE", "AUTO").map { v ->
                    (AndroidRuleWords.recircRes(v)?.let { s(it) } ?: v) to v }, meme, ok = ::fini)
                else -> valeurs(nom, ouiNon, meme, ok = ::fini)
            }
        }
    }

    // ── Options ──────────────────────────────────────────────────────────────

    private fun options(box: LinearLayout) {
        fun rangee(libelle: Int): LinearLayout {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(haut = 12f)
            }
            r.addView(texteVue(s(libelle), 19f, R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            box.addView(r)
            return r
        }

        val enCours = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_dialog_segments)
            val p = dp(4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(LARGEUR_SEGMENTS_DP), dp(60f))
        }
        val modes = listOf(CustomRule.IfRunning.IGNORE, CustomRule.IfRunning.RESTART)
        AppDialog.segments(enCours, listOf(s(R.string.rule_opt_ignore), s(R.string.rule_opt_restart)),
            modes.indexOf(regle.ifRunning).coerceAtLeast(0)) { rang -> regle = regle.copy(ifRunning = modes[rang]) }
        rangee(R.string.rule_opt_if_running).addView(enCours)

        val pause = texteVue(
            if (regle.cooldownMinutes <= 0) s(R.string.rule_opt_cooldown_none) else s(R.string.rule_minutes_fmt, regle.cooldownMinutes),
            19f, R.color.dash_accent, gras = true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(LARGEUR_SEGMENTS_DP), dp(60f))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dialog_tile)
            setOnClickListener {
                RuleValueDialogs.nombre(ctx, s(R.string.rule_opt_cooldown), s(R.string.rule_opt_cooldown_hint), regle.cooldownMinutes, 0, 240,
                    texte = { if (it == 0) s(R.string.rule_opt_cooldown_none) else s(R.string.rule_minutes_fmt, it) },
                    pas = { v, sens -> v + sens * (if (sens > 0 && v < 10 || sens < 0 && v <= 10) 1 else 5) }) { m ->
                    maj { it.copy(cooldownMinutes = m) }
                }
            }
        }
        rangee(R.string.rule_opt_cooldown).addView(pause)

        val ready = SegmentSwitch(ctx).apply {
            isChecked = regle.onlyWhenReady
            setOnCheckedChangeListener { _, on -> regle = regle.copy(onlyWhenReady = on) }
        }
        rangee(R.string.rule_opt_ready).addView(ready)
    }
}
