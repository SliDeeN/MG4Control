package com.mg4.control.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.mg4.control.R
import com.mg4.control.accessibility.AdvancedShortcuts
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction

/**
 * Onglet Raccourcis › « Boutons physiques » (fusion du 2026-11-02 des anciens sous-onglets
 * « Raccourcis » et « Avancés », maquette validée ; renommé le même jour : les touches ne sont pas
 * toutes au volant) : une carte par touche, avec ses appuis Simple, Long et Double.
 *
 * Deux stockages, inchangés, pour ne rien perdre de ce que l'utilisateur avait réglé :
 * - **classique** (`mg4_shortcuts`, `shortcut_btn1_single`…) : les boutons ★, appuis simple et long,
 *   par le broadcast de la voiture — marche sans la capture avancée ;
 * - **avancé** ([AdvancedShortcuts]) : toute autre touche, le double appui, et un réglage par profil.
 *
 * Un bouton ★ passe à l'avancé dès qu'il y porte un raccourci, capture active : la touche est alors
 * interceptée en bloc et le broadcast n'apporterait plus l'appui long. Ses réglages classiques y sont
 * recopiés à ce moment-là (et restent en place : couper la capture rend le classique).
 */
class WheelButtonsPage(private val host: Host) {

    interface Host {
        val ctx: Context
        /** Les actions proposées, « Aucune » en tête, filtrées par firmware. */
        fun actions(): List<Pair<String, ShortcutAction>>
        /** Choisit la cible d'une action qui en demande une (appli, profil, raccourci perso), puis [apres]. */
        fun choisirCible(slot: String, action: ShortcutAction, apres: () -> Unit)
        /** Le libellé d'une action d'un emplacement, cible comprise (« Ouvrir Waze »). */
        fun libelle(slot: String, action: ShortcutAction): String
        /** Une attribution a changé : les pages de réglage des actions apparaissent ou disparaissent. */
        fun changement()
    }

    private companion object {
        const val STAR_LEFT = 17
        const val STAR_RIGHT = 286
        const val COLONNES = 3
        const val CAPTURE_MS = 15_000L
        const val PREFS_CLASSIQUE = "mg4_shortcuts"
    }

    private var racine: View? = null
    /** Profil réglé : `null` = tous les profils. */
    private var portee: String? = null
    /** Touches ajoutées pendant la session, qui n'ont encore aucun raccourci. */
    private val ajoutees = linkedSetOf<Int>()

    private val ctx: Context get() = host.ctx
    private fun s(@StringRes id: Int, vararg a: Any) = ctx.getString(id, *a)
    private fun dp(v: Float) = AppDialog.dp(ctx, v)
    private val classique get() = ctx.getSharedPreferences(PREFS_CLASSIQUE, Context.MODE_PRIVATE)

    fun bind(root: View) {
        racine = root
        refresh()
    }

    fun detacher() {
        racine = null
        KeyCaptureService.listener = null
    }

    private fun serviceActif() = KeyCaptureService.isEnabled(ctx)
    private fun captureActive() = AdvancedShortcuts.isEnabled(ctx) && serviceActif()

    private fun slotClassique(code: Int, press: PressType): String? = when (code) {
        STAR_LEFT -> "btn1_${press.key}"
        STAR_RIGHT -> "btn2_${press.key}"
        else -> null
    }?.takeIf { press != PressType.DOUBLE }

    private fun estEtoile(code: Int) = code == STAR_LEFT || code == STAR_RIGHT

    /** Une touche ★ suit le stockage avancé dès qu'elle y porte un raccourci, capture active. */
    private fun enAvance(code: Int): Boolean =
        !estEtoile(code) || (captureActive() && AdvancedShortcuts.all(ctx).any { it.keyCode == code })

    fun refresh() {
        val root = racine ?: return
        if (!captureActive()) portee = null
        // Profil supprimé entre-temps : retour à « tous les profils ».
        if (portee != null && ProfileManager(ctx).getById(portee!!) == null) portee = null
        bandeau(root.findViewById(R.id.wheel_status))
        porteeChoix(root.findViewById(R.id.wheel_scope), root.findViewById(R.id.wheel_scope_scroll))
        cartes(root.findViewById(R.id.wheel_cards))
    }

    // ── Bandeau d'état de la capture avancée ─────────────────────────────────

    private fun bandeau(box: LinearLayout) {
        box.removeAllViews()
        val (texte, fond, bord, bouton) = when {
            captureActive() -> Quad(R.string.wheel_status_on, R.color.dash_eco_dim, R.color.dash_eco, R.string.wheel_disable)
            !serviceActif() -> Quad(R.string.wheel_status_service_off, R.color.dash_warn_dim, R.color.dash_warn, R.string.wheel_open_settings)
            else -> Quad(R.string.wheel_status_off, R.color.dash_warn_dim, R.color.dash_warn, R.string.wheel_enable)
        }
        com.mg4.control.service.OverlayKit.fond(box, ctx.getColor(fond), ctx.getColor(bord), dp(1f), dp(18f).toFloat())
        box.setPadding(dp(18f), dp(12f), dp(12f), dp(12f))
        box.addView(TextView(ctx).apply {
            text = s(texte)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(tuile(s(bouton), accent = !captureActive()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(58f)).also { it.marginStart = dp(14f) }
            setPadding(dp(22f), 0, dp(22f), 0)
            setOnClickListener { basculerCapture() }
        })
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun basculerCapture() {
        when {
            captureActive() -> {
                AdvancedShortcuts.setEnabled(ctx, false)
                refresh()
            }
            // Mêmes précautions qu'avant la fusion : on prévient avant d'armer quoi que ce soit.
            else -> AppDialog.confirmer(ctx,
                titre = s(R.string.adv_sc_enable_warn_title),
                message = s(R.string.adv_sc_enable_warn_msg),
                ton = AppDialog.Ton.ALERTE,
                oui = s(R.string.adv_sc_enable_warn_ok),
                non = s(R.string.profile_cancel),
                onOui = {
                    AdvancedShortcuts.setEnabled(ctx, true)
                    if (!serviceActif()) ouvrirAccessibilite()
                    refresh()
                })
        }
    }

    private fun ouvrirAccessibilite() {
        val ouvert = runCatching {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        if (!ouvert) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            AppLogger.w("MG4_KEYCAP", "réglages d'accessibilité inaccessibles — repli Réglages")
        }
    }

    // ── Profil réglé ─────────────────────────────────────────────────────────

    private fun porteeChoix(box: LinearLayout, cadre: View) {
        box.removeAllViews()
        val profils = ProfileManager(ctx).getAll()
        cadre.visibility = if (captureActive() && profils.isNotEmpty()) View.VISIBLE else View.GONE
        if (cadre.visibility != View.VISIBLE) return
        box.addView(TextView(ctx).apply {
            text = s(R.string.wheel_scope_label)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setPadding(0, 0, dp(12f), 0)
        })
        (listOf<Pair<String, String?>>(s(R.string.adv_sc_all_profiles) to null) + profils.map { it.name to it.id }).forEach { (nom, id) ->
            val on = id == portee
            box.addView(TextView(ctx).apply {
                text = nom
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTextColor(ctx.getColor(if (on) R.color.dash_on_accent else R.color.text_primary))
                com.mg4.control.service.OverlayKit.fond(this,
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_card),
                    ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border), dp(1f), dp(26f).toFloat())
                setPadding(dp(20f), 0, dp(20f), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52f)).also { it.marginEnd = dp(8f) }
                setOnClickListener { portee = id; refresh() }
            })
        }
    }

    // ── Cartes ───────────────────────────────────────────────────────────────

    private fun touches(): List<Int> {
        val avancees = if (captureActive()) AdvancedShortcuts.all(ctx).map { it.keyCode } else emptyList()
        return (listOf(STAR_LEFT, STAR_RIGHT) + avancees.sorted() + ajoutees).distinct()
    }

    private fun cartes(box: LinearLayout) {
        box.removeAllViews()
        val vues = touches().map { carte(it) } + ajout()
        vues.chunked(COLONNES).forEach { ligne ->
            val rangee = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = dp(14f) }
            }
            repeat(COLONNES) { i ->
                val v = ligne.getOrNull(i) ?: View(ctx)
                v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .also { if (i > 0) it.marginStart = dp(14f) }
                rangee.addView(v)
            }
            box.addView(rangee)
        }
    }

    // ── Nom des touches : celui donné par l'utilisateur, sinon le nom connu, sinon le code ──
    // Pas de table de noms par code Android : rien ne garantit le même code d'un firmware à l'autre.

    private val noms get() = ctx.getSharedPreferences(AdvancedShortcuts.PREFS, Context.MODE_PRIVATE)
    private fun cleNom(code: Int) = "wheel_key_name_$code"
    private fun nomPerso(code: Int): String? = noms.getString(cleNom(code), null)?.takeIf { it.isNotBlank() }

    fun nomTouche(code: Int): String = nomPerso(code) ?: nomOrigine(code)

    private fun nomOrigine(code: Int): String = when (code) {
        STAR_LEFT -> s(R.string.wheel_key_star_left)
        STAR_RIGHT, 18 -> s(R.string.wheel_key_star_right)
        297 -> s(R.string.wheel_key_joy_up)
        298 -> s(R.string.wheel_key_joy_down)
        299 -> s(R.string.wheel_key_joy_left)
        300 -> s(R.string.wheel_key_joy_right)
        301 -> s(R.string.wheel_key_joy_center)
        else -> s(R.string.adv_sc_key_unknown, code)
    }

    private fun carte(code: Int): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(18f), dp(14f), dp(18f), dp(16f))
        }
        // Même hauteur de titre pour toutes les cartes, ⋮ ou non : sinon les lignes Simple / Long /
        // Double d'un ★ (sans ⋮) remontent par rapport à celles d'une touche ajoutée.
        val tete = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
        }
        tete.addView(TextView(ctx).apply {
            text = nomTouche(code)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tete.addView(TextView(ctx).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(56f))
            setOnClickListener { menuTouche(code) }
        })
        c.addView(tete)
        listOf(
            PressType.SINGLE to R.string.shortcuts_press_single,
            PressType.LONG to R.string.shortcuts_press_long,
            PressType.DOUBLE to R.string.wheel_press_double,
        ).forEach { (press, nom) -> c.addView(ligne(code, press, s(nom))) }
        return c
    }

    /** Ce que porte un appui : action, emplacement de sa cible, et s'il vient de « tous les profils ». */
    private data class Lecture(val action: ShortcutAction, val slot: String, val herite: Boolean)

    private fun lire(code: Int, press: PressType): Lecture {
        if (!enAvance(code)) {
            val slot = slotClassique(code, press) ?: return Lecture(ShortcutAction.NONE, "", false)
            return Lecture(ShortcutAction.fromId(classique.getInt("shortcut_$slot", 0)), slot, false)
        }
        AdvancedShortcuts.actionFor(ctx, code, press, portee)?.let {
            return Lecture(it, AdvancedShortcuts.slotKey(code, press, portee), false)
        }
        if (portee != null) AdvancedShortcuts.actionFor(ctx, code, press, null)?.let {
            return Lecture(it, AdvancedShortcuts.slotKey(code, press, null), true)
        }
        return Lecture(ShortcutAction.NONE, AdvancedShortcuts.slotKey(code, press, portee), false)
    }

    private fun verrouille(code: Int, press: PressType): Boolean =
        !captureActive() && (!estEtoile(code) || press == PressType.DOUBLE)

    private fun ligne(code: Int, press: PressType, nom: String): View {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(10f) }
        }
        r.addView(TextView(ctx).apply {
            text = nom
            isAllCaps = true
            letterSpacing = 0.06f
            setTextColor(ctx.getColor(R.color.dash_text_lo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(dp(92f), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val tuile = if (verrouille(code, press)) {
            tuile("🔒 " + s(R.string.wheel_locked), accent = false).apply {
                setTextColor(ctx.getColor(R.color.dash_text_lo))
                setOnClickListener { Toast.makeText(ctx, s(R.string.wheel_locked_hint), Toast.LENGTH_LONG).show() }
            }
        } else {
            val l = lire(code, press)
            val libelle = when {
                l.action == ShortcutAction.NONE -> s(R.string.shortcuts_action_none)
                l.herite -> s(R.string.wheel_inherited_fmt, host.libelle(l.slot, l.action))
                else -> host.libelle(l.slot, l.action)
            }
            tuile("$libelle  ›", accent = l.action != ShortcutAction.NONE && !l.herite).apply {
                setOnClickListener { choisir(code, press, l) }
            }
        }
        tuile.layoutParams = LinearLayout.LayoutParams(0, dp(58f), 1f)
        r.addView(tuile)
        return r
    }

    private fun tuile(texte: String, accent: Boolean) = TextView(ctx).apply {
        text = texte
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(ctx.getColor(if (accent) R.color.text_primary else R.color.text_secondary))
        setPadding(dp(16f), 0, dp(12f), 0)
        // Deux lignes si la tuile les loge, sinon une, puis « … » (règle commune des boutons).
        com.mg4.control.util.ButtonLines.appliquer(this)
        com.mg4.control.service.OverlayKit.fond(this,
            ctx.getColor(if (accent) R.color.dash_accent_dim else R.color.dash_btn),
            ctx.getColor(if (accent) R.color.dash_accent else R.color.dash_border), dp(1f), dp(14f).toFloat())
    }

    // ── Attribution ──────────────────────────────────────────────────────────

    private fun choisir(code: Int, press: PressType, actuelle: Lecture) {
        val actions = host.actions()
        AppDialog.choisir(ctx,
            titre = s(R.string.wheel_pick_action),
            sousTitre = nomTouche(code),
            choix = actions.map { AppDialog.Choix(it.first) },
            annuler = s(R.string.profile_cancel),
            choisi = if (actuelle.herite) -1 else actions.indexOfFirst { it.second == actuelle.action },
        ) { rang -> attribuer(code, press, actions[rang].second) }
    }

    private fun attribuer(code: Int, press: PressType, action: ShortcutAction) {
        // Un ★ passe à l'avancé pour un double appui ou un réglage propre à un profil.
        val versAvance = estEtoile(code) && !enAvance(code) && (press == PressType.DOUBLE || portee != null)
        if (versAvance) migrer(code)
        if (!enAvance(code)) {
            val slot = slotClassique(code, press) ?: return
            val ecrire = {
                classique.edit().putInt("shortcut_$slot", action.id).apply()
                fini()
            }
            if (action == ShortcutAction.NONE) ecrire() else host.choisirCible(slot, action, ecrire)
            return
        }
        if (action == ShortcutAction.NONE) {
            AdvancedShortcuts.remove(ctx, code, press, portee)
            fini()
            return
        }
        val slot = AdvancedShortcuts.slotKey(code, press, portee)
        host.choisirCible(slot, action) {
            AdvancedShortcuts.set(ctx, code, press, action, portee)
            AppLogger.i("MG4_KEYCAP", "volant : touche=$code ${press.key} → ${action.name} (profil ${portee ?: "tous"})")
            fini()
        }
    }

    /**
     * Recopie les appuis simple et long classiques d'un ★ dans le stockage avancé (tous les
     * profils), cibles comprises : la touche y passe, son broadcast ne livrerait plus l'appui long.
     */
    private fun migrer(code: Int) {
        val p = classique
        val e = p.edit()
        for (press in listOf(PressType.SINGLE, PressType.LONG)) {
            val slot = slotClassique(code, press) ?: continue
            val action = ShortcutAction.fromId(p.getInt("shortcut_$slot", 0))
            if (action == ShortcutAction.NONE || AdvancedShortcuts.actionFor(ctx, code, press, null) != null) continue
            AdvancedShortcuts.set(ctx, code, press, action, null)
            val vers = AdvancedShortcuts.slotKey(code, press, null)
            for (champ in listOf("custom_app", "profile_id", "custom_id")) {
                p.getString("shortcut_${slot}_$champ", null)?.let { e.putString("shortcut_${vers}_$champ", it) }
            }
        }
        e.apply()
        AppLogger.i("MG4_KEYCAP", "volant : ★ $code passe à la capture avancée, réglages classiques recopiés")
    }

    private fun fini() {
        host.changement()
        refresh()
    }

    /** ⋮ d'une carte : renommer, revenir au nom d'origine, retirer (touches ajoutées seulement). */
    private fun menuTouche(code: Int) {
        val gestes = buildList<Pair<String, () -> Unit>> {
            add(s(R.string.wheel_rename) to { renommer(code) })
            if (nomPerso(code) != null) add(s(R.string.wheel_name_reset) to {
                noms.edit().remove(cleNom(code)).apply()
                refresh()
            })
            if (!estEtoile(code)) add(s(R.string.wheel_remove_key) to { retirer(code) })
        }
        AppDialog.choisir(ctx,
            titre = nomTouche(code),
            sousTitre = s(R.string.adv_sc_key_unknown, code),
            icone = R.drawable.ic_dialog_edit,
            choix = gestes.map { AppDialog.Choix(it.first) },
            annuler = s(R.string.profile_cancel),
        ) { rang -> gestes[rang].second() }
    }

    /**
     * Nom PROPOSÉ au renommage d'une touche sans nom : celui de la touche Android de même code
     * (`KeyEvent.KEYCODE_*`). Seulement une proposition — rien ne garantit qu'un firmware donne le
     * même code à la même touche ; l'utilisateur corrige au besoin. La carte, elle, garde son nom
     * d'origine tant qu'il n'a rien validé.
     */
    private fun suggestion(code: Int): String? = when (code) {
        3 -> R.string.wheel_hint_home
        4 -> R.string.wheel_hint_back
        5 -> R.string.wheel_hint_call
        6 -> R.string.wheel_hint_end_call
        24 -> R.string.wheel_hint_vol_up
        25 -> R.string.wheel_hint_vol_down
        82 -> R.string.wheel_hint_menu
        85 -> R.string.wheel_hint_play_pause
        86 -> R.string.wheel_hint_stop
        87 -> R.string.wheel_hint_next
        88 -> R.string.wheel_hint_previous
        164 -> R.string.wheel_hint_mute
        231 -> R.string.wheel_hint_voice
        else -> null
    }?.let { s(it) }

    private fun renommer(code: Int) {
        val propose = nomPerso(code) ?: suggestion(code) ?: nomOrigine(code)
        RuleValueDialogs.texte(ctx, s(R.string.wheel_name_title), propose) { nom ->
            noms.edit().putString(cleNom(code), nom.take(30)).apply()
            refresh()
        }
    }

    private fun retirer(code: Int) {
        AppDialog.confirmer(ctx,
            titre = s(R.string.wheel_remove_key) + " — " + nomTouche(code),
            message = s(R.string.wheel_remove_key_msg),
            ton = AppDialog.Ton.DANGER,
            oui = s(R.string.wheel_remove_key),
            non = s(R.string.profile_cancel),
            onOui = {
                AdvancedShortcuts.all(ctx).filter { it.keyCode == code }
                    .forEach { AdvancedShortcuts.remove(ctx, it.keyCode, it.press, it.profileId) }
                noms.edit().remove(cleNom(code)).apply()
                ajoutees.remove(code)
                fini()
            })
    }

    // ── Ajouter une touche ───────────────────────────────────────────────────

    private fun ajout(): View = TextView(ctx).apply {
        val actif = captureActive()
        text = "+  " + s(R.string.wheel_add_key) + "\n" + s(R.string.wheel_add_key_hint)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(ctx.getColor(if (actif) R.color.dash_accent else R.color.dash_text_lo))
        minHeight = dp(200f)
        setBackgroundResource(R.drawable.bg_dialog_tile)
        setOnClickListener {
            if (!actif) Toast.makeText(ctx, s(R.string.wheel_locked_hint), Toast.LENGTH_LONG).show()
            else capturer(this)
        }
    }

    private fun capturer(ancre: View) {
        val dialog = AppDialog.confirmer(ctx,
            titre = s(R.string.wheel_capture_title),
            message = s(R.string.wheel_capture_msg),
            icone = R.drawable.ic_dialog_question,
            oui = s(R.string.profile_cancel),
            onOui = { KeyCaptureService.listener = null })
        val expire = Runnable {
            KeyCaptureService.listener = null
            if (dialog.isShowing) {
                dialog.dismiss()
                Toast.makeText(ctx, R.string.adv_sc_record_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
        ancre.postDelayed(expire, CAPTURE_MS)
        dialog.setOnDismissListener { KeyCaptureService.listener = null; ancre.removeCallbacks(expire) }
        KeyCaptureService.listener = { code ->
            KeyCaptureService.listener = null
            ancre.post {
                ancre.removeCallbacks(expire)
                if (dialog.isShowing) dialog.dismiss()
                ajoutees += code
                Toast.makeText(ctx, s(R.string.wheel_capture_added, nomTouche(code)), Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }
}
