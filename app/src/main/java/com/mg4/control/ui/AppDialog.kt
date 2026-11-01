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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
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

        // Un toucher hors de la fenêtre ne fait plus que refermer le clavier.
        val origine = window.callback
        window.callback = object : Window.Callback by origine {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val decor = window.decorView
                    if (e.x < 0 || e.y < 0 || e.x > decor.width || e.y > decor.height) {
                        fermerClavier()
                        return true
                    }
                }
                return origine.dispatchTouchEvent(e)
            }
        }
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
    )

    /**
     * Choix dans une liste, présentée en grille de tuiles qui défile (style choisi sur maquette
     * le 2026-10-13).
     *
     * @param onChoix  rang du choix touché, dans [choix]
     * @param onAnnule « Annuler », mais aussi la fenêtre fermée sans choisir (retour, toucher à côté)
     * @param choisi   rang du choix en cours, s'il y en a un : sa tuile est marquée et amenée en vue
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
        onChoix: (Int) -> Unit,
    ): AlertDialog {
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

        val dialog = afficher(context, vue, LARGEUR_CHOIX_DP)
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
        choix.chunked(COLONNES).forEachIndexed { rangLigne, ligne ->
            val rangee = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (rangLigne > 0) it.topMargin = ecart }
            }
            // Toujours COLONNES cellules : une dernière ligne incomplète garde des tuiles de la
            // même largeur que les autres, le reste étant du vide.
            repeat(COLONNES) { colonne ->
                val rang = rangLigne * COLONNES + colonne
                val cellule = gonfleur.inflate(R.layout.item_dialog_choice, rangee, false)
                (cellule.layoutParams as LinearLayout.LayoutParams).marginStart = if (colonne > 0) ecart else 0
                val c = ligne.getOrNull(colonne)
                if (c == null) {
                    cellule.visibility = View.INVISIBLE
                } else {
                    peindreChoix(context, cellule, c, choisie = rang == choisi)
                    if (rang == choisi) tuileChoisie = cellule
                    cellule.setOnClickListener {
                        dialog.dismiss()
                        onChoix(rang)
                    }
                }
                rangee.addView(cellule)
            }
            lignes.addView(rangee)
        }

        vue.findViewById<TextView>(R.id.choice_cancel).apply {
            text = annuler
            setOnClickListener {
                dialog.dismiss()
                onAnnule()
            }
        }
        // « dismiss » ne passe pas par ici : seuls le retour et le toucher à côté annulent.
        dialog.setOnCancelListener { onAnnule() }

        // Le choix en cours peut être loin dans la grille : on l'amène en vue une fois posée.
        tuileChoisie?.let { tuile ->
            defilement.post {
                val ligne = tuile.parent as View
                defilement.scrollTo(0, (ligne.top - ecart).coerceAtLeast(0))
            }
        }
        return dialog
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
        cellule.findViewById<TextView>(R.id.choice_item_label).text = c.libelle
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
