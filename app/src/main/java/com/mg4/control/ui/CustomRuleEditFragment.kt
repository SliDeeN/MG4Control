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
import com.mg4.control.model.RuleTrigger
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
        rangee.setOnClickListener { (editer ?: menu)() }
        box.addView(rangee)
    }

    private fun boutonMenu(menu: () -> Unit) = texteVue("⋮", 30f, R.color.text_secondary, gras = true).apply {
        layoutParams = LinearLayout.LayoutParams(dp(64f), dp(64f))
        gravity = Gravity.CENTER
        contentDescription = s(R.string.rule_menu_title)
        setOnClickListener { menu() }
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
            setOnClickListener { onClick() }
        })
    }

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
            if (profondeur < PROFONDEUR_MAX - 1) {
                add(R.string.rule_cond_group_all to { ok(RuleCondition(RuleCondition.Type.ALL, children = emptyList())) })
                add(R.string.rule_cond_group_any to { ok(RuleCondition(RuleCondition.Type.ANY, children = emptyList())) })
                add(R.string.rule_cond_group_not to { ok(RuleCondition(RuleCondition.Type.NOT, children = emptyList())) })
            }
        }
        liste(R.string.rule_choose_condition, choix)
    }

    /** Modifie une condition simple (valeur, plage, jours) ; un nœud inconnu repart d'une valeur. */
    private fun condition(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) = when (actuelle?.type) {
        RuleCondition.Type.TIME_RANGE -> plage(actuelle, ok)
        RuleCondition.Type.WEEKDAYS -> jours(actuelle, ok)
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

    /** Une grille de choix dont chaque tuile lance son action. */
    private fun liste(titre: Int, choix: List<Pair<Int, () -> Unit>>, choisi: Int = -1) {
        AppDialog.choisir(ctx, titre = s(titre), choix = choix.map { AppDialog.Choix(s(it.first)) },
            annuler = s(R.string.profile_cancel), choisi = choisi) { rang -> choix[rang].second() }
    }

    /** Une grille de valeurs libellées. */
    private fun <T> valeurs(titre: CharSequence, choix: List<Pair<String, T>>, choisi: T?, ok: (T) -> Unit) {
        if (choix.isEmpty()) return
        AppDialog.choisir(ctx, titre = titre, choix = choix.map { AppDialog.Choix(it.first) },
            annuler = s(R.string.profile_cancel), choisi = choix.indexOfFirst { it.second == choisi }) { rang -> ok(choix[rang].second) }
    }

    private fun signaux(): List<RuleSignal> = RuleSignal.entries.filter { it != RuleSignal.HVAC_ON || MG4Hardware.hasClimateControl() }

    private class Borne(val min: Int, val max: Int, val defaut: Int, val pas: Int)

    private fun borne(s: RuleSignal) = when (s) {
        RuleSignal.SPEED -> Borne(0, 200, 50, 5)
        RuleSignal.SOC -> Borne(0, 100, 50, 1)
        RuleSignal.RANGE -> Borne(0, 700, 100, 5)
        RuleSignal.OUTSIDE_TEMP -> Borne(-30, 50, 20, 1)
        else -> Borne(0, 100, 0, 1)
    }

    /** Les valeurs possibles d'un signal texte ; vide = rien à proposer (pas de profil, de téléphone). */
    private fun choixTexte(sig: RuleSignal): List<Pair<String, String>> = when (sig) {
        RuleSignal.GEAR -> listOf(RuleSignal.GEAR_PARK, RuleSignal.GEAR_DRIVE, RuleSignal.GEAR_OTHER).map { mots.value(sig, it) to it }
        RuleSignal.PROFILE -> ProfileManager(ctx).getAll().map { it.name to it.id }
        RuleSignal.DRIVE_MODE -> DriveMode.entries.map { mots.driveMode(it) to it.name }
        RuleSignal.BT_DEVICE -> runCatching { BluetoothProfileManager.getBondedDevices(ctx) }.getOrNull().orEmpty()
            .map { it.name to it.mac.uppercase() }
        else -> emptyList()
    }

    /** Une comparaison : le signal, puis comment le comparer, puis la valeur. */
    private fun comparer(actuelle: RuleCondition?, ok: (RuleCondition) -> Unit) {
        val liste = signaux()
        valeurs(s(R.string.rule_choose_signal), liste.map { mots.signal(it) to it }, actuelle?.signal) { sig ->
            val meme = actuelle?.takeIf { it.signal == sig }
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
                    if (choix.isEmpty()) {
                        Toast.makeText(ctx, s(if (sig == RuleSignal.BT_DEVICE) R.string.rule_no_phones else R.string.rule_no_profiles), Toast.LENGTH_LONG).show()
                        return@valeurs
                    }
                    valeurs(mots.signal(sig),
                        listOf(s(R.string.rule_op_is) to RuleOp.EQUALS, s(R.string.rule_op_is_not) to RuleOp.NOT_EQUALS), meme?.op) { op ->
                        valeurs(mots.signal(sig), choix, meme?.text) { v -> ok(base(op).copy(text = v)) }
                    }
                }
            }
        }
    }

    // ── Déclencheurs ─────────────────────────────────────────────────────────

    private fun declencheur(actuel: RuleTrigger?, ok: (RuleTrigger) -> Unit) {
        fun evenement(t: RuleTrigger) = valeurs(s(R.string.rule_choose_event),
            RuleEvent.entries.filter { it != RuleEvent.FRONT_DOOR_OPEN || MG4Hardware.hasDoorDetection() }
                .map { mots.event(it) to it }, t.event) { e -> ok(t.copy(type = RuleTrigger.Type.EVENT, event = e)) }
        fun devient(t: RuleTrigger) = comparer(t.condition) { c ->
            RuleValueDialogs.nombre(ctx, s(R.string.rule_for_title), s(R.string.rule_for_hint), t.forSeconds, 0, RuleRun.MAX_WAIT_S,
                texte = { if (it == 0) s(R.string.rule_for_now) else duree(it) }, pas = ::pasDuree) { sec ->
                ok(t.copy(type = RuleTrigger.Type.BECOMES, condition = c, forSeconds = sec))
            }
        }
        fun horaire(t: RuleTrigger) = RuleValueDialogs.heure(ctx, s(R.string.rule_time_title), t.at ?: 7 * 60) { m ->
            ok(t.copy(type = RuleTrigger.Type.TIME, at = m))
        }
        when (actuel?.type) {
            RuleTrigger.Type.EVENT -> evenement(actuel)
            RuleTrigger.Type.BECOMES -> devient(actuel)
            RuleTrigger.Type.TIME -> horaire(actuel)
            null -> liste(R.string.rule_choose_trigger, listOf(
                R.string.rule_trg_event to { evenement(RuleTrigger()) },
                R.string.rule_trg_becomes to { devient(RuleTrigger()) },
                R.string.rule_trg_time to { horaire(RuleTrigger()) },
            ))
        }
    }

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

    /** Ajoute ([actuelle] nulle) ou modifie une action. */
    private fun action(actuelle: RuleAction?, profondeur: Int, ok: (RuleAction) -> Unit) {
        fun reglage(a: RuleAction) = choisirReglage(a, ok)
        fun raccourci(a: RuleAction) = valeurs(s(R.string.rule_choose_shortcut),
            ShortcutCatalog.items(ctx).filter { it.action !in CustomRules.INTERDITS }.map { it.label to it.action.name }, a.shortcut) { n ->
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
        fun message(a: RuleAction) = RuleValueDialogs.texte(ctx, s(R.string.rule_notify_title), a.text.orEmpty()) { t ->
            ok(a.copy(type = RuleAction.Type.NOTIFY, text = t))
        }
        fun repeter(a: RuleAction) = RuleValueDialogs.nombre(ctx, s(R.string.rule_repeat_title), null, a.count ?: 2, 2, RuleRun.MAX_REPEAT,
            texte = { "×$it" }) { n -> ok(a.copy(type = RuleAction.Type.REPEAT, count = n)) }

        when (actuelle?.type) {
            RuleAction.Type.SET -> reglage(actuelle)
            RuleAction.Type.SHORTCUT -> raccourci(actuelle)
            RuleAction.Type.PROFILE -> profil(actuelle)
            RuleAction.Type.WAIT -> attente(actuelle)
            RuleAction.Type.NOTIFY -> message(actuelle)
            RuleAction.Type.REPEAT -> repeter(actuelle)
            RuleAction.Type.IF, RuleAction.Type.STOP -> {}
            null -> liste(R.string.rule_choose_action, buildList {
                add(R.string.rule_act_set to { reglage(RuleAction()) })
                add(R.string.rule_act_shortcut to { raccourci(RuleAction()) })
                add(R.string.rule_act_profile to { profil(RuleAction()) })
                add(R.string.rule_act_wait to { attente(RuleAction()) })
                add(R.string.rule_act_notify to { message(RuleAction()) })
                if (profondeur < PROFONDEUR_MAX - 1) {
                    add(R.string.rule_act_if to {
                        ok(RuleAction(RuleAction.Type.IF, condition = RuleCondition(RuleCondition.Type.ALL, children = emptyList()),
                            then = emptyList(), otherwise = emptyList()))
                    })
                    add(R.string.rule_act_repeat to { repeter(RuleAction(then = emptyList())) })
                }
                add(R.string.rule_act_stop to { ok(RuleAction(RuleAction.Type.STOP)) })
            })
        }
    }

    /** Les réglages de la voiture qu'une action peut écrire, et la façon de choisir leur valeur. */
    private fun choisirReglage(a: RuleAction, ok: (RuleAction) -> Unit) {
        val clim = MG4Hardware.hasClimateControl()
        val cles = buildList {
            add(ExternalApi.SET_DRIVE_MODE)
            add(ExternalApi.SET_REGEN)
            add(ExternalApi.SET_SEAT_HEAT_LEFT)
            add(ExternalApi.SET_SEAT_HEAT_RIGHT)
            add(ExternalApi.SET_STEERING_HEAT)
            if (clim) {
                add(ExternalApi.SET_HVAC_POWER)
                add(ExternalApi.SET_HVAC_AC)
                add(ExternalApi.SET_HVAC_AUTO)
                add(ExternalApi.SET_HVAC_TEMP)
                add(ExternalApi.SET_HVAC_FAN)
                add(ExternalApi.SET_HVAC_RECIRC)
                add(ExternalApi.SET_DEFROST_FRONT)
                add(ExternalApi.SET_DEFROST_REAR)
            }
        }
        valeurs(s(R.string.rule_choose_setting), cles.map { k -> (AndroidRuleWords.keyRes(k)?.let { s(it) } ?: k) to k }, a.key) { k ->
            val nom = AndroidRuleWords.keyRes(k)?.let { s(it) } ?: k
            val meme = a.value.takeIf { a.key == k }
            fun fini(v: String) = ok(RuleAction(RuleAction.Type.SET, key = k, value = v))
            val ouiNon = listOf(s(R.string.segment_yes) to "1", s(R.string.segment_no) to "0")
            when (k) {
                ExternalApi.SET_DRIVE_MODE -> valeurs(nom, DriveMode.entries.map { mots.driveMode(it) to it.name }, meme, ::fini)
                ExternalApi.SET_REGEN -> valeurs(nom, RegenLevel.entries.map { mots.regen(it) to it.name }, meme, ::fini)
                ExternalApi.SET_SEAT_HEAT_LEFT, ExternalApi.SET_SEAT_HEAT_RIGHT ->
                    valeurs(nom, listOf(s(R.string.rule_seat_off) to "0", "1" to "1", "2" to "2", "3" to "3"), meme, ::fini)
                ExternalApi.SET_HVAC_TEMP -> RuleValueDialogs.nombre(ctx, nom, null, meme?.toIntOrNull() ?: 21, 16, 30,
                    texte = { "$it °C" }) { fini("$it") }
                ExternalApi.SET_HVAC_FAN -> RuleValueDialogs.nombre(ctx, nom, null, meme?.toIntOrNull() ?: 3, 1, 7,
                    texte = { "$it" }) { fini("$it") }
                ExternalApi.SET_HVAC_RECIRC -> valeurs(nom, listOf("INNER", "OUTSIDE", "AUTO").map { v ->
                    (AndroidRuleWords.recircRes(v)?.let { s(it) } ?: v) to v }, meme, ::fini)
                else -> valeurs(nom, ouiNon, meme, ::fini)
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
