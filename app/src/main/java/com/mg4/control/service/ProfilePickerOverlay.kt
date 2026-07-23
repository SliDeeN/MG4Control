package com.mg4.control.service

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.mg4.hardware.VehicleWriteGate
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.hardware.AppLogger
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.model.DrivingProfile
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
 * Toutes les opérations WindowManager se font sur le thread principal.
 */
object ProfilePickerOverlay {

    private const val TAG             = "MG4_OVERLAY"
    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    // ── API publique ─────────────────────────────────────────────────────────

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
        // [T-904] L'overlay ne sert qu'à appliquer un profil, donc à écrire dans le
        // véhicule : inutile et dangereux de le poser devant le conducteur en roulant.
        // Même politique que les écritures — refus aussi si la vitesse est illisible.
        if (VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh())
                != VehicleWriteGate.Decision.ALLOWED) {
            AppLogger.w(TAG, "Overlay non affiché : véhicule non à l'arrêt")
            onAutoDismiss?.invoke()
            return
        }

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

        fun makeProfileButton(profile: com.mg4.hardware.model.DrivingProfile) =
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

            row.forEachIndexed { index, profile ->
                val btn = makeProfileButton(profile).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(90f), 1f).also {
                        if (index == 0 && row.size == 2) it.marginEnd = dp(10f)
                    }
                }
                rowLayout.addView(btn)
            }

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
