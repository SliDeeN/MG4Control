package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.update.UpdateInfo
import com.mg4.control.util.PopupDelay

/**
 * « Une mise à jour est disponible », par-dessus l'infodivertissement.
 *
 * Un BANDEAU en bas de l'écran, le même que [ProfileConfirmOverlay] (proposition « A », choisie
 * sur maquette le 2026-10-13) : il n'assombrit ni ne cache ce qui est derrière, et toucher
 * l'écran ailleurs agit sur l'application du dessous.
 *
 * Quatre sorties. Installer, ignorer cette version et ne plus prévenir sont des choix ; « plus
 * tard » n'engage à rien — on ne retient rien, la vérification suivante reproposera — et c'est
 * donc lui que retient le délai de Réglages › Interface ([PopupDelay]) quand personne ne répond :
 * un bandeau ne doit pas rester à l'écran tout un trajet.
 */
object UpdateOverlay {

    private const val TAG = "MG4_OVERLAY"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    fun show(
        context: Context,
        info: UpdateInfo,
        versionActuelle: String,
        onInstaller: () -> Unit,
        onIgnorer: () -> Unit,
        onDesactiver: () -> Unit,
        onAffiche: () -> Unit = {},
        onReporte: () -> Unit = {}
    ) {
        handler.post {
            showOnMain(context, info, versionActuelle, onInstaller, onIgnorer, onDesactiver,
                onAffiche, onReporte)
        }
    }

    private fun showOnMain(
        context: Context,
        info: UpdateInfo,
        versionActuelle: String,
        onInstaller: () -> Unit,
        onIgnorer: () -> Unit,
        onDesactiver: () -> Unit,
        onAffiche: () -> Unit,
        onReporte: () -> Unit
    ) {
        // Même règle que le popup de confirmation de profil : rien ne s'ajoute à l'écran en
        // roulant. La vérification reviendra plus tard, la mise à jour attendra.
        if (!VehicleWriteGate.isAllowedNow()) {
            AppLogger.w(TAG, "MAJ ${info.versionName} non signalée : véhicule en mouvement")
            onReporte()
            return
        }
        dismiss(context)

        // Un seul chemin de sortie : sans ce garde-fou, un double appui sur « Ignorer » pendant
        // que la fenêtre se retire déclencherait deux fois l'action.
        var done = false
        val finish: ((() -> Unit)?) -> Unit = { action ->
            if (!done) {
                done = true
                dismiss(context)
                action?.invoke()
            }
        }

        val delaiMs = PopupDelay.millis(context)
        val view: View
        val localized: Context
        try {
            val contextes = OverlayKit.contextesBandeau(context)
            localized = contextes.localized
            view = construire(contextes.themed, info, versionActuelle, finish, onInstaller, onIgnorer, onDesactiver)
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .addView(view, OverlayKit.fenetreBandeau(contextes.themed))
        } catch (e: Exception) {
            // Permission d'affichage par-dessus les autres applications retirée, par exemple — ou
            // une mise en page fautive, qui ne doit pas emporter le service avec elle.
            AppLogger.w(TAG, "MAJ non signalée — ${e.javaClass.simpleName} ${e.message}")
            onReporte()
            return
        }
        overlayView = view
        AppLogger.i(TAG, "MAJ signalée : $versionActuelle → ${info.versionName}")
        // Seulement ICI : les deux sorties au-dessus renoncent à afficher, et l'annonce doit
        // pouvoir revenir au prochain réveil du véhicule.
        onAffiche()

        // Le temps qui reste s'écrit sur « Plus tard » : c'est la réponse retenue quand il est écoulé.
        val tvCountdown = view.findViewById<TextView>(R.id.update_countdown)
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
            AppLogger.i(TAG, "MAJ ${info.versionName} — reportée (délai écoulé)")
            finish(null)
        }
        dismissRunnable = dr
        handler.postDelayed(dr, delaiMs)
    }

    /** Gonfle le bandeau, le peint au thème de l'application et branche ses quatre sorties. */
    private fun construire(
        themed: Context,
        info: UpdateInfo,
        versionActuelle: String,
        finish: ((() -> Unit)?) -> Unit,
        onInstaller: () -> Unit,
        onIgnorer: () -> Unit,
        onDesactiver: () -> Unit,
    ): View {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_update, null)

        val accent          = themed.getColor(R.color.dash_accent)
        val surAccent       = themed.getColor(R.color.hvac_on_accent)
        val bouton          = themed.getColor(R.color.dash_btn)
        val bordure         = themed.getColor(R.color.dash_border)
        val texteSecondaire = themed.getColor(R.color.text_secondary)
        val traitFin        = OverlayKit.dp(themed, 1f)
        val rayonTuile      = OverlayKit.dp(themed, 12f).toFloat()
        val rayonPastille   = OverlayKit.dp(themed, 14f).toFloat()

        view.findViewById<ImageView>(R.id.update_icon).imageTintList = ColorStateList.valueOf(accent)
        view.findViewById<ImageView>(R.id.update_arrow).imageTintList = ColorStateList.valueOf(accent)

        // La version installée, discrète ; celle qui est proposée, en accent.
        view.findViewById<TextView>(R.id.update_version_current).apply {
            text = versionActuelle
            setTextColor(texteSecondaire)
            OverlayKit.fond(this, bouton, rayon = rayonPastille)
        }
        view.findViewById<TextView>(R.id.update_version_new).apply {
            text = info.versionName
            setTextColor(accent)
            OverlayKit.fond(this, themed.getColor(R.color.dash_accent_dim), accent, traitFin, rayonPastille)
        }

        val plusTard = view.findViewById<View>(R.id.update_btn_later)
        val ignorer  = view.findViewById<TextView>(R.id.update_btn_skip)
        val installer = view.findViewById<View>(R.id.update_btn_install)
        OverlayKit.fond(plusTard, bouton, bordure, traitFin, rayonTuile)
        OverlayKit.fond(ignorer, bouton, bordure, traitFin, rayonTuile)
        OverlayKit.fond(installer, accent, Color.TRANSPARENT, 0, rayonTuile)
        view.findViewById<ImageView>(R.id.update_install_icon).imageTintList = ColorStateList.valueOf(surAccent)
        view.findViewById<TextView>(R.id.update_install_label).setTextColor(surAccent)

        installer.setOnClickListener { finish(onInstaller) }
        ignorer.setOnClickListener { finish(onIgnorer) }
        // « Plus tard » : on ne retient rien, la vérification suivante reproposera.
        plusTard.setOnClickListener {
            AppLogger.i(TAG, "MAJ ${info.versionName} — reportée (Plus tard)")
            finish(null)
        }
        // Un lien : souligné, c'est ce qui dit qu'il se touche.
        view.findViewById<TextView>(R.id.update_btn_disable).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { finish(onDesactiver) }
        }
        return view
    }

    fun dismiss(context: Context) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        countdownRunnable = null
        val v = overlayView ?: return
        overlayView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture du popup MAJ : ${e.message}")
        }
    }
}
