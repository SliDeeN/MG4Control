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
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.automation.AutomationSettings
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.model.DrivingProfile
import com.mg4.control.util.PopupDelay

/**
 * Question OUI / NON : faut-il appliquer [DrivingProfile] parce qu'un seuil est franchi —
 * température extérieure ([show]) ou niveau de batterie ([showBattery]) ?
 * OUI → onConfirmed ; NON ou délai écoulé → onDeclined (une seule fois). Le délai est celui de
 * Réglages › Interface ([PopupDelay]).
 *
 * C'est un BANDEAU en bas de l'écran (proposition « C », choisie sur maquette le 2026-10-13) : la
 * question arrive souvent en roulant, elle ne doit ni assombrir ni cacher ce qui est derrière.
 * La fenêtre n'occupe que la place du bandeau : toucher l'écran ailleurs agit sur l'application
 * du dessous et ne répond PAS à la question — seuls NON et le délai refusent.
 *
 * Il se pilote au joystick droit du volant (voir [naviguer]) si le service d'accessibilité est
 * actif : focus de départ sur OUI, gauche / droite pour changer, clic central pour valider. Ne
 * rien faire reste un NON.
 */
object ProfileConfirmOverlay {

    private const val TAG = "MG4_OVERLAY"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    /** Focus au joystick du popup affiché ; vit et meurt avec la vue. Thread principal seulement. */
    private var navigation: OverlayNavigation? = null

    /** Ce qui a déclenché la question : la mesure, son seuil, et de quel côté elle l'a franchi. */
    private class Declencheur(
        val icone: Int,
        val valeur: Float,
        val seuil: Int,
        val unite: String,
        val auDessus: Boolean,
    )

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
        val declencheur = Declencheur(
            R.drawable.ic_overlay_temperature, currentTemp, threshold, "°C",
            auDessus = direction == AutomationSettings.Direction.ABOVE)
        handler.post { showOnMain(context, profile, declencheur, onConfirmed, onDeclined) }
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
        val declencheur = Declencheur(R.drawable.ic_overlay_battery, currentSoc, threshold, "%", auDessus = false)
        handler.post { showOnMain(context, profile, declencheur, onConfirmed, onDeclined) }
    }

    private fun showOnMain(
        context: Context,
        profile: DrivingProfile,
        declencheur: Declencheur,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit,
    ) {
        // En roulant (verrou actif) : pas d'écriture → on décline directement (fallback BT/défaut).
        if (!VehicleWriteGate.isAllowedNow()) {
            AppLogger.w(TAG, "Confirm non affiché : sécurité conduite active → onDeclined")
            onDeclined(); return
        }
        dismiss(context)

        // Un seul chemin de sortie : garde-fou pour ne déclencher qu'un callback.
        var done = false
        val finish: (Boolean) -> Unit = { confirmed ->
            if (!done) {
                done = true
                dismiss(context)
                if (confirmed) onConfirmed() else onDeclined()
            }
        }

        // Sous garde : une mise en page fautive lèverait ici, sur le thread principal du service,
        // et l'emporterait avec elle. Sans bandeau, la question vaut NON — comme sans réponse.
        try {
            poser(context, profile, declencheur, finish)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Confirm non affiché — ${e.javaClass.simpleName} ${e.message} → onDeclined")
            finish(false)
        }
    }

    private fun poser(context: Context, profile: DrivingProfile, d: Declencheur, finish: (Boolean) -> Unit) {
        // Même agrandissement que les deux grands pop-ups : densité corrigée, thème de l'application.
        val contextes = OverlayKit.contextesBandeau(context)
        val localized = contextes.localized
        val themed = contextes.themed
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_profile_confirm, null)

        val accent          = themed.getColor(R.color.dash_accent)
        val textePrincipal  = themed.getColor(R.color.text_primary)
        val rayonTuile      = OverlayKit.dp(themed, 12f).toFloat()

        // ── Ce qui a déclenché la question ───────────────────────────────
        val langue = localized.resources.configuration.locales[0]
        view.findViewById<ImageView>(R.id.confirm_icon).apply {
            setImageResource(d.icone)
            imageTintList = ColorStateList.valueOf(accent)
        }
        view.findViewById<TextView>(R.id.confirm_value).text = String.format(langue, "%.1f", d.valeur)
        view.findViewById<TextView>(R.id.confirm_unit).text = d.unite
        view.findViewById<TextView>(R.id.confirm_threshold).text = localized.getString(
            if (d.auDessus) R.string.automation_confirm_above else R.string.automation_confirm_below,
            "${d.seuil} ${d.unite}")

        // ── Le profil proposé ────────────────────────────────────────────
        view.findViewById<TextView>(R.id.confirm_profile).text = profile.name
        val mode = view.findViewById<TextView>(R.id.confirm_mode)
        // Rien sur un réglage que le profil n'écrira pas : sans la catégorie Conduite, pas de mode.
        if (profile.appliesDrive) {
            val (fond, texte) = OverlayKit.pastilleMode(themed, profile.driveMode)
            mode.text = profile.driveMode.label
            mode.setTextColor(texte)
            OverlayKit.fond(mode, fond, rayon = OverlayKit.dp(themed, 10f).toFloat())
        } else {
            mode.visibility = View.GONE
        }

        // ── La réponse ───────────────────────────────────────────────────
        val btnNo  = view.findViewById<View>(R.id.confirm_btn_no)
        val btnYes = view.findViewById<TextView>(R.id.confirm_btn_yes)
        OverlayKit.fond(
            btnNo, themed.getColor(R.color.dash_btn), themed.getColor(R.color.dash_border),
            OverlayKit.dp(themed, 1f), rayonTuile)
        OverlayKit.fond(btnYes, accent, rayon = rayonTuile)
        btnYes.setTextColor(themed.getColor(R.color.hvac_on_accent))
        btnYes.setOnClickListener { finish(true) }
        btnNo.setOnClickListener { finish(false) }

        // La fenêtre n'a que la taille du bandeau : ce qui est touché ailleurs va à l'application
        // du dessous.
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val delaiMs = PopupDelay.millis(context)
        wm.addView(view, OverlayKit.fenetreBandeau(themed))
        overlayView = view
        AppLogger.i(TAG, "Confirm affiché pour '${profile.name}' (${d.valeur} ${d.unite}, seuil ${d.seuil})")

        // Le temps qui reste s'écrit sur NON : c'est la réponse retenue quand il est écoulé.
        val tvCountdown = view.findViewById<TextView>(R.id.confirm_countdown)
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
            AppLogger.i(TAG, "Confirm — timeout → onDeclined")
            finish(false)
        }
        dismissRunnable = dr
        handler.postDelayed(dr, delaiMs)

        // Joystick : les cellules dans l'ordre de l'écran — NON à gauche, OUI à droite —, focus de
        // départ sur OUI. Chaque déplacement relance le délai, comme dans le popup profils.
        // L'anneau prend la couleur du texte : en accent, il ne se verrait pas sur le bouton OUI.
        navigation = OverlayNavigation(
            context        = themed,
            grille         = listOf(listOf(btnNo, btnYes)),
            depart         = JoystickFocus.Position(0, 1),
            surDeplacement = {
                handler.removeCallbacks(dr)
                handler.postDelayed(dr, delaiMs)
                handler.removeCallbacks(tick)
                remaining = (delaiMs / 1_000L).toInt()
                handler.post(tick)
            },
            couleurAnneau  = textePrincipal,
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
