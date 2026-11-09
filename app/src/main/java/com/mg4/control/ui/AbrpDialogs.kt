package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.automation.AbrpStore
import com.mg4.control.automation.AbrpStream
import com.mg4.control.model.AbrpAccount
import com.mg4.control.model.AbrpCadence
import com.mg4.control.model.AbrpConfigFile
import com.mg4.control.model.AbrpField
import com.mg4.control.model.maskSecret
import java.text.DateFormat
import java.util.Date

/**
 * Fenêtres ABRP (issue #188, maquette validée le 2026-11-06) : la liste des comptes avec l'état de
 * l'envoi et la cadence, et la fenêtre d'un compte (nom, jeton, clé, données envoyées, Tester).
 * Le jeton n'est jamais réaffiché en entier : ses 4 derniers caractères seulement.
 */
object AbrpDialogs {

    private const val LARGEUR_DP = 980f

    /**
     * Les comptes. [importer] et [modele] ouvrent les fenêtres d'import et d'export de la page
     * (leurs sélecteurs de fichiers sont enregistrés par le fragment).
     */
    /** [nouveau] : un compte vient d'être AJOUTÉ (pas modifié) ; la liste est alors fermée. */
    fun comptes(ctx: Context, importer: () -> Unit, modele: () -> Unit, nouveau: (AbrpAccount) -> Unit = {}) {
        lateinit var dialog: AlertDialog
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        val corps = vue.findViewById<LinearLayout>(R.id.rule_value_body)
        vue.findViewById<View>(R.id.rule_value_cancel).visibility = View.GONE
        vue.findViewById<View>(R.id.rule_value_back).visibility = View.GONE
        fun dessiner() {
            val comptes = AbrpStore.accounts(ctx)
            vue.findViewById<TextView>(R.id.rule_value_title).text = ctx.getString(R.string.abrp_title)
            vue.findViewById<TextView>(R.id.rule_value_subtitle).text =
                ctx.getString(R.string.abrp_subtitle_fmt, comptes.size, AbrpStore.MAX_ACCOUNTS)
            corps.removeAllViews()
            val liste = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // Hauteur fixe : cinq comptes et la cadence dépasseraient l'écran de la voiture.
            corps.addView(ScrollView(ctx).apply {
                addView(liste)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 440f))
            })
            if (AbrpStore.appKey.isBlank()) liste.addView(note(ctx, ctx.getString(R.string.abrp_no_app_key), alerte = true))
            etat(ctx, liste) { dessiner() }
            if (comptes.isEmpty()) liste.addView(note(ctx, ctx.getString(R.string.abrp_none)))
            comptes.forEach { a ->
                liste.addView(ligne(ctx, a.name,
                    maskSecret(a.token) + " · " + ctx.getString(if (a.apiKey.isBlank()) R.string.abrp_key_mg4 else R.string.abrp_key_personal),
                    ctx.getString(R.string.abrp_edit)) { compte(ctx, a) { dessiner() } })
            }
            val boutons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 12f), 0, 0) }
            boutons.addView(tuile(ctx, ctx.getString(R.string.abrp_add), comptes.size < AbrpStore.MAX_ACCOUNTS) {
                val ajout = { a: AbrpAccount -> dialog.dismiss(); nouveau(a) }
                if (AbrpStore.consent(ctx)) compte(ctx, null, ajout) { dessiner() }
                else AppDialog.confirmer(ctx, titre = ctx.getString(R.string.abrp_consent_title), message = ctx.getString(R.string.abrp_consent_msg),
                    ton = AppDialog.Ton.ALERTE, oui = ctx.getString(R.string.abrp_consent_ok), non = ctx.getString(R.string.profile_cancel),
                    onOui = { AbrpStore.setConsent(ctx); compte(ctx, null, ajout) { dessiner() } })
            })
            boutons.addView(tuile(ctx, ctx.getString(R.string.abrp_import_usb), true) { dialog.dismiss(); importer() })
            boutons.addView(tuile(ctx, ctx.getString(R.string.abrp_template), true) { dialog.dismiss(); modele() })
            liste.addView(boutons)
            cadence(ctx, liste)
        }
        dessiner()
        vue.findViewById<TextView>(R.id.rule_value_ok).apply {
            text = ctx.getString(R.string.nav_close)
            setOnClickListener { dialog.dismiss() }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
        // L'état de l'envoi suit chaque relevé tant que la fenêtre est ouverte.
        AbrpStream.onChange = { if (dialog.isShowing) dessiner() }
        dialog.setOnDismissListener { AbrpStream.onChange = null }
    }

    /** Envoi en cours : vers quel compte, le dernier relevé, et Arrêter. */
    private fun etat(ctx: Context, box: LinearLayout, apres: () -> Unit) {
        val st = AbrpStream.status
        if (!st.running) return
        val nom = AbrpStore.account(ctx, st.accountId)?.name ?: ctx.getString(R.string.abrp_account_missing)
        val heure = st.lastAtMs?.let { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) }
        val detail = when {
            heure == null -> ctx.getString(R.string.abrp_waiting)
            st.lastCode == 200 -> ctx.getString(R.string.abrp_last_ok_fmt, heure, st.sent) + "\n" + (st.lastText ?: "")
            else -> ctx.getString(R.string.abrp_last_fail_fmt, heure, (if ((st.lastCode ?: -1) < 0) "" else "HTTP ${st.lastCode} · ") + (st.lastText ?: ""))
        }
        box.addView(ligne(ctx, ctx.getString(R.string.abrp_running_fmt, nom), detail, ctx.getString(R.string.abrp_stop), accent = true) {
            AbrpStream.stop(ctx, "fenêtre des comptes")
            apres()
        })
    }

    /** Cadence de l'envoi : en roulant, en charge, à l'arrêt ; et la consommation de données estimée. */
    private fun cadence(ctx: Context, box: LinearLayout) {
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.abrp_cadence_title)
            textSize = 19f
            setTextColor(ctx.getColor(R.color.text_primary))
            setPadding(0, dp(ctx, 18f), 0, dp(ctx, 4f))
        })
        val estimation = TextView(ctx).apply { textSize = 16f; setTextColor(ctx.getColor(R.color.text_secondary)) }
        fun maj() {
            // Un relevé ≈ 1,2 Ko avec l'en-tête HTTPS (mesure d'EVABRPUploader : environ 1 Ko).
            estimation.text = ctx.getString(R.string.abrp_data_estimate_fmt, (3600 / AbrpStore.cadence(ctx).drivingSeconds) * 6 / 5)
        }
        fun rangee(titre: Int, valeurs: List<Int>, actuelle: Int, nom: (Int) -> String, ecrire: (Int) -> AbrpCadence) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(ctx, 64f) }
            r.addView(TextView(ctx).apply {
                text = ctx.getString(titre); textSize = 18f; setTextColor(ctx.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val seg = segmentsCadre(ctx, 440f)
            r.addView(seg)
            AppDialog.segments(seg, valeurs.map(nom), valeurs.indexOf(actuelle).coerceAtLeast(0)) { i ->
                AbrpStore.saveCadence(ctx, ecrire(valeurs[i])); maj()
            }
            box.addView(r)
        }
        val c = AbrpStore.cadence(ctx)
        fun sec(v: Int) = if (v >= 60 && v % 60 == 0) ctx.getString(R.string.rule_minutes_fmt, v / 60) else ctx.getString(R.string.rule_seconds_fmt, v)
        rangee(R.string.abrp_cadence_driving, AbrpCadence.DRIVING_CHOICES, c.drivingSeconds, ::sec) { AbrpStore.cadence(ctx).copy(drivingSeconds = it) }
        rangee(R.string.abrp_cadence_charging, AbrpCadence.CHARGING_CHOICES, c.chargingSeconds, ::sec) { AbrpStore.cadence(ctx).copy(chargingSeconds = it) }
        rangee(R.string.abrp_cadence_parked, AbrpCadence.PARKED_CHOICES, c.parkedMinutes,
            { if (it == 0) ctx.getString(R.string.abrp_cadence_parked_never) else ctx.getString(R.string.rule_minutes_fmt, it) }) {
            AbrpStore.cadence(ctx).copy(parkedMinutes = it)
        }
        maj()
        box.addView(estimation)
    }

    /** Un compte : nom, jeton, clé, données envoyées, Tester, Supprimer. [a] nul = nouveau compte. */
    /** [ajoute] : appelé après [apres] quand [a] était nul, c'est-à-dire pour un compte créé. */
    fun compte(ctx: Context, a: AbrpAccount?, ajoute: ((AbrpAccount) -> Unit)? = null, apres: () -> Unit) {
        lateinit var dialog: AlertDialog
        val base = a ?: AbrpAccount()
        var personnelle = base.apiKey.isNotBlank() || AbrpStore.appKey.isBlank()
        val coupes = base.off.toMutableSet()
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        vue.findViewById<TextView>(R.id.rule_value_title).text = ctx.getString(if (a == null) R.string.abrp_account_title_new else R.string.abrp_account_title_edit)
        vue.findViewById<TextView>(R.id.rule_value_subtitle).visibility = View.GONE
        vue.findViewById<View>(R.id.rule_value_back).visibility = View.GONE
        val corps = vue.findViewById<LinearLayout>(R.id.rule_value_body)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        corps.addView(ScrollView(ctx).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 420f))
        })

        fun champ(titre: Int, aide: String?, valeur: String, indice: String?, secret: Boolean): EditText {
            box.addView(legende(ctx, ctx.getString(titre)))
            if (aide != null) box.addView(note(ctx, aide))
            val e = EditText(ctx).apply {
                setText(valeur)
                hint = indice
                textSize = 19f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (secret) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else 0)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            box.addView(e)
            return e
        }
        val nom = champ(R.string.abrp_field_name, null, base.name, null, false)
        // Jeton existant : jamais réaffiché ; champ vide = inchangé.
        val jeton = champ(R.string.abrp_field_token, ctx.getString(R.string.abrp_field_token_hint), "",
            if (base.token.isBlank()) null else ctx.getString(R.string.abrp_token_keep_fmt, maskSecret(base.token)), true)

        box.addView(legende(ctx, ctx.getString(R.string.abrp_field_key)))
        val cle = EditText(ctx).apply {
            hint = if (base.apiKey.isBlank()) ctx.getString(R.string.abrp_field_key_hint) else ctx.getString(R.string.abrp_token_keep_fmt, maskSecret(base.apiKey))
            textSize = 19f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            visibility = if (personnelle) View.VISIBLE else View.GONE
        }
        if (AbrpStore.appKey.isNotBlank()) {
            val seg = segmentsCadre(ctx, 520f).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(ctx, 8f) }
            box.addView(seg)
            // Clé partagée (2026-11-07) : 2 envois/s au total chez ABRP, d'où ce bandeau tant qu'elle est choisie.
            val avert = bandeau(ctx, ctx.getString(R.string.abrp_key_shared_warning)).apply {
                visibility = if (personnelle) View.GONE else View.VISIBLE
            }
            box.addView(avert)
            AppDialog.segments(seg, listOf(ctx.getString(R.string.abrp_key_choice_mg4), ctx.getString(R.string.abrp_key_choice_personal)),
                if (personnelle) 1 else 0) { i ->
                personnelle = i == 1
                cle.visibility = if (personnelle) View.VISIBLE else View.GONE
                avert.visibility = if (personnelle) View.GONE else View.VISIBLE
            }
        } else {
            box.addView(note(ctx, ctx.getString(R.string.abrp_no_app_key), alerte = true))
        }
        box.addView(cle)

        box.addView(legende(ctx, ctx.getString(R.string.abrp_fields_title)))
        val puces = FlowRow(ctx)
        AbrpField.entries.filter { it != AbrpField.SOC }.forEach { f ->
            puces.addView(TextView(ctx).apply {
                text = ctx.getString(nomChamp(f))
                textSize = 17f
                setPadding(dp(ctx, 16f), dp(ctx, 10f), dp(ctx, 16f), dp(ctx, 10f))
                fun peindre() {
                    val on = f.name !in coupes
                    setBackgroundResource(if (on) R.drawable.bg_chip_active else R.drawable.bg_chip_inactive)
                    setTextColor(ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary))
                }
                peindre()
                setOnClickListener { if (!coupes.remove(f.name)) coupes.add(f.name); peindre() }
            })
        }
        box.addView(puces)

        fun lu(): AbrpAccount = base.copy(
            name = nom.text.toString().trim().take(AbrpConfigFile.MAX_NAME),
            token = jeton.text.toString().trim().ifEmpty { base.token },
            apiKey = if (personnelle) cle.text.toString().trim().ifEmpty { base.apiKey } else "",
            off = coupes.toList(),
        )
        fun erreur(a2: AbrpAccount): Int? = when {
            a2.name.isBlank() -> R.string.abrp_need_name
            a2.token.isBlank() -> R.string.abrp_need_token
            AbrpStore.keyFor(a2).isBlank() -> R.string.abrp_need_key
            else -> null
        }

        val resultat = note(ctx, "")
        resultat.visibility = View.GONE
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 14f), 0, 0) }
        actions.addView(tuile(ctx, ctx.getString(R.string.abrp_test), true) {
            val l = lu()
            erreur(l)?.let { return@tuile Toast.makeText(ctx, ctx.getString(it), Toast.LENGTH_LONG).show() }
            resultat.visibility = View.VISIBLE
            resultat.text = ctx.getString(R.string.abrp_testing)
            AbrpStream.check(l) { code, raison ->
                resultat.text = when {
                    code == 200 -> ctx.getString(R.string.abrp_test_ok)
                    code < 0 -> ctx.getString(R.string.abrp_test_network)
                    else -> ctx.getString(R.string.abrp_test_fail_fmt, "HTTP $code" + if (raison.isBlank()) "" else " · $raison")
                }
            }
        })
        if (a != null) actions.addView(tuile(ctx, ctx.getString(R.string.abrp_delete), true, danger = true) {
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_delete_confirm_fmt, a.name), ton = AppDialog.Ton.DANGER,
                oui = ctx.getString(R.string.abrp_delete), non = ctx.getString(R.string.profile_cancel), onOui = {
                    if (AbrpStream.status.running && AbrpStream.status.accountId == a.id) AbrpStream.stop(ctx, "compte supprimé")
                    AbrpStore.delete(ctx, a.id)
                    dialog.dismiss()
                    apres()
                })
        })
        box.addView(actions)
        box.addView(resultat)

        vue.findViewById<TextView>(R.id.rule_value_cancel).apply {
            text = ctx.getString(R.string.profile_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        vue.findViewById<TextView>(R.id.rule_value_ok).apply {
            text = ctx.getString(R.string.rule_save)
            setOnClickListener {
                val l = lu()
                erreur(l)?.let { return@setOnClickListener Toast.makeText(ctx, ctx.getString(it), Toast.LENGTH_LONG).show() }
                if (!AbrpStore.save(ctx, l)) return@setOnClickListener Toast.makeText(ctx, ctx.getString(R.string.abrp_full_fmt, AbrpStore.MAX_ACCOUNTS), Toast.LENGTH_LONG).show()
                // Compte en cours d'envoi : le relevé suivant part avec les nouveaux réglages.
                if (AbrpStream.status.running && AbrpStream.status.accountId == l.id) AbrpStream.start(ctx, l.id)
                dialog.dismiss()
                apres()
                if (a == null) ajoute?.invoke(l)
            }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
        AppDialog.saisie(dialog, vue)
    }

    /**
     * Import du fichier de comptes : un compte de même nom (sans casse) est mis à jour, un nouveau
     * est ajouté s'il reste de la place. Le jeton n'est ni affiché ni journalisé.
     */
    /** [nouveaux] : comptes AJOUTÉS par l'import (pas ceux mis à jour) ; appelé à la place de [apres] s'il y en a. */
    fun importer(ctx: Context, texte: String?, ou: String, nouveaux: (List<AbrpAccount>) -> Unit = {}, apres: () -> Unit) {
        // Un compte avec jeton, ou une clé seule pour un compte qui existe déjà (configuration rapide).
        val existants = AbrpStore.accounts(ctx)
        val lus = texte?.let { AbrpConfigFile.parse(it) }.orEmpty().filter { e ->
            !e.token.isNullOrBlank() || (e.apiKey != null && existants.any { it.name.equals(e.name, ignoreCase = true) })
        }
        if (lus.isEmpty()) {
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.abrp_title), message = ctx.getString(R.string.abrp_import_none_fmt, ou),
                ton = AppDialog.Ton.ALERTE, oui = ctx.getString(R.string.nav_close))
            return
        }
        val faire = {
            var n = 0
            val ajoutes = mutableListOf<AbrpAccount>()
            lus.forEach { e ->
                val deja = AbrpStore.accounts(ctx).firstOrNull { it.name.equals(e.name, ignoreCase = true) }
                val c = (deja ?: AbrpAccount(name = e.name)).copy(
                    token = e.token?.takeIf { it.isNotBlank() } ?: deja?.token.orEmpty(),
                    // null = choix gardé ; « » = clé MG4Control ; sinon la clé personnelle.
                    apiKey = e.apiKey ?: deja?.apiKey.orEmpty())
                if (AbrpStore.save(ctx, c)) { n++; if (deja == null) ajoutes += c }
            }
            Toast.makeText(ctx, ctx.getString(R.string.abrp_import_done_fmt, n), Toast.LENGTH_LONG).show()
            if (ajoutes.isNotEmpty()) nouveaux(ajoutes) else apres()
        }
        if (AbrpStore.consent(ctx)) faire()
        else AppDialog.confirmer(ctx, titre = ctx.getString(R.string.abrp_consent_title), message = ctx.getString(R.string.abrp_consent_msg),
            ton = AppDialog.Ton.ALERTE, oui = ctx.getString(R.string.abrp_consent_ok), non = ctx.getString(R.string.profile_cancel),
            onOui = { AbrpStore.setConsent(ctx); faire() })
    }

    /** Le fichier modèle : les comptes existants (sans jeton), ou un compte « ABRP » à remplir. */
    fun modele(ctx: Context): String {
        // Les explications du fichier dans la langue choisie dans MG4Control (2026-11-07), quel que
        // soit le contexte qui demande le fichier. Les mots-clés (token, api_key) restent fixes :
        // un fichier rempli dans une langue s'importe dans toutes les autres.
        val l = com.mg4.control.util.LocaleHelper.applyLocale(ctx)
        return AbrpConfigFile.template(
            listOf(R.string.abrp_template_comment1, R.string.abrp_template_comment2, R.string.abrp_template_comment3,
                R.string.abrp_template_comment4, R.string.abrp_template_comment5).map { l.getString(it) },
            AbrpStore.accounts(ctx).map { it.name })
    }

    private fun nomChamp(f: AbrpField): Int = when (f) {
        AbrpField.SOC -> R.string.abrp_f_soc
        AbrpField.POWER -> R.string.abrp_f_power
        AbrpField.SPEED -> R.string.abrp_f_speed
        AbrpField.CHARGING -> R.string.abrp_f_charging
        AbrpField.PARKED -> R.string.abrp_f_parked
        AbrpField.OUTSIDE_TEMP -> R.string.abrp_f_outside_temp
        AbrpField.RANGE -> R.string.abrp_f_range
        AbrpField.ODOMETER -> R.string.abrp_f_odometer
        AbrpField.CAPACITY -> R.string.abrp_f_capacity
        AbrpField.HVAC -> R.string.abrp_f_hvac
        AbrpField.POSITION -> R.string.abrp_f_position
        AbrpField.ALTITUDE_HEADING -> R.string.abrp_f_altitude
    }

    // ── Petites briques ─────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Float) = AppDialog.dp(ctx, v)

    private fun legende(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        textSize = 18f
        setTextColor(ctx.getColor(R.color.text_primary))
        setPadding(0, dp(ctx, 14f), 0, dp(ctx, 4f))
    }

    /** Bandeau d'avertissement orange, comme dans la maquette validée (variante A). */
    private fun bandeau(ctx: Context, t: String) = TextView(ctx).apply {
        text = "⚠  $t"
        textSize = 16f
        setTextColor(ctx.getColor(R.color.dash_warn))
        val p = dp(ctx, 12f)
        setPadding(p, dp(ctx, 10f), p, dp(ctx, 10f))
        com.mg4.control.service.OverlayKit.fond(this, ctx.getColor(R.color.dash_warn_dim), rayon = dp(ctx, 12f).toFloat())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(ctx, 8f) }
    }

    private fun note(ctx: Context, t: String, alerte: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = 16f
        setTextColor(ctx.getColor(if (alerte) R.color.dash_warn else R.color.text_secondary))
        setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
    }

    /** Une ligne de liste : titre, détail, et un bouton à droite. */
    private fun ligne(ctx: Context, titre: String, detail: String, bouton: String, accent: Boolean = false, onClic: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 72f)
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = titre; textSize = 19f
                    setTextColor(ctx.getColor(if (accent) R.color.dash_accent else R.color.text_primary))
                })
                addView(TextView(ctx).apply { text = detail; textSize = 16f; setTextColor(ctx.getColor(R.color.text_secondary)) })
            })
            addView(tuile(ctx, bouton, true, onClic = onClic))
        }

    private fun tuile(ctx: Context, t: String, actif: Boolean, danger: Boolean = false, onClic: () -> Unit) = TextView(ctx).apply {
        text = t
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(ctx.getColor(if (danger) R.color.dash_danger else R.color.text_primary))
        setBackgroundResource(R.drawable.bg_dialog_tile)
        setPadding(dp(ctx, 18f), 0, dp(ctx, 18f), 0)
        alpha = if (actif) 1f else 0.4f
        isEnabled = actif
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 56f)).also { it.marginEnd = dp(ctx, 10f) }
        setOnClickListener { onClic() }
    }

    /** Le cadre d'un choix à segments, à la taille de ceux de l'éditeur (60 dp de haut). */
    private fun segmentsCadre(ctx: Context, largeurDp: Float) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundResource(R.drawable.bg_dialog_segments)
        val p = dp(ctx, 4f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, largeurDp), dp(ctx, 60f))
    }
}
