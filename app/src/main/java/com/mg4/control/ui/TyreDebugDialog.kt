package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.TyreProbe
import com.mg4.control.service.OverlayKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fenêtre « Pneus » (Réglages, débloquée avec le Diagnostic — 2026-11-20).
 *
 * Les quatre roues autour de la voiture, relues chaque seconde : la pression brute en grand, la
 * température et l'état dessous, puis ce que rend chacune des trois voies de lecture. Rien n'est
 * converti : c'est en comparant au combiné, pneus froids puis chauds, qu'on fixera l'unité et
 * l'échelle. L'ouverture ajoute un relevé complet au Diagnostic ; chaque changement est noté dans
 * le journal.
 *
 * Lecture seule.
 */
object TyreDebugDialog {

    /** La pression bouge lentement, mais on veut voir tout de suite si une voie se réveille. */
    private const val CADENCE_MS = 1_000L

    internal class Carte(
        val nom: TextView,
        val valeur: TextView,
        val temp: TextView,
        val etat: TextView,
        val detail: TextView,
    )

    /** Les vues à repeindre à chaque relevé. */
    internal class Vues(val cartes: Map<TyreProbe.Roue, Carte>, val unite: TextView, val heure: TextView)

    /** Gonfle et prépare la fenêtre, sans l'ouvrir : séparé pour les tests de mise en page. */
    internal fun preparer(ctx: Context): Pair<View, Vues> {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_tyre_debug, null)
        OverlayKit.fond(vue.findViewById(R.id.tyre_badge), ctx.getColor(R.color.dash_accent_dim), ovale = true)
        val accent = ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.tyre_icon).imageTintList = accent
        vue.findViewById<ImageView>(R.id.tyre_car).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.text_secondary))
        vue.findViewById<ImageView>(R.id.tyre_close).imageTintList =
            ColorStateList.valueOf(ctx.getColor(R.color.text_primary))

        val cartes = mapOf(
            TyreProbe.Roue.AVANT_GAUCHE to carte(vue, R.id.tyre_fl, ctx.getString(R.string.win_front_left)),
            TyreProbe.Roue.AVANT_DROITE to carte(vue, R.id.tyre_fr, ctx.getString(R.string.win_front_right)),
            TyreProbe.Roue.ARRIERE_GAUCHE to carte(vue, R.id.tyre_rl, ctx.getString(R.string.win_rear_left)),
            TyreProbe.Roue.ARRIERE_DROITE to carte(vue, R.id.tyre_rr, ctx.getString(R.string.win_rear_right)),
        )
        return vue to Vues(cartes, vue.findViewById(R.id.tyre_unit), vue.findViewById(R.id.tyre_time))
    }

    fun afficher(ctx: Context) {
        val (vue, vues) = preparer(ctx)
        val dialog = AppDialog.afficher(ctx, vue, 1240f)
        vue.findViewById<View>(R.id.tyre_close).setOnClickListener { dialog.dismiss() }

        // Lectures binder sur un fil à elles, arrêté avec la fenêtre.
        val fil = HandlerThread("mg4-tyre").also { it.start() }
        val lecteur = Handler(fil.looper)
        val main = Handler(Looper.getMainLooper())
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US)
        var precedent: TyreProbe.Releve? = null

        val releve = object : Runnable {
            override fun run() {
                val r = runCatching { TyreProbe.lire() }.getOrNull()
                // Premier relevé : complet, gardé pour le Diagnostic.
                if (r != null && precedent == null) TyreProbe.run("fenêtre Pneus", r)
                if (r != null) {
                    precedent?.let { avant ->
                        val ch = TyreProbe.changements(avant, r)
                        if (ch.isNotEmpty()) AppLogger.i(TyreProbe.TAG, "Pneus : $ch")
                    }
                    precedent = r
                }
                val quand = heure.format(Date())
                main.post {
                    if (dialog.isShowing) montrer(ctx, r, vues)
                    vues.heure.text = quand
                }
                lecteur.postDelayed(this, CADENCE_MS)
            }
        }
        lecteur.post(releve)
        dialog.setOnDismissListener {
            lecteur.removeCallbacksAndMessages(null)
            fil.quitSafely()
            AppLogger.i(TyreProbe.TAG, "Pneus : fenêtre fermée")
        }
    }

    private fun carte(vue: View, id: Int, nom: String): Carte {
        val c = vue.findViewById<View>(id)
        return Carte(
            nom = c.findViewById<TextView>(R.id.tyre_name).also { it.text = nom },
            valeur = c.findViewById(R.id.tyre_value),
            temp = c.findViewById(R.id.tyre_temp),
            etat = c.findViewById(R.id.tyre_state),
            detail = c.findViewById(R.id.tyre_detail),
        )
    }

    internal fun montrer(ctx: Context, r: TyreProbe.Releve?, vues: Vues) {
        vues.unite.text = ctx.getString(R.string.tyre_debug_unit, nombre(r?.unite?.valeur))
        vues.cartes.forEach { (roue, c) ->
            val w = r?.roues?.firstOrNull { it.roue == roue }
            c.valeur.text = nombre(w?.pression?.valeur)
            c.temp.text = ctx.getString(R.string.tyre_debug_temp, nombre(w?.temperature?.valeur))

            // D'après la voix SAIC : 0 ou 1 = normal, au-dessus = alerte (sens exact à relever).
            val etat = w?.etat?.valeur?.toInt()
            val (texte, fond) = when {
                etat == null -> R.color.dash_text_lo to R.color.dash_section
                etat <= 1 -> R.color.dash_eco to R.color.dash_eco_dim
                else -> R.color.dash_warn to R.color.dash_warn_dim
            }
            c.etat.text = ctx.getString(R.string.tyre_debug_state, nombre(etat))
            c.etat.setTextColor(ctx.getColor(texte))
            // Pastille : un rayon plus grand que la moitié de la hauteur arrondit les bouts.
            OverlayKit.fond(c.etat, ctx.getColor(fond), rayon = 200f)

            // Noms techniques, à recopier tels quels : pas de traduction.
            c.detail.text = if (w == null) "—" else buildString {
                append("P ").append(voies(w.pression))
                append("\nT ").append(voies(w.temperature))
                append("\nE ").append(voies(w.etat))
                append("\nstd ").append(court(w.standard))
            }
        }
    }

    private fun voies(v: TyreProbe.Voies) =
        "cpm ${court(v.global)} · @0 ${court(v.zero)} · cap ${court(v.capteur)}"

    /** Une voie en peu de place : le nombre, ou le nom de l'échec raccourci. */
    private fun court(l: TyreProbe.Lu): String = l.nombre?.let { nombre(it) }
        ?: l.texte.substringBefore(' ').removeSuffix("Exception").take(12)

    /** Point décimal quelle que soit la langue (voir le piège de Locale.ROOT). */
    private fun nombre(n: Number?): String = when (n) {
        null -> "–"
        is Float, is Double -> String.format(Locale.US, "%.1f", n.toDouble())
        else -> n.toString()
    }
}
