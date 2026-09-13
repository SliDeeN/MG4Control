package com.mg4.control.service

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.MainActivity
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.DrivingProfile
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * Toutes les opérations WindowManager se font sur le thread principal.
 */
object ProfilePickerOverlay {

    private const val TAG             = "MG4_OVERLAY"
    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())

    /** Cran du curseur de luminosité au joystick, en points de pourcentage. */
    private const val PAS_LUMINOSITE = 5

    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    /** Focus au joystick du popup affiché ; vit et meurt avec la vue. Thread principal seulement. */
    private var navigation: Navigation? = null

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
     * Affiche l'overlay avec une liste restreinte de profils (conflit BT).
     * [onAutoDismiss] est appelé si le timeout s'écoule sans sélection.
     * Peut être appelé depuis n'importe quel thread.
     */
    fun show(context: Context, profiles: List<DrivingProfile>, onAutoDismiss: () -> Unit) {
        handler.post { showOnMainThread(context, profiles, onAutoDismiss) }
    }

    /**
     * Ferme l'overlay immédiatement (sans déclencher onAutoDismiss).
     * Peut être appelé depuis n'importe quel thread.
     */
    fun dismiss(context: Context) {
        handler.post { dismissOnMainThread(context, fireAutoDismiss = false) }
    }

    // ── Implémentation (main thread) ─────────────────────────────────────────

    private fun showOnMainThread(
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
        dismissOnMainThread(context, fireAutoDismiss = false)

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

        // Le contexte du Service n'a pas de thème Material → on l'enveloppe
        // avec le thème de l'app pour que MaterialButton puisse s'instancier.
        val themedContext = ContextThemeWrapper(localizedContext, R.style.Theme_MG4Control)

        // Inflate la vue depuis le layout XML (utilise le contexte thémé)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_profile_picker, null)

        // ── Grille 2 colonnes de profils ─────────────────────────────────
        val container      = view.findViewById<LinearLayout>(R.id.overlay_profiles_container)
        val accentColor    = context.getColor(R.color.dash_accent)
        val accentDimColor = context.getColor(R.color.dash_accent_dim)
        val dm             = context.resources.displayMetrics

        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm).toInt()

        /** Boutons de profil par ligne de la grille, pour la navigation au joystick. */
        val lignesProfils = mutableListOf<List<View>>()

        fun makeProfileButton(profile: com.mg4.control.model.DrivingProfile) =
            MaterialButton(themedContext).apply {
                text      = profile.name
                textSize  = 19f
                isAllCaps = false
                setTextColor(accentColor)
                backgroundTintList = ColorStateList.valueOf(accentDimColor)
                strokeColor        = ColorStateList.valueOf(accentColor)
                strokeWidth        = dp(1f)
                cornerRadius       = dp(10f)
                setOnClickListener {
                    AppLogger.i(TAG, "Profil sélectionné : '${profile.name}'")
                    CoroutineScope(Dispatchers.IO).launch {
                        ProfileApplier.apply(profile)
                    }
                    dismissOnMainThread(context)
                }
            }

        // Découpe en lignes de 2, chaque ligne = LinearLayout horizontal
        profilesToShow.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10f) }
            }

            val boutons = row.mapIndexed { index, profile ->
                makeProfileButton(profile).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(90f), 1f).also {
                        if (index == 0 && row.size == 2) it.marginEnd = dp(10f)
                    }
                }
            }
            boutons.forEach(rowLayout::addView)
            lignesProfils += boutons

            // Nombre impair → placeholder invisible pour garder la symétrie
            if (row.size == 1) {
                val spacer = android.view.View(themedContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(90f), 1f)
                }
                rowLayout.addView(spacer)
            }

            container.addView(rowLayout)
        }

        // ── Fermeture manuelle ────────────────────────────────────────────
        view.findViewById<View>(R.id.overlay_btn_close)?.setOnClickListener {
            dismissOnMainThread(context)
        }

        // ── Bouton « Éteindre la voiture » (centré, gated firmware) ────────
        val btnPowerOff = view.findViewById<MaterialButton>(R.id.overlay_btn_poweroff)
        if (!MG4Hardware.hasVehiclePowerOff()) {
            btnPowerOff?.visibility = View.GONE
        } else {
            btnPowerOff?.setOnClickListener {
                dismissOnMainThread(context)              // ferme le popup profils
                showVehiclePowerOffConfirm(context)       // P-check + confirmation
            }
        }

        // ── Bouton « Ouvrir MG4Control » (bas droite, toujours visible) ────
        view.findViewById<MaterialButton>(R.id.overlay_btn_open_app)?.setOnClickListener {
            AppLogger.i(TAG, "Ouverture de MG4Control depuis l'overlay")
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { AppLogger.w(TAG, "Lancement MainActivity échoué : ${it.message}") }
            dismissOnMainThread(context)
        }

        // ── Tap sur le fond → fermeture ───────────────────────────────────
        view.findViewById<View>(R.id.overlay_backdrop)?.setOnClickListener {
            dismissOnMainThread(context)
        }
        // La carte intérieure intercepte les appuis sans propager au fond
        view.findViewById<View>(R.id.overlay_card)?.setOnClickListener { /* consommer */ }

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
        AppLogger.i(TAG, "Overlay affiché — ${profilesToShow.size} profil(s)")

        // ── Compte à rebours ──────────────────────────────────────────────
        val tvCountdown = view.findViewById<TextView>(R.id.overlay_countdown)
        var remaining = (AUTO_DISMISS_MS / 1_000L).toInt()

        val tick: Runnable = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown?.text = localizedContext.getString(R.string.overlay_countdown, remaining)
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
        // dismissOnMainThread(fireAutoDismiss=false) annule ce runnable.
        val dr = Runnable {
            AppLogger.i(TAG, "Overlay — timeout, fallback onAutoDismiss")
            dismissOnMainThread(context, fireAutoDismiss = false)
            onAutoDismiss?.invoke()
        }
        dismissRunnable = dr
        handler.postDelayed(dr, AUTO_DISMISS_MS)

        // Ré-arme les deux timers (appelé à chaque interaction luminosité pour
        // ne pas fermer le popup pendant le réglage).
        fun resetTimers() {
            handler.removeCallbacks(dr)
            handler.postDelayed(dr, AUTO_DISMISS_MS)
            countdownRunnable?.let { handler.removeCallbacks(it) }
            remaining = (AUTO_DISMISS_MS / 1_000L).toInt()
            handler.post(tick)
        }

        // ── Bloc luminosité (ancien SDK SWI133/68/165 ; A9 = phase 2) ─────
        // Posé par le bloc luminosité s'il est affiché : le joystick règle le curseur par crans.
        var reglerLuminosite: ((Int) -> Unit)? = null
        val briSection = view.findViewById<View>(R.id.overlay_brightness_section)
        if (!MG4Hardware.hasBrightnessControl()) {
            briSection?.visibility = View.GONE
        } else {
            val slider   = view.findViewById<Slider>(R.id.overlay_bri_slider)
            val briValue = view.findViewById<TextView>(R.id.overlay_bri_value)

            fun applyBrightnessAsync(pct: Int) {
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setScreenBrightnessPercent(pct) }
            }
            // Debounce des écritures pendant le glissement (évite de spammer le binder)
            val pendingApply = Runnable { slider?.let { applyBrightnessAsync(it.value.toInt()) } }

            slider?.addOnChangeListener { _, value, fromUser ->
                briValue?.text = "${value.toInt()}%"
                if (fromUser) {
                    resetTimers()
                    handler.removeCallbacks(pendingApply)
                    handler.postDelayed(pendingApply, 60L)
                }
            }
            slider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { resetTimers() }
                override fun onStopTrackingTouch(s: Slider) {
                    handler.removeCallbacks(pendingApply)
                    applyBrightnessAsync(s.value.toInt())   // application finale
                    resetTimers()
                }
            })

            // Presets — déplacent le curseur (met à jour le label via le listener) puis appliquent
            fun preset(pct: Int) {
                slider?.value = pct.toFloat()
                applyBrightnessAsync(pct)
                resetTimers()
            }
            view.findViewById<View>(R.id.overlay_bri_night)?.setOnClickListener { preset(15) }
            view.findViewById<View>(R.id.overlay_bri_mid)?.setOnClickListener   { preset(50) }
            view.findViewById<View>(R.id.overlay_bri_day)?.setOnClickListener   { preset(100) }

            // Joystick : même chemin que le glissement (écriture différée), sans quoi une rafale
            // de crans enverrait une écriture binder par appui.
            reglerLuminosite = { delta ->
                slider?.let {
                    it.value = (it.value + delta).coerceIn(it.valueFrom, it.valueTo)  // label via le listener
                    handler.removeCallbacks(pendingApply)
                    handler.postDelayed(pendingApply, 60L)
                    resetTimers()
                }
            }

            // Initialisation depuis la valeur courante (lecture binder en arrière-plan)
            briValue?.text = "…"
            CoroutineScope(Dispatchers.IO).launch {
                val cur = MG4Hardware.getScreenBrightnessPercent()
                handler.post {
                    if (overlayView == null) return@post
                    if (cur >= 0) slider?.value = cur.coerceIn(5, 100).toFloat()  // label via le listener
                    else briValue?.text = "--%"
                }
            }
        }

        // ── Navigation au joystick droit du volant ───────────────────────
        // Grille de haut en bas : préréglages, curseur, lignes de profils, barre du bas. Seules
        // les cellules visibles y figurent : un bouton masqué ne doit pas pouvoir prendre le focus.
        fun visibles(vararg vues: View?) = vues.filterNotNull().filter { it.visibility == View.VISIBLE }
        val briVisible   = briSection?.visibility == View.VISIBLE
        val ligneCurseur = if (briVisible) view.findViewById<View>(R.id.overlay_bri_slider)?.parent as? View else null
        val lignes = buildList {
            if (briVisible) {
                add(visibles(view.findViewById<View>(R.id.overlay_bri_night),
                             view.findViewById<View>(R.id.overlay_bri_mid),
                             view.findViewById<View>(R.id.overlay_bri_day)))
                add(listOfNotNull(ligneCurseur))
            }
            addAll(lignesProfils)
            add(visibles(view.findViewById<View>(R.id.overlay_btn_close), btnPowerOff,
                         view.findViewById<View>(R.id.overlay_btn_open_app)))
        }
        val grille = lignes.filter { it.isNotEmpty() }
        // Focus de départ sur le premier profil : c'est ce qu'on vient chercher dans ce popup, et
        // c'est aussi celui qu'applique le délai d'un conflit Bluetooth.
        val premierProfil = grille.indexOfFirst { it.firstOrNull() === lignesProfils.firstOrNull()?.firstOrNull() }
        navigation = Navigation(
            grille           = grille,
            ligneCurseur     = ligneCurseur,
            defilement       = container.parent as? ScrollView,
            reglerLuminosite = reglerLuminosite,
            surDeplacement   = { resetTimers() },
            couleur          = accentColor,
            epaisseur        = dp(4f),
            arrondi          = dp(12f).toFloat(),
            depart           = JoystickFocus.Position(premierProfil.coerceAtLeast(0), 0),
        ).also {
            // Surligner sans service d'accessibilité promettrait une navigation qui ne viendra pas.
            if (KeyCaptureService.isEnabled(context)) it.afficher()
        }
    }

    /**
     * Focus au joystick sur les cellules d'un popup affiché.
     *
     * Le choix de la cible est délégué à [JoystickFocus] (testé) ; cette classe ne fait que lire
     * les positions à l'écran, dessiner l'anneau et agir. Les positions sont relues à CHAQUE appui :
     * au premier, la mise en page vient à peine d'avoir lieu.
     */
    private class Navigation(
        private val grille: List<List<View>>,
        private val ligneCurseur: View?,
        private val defilement: ScrollView?,
        private val reglerLuminosite: ((Int) -> Unit)?,
        private val surDeplacement: () -> Unit,
        private val couleur: Int,
        private val epaisseur: Int,
        private val arrondi: Float,
        depart: JoystickFocus.Position,
    ) {
        private var position = depart
        private var surlignee: View? = null

        fun afficher() = surligner(cellule())

        fun recevoir(commande: JoystickFocus.Commande) {
            val cellule = cellule()
            val lateral = commande == JoystickFocus.Commande.GAUCHE || commande == JoystickFocus.Commande.DROITE
            when {
                // Clic réel : même chemin que le doigt, donc mêmes garde-fous (verrou de conduite,
                // confirmation d'extinction…). Le curseur n'a rien à valider.
                commande == JoystickFocus.Commande.VALIDER -> {
                    AppLogger.i(TAG, "Joystick — validation de la cellule ${position.ligne}/${position.colonne}")
                    if (cellule !== ligneCurseur) cellule.performClick()
                }
                // Sur le curseur, gauche/droite règlent la luminosité au lieu de déplacer le focus
                // (la ligne n'a de toute façon qu'une cellule).
                cellule === ligneCurseur && lateral -> reglerLuminosite?.invoke(
                    if (commande == JoystickFocus.Commande.GAUCHE) -PAS_LUMINOSITE else PAS_LUMINOSITE
                )
                else -> {
                    position = JoystickFocus.deplacer(centres(), position, commande)
                    surligner(cellule())
                    surDeplacement()
                }
            }
        }

        private fun cellule(): View {
            val ligne = grille[position.ligne.coerceIn(0, grille.lastIndex)]
            return ligne[position.colonne.coerceIn(0, ligne.lastIndex)]
        }

        private fun centres(): List<List<Int>> {
            val xy = IntArray(2)
            return grille.map { ligne ->
                ligne.map { v -> v.getLocationOnScreen(xy); xy[0] + v.width / 2 }
            }
        }

        private fun surligner(v: View) {
            surlignee?.foreground = null
            // Les MaterialButton dessinent leur fond en retrait vertical : sans le même retrait,
            // l'anneau flotterait au-dessus et au-dessous du bouton.
            val (haut, bas) = if (v is MaterialButton) v.insetTop to v.insetBottom else 0 to 0
            v.foreground = InsetDrawable(GradientDrawable().apply {
                cornerRadius = arrondi
                setColor(Color.TRANSPARENT)
                setStroke(epaisseur, couleur)
            }, 0, haut, 0, bas)
            surlignee = v
            amenerDansLaVue(v)
        }

        /** Fait défiler la liste de profils si la cellule surlignée en dépasse. */
        private fun amenerDansLaVue(v: View) {
            val sv = defilement ?: return
            if (sv.height == 0 || !estDans(v, sv)) return
            val r = Rect()
            v.getDrawingRect(r)
            sv.offsetDescendantRectToMyCoords(v, r)
            when {
                r.top < sv.scrollY                -> sv.smoothScrollTo(0, r.top)
                r.bottom > sv.scrollY + sv.height -> sv.smoothScrollTo(0, r.bottom - sv.height)
            }
        }

        private fun estDans(v: View, parent: View): Boolean {
            var p = v.parent
            while (p != null) {
                if (p === parent) return true
                p = p.parent
            }
            return false
        }
    }

    /**
     * « Éteindre la voiture » depuis l'overlay : vérifie la position P (lecture gear),
     * puis affiche le MÊME dialogue de confirmation que les Réglages/raccourci, en
     * fenêtre overlay. Si pas en P → Toast. `vehiclePowerOff()` re-vérifie le P à l'envoi.
     */
    private fun showVehiclePowerOffConfirm(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val inPark = MG4Hardware.isVehicleInPark()
            handler.post {
                if (inPark == true) {
                    val themed = ContextThemeWrapper(LocaleHelper.applyLocale(context), R.style.Theme_MG4Control)
                    val dialog = AlertDialog.Builder(themed)
                        .setTitle(R.string.vehicle_power_dialog_title)
                        .setMessage(R.string.vehicle_power_dialog_msg)
                        .setNegativeButton(R.string.vehicle_power_dialog_cancel, null)
                        .setPositiveButton(R.string.vehicle_power_dialog_confirm) { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                val ok = MG4Hardware.vehiclePowerOff()
                                AppLogger.i(TAG, "OVERLAY VEHICLE_POWER_OFF confirmé → $ok")
                            }
                        }
                        .create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                } else {
                    Toast.makeText(context, R.string.vehicle_power_need_park, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dismissOnMainThread(context: Context, fireAutoDismiss: Boolean = false) {
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
