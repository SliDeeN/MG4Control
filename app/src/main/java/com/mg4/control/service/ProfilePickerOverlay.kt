package com.mg4.control.service

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.mg4.control.MainActivity
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.HvacPopup
import com.mg4.control.model.ProfilePicker
import com.mg4.control.model.ProfileSections
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.PopupDelay
import com.mg4.control.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Overlay flottant affichant la liste des profils de conduite.
 *
 * Deux modes d'utilisation :
 *  - Raccourci volant → show(ctx) : affiche tous les profils
 *  - Conflit BT       → show(ctx, profiles, onAutoDismiss) : affiche uniquement
 *    les profils associés aux appareils connectés ; si l'utilisateur ne choisit
 *    pas avant le timeout, [onAutoDismiss] est appelé (ex. applique le 1er profil).
 *
 * Dans les deux modes, le joystick droit du volant navigue dans le popup (voir [naviguer]) si le
 * service d'accessibilité est actif ; sinon le popup reste purement tactile.
 *
 * Présentation refaite le 2026-10-13 dans le style du pop-up HVAC « moderne » (proposition « B »
 * choisie sur maquette) : les profils en une rangée de grandes cartes — celui qui est en cours
 * s'y reconnaît —, la luminosité et les actions en bandeau dessous. Ce que fait le popup n'a pas
 * changé.
 *
 * Toutes les opérations WindowManager se font sur le thread principal.
 */
object ProfilePickerOverlay {

    private const val TAG = "MG4_OVERLAY"

    private val handler = Handler(Looper.getMainLooper())

    /** Cran du curseur de luminosité au joystick, en points de pourcentage. */
    private const val PAS_LUMINOSITE = 5

    /** Préréglages de luminosité, en pour cent : Nuit, Moyen, Jour. */
    private val PREREGLAGES = intArrayOf(15, 50, 100)

    // Carte de base de overlay_profile_picker.xml, et part de l'écran qu'elle peut prendre : les
    // mêmes que la présentation « moderne » du pop-up HVAC, pour que les deux fenêtres aient la
    // même taille (voir HvacPopup.echelle).
    private const val LARGEUR_DP = 1108f
    private const val HAUTEUR_DP = 392f
    private const val PART_LARGEUR = 0.96f
    private const val PART_HAUTEUR = 0.82f

    /** Espace laissé entre la carte et les bords de la fenêtre quand il faut la réduire, en dp. */
    private const val MARGE_FENETRE_DP = 12f

    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    /**
     * Repli du pop-up affiché (conflit Bluetooth), à jouer s'il se ferme sans choix ni « Fermer » :
     * à l'échéance, ou quand on rappuie sur le raccourci ([toggle]). Thread principal seulement.
     */
    private var repli: (() -> Unit)? = null

    /** Focus au joystick du popup affiché ; vit et meurt avec la vue. Thread principal seulement. */
    private var navigation: OverlayNavigation? = null

    // ── API publique ─────────────────────────────────────────────────────────

    /**
     * Vrai si le popup est à l'écran. Lu depuis [com.mg4.control.accessibility.KeyCaptureService]
     * pour décider, AU MOMENT de l'appui, si le joystick doit être avalé.
     */
    fun isShowing(): Boolean = overlayView != null

    /**
     * Commande du joystick droit reçue pendant que le popup est ouvert.
     * Peut être appelé depuis n'importe quel thread ; sans effet si le popup s'est fermé entre-temps.
     */
    fun naviguer(commande: JoystickFocus.Commande) {
        handler.post { navigation?.recevoir(commande) }
    }

    /**
     * Affiche l'overlay avec tous les profils (raccourci volant).
     * Peut être appelé depuis n'importe quel thread.
     */
    fun show(context: Context) {
        handler.post { showOnMainThread(context, profiles = null, onAutoDismiss = null) }
    }

    /**
     * Raccourci volant : ouvre le popup avec tous les profils, ou le referme s'il est déjà à
     * l'écran (issue #137). Avant, un second appui le refermait et le rouvrait aussitôt : on le
     * croyait impossible à fermer. Peut être appelé depuis n'importe quel thread.
     */
    fun toggle(context: Context) {
        handler.post {
            if (overlayView != null) fermerParRaccourciSurMain(context, "second appui du raccourci")
            else showOnMainThread(context, profiles = null, onAutoDismiss = null)
        }
    }

    /**
     * Appui long, au joystick, de la touche qui a ouvert le popup (issue #137) : le referme s'il
     * est encore là, n'ouvre jamais. Peut être appelé depuis n'importe quel thread.
     */
    fun fermerParRaccourci(context: Context) {
        handler.post { fermerParRaccourciSurMain(context, "appui long du joystick") }
    }

    /**
     * Fermeture demandée par le raccourci : comme l'échéance, elle laisse partir le repli d'un
     * conflit Bluetooth — sans quoi aucun profil ne serait appliqué.
     */
    private fun fermerParRaccourciSurMain(context: Context, origine: String) {
        if (overlayView == null) return
        val enAttente = repli
        AppLogger.i(TAG, "Overlay — $origine → fermeture" +
            if (enAttente != null) " (repli du conflit Bluetooth, comme à l'échéance)" else "")
        dismissOnMainThread(context)
        enAttente?.invoke()
    }

    /**
     * Affiche l'overlay avec une liste restreinte de profils (conflit BT).
     * [onAutoDismiss] est appelé si le timeout s'écoule sans sélection.
     * Peut être appelé depuis n'importe quel thread.
     */
    fun show(context: Context, profiles: List<DrivingProfile>, onAutoDismiss: () -> Unit) {
        handler.post { showOnMainThread(context, profiles, onAutoDismiss) }
    }

    // ── Implémentation (main thread) ─────────────────────────────────────────

    /**
     * Sous garde : une mise en page fautive (vue introuvable, mauvais type) lèverait ici, sur le
     * thread principal du service, et l'emporterait avec elle. Le popup ne s'ouvre pas, le journal
     * dit pourquoi — et le repli d'un conflit Bluetooth part quand même, sans quoi aucun profil
     * ne serait appliqué.
     */
    private fun showOnMainThread(
        context: Context,
        profiles: List<DrivingProfile>?,
        onAutoDismiss: (() -> Unit)?
    ) {
        try {
            construire(context, profiles, onAutoDismiss)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Sélecteur de profils non affiché — ${e.javaClass.simpleName} ${e.message}")
            dismissOnMainThread(context)
            onAutoDismiss?.invoke()
        }
    }

    private fun construire(
        context: Context,
        profiles: List<DrivingProfile>?,
        onAutoDismiss: (() -> Unit)?
    ) {
        // L'overlay s'affiche TOUJOURS, quelle que soit la vitesse : il sert aussi à la
        // luminosité et à l'extinction, et masquer le sélecteur sans rien dire donnait
        // l'impression que le raccourci volant était cassé. C'est l'ÉCRITURE qui est
        // filtrée : si la sécurité conduite refuse, ProfileApplier échoue et VehicleWriteGate
        // affiche le toast « vitesse (limite) » au moment du choix du profil.

        // Si déjà affiché → on remplace (sans déclencher l'ancien onAutoDismiss)
        dismissOnMainThread(context)
        repli = onAutoDismiss

        val profilesToShow = profiles ?: ProfileManager(context).getAll()
        if (profilesToShow.isEmpty()) {
            AppLogger.i(TAG, "Aucun profil — overlay non affiché")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Le contexte du Service n'applique pas la langue choisie dans l'app
        // (LocaleHelper n'est posé que sur MainActivity/MG4App). Sans ça, le popup
        // tombe sur la langue système. On enveloppe avec la locale courante (lecture
        // fraîche → reflète un changement de langue en cours de session).
        val localizedContext = LocaleHelper.applyLocale(context)

        // La mise en page est écrite pour une carte de base ; on la gonfle avec une densité
        // corrigée pour qu'elle prenne la même part de l'écran partout — dp et sp suivent d'un
        // bloc, pictos et texte restent nets. Même contexte pour le thème : clair ou sombre comme
        // l'application, pas comme le système, et Material pour le curseur.
        val mesures = localizedContext.resources.displayMetrics
        val echelle = HvacPopup.echelle(
            mesures.widthPixels, mesures.heightPixels, mesures.density,
            LARGEUR_DP, HAUTEUR_DP, PART_LARGEUR, PART_HAUTEUR)
        val auTheme = ThemeHelper.withAppTheme(localizedContext)
        val agrandi = auTheme.createConfigurationContext(
            Configuration(auTheme.resources.configuration).apply {
                densityDpi = (mesures.densityDpi * echelle).roundToInt()
            })
        val themedContext = ContextThemeWrapper(agrandi, R.style.Theme_MG4Control)
        val p = Palette(themedContext)

        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_profile_picker, null)

        // ── En-tête ───────────────────────────────────────────────────────
        view.findViewById<ImageView>(R.id.overlay_title_icon).imageTintList = ColorStateList.valueOf(p.accent)
        val fermer = view.findViewById<ImageView>(R.id.overlay_btn_close)
        OverlayKit.fond(fermer, p.bouton, p.bordure, p.traitFin, ovale = true)
        fermer.imageTintList = ColorStateList.valueOf(p.texteSecondaire)
        fermer.setOnClickListener { dismissOnMainThread(context) }
        repereJoystick(view, p, localizedContext, utilisable = KeyCaptureService.isEnabled(context))

        // ── Rangée de cartes de profil ────────────────────────────────────
        val rangee    = view.findViewById<HorizontalScrollView>(R.id.overlay_profiles_scroll)
        val container = view.findViewById<LinearLayout>(R.id.overlay_profiles_container)
        val actifId   = ActiveProfile.id(context)
        val largeur   = OverlayKit.dp(themedContext, ProfilePicker.largeurCarteDp(profilesToShow.size))
        val ecart     = OverlayKit.dp(themedContext, ProfilePicker.ECART_DP)
        val gonfleur  = LayoutInflater.from(themedContext)

        val cartes = profilesToShow.mapIndexed { rang, profile ->
            val carte = gonfleur.inflate(R.layout.item_overlay_profile_card, container, false)
            peindreCarte(carte, profile, actif = profile.id == actifId, p = p, localized = localizedContext)
            carte.layoutParams = LinearLayout.LayoutParams(largeur, LinearLayout.LayoutParams.MATCH_PARENT)
                .also { if (rang > 0) it.marginStart = ecart }
            carte.setOnClickListener {
                AppLogger.i(TAG, "Profil sélectionné : '${profile.name}'")
                CoroutineScope(Dispatchers.IO).launch {
                    ProfileApplier.apply(profile)
                }
                dismissOnMainThread(context)
            }
            container.addView(carte)
            carte
        }

        // ── Bouton « Ouvrir MG4Control » (toujours visible) ───────────────
        val ouvrir = view.findViewById<TextView>(R.id.overlay_btn_open_app)
        tuileAction(ouvrir, p, danger = false)
        ouvrir.setOnClickListener {
            AppLogger.i(TAG, "Ouverture de MG4Control depuis l'overlay")
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppLogger.w(TAG, "Lancement MainActivity échoué : ${it.message}") }
            dismissOnMainThread(context)
        }

        // ── Bouton « Éteindre la voiture » (gated firmware) ───────────────
        val eteindre = view.findViewById<TextView>(R.id.overlay_btn_poweroff)
        val actions  = view.findViewById<View>(R.id.overlay_actions)
        val peutEteindre = MG4Hardware.hasVehiclePowerOff()
        if (!peutEteindre) {
            eteindre.visibility = View.GONE
        } else {
            tuileAction(eteindre, p, danger = true)
            eteindre.setOnClickListener {
                dismissOnMainThread(context)              // ferme le popup profils
                VehiclePowerOffOverlay.demander(context, "sélecteur de profils")
            }
        }

        // ── Luminosité (masquée si le firmware ne sait pas la régler) ─────
        // Décidé AVANT la pose : la place de la fenêtre se mesure sur la mise en page finale.
        val briSection = view.findViewById<View>(R.id.overlay_brightness_section)
        val briVisible = MG4Hardware.hasBrightnessControl()
        val place = actions.layoutParams as LinearLayout.LayoutParams
        when {
            // Sans luminosité, les actions prennent tout le bandeau.
            !briVisible -> {
                briSection.visibility = View.GONE
                place.width = 0
                place.weight = 1f
            }
            // Une seule action : un panneau moitié moins large suffit, la luminosité prend le reste.
            !peutEteindre -> place.width = OverlayKit.dp(themedContext, 230f)
        }
        actions.layoutParams = place

        // ── Tap sur le fond → fermeture ───────────────────────────────────
        view.findViewById<View>(R.id.overlay_backdrop).setOnClickListener {
            dismissOnMainThread(context)
        }
        // La carte intérieure intercepte les appuis sans propager au fond
        view.findViewById<View>(R.id.overlay_card).setOnClickListener { /* consommer */ }

        OverlayKit.tenirDansLaFenetre(
            view, (MARGE_FENETRE_DP * mesures.density).roundToInt(), "Sélecteur de profils")

        // ── Paramètres WindowManager ──────────────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        wm.addView(view, params)
        overlayView = view
        AppLogger.i(TAG, "Overlay affiché — ${profilesToShow.size} profil(s), actif=${actifId ?: "aucun"} ; " +
            "échelle ×${String.format(Locale.US, "%.2f", echelle)} (écran ${mesures.widthPixels}×" +
            "${mesures.heightPixels} px, ${mesures.densityDpi} dpi)")

        // ── Compte à rebours ──────────────────────────────────────────────
        // Délai réglé dans Réglages › Interface, lu à l'ouverture.
        val delaiMs = PopupDelay.millis(context)
        val tvCountdown = view.findViewById<TextView>(R.id.overlay_countdown)
        var remaining = (delaiMs / 1_000L).toInt()

        val tick: Runnable = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown.text = localizedContext.getString(R.string.overlay_countdown, remaining)
                if (remaining > 0) {
                    remaining--
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
        countdownRunnable = tick
        handler.post(tick)

        // ── Fermeture automatique ─────────────────────────────────────────
        // onAutoDismiss est appelé UNIQUEMENT ici (timeout sans sélection).
        // Si l'utilisateur choisit un profil ou appuie sur Fermer,
        // dismissOnMainThread() annule ce runnable.
        val dr = Runnable {
            AppLogger.i(TAG, "Overlay — timeout, fallback onAutoDismiss")
            dismissOnMainThread(context)
            onAutoDismiss?.invoke()
        }
        dismissRunnable = dr
        handler.postDelayed(dr, delaiMs)

        // Ré-arme les deux timers (appelé à chaque interaction luminosité pour
        // ne pas fermer le popup pendant le réglage).
        fun resetTimers() {
            handler.removeCallbacks(dr)
            handler.postDelayed(dr, delaiMs)
            countdownRunnable?.let { handler.removeCallbacks(it) }
            remaining = (delaiMs / 1_000L).toInt()
            handler.post(tick)
        }

        // ── Bloc luminosité (ancien SDK SWI133/68/165 ; A9 = phase 2) ─────
        // Posé par le bloc luminosité s'il est affiché : le joystick règle le curseur par crans.
        var reglerLuminosite: ((Int) -> Unit)? = null
        val prereglages = listOf(
            view.findViewById<TextView>(R.id.overlay_bri_night),
            view.findViewById<TextView>(R.id.overlay_bri_mid),
            view.findViewById<TextView>(R.id.overlay_bri_day),
        )
        val ligneCurseur = view.findViewById<View>(R.id.overlay_bri_slider_row)
        if (briVisible) {
            val slider   = view.findViewById<Slider>(R.id.overlay_bri_slider)
            val briValue = view.findViewById<TextView>(R.id.overlay_bri_value)
            view.findViewById<ImageView>(R.id.overlay_bri_icon).imageTintList = ColorStateList.valueOf(p.accent)
            OverlayKit.fond(view.findViewById(R.id.overlay_bri_presets), p.bouton, rayon = p.rayonTuile)

            /** Le préréglage qui correspond au curseur s'allume ; entre deux, aucun. */
            fun peindrePrereglages(valeur: Int) {
                prereglages.forEachIndexed { rang, vue ->
                    val choisi = PREREGLAGES[rang] == valeur
                    OverlayKit.fond(vue, if (choisi) p.segmentChoisi else Color.TRANSPARENT, rayon = p.rayonSegment)
                    vue.setTextColor(if (choisi) p.textePrincipal else p.texteSecondaire)
                }
            }
            peindrePrereglages(-1)

            fun applyBrightnessAsync(pct: Int) {
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setScreenBrightnessPercent(pct) }
            }
            // Debounce des écritures pendant le glissement (évite de spammer le binder)
            val pendingApply = Runnable { applyBrightnessAsync(slider.value.toInt()) }

            slider.addOnChangeListener { _, value, fromUser ->
                briValue.text = "${value.toInt()}%"
                peindrePrereglages(value.toInt())
                if (fromUser) {
                    resetTimers()
                    handler.removeCallbacks(pendingApply)
                    handler.postDelayed(pendingApply, 60L)
                }
            }
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { resetTimers() }
                override fun onStopTrackingTouch(s: Slider) {
                    handler.removeCallbacks(pendingApply)
                    applyBrightnessAsync(s.value.toInt())   // application finale
                    resetTimers()
                }
            })

            // Presets — déplacent le curseur (met à jour le label via le listener) puis appliquent
            fun preset(pct: Int) {
                slider.value = pct.toFloat()
                applyBrightnessAsync(pct)
                resetTimers()
            }
            prereglages.forEachIndexed { rang, vue -> vue.setOnClickListener { preset(PREREGLAGES[rang]) } }

            // Joystick : même chemin que le glissement (écriture différée), sans quoi une rafale
            // de crans enverrait une écriture binder par appui.
            reglerLuminosite = { sens ->
                slider.value = (slider.value + sens * PAS_LUMINOSITE).coerceIn(slider.valueFrom, slider.valueTo)  // label via le listener
                handler.removeCallbacks(pendingApply)
                handler.postDelayed(pendingApply, 60L)
                resetTimers()
            }

            // Initialisation depuis la valeur courante (lecture binder en arrière-plan)
            briValue.text = "…"
            CoroutineScope(Dispatchers.IO).launch {
                val cur = MG4Hardware.getScreenBrightnessPercent()
                handler.post {
                    if (overlayView == null) return@post
                    if (cur >= 0) slider.value = cur.coerceIn(5, 100).toFloat()  // label via le listener
                    else briValue.text = "--%"
                }
            }
        }

        // ── Navigation au joystick droit du volant ───────────────────────
        // Grille de haut en bas : la rangée de profils, puis préréglages et actions, puis le
        // curseur — sur sa propre ligne, puisque gauche / droite le règlent. Seules les cellules
        // visibles y figurent : un bouton masqué ne doit pas pouvoir prendre le focus.
        val lignes = buildList<List<View>> {
            add(cartes)
            add((if (briVisible) prereglages else emptyList<View>()) + listOf(ouvrir) +
                (if (peutEteindre) listOf(eteindre) else emptyList<View>()))
            if (briVisible) add(listOf(ligneCurseur))
        }
        // Focus de départ sur le premier profil : c'est ce qu'on vient chercher dans ce popup, et
        // c'est aussi celui qu'applique le délai d'un conflit Bluetooth.
        navigation = OverlayNavigation(
            context              = themedContext,
            grille               = lignes.filter { it.isNotEmpty() },
            depart               = JoystickFocus.Position(0, 0),
            surDeplacement       = { resetTimers() },
            ligneCurseur         = if (briVisible) ligneCurseur else null,
            reglerCurseur        = reglerLuminosite,
            defilementHorizontal = rangee,
        ).also { it.afficher() }
    }

    // ── Peinture ─────────────────────────────────────────────────────────────

    /** Couleurs et cotes du popup, prises sur son contexte THÉMÉ et agrandi. */
    private class Palette(private val themed: Context) {
        val accent          = themed.getColor(R.color.dash_accent)
        val accentDoux      = themed.getColor(R.color.dash_accent_dim)
        val alerte          = themed.getColor(R.color.dash_warn)
        val alerteDoux      = themed.getColor(R.color.dash_warn_dim)
        val danger          = themed.getColor(R.color.dash_danger)
        val dangerDoux      = themed.getColor(R.color.dash_danger_dim)
        val bouton          = themed.getColor(R.color.dash_btn)
        val bordure         = themed.getColor(R.color.dash_border)
        val segmentChoisi   = themed.getColor(R.color.hvac_segment_on)
        val textePrincipal  = themed.getColor(R.color.text_primary)
        val texteSecondaire = themed.getColor(R.color.text_secondary)

        val traitFin      = OverlayKit.dp(themed, 1f)
        val traitMoyen    = OverlayKit.dp(themed, 2f)
        val rayonCarte    = OverlayKit.dp(themed, 14f).toFloat()
        val rayonTuile    = OverlayKit.dp(themed, 12f).toFloat()
        val rayonSegment  = OverlayKit.dp(themed, 9f).toFloat()
        val rayonPastille = OverlayKit.dp(themed, 15f).toFloat()

        /** Fond et texte de la pastille d'un mode de conduite. */
        fun mode(mode: DriveMode): Pair<Int, Int> = OverlayKit.pastilleMode(themed, mode)
    }

    /**
     * Une carte de profil : son mode de conduite en pastille, son nom, un résumé. Celle du profil
     * en cours est teintée et le dit — c'est le dernier profil APPLIQUÉ ([ActiveProfile]), pas une
     * lecture de la voiture.
     */
    private fun peindreCarte(carte: View, profile: DrivingProfile, actif: Boolean, p: Palette, localized: Context) {
        if (actif) OverlayKit.fond(carte, p.accentDoux, p.accent, p.traitMoyen, p.rayonCarte)
        else OverlayKit.fond(carte, p.bouton, p.bordure, p.traitFin, p.rayonCarte)

        // Rien sur un réglage que le profil n'écrira pas : sans la catégorie Conduite, ni mode
        // ni régénération, et le résumé nomme les catégories qu'il applique.
        val mode = carte.findViewById<TextView>(R.id.profile_card_mode)
        val resume = carte.findViewById<TextView>(R.id.profile_card_summary)
        if (profile.appliesDrive) {
            val (fond, texte) = p.mode(profile.driveMode)
            mode.text = profile.driveMode.label
            mode.setTextColor(texte)
            OverlayKit.fond(mode, fond, rayon = p.rayonPastille)
            resume.text = localized.getString(R.string.drive_section_regen) + " " + profile.regenLevel.label
        } else {
            mode.visibility = View.INVISIBLE
            resume.text = ProfileSections.summary(
                profile, adasLabel = "",
                driveName   = localized.getString(R.string.profile_cat_drive),
                safetyName  = localized.getString(R.string.profile_cat_safety),
                comfortName = localized.getString(R.string.profile_cat_comfort))
        }

        carte.findViewById<TextView>(R.id.profile_card_name).text = profile.name

        if (actif) {
            val etiquette = carte.findViewById<TextView>(R.id.profile_card_active)
            etiquette.visibility = View.VISIBLE
            etiquette.setTextColor(p.accent)
            OverlayKit.fond(etiquette, Color.TRANSPARENT, p.accent, p.traitFin, p.rayonPastille)
        }
        val action = carte.findViewById<TextView>(R.id.profile_card_action)
        action.text = localized.getString(
            if (actif) R.string.overlay_profile_current else R.string.overlay_profile_apply)
        action.setTextColor(if (actif) p.texteSecondaire else p.accent)
    }

    /** Tuile d'action du bandeau ; [danger] pour celle qui éteint la voiture. */
    private fun tuileAction(tuile: TextView, p: Palette, danger: Boolean) {
        if (danger) OverlayKit.fond(tuile, p.dangerDoux, p.danger, p.traitFin, p.rayonTuile)
        else OverlayKit.fond(tuile, p.bouton, p.bordure, p.traitFin, p.rayonTuile)
        tuile.setTextColor(p.textePrincipal)
        tuile.compoundDrawableTintList = ColorStateList.valueOf(if (danger) p.danger else p.accent)
    }

    /**
     * Repère de l'en-tête. Joystick utilisable : son nom, puis ce que font ses gestes — pousser
     * déplace le focus, le clic valide. Sinon l'avertissement, seul, au même endroit : sans service
     * d'accessibilité le joystick ne nous parvient pas (il règle le volume).
     */
    private fun repereJoystick(view: View, p: Palette, localized: Context, utilisable: Boolean) {
        val couleur = if (utilisable) p.accent else p.alerte
        val titre = view.findViewById<TextView>(R.id.overlay_joy_title)
        titre.text =
            if (utilisable) localized.getString(R.string.hvac_popup_joystick_title)
            else localized.getString(R.string.hvac_popup_joystick_off_title) + " · " +
                localized.getString(R.string.hvac_popup_joystick_off_text)
        titre.setTextColor(couleur)
        titre.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        listOf(
            view.findViewById<TextView>(R.id.overlay_joy_move),
            view.findViewById<TextView>(R.id.overlay_joy_ok),
        ).forEach { geste ->
            geste.visibility = if (utilisable) View.VISIBLE else View.GONE
            geste.setTextColor(p.textePrincipal)
            geste.compoundDrawableTintList = ColorStateList.valueOf(p.accent)
        }
        OverlayKit.fond(
            view.findViewById(R.id.overlay_joy_chip),
            if (utilisable) p.accentDoux else p.alerteDoux, rayon = p.rayonPastille)
    }

    private fun dismissOnMainThread(context: Context) {
        repli = null
        dismissRunnable?.let  { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable   = null
        countdownRunnable = null
        navigation        = null

        val v = overlayView ?: return
        overlayView = null
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
            AppLogger.i(TAG, "Overlay fermé")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture overlay : ${e.message}")
        }
    }
}
