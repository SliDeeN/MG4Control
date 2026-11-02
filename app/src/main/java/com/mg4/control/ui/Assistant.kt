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
    private val pile = ArrayList<() -> Unit>()

    var dialog: AlertDialog? = null
        private set

    val peutRetour: Boolean get() = pile.size > 1

    /** Ajoute une étape et l'affiche ; [rendu] la reconstruit aussi au retour. */
    fun etape(rendu: () -> Unit) {
        pile += rendu
        rendu()
    }

    /** Remplace le contenu de la fenêtre (la crée à la première étape). */
    fun afficher(vue: View, largeurDp: Float) {
        conteneur.removeAllViews()
        conteneur.addView(vue)
        val d = dialog
        if (d == null) {
            dialog = AppDialog.afficher(ctx, conteneur, largeurDp).also { it.setOnDismissListener { pile.clear() } }
        } else {
            d.window?.setLayout(AppDialog.dp(ctx, largeurDp), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Revient à l'étape précédente, reconstruite telle qu'elle était. */
    fun retour() {
        if (pile.size < 2) return
        pile.removeAt(pile.lastIndex)
        pile.last().invoke()
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
