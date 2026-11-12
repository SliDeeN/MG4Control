package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.mg4.control.R
import com.mg4.control.service.OverlayKit

/**
 * Fenêtres de l'application au style « moderne » : la mise en page apporte sa propre carte
 * arrondie, la fenêtre n'a donc ni fond ni décor, et sa largeur est celle qu'on lui donne —
 * celle par défaut d'un dialogue Android est trop étroite pour un écran de voiture.
 *
 * Tailles normales de l'application : ces fenêtres ne sont pas agrandies comme les pop-ups posés
 * par-dessus les autres applications.
 */
internal object AppDialog {

    private const val LARGEUR_CONFIRMATION_DP = 640f
    private const val LARGEUR_CHOIX_DP = 1040f

    /** Tuiles par ligne de la grille de choix. */
    private const val COLONNES = 4
    private const val ECART_DP = 10f

    /** Hauteur d'une tuile de la grille : celle d'item_dialog_choice.xml. */
    private const val TUILE_DP = 104f

    /**
     * Lignes de la grille montrées sans défiler. Au-delà elle s'arrête au milieu de la suivante :
     * une tuile coupée dit mieux qu'une barre qu'il y a une suite.
     */
    private const val LIGNES_VISIBLES = 3

    /** Au-delà, la fenêtre de choix a un champ de recherche (2026-11-05). */
    private const val SEUIL_RECHERCHE = 9

    /** Ce que la couleur d'une confirmation dit de l'action proposée. */
    enum class Ton {
        /** Une action ordinaire, qu'on peut refaire ou défaire. */
        NEUTRE,
        /** À lire avant d'accepter : un comportement qui change, un coût, un risque. */
        ALERTE,
        /** Efface ou éteint : ne se rattrape pas. */
        DANGER,
    }

    /**
     * Fenêtre de SAISIE (2026-10-29, retour d'un utilisateur de la beta) : le clavier de la voiture
     * prend la moitié de l'écran et cache les boutons, et un champ multiligne n'a qu'« Entrée »,
     * qui ne le referme pas. Toucher à côté de la fenêtre REFERME LE CLAVIER, au lieu de fermer la
     * fenêtre en perdant la saisie : elle ne se ferme plus que par ses boutons. « OK » d'un champ
     * d'une ligne referme aussi le clavier.
     *
     * Essayé puis retiré le même jour (sa décision) : remonter la fenêtre et la réduire à la moitié
     * du haut tant que le clavier est ouvert — trop tassé.
     */
    fun saisie(dialog: AlertDialog, racine: View) {
        val window = dialog.window ?: return
        val ctx = racine.context
        dialog.setCanceledOnTouchOutside(false)
        // Une AlertDialog créée SANS champ de saisie (l'assistant de l'éditeur d'automatisation,
        // dont la première étape est une liste) reçoit FLAG_ALT_FOCUSABLE_IM : le clavier ne
        // s'ouvre alors jamais, même en touchant le champ ajouté plus tard (2026-11-05).
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        // Fermé à l'ouverture seulement. ALWAYS_HIDDEN le refermait à CHAQUE retour du focus sur
        // la fenêtre : un clavier qui prend le focus un instant (soupçonné sur SWI68, #224) se
        // refermait aussitôt ouvert.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        // Le focus repose sur la carte quand le clavier est fermé : sans ça, Android le rendrait
        // au premier champ, et le clavier pourrait revenir.
        racine.isFocusableInTouchMode = true
        racine.requestFocus()

        fun fermerClavier() {
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            if (racine.findFocus() is EditText) racine.requestFocus()
        }

        // « OK » d'un champ d'une ligne : clavier refermé.
        fun brancherOk(v: View) {
            if (v is EditText) {
                v.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { fermerClavier(); true } else false
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) brancherOk(v.getChildAt(i))
            }
        }
        brancherOk(racine)

        // Un toucher hors de la fenêtre ne fait plus que refermer le clavier. Une seule garde par
        // fenêtre : l'assistant de l'éditeur réutilise la sienne d'une étape de saisie à l'autre.
        garde(window).apply {
            toujours = true
            surFermeture = { if (racine.findFocus() is EditText) racine.requestFocus() }
        }
    }

    /**
     * Garde d'une fenêtre contre le toucher à côté (2026-11-05) : tant que [toujours] (fenêtre de
     * saisie) ou que le clavier est ouvert par un champ de recherche ([clavier]), un toucher hors
     * de la fenêtre referme le clavier au lieu de fermer la fenêtre — on ne perd ni la saisie ni
     * l'automatisation en cours de création. Clavier fermé, une fenêtre de choix se ferme comme avant.
     */
    private class Garde {
        var toujours = false
        var clavier = false
        var surFermeture: (() -> Unit)? = null
    }

    private val gardes = java.util.WeakHashMap<Window, Garde>()

    private fun garde(window: Window): Garde = gardes.getOrPut(window) {
        val g = Garde()
        val origine = window.callback
        window.callback = object : Window.Callback by origine {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN && (g.toujours || g.clavier)) {
                    val decor = window.decorView
                    if (e.x < 0 || e.y < 0 || e.x > decor.width || e.y > decor.height) {
                        decor.context.getSystemService(InputMethodManager::class.java)
                            ?.hideSoftInputFromWindow(decor.windowToken, 0)
                        g.clavier = false
                        g.surFermeture?.invoke()
                        return true
                    }
                }
                val traite = origine.dispatchTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val x = e.rawX.toInt()
                    val y = e.rawY.toInt()
                    window.decorView.post { montrerClavier(window, x, y) }
                }
                return traite
            }
        }
        g
    }

    /**
     * Champ touché dans une fenêtre de saisie : le clavier est DEMANDÉ, au lieu de compter sur le
     * seul toucher (issue #224, SWI68 : clavier absent au renommage d'une touche, deuxième cas après
     * le nom de variable). Le drapeau qui bloque le clavier est réeffacé au passage, et la ligne
     * MG4_UI dit ce que la fenêtre et le système répondent — de quoi trancher au prochain cas.
     */
    private fun montrerClavier(window: Window, x: Int, y: Int) {
        val champ = window.decorView.findFocus() as? EditText ?: return
        val r = android.graphics.Rect()
        if (!champ.getGlobalVisibleRect(r)) return
        val o = IntArray(2)
        window.decorView.getLocationOnScreen(o)
        // getGlobalVisibleRect est relatif à la fenêtre : on le ramène à l'écran, comme rawX / rawY.
        r.offset(o[0], o[1])
        if (!r.contains(x, y)) return
        val avant = window.attributes.flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val imm = champ.context.getSystemService(InputMethodManager::class.java) ?: return
        val montre = imm.showSoftInput(champ, InputMethodManager.SHOW_IMPLICIT)
        com.mg4.control.debug.AppLogger.i("MG4_UI", "clavier demandé : accepté=$montre, actif=${imm.isActive(champ)}, " +
            "focus fenêtre=${champ.hasWindowFocus()}, drapeaux=0x${Integer.toHexString(avant)}, " +
            "mode=0x${Integer.toHexString(window.attributes.softInputMode)}, toucher=${champ.isInTouchMode}")
    }

    /** Affiche [vue] et rend la fenêtre, à refermer par `dismiss()`. */
    fun afficher(context: Context, vue: View, largeurDp: Float, annulable: Boolean = true): AlertDialog {
        val dialog = AlertDialog.Builder(context)
            .setView(vue)
            .setCancelable(annulable)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // Après show() : avant, la fenêtre n'a pas encore ses dimensions et le réglage est perdu.
        dialog.window?.setLayout(dp(context, largeurDp), WindowManager.LayoutParams.WRAP_CONTENT)
        com.mg4.control.util.ButtonLines.suivre(vue)
        return dialog
    }

    /**
     * Confirmation simple : un pictogramme, un titre, un texte, un ou deux boutons — toutes les
     * mêmes dans l'application (style choisi sur maquette le 2026-10-13).
     *
     * @param non    libellé du refus ; null = un seul bouton, pour une simple information
     * @param onOui  le bouton d'action
     * @param onNon  le refus : son bouton, mais aussi la fenêtre fermée sans répondre (retour,
     *               toucher à côté) — dans le doute on ne suppose jamais l'accord. Sans bouton
     *               de refus, fermer la fenêtre ne déclenche rien.
     */
    fun confirmer(
        context: Context,
        titre: CharSequence?,
        message: CharSequence? = null,
        ton: Ton = Ton.NEUTRE,
        @DrawableRes icone: Int = when (ton) {
            Ton.NEUTRE -> R.drawable.ic_dialog_question
            else       -> R.drawable.ic_dialog_warning
        },
        oui: CharSequence,
        non: CharSequence? = null,
        onOui: () -> Unit = {},
        onNon: () -> Unit = {},
    ): AlertDialog {
        val vue = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)

        // Fond de la pastille, couleur du pictogramme et du bouton, texte posé sur ce bouton.
        val (doux, vif, surVif) = when (ton) {
            Ton.NEUTRE -> Triple(R.color.dash_accent_dim, R.color.dash_accent, R.color.hvac_on_accent)
            Ton.ALERTE -> Triple(R.color.dash_warn_dim, R.color.dash_warn, R.color.hvac_on_heat)
            Ton.DANGER -> Triple(R.color.dash_danger_dim, R.color.dash_danger, R.color.text_active)
        }
        val couleur = context.getColor(vif)
        OverlayKit.fond(vue.findViewById(R.id.confirm_dialog_badge), context.getColor(doux), ovale = true)
        vue.findViewById<ImageView>(R.id.confirm_dialog_icon).apply {
            setImageResource(icone)
            imageTintList = ColorStateList.valueOf(couleur)
        }

        vue.findViewById<TextView>(R.id.confirm_dialog_title).apply {
            text = titre
            visibility = if (titre.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        vue.findViewById<TextView>(R.id.confirm_dialog_message).apply {
            text = message
            // Plusieurs paragraphes se lisent mal centrés : on les aligne au bord.
            if (message != null && message.contains("\n\n")) gravity = Gravity.START
        }
        if (message.isNullOrEmpty()) vue.findViewById<View>(R.id.confirm_dialog_scroll).visibility = View.GONE

        val dialog = afficher(context, vue, LARGEUR_CONFIRMATION_DP)
        vue.findViewById<TextView>(R.id.confirm_dialog_yes).apply {
            text = oui
            setTextColor(context.getColor(surVif))
            OverlayKit.fond(this, couleur, rayon = dp(context, 12f).toFloat())
            setOnClickListener {
                dialog.dismiss()
                onOui()
            }
        }
        vue.findViewById<TextView>(R.id.confirm_dialog_no).apply {
            if (non == null) {
                visibility = View.GONE
            } else {
                text = non
                setOnClickListener {
                    dialog.dismiss()
                    onNon()
                }
                // « dismiss » ne passe pas par ici : seuls le retour et le toucher à côté annulent.
                dialog.setOnCancelListener { onNon() }
            }
        }
        return dialog
    }

    /**
     * Un choix de la grille : son nom, et ce qui le fait reconnaître — l'icône d'une application,
     * ou une pastille ([pastille], avec son fond et la couleur de son texte) pour un profil.
     * Une pastille vide garde sa place sans rien montrer : les noms restent alignés d'une tuile à
     * l'autre.
     */
    class Choix(
        val libelle: CharSequence,
        val icone: Drawable? = null,
        val pastille: CharSequence? = null,
        val fondPastille: Int = 0,
        val textePastille: Int = 0,
        /** Seconde ligne, plus petite et grise (« Devient… » sous « Profil actif ») ; cherchée aussi. */
        val sousLibelle: CharSequence? = null,
        /** Visible seulement dans les résultats d'une recherche (2026-11-12, capteurs déclinés). */
        val rechercheSeule: Boolean = false,
        /** Caché pendant une recherche : ses déclinaisons ([rechercheSeule]) le remplacent. */
        val horsRecherche: Boolean = false,
    )

    /**
     * [c] trouvé par la recherche [cle] (minuscules sans accents) : chaque mot tapé se trouve dans
     * son nom ou sa seconde ligne — « profil change » trouve « Profil actif · Change ».
     */
    private fun trouve(c: Choix, cle: String): Boolean {
        if (c.horsRecherche) return false
        val texte = sansAccents(listOfNotNull(c.libelle, c.sousLibelle).joinToString(" "))
        return cle.split(' ').filter { it.isNotEmpty() }.all { texte.contains(it) }
    }

    /**
     * Choix dans une liste, présentée en grille de tuiles qui défile (style choisi sur maquette
     * le 2026-10-13).
     *
     * @param onChoix  rang du choix touché, dans [choix]
     * @param onAnnule « Annuler », mais aussi la fenêtre fermée sans choisir (retour, toucher à côté)
     * @param choisi   rang du choix en cours, s'il y en a un : sa tuile est marquée et amenée en vue
     */
    /** Une catégorie de la grande fenêtre de choix : son nom, son pictogramme, les rangs de ses choix. */
    class Categorie(val libelle: CharSequence, @DrawableRes val icone: Int, val rangs: List<Int>)

    /**
     * @param categories rangement des choix (grande fenêtre de l'éditeur, maquette B du 2026-11-05)
     * @param recents    nom de la liste pour ses « Récents » (null : pas de récents) ; [cles] donne
     *                   alors un identifiant stable à chaque choix
     */
    fun choisir(
        context: Context,
        titre: CharSequence,
        choix: List<Choix>,
        annuler: CharSequence,
        sousTitre: CharSequence? = null,
        @DrawableRes icone: Int = R.drawable.ic_dialog_list,
        choisi: Int = -1,
        onAnnule: () -> Unit = {},
        categories: List<Categorie>? = null,
        recents: String? = null,
        cles: List<String>? = null,
        onChoix: (Int) -> Unit,
    ) {
        fun retenirChoix(rang: Int) {
            val cle = cles?.getOrNull(rang) ?: return
            if (recents != null) retenirRecent(context, recents, cle)
        }
        // Dans une fenêtre à étapes : la grille remplace l'étape d'avant, Retour y ramène.
        val a = Assistant.courant
        if (a != null) {
            // Grande fenêtre (en part d'écran) pour une liste rangée ou longue ; une courte (Dans /
            // Hors d'un lieu…) garde sa fenêtre, elle se perdrait dans la grande.
            val grand = categories != null || choix.size > SEUIL_RECHERCHE
            a.etape(titre) {
                val surChoix: (Int) -> Unit = { rang -> retenirChoix(rang); a.noter(choix[rang].libelle); a.apres { onChoix(rang) } }
                if (grand) a.afficherGrand(vueChoixGrand(context, a, titre, sousTitre, icone, choix, annuler,
                    avecRecents(context, categories, recents, cles), choisi, surAnnule = { a.fermer(); onAnnule() }, surChoix = surChoix))
                else a.afficher(vueChoix(context, titre, choix, annuler, sousTitre, icone, choisi,
                    surAnnule = { a.fermer(); onAnnule() },
                    surRetour = if (a.peutRetour) ({ a.retour() }) else null,
                    surChoix = surChoix,
                    fenetre = { a.dialog }), LARGEUR_CHOIX_DP)
            }
            return
        }
        var dialog: AlertDialog? = null
        val vue = vueChoix(context, titre, choix, annuler, sousTitre, icone, choisi,
            surAnnule = { dialog?.dismiss(); onAnnule() },
            surRetour = null,
            surChoix = { rang -> retenirChoix(rang); dialog?.dismiss(); onChoix(rang) },
            fenetre = { dialog })
        dialog = afficher(context, vue, LARGEUR_CHOIX_DP).also {
            // « dismiss » ne passe pas par ici : seuls le retour et le toucher à côté annulent.
            it.setOnCancelListener { onAnnule() }
        }
    }

    /** La grille de choix : en-tête, tuiles, puis (Retour) et Annuler. */
    private fun vueChoix(
        context: Context, titre: CharSequence, choix: List<Choix>, annuler: CharSequence,
        sousTitre: CharSequence?, @DrawableRes icone: Int, choisi: Int,
        surAnnule: () -> Unit, surRetour: (() -> Unit)?, surChoix: (Int) -> Unit,
        fenetre: () -> android.app.Dialog? = { null },
    ): View {
        val gonfleur = LayoutInflater.from(context)
        val vue = gonfleur.inflate(R.layout.dialog_choice, null)
        val accent = context.getColor(R.color.dash_accent)

        vue.findViewById<ImageView>(R.id.choice_icon).apply {
            setImageResource(icone)
            imageTintList = ColorStateList.valueOf(accent)
        }
        vue.findViewById<TextView>(R.id.choice_title).text = titre
        vue.findViewById<TextView>(R.id.choice_subtitle).apply {
            text = sousTitre
            visibility = if (sousTitre.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        val lignes = vue.findViewById<LinearLayout>(R.id.choice_rows)
        val defilement = vue.findViewById<ScrollView>(R.id.choice_scroll)
        val ecart = dp(context, ECART_DP)
        if (choix.size > COLONNES * LIGNES_VISIBLES) {
            val ligne = dp(context, TUILE_DP) + ecart
            defilement.layoutParams = defilement.layoutParams.apply {
                height = ligne * LIGNES_VISIBLES + dp(context, TUILE_DP) / 2
            }
        }
        var tuileChoisie: View? = null
        // Recherche (2026-11-05, maquette A) : au-delà de [SEUIL_RECHERCHE] choix, un champ filtre la
        // grille, sans tenir compte des majuscules ni des accents. Les rangs rendus restent ceux de
        // la liste complète.
        fun remplir(filtre: String) {
            val cle = sansAccents(filtre.trim())
            val visibles = choix.indices.filter { if (cle.isEmpty()) !choix[it].rechercheSeule else trouve(choix[it], cle) }
            tuileChoisie = grille(context, lignes, visibles, choix, choisi, COLONNES, surChoix)
        }
        remplir("")
        if (choix.size > SEUIL_RECHERCHE) {
            val parent = defilement.parent as LinearLayout
            parent.addView(champRecherche(context, fenetre) { remplir(it) }, parent.indexOfChild(defilement))
        }

        vue.findViewById<TextView>(R.id.choice_cancel).apply {
            text = annuler
            setOnClickListener { surAnnule() }
        }
        vue.findViewById<TextView>(R.id.choice_back).apply {
            visibility = if (surRetour == null) View.GONE else View.VISIBLE
            setOnClickListener { surRetour?.invoke() }
        }

        // Le choix en cours peut être loin dans la grille : on l'amène en vue une fois posée.
        tuileChoisie?.let { tuile ->
            defilement.post {
                val ligne = tuile.parent as View
                defilement.scrollTo(0, (ligne.top - ecart).coerceAtLeast(0))
            }
        }
        return vue
    }

    /**
     * Remplit [lignes] avec les choix [rangs] (rangs de la liste complète), [colonnes] par ligne ;
     * rend la tuile du choix en cours s'il est affiché. Vide : « Aucun résultat ».
     */
    private fun grille(
        context: Context, lignes: LinearLayout, rangs: List<Int>, choix: List<Choix>, choisi: Int,
        colonnes: Int, surChoix: (Int) -> Unit,
    ): View? {
        lignes.removeAllViews()
        if (rangs.isEmpty()) {
            lignes.addView(TextView(context).apply {
                text = context.getString(R.string.dialog_search_none)
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 18f
                setPadding(0, dp(context, 12f), 0, dp(context, 12f))
            })
            return null
        }
        val gonfleur = LayoutInflater.from(context)
        val ecart = dp(context, ECART_DP)
        var tuileChoisie: View? = null
        rangs.chunked(colonnes).forEachIndexed { rangLigne, ligne ->
            val rangee = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (rangLigne > 0) it.topMargin = ecart }
            }
            // Toujours [colonnes] cellules : une dernière ligne incomplète garde des tuiles de la
            // même largeur que les autres, le reste étant du vide.
            repeat(colonnes) { colonne ->
                val rang = ligne.getOrNull(colonne) ?: -1
                val cellule = gonfleur.inflate(R.layout.item_dialog_choice, rangee, false)
                (cellule.layoutParams as LinearLayout.LayoutParams).marginStart = if (colonne > 0) ecart else 0
                val c = choix.getOrNull(rang)
                if (c == null) {
                    cellule.visibility = View.INVISIBLE
                } else {
                    peindreChoix(context, cellule, c, choisie = rang == choisi)
                    if (rang == choisi) tuileChoisie = cellule
                    cellule.setOnClickListener { surChoix(rang) }
                }
                rangee.addView(cellule)
            }
            lignes.addView(rangee)
        }
        return tuileChoisie
    }

    // ── Grande fenêtre de choix (éditeur d'automatisation, 2026-11-05) ─────────────────────────

    /**
     * La grande fenêtre (maquette B, écran complet) : en haut Retour, le chemin parcouru (chaque
     * étape y ramène), la recherche et Annuler ; dessous, les catégories en colonne et la grille.
     * La recherche couvre toutes les catégories ; toucher une catégorie la vide.
     */
    private fun vueChoixGrand(
        context: Context, a: Assistant, titre: CharSequence, sousTitre: CharSequence?, @DrawableRes icone: Int,
        choix: List<Choix>, annuler: CharSequence, categories: List<Categorie>?, choisi: Int,
        surAnnule: () -> Unit, surChoix: (Int) -> Unit,
    ): View {
        fun px(v: Float) = dp(context, v)
        val accent = context.getColor(R.color.dash_accent)
        fun bouton(texte: CharSequence, onClick: () -> Unit) = TextView(context).apply {
            text = texte
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            maxLines = 1
            setBackgroundResource(R.drawable.bg_dialog_tile)
            setPadding(px(22f), 0, px(22f), 0)
            setOnClickListener { onClick() }
        }
        val racine = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_hvac_modern_card)
            setPadding(px(22f), px(20f), px(22f), px(20f))
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // ── Ligne du haut ──
        val haut = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (a.peutRetour) haut.addView(bouton("‹  " + context.getString(R.string.dialog_back)) { a.retour() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(56f)).also { it.marginEnd = px(14f) })
        haut.addView(android.widget.FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_dialog_icon)
            addView(ImageView(context).apply {
                setImageResource(icone)
                imageTintList = ColorStateList.valueOf(accent)
            }, android.widget.FrameLayout.LayoutParams(px(28f), px(28f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(px(52f), px(52f)))
        // Le chemin : les étapes passées (touchables), puis l'étape en cours.
        val chemin = a.chemin.ifEmpty { listOf(titre) }
        val pistes = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chemin.forEachIndexed { i, etape ->
            if (i > 0) pistes.addView(TextView(context).apply {
                text = "›"
                textSize = 20f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(px(8f), 0, px(8f), 0)
            })
            val ici = i == chemin.lastIndex
            pistes.addView(TextView(context).apply {
                text = etape
                textSize = if (ici) 20f else 17f
                maxLines = 1
                setTypeface(typeface, if (ici) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (ici) context.getColor(R.color.text_primary) else accent)
                setPadding(px(14f), px(8f), px(14f), px(8f))
                OverlayKit.fond(this, context.getColor(if (ici) R.color.dash_accent_dim else R.color.dash_btn),
                    rayon = px(12f).toFloat())
                if (!ici) setOnClickListener { a.retourA(i) }
            })
        }
        val defilChemin = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(pistes)
            post { fullScroll(View.FOCUS_RIGHT) }
        }
        haut.addView(defilChemin, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = px(14f) })

        val lignes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val defilGrille = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(lignes) }
        val colonneCats = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var courante = categories?.indexOfFirst { choisi in it.rangs && it.icone != R.drawable.ic_cat_star }
            ?.takeIf { it >= 0 } ?: 0
        var filtre = ""

        lateinit var champ: EditText
        fun dessiner() {
            val cle = sansAccents(filtre.trim())
            fun trouve(r: Int) = cle.isEmpty() || trouve(choix[r], cle)
            // Hors recherche, les déclinaisons cachées ne se montrent que dans « Récents ».
            fun montres(c: Categorie) = if (c.icone == R.drawable.ic_cat_star) c.rangs else c.rangs.filter { !choix[it].rechercheSeule }
            val rangs = when {
                cle.isNotEmpty() -> choix.indices.filter { trouve(it) }
                categories == null -> choix.indices.filter { !choix[it].rechercheSeule }
                else -> categories.getOrNull(courante)?.let { montres(it) }.orEmpty()
            }
            val tuile = grille(context, lignes, rangs, choix, choisi, COLONNES, surChoix)
            if (cle.isEmpty() && tuile != null) defilGrille.post { defilGrille.scrollTo(0, ((tuile.parent as View).top - px(ECART_DP)).coerceAtLeast(0)) }
            else defilGrille.scrollTo(0, 0)
            colonneCats.removeAllViews()
            categories?.forEachIndexed { i, c ->
                val n = if (cle.isEmpty()) montres(c).size else c.rangs.count { trouve(it) }
                val on = cle.isEmpty() && i == courante
                val couleur = if (on) accent else context.getColor(R.color.text_primary)
                colonneCats.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(14f), 0, px(14f), 0)
                    alpha = if (cle.isNotEmpty() && n == 0) 0.4f else 1f
                    if (on) OverlayKit.fond(this, context.getColor(R.color.dash_accent_dim), rayon = px(14f).toFloat())
                    addView(ImageView(context).apply {
                        setImageResource(c.icone)
                        imageTintList = ColorStateList.valueOf(if (on) accent else context.getColor(R.color.text_secondary))
                    }, LinearLayout.LayoutParams(px(24f), px(24f)))
                    addView(TextView(context).apply {
                        text = c.libelle
                        textSize = 18f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(couleur)
                        setPadding(px(12f), 0, px(8f), 0)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(context).apply {
                        text = n.toString()
                        textSize = 15f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(context.getColor(R.color.text_secondary))
                    })
                    setOnClickListener {
                        courante = i
                        if (filtre.isNotEmpty()) champ.setText("") else dessiner()
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(58f)).also { if (i > 0) it.topMargin = px(6f) })
            }
        }

        champ = champRecherche(context, { a.dialog }) { filtre = it; dessiner() } as EditText
        haut.addView(champ, LinearLayout.LayoutParams(px(420f), px(56f)).also { it.marginStart = px(14f) })
        haut.addView(bouton(annuler) { surAnnule() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(56f)).also { it.marginStart = px(14f) })
        racine.addView(haut)

        if (!sousTitre.isNullOrEmpty()) racine.addView(TextView(context).apply {
            text = sousTitre
            textSize = 15f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(px(4f), px(10f), 0, 0)
        })

        val corps = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        if (categories != null) corps.addView(ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(colonneCats)
        }, LinearLayout.LayoutParams(px(320f), LinearLayout.LayoutParams.MATCH_PARENT).also { it.marginEnd = px(18f) })
        corps.addView(defilGrille, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        racine.addView(corps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = px(16f) })
        dessiner()
        return racine
    }

    // ── Récents (2026-11-05) ──────────────────────────────────────────────────────────────────

    private const val PREFS_RECENTS = "mg4_rule_picker"
    private const val MAX_RECENTS = 8

    private fun lireRecents(context: Context, liste: String): List<String> =
        context.getSharedPreferences(PREFS_RECENTS, Context.MODE_PRIVATE).getString("recent_$liste", null)
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun retenirRecent(context: Context, liste: String, cle: String) {
        val l = (listOf(cle) + lireRecents(context, liste).filter { it != cle }).take(MAX_RECENTS)
        context.getSharedPreferences(PREFS_RECENTS, Context.MODE_PRIVATE).edit()
            .putString("recent_$liste", l.joinToString("\n")).apply()
    }

    /** « Récents » en tête des catégories : les derniers choix de cette liste encore proposés. */
    private fun avecRecents(context: Context, categories: List<Categorie>?, liste: String?, cles: List<String>?): List<Categorie>? {
        if (categories == null || liste == null || cles == null) return categories
        val rangs = lireRecents(context, liste).mapNotNull { k -> cles.indexOf(k).takeIf { it >= 0 } }
        if (rangs.isEmpty()) return categories
        return listOf(Categorie(context.getString(R.string.rule_cat_recent), R.drawable.ic_cat_star, rangs)) + categories
    }

    /** Minuscules sans accents : « Volant » et « vol », « Démarrage » et « demar » se trouvent. */
    private fun sansAccents(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /**
     * Champ de recherche d'une fenêtre de choix. Le clavier de la voiture couvre la moitié basse de
     * l'écran : tant qu'on tape, la fenêtre remonte en haut, et les premiers résultats restent
     * visibles ; « OK » du clavier le referme et la recentre.
     */
    private fun champRecherche(context: Context, fenetre: () -> android.app.Dialog?, surTexte: (String) -> Unit): View {
        val champ = EditText(context).apply {
            hint = context.getString(R.string.dialog_search)
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            textSize = 19f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.bg_dialog_tile)
            setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
            compoundDrawablePadding = dp(context, 10f)
            context.getDrawable(R.drawable.ic_search)?.mutate()?.let { d ->
                d.setTint(context.getColor(R.color.text_secondary))
                d.setBounds(0, 0, dp(context, 22f), dp(context, 22f))
                setCompoundDrawablesRelative(d, null, null, null)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 56f))
                .also { it.topMargin = dp(context, 14f) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = surTexte(s?.toString().orEmpty())
            })
        }
        fun placer(enHaut: Boolean) {
            val w = fenetre()?.window ?: return
            // Clavier ouvert : toucher à côté le referme sans fermer la fenêtre, et la recentre.
            garde(w).apply {
                clavier = enHaut
                if (enHaut) surFermeture = { champ.clearFocus(); placer(false) }
            }
            // Une fenêtre créée sans champ de saisie bloque le clavier : voir [saisie].
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            w.attributes = w.attributes.apply {
                gravity = if (enHaut) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                y = if (enHaut) dp(context, 8f) else 0
            }
        }
        // Au toucher, pas au focus : à l'ouverture, Android peut donner le focus au champ sans que
        // personne ne tape, et la fenêtre remonterait pour rien.
        @Suppress("ClickableViewAccessibility")
        champ.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_UP) placer(true); false }
        champ.setOnEditorActionListener { v, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            placer(false)
            true
        }
        // Le focus ne va pas au champ à l'ouverture : le clavier ne s'ouvre qu'au toucher.
        champ.post { fenetre()?.window?.let { placer(false) } }
        return champ
    }

    private fun peindreChoix(context: Context, cellule: View, c: Choix, choisie: Boolean) {
        if (choisie) {
            // Le fond de la mise en page est partagé par toutes les tuiles : le repeindre les
            // changerait toutes. On le retire, et la tuile reçoit le sien.
            cellule.background = null
            OverlayKit.fond(
                cellule, context.getColor(R.color.dash_accent_dim), context.getColor(R.color.dash_accent),
                dp(context, 2f), dp(context, 12f).toFloat())
        }
        cellule.findViewById<TextView>(R.id.choice_item_label).apply {
            val sous = c.sousLibelle
            if (sous.isNullOrEmpty()) text = c.libelle
            else {
                val debut = c.libelle.length + 1
                text = android.text.SpannableStringBuilder(c.libelle).append("\n").append(sous).apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.85f), debut, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.text_secondary)), debut, length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                maxLines = 3
            }
        }
        cellule.findViewById<ImageView>(R.id.choice_item_icon).apply {
            setImageDrawable(c.icone)
            visibility = if (c.icone == null) View.GONE else View.VISIBLE
        }
        cellule.findViewById<TextView>(R.id.choice_item_pill).apply {
            if (c.pastille.isNullOrEmpty()) {
                visibility = if (c.pastille == null) View.GONE else View.INVISIBLE
            } else {
                text = c.pastille
                setTextColor(c.textePastille)
                OverlayKit.fond(this, c.fondPastille, rayon = dp(context, 11f).toFloat())
            }
        }
    }

    /**
     * Liste à choix unique, toujours ouverte : une ligne par option, celle en cours marquée et
     * amenée en vue. Sert là où deux choix se font dans la même fenêtre.
     *
     * @param liste      où poser les lignes (item_dialog_option.xml)
     * @param defilement ce qui fait défiler [liste]
     * @return de quoi lire le rang choisi au moment d'enregistrer
     */
    fun options(
        liste: LinearLayout,
        defilement: ScrollView,
        libelles: List<CharSequence>,
        choisi: Int,
    ): () -> Int {
        val context = liste.context
        val gonfleur = LayoutInflater.from(context)
        var rangChoisi = choisi
        val lignes = libelles.map { libelle ->
            (gonfleur.inflate(R.layout.item_dialog_option, liste, false) as TextView).also {
                it.text = libelle
                liste.addView(it)
            }
        }
        fun peindre() = lignes.forEachIndexed { rang, ligne ->
            val on = rang == rangChoisi
            OverlayKit.fond(
                ligne,
                context.getColor(if (on) R.color.dash_accent_dim else R.color.dash_btn),
                context.getColor(if (on) R.color.dash_accent else R.color.dash_border),
                dp(context, 1f), dp(context, 12f).toFloat())
            ligne.setTextColor(context.getColor(if (on) R.color.text_primary else R.color.text_secondary))
        }
        lignes.forEachIndexed { rang, ligne ->
            ligne.setOnClickListener {
                rangChoisi = rang
                peindre()
            }
        }
        peindre()
        // Une ligne au-dessus de l'option en cours reste visible : on voit qu'on n'est pas en tête.
        lignes.getOrNull(choisi)?.let { ligne ->
            defilement.post { defilement.scrollTo(0, (ligne.top - ligne.height).coerceAtLeast(0)) }
        }
        return { rangChoisi }
    }

    /**
     * Choix à segments : une rangée de cases dont une seule est allumée. Les cases
     * (item_dialog_segment.xml) sont ajoutées à [rangee].
     *
     * @param onChoix rang de la case touchée, chaque fois qu'elle change
     */
    fun segments(rangee: LinearLayout, libelles: List<CharSequence>, choisi: Int, onChoix: (Int) -> Unit) {
        val context = rangee.context
        val gonfleur = LayoutInflater.from(context)
        var rangChoisi = choisi
        val cases = libelles.map { libelle ->
            (gonfleur.inflate(R.layout.item_dialog_segment, rangee, false) as TextView).also {
                it.text = libelle
                rangee.addView(it)
            }
        }
        fun peindre() = cases.forEachIndexed { rang, case ->
            val on = rang == rangChoisi
            OverlayKit.fond(
                case, if (on) context.getColor(R.color.hvac_segment_on) else Color.TRANSPARENT,
                rayon = dp(context, 9f).toFloat())
            case.setTextColor(context.getColor(if (on) R.color.text_primary else R.color.text_secondary))
        }
        cases.forEachIndexed { rang, case ->
            case.setOnClickListener {
                if (rang == rangChoisi) return@setOnClickListener
                rangChoisi = rang
                peindre()
                onChoix(rang)
            }
        }
        peindre()
    }

    /** Délai entre deux pas d'un bouton maintenu. */
    private const val REPETITION_MS = 110L

    /**
     * Bouton − ou + : un appui fait un [pas], un appui maintenu le répète tant que le doigt reste
     * dessus — de quoi parcourir une heure ou un prix sans marteler l'écran.
     */
    fun repeter(bouton: View, pas: () -> Unit) {
        bouton.setOnClickListener { pas() }
        bouton.setOnLongClickListener {
            val boucle = object : Runnable {
                override fun run() {
                    if (!bouton.isPressed || !bouton.isAttachedToWindow) return
                    pas()
                    bouton.postDelayed(this, REPETITION_MS)
                }
            }
            bouton.post(boucle)
            // Consommé : sans ça, relâcher compterait pour un appui de plus.
            true
        }
    }

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
