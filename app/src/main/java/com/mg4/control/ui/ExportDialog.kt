package com.mg4.control.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import com.mg4.control.R
import com.mg4.control.service.OverlayKit
import com.mg4.control.stats.StatsExport
import com.mg4.control.stats.StatsExport.Destination

/**
 * Fenêtre « où enregistrer ? » : trois tuiles de destination — Téléchargements (défaut), Clé USB
 * (seulement branchée), Autre dossier… — puis l'écriture par [StatsExport]. Née pour l'export des
 * Statistiques (issue #206, maquette B du 2026-11-03), reprise par le diagnostic (« Enregistrer… »,
 * maquette B du même jour). Le dernier choix est commun aux deux.
 *
 * [fichiers] (nom, contenu) est appelé sur un fil de travail, au moment d'écrire.
 * « Autre dossier… » ouvre le sélecteur d'Android par [parcourir] ; le dossier revient par
 * [surDossier]. Toucher la tuile déjà choisie relance le sélecteur pour en changer.
 */
internal class ExportDialog(
    private val ctx: Context,
    private val titre: String,
    private val sousTitre: String,
    private val bouton: String,
    private val titreFait: String,
    private val fichiers: () -> List<Pair<String, String>>,
    private val parcourir: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var tuiles: LinearLayout? = null
    private var usb: StatsExport.Usb? = null
    private var choix = Destination.DOWNLOADS
    private var enCours = false

    private fun s(id: Int, vararg a: Any): String = if (a.isEmpty()) ctx.getString(id) else ctx.getString(id, *a)
    private fun dp(v: Float) = OverlayKit.dp(ctx, v)

    fun ouvrir() {
        usb = StatsExport.usb(ctx)
        choix = StatsExport.destination(ctx, usb)
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        vue.findViewById<TextView>(R.id.rule_value_title).text = titre
        vue.findViewById<TextView>(R.id.rule_value_subtitle).apply {
            text = sousTitre
            visibility = View.VISIBLE
        }
        val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        vue.findViewById<LinearLayout>(R.id.rule_value_body).addView(rangee,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tuiles = rangee
        peindre()
        vue.findViewById<TextView>(R.id.rule_value_cancel).apply {
            text = s(R.string.profile_cancel)
            setOnClickListener { dialog?.dismiss() }
        }
        vue.findViewById<TextView>(R.id.rule_value_ok).apply {
            text = bouton
            setOnClickListener { exporter() }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP).also { it.setOnDismissListener { dialog = null } }
    }

    /** Dossier rendu par le sélecteur (null : rien choisi, le choix d'avant reste). */
    fun surDossier(arbre: Uri?) {
        if (arbre == null) return
        StatsExport.retenirDossier(ctx, arbre)
        choix = Destination.FOLDER
        peindre()
    }

    private fun peindre() {
        val rangee = tuiles ?: return
        rangee.removeAllViews()
        val dossier = StatsExport.dossierChoisi(ctx)
        val liste = buildList {
            add(Triple(Destination.DOWNLOADS, R.drawable.ic_dialog_download,
                s(R.string.stats_export_downloads) to s(R.string.stats_export_default)))
            usb?.let { add(Triple(Destination.USB, R.drawable.ic_source_usb,
                s(R.string.stats_export_usb) to s(R.string.stats_export_usb_free, it.nom, Formatter.formatShortFileSize(ctx, it.libreOctets)))) }
            add(Triple(Destination.FOLDER, R.drawable.ic_export_folder,
                s(R.string.stats_export_other) to (dossier?.let { StatsExport.libelleDossier(it) } ?: s(R.string.stats_export_browse))))
        }
        liste.forEachIndexed { i, (d, icone, textes) ->
            rangee.addView(tuile(d, icone, textes.first, textes.second),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also { if (i > 0) it.marginStart = dp(12f) })
        }
    }

    private fun tuile(d: Destination, icone: Int, titre: String, detail: String): View {
        val on = d == choix
        val couleur = ctx.getColor(if (on) R.color.dash_accent else R.color.text_secondary)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            OverlayKit.fond(this, ctx.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn),
                ctx.getColor(if (on) R.color.dash_accent else R.color.dash_border), dp(if (on) 2f else 1f), dp(16f).toFloat())
            addView(ImageView(ctx).apply {
                setImageResource(icone)
                imageTintList = ColorStateList.valueOf(couleur)
            }, LinearLayout.LayoutParams(dp(30f), dp(30f)))
            addView(TextView(ctx).apply {
                text = titre
                setTextColor(ctx.getColor(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8f), 0, 0)
            })
            addView(TextView(ctx).apply {
                text = detail
                setTextColor(ctx.getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.START
            })
            setOnClickListener { toucher(d) }
        }
    }

    private fun toucher(d: Destination) {
        if (d == Destination.FOLDER && (choix == Destination.FOLDER || StatsExport.dossierChoisi(ctx) == null)) {
            try {
                parcourir()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, s(R.string.stats_export_no_picker), Toast.LENGTH_LONG).show()
            }
            return
        }
        choix = d
        peindre()
    }

    private fun exporter() {
        if (enCours) return
        enCours = true
        val d = choix
        StatsExport.retenir(ctx, d)
        Thread {
            val r = StatsExport.exporter(ctx, d, fichiers())
            main.post {
                enCours = false
                dialog?.dismiss()
                montrer(r, d)
            }
        }.start()
    }

    private fun montrer(r: StatsExport.Resultat, d: Destination) {
        when (r) {
            is StatsExport.Resultat.Ok -> AppDialog.confirmer(ctx,
                titre = titreFait,
                message = s(R.string.stats_export_done_text, r.ou, r.fichiers.joinToString("\n")),
                icone = R.drawable.ic_dialog_download,
                oui = s(R.string.nav_close))
            is StatsExport.Resultat.Echec -> AppDialog.confirmer(ctx,
                titre = titre,
                message = s(R.string.stats_export_failed, r.raison) +
                    if (d == Destination.USB) "\n\n" + s(R.string.stats_export_failed_hint) else "",
                ton = AppDialog.Ton.ALERTE,
                oui = s(R.string.nav_close))
        }
    }

    private companion object {
        const val LARGEUR_DP = 900f
    }
}
