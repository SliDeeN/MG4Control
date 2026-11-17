package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
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
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateRequest
import com.mg4.control.ui.AppDialog
import com.mg4.control.ui.DashcamUi
import com.mg4.control.util.DashcamApp
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
 * Au toucher seulement depuis le 2026-11-08 (sa demande) : la fenêtre se règle à l'arrêt, le
 * joystick droit n'y navigue plus et garde ses fonctions habituelles. Pas de fermeture au délai :
 * elle reste jusqu'à la croix, « Annuler » ou un second appui du raccourci. Fil principal seulement.
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
    /**
     * Appli caméra (issue #180), lue à l'ouverture : la surveillance est proposée à tous depuis le
     * 2026-11-08 ; l'appli ne fait que dire, sous « Clim coupée », si quelque chose sera filmé.
     */
    private var camera: DashcamApp.Detection = DashcamApp.Detection.Absente
    /** « Surveillance sans caméra » permis (lu à l'ouverture, comme [camera]). */
    private var sansCamera = false
    private val survPossible get() = DashcamApp.surveillancePossible(camera, sansCamera)
    /** Réponse au test de la clé USB lancé à l'ouverture ; `null` tant qu'elle n'est pas là. */
    private var cleUsb: DashcamRemote.Usb? = null

    fun isShowing(): Boolean = vue != null

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
        camera = DashcamApp.detecter(app)
        sansCamera = PreClimateSettings.survWithoutCamera(app)
        cleUsb = null
        req = dernier.copy(
            tempC = dernier.tempC.coerceIn(clim.tempMin, clim.tempMax),
            fan = dernier.fan.coerceIn(clim.fanMin, clim.fanMax),
        )
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
        AppLogger.i(TAG, "Pop-up affiché — clim ${clim.temp ?: "?"} °C, ventilation ${if (clim.auto) "AUTO" else clim.fan ?: "?"}, " +
            "bornes ${clim.tempMin}–${clim.tempMax} °C / ${clim.fanMin}–${clim.fanMax}, " +
            "appli caméra : ${when (val c = camera) {
                is DashcamApp.Detection.Pilotable -> "${c.nom}, pilotable"
                is DashcamApp.Detection.Ancienne -> "${c.nom}, version non pilotable"
                else -> if (sansCamera) "aucune (surveillance sans vidéo permise)" else "aucune"
            }}")
        // La ligne caméra du mode Surveillance dit s'il reste de la place sur la clé (≤ 8 s).
        if (camera.pilotable && PreClimateSettings.dashcamAuto(app)) {
            val ouverte = v.racine
            DashcamRemote.testerUsb(app) { u -> if (vue === ouverte) { cleUsb = u; peindre() } }
        }
    }

    fun fermer(raison: String) {
        handler.post {
            val v = vue ?: return@post
            runCatching { gestionnaire?.removeView(v) }
            vue = null
            vues = null
            gestionnaire = null
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
        val cameraSurveillance: TextView = racine.findViewById(R.id.preclim_surv_camera)
        val bandeauCamera: View = racine.findViewById(R.id.preclim_surv_cam_banner)
        val bandeauIcone: ImageView = racine.findViewById(R.id.preclim_surv_cam_banner_icon)
        val bandeauTitre: TextView = racine.findViewById(R.id.preclim_surv_cam_banner_title)
        val bandeauTexte: TextView = racine.findViewById(R.id.preclim_surv_cam_banner_text)
        val texteClimCoupee: View = racine.findViewById(R.id.preclim_surv_off_text)
        val avertChien: TextView = racine.findViewById(R.id.preclim_dog_warning)
        val annuler: TextView = racine.findViewById(R.id.preclim_cancel)
        val demarrer: TextView = racine.findViewById(R.id.preclim_start)
        // Rappel avant le départ (2026-10-31), par-dessus la carte.
        val rappel: View = racine.findViewById(R.id.preclim_unlock_layer)
        val rappelJamais: TextView = racine.findViewById(R.id.preclim_unlock_never)
        val rappelOk: TextView = racine.findViewById(R.id.preclim_unlock_ok)
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
        val surAlerte = themed.getColor(R.color.hvac_on_heat)
        val section = themed.getColor(R.color.dash_section)
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

        // Mode : « Maintenant » d'abord, toujours choisi à l'ouverture.
        AppDialog.segments(v.modes, listOf(v.s(R.string.preclim_mode_now), v.s(R.string.preclim_mode_at)), 0) { rang ->
            changer { it.copy(mode = if (rang == 0) PreClimateMode.NOW else PreClimateMode.AT_TIME) }
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
        }
        // Surveillance : exclusive du mode chien.
        v.surveillance.setOnClickListener {
            changer { it.copy(surveillance = !it.surveillance, dog = false) }
        }
        v.demarrer.setOnClickListener { demarrer(app) }
        brancherRappel(app, v)
    }

    /**
     * Rappel avant le départ (2026-10-31) : la pastille, les pictogrammes et OK aux couleurs d'un
     * avertissement, comme AppDialog. Toucher à côté de sa carte ramène au réglage sans démarrer.
     */
    private fun brancherRappel(app: Context, v: Vues) {
        OverlayKit.fond(v.racine.findViewById(R.id.preclim_unlock_badge), v.alerteDoux, ovale = true)
        v.racine.findViewById<ImageView>(R.id.preclim_unlock_icon).imageTintList = ColorStateList.valueOf(v.alerte)
        val lignes = listOf(
            listOf(R.id.preclim_unlock_row1, R.id.preclim_unlock_ic1, R.id.preclim_unlock_t1, R.id.preclim_unlock_d1,
                R.string.preclim_unlock_handle, R.string.preclim_unlock_handle_desc),
            listOf(R.id.preclim_unlock_row2, R.id.preclim_unlock_ic2, R.id.preclim_unlock_t2, R.id.preclim_unlock_d2,
                R.string.preclim_unlock_key, R.string.preclim_unlock_key_desc),
            listOf(R.id.preclim_unlock_row3, R.id.preclim_unlock_ic3, R.id.preclim_unlock_t3, R.id.preclim_unlock_d3,
                R.string.preclim_unlock_app, R.string.preclim_unlock_app_desc),
        )
        lignes.forEach { (rangee, icone, titre, texte, chaineTitre, chaineTexte) ->
            OverlayKit.fond(v.racine.findViewById(rangee), v.section, rayon = v.dp(12f).toFloat())
            v.racine.findViewById<ImageView>(icone).imageTintList = ColorStateList.valueOf(v.alerte)
            v.racine.findViewById<TextView>(titre).text = v.s(chaineTitre)
            v.racine.findViewById<TextView>(texte).text = v.s(chaineTexte)
        }
        OverlayKit.fond(v.rappelOk, v.alerte, rayon = v.dp(12f).toFloat())
        v.rappelOk.setTextColor(v.surAlerte)
        v.rappel.setOnClickListener { cacherRappel("toucher hors du rappel") }
        v.rappelOk.setOnClickListener { partir(app, "OK") }
        v.rappelJamais.setOnClickListener {
            PreClimateSettings.setUnlockReminder(app, false)
            partir(app, "Ne plus me rappeler — rappel désactivé")
        }
    }

    private operator fun <T> List<T>.component6(): T = this[5]

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
        peindreCamera(v, surv)
        if (surv && !survPossible) {
            // Aucune appli dashcam : la surveillance ne peut pas démarrer (2026-11-08).
            OverlayKit.fond(v.demarrer, v.bouton, v.bordure, v.dp(1f), v.dp(12f).toFloat())
            v.demarrer.setTextColor(v.texte2)
            v.demarrer.alpha = ALPHA_GRISE
        } else if (surv) {
            OverlayKit.fond(v.demarrer, v.danger, rayon = v.dp(12f).toFloat())
            v.demarrer.setTextColor(v.blanc)
        } else {
            v.demarrer.setBackgroundResource(R.drawable.bg_dialog_primary)
            v.demarrer.setTextColor(v.surAccent)
        }
        if (!(surv && !survPossible)) v.demarrer.alpha = 1f
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

    /**
     * Surveillance et appli caméra (2026-11-08). Appli pilotable et enregistrement auto : une
     * ligne sous « Clim coupée » (point rouge, place sur la clé). Sinon un avertissement dans la
     * colonne de droite, à la place du texte « 0,2 kW » (proposition 1, 2026-11-08 — le bandeau
     * pleine largeur faisait déborder la carte de l'écran) : orange quand l'enregistrement se lance
     * à la main, rouge sans appli — Démarrer est alors grisé.
     */
    private fun peindreCamera(v: Vues, surv: Boolean) {
        val pilotable = camera as? DashcamApp.Detection.Pilotable
        val auto = PreClimateSettings.dashcamAuto(v.loc)
        val filmera = surv && pilotable != null && auto
        v.cameraSurveillance.visibility = if (filmera) View.VISIBLE else View.GONE
        if (filmera && pilotable != null) {
            val texte = SpannableStringBuilder("●  ")
            texte.setSpan(ForegroundColorSpan(v.danger), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            texte.append(v.s(R.string.preclim_surv_cam_on, pilotable.nom))
            DashcamUi.popup(v.loc, cleUsb)?.let { cle ->
                texte.append(" · ")
                val debut = texte.length
                texte.append(cle.texte)
                if (cle.niveau == DashcamUi.Niveau.ALERTE)
                    texte.setSpan(ForegroundColorSpan(v.alerte), debut, texte.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            v.cameraSurveillance.text = texte
            v.cameraSurveillance.setTextColor(v.texte)
        }

        val c = camera
        val bandeau: Triple<String, String, Boolean>? = when {
            !surv || filmera -> null
            pilotable != null -> Triple(v.s(R.string.preclim_surv_ban_off_title),
                v.s(R.string.preclim_surv_ban_off_text, pilotable.nom), false)
            c is DashcamApp.Detection.Ancienne && c.aMettreAJour -> Triple(v.s(R.string.preclim_surv_ban_old_title, c.nom),
                v.s(R.string.preclim_surv_ban_old_text), false)
            c is DashcamApp.Detection.Ancienne -> Triple(v.s(R.string.preclim_surv_ban_incompat_title, c.nom),
                v.s(R.string.preclim_surv_ban_incompat_text), false)
            // Permise sans caméra (2026-11-13) : rappel orange à chaque lancement, Démarrer actif.
            sansCamera -> Triple(v.s(R.string.preclim_cam_nocam_warn_title), v.s(R.string.preclim_surv_ban_novideo_text), false)
            else -> Triple(v.s(R.string.preclim_surv_ban_none_title), v.s(R.string.preclim_surv_ban_none_text), true)
        }
        val visible = bandeau != null
        if (bandeau != null) {
            val (vif, doux) = if (bandeau.third) v.danger to v.dangerDoux else v.alerte to v.alerteDoux
            v.bandeauTitre.text = bandeau.first
            v.bandeauTexte.text = bandeau.second
            OverlayKit.fond(v.bandeauCamera, doux, vif, v.dp(1f), v.dp(12f).toFloat())
            v.bandeauIcone.setImageResource(if (bandeau.third) R.drawable.ic_preclim_cctv else R.drawable.ic_dialog_warning)
            v.bandeauIcone.imageTintList = ColorStateList.valueOf(vif)
        }
        v.bandeauCamera.visibility = if (visible) View.VISIBLE else View.GONE
        // La ligne du bas dit déjà « clim coupée · écran allumé » : le texte « 0,2 kW » cède sa place.
        v.texteClimCoupee.visibility = if (visible) View.GONE else View.VISIBLE
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

    /**
     * « Démarrer ». Rappel activé : il passe avant le décompte, mais seulement si le départ est
     * possible — pas en P, le refus vient tout de suite, sans faire lire le rappel pour rien.
     */
    private fun demarrer(app: Context) {
        val r0 = req ?: return
        if (demarrage) return
        if (r0.mode == PreClimateMode.NOW && r0.surveillance && !survPossible) {
            AppLogger.i(TAG, "Pop-up — Démarrer ignoré : surveillance sans appli dashcam")
            return
        }
        AppLogger.i(TAG, "Pop-up — Démarrer")
        if (!PreClimateSettings.unlockReminder(app)) { lancer(app); return }
        demarrage = true
        lecture.execute {
            val enP = runCatching { MG4Hardware.isVehicleInPark() }.getOrNull() == true
            handler.post {
                demarrage = false
                val v = vues ?: return@post
                if (enP) montrerRappel(v)
                else {
                    AppLogger.w(TAG, "refusée avant le rappel : levier pas confirmé en P")
                    Toast.makeText(app, v.s(R.string.preclim_need_park), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** OK ou « Ne plus me rappeler » : le départ, le rappel restant affiché jusqu'au décompte. */
    private fun partir(app: Context, bouton: String) {
        AppLogger.i(TAG, "Rappel avant le départ — $bouton")
        lancer(app)
    }

    private fun lancer(app: Context) {
        val r = req ?: return
        if (demarrage) return
        demarrage = true
        PreClimate.demarrer(app, r,
            surRefus = { texte ->
                demarrage = false
                cacherRappel("départ refusé")
                vues?.let { Toast.makeText(app, it.s(texte), Toast.LENGTH_LONG).show() }
            },
            surDepart = { fermer("démarrage") })
    }

    /** Rappel avant le départ, par-dessus la carte. */
    private fun montrerRappel(v: Vues) {
        v.rappel.visibility = View.VISIBLE
        AppLogger.i(TAG, "Rappel avant le départ affiché")
    }

    /** Retour au réglage. */
    private fun cacherRappel(raison: String) {
        val v = vues ?: return
        if (v.rappel.visibility != View.VISIBLE) return
        v.rappel.visibility = View.GONE
        AppLogger.i(TAG, "Rappel avant le départ fermé ($raison)")
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
