package com.mg4.control.service

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mg4.control.MainActivity
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.QuickSettings
import com.mg4.control.model.QuickTile
import com.mg4.control.util.PopupDelay
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Pop-up Réglages rapides (issue #197, maquette C du 2026-11-05) : les tuiles choisies dans
 * Raccourcis › Réglages rapides, avec leur valeur lue sur la voiture. Toucher une tuile ouvre ses
 * valeurs juste dessous ; en toucher une l'écrit, puis la tuile est relue. Se ferme seule après le
 * délai commun des pop-ups ([PopupDelay]), remis à zéro à chaque toucher ; un second appui du
 * raccourci, la croix ou un toucher à côté la referment.
 *
 * « Choisir les tuiles » (tant qu'il reste de la place) et un appui long sur une tuile ouvrent la
 * page de réglage dans MG4Control. Fil principal, sauf les lectures et écritures (file [vehicule]).
 */
object QuickSettingsOverlay {

    private const val TAG = "MG4_QUICK"
    /** Carte de base, agrandie comme le pop-up HVAC pour prendre la même part de l'écran. */
    private const val LARGEUR_DP = 1108f
    private const val HAUTEUR_DP = 392f
    private const val MARGE_FENETRE_DP = 12f
    private const val TICK_MS = 250L
    /** Après une écriture, la voiture met un instant à rendre la nouvelle valeur. */
    private const val RELECTURE_MS = 1_200L
    /** Au-delà, la voiture « roule » pour les réglages refusés en route. */
    private const val EN_ROUTE_KMH = 3f

    private val handler = Handler(Looper.getMainLooper())
    private val vehicule = Executors.newSingleThreadExecutor { r -> Thread(r, "mg4-quick") }

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null
    private var ctxApp: Context? = null
    private var themed: Context? = null
    private var tuiles: List<QuickTile> = emptyList()
    private val valeurs = HashMap<QuickTile, Int?>()
    private var choisie: QuickTile? = null
    private var enRoute = false
    private var delaiMs = PopupDelay.DEFAULT_SECONDS * 1_000L
    private var echeance = 0L
    private var ouvertureEnCours = false
    private var grille: LinearLayout? = null
    private var panneau: LinearLayout? = null
    private var decompte: TextView? = null
    private var tick: Runnable? = null

    fun isShowing(): Boolean = vue != null

    /** Raccourci : ouvre le pop-up, ou le referme s'il est déjà à l'écran. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (vue != null) {
                AppLogger.i(TAG, "Réglages rapides — second appui du raccourci → fermeture")
                fermer()
                return@post
            }
            if (ouvertureEnCours) return@post
            ouvertureEnCours = true
            val liste = QuickTiles.tiles(app)
            vehicule.execute {
                val lus = liste.associateWith { QuickTiles.lire(it) }
                val roule = (MG4Hardware.getVehicleSpeedKmh() ?: 0f) > EN_ROUTE_KMH
                handler.post {
                    ouvertureEnCours = false
                    ouvrir(app, liste, lus, roule)
                }
            }
        }
    }

    private fun ouvrir(app: Context, liste: List<QuickTile>, lus: Map<QuickTile, Int?>, roule: Boolean) {
        fermer()
        val c = OverlayKit.contextes(app, LARGEUR_DP, HAUTEUR_DP)
        ctxApp = app
        themed = c.themed
        tuiles = liste
        valeurs.clear(); valeurs.putAll(lus)
        choisie = null
        enRoute = roule
        delaiMs = PopupDelay.millis(app)
        val racine = try {
            construire(c.themed).also {
                OverlayKit.tenirDansLaFenetre(it, (MARGE_FENETRE_DP * c.mesures.density).roundToInt(), "Réglages rapides")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Réglages rapides non affichés — mise en page : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(racine, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Réglages rapides non affichés — fenêtre : ${e.javaClass.simpleName} ${e.message}")
            return
        }
        vue = racine
        gestionnaire = wm
        AppLogger.i(TAG, "Réglages rapides affichés : " + liste.joinToString { "${it.name}=${lus[it] ?: "?"}" } +
            (if (roule) " (en roulant)" else "") + " — échelle ×${String.format(Locale.US, "%.2f", c.echelle)}")
        relancerDelai()
        val t = object : Runnable {
            override fun run() {
                if (vue == null) return
                val reste = echeance - SystemClock.uptimeMillis()
                if (reste <= 0) {
                    AppLogger.i(TAG, "Réglages rapides — délai écoulé → fermeture")
                    fermer()
                    return
                }
                decompte?.text = ctxApp?.let { com.mg4.control.util.LocaleHelper.applyLocale(it) }
                    ?.getString(R.string.quick_closes_in, ((reste + 999) / 1_000).toInt())
                handler.postDelayed(this, TICK_MS)
            }
        }
        tick = t
        handler.post(t)
    }

    private fun relancerDelai() {
        echeance = SystemClock.uptimeMillis() + delaiMs
    }

    // ── Vues ──────────────────────────────────────────────────────────────────

    private fun dp(ctx: Context, v: Float) = OverlayKit.dp(ctx, v)

    private fun construire(ctx: Context): View {
        val racine = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setOnClickListener { fermer() }
        }
        val carte = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
            setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 20f))
            isClickable = true
            setOnClickListener { relancerDelai() }
        }
        // En-tête : pictogramme, titre, décompte, croix.
        val tete = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tete.addView(FrameLayout(ctx).apply {
            OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), rayon = dp(ctx, 14f).toFloat())
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_cat_adjust)
                imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
            }, FrameLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 28f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(ctx, 52f), dp(ctx, 52f)))
        tete.addView(texte(ctx, ctx.getString(R.string.quick_title), 24f, R.color.text_primary, gras = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(ctx, 14f) })
        decompte = texte(ctx, "", 17f, R.color.text_secondary)
        tete.addView(decompte)
        tete.addView(texte(ctx, "✕", 22f, R.color.text_primary).apply {
            gravity = Gravity.CENTER
            OverlayKit.fond(this, ctx.getColor(R.color.dash_btn), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
            setOnClickListener { fermer() }
        }, LinearLayout.LayoutParams(dp(ctx, 56f), dp(ctx, 56f)).also { it.marginStart = dp(ctx, 14f) })
        carte.addView(tete)

        grille = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        carte.addView(grille, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(ctx, 16f) })
        panneau = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
            OverlayKit.fond(this, ctx.getColor(R.color.dash_bg), rayon = dp(ctx, 16f).toFloat())
            visibility = View.GONE
        }
        carte.addView(panneau, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.topMargin = dp(ctx, 12f) })
        peindre()
        racine.addView(carte, FrameLayout.LayoutParams(dp(ctx, LARGEUR_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return racine
    }

    private fun texte(ctx: Context, t: String, sp: Float, couleur: Int, gras: Boolean = false) = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ctx.getColor(couleur))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (gras) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun bloquee(t: QuickTile) = enRoute && QuickTiles.aLArret(t)

    /** Redessine la grille et le panneau des valeurs. */
    private fun peindre() {
        val ctx = themed ?: return
        val g = grille ?: return
        g.removeAllViews()
        val cases: List<QuickTile?> = tuiles + (if (tuiles.size < QuickSettings.MAX_TILES) listOf(null) else emptyList())
        cases.chunked(4).forEachIndexed { i, ligne ->
            val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            repeat(4) { col ->
                val t = ligne.getOrNull(col)
                val cellule: View = when {
                    col >= ligne.size -> View(ctx).apply { visibility = View.INVISIBLE }
                    t == null -> QuickTileViews.ajout(ctx) { ouvrirPage() }
                    else -> QuickTileViews.tuile(ctx, t, valeurLibelle(ctx, t), choisie = t == choisie, grisee = bloquee(t)).apply {
                        setOnClickListener {
                            relancerDelai()
                            if (bloquee(t)) {
                                Toast.makeText(ctx, R.string.quick_refused_driving, Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            choisie = if (choisie == t) null else t
                            peindre()
                        }
                        setOnLongClickListener { ouvrirPage(); true }
                    }
                }
                rangee.addView(cellule, LinearLayout.LayoutParams(0, dp(ctx, 104f), 1f).also { if (col > 0) it.marginStart = dp(ctx, 10f) })
            }
            g.addView(rangee, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { if (i > 0) it.topMargin = dp(ctx, 10f) })
        }
        peindrePanneau(ctx)
    }

    private fun valeurLibelle(ctx: Context, t: QuickTile): String {
        val v = valeurs[t] ?: return "—"
        return QuickTiles.options(t).firstOrNull { it.valeur == v }?.let { QuickTiles.libelle(ctx, t, it) } ?: "—"
    }

    private fun peindrePanneau(ctx: Context) {
        val p = panneau ?: return
        val t = choisie
        p.removeAllViews()
        if (t == null) { p.visibility = View.GONE; return }
        p.visibility = View.VISIBLE
        p.addView(texte(ctx, ctx.getString(QuickTiles.nom(t)), 18f, R.color.text_secondary).apply { setPadding(0, 0, dp(ctx, 10f), 0) })
        val actuelle = valeurs[t]
        QuickTiles.options(t).forEach { o ->
            val on = o.valeur == actuelle
            p.addView(texte(ctx, QuickTiles.libelle(ctx, t, o), 19f, if (on) R.color.dash_on_accent else R.color.text_primary, gras = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 20f), 0, dp(ctx, 20f), 0)
                if (on) OverlayKit.fond(this, ctx.getColor(R.color.dash_accent), rayon = dp(ctx, 14f).toFloat())
                else OverlayKit.fond(this, ctx.getColor(R.color.dash_btn), ctx.getColor(R.color.dash_border), dp(ctx, 1f), dp(ctx, 14f).toFloat())
                setOnClickListener { relancerDelai(); if (!on) ecrire(t, o.valeur) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, 56f)).also { it.marginStart = dp(ctx, 8f) })
        }
    }

    // ── Voiture ───────────────────────────────────────────────────────────────

    private fun ecrire(t: QuickTile, v: Int) {
        val avant = valeurs[t]
        valeurs[t] = v
        peindre()
        AppLogger.i(TAG, "Réglages rapides — ${t.name} : ${avant ?: "?"} → $v")
        vehicule.execute {
            val ok = QuickTiles.ecrire(t, v)
            handler.post {
                if (!ok) themed?.let { Toast.makeText(it, R.string.quick_write_failed, Toast.LENGTH_SHORT).show() }
                handler.postDelayed({ relire(t) }, RELECTURE_MS)
            }
        }
    }

    /** Remet la tuile sur ce que la voiture a retenu. */
    private fun relire(t: QuickTile) {
        if (vue == null) return
        vehicule.execute {
            val lu = QuickTiles.lire(t)
            handler.post {
                if (vue == null) return@post
                if (lu != valeurs[t]) AppLogger.i(TAG, "Réglages rapides — ${t.name} relu : $lu")
                valeurs[t] = lu
                peindre()
            }
        }
    }

    /** La page Raccourcis › Réglages rapides, dans MG4Control. */
    private fun ouvrirPage() {
        val app = ctxApp ?: return
        AppLogger.i(TAG, "Réglages rapides — ouverture de la page de réglage")
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, MainActivity.PAGE_QUICK_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { AppLogger.w(TAG, "Lancement de MG4Control échoué : ${it.message}") }
        fermer()
    }

    private fun fermer() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        grille = null
        panneau = null
        decompte = null
        val v = vue ?: return
        vue = null
        runCatching { gestionnaire?.removeView(v) }
        gestionnaire = null
        // Rien de la fenêtre n'est gardé une fois fermée (contextes compris).
        themed = null
        ctxApp = null
        AppLogger.i(TAG, "Réglages rapides fermés")
    }
}

/**
 * Une tuile des Réglages rapides — dans le pop-up et dans l'aperçu de la page de réglage : son
 * nom (avec le pictogramme de sa catégorie) et sa valeur en gros.
 */
object QuickTileViews {

    private fun dp(ctx: Context, v: Float) = OverlayKit.dp(ctx, v)

    fun tuile(ctx: Context, t: QuickTile, valeur: String, choisie: Boolean = false, grisee: Boolean = false): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            OverlayKit.fond(this, ctx.getColor(if (choisie) R.color.dash_accent_dim else R.color.dash_btn),
                ctx.getColor(if (choisie) R.color.dash_accent else R.color.dash_border), dp(ctx, if (choisie) 2f else 1f), dp(ctx, 16f).toFloat())
            alpha = if (grisee) 0.45f else 1f
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageResource(QuickTiles.cat(t).icone)
                    imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.text_secondary))
                }, LinearLayout.LayoutParams(dp(ctx, 20f), dp(ctx, 20f)))
                addView(TextView(ctx).apply {
                    text = ctx.getString(QuickTiles.nom(t))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(ctx, 8f), 0, 0, 0)
                })
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(TextView(ctx).apply {
                text = valeur
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.getColor(if (choisie) R.color.dash_accent else R.color.text_primary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    /** « + Choisir les tuiles » : ouvre la page de réglage. */
    fun ajout(ctx: Context, onClick: () -> Unit): View = TextView(ctx).apply {
        text = "+  " + ctx.getString(R.string.quick_choose_tiles)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(ctx.getColor(R.color.text_secondary))
        gravity = Gravity.CENTER
        OverlayKit.fond(this, Color.TRANSPARENT, ctx.getColor(R.color.dash_border), dp(ctx, 2f), dp(ctx, 16f).toFloat())
        setOnClickListener { onClick() }
    }
}
