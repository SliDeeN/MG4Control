package com.mg4.control.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import com.mg4.control.util.FloatingButtonSettings
import com.mg4.control.util.GarageMode
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Boutons de raccourci flottants (2026-10-23) : une fenêtre `TYPE_APPLICATION_OVERLAY` par bulle,
 * par-dessus toutes les applications (sous la barre d'état et le rail, comme les pop-ups).
 *
 * Appui COURT : le raccourci, relayé au service par le même intent que les raccourcis avancés —
 * un seul chemin d'exécution ; la bulle s'enfonce sous le doigt et un halo en sort au relâchement
 * ([FloatingHalo], variante D du 2026-10-23). Appui LONG ([APPUI_LONG_MS]) : la bulle se soulève (bordure orange)
 * et suit le doigt ; relâchée, sa place est enregistrée. Un glissé commencé avant l'appui long
 * n'est ni un appui ni un déplacement : c'est le doigt qui passait sur la carte.
 *
 * Masquées en marche arrière si l'option est cochée (rapport relu toutes les [RAPPORT_MS]), et
 * en Mode Garage. Thread principal, sauf la lecture du rapport. Journal : [TAG].
 */
object FloatingButtonsOverlay {

    const val TAG = "MG4_FLOAT"

    private const val APPUI_LONG_MS = 500L
    private const val RAPPORT_MS = 700L
    private const val LARGEUR_MAX_DP = 360f
    /** Bulle sous le doigt (maquette D) : à peine enfoncée, le halo dit le reste. */
    private const val ENFONCEE = 0.95f
    private const val ENFONCE_MS = 80L
    private const val RELACHE_MS = 100L

    /** Bulle : fond sombre presque opaque, bordure bleue (orange pendant le déplacement). */
    private val FOND = Color.argb(0xE6, 0x17, 0x17, 0x1A)
    private val BORDURE = Color.rgb(0x38, 0xBD, 0xF8)
    private val BORDURE_DEPLACEMENT = Color.rgb(0xF5, 0x9E, 0x0B)
    private val TEXTE = Color.rgb(0xE5, 0xE5, 0xE5)

    private val main = Handler(Looper.getMainLooper())
    private val lecteur: Handler by lazy { Handler(HandlerThread("mg4-float").also { it.start() }.looper) }

    @Volatile private var app: Context? = null
    private var fenetres: WindowManager? = null
    private val bulles = LinkedHashMap<String, Bulle>()
    private var marcheArriere = false
    /** Tour de surveillance du rapport en cours ; 0 = aucune. Un nouveau tour fait taire l'ancien. */
    @Volatile private var rapportTour = 0
    private var dernierRapport: Int? = null

    private class Bulle(val vue: TextView, val params: WindowManager.LayoutParams, var bouton: FloatingButton)

    /** Au démarrage du service et à chaque changement de réglage. */
    fun refresh(context: Context) {
        app = context.applicationContext
        main.post { reconstruire() }
    }

    private fun reconstruire() {
        val ctx = app ?: return
        retirerTout()
        val boutons = if (FloatingButtonSettings.isEnabled(ctx) && !GarageMode.isOn(ctx))
            FloatingButtonSettings.buttons(ctx) else emptyList()
        if (boutons.isEmpty()) {
            rapportTour = 0
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fenetres = wm
        boutons.forEach { poser(ctx, wm, it) }
        val surveiller = FloatingButtonSettings.hideInReverse(ctx)
        if (!surveiller) marcheArriere = false
        appliquerVisibilite()
        if (surveiller) {
            val tour = rapportTour + 1
            rapportTour = tour
            lecteur.post { dernierRapport = null; lireRapport(tour) }
        } else {
            rapportTour = 0
        }
        AppLogger.i(TAG, "${bulles.size} bouton(s) posé(s)" + if (surveiller) ", masqués en marche arrière" else "")
    }

    private fun retirerTout() {
        val wm = fenetres
        bulles.values.forEach { b -> runCatching { wm?.removeViewImmediate(b.vue) } }
        bulles.clear()
    }

    /** Taille de l'écran disponible pour les fenêtres (sans la barre d'état ni le rail). */
    @Suppress("DEPRECATION")
    private fun ecran(wm: WindowManager): Point = Point().also { wm.defaultDisplay.getSize(it) }

    @SuppressLint("ClickableViewAccessibility")
    private fun poser(ctx: Context, wm: WindowManager, b: FloatingButton) {
        val vue = TextView(ctx)
        peindre(vue, b, FloatingButtons.title(b, b.actionLabel), deplacement = false)
        vue.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val taille = ecran(wm)
        val (x, y) = FloatingButtons.topLeft(b.x, b.y, vue.measuredWidth, vue.measuredHeight, taille.x, taille.y)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "MG4Control bouton flottant"
        }
        val bulle = Bulle(vue, params, b)
        vue.setOnTouchListener(toucher(ctx, bulle))
        try {
            wm.addView(vue, params)
            bulles[b.id] = bulle
        } catch (e: Exception) {
            AppLogger.e(TAG, "bouton « ${vue.text} » non posé : ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Appui court = raccourci ; appui long = déplacer ; glissé précoce = rien (le doigt passait). */
    private fun toucher(ctx: Context, bulle: Bulle): View.OnTouchListener {
        val seuil = ViewConfiguration.get(ctx).scaledTouchSlop
        var departX = 0f
        var departY = 0f
        var fenetreX = 0
        var fenetreY = 0
        var deplacement = false
        var annule = false
        val appuiLong = Runnable {
            deplacement = true
            // L'enfoncement s'arrête là : peindre() pose l'agrandissement du déplacement.
            bulle.vue.animate().cancel()
            bulle.vue.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            peindre(bulle.vue, bulle.bouton, bulle.vue.text.toString(), deplacement = true)
        }
        return View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    departX = ev.rawX
                    departY = ev.rawY
                    fenetreX = bulle.params.x
                    fenetreY = bulle.params.y
                    deplacement = false
                    annule = false
                    bulle.vue.animate().scaleX(ENFONCEE).scaleY(ENFONCEE).setDuration(ENFONCE_MS).start()
                    main.postDelayed(appuiLong, APPUI_LONG_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - departX
                    val dy = ev.rawY - departY
                    if (deplacement) {
                        val wm = fenetres ?: return@OnTouchListener true
                        val taille = ecran(wm)
                        bulle.params.x = (fenetreX + dx).roundToInt().coerceIn(0, (taille.x - bulle.vue.width).coerceAtLeast(0))
                        bulle.params.y = (fenetreY + dy).roundToInt().coerceIn(0, (taille.y - bulle.vue.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(bulle.vue, bulle.params) }
                    } else if (!annule && hypot(dx, dy) > seuil) {
                        annule = true
                        main.removeCallbacks(appuiLong)
                        relacher(bulle.vue)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(appuiLong)
                    when {
                        deplacement -> {
                            deplacement = false
                            peindre(bulle.vue, bulle.bouton, bulle.vue.text.toString(), deplacement = false)
                            enregistrerPlace(ctx, bulle)
                        }
                        !annule -> {
                            relacher(bulle.vue)
                            halo(ctx, bulle)
                            declencher(ctx, bulle.bouton)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(appuiLong)
                    if (deplacement) {
                        deplacement = false
                        peindre(bulle.vue, bulle.bouton, bulle.vue.text.toString(), deplacement = false)
                        enregistrerPlace(ctx, bulle)
                    } else {
                        relacher(bulle.vue)
                    }
                }
            }
            true
        }
    }

    private fun relacher(vue: View) {
        vue.animate().scaleX(1f).scaleY(1f).setDuration(RELACHE_MS).start()
    }

    /** Halo autour de la bulle, à sa place et à sa taille d'origine (l'enfoncement ne compte pas). */
    private fun halo(ctx: Context, bulle: Bulle) {
        val wm = fenetres ?: return
        FloatingHalo.jouer(ctx, wm, bulle.params.x, bulle.params.y, bulle.vue.width, bulle.vue.height, ecran(wm), BORDURE)
    }

    private fun enregistrerPlace(ctx: Context, bulle: Bulle) {
        val wm = fenetres ?: return
        val taille = ecran(wm)
        val (cx, cy) = FloatingButtons.centre(bulle.params.x, bulle.params.y, bulle.vue.width, bulle.vue.height, taille.x, taille.y)
        bulle.bouton = bulle.bouton.copy(x = cx, y = cy)
        FloatingButtonSettings.update(ctx, bulle.bouton)
        AppLogger.i(TAG, "« ${bulle.vue.text} » déplacé en (${"%.2f".format(java.util.Locale.US, cx)}, " +
            "${"%.2f".format(java.util.Locale.US, cy)})")
    }

    /** Le raccourci, par le chemin des raccourcis avancés ; clé de bascule propre à la bulle. */
    private fun declencher(ctx: Context, b: FloatingButton) {
        AppLogger.i(TAG, "appui sur « ${FloatingButtons.title(b, b.actionLabel)} » → ${b.action}")
        val i = Intent(ctx, MG4ControlService::class.java).apply {
            action = MG4ControlService.ACTION_ADV_SHORTCUT
            putExtra(MG4ControlService.EXTRA_ADV_ACTION, b.action)
            putExtra(MG4ControlService.EXTRA_ADV_SLOT, "floating:${b.id}")
        }
        runCatching { ctx.startForegroundService(i) }
            .onFailure { AppLogger.w(TAG, "relais impossible : ${it.message}") }
    }

    /**
     * Habille [vue] en bulle de [b] titrée [titre] : hauteur et largeur minimales selon la taille,
     * texte sur deux lignes au plus. Sert aussi à l'aperçu de la page de réglage.
     */
    fun peindre(vue: TextView, b: FloatingButton, titre: String, deplacement: Boolean) {
        val densite = vue.resources.displayMetrics.density
        val hauteur = (FloatingButtons.heightDp(b.size) * densite).roundToInt()
        vue.text = titre
        vue.setTextColor(TEXTE)
        vue.setTextSize(TypedValue.COMPLEX_UNIT_SP, FloatingButtons.clampText(b.textSp).toFloat())
        vue.gravity = Gravity.CENTER
        vue.maxLines = 2
        vue.ellipsize = TextUtils.TruncateAt.END
        vue.includeFontPadding = false
        vue.minHeight = hauteur
        vue.minWidth = (FloatingButtons.minWidthDp(b.size) * densite).roundToInt()
        vue.maxWidth = (LARGEUR_MAX_DP * densite).roundToInt()
        val marge = (hauteur * 0.35f).roundToInt()
        vue.setPadding(marge, (6 * densite).roundToInt(), marge, (6 * densite).roundToInt())
        vue.background = GradientDrawable().apply {
            cornerRadius = hauteur / 2f
            setColor(FOND)
            setStroke((2 * densite).roundToInt(), if (deplacement) BORDURE_DEPLACEMENT else BORDURE)
        }
        vue.scaleX = if (deplacement) 1.1f else 1f
        vue.scaleY = vue.scaleX
    }

    private fun appliquerVisibilite() {
        bulles.values.forEach { it.vue.visibility = if (marcheArriere) View.GONE else View.VISIBLE }
    }

    /** Rapport relu tant que des bulles sont posées et l'option cochée (fil mg4-float). */
    private fun lireRapport(tour: Int) {
        run {
            if (rapportTour != tour) return
            val g = MG4Hardware.readGearRaw()
            if (g != null && g != dernierRapport) {
                dernierRapport = g
                val arriere = g == FloatingButtons.GEAR_REVERSE
                AppLogger.i(TAG, "rapport $g" + if (arriere) " (marche arrière) → boutons masqués" else "")
                main.post {
                    if (marcheArriere != arriere) {
                        marcheArriere = arriere
                        appliquerVisibilite()
                    }
                }
            }
            lecteur.postDelayed({ lireRapport(tour) }, RAPPORT_MS)
        }
    }
}
