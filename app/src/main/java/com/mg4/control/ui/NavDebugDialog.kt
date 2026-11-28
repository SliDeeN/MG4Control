/*
 * MG4Control — fenêtre « Guidage AA / CarPlay » (issue #285).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.NavGuidanceProbe
import com.mg4.control.hardware.ProjectionReader
import com.mg4.control.model.NavGuidanceText
import com.mg4.control.service.OverlayKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fenêtre « Guidage AA / CarPlay » (Réglages, débloquée avec le Diagnostic — 2026-11-28, issue #285).
 *
 * Ce que la sonde [NavGuidanceProbe] a reçu d'AllGo, relu chaque seconde : abonnement, session en
 * cours (même question que le raccourci Android Auto / CarPlay), dernier guidage (flèche du
 * téléphone et textes reconnus), compteurs, rappels vus et données brutes du dernier message.
 * Lecture seule : rien n'est envoyé à AllGo ni à la voiture.
 */
object NavDebugDialog {

    private const val CADENCE_MS = 1_000L
    /** Au-delà, le guidage n'est plus « en cours » : la pastille repasse au neutre. */
    private const val FRAIS_MS = 30_000L
    /** Service injoignable : on ne repose la question que toutes les 10 s (le journal s'en souvient). */
    private const val REESSAI_SESSION = 10

    /** Les vues à repeindre à chaque relevé. */
    internal class Vues(
        val service: TextView,
        val session: TextView,
        val guidage: TextView,
        val fleche: ImageView,
        val grand: TextView,
        val ligne1: TextView,
        val ligne2: TextView,
        val detail: TextView,
        val compteurs: TextView,
        val rappels: TextView,
        val brut: TextView,
        val heure: TextView,
    )

    /** Gonfle et prépare la fenêtre, sans l'ouvrir : séparé pour les tests de mise en page. */
    internal fun preparer(ctx: Context): Pair<View, Vues> {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_nav_debug, null)
        OverlayKit.fond(vue.findViewById(R.id.nav_badge), ctx.getColor(R.color.dash_accent_dim), ovale = true)
        vue.findViewById<ImageView>(R.id.nav_icon).imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.nav_close).imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_primary))
        // La flèche d'Android Auto est dessinée blanche sur transparent : fond sombre fixe.
        OverlayKit.fond(vue.findViewById(R.id.nav_arrow), 0xFF11151A.toInt(), rayon = 10f * ctx.resources.displayMetrics.density)
        return vue to Vues(
            service = vue.findViewById(R.id.nav_chip_service),
            session = vue.findViewById(R.id.nav_chip_session),
            guidage = vue.findViewById(R.id.nav_chip_guidance),
            fleche = vue.findViewById(R.id.nav_arrow),
            grand = vue.findViewById(R.id.nav_big),
            ligne1 = vue.findViewById(R.id.nav_line1),
            ligne2 = vue.findViewById(R.id.nav_line2),
            detail = vue.findViewById(R.id.nav_detail),
            compteurs = vue.findViewById(R.id.nav_counts),
            rappels = vue.findViewById(R.id.nav_callbacks),
            brut = vue.findViewById(R.id.nav_raw),
            heure = vue.findViewById(R.id.nav_time),
        )
    }

    fun afficher(ctx: Context) {
        val (vue, vues) = preparer(ctx)
        val dialog = AppDialog.afficher(ctx, vue, 1240f)
        vue.findViewById<View>(R.id.nav_close).setOnClickListener { dialog.dismiss() }

        // Questions binder sur un fil à elles, arrêté avec la fenêtre.
        val fil = HandlerThread("mg4-navdebug").also { it.start() }
        val lecteur = Handler(fil.looper)
        val main = Handler(Looper.getMainLooper())
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US)
        var session: ProjectionReader.Lecture? = null
        var tour = 0
        AppLogger.i(NavGuidanceProbe.TAG, "Guidage AA / CarPlay : fenêtre ouverte")

        val releve = object : Runnable {
            override fun run() {
                if (session !is ProjectionReader.Lecture.Injoignable || tour % REESSAI_SESSION == 0) {
                    session = runCatching { ProjectionReader.appareilActif(ctx) }.getOrDefault(ProjectionReader.Lecture.Injoignable)
                }
                tour++
                val etat = NavGuidanceProbe.etat()
                val s = session
                val maintenant = SystemClock.elapsedRealtime()
                val quand = heure.format(Date())
                main.post {
                    if (dialog.isShowing) montrer(ctx, etat, s, maintenant, vues)
                    vues.heure.text = quand
                }
                lecteur.postDelayed(this, CADENCE_MS)
            }
        }
        lecteur.post(releve)
        dialog.setOnDismissListener {
            lecteur.removeCallbacksAndMessages(null)
            fil.quitSafely()
            AppLogger.i(NavGuidanceProbe.TAG, "Guidage AA / CarPlay : fenêtre fermée")
        }
    }

    private enum class Ton { OK, ATTENTE, KO, NEUTRE }

    private fun pastille(ctx: Context, t: TextView, texte: String, ton: Ton) {
        val (couleur, fond) = when (ton) {
            Ton.OK -> R.color.dash_eco to R.color.dash_eco_dim
            Ton.ATTENTE -> R.color.dash_warn to R.color.dash_warn_dim
            Ton.KO -> R.color.dash_danger to R.color.dash_danger_dim
            Ton.NEUTRE -> R.color.dash_text_lo to R.color.dash_section
        }
        t.text = texte
        t.setTextColor(ctx.getColor(couleur))
        // Un rayon plus grand que la moitié de la hauteur arrondit les bouts.
        OverlayKit.fond(t, ctx.getColor(fond), rayon = 200f)
    }

    /** [maintenant] en `elapsedRealtime`, comme [NavGuidanceProbe.Etat.guidageMs]. */
    internal fun montrer(ctx: Context, e: NavGuidanceProbe.Etat, session: ProjectionReader.Lecture?, maintenant: Long, vues: Vues) {
        when (e.abonnement) {
            NavGuidanceProbe.Abonnement.ABONNE -> pastille(ctx, vues.service, ctx.getString(R.string.nav_debug_service_ok), Ton.OK)
            NavGuidanceProbe.Abonnement.EN_COURS -> pastille(ctx, vues.service, ctx.getString(R.string.nav_debug_service_wait), Ton.ATTENTE)
            NavGuidanceProbe.Abonnement.ECHEC -> pastille(ctx, vues.service, ctx.getString(R.string.nav_debug_service_ko), Ton.KO)
        }

        val appareil = (session as? ProjectionReader.Lecture.Lue)?.appareil
        val sessionOuverte = appareil != null
        when {
            appareil != null -> pastille(ctx, vues.session,
                ctx.getString(R.string.nav_debug_session, appareil.kind?.label ?: "type ${appareil.type}"), Ton.OK)
            session is ProjectionReader.Lecture.Lue -> pastille(ctx, vues.session, ctx.getString(R.string.nav_debug_session_none), Ton.KO)
            else -> pastille(ctx, vues.session, ctx.getString(R.string.nav_debug_session_unknown), Ton.NEUTRE)
        }

        val t = e.guidageMs
        when {
            t != null -> pastille(ctx, vues.guidage, ctx.getString(R.string.nav_debug_guidance_age, NavGuidanceText.age(maintenant - t)),
                if (maintenant - t <= FRAIS_MS) Ton.OK else Ton.NEUTRE)
            sessionOuverte -> pastille(ctx, vues.guidage, ctx.getString(R.string.nav_debug_guidance_wait), Ton.ATTENTE)
            else -> pastille(ctx, vues.guidage, ctx.getString(R.string.nav_debug_guidance_none), Ton.NEUTRE)
        }

        // Dernier guidage.
        vues.fleche.setImageBitmap(e.image)
        vues.fleche.visibility = if (e.image != null) View.VISIBLE else View.GONE
        val r = e.resume
        if (r == null || r.vide) {
            vues.grand.text = null
            vues.grand.visibility = View.GONE
            vues.ligne1.text = ctx.getString(when {
                r != null -> R.string.nav_debug_last_unknown
                sessionOuverte -> R.string.nav_debug_last_wait
                else -> R.string.nav_debug_last_empty
            })
            vues.ligne1.setTextColor(ctx.getColor(R.color.text_secondary))
            vues.ligne2.visibility = View.GONE
        } else {
            vues.grand.visibility = if (r.grand != null) View.VISIBLE else View.GONE
            vues.grand.text = r.grand
            vues.ligne1.text = r.ligne1
            vues.ligne1.setTextColor(ctx.getColor(R.color.text_primary))
            vues.ligne2.text = r.ligne2
            vues.ligne2.visibility = if (r.ligne2 != null) View.VISIBLE else View.GONE
        }
        vues.ligne1.visibility = if (vues.ligne1.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        vues.detail.text = e.detail
        vues.detail.visibility = if (e.detail != null) View.VISIBLE else View.GONE

        vues.compteurs.text = listOf(
            "Android Auto" to e.aa,
            "CarPlay" to e.carplay,
            ctx.getString(R.string.nav_debug_other) to e.autres,
        ).joinToString("\n") { (nom, n) -> "$nom : $n" }
        vues.rappels.text = e.rappels.joinToString(" · ").ifEmpty { "—" }
        vues.brut.text = e.brut ?: "—"
    }
}
