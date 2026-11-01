package com.mg4.control.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.control.model.CustomShortcuts
import com.mg4.control.service.OverlayKit
import com.mg4.control.util.CustomShortcutSettings

/**
 * Page « Raccourcis perso » de l'onglet Raccourcis (issue #172, 2026-10-25) : la liste, créer,
 * modifier, supprimer. Chaque changement est signalé à l'onglet ([apresChangement]), qui repeint les
 * libellés des emplacements qui en lancent un.
 */
class CustomShortcutsPage {

    private var fragment: Fragment? = null
    private lateinit var racine: View
    private var apresChangement: () -> Unit = {}

    fun bind(f: Fragment, view: View, apresChangement: () -> Unit) {
        fragment = f
        racine = view
        this.apresChangement = apresChangement
        view.findViewById<View>(R.id.btn_custom_add).setOnClickListener {
            val ctx = fragment?.context ?: return@setOnClickListener
            CustomShortcutDialog.ouvrir(ctx, null) { changer() }
        }
        peindre()
    }

    private fun changer() {
        peindre()
        apresChangement()
    }

    fun peindre() {
        val ctx = fragment?.context ?: return
        val liste = CustomShortcutSettings.all(ctx)
        val boite = racine.findViewById<LinearLayout>(R.id.custom_list)
        boite.removeAllViews()
        racine.findViewById<View>(R.id.custom_empty).visibility = if (liste.isEmpty()) View.VISIBLE else View.GONE
        racine.findViewById<View>(R.id.btn_custom_add).visibility =
            if (liste.size >= CustomShortcuts.MAX) View.GONE else View.VISIBLE
        val gonfleur = LayoutInflater.from(ctx)
        liste.forEach { c ->
            // Même ligne que les boutons flottants : la pastille dit le type, le texte le nom et la cible.
            val ligne = gonfleur.inflate(R.layout.item_floating_button, boite, false)
            ligne.findViewById<TextView>(R.id.floating_row_chip).apply {
                text = CustomShortcutDialog.libelleType(ctx, CustomShortcuts.kindOf(c))
                setTextColor(ctx.getColor(R.color.dash_accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val m = AppDialog.dp(ctx, 10f)
                setPadding(m, AppDialog.dp(ctx, 6f), m, AppDialog.dp(ctx, 6f))
                OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), rayon = AppDialog.dp(ctx, 8f).toFloat())
            }
            ligne.findViewById<TextView>(R.id.floating_row_info).text = "${c.name}\n${CustomShortcutDialog.resume(ctx, c)}"
            ligne.findViewById<View>(R.id.btn_floating_row_edit).setOnClickListener {
                CustomShortcutDialog.ouvrir(ctx, c) { changer() }
            }
            ligne.findViewById<View>(R.id.btn_floating_row_delete).setOnClickListener {
                AppDialog.confirmer(ctx,
                    titre = ctx.getString(R.string.custom_delete_confirm, c.name),
                    ton = AppDialog.Ton.DANGER,
                    icone = R.drawable.ic_dialog_trash,
                    oui = ctx.getString(R.string.custom_delete),
                    non = ctx.getString(R.string.profile_cancel),
                    onOui = {
                        CustomShortcutSettings.delete(ctx, c.id)
                        changer()
                    })
            }
            boite.addView(ligne)
        }
    }
}
