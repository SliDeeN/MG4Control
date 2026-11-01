package com.mg4.control.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.ProjectionReader
import com.mg4.control.model.ProjectionKind
import com.mg4.control.model.ProjectionRule
import com.mg4.control.model.ProjectionStep
import com.mg4.control.util.LocaleHelper

/**
 * Raccourci « Android Auto / CarPlay » (2026-10-23) : ouvre la projection du téléphone connecté,
 * dit qu'aucun téléphone ne l'est, ou — projection déjà à l'écran — ramène à l'écran d'avant
 * (ses deux choix). La règle est dans [ProjectionRule], la lecture dans [ProjectionReader].
 *
 * L'écran d'avant est la tâche la plus récente après la projection, dans la liste d'Android :
 * notre uid système voit toutes les tâches et peut les ramener devant. Aucune : l'accueil.
 *
 * Tout se passe sur un fil à lui : la première liaison à AllGo attend sa réponse. Journal [TAG].
 */
object ProjectionShortcut {

    private const val TAG = "MG4_PROJ"
    /** Assez pour passer quelques tâches sans activité ou sur l'écran du combiné. */
    private const val TACHES_MAX = 10

    private val main = Handler(Looper.getMainLooper())
    private val fil: Handler by lazy { Handler(HandlerThread("mg4-proj").also { it.start() }.looper) }
    /** Ne se lit et ne s'écrit que sur le thread principal. */
    private var message: Toast? = null

    fun press(context: Context) {
        val app = context.applicationContext
        fil.post { appui(app) }
    }

    private fun appui(app: Context) {
        val taches = taches(app)
        val devant = taches.firstOrNull()?.second
        val lecture = ProjectionReader.appareilActif(app)
        val appareil = (lecture as? ProjectionReader.Lecture.Lue)?.appareil
        val etape = ProjectionRule.step(appareil?.kind, devant)
        AppLogger.i(TAG, "SHORTCUT Android Auto / CarPlay — " + when (lecture) {
            ProjectionReader.Lecture.Injoignable -> "AllGo injoignable"
            is ProjectionReader.Lecture.Lue -> appareil?.let {
                "session ${it.kind?.label ?: "type ${it.type} inconnu"} (transport ${it.transport}, statut ${it.session})"
            } ?: "aucune session"
        } + ", au premier plan ${devant ?: "?"} → ${etape.javaClass.simpleName}")
        when (etape) {
            is ProjectionStep.Open -> ouvrir(app, etape.kind)
            ProjectionStep.Return -> revenir(app, taches)
            ProjectionStep.NoPhone ->
                annoncer(app, LocaleHelper.applyLocale(app).getString(R.string.projection_no_phone))
        }
    }

    /** Tâches en cours, la plus récente d'abord : (identifiant, paquet de l'activité du haut). */
    @Suppress("DEPRECATION")
    private fun taches(app: Context): List<Pair<Int, String?>> = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningTasks(TACHES_MAX).map { it.id to it.topActivity?.packageName }
    }.onFailure { AppLogger.w(TAG, "tâches illisibles : ${it.javaClass.simpleName} ${it.message}") }
        .getOrDefault(emptyList())

    /** Comme le lanceur A9 : MAIN / LAUNCHER sur le composant, dans une tâche à lui. */
    private fun ouvrir(app: Context, kind: ProjectionKind) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(kind.packageName, kind.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
            .onSuccess { AppLogger.i(TAG, "${kind.label} ouvert") }
            .onFailure {
                AppLogger.w(TAG, "${kind.label} non ouvert : ${it.javaClass.simpleName} ${it.message}")
                val loc = LocaleHelper.applyLocale(app)
                annoncer(app, loc.getString(R.string.projection_launch_failed, kind.label))
            }
    }

    private fun revenir(app: Context, taches: List<Pair<Int, String?>>) {
        val retour = ProjectionRule.returnTask(taches)
        if (retour != null) {
            val ok = runCatching {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(retour, 0)
            }.onFailure { AppLogger.w(TAG, "retour impossible : ${it.javaClass.simpleName} ${it.message}") }.isSuccess
            if (ok) {
                AppLogger.i(TAG, "retour à ${taches.firstOrNull { it.first == retour }?.second}")
                return
            }
        }
        AppLogger.i(TAG, "pas d'écran précédent : retour à l'accueil")
        runCatching {
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLogger.w(TAG, "accueil impossible : ${it.javaClass.simpleName} ${it.message}") }
    }

    /** Un seul message à la fois, dans la langue de l'application. */
    private fun annoncer(app: Context, texte: String) {
        main.post {
            val loc = LocaleHelper.applyLocale(app)
            message?.cancel()
            message = runCatching { Toast.makeText(loc, texte, Toast.LENGTH_SHORT).also { it.show() } }.getOrNull()
        }
    }
}
