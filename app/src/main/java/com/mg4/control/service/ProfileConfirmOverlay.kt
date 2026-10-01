package com.mg4.control.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.automation.AutomationSettings
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.model.DrivingProfile
import com.mg4.control.util.LocaleHelper

/**
 * Popup OUI/NON demandant s'il faut appliquer [profile] parce qu'un seuil est franchi :
 * température extérieure ([show]) ou niveau de batterie ([showBattery]).
 * Calqué sur ProfilePickerOverlay (fenêtre overlay, compte à rebours 8 s, verrou 0 km/h).
 * OUI → onConfirmed ; NON ou timeout → onDeclined (une seule fois).
 *
 * Comme le popup profils, il se pilote au joystick droit du volant (voir [naviguer]) si le
 * service d'accessibilité est actif : focus de départ sur OUI, gauche/droite pour changer,
 * clic central pour valider. Ne rien faire reste un NON.
 */
object ProfileConfirmOverlay {

    private const val TAG = "MG4_OVERLAY"
    private const val AUTO_DISMISS_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    /** Focus au joystick du popup affiché ; vit et meurt avec la vue. Thread principal seulement. */
    private var navigation: OverlayNavigation? = null

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

    fun show(
        context: Context,
        profile: DrivingProfile,
        threshold: Int,
        currentTemp: Float,
        direction: AutomationSettings.Direction,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit
    ) {
        handler.post {
            showOnMain(context, profile, onConfirmed, onDeclined) { localized ->
                val tempStr = String.format(java.util.Locale.getDefault(), "%.1f", currentTemp)
                val msgRes = if (direction == AutomationSettings.Direction.ABOVE)
                    R.string.automation_confirm_msg_above else R.string.automation_confirm_msg
                localized.getString(msgRes, threshold, tempStr, profile.name)
            }
        }
    }

    /** Même popup, pour l'automatisation batterie (issue #112). */
    fun showBattery(
        context: Context,
        profile: DrivingProfile,
        threshold: Int,
        currentSoc: Float,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit
    ) {
        handler.post {
            showOnMain(context, profile, onConfirmed, onDeclined) { localized ->
                val socStr = String.format(java.util.Locale.getDefault(), "%.1f", currentSoc)
                localized.getString(R.string.battery_auto_confirm_msg, threshold, socStr, profile.name)
            }
        }
    }

    private fun showOnMain(
        context: Context,
        profile: DrivingProfile,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit,
        message: (Context) -> String
    ) {
        // En roulant (verrou actif) : pas d'écriture → on décline directement (fallback BT/défaut).
        if (!VehicleWriteGate.isAllowedNow()) {
            AppLogger.w(TAG, "Confirm non affiché : sécurité conduite active → onDeclined")
            onDeclined(); return
        }
        dismiss(context)

        val localized = LocaleHelper.applyLocale(context)
        val themed = ContextThemeWrapper(localized, R.style.Theme_MG4Control)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_confirm, null)

        view.findViewById<TextView>(R.id.confirm_message).text = message(localized)

        // Un seul chemin de sortie : garde-fou pour ne déclencher qu'un callback.
        var done = false
        fun finish(confirmed: Boolean) {
            if (done) return
            done = true
            dismiss(context)
            if (confirmed) onConfirmed() else onDeclined()
        }

        val btnYes = view.findViewById<MaterialButton>(R.id.confirm_btn_yes)
        val btnNo  = view.findViewById<MaterialButton>(R.id.confirm_btn_no)
        btnYes.setOnClickListener { finish(true) }
        btnNo.setOnClickListener { finish(false) }
        view.findViewById<View>(R.id.confirm_backdrop).setOnClickListener { finish(false) }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wm.addView(view, params)
        overlayView = view
        AppLogger.i(TAG, "Confirm affiché pour '${profile.name}'")

        val tvCountdown = view.findViewById<TextView>(R.id.confirm_countdown)
        var remaining = (AUTO_DISMISS_MS / 1_000L).toInt()
        val tick = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown.text = localized.getString(R.string.overlay_countdown, remaining)
                if (remaining > 0) { remaining--; handler.postDelayed(this, 1_000L) }
            }
        }
        countdownRunnable = tick
        handler.post(tick)

        val dr = Runnable {
            AppLogger.i(TAG, "Confirm — timeout → onDeclined")
            finish(false)
        }
        dismissRunnable = dr
        handler.postDelayed(dr, AUTO_DISMISS_MS)

        // Joystick : chaque déplacement du focus relance le délai, comme dans le popup profils.
        navigation = OverlayNavigation(
            context        = context,
            grille         = listOf(listOf(btnYes, btnNo)),
            depart         = JoystickFocus.Position(0, 0),
            surDeplacement = {
                handler.removeCallbacks(dr)
                handler.postDelayed(dr, AUTO_DISMISS_MS)
                handler.removeCallbacks(tick)
                remaining = (AUTO_DISMISS_MS / 1_000L).toInt()
                handler.post(tick)
            },
        ).also { it.afficher() }
    }

    private fun dismiss(context: Context) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        countdownRunnable = null
        navigation = null
        val v = overlayView ?: return
        overlayView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            AppLogger.i(TAG, "Confirm fermé")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture confirm : ${e.message}")
        }
    }
}
