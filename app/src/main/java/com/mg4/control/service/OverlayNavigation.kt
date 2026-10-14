package com.mg4.control.service

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger

/**
 * Focus au joystick droit du volant sur les cellules d'un popup affiché
 * ([ProfilePickerOverlay], [ProfileConfirmOverlay]).
 *
 * Le choix de la cible est délégué à [JoystickFocus] (testé) ; cette classe ne fait que lire
 * les positions à l'écran, dessiner l'anneau et agir. Les positions sont relues à CHAQUE appui :
 * au premier, la mise en page vient à peine d'avoir lieu.
 *
 * Vit et meurt avec la vue du popup. Thread principal seulement.
 *
 * @param context contexte THÉMÉ du popup : l'anneau prend la couleur d'accent de son thème.
 *
 * @param ligneCurseur cellule d'un curseur : gauche/droite y appellent [reglerCurseur] (-1 / +1)
 *                     au lieu de déplacer le focus, et la validation n'y fait rien.
 * @param defilement   conteneur à faire défiler quand la cellule surlignée en dépasse.
 * @param defilementHorizontal idem pour une rangée qui défile de gauche à droite.
 * @param couleurAnneau couleur de l'anneau ; par défaut l'accent du thème — invisible sur une
 *                      cellule elle-même remplie d'accent, d'où ce paramètre.
 * @param surDeplacement appelé à chaque déplacement du focus (typiquement : relancer le délai).
 */
internal class OverlayNavigation(
    context: Context,
    private val grille: List<List<View>>,
    depart: JoystickFocus.Position,
    private val surDeplacement: () -> Unit,
    private val ligneCurseur: View? = null,
    private val defilement: ScrollView? = null,
    private val reglerCurseur: ((Int) -> Unit)? = null,
    private val defilementHorizontal: HorizontalScrollView? = null,
    couleurAnneau: Int? = null,
) {
    private val couleur = couleurAnneau ?: context.getColor(R.color.dash_accent)
    private val epaisseur = dp(context, 4f).toInt()
    private val arrondi = dp(context, 12f)

    /** Surligner sans service d'accessibilité promettrait une navigation qui ne viendra pas. */
    private val actif = KeyCaptureService.isEnabled(context)

    private var position = depart
    private var surlignee: View? = null

    /** Pose l'anneau sur la cellule de départ, si le joystick peut effectivement naviguer. */
    fun afficher() {
        if (actif) surligner(cellule())
    }

    fun recevoir(commande: JoystickFocus.Commande) {
        val cellule = cellule()
        val lateral = commande == JoystickFocus.Commande.GAUCHE || commande == JoystickFocus.Commande.DROITE
        when {
            // Clic réel : même chemin que le doigt, donc mêmes garde-fous (verrou de conduite,
            // confirmation d'extinction…). Le curseur n'a rien à valider.
            commande == JoystickFocus.Commande.VALIDER -> {
                AppLogger.i(TAG, "Joystick — validation de la cellule ${position.ligne}/${position.colonne}")
                if (cellule !== ligneCurseur) cellule.performClick()
            }
            // Sur le curseur, gauche/droite le règlent au lieu de déplacer le focus
            // (la ligne n'a de toute façon qu'une cellule).
            cellule === ligneCurseur && lateral -> reglerCurseur?.invoke(
                if (commande == JoystickFocus.Commande.GAUCHE) -1 else 1
            )
            else -> {
                position = JoystickFocus.deplacer(centres(), position, commande)
                surligner(cellule())
                surDeplacement()
            }
        }
    }

    private fun cellule(): View {
        val ligne = grille[position.ligne.coerceIn(0, grille.lastIndex)]
        return ligne[position.colonne.coerceIn(0, ligne.lastIndex)]
    }

    private fun centres(): List<List<Int>> {
        val xy = IntArray(2)
        return grille.map { ligne ->
            ligne.map { v -> v.getLocationOnScreen(xy); xy[0] + v.width / 2 }
        }
    }

    private fun surligner(v: View) {
        surlignee?.foreground = null
        // Les MaterialButton dessinent leur fond en retrait vertical : sans le même retrait,
        // l'anneau flotterait au-dessus et au-dessous du bouton.
        val (haut, bas) = if (v is MaterialButton) v.insetTop to v.insetBottom else 0 to 0
        v.foreground = InsetDrawable(GradientDrawable().apply {
            cornerRadius = arrondi
            setColor(Color.TRANSPARENT)
            setStroke(epaisseur, couleur)
        }, 0, haut, 0, bas)
        surlignee = v
        amenerDansLaVue(v)
    }

    /** Fait défiler le conteneur si la cellule surlignée en dépasse. */
    private fun amenerDansLaVue(v: View) {
        amenerDansLaRangee(v)
        val sv = defilement ?: return
        if (sv.height == 0 || !estDans(v, sv)) return
        val r = Rect()
        v.getDrawingRect(r)
        sv.offsetDescendantRectToMyCoords(v, r)
        when {
            r.top < sv.scrollY                -> sv.smoothScrollTo(0, r.top)
            r.bottom > sv.scrollY + sv.height -> sv.smoothScrollTo(0, r.bottom - sv.height)
        }
    }

    /**
     * Rangée horizontale : la cellule surlignée revient en entier dans la vue, avec un peu de sa
     * voisine — sinon rien ne dirait qu'il en reste une au-delà.
     */
    private fun amenerDansLaRangee(v: View) {
        val rangee = defilementHorizontal ?: return
        if (rangee.width == 0 || !estDans(v, rangee)) return
        val r = Rect()
        v.getDrawingRect(r)
        rangee.offsetDescendantRectToMyCoords(v, r)
        val voisine = v.width / 4
        when {
            r.left < rangee.scrollX                 -> rangee.smoothScrollTo((r.left - voisine).coerceAtLeast(0), 0)
            r.right > rangee.scrollX + rangee.width -> rangee.smoothScrollTo(r.right - rangee.width + voisine, 0)
        }
    }

    private fun estDans(v: View, parent: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private companion object {
        const val TAG = "MG4_OVERLAY"

        fun dp(context: Context, value: Float) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
    }
}
