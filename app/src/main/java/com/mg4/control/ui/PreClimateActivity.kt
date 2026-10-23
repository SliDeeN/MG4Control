package com.mg4.control.ui

import android.app.Activity
import android.content.Context
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ClimateSnapshot
import com.mg4.control.model.PreClimateEnd
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateScreenMode
import com.mg4.control.service.OverlayKit
import com.mg4.control.service.PreClimate
import com.mg4.control.service.PreClimateScreen
import com.mg4.control.ui.widget.PreClimateRingView
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
    private var attenue: Boolean? = null
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
        OverlayKit.tenirDansLaFenetre(v.racine, (MARGE_DP * c.mesures.density).roundToInt(), "Pré-climatisation (suivi)")
        pleinEcran()
        PreClimateScreen.attacher(this)
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        AppLogger.i(TAG, "Écran de suivi affiché (plein écran)")
    }

    override fun onStart() {
        super.onStart()
        PreClimate.ecouteur = ecoute
        val e = PreClimate.etat
        if (e == null) finish() else peindre(e)
    }

    override fun onStop() {
        if (PreClimate.ecouteur === ecoute) PreClimate.ecouteur = null
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

    /** Barres système masquées : drapeaux d'origine (Android 9) et contrôleur d'encarts. */
    private fun pleinEcran() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── Vues ─────────────────────────────────────────────────────────────────

    private class Vues(val racine: View, val loc: Context) {
        val themed: Context = racine.context
        val locale: Locale = loc.resources.configuration.locales[0]
        val voile: View = racine.findViewById(R.id.preclim_dim)
        val decompte: View = racine.findViewById(R.id.preclim_cd)
        val cdAnneau: PreClimateRingView = racine.findViewById(R.id.preclim_cd_ring)
        val cdChiffre: TextView = racine.findViewById(R.id.preclim_cd_number)
        val cdClim: TextView = racine.findViewById(R.id.preclim_cd_clim)
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
        val fin: View = racine.findViewById(R.id.preclim_end)
        val finIcone: TextView = racine.findViewById(R.id.preclim_end_icon)
        val finTitre: TextView = racine.findViewById(R.id.preclim_end_title)
        val finTexte: TextView = racine.findViewById(R.id.preclim_end_text)
        val finFermer: TextView = racine.findViewById(R.id.preclim_end_close)

        val accent = themed.getColor(R.color.dash_accent)
        val accentDoux = themed.getColor(R.color.dash_accent_dim)
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
        v.finFermer.setOnClickListener {
            PreClimate.oublierFin()
            PreClimateScreen.fermer("Fermer")
        }
        listOf(v.arreter, v.chienArreter).forEach { OverlayKit.fond(it, v.dangerDoux, v.danger, v.dp(1f), v.dp(12f).toFloat()) }
        v.chienPatte.imageTintList = ColorStateList.valueOf(v.accent)
    }

    /** Un toucher : l'écran se rallume pour [EVEIL_MS]. */
    fun eveiller() {
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        attenuer(false)
    }

    /** Voile et luminosité de la fenêtre : selon le réglage, pendant l'attente ; rendus au toucher. */
    private fun attenuer(oui: Boolean) {
        if (oui == attenue) return
        attenue = oui
        val mode = PreClimateSettings.screenMode(v.loc)
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
        val maintenant = SystemClock.elapsedRealtime()
        val chien = e.req.dog && e.phase == PreClimate.Phase.ACTIVE
        v.decompte.visibility = if (e.phase == PreClimate.Phase.COUNTDOWN) View.VISIBLE else View.GONE
        v.suivi.visibility = if (e.phase == PreClimate.Phase.ACTIVE && !chien) View.VISIBLE else View.GONE
        v.chien.visibility = if (chien) View.VISIBLE else View.GONE
        v.fin.visibility = if (e.phase == PreClimate.Phase.ENDED) View.VISIBLE else View.GONE
        when {
            e.phase == PreClimate.Phase.COUNTDOWN -> peindreDecompte(e, maintenant)
            chien -> peindreChien(e, maintenant)
            e.phase == PreClimate.Phase.ACTIVE -> peindreSuivi(e, maintenant)
            else -> peindreFin(e)
        }
        // Le mode chien ne s'atténue jamais : les passants doivent pouvoir lire.
        attenuer(e.phase == PreClimate.Phase.ACTIVE && !chien && maintenant > eveilJusqua)
    }

    private fun peindreDecompte(e: PreClimate.Etat, maintenant: Long) {
        val reste = (e.lockAtMs - maintenant).coerceAtLeast(0L)
        v.cdChiffre.text = ((reste + 999) / 1000).toString()
        v.cdAnneau.anim = PreClimateRingView.Anim.AUCUNE
        v.cdAnneau.fraction = reste.toFloat() / (e.lockAtMs - e.startMs).coerceAtLeast(1L)
        val clim = resumeDemande(e)
        v.cdClim.text = if (e.req.mode == PreClimateMode.NOW) v.s(R.string.preclim_cd_now, clim)
            else v.s(R.string.preclim_cd_at, heure(e.wallTimes.firstOrNull()?.first ?: 0), clim)
    }

    private fun peindreSuivi(e: PreClimate.Etat, maintenant: Long) {
        v.titre.text = v.s(R.string.preclim_live_title, heure(PreClimate.minuteDuJour().toInt()))
        peindreVerrou(e)
        val step = e.step
        v.anneau.fraction = step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        val aUneHeure = e.req.mode == PreClimateMode.AT_TIME
        when (step) {
            is PreClimatePlan.Step.Waiting -> {
                v.anneau.anim = PreClimateRingView.Anim.ORBITE
                v.anneauTitre.text = v.s(R.string.preclim_wait_label)
                v.anneauGrand.text = duree(minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = v.s(R.string.preclim_at_time, heure(e.wallTimes.getOrNull(step.index)?.first ?: 0))
                pastille(v.etat, v.s(R.string.preclim_state_wait), v.bouton, v.texte2)
            }
            is PreClimatePlan.Step.Running -> {
                v.anneau.anim = PreClimateRingView.Anim.ONDES
                val sansLimite = step.untilMs == PreClimatePlan.NEVER
                v.anneauTitre.text = when {
                    aUneHeure -> v.s(R.string.preclim_cycle, step.index + 1, e.cycles.size)
                    sansLimite -> v.s(R.string.preclim_elapsed)
                    else -> v.s(R.string.preclim_remaining)
                }
                v.anneauGrand.text = duree(
                    if (sansLimite) ((maintenant - step.fromMs) / 60_000L).toInt() else minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = when {
                    aUneHeure -> v.s(R.string.preclim_until, heure(e.wallTimes.getOrNull(step.index)?.second ?: 0))
                    sansLimite -> v.s(R.string.preclim_unlimited)
                    else -> v.s(R.string.preclim_stop_at, heure((e.startWallMinute + e.req.maxMin).roundToInt()))
                }
                pastille(v.etat, v.s(R.string.preclim_state_run), v.accentDoux, v.accent)
            }
            else -> {
                v.anneau.anim = PreClimateRingView.Anim.AUCUNE
                v.anneauTitre.text = ""
                v.anneauGrand.text = "–"
                v.anneauSous.text = ""
            }
        }
        v.clim.text = resumeDemande(e)
        lignes(e, step)
        v.dehors.text = v.s(R.string.preclim_outside, temperatureDehors(e))
        v.batterie.text = v.s(R.string.preclim_battery, batterie(e))
    }

    /**
     * Mode chien : le message pour les passants, la température réglée en grand, et de quoi
     * arrêter pour le conducteur. Les ondes tournent autour de la patte tant que la clim marche.
     */
    private fun peindreChien(e: PreClimate.Etat, maintenant: Long) {
        val enMarche = e.step is PreClimatePlan.Step.Running
        v.chienAnneau.anim = if (enMarche) PreClimateRingView.Anim.ONDES else PreClimateRingView.Anim.ORBITE
        v.chienAnneau.fraction = e.step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        v.chienTemp.text = v.s(R.string.preclim_value_celsius, e.req.tempC)
        v.chienDehors.text = v.s(R.string.preclim_dog_outside, temperatureDehors(e))
        v.chienEtat.text = v.s(R.string.preclim_dog_status, heure(e.startWallMinute.toInt()), batterie(e))
    }

    private fun peindreVerrou(e: PreClimate.Etat) = when (e.lock) {
        PreClimate.LockState.PENDING -> pastille(v.verrou, v.s(R.string.preclim_lock_pending), v.bouton, v.texte2)
        PreClimate.LockState.LOCKED -> pastille(v.verrou, v.s(R.string.preclim_lock_ok), v.bouton, v.texte)
        PreClimate.LockState.FAILED -> pastille(v.verrou, v.s(R.string.preclim_lock_failed), v.alerteDoux, v.alerte)
    }

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
                if (e.req.maxMin > 0) v.s(R.string.preclim_off_at, heure((e.startWallMinute + e.req.maxMin).roundToInt()))
                else v.s(R.string.preclim_until_return, e.minSoc),
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

    private fun peindreFin(e: PreClimate.Etat) {
        val fin = e.end ?: return
        if (fin == PreClimateEnd.CANCELLED) {
            Toast.makeText(v.loc, v.s(R.string.preclim_cancelled), Toast.LENGTH_SHORT).show()
            PreClimate.oublierFin()
            finish()
            return
        }
        val heureFin = heure((e.endWallMinute ?: PreClimate.minuteDuJour()).toInt())
        val (titre, couleur, icone) = when (fin) {
            PreClimateEnd.DONE -> Triple(v.s(R.string.preclim_end_done), v.vert, "✓")
            PreClimateEnd.BATTERY -> Triple(v.s(R.string.preclim_end_battery, e.minSoc), v.alerte, "!")
            PreClimateEnd.UNLOCKED -> Triple(v.s(R.string.preclim_end_back), v.vert, "✓")
            PreClimateEnd.DRIVING -> Triple(v.s(R.string.preclim_end_driving), v.accent, "✓")
            PreClimateEnd.STOPPED, PreClimateEnd.CANCELLED -> Triple(v.s(R.string.preclim_end_stopped), v.accent, "✓")
        }
        val texte = buildList {
            if (fin == PreClimateEnd.DONE) add(
                if (e.req.mode == PreClimateMode.AT_TIME) v.s(R.string.preclim_end_cycles, e.cycles.size, duree(e.req.runMin))
                else v.s(R.string.preclim_end_now, duree(e.req.maxMin)))
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

    /** « 25 °C · ventilation 8 · A/C » : ce que la pré-climatisation règle. */
    private fun resumeDemande(e: PreClimate.Etat): String = v.s(
        if (e.req.ac) R.string.preclim_summary_ac else R.string.preclim_summary,
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
        const val VOILE_ETEINT = 0.97f
        const val LUMINOSITE_MIN = 0.01f
        /** Fin où le conducteur est là (retour, arrêt) : l'écran s'en va de lui-même. */
        const val FIN_PRESENT_MS = 30_000L
    }
}
