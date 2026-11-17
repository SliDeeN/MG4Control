package com.mg4.control.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.service.OverlayKit
import com.mg4.control.stats.StatsExport
import com.mg4.control.stats.StatsExport.Destination

/**
 * Fenêtre « où est le fichier ? » de l'import des automatisations (2026-11-05), le pendant de
 * [ExportDialog] : Téléchargements, clé USB (seulement branchée) — le fichier [nom] y est cherché
 * dans « MG4Control » —, ou « Autre fichier… », le sélecteur d'Android ([parcourir], réponse par
 * [surFichier]). [lu] reçoit le texte lu (null s'il n'est pas trouvé) et l'endroit, à afficher.
 */
internal class ImportDialog(
    private val ctx: Context,
    private val nom: String,
    private val parcourir: () -> Unit,
    private val lu: (String?, String) -> Unit,
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
        // Clé branchée : c'est sans doute de là que vient le fichier.
        choix = if (usb != null) Destination.USB else Destination.DOWNLOADS
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        vue.findViewById<TextView>(R.id.rule_value_title).text = s(R.string.rule_import_title_from)
        vue.findViewById<TextView>(R.id.rule_value_subtitle).apply {
            text = s(R.string.rule_import_subtitle)
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
            text = s(R.string.rule_tool_import)
            setOnClickListener { lire() }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP).also { it.setOnDismissListener { dialog = null } }
    }

    /** Fichier rendu par le sélecteur : lu tout de suite. */
    fun surFichier(doc: Uri?) {
        if (doc == null) return
        dialog?.dismiss()
        Thread {
            val texte = StatsExport.lireUri(ctx, doc)
            main.post { lu(texte, doc.lastPathSegment ?: doc.toString()) }
        }.start()
    }

    private fun peindre() {
        val rangee = tuiles ?: return
        rangee.removeAllViews()
        val liste = buildList {
            add(Triple(Destination.DOWNLOADS, R.drawable.ic_dialog_download,
                s(R.string.stats_export_downloads) to "Download/${StatsExport.DOSSIER}"))
            usb?.let { add(Triple(Destination.USB, R.drawable.ic_source_usb,
                s(R.string.stats_export_usb) to "${it.nom}/${StatsExport.DOSSIER}")) }
            add(Triple(Destination.FOLDER, R.drawable.ic_export_folder,
                s(R.string.rule_import_other) to s(R.string.rule_import_pick)))
        }
        liste.forEachIndexed { i, (d, icone, textes) ->
            rangee.addView(tuileDestination(ctx, d == choix, icone, textes.first, textes.second) { toucher(d) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also { if (i > 0) it.marginStart = dp(12f) })
        }
    }

    /** « Autre fichier… » ouvre directement le sélecteur : il n'y a rien à préchoisir. */
    private fun toucher(d: Destination) {
        if (d == Destination.FOLDER) {
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

    private fun lire() {
        if (enCours) return
        if (choix == Destination.FOLDER) { toucher(choix); return }
        enCours = true
        val d = choix
        val ou = if (d == Destination.USB) "${usb?.nom ?: "USB"}/${StatsExport.DOSSIER}/$nom" else "Download/${StatsExport.DOSSIER}/$nom"
        Thread {
            val texte = StatsExport.lire(ctx, d, nom)
            main.post {
                enCours = false
                dialog?.dismiss()
                lu(texte, ou)
            }
        }.start()
    }

    private companion object {
        const val LARGEUR_DP = 900f
    }
}
