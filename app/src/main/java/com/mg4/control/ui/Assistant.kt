package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Une fenêtre à étapes (2026-11-02, éditeur de Mes automatisations, option 1 de la maquette) : les
 * choix qui s'enchaînent — déclencheur › valeur › comparaison › seuil — se succèdent DANS LA MÊME
 * fenêtre, et « Retour » réaffiche l'étape précédente, choix d'avant en surbrillance. Il n'apparaît
 * qu'à partir de la deuxième étape ; « Annuler » reste pour tout abandonner.
 *
 * Les grilles ([AppDialog.choisir]) et les fenêtres de valeur ([RuleValueDialogs]) se posent dans
 * l'assistant [courant] s'il y en a un, sinon dans une fenêtre à elles comme avant. L'éditeur ouvre
 * un assistant à chaque toucher (ajouter, modifier, ⋮) ; une étape qui ne mène à aucune autre referme
 * la fenêtre ([apres]).
 */
internal class Assistant(private val ctx: Context) {

    companion object {
        /** Part de l'écran occupée par la grande fenêtre (maquette du 2026-11-05). */
        private const val PART_LARGEUR = 0.85f
        private const val PART_HAUTEUR = 0.92f

        /** L'enchaînement dans lequel la prochaine fenêtre se pose ; `null` = fenêtre à part. */
        var courant: Assistant? = null
            private set

        fun <T> dans(a: Assistant?, f: () -> T): T {
            val avant = courant
            courant = a
            try {
                return f()
            } finally {
                courant = avant
            }
        }
    }

    private val conteneur = FrameLayout(ctx)

    /**
     * Une étape : son titre, ce qui la redessine, et le choix qu'on y a fait (2026-11-05). Le chemin
     * de la grande fenêtre ([AppDialog.choisir]) montre les choix des étapes passées, puis le titre
     * de l'étape en cours ; toucher une étape y ramène ([retourA]).
     */
    private class Etape(val titre: CharSequence, val rendu: () -> Unit) {
        var choix: CharSequence? = null
    }

    private val pile = ArrayList<Etape>()

    var dialog: AlertDialog? = null
        private set

    val peutRetour: Boolean get() = pile.size > 1

    /** Le chemin parcouru : les choix faits aux étapes passées, puis le titre de l'étape en cours. */
    val chemin: List<CharSequence>
        get() = pile.mapIndexed { i, e -> if (i == pile.lastIndex) e.titre else e.choix ?: e.titre }

    /** Ajoute une étape et l'affiche ; [rendu] la reconstruit aussi au retour. */
    fun etape(titre: CharSequence, rendu: () -> Unit) {
        pile += Etape(titre, rendu)
        rendu()
    }

    /** Le choix fait à l'étape en cours, pour le chemin. */
    fun noter(choix: CharSequence) {
        pile.lastOrNull()?.choix = choix
    }

    /** Une fenêtre de valeur ou une petite grille : [largeurDp] de large, la hauteur de son contenu. */
    fun afficher(vue: View, largeurDp: Float) =
        poser(vue, AppDialog.dp(ctx, largeurDp), WindowManager.LayoutParams.WRAP_CONTENT)

    /**
     * La grande fenêtre des sélecteurs de l'éditeur (2026-11-05) : une part de l'écran plutôt que des
     * dp, la densité des firmwares A9 n'étant pas connue — elle ne déborde ainsi sur aucun.
     */
    fun afficherGrand(vue: View) {
        val m = ctx.resources.displayMetrics
        poser(vue, (m.widthPixels * PART_LARGEUR).toInt(), (m.heightPixels * PART_HAUTEUR).toInt())
    }

    private fun poser(vue: View, largeur: Int, hauteur: Int) {
        conteneur.removeAllViews()
        conteneur.addView(vue)
        val d = dialog ?: AppDialog.afficher(ctx, conteneur, 600f).also {
            it.setOnDismissListener { pile.clear() }
            dialog = it
        }
        d.window?.let { w ->
            w.setLayout(largeur, hauteur)
            // Une recherche a pu remonter la fenêtre en haut : chaque étape repart centrée.
            w.attributes = w.attributes.apply { gravity = android.view.Gravity.CENTER; y = 0 }
        }
    }

    /** Revient à l'étape précédente, reconstruite telle qu'elle était. */
    fun retour() = retourA(pile.size - 2)

    /** Revient à l'étape [i] du chemin (0 = la première). */
    fun retourA(i: Int) {
        if (i < 0 || i >= pile.lastIndex) return
        while (pile.size > i + 1) pile.removeAt(pile.lastIndex)
        pile.last().choix = null
        pile.last().rendu()
    }

    fun fermer() {
        dialog?.dismiss()
    }

    /** Après un choix : si [rappel] n'a ouvert aucune étape, l'enchaînement est fini. */
    fun apres(rappel: () -> Unit) {
        val avant = pile.size
        dans(this, rappel)
        if (pile.size == avant) fermer()
    }

    /** OK d'une fenêtre de valeur : refusée, elle reste ; acceptée sans suite, tout se ferme. */
    fun valider(ok: () -> Boolean) {
        val avant = pile.size
        var accepte = false
        dans(this) { accepte = ok() }
        if (accepte && pile.size == avant) fermer()
    }
}
