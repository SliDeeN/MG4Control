package com.mg4.control.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.mg4.control.debug.AppLogger
import com.mg4.control.ui.PreClimateActivity
import java.lang.ref.WeakReference

/**
 * Pré-climatisation (issue #130) : ouvre et ferme l'écran de suivi, [PreClimateActivity].
 *
 * D'abord une fenêtre superposée (2026-10-23) : elle restait sous la barre du haut et le rail de
 * gauche, qu'aucune fenêtre d'application ne recouvre. C'est désormais une activité en plein écran
 * immersif — la voie de DiAuto-MG4, qui masque les deux barres sur les MG4 (`SystemUI.apply`).
 */
object PreClimateScreen {

    private const val TAG = PreClimate.TAG

    private val handler = Handler(Looper.getMainLooper())
    private var activite: WeakReference<PreClimateActivity>? = null

    fun isShowing(): Boolean = activite?.get() != null

    /**
     * Affiche l'écran, ou le ramène devant et le rallume s'il existe déjà. Fil principal.
     *
     * Toujours relancé : quitté par l'accueil ou une autre appli (pas par « Masquer »), il reste
     * ouvert en arrière-plan — le seul rallumer ne le faisait pas revenir (2026-11-02). L'activité
     * étant `singleInstance`, c'est la même instance qui revient, par [PreClimateActivity.onNewIntent].
     */
    fun afficher(context: Context, raison: String) {
        AppLogger.i(TAG, "Écran de suivi : " + (if (activite?.get() != null) "ramené devant" else "ouverture") + " ($raison)")
        try {
            context.startActivity(Intent(context, PreClimateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Écran de suivi non affiché : ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Retire l'écran, sans toucher au programme. Appelable de n'importe quel fil. */
    fun fermer(raison: String) {
        handler.post {
            val a = activite?.get() ?: return@post
            AppLogger.i(TAG, "Écran de suivi fermé ($raison)")
            a.finish()
        }
    }

    internal fun attacher(a: PreClimateActivity) {
        activite = WeakReference(a)
    }

    internal fun detacher(a: PreClimateActivity) {
        if (activite?.get() === a) activite = null
    }
}
