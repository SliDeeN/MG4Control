package com.mg4.control.service

import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeType
import com.mg4.control.model.PriceStepper
import com.mg4.control.model.StatsTracker
import com.mg4.control.ui.AppDialog
import com.mg4.control.ui.widget.ChargeCurveView
import com.mg4.control.ui.widget.ChargeLimitSliderView
import com.mg4.control.util.ChargePopupSettings
import com.mg4.control.hardware.BatteryTypeConfig
import com.mg4.control.model.ChargeLimitSupport
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.LocaleHelper
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pop-up de charge (maquette A, 2026-10-16) : par-dessus n'importe quel écran pendant une charge.
 * À gauche la batterie et le curseur de la limite de charge, au centre la courbe depuis le début
 * de la charge, à droite puissance, énergie, autonomie gagnée, coût (prix du kWh modifiable pour
 * cette charge) et durée.
 *
 * S'ouvre à chaque début de charge ([ChargeMonitor]), se ferme à la demande ou au passage en READY,
 * jamais tout seul. Le raccourci « Pop-up de charge » le rouvre ([toggle]). Fil principal seulement.
 */
object ChargePopupOverlay {

    private const val TAG = ChargeMonitor.TAG
    /** Carte de base de overlay_charge_popup.xml, agrandie comme le pop-up HVAC. */
    private const val LARGEUR_DP = 1140f
    private const val HAUTEUR_DP = 400f
    private const val MARGE_FENETRE_DP = 12f
    private const val PAS_PAR_DEFAUT = 1
    /** Limite non réglable : la charge va au plein. */
    private const val PLEIN_PCT = 100

    private val handler = Handler(Looper.getMainLooper())

    /** Lu aussi par le fil du suivi ([ChargeMonitor]) pour choisir sa cadence. */
    @Volatile private var vue: View? = null
    private var gestionnaire: WindowManager? = null
    private var vues: Vues? = null
    private var dernier: ChargeMonitor.Etat? = null

    // Éditeur du prix : ouvert ou non, valeur en cours, pas choisi.
    private var prixEdite: Float? = null
    private var pas: List<Float> = emptyList()
    private var rangPas = PAS_PAR_DEFAUT

    /** Pendant le glissement du curseur : le cran visé (le texte d'aide en parle). */
    private var vise: Int? = null

    fun isShowing(): Boolean = vue != null

    /** Raccourci : ouvre le pop-up, ou le referme s'il est déjà à l'écran. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (vue != null) {
                fermer("second appui du raccourci")
                return@post
            }
            val texte = when {
                !ChargePopupSettings.isEnabled(app) -> R.string.charge_popup_disabled
                ChargeMonitor.etat == null -> R.string.charge_popup_none
                else -> null
            }
            if (texte != null) {
                AppLogger.i(TAG, "Pop-up de charge — raccourci sans effet (" +
                    (if (texte == R.string.charge_popup_disabled) "option coupée" else "aucune charge suivie") + ")")
                Toast.makeText(app, LocaleHelper.applyLocale(app).getString(texte), Toast.LENGTH_SHORT).show()
                return@post
            }
            ouvrir(app, "raccourci")
        }
    }

    /** Ouvre le pop-up s'il ne l'est pas. Fil principal. */
    fun ouvrir(context: Context, raison: String) {
        if (vue != null) return
        val c = OverlayKit.contextes(context, LARGEUR_DP, HAUTEUR_DP)
        val (racine, v) = try {
            val (gonflee, liees) = preparer(c.themed, c.localized, ChargeMonitor.etat)
            OverlayKit.tenirDansLaFenetre(gonflee, (MARGE_FENETRE_DP * c.mesures.density).roundToInt(), "Pop-up de charge")
            gonflee to liees
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pop-up de charge non affiché — mise en page : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(racine, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pop-up de charge non affiché — fenêtre : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        vue = racine
        gestionnaire = wm
        vues = v
        ChargeMonitor.ecouteur = { e -> vues?.let { peindre(it, e) } }
        ChargeMonitor.accelerer()
        AppLogger.i(TAG, "Pop-up de charge affiché ($raison) — échelle ×${String.format(Locale.US, "%.2f", c.echelle)}")
    }

    /**
     * Gonfle, branche et peint le pop-up sans le poser à l'écran. Séparé pour les tests : une
     * erreur ici passe la compilation et ne se voit qu'en voiture (texte mal formé, vue absente).
     */
    internal fun preparer(
        themed: Context, localized: Context, etat: ChargeMonitor.Etat?,
        a9: Boolean = FirmwareInfo.isA9(), codeBatterie: Int? = if (a9) BatteryTypeConfig.code() else null,
    ): Pair<View, Vues> {
        val gonflee = LayoutInflater.from(themed).inflate(R.layout.overlay_charge_popup, null)
        val liees = Vues(gonflee, localized, !ChargeLimitSupport.reglable(a9, codeBatterie), codeBatterie == ChargeLimitSupport.CODE_LFP)
        brancher(liees)
        etat?.let { peindre(liees, it) }
        return gonflee to liees
    }

    /** Ferme le pop-up s'il est ouvert. Appelable de n'importe quel fil. */
    fun fermer(raison: String) {
        handler.post {
            val v = vue ?: return@post
            ChargeMonitor.ecouteur = null
            runCatching { gestionnaire?.removeView(v) }
            vue = null
            vues = null
            gestionnaire = null
            prixEdite = null
            vise = null
            AppLogger.i(TAG, "Pop-up de charge fermé ($raison)")
        }
    }

    // ── Vues ─────────────────────────────────────────────────────────────────

    /** Une ligne de droite (item_charge_row). */
    internal class Ligne(racine: View) {
        val libelle: TextView = racine.findViewById(R.id.row_label)
        val sous: TextView = racine.findViewById(R.id.row_sub)
        val valeur: TextView = racine.findViewById(R.id.row_value)
        val unite: TextView = racine.findViewById(R.id.row_unit)
        val crayon: ImageView = racine.findViewById(R.id.row_edit)
        val vue: View = racine
    }

    /**
     * [limiteFixe] : la limite n'est pas réglable ([ChargeLimitSupport] — une A9 en batterie LFP,
     * ou au type de batterie illisible) : barre de niveau seule, la note à la place de l'aide (qui
     * nomme la LFP si [lfp]), courbe sans ligne de limite. L'heure de fin en grand, en bas, est la
     * même partout.
     */
    internal class Vues(val racine: View, val loc: Context, val limiteFixe: Boolean, val lfp: Boolean) {
        val themed: Context = racine.context
        val locale: Locale = loc.resources.configuration.locales[0]
        val icone: ImageView = racine.findViewById(R.id.charge_title_icon)
        val titre: TextView = racine.findViewById(R.id.charge_title)
        val pastille: TextView = racine.findViewById(R.id.charge_pill)
        val fermer: ImageView = racine.findViewById(R.id.charge_close)
        val soc: TextView = racine.findViewById(R.id.charge_soc)
        val curseur: ChargeLimitSliderView = racine.findViewById(R.id.charge_slider)
        val aide: TextView = racine.findViewById(R.id.charge_hint)
        val noteLfp: View = racine.findViewById(R.id.charge_lfp_note)
        val noteLfpTexte: TextView = racine.findViewById(R.id.charge_lfp_note_text)
        val finTitre: TextView = racine.findViewById(R.id.charge_end_title)
        val finHeure: TextView = racine.findViewById(R.id.charge_end_time)
        val finReste: TextView = racine.findViewById(R.id.charge_end_left)
        val legendePuissance: TextView = racine.findViewById(R.id.charge_legend_power)
        val legendeBatterie: TextView = racine.findViewById(R.id.charge_legend_battery)
        val depuis: TextView = racine.findViewById(R.id.charge_since)
        val courbe: ChargeCurveView = racine.findViewById(R.id.charge_curve)
        val lignes: View = racine.findViewById(R.id.charge_rows)
        val puissance = Ligne(racine.findViewById(R.id.row_power))
        val energie = Ligne(racine.findViewById(R.id.row_energy))
        val autonomie = Ligne(racine.findViewById(R.id.row_range))
        val cout = Ligne(racine.findViewById(R.id.row_cost))
        val duree = Ligne(racine.findViewById(R.id.row_duration))
        val editeur: View = racine.findViewById(R.id.charge_price_editor)
        val prixType: TextView = racine.findViewById(R.id.price_type)
        val prixAnnuler: ImageView = racine.findViewById(R.id.price_cancel)
        val prixMoins: ImageView = racine.findViewById(R.id.price_minus)
        val prixPlus: ImageView = racine.findViewById(R.id.price_plus)
        val prixValeur: TextView = racine.findViewById(R.id.price_value)
        val prixUnite: TextView = racine.findViewById(R.id.price_unit)
        val prixPas: LinearLayout = racine.findViewById(R.id.price_steps)
        val prixCout: TextView = racine.findViewById(R.id.price_cost)
        val prixAncien: TextView = racine.findViewById(R.id.price_cost_old)
        val prixDefaut: View = racine.findViewById(R.id.price_default)
        val prixOk: View = racine.findViewById(R.id.price_ok)

        val cAccent = themed.getColor(R.color.dash_accent)
        val cVert = themed.getColor(R.color.dash_eco)
        val cWarn = themed.getColor(R.color.dash_warn)
        val cTexteBas = themed.getColor(R.color.dash_text_lo)
        val cTexte = themed.getColor(R.color.text_primary)
        val cTexte2 = themed.getColor(R.color.text_secondary)

        /**
         * Sans argument, PAS de mise en forme : « Batterie (%) » la ferait lever
         * (UnknownFormatConversionException, pop-up jamais affiché — diag SWI133 du 2026-10-16).
         */
        fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

    private fun brancher(v: Vues) {
        v.racine.findViewById<View>(R.id.charge_backdrop).setOnClickListener { fermer("toucher hors de la carte") }
        v.fermer.setOnClickListener { fermer("bouton de fermeture") }
        v.puissance.libelle.text = v.s(R.string.charge_popup_power)
        v.energie.libelle.text = v.s(R.string.charge_popup_energy)
        v.autonomie.libelle.text = v.s(R.string.charge_popup_range)
        v.cout.libelle.text = v.s(R.string.charge_popup_cost)
        v.duree.libelle.text = v.s(R.string.charge_popup_duration)
        v.cout.crayon.visibility = View.VISIBLE
        v.cout.crayon.setColorFilter(v.cAccent)
        v.cout.vue.setOnClickListener { ouvrirEditeur(v) }
        v.legendePuissance.text = puce(v.s(R.string.charge_popup_legend_power), v.cAccent)
        v.legendeBatterie.text = puce(v.s(R.string.charge_popup_legend_battery), v.cVert)
        v.icone.setColorFilter(v.cAccent)

        v.curseur.surGlisse = { pct ->
            vise = pct
            aide(v, dernier)
        }
        v.curseur.surRelache = { pct ->
            vise = null
            AppLogger.i(TAG, "Pop-up de charge — limite demandée : $pct %")
            ChargeMonitor.demanderLimite(pct)
            v.aide.text = v.s(R.string.charge_popup_hint_sending, pct)
            v.aide.setTextColor(v.cAccent)
        }

        v.prixAnnuler.setOnClickListener { fermerEditeur(v) }
        v.prixDefaut.setOnClickListener {
            ChargeMonitor.fixerPrix(null)
            fermerEditeur(v)
        }
        v.prixOk.setOnClickListener {
            prixEdite?.let { ChargeMonitor.fixerPrix(it) }
            fermerEditeur(v)
        }
        v.prixMoins.setOnClickListener { bougerPrix(v, -1) }
        v.prixPlus.setOnClickListener { bougerPrix(v, +1) }
        val teinte = android.content.res.ColorStateList.valueOf(v.cTexte)
        v.prixMoins.imageTintList = teinte
        v.prixPlus.imageTintList = teinte
        // Les croix : l'icône est blanche, invisible sur la carte du thème clair — teintées comme
        // celle du pop-up HVAC (HvacPopupSkin), au gris des textes secondaires.
        val gris = android.content.res.ColorStateList.valueOf(v.cTexte2)
        v.fermer.imageTintList = gris
        v.prixAnnuler.imageTintList = gris

        // Limite non réglable : la barre de niveau seule, et la note qui dit pourquoi à la place
        // de l'aide — « batterie LFP » seulement si la voiture le dit.
        if (v.limiteFixe) {
            v.curseur.reglable = false
            v.aide.visibility = View.GONE
            v.noteLfp.visibility = View.VISIBLE
            v.noteLfpTexte.text = v.s(if (v.lfp) R.string.charge_popup_lfp_info else R.string.charge_popup_limit_fixed)
        }
    }

    /** Légende : un carré de la couleur de la courbe, puis le texte. */
    private fun puce(texte: String, couleur: Int): CharSequence =
        SpannableString("■  $texte").apply { setSpan(ForegroundColorSpan(couleur), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    // ── Peinture ─────────────────────────────────────────────────────────────

    private fun peindre(v: Vues, e: ChargeMonitor.Etat) {
        dernier = e
        val couleur = if (e.finie) v.cVert else v.cAccent
        v.titre.text = v.s(if (e.finie) R.string.charge_popup_title_done else R.string.charge_popup_title)
        v.icone.setColorFilter(couleur)
        v.pastille.text = v.s(when (e.type) {
            ChargeType.AC -> R.string.charge_popup_type_ac
            ChargeType.DC -> R.string.charge_popup_type_dc
            null -> R.string.charge_popup_type_unknown
        })
        v.soc.text = e.soc?.let { decimale(v.locale).format(it) + " %" } ?: "–"

        if (!v.curseur.enGlissement()) v.curseur.limite = if (v.limiteFixe) null else e.limitePct
        v.curseur.niveau = e.soc
        v.curseur.enCharge = e.enCharge
        v.curseur.finie = e.finie
        if (!v.limiteFixe) aide(v, e)

        // Heure de fin, en grand, et ce qu'il reste.
        fin(v, e, StatsTracker.clockPlausible(e.horlogeMs))

        // Courbe, depuis le début de la charge.
        val debut = e.debutHorlogeMs
        val restantMs = if (!e.finie) e.restantMin?.let { it * 60_000L } else null
        val totalMs = e.ecouleMs + (restantMs ?: 0L)
        v.depuis.text = if (debut != null) v.s(R.string.charge_popup_since, heure(v, debut)) else v.s(R.string.charge_popup_since_plain)
        v.courbe.donnees = ChargeCurveView.Donnees(
            points = e.points,
            ecouleMs = e.ecouleMs,
            restantMs = restantMs,
            socActuel = e.soc,
            kwMax = e.kwMax,
            limitePct = if (v.limiteFixe) PLEIN_PCT else e.limitePct,
            afficherLimite = !v.limiteFixe,
            finie = e.finie,
            libelleCible = if (v.limiteFixe) null else e.limitePct?.let { v.s(R.string.charge_popup_target, it) },
            heureDebut = debut?.let { heure(v, it) } ?: duree(v, 0),
            heureMilieu = if (totalMs >= 10 * 60_000L) (debut?.let { heure(v, it + totalMs / 2) } ?: duree(v, (totalMs / 120_000L).toInt())) else null,
            heureFin = when {
                restantMs != null && debut != null -> v.s(R.string.charge_popup_end_estimate, heure(v, debut + totalMs))
                debut != null -> heure(v, debut + totalMs)
                else -> duree(v, (totalMs / 60_000L).toInt())
            },
            locale = v.locale,
        )

        // Chiffres.
        // Une décimale (demande du 2026-10-17) : en AC, « 7 kW » ne bougeait jamais.
        ligne(v.puissance, e.kw?.takeIf { e.enCharge }?.let { decimale(v.locale).format(it) } ?: "–", "kW",
            e.kwMax?.let { v.s(R.string.charge_popup_power_max, decimale(v.locale).format(it)) })
        ligne(v.energie, "+" + decimale(v.locale).format(e.energieKwh), "kWh", null)
        ligne(v.autonomie, e.autonomieGagneeKm?.let { "+$it" } ?: "–", if (e.autonomieGagneeKm != null) "km" else "", null)
        val prixTexte = prix(v.locale, e.prix)
        ligne(v.cout, montant(v.locale, e.energieKwh * e.prix), e.devise,
            if (e.prixCorrige) v.s(R.string.charge_popup_tariff_fixed, prixTexte, e.devise)
            else v.s(R.string.charge_popup_tariff_default, prixTexte, e.devise, typeCourt(e.type)))
        ligne(v.duree, duree(v, e.dureeMin), "",
            debut?.let { v.s(R.string.charge_popup_since_time, heure(v, it)) })

        if (prixEdite != null) peindreEditeur(v)
    }

    /**
     * Bas de la colonne, sur tous les firmwares (2026-10-18) : l'heure de fin en grand ; dessous,
     * le temps restant et la cible (la limite, ou 100 % sur les A9). Sans horloge crédible (réveil
     * à l'horloge d'usine), la durée prend la place de l'heure.
     */
    private fun fin(v: Vues, e: ChargeMonitor.Etat, fiable: Boolean) {
        val restant = e.restantMin
        val cible = if (v.limiteFixe) PLEIN_PCT else e.limitePct
        when {
            e.finie -> {
                v.finTitre.text = v.s(R.string.charge_popup_title_done)
                v.finHeure.text = if (fiable) heure(v, e.horlogeMs) else "–"
                v.finReste.text = ""
            }
            restant != null -> {
                v.finTitre.text = v.s(R.string.charge_popup_end_label)
                v.finHeure.text = if (fiable) heure(v, e.horlogeMs + restant * 60_000L) else duree(v, restant)
                v.finReste.text = when {
                    cible == null -> ""
                    fiable -> v.s(R.string.charge_popup_end_left, duree(v, restant), cible)
                    else -> v.s(R.string.charge_popup_until_unknown, cible)
                }
            }
            else -> {
                v.finTitre.text = v.s(R.string.charge_popup_end_label)
                v.finHeure.text = "–"
                v.finReste.text = cible?.let { v.s(R.string.charge_popup_until_unknown, it) } ?: ""
            }
        }
    }

    /** Texte sous le curseur : glissement, envoi, refus, ou simple invitation. */
    private fun aide(v: Vues, e: ChargeMonitor.Etat?) {
        val cible = vise
        val soc = e?.soc
        when {
            cible != null && soc != null && cible < soc -> {
                v.aide.text = v.s(R.string.charge_popup_hint_below, cible)
                v.aide.setTextColor(v.cWarn)
            }
            cible != null -> {
                v.aide.text = v.s(R.string.charge_popup_hint_release, cible)
                v.aide.setTextColor(v.cAccent)
            }
            e?.limiteRefusee != null -> {
                v.aide.text = v.s(R.string.charge_popup_hint_refused, e.limiteRefusee)
                v.aide.setTextColor(v.cWarn)
            }
            e?.limiteEnAttente == true && e.limitePct != null -> {
                v.aide.text = v.s(R.string.charge_popup_hint_sending, e.limitePct)
                v.aide.setTextColor(v.cAccent)
            }
            e?.limitePct == null -> {
                v.aide.text = v.s(R.string.charge_popup_hint_unknown)
                v.aide.setTextColor(v.cTexteBas)
            }
            else -> {
                v.aide.text = v.s(R.string.charge_popup_hint)
                v.aide.setTextColor(v.cTexteBas)
            }
        }
    }

    private fun ligne(l: Ligne, valeur: String, unite: String, sous: String?) {
        l.valeur.text = valeur
        l.unite.text = unite
        l.unite.visibility = if (unite.isEmpty()) View.GONE else View.VISIBLE
        l.sous.text = sous ?: ""
        l.sous.visibility = if (sous.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ── Prix du kWh, pour cette charge ───────────────────────────────────────

    private fun ouvrirEditeur(v: Vues) {
        val e = dernier ?: return
        prixEdite = e.prix
        pas = PriceStepper.steps(if (e.prixDefaut > 0f) e.prixDefaut else e.prix)
        rangPas = PAS_PAR_DEFAUT.coerceAtMost(pas.lastIndex)
        v.prixPas.removeAllViews()
        val format = DecimalFormat("0.###", DecimalFormatSymbols.getInstance(v.locale))
        AppDialog.segments(v.prixPas, pas.map { "±" + format.format(it.toDouble()) }, rangPas) { rangPas = it }
        for (i in 0 until v.prixPas.childCount) (v.prixPas.getChildAt(i) as? TextView)?.textSize = 13f
        v.prixType.text = typeCourt(e.type)
        v.prixUnite.text = "${e.devise}/kWh"
        v.lignes.visibility = View.INVISIBLE
        v.editeur.visibility = View.VISIBLE
        peindreEditeur(v)
        AppLogger.i(TAG, "Pop-up de charge — éditeur du prix ouvert (${e.prix})")
    }

    private fun fermerEditeur(v: Vues) {
        prixEdite = null
        v.editeur.visibility = View.GONE
        v.lignes.visibility = View.VISIBLE
    }

    private fun bougerPrix(v: Vues, sens: Int) {
        val p = prixEdite ?: return
        prixEdite = PriceStepper.move(p, pas.getOrElse(rangPas) { 0.01f }, sens)
        peindreEditeur(v)
    }

    private fun peindreEditeur(v: Vues) {
        val p = prixEdite ?: return
        val e = dernier ?: return
        v.prixValeur.text = prix(v.locale, p)
        v.prixCout.text = montant(v.locale, e.energieKwh * p) + " " + e.devise
        val ancien = e.energieKwh * e.prix
        if (kotlin.math.abs(p - e.prix) > 1e-6f) {
            v.prixAncien.text = montant(v.locale, ancien) + " " + e.devise
            v.prixAncien.paintFlags = v.prixAncien.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            v.prixAncien.visibility = View.VISIBLE
        } else {
            v.prixAncien.visibility = View.GONE
        }
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private fun decimale(locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    /** Prix du kWh : trois décimales sous 10, comme l'onglet Statistiques. */
    private fun prix(locale: Locale, v: Float): String = com.mg4.control.model.MoneyFormat.price(v, locale)

    /** Coût d'une charge : au centime — sur une seule recharge, le dixième est trop grossier. */
    private fun montant(locale: Locale, v: Float): String =
        if (v < 1000f) String.format(locale, "%.2f", v) else com.mg4.control.model.MoneyFormat.amount(v, locale)

    private fun typeCourt(t: ChargeType?): String = when (t) {
        ChargeType.DC -> "DC"
        else -> "AC"
    }

    private fun heure(v: Vues, ms: Long): String = DateFormat.getTimeFormat(v.loc).format(Date(ms))

    private fun duree(v: Vues, minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return if (m >= 60) v.s(R.string.cluster_duration_hm, m / 60, m % 60) else v.s(R.string.cluster_duration_min, m)
    }
}
