package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateRequest
import com.mg4.control.ui.AppDialog
import com.mg4.control.util.PreClimateSettings
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Pré-climatisation (issue #130) : le pop-up de réglage, ouvert par le raccourci au volant.
 *
 * Il s'ouvre TOUJOURS sur « Maintenant » et reprend les derniers choix pour le reste
 * ([PreClimateSettings]). Température et ventilation sont bornées par ce que la voiture annonce
 * (7 crans sur l'ancien SDK, 11 sur A9) ; l'A/C est activé par défaut. « Démarrer » passe la main
 * à [PreClimate], qui affiche l'écran de suivi.
 *
 * Joystick droit : focus sur les cases ([OverlayNavigation]), départ sur « Démarrer ». Pas de
 * fermeture au délai : c'est une fenêtre de réglage, elle reste jusqu'à la croix, « Annuler » ou un
 * second appui du raccourci. Fil principal seulement.
 */
object PreClimatePopupOverlay {

    private const val TAG = PreClimate.TAG
    private const val LARGEUR_DP = 1140f
    private const val HAUTEUR_DP = 440f
    private const val MARGE_FENETRE_DP = 12f
    /** Bornes de repli si la clim ne répond pas à l'ouverture (celles de l'ancien SDK). */
    private const val TEMP_MIN_REPLI = 16
    private const val TEMP_MAX_REPLI = 32
    private const val FAN_MIN_REPLI = 1
    private const val FAN_MAX_REPLI = 7
    /** Durées non choisissables en mode chien. */
    private const val ALPHA_GRISE = 0.35f

    private val handler = Handler(Looper.getMainLooper())
    private val lecture = Executors.newSingleThreadExecutor()

    @Volatile private var vue: View? = null
    private var gestionnaire: WindowManager? = null
    private var ouverture = false
    private var demarrage = false
    private var vues: Vues? = null
    private var req: PreClimateRequest? = null
    private var navigation: OverlayNavigation? = null

    fun isShowing(): Boolean = vue != null

    /** Joystick droit, depuis [KeyCaptureService] ; sans effet si le pop-up s'est fermé. */
    fun naviguer(commande: JoystickFocus.Commande) {
        handler.post { navigation?.recevoir(commande) }
    }

    /** Appui long, au joystick, de la touche qui l'a ouvert : le referme. */
    fun fermerParRaccourci() {
        handler.post { if (vue != null) fermer("appui long du joystick sur son raccourci") }
    }

    /** Raccourci : ouvre le pop-up, ou le referme s'il est déjà là. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (vue != null) { fermer("second appui du raccourci"); return@post }
            if (ouverture) return@post
            ouverture = true
            lecture.execute {
                val clim = runCatching { MG4Hardware.getClimateState() }.getOrNull()
                handler.post {
                    ouverture = false
                    ouvrir(app, clim)
                }
            }
        }
    }

    /** Bornes et valeurs de la clim lues à l'ouverture. */
    private class Clim(
        val tempMin: Int, val tempMax: Int, val fanMin: Int, val fanMax: Int,
        val temp: Int?, val fan: Int?, val auto: Boolean, val ac: Boolean?, val on: Boolean?,
    )

    private fun ouvrir(app: Context, lu: MG4Hardware.ClimateState?) {
        if (vue != null) return
        val r = lu?.reglages()
        val clim = Clim(
            tempMin = lu?.tempMin ?: TEMP_MIN_REPLI, tempMax = lu?.tempMax ?: TEMP_MAX_REPLI,
            fanMin = lu?.fanMin ?: FAN_MIN_REPLI, fanMax = lu?.fanMax ?: FAN_MAX_REPLI,
            temp = lu?.tempC, fan = r?.ventilationReelle ?: lu?.lastManualFan, auto = r?.enAuto == true,
            ac = lu?.acOn, on = lu?.powerOn)
        val dernier = PreClimateSettings.lastRequest(app, PreClimate.minuteDuJour(), clim.temp, clim.fan)
        req = dernier.copy(
            tempC = dernier.tempC.coerceIn(clim.tempMin, clim.tempMax),
            fan = dernier.fan.coerceIn(clim.fanMin, clim.fanMax))
        val c = OverlayKit.contextes(app, LARGEUR_DP, HAUTEUR_DP)
        val v = try {
            val racine = LayoutInflater.from(c.themed).inflate(R.layout.overlay_preclim_setup, null)
            Vues(racine, c.localized, clim).also { brancher(app, it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pop-up non affiché — mise en page : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        OverlayKit.tenirDansLaFenetre(v.racine, (MARGE_FENETRE_DP * c.mesures.density).roundToInt(), "Pré-climatisation")
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(v.racine, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pop-up non affiché — fenêtre : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        vue = v.racine
        gestionnaire = wm
        vues = v
        demarrage = false
        peindre()
        // Après la mise en page : la navigation lit la position des cases à l'écran.
        v.racine.post { naviguerDepuis(JoystickFocus.Position(Int.MAX_VALUE, Int.MAX_VALUE)) }
        AppLogger.i(TAG, "Pop-up affiché — clim ${clim.temp ?: "?"} °C, ventilation ${if (clim.auto) "AUTO" else clim.fan ?: "?"}, " +
            "bornes ${clim.tempMin}–${clim.tempMax} °C / ${clim.fanMin}–${clim.fanMax}")
    }

    fun fermer(raison: String) {
        handler.post {
            val v = vue ?: return@post
            runCatching { gestionnaire?.removeView(v) }
            vue = null
            vues = null
            gestionnaire = null
            navigation = null
            AppLogger.i(TAG, "Pop-up fermé ($raison)")
        }
    }

    // ── Vues ─────────────────────────────────────────────────────────────────

    /** Une ligne « intitulé − valeur + » (item_preclim_stepper). */
    private class Ligne(racine: View) {
        val libelle: TextView = racine.findViewById(R.id.stepper_label)
        val moins: ImageView = racine.findViewById(R.id.stepper_minus)
        val valeur: TextView = racine.findViewById(R.id.stepper_value)
        val plus: ImageView = racine.findViewById(R.id.stepper_plus)
    }

    private class Vues(val racine: View, val loc: Context, val clim: Clim) {
        val themed: Context = racine.context
        val fermer: ImageView = racine.findViewById(R.id.preclim_close)
        val icone: ImageView = racine.findViewById(R.id.preclim_title_icon)
        val modes: LinearLayout = racine.findViewById(R.id.preclim_modes)
        val blocMaintenant: View = racine.findViewById(R.id.preclim_now)
        val rangeeMax: LinearLayout = racine.findViewById(R.id.preclim_max_row)
        val blocHeure: View = racine.findViewById(R.id.preclim_at)
        val premier = Ligne(racine.findViewById(R.id.preclim_row_first))
        val duree = Ligne(racine.findViewById(R.id.preclim_row_run))
        val cycles = Ligne(racine.findViewById(R.id.preclim_row_runs))
        val pause = Ligne(racine.findViewById(R.id.preclim_row_pause))
        val temp = Ligne(racine.findViewById(R.id.preclim_row_temp))
        val ventil = Ligne(racine.findViewById(R.id.preclim_row_fan))
        val barre: LinearLayout = racine.findViewById(R.id.preclim_fan_bar)
        val actuels: TextView = racine.findViewById(R.id.preclim_current)
        val plan: TextView = racine.findViewById(R.id.preclim_plan)
        val chien: TextView = racine.findViewById(R.id.preclim_dog_toggle)
        // Surveillance (issue #180).
        val surveillance: TextView = racine.findViewById(R.id.preclim_surv_toggle)
        val titre: TextView = racine.findViewById(R.id.preclim_title_text)
        val legendeMaintenant: TextView = racine.findViewById(R.id.preclim_now_caption)
        val arretsSurveillance: TextView = racine.findViewById(R.id.preclim_surv_stops)
        val ligneTemp: View = racine.findViewById(R.id.preclim_row_temp)
        val ligneVentil: View = racine.findViewById(R.id.preclim_row_fan)
        val panneauSurveillance: View = racine.findViewById(R.id.preclim_surv_panel)
        val iconeClimCoupee: ImageView = racine.findViewById(R.id.preclim_surv_off_icon)
        val avertSurveillance: TextView = racine.findViewById(R.id.preclim_surv_warning)
        val avertChien: TextView = racine.findViewById(R.id.preclim_dog_warning)
        val annuler: TextView = racine.findViewById(R.id.preclim_cancel)
        val demarrer: TextView = racine.findViewById(R.id.preclim_start)
        val cases = mutableListOf<TextView>()
        val puces = mutableListOf<TextView>()
        val crans = mutableListOf<View>()

        val accent = themed.getColor(R.color.dash_accent)
        val accentDoux = themed.getColor(R.color.dash_accent_dim)
        val bouton = themed.getColor(R.color.dash_btn)
        val bordure = themed.getColor(R.color.dash_border)
        val texte = themed.getColor(R.color.text_primary)
        val texte2 = themed.getColor(R.color.text_secondary)
        val alerte = themed.getColor(R.color.dash_warn)
        val alerteDoux = themed.getColor(R.color.dash_warn_dim)
        val danger = themed.getColor(R.color.dash_danger)
        val dangerDoux = themed.getColor(R.color.dash_danger_dim)
        val surAccent = themed.getColor(R.color.hvac_on_accent)
        val blanc = themed.getColor(R.color.text_active)
        fun dp(x: Float) = OverlayKit.dp(themed, x)

        fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

    private fun brancher(app: Context, v: Vues) {
        v.racine.findViewById<View>(R.id.preclim_backdrop).setOnClickListener { fermer("toucher hors de la carte") }
        v.fermer.setOnClickListener { fermer("bouton de fermeture") }
        v.annuler.setOnClickListener { fermer("Annuler") }
        // L'icône de fermeture et les − / + sont blancs : teintés, ils restent visibles en thème clair.
        v.fermer.imageTintList = ColorStateList.valueOf(v.texte2)
        v.icone.imageTintList = ColorStateList.valueOf(v.accent)
        OverlayKit.fond(v.avertChien, v.alerteDoux, v.alerte, v.dp(1f), v.dp(12f).toFloat())
        v.avertChien.compoundDrawableTintList = ColorStateList.valueOf(v.alerte)
        OverlayKit.fond(v.avertSurveillance, v.alerteDoux, v.alerte, v.dp(1f), v.dp(12f).toFloat())
        v.avertSurveillance.compoundDrawableTintList = ColorStateList.valueOf(v.alerte)
        v.iconeClimCoupee.imageTintList = ColorStateList.valueOf(v.texte2)
        listOf(v.premier, v.duree, v.cycles, v.pause, v.temp, v.ventil).forEach {
            it.moins.imageTintList = ColorStateList.valueOf(v.texte)
            it.plus.imageTintList = ColorStateList.valueOf(v.texte)
        }
        repereJoystick(v)

        // Mode : « Maintenant » d'abord, toujours choisi à l'ouverture.
        AppDialog.segments(v.modes, listOf(v.s(R.string.preclim_mode_now), v.s(R.string.preclim_mode_at)), 0) { rang ->
            changer { it.copy(mode = if (rang == 0) PreClimateMode.NOW else PreClimateMode.AT_TIME) }
            naviguerDepuis(JoystickFocus.Position(0, rang))
        }
        (0 until v.modes.childCount).forEach { v.cases += v.modes.getChildAt(it) as TextView }

        // Durées maximales, en puces.
        PreClimatePlan.MAX_CHOICES.forEach { minutes ->
            val puce = TextView(v.themed).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                text = duree(v, minutes)
                // Mode chien : 2 h imposées, les autres durées sont grisées.
                setOnClickListener { if (req?.dog != true) changer { it.copy(maxMin = minutes) } }
            }
            v.rangeeMax.addView(puce, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (v.puces.isNotEmpty()) marginStart = v.dp(8f)
            })
            v.puces += puce
        }

        v.premier.libelle.text = v.s(R.string.preclim_first)
        v.duree.libelle.text = v.s(R.string.preclim_run)
        v.cycles.libelle.text = v.s(R.string.preclim_runs)
        v.pause.libelle.text = v.s(R.string.preclim_pause)
        v.temp.libelle.text = v.s(R.string.preclim_temp)
        v.ventil.libelle.text = v.s(R.string.preclim_fan)
        pas(v.premier) { r, d -> r.copy(firstMinuteOfDay = PreClimatePlan.stepFirst(r.firstMinuteOfDay, d)) }
        pas(v.duree) { r, d -> r.copy(runMin = PreClimatePlan.clampRun(r.runMin + d * PreClimatePlan.RUN_STEP)) }
        pas(v.cycles) { r, d -> r.copy(runs = PreClimatePlan.clampRuns(r.runs + d)) }
        pas(v.pause) { r, d -> r.copy(pauseMin = PreClimatePlan.clampPause(r.pauseMin + d * PreClimatePlan.PAUSE_STEP)) }
        pas(v.temp) { r, d -> r.copy(tempC = (r.tempC + d).coerceIn(v.clim.tempMin, v.clim.tempMax)) }
        pas(v.ventil) { r, d -> r.copy(fan = (r.fan + d).coerceIn(v.clim.fanMin, v.clim.fanMax)) }

        // Crans de la ventilation, sous sa ligne.
        (v.clim.fanMin..v.clim.fanMax).forEach { _ ->
            val cran = View(v.themed)
            v.barre.addView(cran, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (v.crans.isNotEmpty()) marginStart = v.dp(3f)
            })
            v.crans += cran
        }

        v.chien.setOnClickListener {
            changer { it.copy(dog = !it.dog, surveillance = false) }
            // Les durées entrent dans la navigation ou en sortent : on la refait, sur ce bouton.
            naviguerDepuis(JoystickFocus.Position(Int.MAX_VALUE, 0))
        }
        // Surveillance : exclusive du mode chien ; la clim sort de la navigation.
        v.surveillance.setOnClickListener {
            changer { it.copy(surveillance = !it.surveillance, dog = false) }
            naviguerDepuis(JoystickFocus.Position(Int.MAX_VALUE, 1))
        }
        v.demarrer.setOnClickListener { demarrer(app) }
    }

    /** Branche − et + d'une ligne ; maintenus, ils répètent le pas. */
    private fun pas(l: Ligne, f: (PreClimateRequest, Int) -> PreClimateRequest) {
        AppDialog.repeter(l.moins) { changer { f(it, -1) } }
        AppDialog.repeter(l.plus) { changer { f(it, 1) } }
    }

    private fun changer(f: (PreClimateRequest) -> PreClimateRequest) {
        val r = f(req ?: return)
        req = r
        // Enregistré à chaque changement, et pas seulement à « Démarrer » : fermé sans démarrer, ou
        // démarrage refusé (pas en P), le pop-up rouvre quand même sur ces réglages.
        vues?.let { PreClimateSettings.saveRequest(it.loc, r) }
        peindre()
    }

    private fun peindre() {
        val v = vues ?: return
        val r = req ?: return
        val maintenant = r.mode == PreClimateMode.NOW
        v.blocMaintenant.visibility = if (maintenant) View.VISIBLE else View.GONE
        v.blocHeure.visibility = if (maintenant) View.GONE else View.VISIBLE
        val max = PreClimatePlan.maxMinutes(r)
        v.puces.forEachIndexed { i, puce ->
            val on = PreClimatePlan.MAX_CHOICES[i] == max
            OverlayKit.fond(puce, if (on) v.accentDoux else v.bouton, if (on) v.accent else v.bordure,
                v.dp(if (on) 2f else 1f), v.dp(12f).toFloat())
            puce.setTextColor(if (on) v.texte else v.texte2)
            puce.alpha = if (r.dog && !on) ALPHA_GRISE else 1f
        }
        val mur = PreClimate.minuteDuJour()
        v.premier.valeur.text = heure(v, r.firstMinuteOfDay)
        v.duree.valeur.text = duree(v, r.runMin)
        v.cycles.valeur.text = r.runs.toString()
        v.pause.valeur.text = duree(v, r.pauseMin)
        v.temp.valeur.text = v.s(R.string.preclim_value_celsius, r.tempC)
        v.ventil.valeur.text = r.fan.toString()
        v.crans.forEachIndexed { i, cran ->
            OverlayKit.fond(cran, if (v.clim.fanMin + i <= r.fan) v.accent else v.bordure, rayon = v.dp(3f).toFloat())
        }
        v.actuels.text = v.s(R.string.preclim_current, resumeActuel(v))
        v.chien.visibility = if (maintenant) View.VISIBLE else View.GONE
        v.avertChien.visibility = if (maintenant && r.dog) View.VISIBLE else View.GONE
        peindreBascule(v, v.chien, r.dog, v.accent, v.accentDoux)

        // Surveillance (issue #180) : clim coupée à la place de ses réglages, titre et Démarrer en rouge.
        val surv = maintenant && r.surveillance
        v.surveillance.visibility = if (maintenant) View.VISIBLE else View.GONE
        peindreBascule(v, v.surveillance, surv, v.danger, v.dangerDoux)
        v.titre.text = v.s(if (surv) R.string.preclim_surv else R.string.preclim_title)
        v.icone.setImageResource(if (surv) R.drawable.ic_preclim_cctv else R.drawable.ic_hvac_ac)
        v.icone.imageTintList = ColorStateList.valueOf(if (surv) v.danger else v.accent)
        v.legendeMaintenant.text = v.s(if (surv) R.string.preclim_surv_caption else R.string.preclim_now_caption)
        v.arretsSurveillance.visibility = if (surv) View.VISIBLE else View.GONE
        v.arretsSurveillance.text = v.s(R.string.preclim_surv_stops, PreClimateSettings.minSoc(v.loc))
        listOf(v.ligneTemp, v.ligneVentil, v.barre, v.actuels).forEach { it.visibility = if (surv) View.GONE else View.VISIBLE }
        v.panneauSurveillance.visibility = if (surv) View.VISIBLE else View.GONE
        if (surv) {
            OverlayKit.fond(v.demarrer, v.danger, rayon = v.dp(12f).toFloat())
            v.demarrer.setTextColor(v.blanc)
        } else {
            v.demarrer.setBackgroundResource(R.drawable.bg_dialog_primary)
            v.demarrer.setTextColor(v.surAccent)
        }
        v.plan.text = when {
            surv -> v.s(R.string.preclim_plan_surv, heure(v, (mur + max).roundToInt()))
            maintenant && r.dog -> v.s(R.string.preclim_plan_dog, heure(v, (mur + max).roundToInt()))
            maintenant -> v.s(R.string.preclim_plan_now, heure(v, (mur + max).roundToInt()))
            else -> {
                val cycles = PreClimatePlan.wallTimes(r, mur)
                val debuts = cycles.joinToString(" · ") { heure(v, it.first) }
                val premiers = if (PreClimatePlan.isTomorrow(r.firstMinuteOfDay, mur)) v.s(R.string.preclim_tomorrow, debuts) else debuts
                v.s(R.string.preclim_plan_at, premiers, heure(v, cycles.last().second))
            }
        }
    }

    /** Bouton à bascule du bas (mode chien, surveillance) : cerclé de [vif] quand il est choisi. */
    private fun peindreBascule(v: Vues, t: TextView, choisi: Boolean, vif: Int, doux: Int) {
        OverlayKit.fond(t, if (choisi) doux else v.bouton, if (choisi) vif else v.bordure,
            v.dp(if (choisi) 2f else 1f), v.dp(12f).toFloat())
        t.setTextColor(if (choisi) vif else v.texte2)
        t.compoundDrawableTintList = ColorStateList.valueOf(if (choisi) vif else v.texte2)
    }

    /** « 21 °C · ventilation 4 · A/C » de la clim telle qu'elle est maintenant. */
    private fun resumeActuel(v: Vues): String {
        val c = v.clim
        val temp = c.temp?.let { v.s(R.string.preclim_value_celsius, it) } ?: "–"
        val ventil = if (c.auto) v.s(R.string.clim_auto).uppercase(v.loc.resources.configuration.locales[0]) else c.fan?.toString() ?: "–"
        return v.s(if (c.ac == true) R.string.preclim_summary_ac else R.string.preclim_summary, temp, ventil)
    }

    private fun demarrer(app: Context) {
        val r = req ?: return
        if (demarrage) return
        demarrage = true
        AppLogger.i(TAG, "Pop-up — Démarrer")
        PreClimate.demarrer(app, r,
            surRefus = { texte ->
                demarrage = false
                vues?.let { Toast.makeText(app, it.s(texte), Toast.LENGTH_LONG).show() }
            },
            surDepart = { fermer("démarrage") })
    }

    // ── Joystick ─────────────────────────────────────────────────────────────

    /**
     * (Re)construit la navigation : les cases dépendent du mode. [depart] : ligne et colonne de
     * départ (une ligne au-delà de la dernière = la dernière, « Démarrer »).
     */
    private fun naviguerDepuis(depart: JoystickFocus.Position) {
        val v = vues ?: return
        val r = req ?: return
        val lignes = buildList<List<View>> {
            add(v.cases + v.fermer)
            if (r.mode == PreClimateMode.NOW) {
                // Surveillance : pas de réglages de clim à atteindre.
                add((if (r.dog) emptyList() else v.puces) +
                    (if (r.surveillance) emptyList() else listOf(v.temp.moins, v.temp.plus)))
                if (!r.surveillance) add(listOf(v.ventil.moins, v.ventil.plus))
            } else {
                add(listOf(v.premier.moins, v.premier.plus, v.temp.moins, v.temp.plus))
                add(listOf(v.duree.moins, v.duree.plus, v.ventil.moins, v.ventil.plus))
                add(listOf(v.cycles.moins, v.cycles.plus))
                add(listOf(v.pause.moins, v.pause.plus))
            }
            add((if (r.mode == PreClimateMode.NOW) listOf<View>(v.chien, v.surveillance) else emptyList()) + listOf(v.annuler, v.demarrer))
        }
        // L'anneau de l'ancienne navigation resterait sur sa case, peut-être d'un autre mode.
        (v.cases + v.puces + listOf(v.premier, v.duree, v.cycles, v.pause, v.temp, v.ventil).flatMap { listOf(it.moins, it.plus) } +
            listOf(v.fermer, v.chien, v.surveillance, v.annuler, v.demarrer)).forEach { it.foreground = null }
        val ligne = depart.ligne.coerceIn(0, lignes.lastIndex)
        navigation = OverlayNavigation(
            context = v.themed,
            grille = lignes,
            depart = JoystickFocus.Position(ligne, depart.colonne.coerceIn(0, lignes[ligne].lastIndex)),
            surDeplacement = {},
        ).also { it.afficher() }
    }

    /** Repère de l'en-tête, comme celui du sélecteur de profils. */
    private fun repereJoystick(v: Vues) {
        val utilisable = KeyCaptureService.isEnabled(v.loc)
        val couleur = if (utilisable) v.accent else v.alerte
        val titre = v.racine.findViewById<TextView>(R.id.preclim_joy_title)
        titre.text = if (utilisable) v.s(R.string.hvac_popup_joystick_title)
            else v.s(R.string.hvac_popup_joystick_off_title) + " · " + v.s(R.string.hvac_popup_joystick_off_text)
        titre.setTextColor(couleur)
        titre.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        listOf(R.id.preclim_joy_move, R.id.preclim_joy_ok).forEach { id ->
            v.racine.findViewById<TextView>(id).apply {
                visibility = if (utilisable) View.VISIBLE else View.GONE
                setTextColor(v.texte)
                compoundDrawableTintList = ColorStateList.valueOf(v.accent)
            }
        }
        OverlayKit.fond(v.racine.findViewById(R.id.preclim_joy_chip),
            if (utilisable) v.accentDoux else v.alerteDoux, rayon = v.dp(15f).toFloat())
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    /** Heure murale [minuteDuJour] au format de la voiture (24 h ou 12 h). */
    private fun heure(v: Vues, minuteDuJour: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Math.floorMod(minuteDuJour, 1440) / 60)
            set(Calendar.MINUTE, Math.floorMod(minuteDuJour, 1440) % 60)
        }
        return DateFormat.getTimeFormat(v.loc).format(c.time)
    }

    private fun duree(v: Vues, minutes: Int): String =
        if (minutes >= 60) v.s(R.string.cluster_duration_hm, minutes / 60, minutes % 60)
        else v.s(R.string.cluster_duration_min, minutes)
}
