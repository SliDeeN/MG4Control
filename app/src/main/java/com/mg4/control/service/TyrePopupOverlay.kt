package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.TyreProbe
import com.mg4.control.hardware.TyreProbe.Roue
import com.mg4.control.model.TyrePressure
import com.mg4.control.model.TyrePressure.Alert
import com.mg4.control.ui.widget.TyreCarView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Pop-up « Pression des pneus » (maquette du 2026-11-20), ouvert par le raccourci TYRE_POPUP
 * (boutons du volant, bulles, automatisations). Les quatre pneus autour de la voiture : pression
 * dans l'unité choisie (bar, PSI, kPa — par défaut celle du combiné), température, et une pastille
 * d'après le code d'état de la voiture ([TyrePressure.alert]).
 *
 * Relu toutes les [RELEVE_MS] tant qu'il est ouvert ; chaque changement de code est noté au journal
 * (on apprendra ainsi le sens des codes 2 à 6). Pas de minuterie (sa demande, 2026-11-20) : seuls la
 * croix, un toucher à côté ou un second appui du raccourci le referment. Fil principal, sauf les
 * lectures (file [lecteur]).
 */
object TyrePopupOverlay {

    private const val TAG = TyreProbe.TAG
    /** Carte de base de overlay_tyre_popup.xml, agrandie comme les autres pop-ups. */
    private const val LARGEUR_DP = 1140f
    private const val HAUTEUR_DP = 470f
    private const val MARGE_FENETRE_DP = 12f
    private const val RELEVE_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lecteur = Executors.newSingleThreadExecutor { r -> Thread(r, "mg4-tyre") }

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null
    private var vues: Vues? = null
    private var ctxApp: Context? = null
    private var dernier: TyreProbe.Pneus? = null
    private var codesVus: Map<Roue, Int?> = emptyMap()

    fun isShowing(): Boolean = vue != null

    /** Raccourci : ouvre le pop-up, ou le referme s'il est déjà à l'écran. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (vue != null) fermer("second appui du raccourci") else ouvrir(app)
        }
    }

    private fun ouvrir(app: Context) {
        val c = OverlayKit.contextes(app, LARGEUR_DP, HAUTEUR_DP)
        val (racine, v) = try {
            preparer(c.themed, c.localized).also { (gonflee, _) ->
                OverlayKit.tenirDansLaFenetre(gonflee, (MARGE_FENETRE_DP * c.mesures.density).roundToInt(), "Pression des pneus")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pression des pneus non affichée — mise en page : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(racine, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pression des pneus non affichée — fenêtre : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        vue = racine
        gestionnaire = wm
        vues = v
        ctxApp = app
        dernier = null
        codesVus = emptyMap()
        v.surUnite = { u ->
            com.mg4.control.util.TyreUnitSetting.set(app, u)
            AppLogger.i(TAG, "Pression des pneus — unité choisie : ${u.label}")
            peindre(v, dernier, u)
        }
        v.fermer.setOnClickListener { fermer("bouton de fermeture") }
        v.racine.findViewById<View>(R.id.tyre_backdrop).setOnClickListener { fermer("toucher hors de la carte") }
        AppLogger.i(TAG, "Pression des pneus affichée — échelle ×${String.format(Locale.US, "%.2f", c.echelle)}")
        lecteur.execute(releve)
    }

    /** Lecture sur le fil [lecteur], peinture sur le fil principal, puis la suivante. */
    private val releve: Runnable = object : Runnable {
        override fun run() {
            val p = runCatching { TyreProbe.pneusRecents() }
                .onFailure { AppLogger.w(TAG, "Pression des pneus — lecture : ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
            handler.post {
                val v = vues ?: return@post
                p?.let { journal(it) }
                dernier = p
                peindre(v, p, unite(ctxApp, p))
                handler.postDelayed({ if (vue != null) lecteur.execute(this) }, RELEVE_MS)
            }
        }
    }

    /** Ouverture, puis chaque code qui change : c'est ainsi qu'on apprendra le sens des codes. */
    private fun journal(p: TyreProbe.Pneus) {
        val codes = p.roues.mapValues { it.value.etat }
        if (codesVus.isEmpty()) {
            AppLogger.i(TAG, "Pression des pneus — " + p.roues.entries.joinToString(" · ") { (r, w) ->
                "${r.name} ${w.pression ?: "?"} kPa ${w.temperature ?: "?"} °C code ${w.etat ?: "?"}"
            } + " · unité combiné ${p.uniteCombine ?: "?"}")
        } else {
            codes.forEach { (r, code) ->
                if (codesVus[r] != code) AppLogger.i(TAG,
                    "Pression des pneus — ${r.name} code ${codesVus[r] ?: "?"} → ${code ?: "?"} (${p.roues[r]?.pression ?: "?"} kPa, ${p.roues[r]?.temperature ?: "?"} °C)")
            }
        }
        codesVus = codes
    }

    /** L'unité choisie ([com.mg4.control.util.TyreUnitSetting]), sinon celle du combiné, sinon bar. */
    fun unite(ctx: Context?, p: TyreProbe.Pneus?): TyrePressure.PressureUnit =
        com.mg4.control.util.TyreUnitSetting.unit(ctx, p?.uniteCombine)

    /** Ferme le pop-up s'il est ouvert. Appelable de n'importe quel fil. */
    fun fermer(raison: String) {
        handler.post {
            val v = vue ?: return@post
            handler.removeCallbacksAndMessages(null)
            runCatching { gestionnaire?.removeView(v) }
            vue = null
            vues = null
            gestionnaire = null
            AppLogger.i(TAG, "Pression des pneus fermée ($raison)")
        }
    }

    // ── Vues ─────────────────────────────────────────────────────────────────

    internal class Carte(val racine: View, val droite: Boolean) {
        val nom: TextView = racine.findViewById(R.id.tyre_card_name)
        val valeur: TextView = racine.findViewById(R.id.tyre_card_value)
        val unite: TextView = racine.findViewById(R.id.tyre_card_unit)
        val temp: TextView = racine.findViewById(R.id.tyre_card_temp)
        val etat: TextView = racine.findViewById(R.id.tyre_card_state)
        val pourquoi: TextView = racine.findViewById(R.id.tyre_card_why)
        val rangee: LinearLayout = racine.findViewById(R.id.tyre_card_row)
    }

    internal class Vues(val racine: View, val loc: Context) {
        val themed: Context = racine.context
        val locale: Locale = loc.resources.configuration.locales[0]
        val fermer: ImageView = racine.findViewById(R.id.tyre_close)
        val voiture: TyreCarView = racine.findViewById(R.id.tyre_car)
        val unites: Map<TyrePressure.PressureUnit, TextView> = mapOf(
            TyrePressure.PressureUnit.BAR to racine.findViewById(R.id.tyre_unit_bar),
            TyrePressure.PressureUnit.PSI to racine.findViewById(R.id.tyre_unit_psi),
            TyrePressure.PressureUnit.KPA to racine.findViewById(R.id.tyre_unit_kpa),
        )
        val cartes: Map<Roue, Carte> = mapOf(
            Roue.AVANT_GAUCHE to Carte(racine.findViewById(R.id.tyre_fl), false),
            Roue.AVANT_DROITE to Carte(racine.findViewById(R.id.tyre_fr), true),
            Roue.ARRIERE_GAUCHE to Carte(racine.findViewById(R.id.tyre_rl), false),
            Roue.ARRIERE_DROITE to Carte(racine.findViewById(R.id.tyre_rr), true),
        )
        var surUnite: (TyrePressure.PressureUnit) -> Unit = {}

        val cAccent = themed.getColor(R.color.dash_accent)
        val cTexte = themed.getColor(R.color.text_primary)
        val cTexte2 = themed.getColor(R.color.text_secondary)
    }

    /**
     * Gonfle et branche le pop-up, peint « sans valeur », sans le poser à l'écran. Séparé pour les
     * tests : une erreur ici passe la compilation et ne se voit qu'en voiture.
     */
    internal fun preparer(themed: Context, localized: Context): Pair<View, Vues> {
        val racine = LayoutInflater.from(themed).inflate(R.layout.overlay_tyre_popup, null)
        val v = Vues(racine, localized)
        racine.findViewById<ImageView>(R.id.tyre_title_icon).setColorFilter(v.cAccent)
        racine.findViewById<TextView>(R.id.tyre_title).text = localized.getString(R.string.tyre_popup_title)
        v.fermer.setColorFilter(v.cTexte)
        OverlayKit.fond(racine.findViewById(R.id.tyre_units), themed.getColor(R.color.dash_section),
            trait = themed.getColor(R.color.dash_border), epaisseur = OverlayKit.dp(themed, 1f), rayon = OverlayKit.dp(themed, 19f).toFloat())
        v.unites.forEach { (u, t) ->
            t.text = u.label
            t.setOnClickListener { v.surUnite(u) }
        }
        val noms = mapOf(
            Roue.AVANT_GAUCHE to R.string.win_front_left, Roue.AVANT_DROITE to R.string.win_front_right,
            Roue.ARRIERE_GAUCHE to R.string.win_rear_left, Roue.ARRIERE_DROITE to R.string.win_rear_right,
        )
        v.cartes.forEach { (r, c) ->
            c.nom.text = localized.getString(noms.getValue(r))
            if (c.droite) {
                (c.racine as LinearLayout).gravity = Gravity.END or Gravity.CENTER_VERTICAL
                c.nom.gravity = Gravity.END
                c.pourquoi.gravity = Gravity.END
                c.rangee.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                c.rangee.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
        }
        peindre(v, null, TyrePressure.PressureUnit.BAR)
        return racine to v
    }

    /** Peint les quatre pneus, l'unité et la voiture. [p] null : rien lu (pas de valeur partout). */
    internal fun peindre(v: Vues, p: TyreProbe.Pneus?, unite: TyrePressure.PressureUnit) {
        v.unites.forEach { (u, t) ->
            val choisie = u == unite
            t.setTextColor(if (choisie) v.themed.getColor(R.color.dash_on_accent) else v.cTexte2)
            t.paint.isFakeBoldText = choisie
            OverlayKit.fond(t, if (choisie) v.cAccent else android.graphics.Color.TRANSPARENT,
                rayon = OverlayKit.dp(v.themed, 17f).toFloat())
        }
        val essieu = mapOf(
            Roue.AVANT_GAUCHE to Roue.AVANT_DROITE, Roue.AVANT_DROITE to Roue.AVANT_GAUCHE,
            Roue.ARRIERE_GAUCHE to Roue.ARRIERE_DROITE, Roue.ARRIERE_DROITE to Roue.ARRIERE_GAUCHE,
        )
        val couleurs = IntArray(4)
        Roue.values().forEachIndexed { i, r ->
            val c = v.cartes.getValue(r)
            val w = p?.roues?.get(r)
            val kpa = TyrePressure.valide(w?.pression)
            val alerte = TyrePressure.alert(w?.etat, p?.roues?.get(essieu.getValue(r))?.etat, kpa != null)
            c.valeur.text = kpa?.let { TyrePressure.format(it, unite, v.locale) } ?: "–"
            c.unite.text = unite.label
            c.temp.text = (w?.temperature?.toString() ?: "–") + " °C"

            val (texte, fond, icone) = when (alerte) {
                Alert.NORMAL -> Triple(R.color.dash_eco, R.color.dash_eco_dim, R.drawable.ic_sym_check_circle)
                Alert.ALERT -> Triple(R.color.dash_warn, R.color.dash_warn_dim, R.drawable.ic_sym_warning)
                Alert.LONE_SIX -> Triple(R.color.dash_text_lo, R.color.dash_border, R.drawable.ic_sym_info)
                Alert.NO_VALUE -> Triple(R.color.dash_text_lo, R.color.dash_border, R.drawable.ic_sym_help)
            }
            val couleur = v.themed.getColor(texte)
            couleurs[i] = couleur
            c.etat.text = when (alerte) {
                Alert.NORMAL -> v.loc.getString(R.string.tyre_state_normal)
                Alert.ALERT -> v.loc.getString(R.string.tyre_state_alert, w?.etat ?: 0)
                Alert.LONE_SIX -> v.loc.getString(R.string.tyre_state_code, w?.etat ?: 6)
                Alert.NO_VALUE -> v.loc.getString(R.string.tyre_state_none)
            }
            c.etat.setTextColor(couleur)
            OverlayKit.fond(c.etat, v.themed.getColor(fond), rayon = OverlayKit.dp(v.themed, 15f).toFloat())
            val taille = OverlayKit.dp(v.themed, 18f)
            val dessin = v.themed.getDrawable(icone)?.mutate()?.apply {
                setBounds(0, 0, taille, taille)
                setTintList(ColorStateList.valueOf(couleur))
            }
            // Picto toujours à gauche du texte (maquette), même dans les cartes de droite dont la
            // rangée est inversée : pose absolue, pastille figée de gauche à droite.
            c.etat.layoutDirection = View.LAYOUT_DIRECTION_LTR
            c.etat.setCompoundDrawables(dessin, null, null, null)

            val pourquoi = when (alerte) {
                Alert.NORMAL -> null
                Alert.ALERT -> if (w?.etat == 6) R.string.tyre_why_axle else R.string.tyre_why_alert
                Alert.LONE_SIX -> R.string.tyre_why_lone_six
                Alert.NO_VALUE -> R.string.tyre_why_none
            }
            c.pourquoi.visibility = if (pourquoi == null) View.GONE else View.VISIBLE
            pourquoi?.let { c.pourquoi.text = v.loc.getString(it) }
            OverlayKit.fond(c.racine, v.themed.getColor(R.color.dash_section),
                trait = if (alerte == Alert.ALERT) couleur else v.themed.getColor(R.color.dash_border),
                epaisseur = OverlayKit.dp(v.themed, if (alerte == Alert.ALERT) 2f else 1f),
                rayon = OverlayKit.dp(v.themed, 16f).toFloat())
        }
        v.voiture.couleurs = couleurs
    }
}
