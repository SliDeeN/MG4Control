package com.mg4.control.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.CampingScreen
import com.mg4.control.model.ClimateSnapshot
import com.mg4.control.model.PreClimateEnd
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateScreenMode
import com.mg4.control.model.PreClimateTrend
import com.mg4.control.service.DashcamRemote
import com.mg4.control.service.OverlayKit
import com.mg4.control.service.PreClimate
import com.mg4.control.service.PreClimateScreen
import com.mg4.control.ui.widget.PreClimateParticlesView
import com.mg4.control.ui.widget.PreClimateRingView
import com.mg4.control.util.FullScreen
import com.mg4.control.util.PreClimateSettings
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pré-climatisation (issue #130) : l'écran de suivi, en PLEIN ÉCRAN (demande du 2026-10-23) et
 * TOUJOURS sombre — fond noir, animation bleue discrète. Quatre vues : le décompte avant
 * verrouillage, le suivi, le mode chien (un message pour les passants) et la fin. Il suit
 * [PreClimate] et n'agit que par lui ; [PreClimateScreen] l'ouvre et le ferme.
 *
 * Plein écran immersif comme DiAuto-MG4 : les barres système (haut et rail de gauche) masquées par
 * les drapeaux d'origine ET le contrôleur d'encarts, remis à chaque retour du focus — un geste peut
 * les faire réapparaître un instant (« collant »).
 *
 * Portes verrouillées, il s'atténue au bout de quelques secondes selon le réglage (normal, atténué,
 * éteint) ; un toucher le rallume 10 s. Le mode chien ne s'atténue jamais : les passants doivent
 * pouvoir lire. « Masquer » (ou retour) le ferme sans arrêter le programme.
 */
class PreClimateActivity : Activity() {

    private lateinit var v: Vues
    private val handler = Handler(Looper.getMainLooper())
    private var eveilJusqua = 0L
    /** Voile posé : (atténuer, mode) — repeint seulement quand l'un change. */
    private var attenue: Pair<Boolean, PreClimateScreenMode>? = null
    private var fermetureFin: Runnable? = null
    private val ecoute: (PreClimate.Etat) -> Unit = { e -> peindre(e) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = OverlayKit.contextes(this, LARGEUR_DP, HAUTEUR_DP, PART_LARGEUR, PART_HAUTEUR)
        // Toujours sombre : c'est l'écran qu'on voit à travers la vitre, voiture verrouillée.
        val nuit = Configuration(c.themed.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val sombre = ContextThemeWrapper(c.themed.createConfigurationContext(nuit), R.style.Theme_MG4Control)
        v = Vues(LayoutInflater.from(sombre).inflate(R.layout.activity_preclim, null), c.localized)
        brancher()
        setContentView(v.racine)
        OverlayKit.tenirDansLaFenetre(v.racine, (MARGE_DP * c.mesures.density).roundToInt(), "Pré-climatisation (suivi)",
            carte = v.racine.findViewById(R.id.preclim_frame))
        pleinEcran()
        journaliserFenetre(c)
        PreClimateScreen.attacher(this)
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        AppLogger.i(TAG, "Écran de suivi affiché (plein écran)")
    }

    /** Raccourci pendant un programme alors que l'écran existe déjà : il revient devant, rallumé. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        eveiller()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        AppLogger.w(TAG, "Écran de suivi : multi-fenêtre $isInMultiWindowMode")
    }

    /**
     * Diagnostic de l'affichage « format téléphone » vu parfois (2026-11-02) : la taille réelle de
     * la fenêtre à chaque changement, l'écran annoncé, l'échelle retenue et le mode multi-fenêtre.
     */
    private fun journaliserFenetre(c: OverlayKit.Contextes) {
        var derniere = ""
        v.racine.addOnLayoutChangeListener { _, gauche, haut, droite, bas, _, _, _, _ ->
            val taille = "${droite - gauche}×${bas - haut}"
            if (taille == derniere) return@addOnLayoutChangeListener
            derniere = taille
            val m = resources.displayMetrics
            AppLogger.i(TAG, "Écran de suivi : fenêtre $taille px (écran ${m.widthPixels}×${m.heightPixels}, " +
                "à la création ${c.mesures.widthPixels}×${c.mesures.heightPixels}, échelle " +
                "${String.format(Locale.US, "%.2f", c.echelle)}, multi-fenêtre $isInMultiWindowMode)")
        }
    }

    override fun onStart() {
        super.onStart()
        PreClimate.ecouteur = ecoute
        // État de l'appli caméra : la pastille « REC » suit sans attendre le relevé suivant.
        DashcamRemote.ecouteur = {
            val r = PreClimate.etat?.req
            if (r?.surveillance == true || r?.camping != null) peindreRec()
        }
        val e = PreClimate.etat
        if (e == null) finish() else peindre(e)
    }

    override fun onStop() {
        if (PreClimate.ecouteur === ecoute) PreClimate.ecouteur = null
        DashcamRemote.ecouteur = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pleinEcran()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        PreClimateScreen.detacher(this)
        super.onDestroy()
    }

    @Deprecated("Remplacé par OnBackPressedDispatcher dans AndroidX ; ici une activité simple.")
    override fun onBackPressed() {
        // Comme « Masquer » : le programme continue, le raccourci réaffiche l'écran.
        finish()
    }

    /** Toujours en plein écran, quel que soit le réglage de l'application. */
    private fun pleinEcran() = FullScreen.apply(window, true)

    // ── Vues ─────────────────────────────────────────────────────────────────

    private class Vues(val racine: View, val loc: Context) {
        val themed: Context = racine.context
        val locale: Locale = loc.resources.configuration.locales[0]
        val voile: View = racine.findViewById(R.id.preclim_dim)
        val particules: PreClimateParticlesView = racine.findViewById(R.id.preclim_particles)
        val decompte: View = racine.findViewById(R.id.preclim_cd)
        val cdAnneau: PreClimateRingView = racine.findViewById(R.id.preclim_cd_ring)
        val cdChiffre: TextView = racine.findViewById(R.id.preclim_cd_number)
        val cdClim: TextView = racine.findViewById(R.id.preclim_cd_clim)
        val cdAvertChien: TextView = racine.findViewById(R.id.preclim_cd_dog_warning)
        val cdUnite: TextView = racine.findViewById(R.id.preclim_cd_unit)
        val cdTitre: TextView = racine.findViewById(R.id.preclim_cd_title)
        val cdTexte: TextView = racine.findViewById(R.id.preclim_cd_text)
        val cdAnnuler: TextView = racine.findViewById(R.id.preclim_cd_cancel)
        val suivi: View = racine.findViewById(R.id.preclim_live)
        val titre: TextView = racine.findViewById(R.id.preclim_live_title)
        val verrou: TextView = racine.findViewById(R.id.preclim_lock_chip)
        val anneau: PreClimateRingView = racine.findViewById(R.id.preclim_ring)
        val anneauTitre: TextView = racine.findViewById(R.id.preclim_ring_label)
        val anneauGrand: TextView = racine.findViewById(R.id.preclim_ring_big)
        val anneauSous: TextView = racine.findViewById(R.id.preclim_ring_sub)
        val etat: TextView = racine.findViewById(R.id.preclim_state_chip)
        val clim: TextView = racine.findViewById(R.id.preclim_clim)
        val lignes: LinearLayout = racine.findViewById(R.id.preclim_lines)
        val dehors: TextView = racine.findViewById(R.id.preclim_outside)
        val batterie: TextView = racine.findViewById(R.id.preclim_battery)
        val puissance: TextView = racine.findViewById(R.id.preclim_power)
        val masquer: TextView = racine.findViewById(R.id.preclim_hide)
        val arreter: TextView = racine.findViewById(R.id.preclim_stop)
        val chien: View = racine.findViewById(R.id.preclim_dog)
        val chienAnneau: PreClimateRingView = racine.findViewById(R.id.preclim_dog_ring)
        val chienPatte: ImageView = racine.findViewById(R.id.preclim_dog_paw)
        val chienTemp: TextView = racine.findViewById(R.id.preclim_dog_temp)
        val chienDehors: TextView = racine.findViewById(R.id.preclim_dog_outside)
        val chienEtat: TextView = racine.findViewById(R.id.preclim_dog_status)
        val chienMasquer: TextView = racine.findViewById(R.id.preclim_dog_hide)
        val chienArreter: TextView = racine.findViewById(R.id.preclim_dog_stop)
        val surv: View = racine.findViewById(R.id.preclim_surv)
        val survCadre: View = racine.findViewById(R.id.preclim_surv_frame)
        val survIcone: ImageView = racine.findViewById(R.id.preclim_surv_icon)
        val survTitre: TextView = racine.findViewById(R.id.preclim_surv_title)
        val survTexte: TextView = racine.findViewById(R.id.preclim_surv_text)
        val survEcall: TextView = racine.findViewById(R.id.preclim_surv_ecall)
        val survEtat: TextView = racine.findViewById(R.id.preclim_surv_status)
        val survRec: TextView = racine.findViewById(R.id.preclim_surv_rec)
        val survMasquer: TextView = racine.findViewById(R.id.preclim_surv_hide)
        val survArreter: TextView = racine.findViewById(R.id.preclim_surv_stop)
        val fin: View = racine.findViewById(R.id.preclim_end)
        val finIcone: TextView = racine.findViewById(R.id.preclim_end_icon)
        val finTitre: TextView = racine.findViewById(R.id.preclim_end_title)
        val finTexte: TextView = racine.findViewById(R.id.preclim_end_text)
        val finFermer: TextView = racine.findViewById(R.id.preclim_end_close)

        val accent = themed.getColor(R.color.dash_accent)
        val accentDoux = themed.getColor(R.color.dash_accent_dim)
        val chaud = themed.getColor(R.color.preclim_heat)
        val vert = themed.getColor(R.color.dash_eco)
        val alerte = themed.getColor(R.color.dash_warn)
        val alerteDoux = themed.getColor(R.color.dash_warn_dim)
        val danger = themed.getColor(R.color.dash_danger)
        val dangerDoux = themed.getColor(R.color.dash_danger_dim)
        val bouton = themed.getColor(R.color.dash_btn)
        val bordure = themed.getColor(R.color.dash_border)
        val texte = themed.getColor(R.color.text_primary)
        val texte2 = themed.getColor(R.color.text_secondary)
        val texteBas = themed.getColor(R.color.dash_text_lo)
        fun dp(x: Float) = OverlayKit.dp(themed, x)

        fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

    private fun brancher() {
        v.racine.setOnClickListener { eveiller() }
        v.voile.setOnClickListener { eveiller() }
        v.cdAnnuler.setOnClickListener { PreClimate.annuler() }
        v.masquer.setOnClickListener { PreClimateScreen.fermer("Masquer") }
        v.arreter.setOnClickListener { PreClimate.arreter() }
        v.chienMasquer.setOnClickListener { PreClimateScreen.fermer("Masquer (mode chien)") }
        v.chienArreter.setOnClickListener { PreClimate.arreter() }
        v.survMasquer.setOnClickListener { PreClimateScreen.fermer("Masquer (surveillance)") }
        v.survArreter.setOnClickListener { PreClimate.arreter() }
        v.finFermer.setOnClickListener {
            PreClimate.oublierFin()
            PreClimateScreen.fermer("Fermer")
        }
        listOf(v.arreter, v.chienArreter, v.survArreter).forEach { OverlayKit.fond(it, v.dangerDoux, v.danger, v.dp(1f), v.dp(12f).toFloat()) }
        // Surveillance (issue #180) : cadre rouge FIXE sur tout l'écran, pictogramme et titre en rouge.
        OverlayKit.fond(v.survCadre, Color.TRANSPARENT, v.danger, v.dp(8f))
        v.survIcone.imageTintList = ColorStateList.valueOf(v.danger)
        v.survTitre.setTextColor(v.danger)
        OverlayKit.fond(v.cdAvertChien, v.alerteDoux, v.alerte, v.dp(1f), v.dp(12f).toFloat())
        v.cdAvertChien.compoundDrawableTintList = ColorStateList.valueOf(v.alerte)
    }

    /** Un toucher : l'écran se rallume pour [EVEIL_MS]. */
    fun eveiller() {
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        attenuer(false, attenue?.second ?: PreClimateSettings.screenMode(v.loc))
    }

    /**
     * Mode camping (2026-11-24) : le voile suit son propre choix — Atténué et Éteint comme ceux de la
     * page, Plein jamais atténué, Suivi le réglage de la page.
     */
    private fun modeEcran(e: PreClimate.Etat): PreClimateScreenMode = when (e.req.camping?.screen) {
        CampingScreen.DIM -> PreClimateScreenMode.DIM
        CampingScreen.OFF -> PreClimateScreenMode.OFF
        CampingScreen.FULL -> PreClimateScreenMode.NORMAL
        else -> PreClimateSettings.screenMode(v.loc)
    }

    /**
     * Voile et luminosité de la fenêtre : selon le réglage, pendant l'attente ; rendus au toucher.
     * [mode] : celui de la page Garde-voiture, ou celui choisi pour le camping ([modeEcran]).
     */
    private fun attenuer(oui: Boolean, mode: PreClimateScreenMode = PreClimateSettings.screenMode(v.loc)) {
        if (attenue == (oui to mode)) return
        attenue = oui to mode
        val voile = when {
            !oui || mode == PreClimateScreenMode.NORMAL -> 0f
            mode == PreClimateScreenMode.OFF -> VOILE_ETEINT
            else -> VOILE_ATTENUE
        }
        v.voile.visibility = if (voile > 0f) View.VISIBLE else View.GONE
        v.voile.alpha = voile
        window.attributes = window.attributes.apply {
            screenBrightness = if (voile > 0f) LUMINOSITE_MIN else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // ── Peinture ─────────────────────────────────────────────────────────────

    private fun peindre(e: PreClimate.Etat) {
        if (e.phase == PreClimate.Phase.ENDED && e.end == PreClimateEnd.CANCELLED) { quitterAnnule(e); return }
        val maintenant = SystemClock.elapsedRealtime()
        val chien = e.req.dog && e.phase == PreClimate.Phase.ACTIVE
        val surv = e.req.surveillance && e.phase == PreClimate.Phase.ACTIVE
        // Mode camping (2026-11-24) : l'écran d'avertissement, sauf « Suivi » (l'écran de suivi).
        val camp = e.req.camping != null && e.phase == PreClimate.Phase.ACTIVE
        val campAvert = camp && e.req.camping?.screen != CampingScreen.FOLLOW
        v.decompte.visibility = if (e.phase == PreClimate.Phase.COUNTDOWN) View.VISIBLE else View.GONE
        v.suivi.visibility = if (e.phase == PreClimate.Phase.ACTIVE && !chien && !surv && !campAvert) View.VISIBLE else View.GONE
        v.chien.visibility = if (chien) View.VISIBLE else View.GONE
        v.surv.visibility = if (surv || campAvert) View.VISIBLE else View.GONE
        v.fin.visibility = if (e.phase == PreClimate.Phase.ENDED) View.VISIBLE else View.GONE
        when {
            e.phase == PreClimate.Phase.COUNTDOWN -> peindreDecompte(e, maintenant)
            campAvert -> peindreCamping(e)
            surv -> peindreSurveillance(e)
            chien -> peindreChien(e, maintenant)
            e.phase == PreClimate.Phase.ACTIVE -> peindreSuivi(e, maintenant)
            else -> peindreFin(e)
        }
        // Le mode chien ne s'atténue jamais : les passants doivent pouvoir lire. La surveillance non
        // plus, sauf si le réglage « Appliquer aussi à la surveillance » le demande (2026-11-13). Le
        // camping suit son propre choix d'écran.
        attenuer(e.phase == PreClimate.Phase.ACTIVE && maintenant > eveilJusqua &&
            (camp || PreClimateScreenMode.mayDim(chien, surv, PreClimateSettings.survFollowsScreen(v.loc))),
            if (camp) modeEcran(e) else PreClimateSettings.screenMode(v.loc))
        peindreParticules(e)
    }

    /**
     * Particules sur tout l'écran tant que la clim tourne (2026-10-23) ; arrêtées sous le voile,
     * où on ne les verrait plus, et pendant l'attente, où rien ne chauffe ni ne refroidit.
     */
    private fun peindreParticules(e: PreClimate.Etat) {
        // Ni sur la surveillance, ni sur l'avertissement du camping : seulement l'écran de suivi.
        val avertCamping = e.req.camping?.let { it.screen != CampingScreen.FOLLOW } == true
        val enMarche = e.phase == PreClimate.Phase.ACTIVE && e.step is PreClimatePlan.Step.Running && !e.req.surveillance &&
            !avertCamping && v.voile.visibility != View.VISIBLE
        v.particules.tendance = PreClimatePlan.trend(e.req.tempC, e.outsideC)
        v.particules.intensite = PreClimatePlan.intensity(e.req.tempC, e.outsideC)
        v.particules.visibility = if (enMarche) View.VISIBLE else View.GONE
        v.particules.actif = enMarche
    }

    /** Couleur des particules, reprise par l'anneau et la température : orange si la clim chauffe, sinon bleu. */
    private fun couleurClim(e: PreClimate.Etat): Int =
        if (e.step is PreClimatePlan.Step.Running &&
            PreClimatePlan.trend(e.req.tempC, e.outsideC) == PreClimateTrend.HEATING) v.chaud else v.accent

    private fun peindreDecompte(e: PreClimate.Etat, maintenant: Long) {
        if (e.waitReady) { peindreAttenteReady(e); return }
        val reste = (e.lockAtMs - maintenant).coerceAtLeast(0L)
        val secondes = ((reste + 999) / 1000).toInt()
        val camp = e.req.camping != null
        // Camping : jusqu'à 10 min, en « m:ss » au-delà d'une minute (2026-11-24).
        v.cdChiffre.text = if (secondes >= 60) "${secondes / 60}:${"%02d".format(Locale.US, secondes % 60)}" else secondes.toString()
        v.cdChiffre.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (secondes >= 60) 64f else 96f)
        v.cdUnite.text = v.s(if (camp) R.string.preclim_cd_unit_camp else R.string.preclim_cd_unit)
        v.cdTitre.text = v.s(if (camp) R.string.preclim_cd_camp_title else R.string.preclim_cd_title)
        v.cdTexte.text = v.s(if (camp) R.string.preclim_cd_camp_text else R.string.preclim_cd_text)
        v.cdAnneau.anim = PreClimateRingView.Anim.AUCUNE
        v.cdAnneau.fraction = reste.toFloat() / (e.lockAtMs - e.startMs).coerceAtLeast(1L)
        val clim = resumeDemande(e)
        v.cdClim.text = when {
            camp -> v.s(R.string.preclim_cd_camp, clim)
            e.req.surveillance -> v.s(R.string.preclim_cd_surv)
            e.req.mode == PreClimateMode.NOW -> v.s(R.string.preclim_cd_now, clim)
            else -> v.s(R.string.preclim_cd_at, heure(e.wallTimes.firstOrNull()?.first ?: 0), clim)
        }
        // Le même encadré, avec le texte du mode choisi.
        v.cdAvertChien.visibility = if (e.req.dog || e.req.surveillance) View.VISIBLE else View.GONE
        v.cdAvertChien.text = v.s(if (e.req.surveillance) R.string.preclim_surv_warning else R.string.preclim_dog_warning)
    }

    /**
     * Camping (issue #194, 2026-11-26) : avant le décompte, la voiture doit passer une fois en READY.
     * Pas de limite d'attente : « Annuler » seulement.
     */
    private fun peindreAttenteReady(e: PreClimate.Etat) {
        v.cdChiffre.text = "READY"
        v.cdChiffre.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        v.cdUnite.text = v.s(R.string.preclim_cd_camp_ready_unit)
        v.cdTitre.text = v.s(R.string.preclim_cd_camp_ready_title)
        v.cdTexte.text = v.s(R.string.preclim_cd_camp_ready_text)
        v.cdAnneau.anim = PreClimateRingView.Anim.AUCUNE
        v.cdAnneau.fraction = 1f
        v.cdClim.text = v.s(R.string.preclim_cd_camp, resumeDemande(e))
        v.cdAvertChien.visibility = View.GONE
    }

    private fun peindreSuivi(e: PreClimate.Etat, maintenant: Long) {
        v.titre.text = if (e.req.camping != null) v.s(R.string.preclim_camp) + " · " + heure(PreClimate.minuteDuJour().toInt())
            else v.s(R.string.preclim_live_title, heure(PreClimate.minuteDuJour().toInt()))
        peindreVerrou(e)
        val step = e.step
        v.anneau.fraction = step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        v.anneau.couleur = couleurClim(e)
        val aUneHeure = e.req.mode == PreClimateMode.AT_TIME
        // Durée illimitée (camping) : le temps écoulé, anneau plein, tant que les portes restent verrouillées.
        if (step is PreClimatePlan.Step.Running && !aUneHeure && PreClimatePlan.isUnlimited(e.cycles[step.index])) {
            v.anneau.anim = PreClimateRingView.Anim.ONDES
            v.anneau.fraction = 1f
            v.anneauTitre.text = v.s(R.string.preclim_unlimited_label)
            v.anneauGrand.text = duree(((maintenant - step.fromMs) / 60_000L).toInt())
            v.anneauSous.text = v.s(R.string.preclim_unlimited_sub)
            pastille(v.etat, v.s(R.string.preclim_state_run), v.accentDoux, v.accent)
        } else when (step) {
            is PreClimatePlan.Step.Waiting -> {
                v.anneau.anim = PreClimateRingView.Anim.ORBITE
                v.anneauTitre.text = v.s(R.string.preclim_wait_label)
                v.anneauGrand.text = duree(minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = v.s(R.string.preclim_at_time, heure(e.wallTimes.getOrNull(step.index)?.first ?: 0))
                pastille(v.etat, v.s(R.string.preclim_state_wait), v.bouton, v.texte2)
            }
            is PreClimatePlan.Step.Running -> {
                v.anneau.anim = PreClimateRingView.Anim.ONDES
                v.anneauTitre.text = if (aUneHeure) v.s(R.string.preclim_cycle, step.index + 1, e.cycles.size)
                    else v.s(R.string.preclim_remaining)
                v.anneauGrand.text = duree(minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = if (aUneHeure) v.s(R.string.preclim_until, heure(e.wallTimes.getOrNull(step.index)?.second ?: 0))
                    else v.s(R.string.preclim_stop_at, heure((e.startWallMinute + e.req.maxMin).roundToInt()))
                pastille(v.etat, v.s(R.string.preclim_state_run), v.accentDoux, v.accent)
            }
            else -> {
                v.anneau.anim = PreClimateRingView.Anim.AUCUNE
                v.anneauTitre.text = ""
                v.anneauGrand.text = "–"
                v.anneauSous.text = ""
            }
        }
        v.clim.text = SpannableStringBuilder(resumeDemande(e)).apply {
            // La consigne prend la couleur des particules.
            val temp = v.s(R.string.preclim_value_celsius, e.req.tempC)
            val i = indexOf(temp)
            if (i >= 0) setSpan(ForegroundColorSpan(couleurClim(e)), i, i + temp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        lignes(e, step)
        v.dehors.text = v.s(R.string.preclim_outside, temperatureDehors(e))
        v.batterie.text = v.s(R.string.preclim_battery, batterie(e))
        // Consommation (issue #210) : absente tant que la mesure est illisible.
        v.puissance.text = e.powerW?.let { v.s(R.string.preclim_power, watts(it)) }.orEmpty()
        v.puissance.visibility = if (e.powerW != null) View.VISIBLE else View.GONE
    }

    /**
     * Mode chien : le message pour les passants, la température réglée en grand, et de quoi
     * arrêter pour le conducteur. Les ondes tournent autour de la patte tant que la clim marche.
     */
    private fun peindreChien(e: PreClimate.Etat, maintenant: Long) {
        val enMarche = e.step is PreClimatePlan.Step.Running
        v.chienAnneau.anim = if (enMarche) PreClimateRingView.Anim.ONDES else PreClimateRingView.Anim.ORBITE
        v.chienAnneau.fraction = e.step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        val couleur = couleurClim(e)
        v.chienAnneau.couleur = couleur
        v.chienPatte.imageTintList = ColorStateList.valueOf(couleur)
        v.chienTemp.setTextColor(couleur)
        v.chienTemp.text = v.s(R.string.preclim_value_celsius, e.req.tempC)
        v.chienDehors.text = v.s(R.string.preclim_dog_outside, temperatureDehors(e))
        v.chienEtat.text = v.s(R.string.preclim_dog_status, heure(e.startWallMinute.toInt()), batterieEtPuissance(e))
    }

    /**
     * Mode camping (2026-11-24) : l'écran de la surveillance, avec les messages choisis — « Surveillance
     * vidéo active » (titre et texte de la surveillance), « eCall actif », en grand s'il est seul —, la
     * pastille REC si la vidéo tourne. Sans limite, READY ou non (issue #194) : tant que les portes
     * restent verrouillées.
     */
    private fun peindreCamping(e: PreClimate.Etat) {
        val c = e.req.camping ?: return
        // Message caméra dès qu'il est choisi (2026-11-25) : une dashcam non pilotée par MG4Control peut
        // filmer seule en READY. La pastille REC, elle, ne suit que notre vidéo. Sans message, la tente.
        val camera = c.msgCamera
        v.survIcone.setImageResource(if (camera) R.drawable.ic_preclim_cctv else R.drawable.ic_preclim_tent)
        v.survTitre.visibility = if (camera) View.VISIBLE else View.GONE
        v.survTexte.visibility = if (camera) View.VISIBLE else View.GONE
        v.survEcall.visibility = if (c.msgEcall) View.VISIBLE else View.GONE
        val seul = c.msgEcall && !camera
        v.survEcall.text = v.s(R.string.preclim_camp_msg_ecall)
        v.survEcall.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (seul) 58f else 28f)
        v.survEcall.setTextColor(if (seul) v.danger else v.texte)
        v.survEtat.text = v.s(R.string.preclim_camp_status, heure(e.startWallMinute.toInt()), batterieEtPuissance(e))
        peindreRec()
    }

    /** Surveillance (issue #180) : l'avertissement est fixe ; la ligne d'état et « REC » changent. */
    private fun peindreSurveillance(e: PreClimate.Etat) {
        // Les vues partagées avec le camping reprennent leur état de la surveillance.
        v.survIcone.setImageResource(R.drawable.ic_preclim_cctv)
        v.survTitre.visibility = View.VISIBLE
        v.survTexte.visibility = View.VISIBLE
        v.survEcall.visibility = View.GONE
        v.survEtat.text = v.s(R.string.preclim_surv_status, heure(e.startWallMinute.toInt()), batterieEtPuissance(e),
            heure((e.startWallMinute + e.req.maxMin).roundToInt()))
        peindreRec()
    }

    /**
     * Pastille « REC » (2026-11-08, variante A) : point rouge fixe et durée filmée, d'après l'état
     * annoncé par l'appli caméra ([DashcamRemote]) ; grise en pause, absente si rien ne filme.
     */
    private fun peindreRec() {
        val rec = DashcamRemote.rec()
        if (rec == null) { v.survRec.visibility = View.GONE; return }
        val couleur = if (rec.enPause) v.texteBas else v.danger
        val texte = v.s(if (rec.enPause) R.string.preclim_surv_rec_paused else R.string.preclim_surv_rec, rec.duree)
        v.survRec.text = SpannableStringBuilder("●  $texte").apply {
            setSpan(ForegroundColorSpan(couleur), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.survRec.setTextColor(if (rec.enPause) v.texteBas else v.texte)
        OverlayKit.fond(v.survRec, if (rec.enPause) v.bouton else v.dangerDoux, if (rec.enPause) v.bordure else v.danger,
            v.dp(1f), v.dp(22f).toFloat())
        v.survRec.visibility = View.VISIBLE
    }

    private fun peindreVerrou(e: PreClimate.Etat) = when (e.lock) {
        PreClimate.LockState.PENDING -> pastille(v.verrou, v.s(R.string.preclim_lock_pending), v.bouton, v.texte2)
        PreClimate.LockState.LOCKED -> pastille(v.verrou, v.s(R.string.preclim_lock_ok), v.bouton, v.texte)
        PreClimate.LockState.FAILED -> pastille(v.verrou, v.s(R.string.preclim_lock_failed), v.alerteDoux, v.alerte)
        // Camping sans verrouillage : pas de pastille.
        PreClimate.LockState.NONE -> { v.verrou.visibility = View.GONE }
    }.also { if (e.lock != PreClimate.LockState.NONE) v.verrou.visibility = View.VISIBLE }

    /** Les cycles et leur état (à une heure) ; le début et la fin prévue (tout de suite). */
    private fun lignes(e: PreClimate.Etat, step: PreClimatePlan.Step?) {
        v.lignes.removeAllViews()
        val textes: List<CharSequence> = if (e.req.mode == PreClimateMode.AT_TIME) {
            val courant = when (step) {
                is PreClimatePlan.Step.Waiting -> step.index
                is PreClimatePlan.Step.Running -> step.index
                else -> e.cycles.size
            }
            e.wallTimes.mapIndexed { i, (debut, fin) ->
                val fait = i < courant
                val enCours = i == courant
                val puce = if (fait) "✓ " else "● "
                SpannableStringBuilder(puce).apply {
                    setSpan(ForegroundColorSpan(if (fait) v.vert else if (enCours) v.accent else v.texteBas), 0, puce.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(v.s(R.string.preclim_run_line, i + 1, heure(debut), heure(fin)))
                    setSpan(ForegroundColorSpan(if (enCours) v.texte else v.texte2), puce.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else {
            listOf(
                v.s(R.string.preclim_started_at, heure(e.startWallMinute.toInt())),
                if (e.cycles.firstOrNull()?.let { PreClimatePlan.isUnlimited(it) } == true) v.s(R.string.preclim_unlimited_line)
                else v.s(R.string.preclim_off_at, heure((e.startWallMinute + e.req.maxMin).roundToInt())),
            )
        }
        textes.forEach { t ->
            v.lignes.addView(TextView(v.themed).apply {
                text = t
                setTextColor(v.texte2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                maxLines = 1
                setPadding(0, v.dp(3f), 0, v.dp(3f))
            })
        }
    }

    /**
     * Annulé pendant le décompte : pas d'écran de fin, un simple toast. Avant (2026-11-02), la vue
     * de fin était rendue visible avant d'être remplie, et le temps que l'activité se ferme on
     * voyait un « Fermer » seul sur fond noir. Écran vidé, puis fermé.
     */
    private fun quitterAnnule(e: PreClimate.Etat) {
        if (isFinishing) return
        v.racine.visibility = View.INVISIBLE
        Toast.makeText(v.loc, v.s(when {
            e.req.camping != null -> R.string.preclim_camp_cancelled
            e.req.surveillance -> R.string.preclim_surv_cancelled
            else -> R.string.preclim_cancelled
        }), Toast.LENGTH_SHORT).show()
        PreClimate.oublierFin()
        finish()
    }

    private fun peindreFin(e: PreClimate.Etat) {
        val fin = e.end ?: return
        val heureFin = heure((e.endWallMinute ?: PreClimate.minuteDuJour()).toInt())
        val surv = e.req.surveillance
        val camp = e.req.camping != null
        val (titre, couleur, icone) = when (fin) {
            PreClimateEnd.DONE -> Triple(v.s(when { camp -> R.string.preclim_camp_end; surv -> R.string.preclim_surv_end_done; else -> R.string.preclim_end_done }), v.vert, "✓")
            PreClimateEnd.BATTERY -> Triple(v.s(R.string.preclim_end_battery, e.minSoc), v.alerte, "!")
            PreClimateEnd.UNLOCKED -> Triple(v.s(R.string.preclim_end_back), v.vert, "✓")
            PreClimateEnd.DRIVING -> Triple(v.s(if (surv) R.string.preclim_surv_end_driving else R.string.preclim_end_driving), v.accent, "✓")
            PreClimateEnd.STOPPED, PreClimateEnd.CANCELLED ->
                Triple(v.s(when { camp -> R.string.preclim_camp_end; surv -> R.string.preclim_surv_end_stopped; else -> R.string.preclim_end_stopped }), v.accent, "✓")
        }
        val texte = buildList {
            // Camping : le temps passé, de l'heure de départ à celle de fin.
            if (camp && e.endWallMinute != null) add(v.s(R.string.preclim_camp_end_ran,
                duree(Math.floorMod((e.endWallMinute - e.startWallMinute).roundToInt(), 1440))))
            else if (fin == PreClimateEnd.DONE) add(when {
                surv -> v.s(R.string.preclim_surv_end_ran, duree(e.req.maxMin))
                e.req.mode == PreClimateMode.AT_TIME -> v.s(R.string.preclim_end_cycles, e.cycles.size, duree(e.req.runMin))
                else -> v.s(R.string.preclim_end_now, duree(e.req.maxMin))
            })
            if (fin.powersOff) add(if (e.powerOffOk == true) v.s(R.string.preclim_end_off, heureFin) else v.s(R.string.preclim_end_power_failed))
            if (fin == PreClimateEnd.UNLOCKED) add(v.s(R.string.preclim_end_back_text, heureFin))
            add(restauration(e.snapshot))
        }
        v.finTitre.text = titre
        v.finTexte.text = texte.joinToString("\n")
        v.finIcone.text = icone
        v.finIcone.setTextColor(couleur)
        OverlayKit.fond(v.finIcone, Color.TRANSPARENT, v.bordure, v.dp(5f), ovale = true)
        if (!fin.powersOff && fermetureFin == null) {
            val r = Runnable {
                fermetureFin = null
                PreClimate.oublierFin()
                finish()
            }
            fermetureFin = r
            handler.postDelayed(r, FIN_PRESENT_MS)
        }
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private fun pastille(t: TextView, texte: String, fond: Int, couleur: Int) {
        t.text = texte
        t.setTextColor(couleur)
        t.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        OverlayKit.fond(t, fond, rayon = v.dp(17f).toFloat())
    }

    private fun temperatureDehors(e: PreClimate.Etat): String =
        e.outsideC?.let { v.s(R.string.preclim_value_celsius, it.roundToInt()) } ?: "–"

    private fun batterie(e: PreClimate.Etat): String =
        e.soc?.let { v.s(R.string.preclim_value_percent, it.toInt()) } ?: "–"

    /**
     * Ligne d'état du mode chien et de la surveillance (issue #210) : « 78 % · 2 100 W » — la
     * puissance suit la batterie, et disparaît tant qu'elle est illisible.
     */
    private fun batterieEtPuissance(e: PreClimate.Etat): String =
        batterie(e) + (e.powerW?.let { " · " + watts(it) } ?: "")

    /** « 1 850 W », séparateur de milliers de la langue. */
    private fun watts(w: Int): String =
        java.text.NumberFormat.getIntegerInstance(v.locale).format(w) + " W"

    /** « 25 °C · ventilation 8 · A/C » : ce que la pré-climatisation règle (compresseur toujours en marche, sauf camping sans A/C). */
    private fun resumeDemande(e: PreClimate.Etat): String = v.s(
        if (e.req.camping?.ac == false) R.string.preclim_summary else R.string.preclim_summary_ac,
        v.s(R.string.preclim_value_celsius, e.req.tempC), e.req.fan.toString())

    /** « Clim rétablie : 21 °C · ventilation 4 » — ou « arrêtée (…) » si elle l'était. */
    private fun restauration(s: ClimateSnapshot): String {
        val temp = s.tempC?.let { v.s(R.string.preclim_value_celsius, it) } ?: "–"
        val ventil = if (s.auto == true) v.s(R.string.clim_auto).uppercase(v.locale) else s.fan?.toString() ?: "–"
        val resume = v.s(if (s.ac == true) R.string.preclim_summary_ac else R.string.preclim_summary, temp, ventil)
        return v.s(if (s.powerOn == false) R.string.preclim_end_restored_off else R.string.preclim_end_restored, resume)
    }

    /** Minutes restantes jusqu'à [jusqua], arrondies au-dessus : « 1 min » jusqu'à la dernière seconde. */
    private fun minutesRestantes(jusqua: Long, maintenant: Long): Int =
        (((jusqua - maintenant).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    private fun heure(minuteDuJour: Int): String {
        val m = Math.floorMod(minuteDuJour, 1440)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }
        return DateFormat.getTimeFormat(v.loc).format(c.time)
    }

    private fun duree(minutes: Int): String =
        if (minutes >= 60) v.s(R.string.cluster_duration_hm, minutes / 60, minutes % 60)
        else v.s(R.string.cluster_duration_min, minutes)

    private companion object {
        const val TAG = PreClimate.TAG
        /** Contenu de base, agrandi pour remplir l'écran — presque tout, barres masquées. */
        const val LARGEUR_DP = 1280f
        const val HAUTEUR_DP = 480f
        const val PART_LARGEUR = 0.98f
        const val PART_HAUTEUR = 0.95f
        const val MARGE_DP = 8f
        /** Allumé ce temps à l'ouverture et après un toucher, puis atténué selon le réglage. */
        const val EVEIL_MS = 10_000L
        const val VOILE_ATTENUE = 0.6f
        /** « Éteint » : noir complet (2026-11-13, 97 % avant), luminosité au minimum ([LUMINOSITE_MIN]). */
        const val VOILE_ETEINT = 1f
        const val LUMINOSITE_MIN = 0.01f
        /** Fin où le conducteur est là (retour, arrêt) : l'écran s'en va de lui-même. */
        const val FIN_PRESENT_MS = 30_000L
    }
}
