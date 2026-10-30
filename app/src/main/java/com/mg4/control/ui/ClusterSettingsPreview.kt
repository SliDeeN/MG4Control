package com.mg4.control.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.mg4.control.hardware.VehicleDistanceUnit
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

    // Trajet simulé.
    private var t = 0.0
    private var kmh = 0f
    private var kwLisse: Float? = null
    private var moyenne = 168f
    private var recupWh = 120f
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
    }

    private fun montrer(dtMs: Long) {
        val module = ClusterModuleSettings.moduleFor(reglages, onglet)
        if (module == null) {
            vue.visibility = View.INVISIBLE
            vide.visibility = View.VISIBLE
            return
        }
        vue.visibility = View.VISIBLE
        vide.visibility = View.GONE
        val p = profilMontre ?: profil
        val unite = ClusterModuleSettings.consumptionUnit(reglages)
        vue.montrer(ClusterGaugeView.Contenu(
            module = module,
            kw = kwLisse,
            kmh = kmh,
            // Le mode de la voiture n'est pas lu ici : Normal, comme sur la maquette.
            pastille = ClusterGauge.pill(ClusterModuleSettings.pill(reglages), DriveMode.NORMAL, p?.name,
                p?.takeIf { it.appliesDrive }?.driveMode, Locale.forLanguageTag(LocaleHelper.getLanguage(ctx))),
            moyenneWhKm = moyenne,
            recupWh = recupWh,
            unite = unite,
            uniteMoyenne = ClusterModuleSettings.averageUnit(reglages),
            distance = ClusterModuleSettings.distanceUnit(reglages, VehicleDistanceUnit.connue()),
            style = ClusterModuleSettings.style(reglages),
            pleineEchelleKw = ClusterModuleSettings.fullScaleKw(reglages, ClusterModuleSettings.learnedPowerKw(ctx)),
        ), if (dtMs == 0L) 0L else ClusterModuleSettings.animationMs(reglages).toLong())
    }

    private companion object {
        const val CYCLE_S = 24.0

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
