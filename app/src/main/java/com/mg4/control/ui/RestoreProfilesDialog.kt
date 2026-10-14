package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.model.DrivingProfile
import com.mg4.control.service.OverlayKit

/**
 * « Restaurer les profils ? » — proposée à l'ouverture de l'application quand elle n'a aucun
 * profil mais qu'une sauvegarde existe sur la voiture (après une réinstallation).
 *
 * La fenêtre montre les profils retrouvés : on ne répond qu'une fois à cette question, autant
 * savoir si c'est la bonne sauvegarde. On n'en sort que par l'un des deux boutons.
 */
internal object RestoreProfilesDialog {

    private const val LARGEUR_DP = 760f

    /** Marges intérieures de la carte (dialog_restore_profiles.xml), des deux côtés. */
    private const val MARGES_DP = 40f
    private const val ECART_DP = 10f

    /** Au-delà, la rangée de profils défile. */
    private const val VISIBLES = 4
    private const val CARTE_DEFILANTE_DP = 170f

    fun show(context: Context, profils: List<DrivingProfile>, onIgnorer: () -> Unit, onRestaurer: () -> Unit) {
        val gonfleur = LayoutInflater.from(context)
        val vue = gonfleur.inflate(R.layout.dialog_restore_profiles, null)

        vue.findViewById<TextView>(R.id.restore_message).text =
            context.getString(R.string.profile_restore_msg, profils.size)
        vue.findViewById<ImageView>(R.id.restore_icon).imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.dash_accent))
        vue.findViewById<ImageView>(R.id.restore_confirm_icon).imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.hvac_on_accent))

        // Jusqu'à quatre profils se partagent la largeur ; au-delà, des cartes plus étroites
        // dont la cinquième dépasse — c'est ce qui montre qu'on peut faire défiler.
        val rangee = vue.findViewById<LinearLayout>(R.id.restore_profiles)
        val ecart = AppDialog.dp(context, ECART_DP)
        val largeur = if (profils.size > VISIBLES) AppDialog.dp(context, CARTE_DEFILANTE_DP) else {
            val place = AppDialog.dp(context, LARGEUR_DP - MARGES_DP)
            (place - ecart * (profils.size - 1).coerceAtLeast(0)) / profils.size.coerceAtLeast(1)
        }
        profils.forEachIndexed { rang, profil ->
            val carte = gonfleur.inflate(R.layout.item_dialog_profile, rangee, false)
            carte.findViewById<TextView>(R.id.dialog_profile_name).text = profil.name
            val mode = carte.findViewById<TextView>(R.id.dialog_profile_mode)
            // Rien sur un réglage que le profil n'écrira pas : sans la catégorie Conduite, pas de mode.
            if (profil.appliesDrive) {
                val (fond, texte) = OverlayKit.pastilleMode(context, profil.driveMode)
                mode.text = profil.driveMode.label
                mode.setTextColor(texte)
                OverlayKit.fond(mode, fond, rayon = AppDialog.dp(context, 11f).toFloat())
            } else {
                mode.visibility = View.INVISIBLE
            }
            carte.layoutParams = LinearLayout.LayoutParams(largeur, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { if (rang > 0) it.marginStart = ecart }
            rangee.addView(carte)
        }

        val dialog = AppDialog.afficher(context, vue, LARGEUR_DP, annulable = false)
        vue.findViewById<View>(R.id.restore_btn_ignore).setOnClickListener {
            dialog.dismiss()
            onIgnorer()
        }
        vue.findViewById<View>(R.id.restore_btn_confirm).setOnClickListener {
            dialog.dismiss()
            onRestaurer()
        }
    }
}
