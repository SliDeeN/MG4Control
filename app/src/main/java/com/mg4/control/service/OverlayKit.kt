package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DriveMode
import com.mg4.control.model.HvacPopup
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.ThemeHelper
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ce que partagent les pop-ups au style « moderne » (pop-up HVAC, sélecteur de profils, bandeaux) :
 * leur agrandissement, la peinture du fond d'une commande, et la place qu'ils prennent à l'écran.
 */
internal object OverlayKit {

    private const val TAG = "MG4_OVERLAY"

    /**
     * Contextes d'un pop-up : [localized] pour ses textes — le service n'a pas la langue choisie
     * dans l'application —, [themed] pour gonfler sa mise en page, au thème clair ou sombre de
     * l'application et à une densité corrigée de [echelle].
     */
    class Contextes(val localized: Context, val themed: Context, val echelle: Float, val mesures: DisplayMetrics)

    /**
     * Les mises en page des pop-ups sont écrites pour une carte de base ; on les gonfle avec une
     * densité corrigée pour qu'elles prennent la même part de l'écran partout — dp et sp suivent
     * d'un bloc, pictos et texte restent nets ([HvacPopup.echelle]).
     */
    fun contextes(
        context: Context, largeurDp: Float, hauteurDp: Float,
        partLargeur: Float = HvacPopup.PART_LARGEUR, partHauteur: Float = HvacPopup.PART_HAUTEUR,
    ): Contextes {
        val localized = LocaleHelper.applyLocale(context)
        val mesures = localized.resources.displayMetrics
        val echelle = HvacPopup.echelle(mesures.widthPixels, mesures.heightPixels, mesures.density, largeurDp, hauteurDp,
            partLargeur, partHauteur)
        val auTheme = ThemeHelper.withAppTheme(localized)
        val agrandi = auTheme.createConfigurationContext(
            Configuration(auTheme.resources.configuration).apply {
                densityDpi = (mesures.densityDpi * echelle).roundToInt()
            })
        return Contextes(localized, ContextThemeWrapper(agrandi, R.style.Theme_MG4Control), echelle, mesures)
    }

    // ── Bandeaux : une question posée en bas de l'écran, sans le cacher ───────
    // Ils reprennent la largeur du pop-up HVAC et du sélecteur de profils, et leur ÉCHELLE : celle
    // d'une carte de 1108 × 392 dp, pour que leurs textes et leurs boutons aient la même taille
    // que les leurs. Leur propre hauteur (80 dp) n'entre pas dans le calcul.
    private const val BANDEAU_LARGEUR_DP = 1108f
    private const val CARTE_DE_REFERENCE_DP = 392f

    /** Espace laissé sous un bandeau, en dp de base. */
    private const val BANDEAU_MARGE_BAS_DP = 14f

    fun contextesBandeau(context: Context): Contextes =
        contextes(context, BANDEAU_LARGEUR_DP, CARTE_DE_REFERENCE_DP)

    /**
     * Fenêtre d'un bandeau : en bas de l'écran, à sa seule taille. Ce qui est touché ailleurs va
     * à l'application du dessous — le bandeau n'assombrit ni ne bloque rien.
     */
    fun fenetreBandeau(themed: Context): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(themed, BANDEAU_MARGE_BAS_DP)
    }

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * Peint le fond d'une vue SANS fond dans sa mise en page : le même objet est repeint à chaque
     * état, plutôt qu'un nouveau à chaque rafraîchissement.
     */
    fun fond(
        vue: View, remplissage: Int,
        trait: Int = Color.TRANSPARENT, epaisseur: Int = 0,
        rayon: Float = 0f, ovale: Boolean = false,
    ) {
        val forme = vue.background as? GradientDrawable ?: GradientDrawable().also { vue.background = it }
        forme.shape = if (ovale) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!ovale) forme.cornerRadius = rayon
        forme.setColor(remplissage)
        forme.setStroke(epaisseur, trait)
    }

    /**
     * Fond et texte de la pastille d'un mode de conduite : une teinte par mode, la même partout
     * où un profil est montré. [themed] : le contexte THÉMÉ du pop-up.
     */
    fun pastilleMode(themed: Context, mode: DriveMode): Pair<Int, Int> = when (mode) {
        DriveMode.ECO    -> themed.getColor(R.color.dash_eco)    to themed.getColor(R.color.hvac_on_eco)
        DriveMode.NORMAL -> themed.getColor(R.color.dash_accent) to themed.getColor(R.color.hvac_on_accent)
        DriveMode.SPORT  -> themed.getColor(R.color.dash_danger) to themed.getColor(R.color.text_active)
        DriveMode.SNOW   -> themed.getColor(R.color.mode_snow)   to themed.getColor(R.color.mode_on_snow)
        DriveMode.CUSTOM -> themed.getColor(R.color.dash_warn)   to themed.getColor(R.color.hvac_on_heat)
    }

    /**
     * Filet : la place réellement accordée à la fenêtre — sous la barre d'état, à côté du rail de
     * navigation — n'est connue qu'une fois celle-ci posée. Si la carte (premier enfant de
     * [racine]) n'y tient pas, on la réduit d'un bloc ([HvacPopup.reduction]) plutôt que de la
     * laisser coupée. Le journal donne la place trouvée : c'est elle qu'il faut pour régler la
     * part d'écran d'un pop-up.
     *
     * À appeler AVANT la pose : la taille voulue se mesure sans contrainte, car une fois dans une
     * fenêtre trop petite la carte serait déjà rognée et sa taille ne dirait plus rien.
     *
     * @param marge espace à garder autour de la carte quand il faut la réduire, en pixels
     * @param nom   de quel pop-up parle le journal
     * @param carte la vue à réduire : par défaut le premier enfant de [racine]
     */
    fun tenirDansLaFenetre(racine: View, marge: Int, nom: String,
                           carte: View? = (racine as? ViewGroup)?.getChildAt(0)) {
        if (carte == null) return
        val libre = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        carte.measure(libre, libre)
        val largeur = carte.measuredWidth
        val hauteur = carte.measuredHeight
        var vue = false
        racine.addOnLayoutChangeListener { _, gauche, haut, droite, bas, _, _, _, _ ->
            if (vue || droite <= gauche || bas <= haut) return@addOnLayoutChangeListener
            vue = true
            val reduction = HvacPopup.reduction(droite - gauche, bas - haut, largeur, hauteur, marge)
            AppLogger.i(TAG, "$nom — fenêtre ${droite - gauche}×${bas - haut} px, carte $largeur×$hauteur px" +
                if (reduction < 1f) " → réduite ×${String.format(Locale.US, "%.2f", reduction)}" else "")
            if (reduction < 1f) {
                // Après la passe de mise en page en cours : en redemander une de l'intérieur est
                // refusé par Android. Taille figée à celle voulue, sinon la fenêtre rogne la carte
                // avant qu'on la réduise.
                racine.post {
                    carte.layoutParams = carte.layoutParams.apply { width = largeur; height = hauteur }
                    carte.scaleX = reduction
                    carte.scaleY = reduction
                }
            }
        }
    }
}
