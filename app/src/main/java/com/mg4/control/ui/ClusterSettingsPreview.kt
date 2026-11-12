package com.mg4.control.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.RuleSignal
import com.mg4.control.ui.widget.ClusterBlocks
import android.widget.TextView
import com.mg4.control.util.DistanceSetting
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager
import com.mg4.control.ui.widget.ClusterGaugeView
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.ClusterProfiles
import com.mg4.control.util.LocaleHelper
import java.util.Locale
import kotlin.math.sin

/**
 * Aperçu du combiné dans Réglages › Interface (refonte A, 2026-10-17) : la vraie jauge
 * ([ClusterGaugeView]), animée sur un trajet SIMULÉ — accélération, croisière, freinage, ville,
 * arrêt — et relue à chaque pas avec les réglages du moment : module de l'onglet montré, style,
 * pastille, unités, actualisation, animation. On voit l'effet d'un réglage sans rouler.
 *
 * Rien n'est lu sur la voiture : ni appel binder sur le fil principal, ni valeur réelle mêlée au
 * trajet simulé. Seul le profil actif est lu, pour la pastille.
 *
 * Depuis le 2026-10-24, les réglages sont ceux d'un profil ([reglages]) : la carte y met ceux du
 * premier profil sélectionné, et son nom dans la pastille ([profilMontre]).
 */
class ClusterSettingsPreview(private val cadre: FrameLayout, private val vide: TextView) {

    private val ctx = cadre.context
    private val vue = ClusterGaugeView(ctx).also {
        cadre.addView(it, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private val handler = Handler(Looper.getMainLooper())
    private var actif = false

    /** Réglages montrés : ceux du premier profil sélectionné dans la carte. */
    var reglages: ClusterConfig = ClusterProfiles.effective(ctx)

    /** Profil nommé dans la pastille ; `null` = le profil en cours. */
    var profilMontre: DrivingProfile? = null

    /** Onglet du combiné montré dans l'aperçu. */
    var onglet: ClusterTab = ClusterTab.NAVIGATION
        set(v) { field = v; rafraichir() }

    /**
     * Personnalisation avancée (issue #229) : la disposition en cours d'édition, montrée à la place
     * de celle des réglages ; [moduleForce] : la jauge à montrer, quel que soit l'onglet.
     */
    var disposition: ClusterLayout? = null
        set(v) { field = v; rafraichir() }
    var moduleForce: ClusterModule? = null
        set(v) { field = v; rafraichir() }

    /** La jauge dessinée : le popup de personnalisation y pose sa sélection ([ClusterGaugeView.edition]). */
    val jauge: ClusterGaugeView get() = vue

    // Trajet simulé.
    private var t = 0.0
    private var kmh = 0f
    private var kwLisse: Float? = null
    private var moyenne = 168f
    private var recupWh = 120f
    private var km = 12.4f
    private var kwh = 2.1f
    private var mots: AndroidRuleWords? = null
    private var dernierMs = 0L
    private var profil: DrivingProfile? = null

    fun demarrer() {
        if (actif) return
        actif = true
        profil = runCatching { ActiveProfile.id(ctx)?.let { ProfileManager(ctx).getById(it) } }.getOrNull()
        dernierMs = 0L
        handler.post(pas)
    }

    fun arreter() {
        actif = false
        handler.removeCallbacks(pas)
    }

    /** Un réglage vient de changer : un pas tout de suite, sans attendre le suivant. */
    fun rafraichir() {
        if (!actif) return
        handler.removeCallbacks(pas)
        handler.post(pas)
    }

    private val pas = object : Runnable {
        override fun run() {
            if (!actif) return
            val maintenant = SystemClock.elapsedRealtime()
            val dt = if (dernierMs == 0L) 0L else (maintenant - dernierMs).coerceIn(0L, 2_000L)
            dernierMs = maintenant
            avancer(dt)
            montrer(dt)
            handler.postDelayed(this, ClusterModuleSettings.refreshMs(reglages).toLong())
        }
    }

    /** Le trajet simulé : une boucle de 24 s, la même que les maquettes. */
    private fun avancer(dtMs: Long) {
        val dt = dtMs / 1000.0
        t = (t + dt) % CYCLE_S
        val (kwCible, vitesse) = consigne(t)
        kmh += ((vitesse - kmh) * (dt * 2).coerceAtMost(1.0)).toFloat()
        val bruit = if (kotlin.math.abs(kwCible) > 5f) 4f else 0.5f
        val kw = kwCible + ((Math.random() * 2 - 1) * bruit).toFloat()
        val style = ClusterModuleSettings.style(reglages)
        kwLisse = if (style == ClusterGaugeStyle.DISCREET) ClusterGauge.smoothTime(kwLisse, kw, dtMs)
            else ClusterGauge.smooth(kwLisse, kw)
        if (kmh >= ClusterGauge.STOP_KMH && kw > 0f) {
            moyenne += ((kw * 1000f / kmh).coerceAtMost(400f) - moyenne) * (dt / 90.0).toFloat()
        }
        if (kw < 0f) recupWh += (-kw * dt / 3.6).toFloat()
        km += (kmh * dt / 3600.0).toFloat()
        if (kw > 0f) kwh += (kw * dt / 3600.0).toFloat()
    }

    private fun montrer(dtMs: Long) {
        val module = moduleForce ?: ClusterModuleSettings.moduleFor(reglages, onglet)
        if (module == null) {
            vue.visibility = View.INVISIBLE
            vide.visibility = View.VISIBLE
            return
        }
        vue.visibility = View.VISIBLE
        vide.visibility = View.GONE
        val p = profilMontre ?: profil
        val unite = ClusterModuleSettings.consumptionUnit(reglages)
        val d = disposition ?: ClusterModuleSettings.layout(reglages)
        vue.montrer(ClusterGaugeView.Contenu(
            module = module,
            kw = kwLisse,
            kmh = kmh,
            // La pastille est une case comme les autres (issue #229).
            pastille = null,
            moyenneWhKm = moyenne,
            recupWh = recupWh,
            unite = unite,
            uniteMoyenne = ClusterModuleSettings.averageUnit(reglages),
            distance = DistanceSetting.unit(ctx),
            style = ClusterModuleSettings.style(reglages),
            pleineEchelleKw = ClusterModuleSettings.fullScaleKw(reglages, ClusterModuleSettings.learnedPowerKw(ctx)),
            disposition = d,
            blocs = blocs(d, p),
        ), if (dtMs == 0L) 0L else ClusterModuleSettings.animationMs(reglages).toLong())
    }

    /** Les cases (issue #229), sur le trajet simulé et des valeurs plausibles : rien n'est lu sur la voiture. */
    private fun blocs(d: ClusterLayout, p: DrivingProfile?): List<ClusterGaugeView.Bloc?> {
        val m = mots ?: AndroidRuleWords(ctx).also { mots = it }
        val lecture = ClusterBlocks.Lecture(
            trajet = ClusterInfo.Trip(moyenne, km, kwh, recupWh, kwLisse),
            vitesseKmh = kmh,
            profilNom = p?.name,
            profilMode = p?.takeIf { it.appliesDrive }?.driveMode,
            mode = DriveMode.NORMAL,
            signaux = SIMULES,
        )
        return runCatching {
            ClusterBlocks.build(ctx, d, lecture, DistanceSetting.unit(ctx), m, Locale.forLanguageTag(LocaleHelper.getLanguage(ctx)))
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val CYCLE_S = 24.0

        /** Valeurs d'exemple des signaux montrables dans une case. */
        val SIMULES: Map<String, Any?> = mapOf(
            RuleSignal.SOC.name to 64.0, RuleSignal.RANGE.name to 285.0, RuleSignal.OUTSIDE_TEMP.name to 8.0,
            RuleSignal.HVAC_TEMP.name to 21.0, RuleSignal.VOLUME.name to 12.0, RuleSignal.CHARGE_LIMIT.name to 90.0,
            RuleSignal.BRIGHTNESS.name to 60.0, RuleSignal.REGEN.name to "MEDIUM", RuleSignal.GEAR.name to "D",
            RuleSignal.HVAC_ON.name to true, RuleSignal.AC_ON.name to true, RuleSignal.READY.name to true,
            RuleSignal.CHARGING.name to false, RuleSignal.PLUGGED.name to false,
        )

        /** Puissance visée (kW) et vitesse (km/h) à l'instant [t] du cycle. */
        fun consigne(t: Double): Pair<Float, Float> = when {
            t < 5 -> 70f to (20 + 12 * t).toFloat()
            t < 13 -> (16 + 5 * sin(t * 2.1)).toFloat() to 88f
            t < 17 -> -28f to (88 - 12 * (t - 13)).toFloat()
            t < 20 -> (8 + 4 * sin(t * 3)).toFloat() to 36f
            else -> 1.5f to 0f
        }
    }
}
