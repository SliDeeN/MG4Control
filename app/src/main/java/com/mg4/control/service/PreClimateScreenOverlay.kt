package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ClimateSnapshot
import com.mg4.control.model.PreClimateEnd
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.ui.widget.PreClimateRingView
import com.mg4.control.util.PreClimateSettings
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pré-climatisation (issue #130) : l'écran de suivi, plein cadre et TOUJOURS sombre (fond noir,
 * animation bleue discrète — maquette du 2026-10-23). Trois vues : le décompte avant verrouillage,
 * le suivi, la fin. Il suit [PreClimate] et n'agit que par lui.
 *
 * Portes verrouillées, il s'atténue au bout de quelques secondes (voile noir et luminosité de la
 * fenêtre au minimum ; « Éteint » dans les réglages : voile presque opaque) ; un toucher le
 * rallume 10 s. « Masquer » le retire sans arrêter le programme — le raccourci le réaffiche.
 * Fil principal seulement.
 */
object PreClimateScreenOverlay {

    private const val TAG = PreClimate.TAG
    private const val LARGEUR_DP = 1280f
    private const val HAUTEUR_DP = 480f
    private const val MARGE_FENETRE_DP = 8f
    /** Allumé ce temps à l'ouverture et après un toucher, puis atténué. */
    private const val EVEIL_MS = 10_000L
    private const val VOILE_ATTENUE = 0.6f
    private const val VOILE_ETEINT = 0.97f
    private const val LUMINOSITE_MIN = 0.01f
    /** Fin où le conducteur est là (retour, arrêt) : l'écran s'en va de lui-même. */
    private const val FIN_PRESENT_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var vue: View? = null
    private var gestionnaire: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var vues: Vues? = null
    private var eveilJusqua = 0L
    private var attenue = false
    private var fermetureFin: Runnable? = null

    fun isShowing(): Boolean = vue != null

    /** Affiche l'écran, ou le rallume s'il l'est déjà. Fil principal. */
    fun afficher(context: Context, raison: String) {
        if (vue != null) { eveiller(); return }
        val app = context.applicationContext
        val c = OverlayKit.contextes(app, LARGEUR_DP, HAUTEUR_DP)
        // Toujours sombre : c'est l'écran qu'on voit à travers la vitre, voiture verrouillée.
        val nuit = Configuration(c.themed.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val sombre = ContextThemeWrapper(c.themed.createConfigurationContext(nuit), R.style.Theme_MG4Control)
        val v = try {
            Vues(LayoutInflater.from(sombre).inflate(R.layout.overlay_preclim_screen, null), c.localized).also { brancher(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Écran de suivi non affiché — mise en page : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        OverlayKit.tenirDansLaFenetre(v.racine, (MARGE_FENETRE_DP * c.mesures.density).roundToInt(), "Pré-climatisation (suivi)")
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(v.racine, p)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Écran de suivi non affiché — fenêtre : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        vue = v.racine
        gestionnaire = wm
        params = p
        vues = v
        attenue = false
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        PreClimate.ecouteur = { e -> peindre(e) }
        PreClimate.etat?.let { peindre(it) }
        AppLogger.i(TAG, "Écran de suivi affiché ($raison)")
    }

    /** Retire l'écran, sans toucher au programme. Appelable de n'importe quel fil. */
    fun fermer(raison: String) {
        handler.post {
            val v = vue ?: return@post
            fermetureFin?.let { handler.removeCallbacks(it) }
            fermetureFin = null
            PreClimate.ecouteur = null
            runCatching { gestionnaire?.removeView(v) }
            vue = null
            vues = null
            gestionnaire = null
            params = null
            AppLogger.i(TAG, "Écran de suivi fermé ($raison)")
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

    private fun brancher(v: Vues) {
        v.racine.setOnClickListener { eveiller() }
        v.voile.setOnClickListener { eveiller() }
        v.cdAnnuler.setOnClickListener { PreClimate.annuler() }
        v.masquer.setOnClickListener { fermer("Masquer") }
        v.arreter.setOnClickListener { PreClimate.arreter() }
        v.finFermer.setOnClickListener {
            PreClimate.oublierFin()
            fermer("Fermer")
        }
        OverlayKit.fond(v.arreter, v.dangerDoux, v.danger, v.dp(1f), v.dp(12f).toFloat())
    }

    /** Un toucher : l'écran se rallume pour [EVEIL_MS]. */
    private fun eveiller() {
        eveilJusqua = SystemClock.elapsedRealtime() + EVEIL_MS
        attenuer(false)
    }

    /** Voile et luminosité de la fenêtre : atténués pendant l'attente, rendus au toucher. */
    private fun attenuer(oui: Boolean) {
        val v = vues ?: return
        if (oui == attenue) return
        attenue = oui
        val eteint = PreClimateSettings.screenOff(v.loc)
        v.voile.visibility = if (oui) View.VISIBLE else View.GONE
        v.voile.alpha = if (oui) (if (eteint) VOILE_ETEINT else VOILE_ATTENUE) else 0f
        params?.let { p ->
            p.screenBrightness = if (oui) LUMINOSITE_MIN else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            runCatching { gestionnaire?.updateViewLayout(v.racine, p) }
        }
    }

    // ── Peinture ─────────────────────────────────────────────────────────────

    private fun peindre(e: PreClimate.Etat) {
        val v = vues ?: return
        val maintenant = SystemClock.elapsedRealtime()
        v.decompte.visibility = if (e.phase == PreClimate.Phase.COUNTDOWN) View.VISIBLE else View.GONE
        v.suivi.visibility = if (e.phase == PreClimate.Phase.ACTIVE) View.VISIBLE else View.GONE
        v.fin.visibility = if (e.phase == PreClimate.Phase.ENDED) View.VISIBLE else View.GONE
        when (e.phase) {
            PreClimate.Phase.COUNTDOWN -> peindreDecompte(v, e, maintenant)
            PreClimate.Phase.ACTIVE -> peindreSuivi(v, e, maintenant)
            PreClimate.Phase.ENDED -> peindreFin(v, e)
        }
        attenuer(e.phase == PreClimate.Phase.ACTIVE && maintenant > eveilJusqua)
    }

    private fun peindreDecompte(v: Vues, e: PreClimate.Etat, maintenant: Long) {
        val reste = (e.lockAtMs - maintenant).coerceAtLeast(0L)
        v.cdChiffre.text = ((reste + 999) / 1000).toString()
        v.cdAnneau.anim = PreClimateRingView.Anim.AUCUNE
        v.cdAnneau.fraction = reste.toFloat() / (e.lockAtMs - e.startMs).coerceAtLeast(1L)
        val clim = resumeDemande(v, e)
        v.cdClim.text = if (e.req.mode == PreClimateMode.NOW) v.s(R.string.preclim_cd_now, clim)
            else v.s(R.string.preclim_cd_at, heure(v, e.wallTimes.firstOrNull()?.first ?: 0), clim)
    }

    private fun peindreSuivi(v: Vues, e: PreClimate.Etat, maintenant: Long) {
        v.titre.text = v.s(R.string.preclim_live_title, heure(v, PreClimate.minuteDuJour().toInt()))
        when (e.lock) {
            PreClimate.LockState.PENDING -> pastille(v.verrou, v.s(R.string.preclim_lock_pending), v.bouton, v.texte2)
            PreClimate.LockState.LOCKED -> pastille(v.verrou, v.s(R.string.preclim_lock_ok), v.bouton, v.texte)
            PreClimate.LockState.FAILED -> pastille(v.verrou, v.s(R.string.preclim_lock_failed), v.alerteDoux, v.alerte)
        }
        val step = e.step
        v.anneau.fraction = step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        val aUneHeure = e.req.mode == PreClimateMode.AT_TIME
        when (step) {
            is PreClimatePlan.Step.Waiting -> {
                v.anneau.anim = PreClimateRingView.Anim.ORBITE
                v.anneauTitre.text = v.s(R.string.preclim_wait_label)
                v.anneauGrand.text = duree(v, minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = v.s(R.string.preclim_at_time, heure(v, e.wallTimes.getOrNull(step.index)?.first ?: 0))
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
                v.anneauGrand.text = duree(v,
                    if (sansLimite) ((maintenant - step.fromMs) / 60_000L).toInt() else minutesRestantes(step.untilMs, maintenant))
                v.anneauSous.text = when {
                    aUneHeure -> v.s(R.string.preclim_until, heure(v, e.wallTimes.getOrNull(step.index)?.second ?: 0))
                    sansLimite -> v.s(R.string.preclim_unlimited)
                    else -> v.s(R.string.preclim_stop_at, heure(v, (e.startWallMinute + e.req.maxMin).roundToInt()))
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
        v.clim.text = resumeDemande(v, e)
        lignes(v, e, step)
        v.dehors.text = v.s(R.string.preclim_outside, e.outsideC?.let { v.s(R.string.preclim_value_celsius, it.roundToInt()) } ?: "–")
        v.batterie.text = v.s(R.string.preclim_battery, e.soc?.let { v.s(R.string.preclim_value_percent, it.toInt()) } ?: "–")
    }

    /** Les cycles et leur état (à une heure) ; le début et la fin prévue (tout de suite). */
    private fun lignes(v: Vues, e: PreClimate.Etat, step: PreClimatePlan.Step?) {
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
                    val ligne = v.s(R.string.preclim_run_line, i + 1, heure(v, debut), heure(v, fin))
                    append(ligne)
                    setSpan(ForegroundColorSpan(if (enCours) v.texte else v.texte2), puce.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else {
            listOf(
                v.s(R.string.preclim_started_at, heure(v, e.startWallMinute.toInt())),
                if (e.req.maxMin > 0) v.s(R.string.preclim_off_at, heure(v, (e.startWallMinute + e.req.maxMin).roundToInt()))
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

    private fun peindreFin(v: Vues, e: PreClimate.Etat) {
        val fin = e.end ?: return
        if (fin == PreClimateEnd.CANCELLED) {
            Toast.makeText(v.loc, v.s(R.string.preclim_cancelled), Toast.LENGTH_SHORT).show()
            PreClimate.oublierFin()
            fermer("annulée pendant le décompte")
            return
        }
        val heureFin = heure(v, (e.endWallMinute ?: PreClimate.minuteDuJour()).toInt())
        val (titre, couleur, icone) = when (fin) {
            PreClimateEnd.DONE -> Triple(v.s(R.string.preclim_end_done), v.vert, "✓")
            PreClimateEnd.BATTERY -> Triple(v.s(R.string.preclim_end_battery, e.minSoc), v.alerte, "!")
            PreClimateEnd.UNLOCKED -> Triple(v.s(R.string.preclim_end_back), v.vert, "✓")
            PreClimateEnd.DRIVING -> Triple(v.s(R.string.preclim_end_driving), v.accent, "✓")
            PreClimateEnd.STOPPED, PreClimateEnd.CANCELLED -> Triple(v.s(R.string.preclim_end_stopped), v.accent, "✓")
        }
        val texte = buildList {
            if (fin == PreClimateEnd.DONE) add(
                if (e.req.mode == PreClimateMode.AT_TIME) v.s(R.string.preclim_end_cycles, e.cycles.size, duree(v, e.req.runMin))
                else v.s(R.string.preclim_end_now, duree(v, e.req.maxMin)))
            if (fin.powersOff) add(if (e.powerOffOk == true) v.s(R.string.preclim_end_off, heureFin) else v.s(R.string.preclim_end_power_failed))
            if (fin == PreClimateEnd.UNLOCKED) add(v.s(R.string.preclim_end_back_text, heureFin))
            add(restauration(v, e.snapshot))
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
                fermer("fin, conducteur présent")
            }
            fermetureFin = r
            handler.postDelayed(r, FIN_PRESENT_MS)
        }
        AppLogger.i(TAG, "Écran de fin : $fin")
    }

    // ── Mise en forme ────────────────────────────────────────────────────────

    private fun pastille(t: TextView, texte: String, fond: Int, couleur: Int) {
        t.text = texte
        t.setTextColor(couleur)
        t.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        OverlayKit.fond(t, fond, rayon = OverlayKit.dp(t.context, 17f).toFloat())
    }

    /** « 25 °C · ventilation 8 · A/C » : ce que la pré-climatisation règle. */
    private fun resumeDemande(v: Vues, e: PreClimate.Etat): String = v.s(
        if (e.req.ac) R.string.preclim_summary_ac else R.string.preclim_summary,
        v.s(R.string.preclim_value_celsius, e.req.tempC), e.req.fan.toString())

    /** « Clim rétablie : 21 °C · ventilation 4 » — ou « arrêtée (…) » si elle l'était. */
    private fun restauration(v: Vues, s: ClimateSnapshot): String {
        val temp = s.tempC?.let { v.s(R.string.preclim_value_celsius, it) } ?: "–"
        val ventil = if (s.auto == true) v.s(R.string.clim_auto).uppercase(v.locale) else s.fan?.toString() ?: "–"
        val resume = v.s(if (s.ac == true) R.string.preclim_summary_ac else R.string.preclim_summary, temp, ventil)
        return v.s(if (s.powerOn == false) R.string.preclim_end_restored_off else R.string.preclim_end_restored, resume)
    }

    /** Minutes restantes jusqu'à [jusqua], arrondies au-dessus : « 1 min » jusqu'à la dernière seconde. */
    private fun minutesRestantes(jusqua: Long, maintenant: Long): Int =
        (((jusqua - maintenant).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    private fun heure(v: Vues, minuteDuJour: Int): String {
        val m = Math.floorMod(minuteDuJour, 1440)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }
        return DateFormat.getTimeFormat(v.loc).format(c.time)
    }

    private fun duree(v: Vues, minutes: Int): String =
        if (minutes >= 60) v.s(R.string.cluster_duration_hm, minutes / 60, minutes % 60)
        else v.s(R.string.cluster_duration_min, minutes)
}
