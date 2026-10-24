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
import com.mg4.control.model.FloatingButtonStyle
import com.mg4.control.model.FloatingButtons
import com.mg4.control.model.FloatingKind
import com.mg4.control.shortcut.ShortcutAction
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
 * Style selon l'action (maquette A, 2026-10-24, [FloatingButtonStyle]) : une bascule a un fond
 * plein quand elle est activée, presque transparent quand elle est coupée — état relu toutes les
 * [FloatingButtonStyle.POLL_MS] et après chaque appui ; un cycle ou un réglage montre un instant
 * sa nouvelle valeur, relue sur la voiture ([FloatingButtonState]).
 *
 * Masquées en marche arrière si l'option est cochée (rapport relu toutes les [RAPPORT_MS]), et
 * en Mode Garage. Thread principal, sauf les lectures (fil mg4-float). Journal : [TAG].
 *
 * Bouton de la barre du haut (2026-10-24, [FloatingButtons.inTopBar]) : une fenêtre AU-DESSUS de la
 * barre noire — `TYPE_STATUS_BAR_SUB_PANEL` (2017), repli `TYPE_SYSTEM_ERROR` (2010), types déjà
 * acceptés pour la zone clim ([ProjectionHvacZone]) — à la hauteur de la barre, glissée de gauche à
 * droite seulement, jamais sur la languette de la clim ni sur un voisin
 * ([FloatingButtons.placeInTopBar]). Le type d'une fenêtre ne change pas une fois posée : c'est le
 * réglage du bouton qui le décide, à la pose. Les deux types refusés : posé juste sous la barre.
 *
 * Repère (corrigé le 2026-10-24 après essai) : la languette grise est centrée sur l'écran RÉEL,
 * rail compris, alors que la fenêtre d'une bulle commence après le rail quand il est affiché (au
 * bord quand il est caché, en Android Auto / CarPlay). Centrer sur la fenêtre décalait la zone
 * interdite vers la droite sur le launcher, vers la gauche en projection, et la bulle bougeait d'un
 * mode à l'autre. Tout se compte donc en px de l'écran réel ; l'origine de la fenêtre
 * ([Bulle.decalage]) est mesurée sur la vue posée, et relue toutes les [RECALAGE_MS] pour recaler
 * la bulle quand le rail apparaît ou disparaît.
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
    /** Bascule coupée : le fond s'efface ; activée : plein, de la couleur de la bordure. */
    private val FOND_COUPE = Color.argb(0x59, 0x17, 0x17, 0x1A)
    private val TEXTE_SUR_PLEIN = Color.rgb(0x06, 0x28, 0x3D)
    /** Seconde lecture de la valeur : si la voiture n'avait pas encore suivi à la première. */
    private const val VALEUR_RELUE_MS = 1_600L

    private val main = Handler(Looper.getMainLooper())
    private val lecteur: Handler by lazy { Handler(HandlerThread("mg4-float").also { it.start() }.looper) }

    @Volatile private var app: Context? = null
    private var fenetres: WindowManager? = null
    private val bulles = LinkedHashMap<String, Bulle>()
    @Volatile private var marcheArriere = false
    /** Tour de surveillance du rapport en cours ; 0 = aucune. Un nouveau tour fait taire l'ancien. */
    @Volatile private var rapportTour = 0
    private var dernierRapport: Int? = null
    /** Tour de relecture des bascules en cours ; 0 = aucune. */
    @Volatile private var etatTour = 0
    /** Bascules posées (id de la bulle, action), pour le fil de lecture. Écrit sur le fil principal. */
    @Volatile private var bascules: List<Pair<String, ShortcutAction>> = emptyList()

    private class Bulle(val vue: TextView, val params: WindowManager.LayoutParams, var bouton: FloatingButton) {
        /** Bascule : état lu, `null` tant qu'il est inconnu (ou pour une autre action). */
        var etat: Boolean? = null
        /** Fin de l'affichage d'une valeur en cours, ou `null`. */
        var eclair: Runnable? = null
        var enDeplacement = false
        /** Barre du haut : bord gauche de la fenêtre sur l'écran réel (le rail, quand il est affiché). */
        var decalage = 0
    }

    /** Relecture de l'origine des fenêtres de la barre du haut (rail affiché ou caché). */
    private const val RECALAGE_MS = 500L
    private val recalage = object : Runnable {
        override fun run() {
            val enHaut = bulles.values.filter { dansLaBarre(it) }
            if (enHaut.isEmpty()) return
            enHaut.forEach { recaler(it) }
            main.postDelayed(this, RECALAGE_MS)
        }
    }

    /** Au démarrage du service et à chaque changement de réglage. */
    fun refresh(context: Context) {
        app = context.applicationContext
        main.post { reconstruire() }
    }

    /**
     * Un profil vient d'être appliqué (2026-10-24) : ses bulles remplacent celles à l'écran — sans
     * rien reposer si ce sont les mêmes, pour qu'elles ne clignotent pas à chaque passage en READY.
     */
    fun suivreProfil(context: Context) {
        app = context.applicationContext
        main.post {
            val ctx = app ?: return@post
            val nouvelles = aPoser(ctx)
            val change = nouvelles != bulles.values.map { it.bouton }
            AppLogger.i(TAG, "profil appliqué : bulles du profil ${com.mg4.control.util.FloatingProfiles.target(ctx)?.name}" +
                (com.mg4.control.util.FloatingProfiles.editingId?.let { " (page des boutons ouverte sur $it)" } ?: "") +
                " → ${nouvelles.size} bulle(s), ${if (change) "reposées" else "inchangées"}")
            if (change) reconstruire()
        }
    }

    /** Bulles à poser : celles du profil à l'écran, si elles sont affichées (et hors Mode Garage). */
    private fun aPoser(ctx: Context): List<FloatingButton> =
        if (FloatingButtonSettings.isEnabled(ctx) && !GarageMode.isOn(ctx)) FloatingButtonSettings.buttons(ctx) else emptyList()

    private fun reconstruire() {
        val ctx = app ?: return
        retirerTout()
        val boutons = aPoser(ctx)
        if (boutons.isEmpty()) {
            rapportTour = 0
            etatTour = 0
            bascules = emptyList()
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fenetres = wm
        boutons.forEach { poser(ctx, wm, it) }
        if (bulles.values.any { dansLaBarre(it) }) main.postDelayed(recalage, RECALAGE_MS)
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
        bascules = boutons.mapNotNull { b ->
            action(b)?.takeIf { FloatingButtonStyle.kind(it) == FloatingKind.TOGGLE }?.let { b.id to it }
        }
        if (bascules.isNotEmpty()) {
            val tour = etatTour + 1
            etatTour = tour
            lecteur.post { lireBascules(tour) }
        } else {
            etatTour = 0
        }
        AppLogger.i(TAG, "${bulles.size} bouton(s) posé(s)" + if (surveiller) ", masqués en marche arrière" else "")
    }

    private fun action(b: FloatingButton): ShortcutAction? = ShortcutAction.entries.firstOrNull { it.name == b.action }

    private fun titre(bulle: Bulle): String = FloatingButtons.title(bulle.bouton, bulle.bouton.actionLabel)

    /** Relit les bascules posées, puis recommence tant que le tour est le bon (fil mg4-float). */
    private fun lireBascules(tour: Int) {
        if (etatTour != tour) return
        if (!marcheArriere) relire(bascules)
        lecteur.postDelayed({ lireBascules(tour) }, FloatingButtonStyle.POLL_MS)
    }

    /** Fil mg4-float : l'état de la clim est lu au plus une fois pour toutes les bulles. */
    private fun relire(cibles: List<Pair<String, ShortcutAction>>) {
        var clim: MG4Hardware.ClimateState? = null
        var climLue = false
        val lus = cibles.map { (id, a) ->
            id to FloatingButtonState.bascule(a) {
                if (!climLue) {
                    climLue = true
                    clim = runCatching { MG4Hardware.getClimateState() }.getOrNull()
                }
                clim
            }
        }
        main.post { lus.forEach { (id, etat) -> bulles[id]?.let { teinter(it, etat) } } }
    }

    /** Nouvel état d'une bascule : seul le fond change, ni la taille ni le titre. */
    private fun teinter(bulle: Bulle, etat: Boolean?) {
        if (bulle.etat == etat) return
        bulle.etat = etat
        habiller(bulle.vue, bulle.bouton, bulle.enDeplacement, etat)
    }

    /** Après un appui court : relit la bascule (deux fois), ou montre la nouvelle valeur. */
    private fun apresAppui(ctx: Context, bulle: Bulle) {
        val a = action(bulle.bouton) ?: return
        val id = bulle.bouton.id
        when (FloatingButtonStyle.kind(a)) {
            FloatingKind.TOGGLE -> {
                lecteur.postDelayed({ relire(listOf(id to a)) }, FloatingButtonStyle.READBACK_MS)
                lecteur.postDelayed({ relire(listOf(id to a)) }, FloatingButtonStyle.READBACK_LATE_MS)
            }
            FloatingKind.VALUE -> {
                // Deux lectures : la seconde corrige la première si la voiture n'avait pas encore suivi.
                listOf(FloatingButtonStyle.READBACK_MS, VALEUR_RELUE_MS).forEach { delai ->
                    lecteur.postDelayed({
                        val v = FloatingButtonState.valeur(ctx, a) ?: return@postDelayed
                        main.post { bulles[id]?.takeIf { it.vue.text.toString() != v }?.let { eclair(it, v) } }
                    }, delai)
                }
            }
            FloatingKind.NONE -> {}
        }
    }

    /** La valeur, en bleu, à la place du titre pendant [FloatingButtonStyle.FLASH_MS]. */
    private fun eclair(bulle: Bulle, texte: String) {
        if (bulle.enDeplacement) return
        bulle.eclair?.let(main::removeCallbacks)
        val vue = bulle.vue
        // Largeur gardée : la fenêtre suit son contenu, une valeur plus courte la ferait rétrécir.
        vue.minWidth = maxOf(vue.width, vue.minWidth)
        vue.text = texte
        vue.setTextColor(BORDURE)
        val fin = Runnable {
            bulle.eclair = null
            peindre(vue, bulle.bouton, titre(bulle), deplacement = false, etat = bulle.etat)
        }
        bulle.eclair = fin
        main.postDelayed(fin, FloatingButtonStyle.FLASH_MS)
    }

    private fun retirerTout() {
        val wm = fenetres
        bulles.values.forEach { b -> b.eclair?.let(main::removeCallbacks) }
        bulles.values.forEach { b -> runCatching { wm?.removeViewImmediate(b.vue) } }
        bulles.clear()
        main.removeCallbacks(recalage)
    }

    /** Taille de l'écran disponible pour les fenêtres (sans la barre d'état ni le rail). */
    @Suppress("DEPRECATION")
    private fun ecran(wm: WindowManager): Point = Point().also { wm.defaultDisplay.getSize(it) }

    /** Types essayés pour un bouton de la barre du haut, dans l'ordre (voir l'en-tête). */
    private val TYPES_BARRE = intArrayOf(2017, 2010)

    /** Taille réelle de l'écran, rail compris : le repère des boutons de la barre du haut. */
    @Suppress("DEPRECATION")
    private fun ecranReel(wm: WindowManager): Point = Point().also { wm.defaultDisplay.getRealSize(it) }

    /**
     * Ce qu'un bouton de la barre du haut ne doit pas recouvrir, en px de l'écran réel : la languette
     * de la clim (au centre de l'écran réel), le début de la barre que sa fenêtre n'atteint pas (le
     * rail affiché, [decalage]) et les autres boutons de la barre, [sauf] lui-même.
     */
    private fun bloquesHaut(ctx: Context, largeurEcran: Int, decalage: Int, sauf: String?): List<ClosedFloatingPointRange<Float>> {
        val d = ctx.resources.displayMetrics.density
        val liste = mutableListOf(FloatingButtons.topBarHvac(largeurEcran.toFloat(), FloatingButtons.TOP_HVAC_DP * d))
        if (decalage > 0) liste += -1f..decalage.toFloat()
        bulles.values.filter { it.bouton.id != sauf && dansLaBarre(it) }
            .mapTo(liste) { (it.params.x + it.decalage).toFloat()..(it.params.x + it.decalage + it.vue.measuredWidth).toFloat() }
        return liste
    }

    /**
     * Position de fenêtre (`params.x`) d'un bouton de la barre de [largeur] px, voulu au bord gauche
     * [voulu] (px de l'écran réel), dans une fenêtre qui commence à [decalage].
     */
    private fun placeHaut(ctx: Context, voulu: Float, largeur: Int, largeurEcran: Int, decalage: Int, sauf: String?): Int =
        (FloatingButtons.placeInTopBar(voulu, largeur.toFloat(), largeurEcran.toFloat(),
            bloquesHaut(ctx, largeurEcran, decalage, sauf),
            FloatingButtons.TOP_GAP_DP * ctx.resources.displayMetrics.density) - decalage).roundToInt()

    /**
     * Mesure où la fenêtre d'un bouton de la barre commence vraiment ; si le rail est apparu ou a
     * disparu depuis, le bouton est remis à sa place sur l'écran réel. Rien pendant un appui.
     */
    private fun recaler(bulle: Bulle) {
        val wm = fenetres ?: return
        val ctx = app ?: return
        if (!bulle.vue.isAttachedToWindow || bulle.enDeplacement || bulle.vue.scaleX != 1f) return
        val loc = IntArray(2).also { bulle.vue.getLocationOnScreen(it) }
        val decalage = loc[0] - bulle.params.x
        if (decalage == bulle.decalage) return
        bulle.decalage = decalage
        val largeurEcran = ecranReel(wm).x
        val voulu = bulle.bouton.x * largeurEcran - bulle.vue.width / 2f
        bulle.params.x = placeHaut(ctx, voulu, bulle.vue.width, largeurEcran, decalage, bulle.bouton.id)
        runCatching { wm.updateViewLayout(bulle.vue, bulle.params) }
        AppLogger.i(TAG, "« ${bulle.vue.text} » recalé dans la barre : fenêtre à $decalage px du bord de l'écran " +
            "(rail ${if (decalage > 0) "affiché" else "caché"})")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun poser(ctx: Context, wm: WindowManager, b: FloatingButton) {
        val vue = TextView(ctx)
        peindre(vue, b, FloatingButtons.title(b, b.actionLabel), deplacement = false)
        vue.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val taille = ecran(wm)
        if (FloatingButtons.inTopBar(b) && poserEnHaut(ctx, wm, b, vue)) return
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

    /**
     * Bouton de la barre du haut : au-dessus de la barre, centré dans sa hauteur, à sa place ou à
     * la première place libre ([FloatingButtons.TOP_AUTO]), alors enregistrée. `false` si les deux
     * types de fenêtre sont refusés : l'appelant le pose comme les autres, juste sous la barre.
     */
    private fun poserEnHaut(ctx: Context, wm: WindowManager, b: FloatingButton, vue: TextView): Boolean {
        val d = ctx.resources.displayMetrics.density
        val barre = ProjectionHvacZone.barreSysteme(ctx)
        val largeur = vue.measuredWidth
        val largeurEcran = ecranReel(wm).x
        // Origine de la fenêtre supposée : après le rail (launcher). Mesurée une fois posée ([recaler]).
        val decalage = (largeurEcran - ecran(wm).x).coerceAtLeast(0)
        val auto = b.x < 0f
        val voulu = if (auto) 0f else b.x * largeurEcran - largeur / 2f
        val x = placeHaut(ctx, voulu, largeur, largeurEcran, decalage, b.id)
        val y = ((barre - vue.measuredHeight) / 2).coerceAtLeast(0)
        for (type in TYPES_BARRE) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
                title = "MG4Control bouton flottant (barre du haut)"
            }
            val bulle = Bulle(vue, params, b).also { it.decalage = decalage }
            vue.setOnTouchListener(toucher(ctx, bulle))
            val ok = runCatching { wm.addView(vue, params) }
                .onFailure { AppLogger.w(TAG, "« ${vue.text} » refusé dans la barre en type $type : ${it.javaClass.simpleName} ${it.message}") }
                .isSuccess
            if (!ok) continue
            bulles[b.id] = bulle
            if (auto || x + decalage != voulu.roundToInt()) {
                bulle.bouton = b.copy(x = ((x + decalage + largeur / 2f) / largeurEcran.coerceAtLeast(1)).coerceIn(0f, 1f))
                FloatingButtonSettings.update(ctx, bulle.bouton)
            }
            // L'origine supposée vérifiée sur la vue posée.
            vue.post { recaler(bulle) }
            AppLogger.i(TAG, "« ${vue.text} » posé dans la barre du haut (type $type, x=$x px, barre ${barre} px, ${(barre / d).roundToInt()} dp)")
            return true
        }
        return false
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
            bulle.enDeplacement = true
            // L'enfoncement s'arrête là : peindre() pose l'agrandissement du déplacement.
            bulle.vue.animate().cancel()
            bulle.eclair?.let(main::removeCallbacks)
            bulle.eclair = null
            bulle.vue.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            peindre(bulle.vue, bulle.bouton, titre(bulle), deplacement = true, etat = bulle.etat)
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
                        if (dansLaBarre(bulle)) {
                            // De gauche à droite seulement, en sautant la languette et les voisins.
                            bulle.params.x = placeHaut(ctx, fenetreX + bulle.decalage + dx, bulle.vue.width,
                                ecranReel(wm).x, bulle.decalage, bulle.bouton.id)
                        } else {
                            bulle.params.x = (fenetreX + dx).roundToInt().coerceIn(0, (taille.x - bulle.vue.width).coerceAtLeast(0))
                            bulle.params.y = (fenetreY + dy).roundToInt().coerceIn(0, (taille.y - bulle.vue.height).coerceAtLeast(0))
                        }
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
                            bulle.enDeplacement = false
                            peindre(bulle.vue, bulle.bouton, titre(bulle), deplacement = false, etat = bulle.etat)
                            enregistrerPlace(ctx, bulle)
                        }
                        !annule -> {
                            relacher(bulle.vue)
                            halo(ctx, bulle)
                            declencher(ctx, bulle.bouton)
                            apresAppui(ctx, bulle)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(appuiLong)
                    if (deplacement) {
                        deplacement = false
                        bulle.enDeplacement = false
                        peindre(bulle.vue, bulle.bouton, titre(bulle), deplacement = false, etat = bulle.etat)
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
        if (dansLaBarre(bulle)) {
            // Même couche et même repère que la bulle : sa fenêtre, de la fin du rail au bord droit,
            // barre comprise.
            val reel = ecranReel(wm)
            val e = Point(reel.x - bulle.decalage, reel.y)
            FloatingHalo.jouer(ctx, wm, bulle.params.x, bulle.params.y, bulle.vue.width, bulle.vue.height, e, BORDURE,
                type = bulle.params.type, pleinEcran = true)
        } else {
            FloatingHalo.jouer(ctx, wm, bulle.params.x, bulle.params.y, bulle.vue.width, bulle.vue.height, ecran(wm), BORDURE)
        }
    }

    /** Bulle posée au-dessus de la barre du haut (et non repliée dessous, faute de type accepté). */
    private fun dansLaBarre(bulle: Bulle): Boolean =
        FloatingButtons.inTopBar(bulle.bouton) && bulle.params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun enregistrerPlace(ctx: Context, bulle: Bulle) {
        val wm = fenetres ?: return
        val taille = ecran(wm)
        val (cx, cy) = FloatingButtons.centre(bulle.params.x, bulle.params.y, bulle.vue.width, bulle.vue.height, taille.x, taille.y)
        // Dans la barre, seule la place de gauche à droite compte, sur l'écran réel.
        bulle.bouton = if (dansLaBarre(bulle)) {
            val largeurEcran = ecranReel(wm).x.coerceAtLeast(1)
            bulle.bouton.copy(x = ((bulle.params.x + bulle.decalage + bulle.vue.width / 2f) / largeurEcran).coerceIn(0f, 1f))
        } else bulle.bouton.copy(x = cx, y = cy)
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
            b.customId?.let { putExtra(MG4ControlService.EXTRA_ADV_CUSTOM, it) }
        }
        runCatching { ctx.startForegroundService(i) }
            .onFailure { AppLogger.w(TAG, "relais impossible : ${it.message}") }
    }

    /**
     * Habille [vue] en bulle de [b] titrée [titre] : hauteur et largeur minimales selon la taille,
     * texte sur deux lignes au plus. [etat] : celui d'une bascule (`null` = inconnu ou autre action).
     * Sert aussi aux pastilles de la page de réglage.
     */
    fun peindre(vue: TextView, b: FloatingButton, titre: String, deplacement: Boolean, etat: Boolean? = null) {
        val densite = vue.resources.displayMetrics.density
        val enHaut = FloatingButtons.inTopBar(b)
        // Dans la barre du haut : sa hauteur, moins une petite marge, et une seule ligne.
        val hauteur = if (enHaut)
            (ProjectionHvacZone.barreSysteme(vue.context) - 2 * FloatingButtons.TOP_INSET_DP * densite).roundToInt().coerceAtLeast(1)
        else (FloatingButtons.heightDp(b.size) * densite).roundToInt()
        vue.text = titre
        vue.setTextSize(TypedValue.COMPLEX_UNIT_SP, FloatingButtons.textFor(b).toFloat())
        vue.setTypeface(null, if (b.bold == true) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        vue.gravity = Gravity.CENTER
        vue.maxLines = if (enHaut) 1 else 2
        vue.ellipsize = TextUtils.TruncateAt.END
        vue.includeFontPadding = false
        vue.minHeight = hauteur
        if (enHaut) vue.height = hauteur
        vue.minWidth = if (enHaut) (hauteur * 1.6f).roundToInt() else (FloatingButtons.minWidthDp(b.size) * densite).roundToInt()
        vue.maxWidth = (LARGEUR_MAX_DP * densite).roundToInt()
        val marge = if (enHaut) (10 * densite).roundToInt() else (hauteur * 0.35f).roundToInt()
        val vertical = if (enHaut) 0 else (6 * densite).roundToInt()
        vue.setPadding(marge, vertical, marge, vertical)
        habiller(vue, b, deplacement, etat)
        vue.scaleX = if (deplacement) 1.1f else 1f
        vue.scaleY = vue.scaleX
    }

    /** Fond, bordure et couleur du texte : bascule activée = plein, coupée = presque transparent. */
    private fun habiller(vue: TextView, b: FloatingButton, deplacement: Boolean, etat: Boolean?) {
        val densite = vue.resources.displayMetrics.density
        val hauteur = (FloatingButtons.heightDp(b.size) * densite).roundToInt()
        vue.setTextColor(if (etat == true) TEXTE_SUR_PLEIN else TEXTE)
        vue.background = GradientDrawable().apply {
            cornerRadius = hauteur / 2f
            setColor(when (etat) {
                true -> BORDURE
                false -> FOND_COUPE
                null -> FOND
            })
            setStroke((2 * densite).roundToInt(), if (deplacement) BORDURE_DEPLACEMENT else BORDURE)
        }
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
