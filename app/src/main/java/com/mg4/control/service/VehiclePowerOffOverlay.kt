package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.util.PopupDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Confirmation « Éteindre le véhicule », demandée par-dessus l'infodivertissement : depuis le
 * raccourci du volant, ou le bouton du sélecteur de profils.
 *
 * Un BANDEAU en bas de l'écran, le même que [ProfileConfirmOverlay] et [UpdateOverlay]
 * (proposition « A », choisie sur maquette le 2026-10-13). La position P est lue d'abord :
 *  - en P, la question — Annuler, puis Éteindre en rouge. Sans réponse, le délai de Réglages ›
 *    Interface ([PopupDelay]) vaut Annuler ;
 *  - hors de P, le même bandeau dit « Extinction impossible » et pourquoi, et se referme seul.
 *
 * [MG4Hardware.vehiclePowerOff] revérifie la position P au moment de l'envoi : entre la question
 * et la réponse, la voiture a pu repartir.
 */
object VehiclePowerOffOverlay {

    private const val TAG = "MG4_OVERLAY"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    /**
     * Lit la position P, puis pose le bandeau. Peut être appelé depuis n'importe quel thread.
     *
     * @param origine d'où vient la demande, pour le journal (« raccourci », « sélecteur de profils »)
     */
    fun demander(context: Context, origine: String) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val enP = MG4Hardware.isVehicleInPark() == true
            handler.post { showOnMain(app, enP, origine) }
        }
    }

    private fun showOnMain(context: Context, enP: Boolean, origine: String) {
        dismiss(context)

        // Un seul chemin de sortie : un double appui pendant que la fenêtre se retire ne doit pas
        // envoyer deux fois la commande.
        var done = false
        val finish: (Boolean) -> Unit = { eteindre ->
            if (!done) {
                done = true
                dismiss(context)
                if (eteindre) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val ok = MG4Hardware.vehiclePowerOff()
                        AppLogger.i(TAG, "Extinction du véhicule confirmée ($origine) → $ok")
                    }
                }
            }
        }

        val delaiMs = PopupDelay.millis(context)
        val view: View
        val localized: Context
        try {
            val contextes = OverlayKit.contextesBandeau(context)
            localized = contextes.localized
            view = construire(contextes.themed, localized, enP, finish)
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .addView(view, OverlayKit.fenetreBandeau(contextes.themed))
        } catch (e: Exception) {
            // Permission d'affichage par-dessus les autres applications retirée, ou mise en page
            // fautive : sans bandeau, rien n'est éteint, et le service ne tombe pas.
            AppLogger.w(TAG, "Extinction ($origine) : bandeau non affiché — ${e.javaClass.simpleName} ${e.message}")
            return
        }
        overlayView = view
        AppLogger.i(TAG, "Extinction du véhicule ($origine) : " +
            if (enP) "question posée" else "refusée, la voiture n'est pas en P")

        // Le temps qui reste s'écrit sur le bouton de gauche : c'est lui que retient le délai.
        val tvCountdown = view.findViewById<TextView>(R.id.power_countdown)
        var remaining = (delaiMs / 1_000L).toInt()
        val tick = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                tvCountdown.text = localized.getString(R.string.win_auto_delay_value, remaining)
                if (remaining > 0) { remaining--; handler.postDelayed(this, 1_000L) }
            }
        }
        countdownRunnable = tick
        handler.post(tick)

        val dr = Runnable {
            AppLogger.i(TAG, "Extinction du véhicule ($origine) — délai écoulé, rien n'est éteint")
            finish(false)
        }
        dismissRunnable = dr
        handler.postDelayed(dr, delaiMs)
    }

    /** Gonfle le bandeau dans l'un de ses deux états, le peint et branche ses sorties. */
    private fun construire(themed: Context, localized: Context, enP: Boolean, finish: (Boolean) -> Unit): View {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_power_off, null)

        val danger     = themed.getColor(R.color.dash_danger)
        val rayonTuile = OverlayKit.dp(themed, 12f).toFloat()

        view.findViewById<ImageView>(R.id.power_icon).apply {
            setImageResource(if (enP) R.drawable.ic_overlay_power else R.drawable.ic_overlay_park)
            imageTintList = ColorStateList.valueOf(if (enP) danger else themed.getColor(R.color.dash_warn))
        }
        view.findViewById<TextView>(R.id.power_title).text = localized.getString(
            if (enP) R.string.vehicle_power_banner_title else R.string.vehicle_power_banner_impossible)
        view.findViewById<TextView>(R.id.power_message).text = localized.getString(
            if (enP) R.string.vehicle_power_banner_msg else R.string.vehicle_power_need_park)

        val annuler = view.findViewById<View>(R.id.power_btn_cancel)
        OverlayKit.fond(
            annuler, themed.getColor(R.color.dash_btn), themed.getColor(R.color.dash_border),
            OverlayKit.dp(themed, 1f), rayonTuile)
        view.findViewById<TextView>(R.id.power_cancel_label).text = localized.getString(
            if (enP) R.string.vehicle_power_dialog_cancel else R.string.nav_close)
        annuler.setOnClickListener { finish(false) }

        val eteindre = view.findViewById<View>(R.id.power_btn_confirm)
        if (enP) {
            val surDanger = themed.getColor(R.color.text_active)
            OverlayKit.fond(eteindre, danger, rayon = rayonTuile)
            view.findViewById<ImageView>(R.id.power_confirm_icon).imageTintList = ColorStateList.valueOf(surDanger)
            view.findViewById<TextView>(R.id.power_confirm_label).setTextColor(surDanger)
            eteindre.setOnClickListener { finish(true) }
        } else {
            eteindre.visibility = View.GONE
        }
        return view
    }

    private fun dismiss(context: Context) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        countdownRunnable = null
        val v = overlayView ?: return
        overlayView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture du bandeau d'extinction : ${e.message}")
        }
    }
}
