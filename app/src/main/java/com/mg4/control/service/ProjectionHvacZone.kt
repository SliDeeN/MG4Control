package com.mg4.control.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ProjectionKind
import com.mg4.control.util.FloatingButtonSettings
import com.mg4.control.util.GarageMode
import kotlin.math.roundToInt

/**
 * Zone clim pendant Android Auto / CarPlay (2026-10-24) : une fenêtre invisible posée sur le haut
 * de l'écran, au centre, qui ouvre NOTRE pop-up HVAC ([HvacPopupOverlay]) — seulement tant que la
 * projection est au premier plan.
 *
 * Pourquoi : pendant la projection, SystemUI refuse lui-même d'ouvrir la clim depuis la barre du
 * haut. SWI133 / 68 / 165 : la languette grise du panneau clim (« dragHVACPage suppressed ») ;
 * A9 : le bloc clim centré de la barre système (`StatusBarView.isForbidClickHvac`, qui teste
 * `com.allgo.app.androidauto` et `com.allgo.carplay.service`).
 *
 * Cette barre est une fenêtre de la barre système, au-dessus des fenêtres d'appli : la zone doit
 * passer au-dessus d'elle, d'où un type de fenêtre système ([TYPE_ZONE], permis à l'uid système),
 * avec un second type en repli. Appui ou glissé : le pop-up s'ouvre, ou se referme.
 *
 * Projection devant : tâche du haut relue toutes les [SONDE_MS] (comme [ProjectionShortcut]).
 * Interrupteur dans Raccourcis › Boutons flottants, activé par défaut, indépendant des bulles.
 * Couverture de la barre vérifiée en voiture le 2026-10-24 (l'option qui colorait la zone pour ce
 * contrôle a été retirée ensuite). Journal : [TAG].
 */
object ProjectionHvacZone {

    private const val TAG = "MG4_ZONE"
    private const val SONDE_MS = 1_000L

    /** `TYPE_STATUS_BAR_SUB_PANEL` (caché du SDK) : juste au-dessus de la barre système. */
    private const val TYPE_ZONE = 2017
    /** Repli : `TYPE_SYSTEM_ERROR`, plus haut encore, si le premier est refusé. */
    private const val TYPE_ZONE_REPLI = 2010

    /** Largeur de la zone : la languette SWI133 fait 220 px, le bloc clim A9 un peu plus. */
    private const val LARGEUR_DP = 320f
    /** Sous la barre système, de quoi couvrir une languette qui en dépasse. */
    private const val DEBORD_DP = 16f

    private val main = Handler(Looper.getMainLooper())
    private val lecteur: Handler by lazy { Handler(HandlerThread("mg4-zone").also { it.start() }.looper) }

    @Volatile private var app: Context? = null
    /** Tour de surveillance en cours ; 0 = aucune. Un nouveau tour fait taire l'ancien. */
    @Volatile private var tour = 0
    private var vue: View? = null
    private var fenetres: WindowManager? = null
    @Volatile private var enProjection = false

    /** Au démarrage du service et à chaque changement de réglage. */
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        main.post {
            retirer()
            if (!FloatingButtonSettings.isHvacZoneEnabled(ctx) || GarageMode.isOn(ctx)) {
                tour = 0
                return@post
            }
            val t = tour + 1
            tour = t
            enProjection = false
            lecteur.post { sonder(ctx, t) }
        }
    }

    /** Fil mg4-zone : la projection est-elle devant ? La zone suit, sur le fil principal. */
    private fun sonder(ctx: Context, t: Int) {
        if (tour != t) return
        val devant = premierPlan(ctx)
        val projection = ProjectionKind.isProjection(devant)
        if (projection != enProjection) {
            enProjection = projection
            AppLogger.i(TAG, if (projection) "projection au premier plan ($devant) : zone clim posée"
                else "projection quittée : zone clim retirée")
            main.post { if (tour == t) { if (projection) poser(ctx) else retirer() } }
        }
        lecteur.postDelayed({ sonder(ctx, t) }, SONDE_MS)
    }

    @Suppress("DEPRECATION")
    private fun premierPlan(ctx: Context): String? = runCatching {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    @SuppressLint("ClickableViewAccessibility")
    private fun poser(ctx: Context) {
        if (vue != null) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = ctx.resources.displayMetrics.density
        val barre = barreSysteme(ctx)
        val v = View(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, ev ->
                // L'appui ou le glissé vers le bas de l'original : au relâchement, quel qu'il soit.
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    AppLogger.i(TAG, "zone clim touchée : pop-up HVAC")
                    HvacPopupOverlay.toggle(ctx)
                }
                true
            }
        }
        for (type in intArrayOf(TYPE_ZONE, TYPE_ZONE_REPLI)) {
            val params = WindowManager.LayoutParams(
                (LARGEUR_DP * d).roundToInt(),
                barre + (DEBORD_DP * d).roundToInt(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 0
                title = "MG4Control zone clim"
            }
            val ok = runCatching { wm.addView(v, params) }
                .onFailure { AppLogger.w(TAG, "zone clim refusée en type $type : ${it.javaClass.simpleName} ${it.message}") }
                .isSuccess
            if (ok) {
                vue = v
                fenetres = wm
                AppLogger.i(TAG, "zone clim posée (type $type, ${params.width} × ${params.height} px)")
                return
            }
        }
    }

    private fun retirer() {
        val v = vue ?: return
        runCatching { fenetres?.removeViewImmediate(v) }
        vue = null
    }

    /** Hauteur de la barre système, en px (la valeur d'Android, comme SystemUI la lit). */
    private fun barreSysteme(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else (40 * ctx.resources.displayMetrics.density).roundToInt()
    }
}
