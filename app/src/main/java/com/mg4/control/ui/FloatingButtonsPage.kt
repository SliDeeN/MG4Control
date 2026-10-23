package com.mg4.control.ui

import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import com.mg4.control.service.FloatingButtonsOverlay
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FloatingButtonSettings

/**
 * Page « Boutons flottants » de l'onglet Raccourcis (2026-10-23, maquette v2) : la liste, l'ajout
 * (le raccourci d'abord, le titre suit), l'édition d'une bulle — aperçu, titre (par défaut le nom
 * du raccourci, ↺ pour y revenir), raccourci, taille de la bulle (curseur à trois repères
 * aimantés) et du texte — et les options. Chaque changement est enregistré ; les bulles à l'écran
 * suivent après un court délai, pour ne pas les reposer à chaque lettre tapée.
 */
class FloatingButtonsPage {

    private var fragment: Fragment? = null
    private lateinit var racine: View
    private lateinit var actions: List<Pair<String, ShortcutAction>>
    private var choisi: String? = null
    private var majTitre = false

    private val rafraichir = Runnable { fragment?.context?.let { FloatingButtonsOverlay.refresh(it) } }

    /** [actions] : celles des raccourcis du volant, sans celles qui demandent un réglage à part. */
    fun bind(f: Fragment, view: View, actions: List<Pair<String, ShortcutAction>>) {
        fragment = f
        racine = view
        this.actions = actions
        val ctx = f.requireContext()

        // Le nom de chaque raccourci, dans la langue de l'appli : le service ne connaît pas les libellés.
        val listeLue = FloatingButtonSettings.buttons(ctx)
        val relibelles = listeLue.map { b -> libelle(b.action)?.let { b.copy(actionLabel = it) } ?: b }
        if (relibelles != listeLue) {
            FloatingButtonSettings.save(ctx, relibelles)
            FloatingButtonsOverlay.refresh(ctx)
        }

        view.findViewById<Switch>(R.id.switch_floating).apply {
            isChecked = FloatingButtonSettings.isEnabled(ctx)
            setOnCheckedChangeListener { _, on ->
                FloatingButtonSettings.setEnabled(ctx, on)
                FloatingButtonsOverlay.refresh(ctx)
            }
        }
        view.findViewById<Switch>(R.id.switch_floating_reverse).apply {
            isChecked = FloatingButtonSettings.hideInReverse(ctx)
            setOnCheckedChangeListener { _, on ->
                FloatingButtonSettings.setHideInReverse(ctx, on)
                FloatingButtonsOverlay.refresh(ctx)
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_reset).setOnClickListener {
            FloatingButtonSettings.resetPositions(ctx)
            FloatingButtonsOverlay.refresh(ctx)
            Toast.makeText(ctx, R.string.floating_reset_done, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_add).setOnClickListener { ajouter() }
        brancherEdition()
        peindreListe()
    }

    private fun libelle(action: String): String? = actions.firstOrNull { it.second.name == action }?.first

    private fun bouton(id: String?): FloatingButton? =
        id?.let { i -> fragment?.context?.let { FloatingButtonSettings.buttons(it).firstOrNull { b -> b.id == i } } }

    /** Le raccourci d'abord : le titre en découle, rien à taper. */
    private fun ajouter() {
        val f = fragment ?: return
        val ctx = f.requireContext()
        if (FloatingButtonSettings.buttons(ctx).size >= FloatingButtons.MAX) {
            Toast.makeText(ctx, R.string.floating_full, Toast.LENGTH_SHORT).show()
            return
        }
        choisirAction(null) { action, nom ->
            val b = FloatingButtonSettings.add(ctx, action.name) ?: return@choisirAction
            FloatingButtonSettings.update(ctx, b.copy(actionLabel = nom))
            choisi = b.id
            FloatingButtonsOverlay.refresh(ctx)
            peindreListe()
            ouvrirEdition()
        }
    }

    private fun choisirAction(actuelle: String?, surChoix: (ShortcutAction, String) -> Unit) {
        val ctx = fragment?.context ?: return
        AppDialog.choisir(ctx, ctx.getString(R.string.floating_choose_action),
            actions.map { AppDialog.Choix(it.first) },
            annuler = ctx.getString(android.R.string.cancel),
            choisi = actions.indexOfFirst { it.second.name == actuelle }) { rang ->
            val (nom, action) = actions[rang]
            surChoix(action, nom)
        }
    }

    // ── Liste ────────────────────────────────────────────────────────────────

    private fun peindreListe() {
        val f = fragment ?: return
        val ctx = f.requireContext()
        val liste = FloatingButtonSettings.buttons(ctx)
        val boite = racine.findViewById<LinearLayout>(R.id.floating_list)
        boite.removeAllViews()
        racine.findViewById<View>(R.id.floating_empty).visibility = if (liste.isEmpty()) View.VISIBLE else View.GONE
        racine.findViewById<View>(R.id.btn_floating_add).visibility =
            if (liste.size >= FloatingButtons.MAX) View.GONE else View.VISIBLE
        val gonfleur = LayoutInflater.from(ctx)
        liste.forEach { b ->
            val ligne = gonfleur.inflate(R.layout.item_floating_button, boite, false)
            val nom = b.actionLabel.ifEmpty { ctx.getString(R.string.floating_unknown_action) }
            // Aperçu réduit : la taille de la liste, la vraie bulle étant dans l'édition.
            FloatingButtonsOverlay.peindre(ligne.findViewById(R.id.floating_row_chip),
                b.copy(size = 0, textSp = 14), FloatingButtons.title(b, nom), deplacement = false)
            ligne.findViewById<TextView>(R.id.floating_row_info).text =
                ctx.getString(R.string.floating_summary, nom, nomTaille(b.size), b.textSp)
            ligne.findViewById<View>(R.id.btn_floating_row_edit).setOnClickListener {
                choisi = b.id
                ouvrirEdition()
            }
            ligne.findViewById<View>(R.id.btn_floating_row_delete).setOnClickListener {
                FloatingButtonSettings.remove(ctx, b.id)
                if (choisi == b.id) fermerEdition()
                FloatingButtonsOverlay.refresh(ctx)
                peindreListe()
            }
            if (b.id == choisi) ligne.background = ctx.getDrawable(R.drawable.bg_card_rounded)
            boite.addView(ligne)
        }
    }

    private fun nomTaille(size: Int): String {
        val ctx = fragment?.context ?: return ""
        return when (FloatingButtons.presetIndex(size)) {
            0 -> ctx.getString(R.string.floating_size_small)
            1 -> ctx.getString(R.string.floating_size_medium)
            2 -> ctx.getString(R.string.floating_size_large)
            else -> ctx.getString(R.string.floating_size_value, size)
        }
    }

    // ── Édition ──────────────────────────────────────────────────────────────

    private fun brancherEdition() {
        val titre = racine.findViewById<EditText>(R.id.edit_floating_title)
        titre.filters = arrayOf(android.text.InputFilter.LengthFilter(FloatingButtons.TITLE_MAX))
        titre.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (majTitre) return
                val b = bouton(choisi) ?: return
                modifier(b.copy(customTitle = FloatingButtons.customTitleFor(s?.toString().orEmpty(), b.actionLabel)),
                    repeindreChamps = false)
            }
        })
        racine.findViewById<View>(R.id.btn_floating_title_reset).setOnClickListener {
            val b = bouton(choisi) ?: return@setOnClickListener
            modifier(b.copy(customTitle = null))
        }
        racine.findViewById<View>(R.id.btn_floating_action).setOnClickListener {
            val b = bouton(choisi) ?: return@setOnClickListener
            choisirAction(b.action) { action, nom -> modifier(b.copy(action = action.name, actionLabel = nom)) }
        }
        val taille = racine.findViewById<Slider>(R.id.slider_floating_size)
        taille.addOnChangeListener { s, valeur, parUtilisateur ->
            if (!parUtilisateur) return@addOnChangeListener
            // Aimanté aux trois repères ; entre eux, la valeur telle quelle.
            val calee = FloatingButtons.snapSize(valeur.toInt())
            if (calee.toFloat() != valeur) s.value = calee.toFloat()
            bouton(choisi)?.let { modifier(it.copy(size = calee)) }
        }
        listOf(R.id.tick_floating_small to 0, R.id.tick_floating_medium to 1, R.id.tick_floating_large to 2).forEach { (id, rang) ->
            racine.findViewById<View>(id).setOnClickListener {
                val b = bouton(choisi) ?: return@setOnClickListener
                modifier(b.copy(size = FloatingButtons.SIZE_PRESETS[rang]))
            }
        }
        racine.findViewById<Slider>(R.id.slider_floating_text).apply {
            valueFrom = FloatingButtons.TEXT_MIN.toFloat()
            valueTo = FloatingButtons.TEXT_MAX.toFloat()
            addOnChangeListener { _, valeur, parUtilisateur ->
                if (!parUtilisateur) return@addOnChangeListener
                bouton(choisi)?.let { modifier(it.copy(textSp = FloatingButtons.clampText(valeur.toInt()))) }
            }
        }
        racine.findViewById<View>(R.id.btn_floating_close).setOnClickListener {
            fermerEdition()
            peindreListe()
        }
    }

    /** Enregistre [b], repeint l'aperçu (et les champs, sauf pendant la frappe), puis les bulles. */
    private fun modifier(b: FloatingButton, repeindreChamps: Boolean = true) {
        val ctx = fragment?.context ?: return
        FloatingButtonSettings.update(ctx, b)
        peindreEdition(b, repeindreChamps)
        peindreListe()
        racine.removeCallbacks(rafraichir)
        racine.postDelayed(rafraichir, RAFRAICHIR_MS)
    }

    private fun ouvrirEdition() {
        val b = bouton(choisi) ?: return
        racine.findViewById<View>(R.id.floating_edit).visibility = View.VISIBLE
        peindreEdition(b, true)
        peindreListe()
    }

    private fun fermerEdition() {
        choisi = null
        racine.findViewById<View>(R.id.floating_edit).visibility = View.GONE
        racine.removeCallbacks(rafraichir)
        fragment?.context?.let { FloatingButtonsOverlay.refresh(it) }
    }

    private fun peindreEdition(b: FloatingButton, repeindreChamps: Boolean) {
        val ctx = fragment?.context ?: return
        val nom = b.actionLabel.ifEmpty { ctx.getString(R.string.floating_unknown_action) }
        val rang = FloatingButtonSettings.buttons(ctx).indexOfFirst { it.id == b.id }
        racine.findViewById<TextView>(R.id.floating_edit_heading).text = ctx.getString(R.string.floating_edit_heading, rang + 1)
        FloatingButtonsOverlay.peindre(racine.findViewById(R.id.floating_preview), b, FloatingButtons.title(b, nom), deplacement = false)
        racine.findViewById<MaterialButton>(R.id.btn_floating_action).text = nom
        if (repeindreChamps) {
            majTitre = true
            racine.findViewById<EditText>(R.id.edit_floating_title).setText(FloatingButtons.title(b, nom))
            majTitre = false
            racine.findViewById<Slider>(R.id.slider_floating_size).value = b.size.toFloat()
            racine.findViewById<Slider>(R.id.slider_floating_text).value = FloatingButtons.clampText(b.textSp).toFloat()
        }
        racine.findViewById<TextView>(R.id.tv_floating_size).text = nomTaille(b.size)
        racine.findViewById<TextView>(R.id.tv_floating_text).text = ctx.getString(R.string.floating_text_value, b.textSp)
        // Le titre suit-il le raccourci ? ↺ n'a de sens que s'il a été modifié.
        racine.findViewById<View>(R.id.btn_floating_title_reset).alpha = if (b.customTitle == null) 0.4f else 1f
        val repere = FloatingButtons.presetIndex(b.size)
        listOf(R.id.tick_floating_small, R.id.tick_floating_medium, R.id.tick_floating_large).forEachIndexed { i, id ->
            racine.findViewById<TextView>(id).setTextColor(ctx.getColor(if (i == repere) R.color.dash_accent else R.color.text_secondary))
        }
        racine.findViewById<MaterialButton>(R.id.btn_floating_action).strokeColor =
            ColorStateList.valueOf(ctx.getColor(R.color.dash_border))
    }

    private companion object {
        /** Délai avant de reposer les bulles à l'écran après un changement. */
        const val RAFRAICHIR_MS = 400L
    }
}
