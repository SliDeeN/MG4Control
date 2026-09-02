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
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleWriteGate
import com.mg4.control.update.UpdateInfo
import com.mg4.control.util.LocaleHelper

/**
 * Popup « une mise à jour est disponible », par-dessus l'infodivertissement.
 *
 * Calqué sur [ProfileConfirmOverlay] : même fenêtre overlay, même fond assombri, même carte.
 * Deux différences assumées :
 *  • aucun compte à rebours — deux des trois choix sont définitifs, on ne les prend pas sous
 *    la pression d'un chronomètre ;
 *  • le fond assombri vaut « plus tard » : il faut une sortie qui n'engage à rien.
 */
object UpdateOverlay {

    private const val TAG = "MG4_OVERLAY"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null

    fun show(
        context: Context,
        info: UpdateInfo,
        versionActuelle: String,
        onInstaller: () -> Unit,
        onIgnorer: () -> Unit,
        onDesactiver: () -> Unit,
        onAffiche: () -> Unit = {}
    ) {
        handler.post {
            showOnMain(context, info, versionActuelle, onInstaller, onIgnorer, onDesactiver,
                onAffiche)
        }
    }

    private fun showOnMain(
        context: Context,
        info: UpdateInfo,
        versionActuelle: String,
        onInstaller: () -> Unit,
        onIgnorer: () -> Unit,
        onDesactiver: () -> Unit,
        onAffiche: () -> Unit
    ) {
        // Même règle que le popup de confirmation de profil : rien ne recouvre l'écran en
        // roulant. La vérification reviendra au prochain contact, la mise à jour attendra.
        if (!VehicleWriteGate.isAllowedNow()) {
            AppLogger.w(TAG, "MAJ ${info.versionName} non signalée : véhicule en mouvement")
            return
        }
        dismiss(context)

        val localized = LocaleHelper.applyLocale(context)
        val themed = ContextThemeWrapper(localized, R.style.Theme_MG4Control)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_update, null)

        view.findViewById<TextView>(R.id.update_versions).text =
            localized.getString(R.string.update_overlay_versions, versionActuelle, info.versionName)

        // Un seul chemin de sortie : sans ce garde-fou, un double appui sur « Ignorer » pendant
        // que la fenêtre se retire déclencherait deux fois l'action.
        var done = false
        fun finish(action: (() -> Unit)?) {
            if (done) return
            done = true
            dismiss(context)
            action?.invoke()
        }

        view.findViewById<MaterialButton>(R.id.update_btn_install).setOnClickListener {
            finish(onInstaller)
        }
        view.findViewById<MaterialButton>(R.id.update_btn_skip).setOnClickListener {
            finish(onIgnorer)
        }
        view.findViewById<MaterialButton>(R.id.update_btn_disable).setOnClickListener {
            finish(onDesactiver)
        }
        // « Plus tard » : on ne retient rien, la vérification suivante reproposera.
        view.findViewById<View>(R.id.update_backdrop).setOnClickListener {
            AppLogger.i(TAG, "MAJ ${info.versionName} — reportée (fond touché)")
            finish(null)
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            // Permission d'affichage par-dessus les autres applications retirée, par exemple.
            AppLogger.w(TAG, "MAJ non signalée — overlay refusé : ${e.message}")
            return
        }
        overlayView = view
        AppLogger.i(TAG, "MAJ signalée : $versionActuelle → ${info.versionName}")
        // Seulement ICI : les deux sorties au-dessus renoncent à afficher, et l'annonce doit
        // pouvoir revenir au prochain réveil du véhicule.
        onAffiche()
    }

    fun dismiss(context: Context) {
        val v = overlayView ?: return
        overlayView = null
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture du popup MAJ : ${e.message}")
        }
    }
}
