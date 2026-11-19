package com.mg4.control.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.AppToggle

/**
 * Le côté Android de [AppToggle] : lit les tâches (REAL_GET_TASKS, comme le raccourci Android Auto /
 * CarPlay) et ramène l'écran d'avant quand l'appli visée est déjà affichée.
 */
object AppToggleRunner {

    private const val TAG = "MG4_SHORTCUT"
    private const val TACHES_MAX = 10

    /**
     * [pkg] déjà à l'écran : l'écran d'avant revient (l'accueil s'il n'y en a pas) et `true` ;
     * sinon `false`, et l'appelant ouvre l'appli comme d'habitude.
     */
    fun backIfFront(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        val taches = runCatching { am.getRunningTasks(TACHES_MAX).map { it.id to it.topActivity?.packageName } }
            .onFailure { AppLogger.w(TAG, "tâches illisibles : ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull() ?: return false
        if (!AppToggle.isFront(taches, pkg)) return false
        val retour = AppToggle.returnTask(taches, pkg)
        if (retour != null && runCatching { am.moveTaskToFront(retour, 0) }.isSuccess) {
            AppLogger.i(TAG, "$pkg déjà à l'écran → retour à ${taches.firstOrNull { it.first == retour }?.second}")
            return true
        }
        AppLogger.i(TAG, "$pkg déjà à l'écran → accueil")
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLogger.w(TAG, "accueil impossible : ${it.javaClass.simpleName} ${it.message}") }
        return true
    }
}
