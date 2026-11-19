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
import com.mg4.control.model.ChargeControl
import com.mg4.control.model.ChargeCurrent
import com.mg4.control.model.ChargePortLock
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
            // Sans charge suivie, il s'ouvre quand même (2026-11-19) : limite, courant AC, verrou de
            // la prise et marche de la charge restent réglables.
            if (!ChargePopupSettings.isEnabled(app)) {
                AppLogger.i(TAG, "Pop-up de charge — raccourci sans effet (option coupée)")
                Toast.makeText(app, LocaleHelper.applyLocale(app).getString(R.string.charge_popup_disabled), Toast.LENGTH_SHORT).show()
                return@post
            }
            ouvrir(app, if (ChargeMonitor.etat == null) "raccourci, aucune charge suivie" else "raccourci")
        }
    }

    /** Ouvre le pop-up s'il ne l'est pas. Fil principal. */
    fun ouvrir(context: Context, raison: String) {
        if (vue != null) return
        val c = OverlayKit.contextes(context, LARGEUR_DP, HAUTEUR_DP)
        val (racine, v) = try {
            val (gonflee, liees) = preparer(c.themed, c.localized, ChargeMonitor.etat ?: ChargeMonitor.vide())
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
        val liees = Vues(gonflee, localized, !ChargeLimitSupport.reglable(a9, codeBatterie), codeBatterie == ChargeLimitSupport.CODE_LFP, a9)
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
    internal class Vues(val racine: View, val loc: Context, val limiteFixe: Boolean, val lfp: Boolean, val a9: Boolean = false) {
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
        val controle: TextView = racine.findViewById(R.id.charge_control)
        /** Marche / arrêt touché, montré « en préparation » jusqu'à ce que le suivi en parle. */
        var controleVise: Int? = null
        val verrou: TextView = racine.findViewById(R.id.charge_port_lock)
        /** Verrou touché, montré « Envoi… » jusqu'à ce que le suivi en parle. */
        var verrouVise: Int? = null
        val courbeVide: View = racine.findViewById(R.id.charge_idle)
        val courbeVideIcone: ImageView = racine.findViewById(R.id.charge_idle_icon)
        val courantTitre: TextView = racine.findViewById(R.id.charge_current_title)
        val courant: LinearLayout = racine.findViewById(R.id.charge_current)
        /** Les quatre cases du courant AC, codes 1..4. */
        val casesCourant = mutableListOf<TextView>()
        /** Courant touché, montré tout de suite, jusqu'à ce que le suivi en parle. */
        var courantVise: Int? = null

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

        // Commencer / Arrêter la charge : le bouton propose le contraire de l'état lu.
        v.controle.setOnClickListener { bouton ->
            val e = dernier ?: return@setOnClickListener
            if (!bouton.isEnabled) return@setOnClickListener
            val code = if (ChargeControl.proposeArret(e.controle, e.enCharge)) ChargeControl.STOP else ChargeControl.START
            AppLogger.i(TAG, "Pop-up de charge — ${if (code == ChargeControl.STOP) "arrêt" else "démarrage"} de la charge demandé")
            v.controleVise = code
            ChargeMonitor.demanderControle(code)
            peindreControle(v, e)
        }

        // Verrou de la prise : le bouton propose le contraire de l'état lu.
        v.verrou.setOnClickListener { bouton ->
            val e = dernier ?: return@setOnClickListener
            if (!bouton.isEnabled) return@setOnClickListener
            val code = ChargePortLock.propose(e.verrou, v.a9) ?: return@setOnClickListener
            AppLogger.i(TAG, "Pop-up de charge — ${if (code == ChargePortLock.LOCK) "verrouillage" else "déverrouillage"} de la prise demandé")
            v.verrouVise = code
            ChargeMonitor.demanderVerrou(code)
            peindreVerrou(v, e)
        }
        v.courbeVideIcone.setColorFilter(v.cTexteBas)

        // Courant de charge AC (#215) : quatre cases, une écriture au toucher.
        val gonfleur = LayoutInflater.from(v.themed)
        for (code in 1..ChargeCurrent.CODE_MAX) {
            val case = (gonfleur.inflate(R.layout.item_dialog_segment, v.courant, false) as TextView).also {
                it.text = libelleCourant(v, code)
                it.textSize = 14f
                it.setOnClickListener { touche ->
                    if (!touche.isEnabled || code == (v.courantVise ?: dernier?.courantCode)) return@setOnClickListener
                    AppLogger.i(TAG, "Pop-up de charge — courant AC demandé : ${libelleCourant(v, code)} (code $code)")
                    v.courantVise = code
                    ChargeMonitor.demanderCourant(code)
                    peindreCourant(v, dernier)
                }
            }
            v.courant.addView(case)
            v.casesCourant += case
        }
        peindreCourant(v, null)

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
        v.titre.text = v.s(titre(e))
        v.icone.setColorFilter(couleur)
        // Sans charge suivie : la pastille dit si le câble est branché (rien si non relu, en READY).
        val pastille = when {
            !e.sansCharge -> when (e.type) {
                ChargeType.AC -> R.string.charge_popup_type_ac
                ChargeType.DC -> R.string.charge_popup_type_dc
                null -> R.string.charge_popup_type_unknown
            }
            e.branchee == true -> R.string.charge_popup_plugged
            e.branchee == false -> R.string.charge_popup_unplugged
            else -> null
        }
        v.pastille.visibility = if (pastille == null) View.GONE else View.VISIBLE
        pastille?.let { v.pastille.text = v.s(it) }
        v.soc.text = e.soc?.let { decimale(v.locale).format(it) + " %" } ?: "–"

        if (!v.curseur.enGlissement()) v.curseur.limite = if (v.limiteFixe) null else e.limitePct
        v.curseur.niveau = e.soc
        v.curseur.enCharge = e.enCharge
        v.curseur.finie = e.finie
        if (!v.limiteFixe) aide(v, e)
        peindreCourant(v, e)
        peindreControle(v, e)
        peindreVerrou(v, e)

        // Heure de fin, en grand, et ce qu'il reste.
        fin(v, e, StatsTracker.clockPlausible(e.horlogeMs))

        // Sans charge suivie : un message à la place de la courbe, des tirets à droite.
        if (e.sansCharge) {
            peindreSansCharge(v, e)
            return
        }
        v.courbeVide.visibility = View.GONE
        v.courbe.visibility = View.VISIBLE
        v.legendePuissance.visibility = View.VISIBLE
        v.legendeBatterie.visibility = View.VISIBLE
        v.cout.crayon.visibility = View.VISIBLE

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
        // Dans l'unité de distance générale (2026-11-10).
        val distance = com.mg4.control.util.DistanceSetting.unit(v.loc)
        ligne(v.autonomie, e.autonomieGagneeKm?.let { "+${kotlin.math.round(distance.fromKm(it.toFloat())).toInt()}" } ?: "–",
            if (e.autonomieGagneeKm != null) distance.short else "", null)
        val prixTexte = prix(v.locale, e.prix)
        ligne(v.cout, montant(v.locale, e.energieKwh * e.prix), e.devise,
            if (e.prixCorrige) v.s(R.string.charge_popup_tariff_fixed, prixTexte, e.devise)
            else v.s(R.string.charge_popup_tariff_default, prixTexte, e.devise, typeCourt(e.type)))
        ligne(v.duree, duree(v, e.dureeMin), "",
            debut?.let { v.s(R.string.charge_popup_since_time, heure(v, it)) })

        if (prixEdite != null) peindreEditeur(v)
    }

    /** Ouvert sans charge suivie : rien à tracer ni à compter ; le tarif du kWh reste indiqué. */
    private fun peindreSansCharge(v: Vues, e: ChargeMonitor.Etat) {
        v.courbe.visibility = View.INVISIBLE
        v.courbeVide.visibility = View.VISIBLE
        v.legendePuissance.visibility = View.INVISIBLE
        v.legendeBatterie.visibility = View.INVISIBLE
        v.depuis.text = ""
        ligne(v.puissance, "–", "", null)
        ligne(v.energie, "–", "", null)
        ligne(v.autonomie, "–", "", null)
        ligne(v.cout, "–", "", if (e.prixDefaut > 0f)
            v.s(R.string.charge_popup_tariff_default, prix(v.locale, e.prixDefaut), e.devise, typeCourt(e.type)) else null)
        ligne(v.duree, "–", "", null)
        v.cout.crayon.visibility = View.GONE
        if (prixEdite != null) fermerEditeur(v)
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
            // Sans charge suivie : l'autonomie, dans l'unité de distance générale.
            e.sansCharge -> {
                val distance = com.mg4.control.util.DistanceSetting.unit(v.loc)
                v.finTitre.text = v.s(R.string.charge_popup_range_now)
                v.finHeure.text = e.autonomieKm?.let { "${kotlin.math.round(distance.fromKm(it.toFloat())).toInt()} ${distance.short}" } ?: "–"
                v.finReste.text = ""
            }
            e.finie -> {
                v.finTitre.text = v.s(titre(e))
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

    /** Titre : en cours, terminée, ou arrêtée (statut 8 : arrêtée par le bouton, l'appli ou la borne). */
    private fun titre(e: ChargeMonitor.Etat): Int = when {
        e.sansCharge -> R.string.charge_popup_none
        !e.finie -> R.string.charge_popup_title
        e.arretee -> R.string.charge_popup_title_stopped
        else -> R.string.charge_popup_title_done
    }

    /**
     * Bouton « Commencer / Arrêter la charge » (variante A) : caché câble débranché ; « Préparation… »
     * inerte pendant l'envoi ou quand la voiture prépare la charge ; refus montré quelques secondes.
     */
    private fun peindreControle(v: Vues, e: ChargeMonitor.Etat?) {
        val vise = v.controleVise
        if (vise != null && e != null && (e.controleEnAttente || e.controleRefuse != null || ChargeControl.pris(vise, e.controle)))
            v.controleVise = null
        if (e == null || (v.controleVise == null && !ChargeControl.visible(e.enCharge, e.branchee))) {
            v.controle.visibility = View.GONE
            return
        }
        v.controle.visibility = View.VISIBLE
        val ctx = v.themed
        val attente = v.controleVise != null || e.controleEnAttente || e.controle == ChargeControl.PREPARING
        val refuse = e.controleRefuse
        val arreter = ChargeControl.proposeArret(e.controle, e.enCharge)
        val (texte, icone, couleur, fond) = when {
            attente -> Quadruple(R.string.charge_control_preparing, null, v.cTexte2, ctx.getColor(R.color.dash_btn))
            refuse != null -> Quadruple(R.string.charge_control_refused, null, v.cWarn, ctx.getColor(R.color.dash_warn_dim))
            arreter -> Quadruple(R.string.charge_control_stop, R.drawable.ic_charge_stop, ctx.getColor(R.color.dash_danger), ctx.getColor(R.color.dash_danger_dim))
            else -> Quadruple(R.string.charge_control_start, R.drawable.ic_charge_start, v.cVert, ctx.getColor(R.color.dash_eco_dim))
        }
        v.controle.text = v.s(texte)
        v.controle.setTextColor(couleur)
        val d = icone?.let { ctx.getDrawable(it)?.mutate() }?.also {
            val taille = (18 * ctx.resources.displayMetrics.density).roundToInt()
            it.setBounds(0, 0, taille, taille)
            it.setTint(couleur)
        }
        v.controle.setCompoundDrawables(d, null, null, null)
        OverlayKit.fond(v.controle, fond, rayon = 17f * ctx.resources.displayMetrics.density)
        v.controle.isEnabled = !attente
    }

    private data class Quadruple(val texte: Int, val icone: Int?, val couleur: Int, val fond: Int)

    /**
     * Bouton du verrou de la prise (variante A, 2026-11-19) : il dit ce qu'il fera (« Déverrouiller
     * la prise » quand elle est verrouillée) ; « Envoi… » inerte pendant la demande, refus montré
     * quelques secondes ; caché quand la voiture ne donne pas le verrou ([ChargePortLock.propose]).
     */
    private fun peindreVerrou(v: Vues, e: ChargeMonitor.Etat?) {
        val vise = v.verrouVise
        if (vise != null && e != null && (e.verrouEnAttente || e.verrouRefuse != null || ChargePortLock.pris(vise, e.verrou)))
            v.verrouVise = null
        val propose = e?.let { ChargePortLock.propose(it.verrou, v.a9) }
        val attente = v.verrouVise != null || e?.verrouEnAttente == true
        val refuse = e?.verrouRefuse
        if (e == null || (!attente && refuse == null && propose == null)) {
            v.verrou.visibility = View.GONE
            return
        }
        v.verrou.visibility = View.VISIBLE
        val ctx = v.themed
        val (texte, icone, couleur, fond) = when {
            attente -> Quadruple(R.string.charge_port_sending, null, v.cTexte2, ctx.getColor(R.color.dash_btn))
            refuse != null -> Quadruple(R.string.charge_control_refused, null, v.cWarn, ctx.getColor(R.color.dash_warn_dim))
            propose == ChargePortLock.UNLOCK -> Quadruple(R.string.charge_port_unlock, R.drawable.ic_sym_lock_open, v.cAccent, ctx.getColor(R.color.dash_btn))
            else -> Quadruple(R.string.charge_port_lock, R.drawable.ic_sym_lock, v.cAccent, ctx.getColor(R.color.dash_btn))
        }
        v.verrou.text = v.s(texte)
        v.verrou.setTextColor(couleur)
        val d = icone?.let { ctx.getDrawable(it)?.mutate() }?.also {
            val taille = (18 * ctx.resources.displayMetrics.density).roundToInt()
            it.setBounds(0, 0, taille, taille)
            it.setTint(couleur)
        }
        v.verrou.setCompoundDrawables(d, null, null, null)
        OverlayKit.fond(v.verrou, fond, rayon = 17f * ctx.resources.displayMetrics.density)
        v.verrou.isEnabled = !attente
    }

    private fun libelleCourant(v: Vues, code: Int?): String =
        ChargeCurrent.amperes(code)?.let { "$it A" } ?: v.s(R.string.charge_current_max)

    /**
     * Les cases du courant AC et leur titre : le courant demandé ou lu en surbrillance ; titre qui dit
     * l'envoi, le refus (l'ancienne valeur revient), ou, en charge DC, que le réglage n'y vaut pas —
     * les cases sont alors grisées et inertes.
     */
    private fun peindreCourant(v: Vues, e: ChargeMonitor.Etat?) {
        if (v.courantVise != null && e != null &&
            (e.courantEnAttente || e.courantRefuse != null || e.courantCode == v.courantVise)) v.courantVise = null
        val dc = e?.type == ChargeType.DC
        val choisi = v.courantVise ?: e?.courantCode
        val rayon = 9f * v.themed.resources.displayMetrics.density
        v.casesCourant.forEachIndexed { i, case ->
            val on = i + 1 == choisi
            OverlayKit.fond(case, if (on) v.themed.getColor(R.color.hvac_segment_on) else android.graphics.Color.TRANSPARENT, rayon = rayon)
            case.setTextColor(if (on) v.cTexte else v.cTexte2)
            case.isEnabled = !dc
        }
        v.courant.alpha = if (dc) 0.4f else 1f
        val refuse = e?.courantRefuse
        when {
            dc -> {
                v.courantTitre.text = v.s(R.string.charge_popup_current_ac_only)
                v.courantTitre.setTextColor(v.cTexteBas)
            }
            v.courantVise != null || e?.courantEnAttente == true -> {
                v.courantTitre.text = v.s(R.string.charge_popup_current_sending, libelleCourant(v, choisi))
                v.courantTitre.setTextColor(v.cAccent)
            }
            refuse != null -> {
                v.courantTitre.text = v.s(R.string.charge_popup_current_refused, libelleCourant(v, refuse))
                v.courantTitre.setTextColor(v.cWarn)
            }
            else -> {
                v.courantTitre.text = v.s(R.string.charge_popup_current)
                v.courantTitre.setTextColor(v.cTexte2)
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
        if (e.sansCharge) return
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
