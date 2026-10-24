package com.mg4.control.ui

import android.content.Context
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
import com.mg4.control.model.ClusterConfigs
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.FloatingButtonsOverlay
import com.mg4.control.service.ProjectionHvacZone
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FloatingButtonSettings
import com.mg4.control.util.FloatingProfiles

/**
 * Page « Boutons flottants » de l'onglet Raccourcis (2026-10-23, maquette v2) : la liste, l'ajout
 * (le raccourci d'abord, le titre suit), l'édition d'une bulle — titre (par défaut le nom du
 * raccourci, ↺ pour y revenir), raccourci, taille de la bulle (curseur à trois repères aimantés)
 * et du texte — et les options. Chaque changement est enregistré ; les bulles à l'écran suivent
 * après un court délai, pour ne pas les reposer à chaque lettre tapée. Pas d'aperçu dans
 * l'édition (retiré le 2026-10-24) : la vraie bulle, à l'écran, en tient lieu.
 *
 * Par profil depuis le 2026-10-24 (proposition B) : « Boutons de : » choisit le profil réglé, un
 * à la fois — à l'ouverture, celui que suit l'écran conducteur. Tant que la page est à l'écran
 * ([surPageVisible]), les bulles montrées sont les siennes, pour les placer ; « Copier ces boutons
 * vers… » les donne à d'autres profils, dont les bulles sont remplacées.
 */
class FloatingButtonsPage {

    private var fragment: Fragment? = null
    private lateinit var racine: View
    private lateinit var actions: List<Pair<String, ShortcutAction>>
    private var choisi: String? = null
    private var majTitre = false

    /** Profil dont la page règle les bulles ; `null` sans aucun profil (bulles communes). */
    private var profilRegle: String? = null
    /** Page à l'écran : les bulles montrées sont alors celles de [profilRegle]. */
    private var visible = false
    /** L'interrupteur est remis à l'état du profil par le code : ce n'est pas un choix. */
    private var majInterrupteur = false
    private var profils: List<DrivingProfile> = emptyList()
    /** Profil que suit l'écran conducteur : sa puce dit « en cours ». */
    private var enCours: String? = null
    private var puces: List<Pair<String, MaterialButton>> = emptyList()
    private var pucesCopie: List<Pair<String, MaterialButton>> = emptyList()
    private var copieVers: Set<String> = emptySet()

    private val rafraichir = Runnable { fragment?.context?.let { FloatingButtonsOverlay.refresh(it) } }

    /** [actions] : celles des raccourcis du volant, sans celles qui demandent un réglage à part. */
    fun bind(f: Fragment, view: View, actions: List<Pair<String, ShortcutAction>>) {
        fragment = f
        racine = view
        this.actions = actions
        val ctx = f.requireContext()
        // Page encore cachée : les bulles lues et écrites sont celles du profil à l'écran.
        profilRegle = FloatingProfiles.target(ctx)?.id
        relibeller(ctx)

        view.findViewById<Switch>(R.id.switch_floating).apply {
            isChecked = FloatingButtonSettings.isEnabled(ctx)
            setOnCheckedChangeListener { _, on ->
                if (majInterrupteur) return@setOnCheckedChangeListener
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
        // Zone clim pendant la projection : indépendante des bulles, elle a son propre interrupteur.
        view.findViewById<Switch>(R.id.switch_floating_hvac_zone).apply {
            isChecked = FloatingButtonSettings.isHvacZoneEnabled(ctx)
            setOnCheckedChangeListener { _, on ->
                FloatingButtonSettings.setHvacZoneEnabled(ctx, on)
                ProjectionHvacZone.refresh(ctx)
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_reset).setOnClickListener {
            FloatingButtonSettings.resetPositions(ctx)
            FloatingButtonsOverlay.refresh(ctx)
            Toast.makeText(ctx, R.string.floating_reset_done, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_add).setOnClickListener { ajouter() }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy).setOnClickListener { ouvrirCopie() }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy_cancel).setOnClickListener { fermerCopie() }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy_go).setOnClickListener { copier() }
        brancherEdition()
        construirePuces()
        peindreListe()
    }

    /**
     * L'onglet Raccourcis montre ou cache la page. Montrée, les bulles à l'écran deviennent celles
     * du profil réglé ; cachée, celles du profil en cours reviennent.
     */
    fun surPageVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        FloatingProfiles.editingId = if (v) profilRegle else null
        val ctx = fragment?.context ?: return
        if (!v) FloatingProfiles.flush(ctx)
        FloatingButtonsOverlay.suivreProfil(ctx)
        if (v) {
            // Un profil a pu changer entre-temps (créé, supprimé, appliqué).
            construirePuces()
            peindreProfil(ctx)
        }
    }

    /** Le nom de chaque raccourci, dans la langue de l'appli : le service ne connaît pas les libellés. */
    private fun relibeller(ctx: Context) {
        val listeLue = FloatingButtonSettings.buttons(ctx)
        val relibelles = listeLue.map { b -> libelle(b.action)?.let { b.copy(actionLabel = it) } ?: b }
        if (relibelles != listeLue) {
            FloatingButtonSettings.save(ctx, relibelles)
            FloatingButtonsOverlay.refresh(ctx)
        }
    }

    // ── Profils ──────────────────────────────────────────────────────────────

    private fun construirePuces() {
        val ctx = fragment?.context ?: return
        profils = ProfileManager(ctx).getAll()
        enCours = ClusterConfigs.shown(profils, ActiveProfile.id(ctx), ProfileManager.defaultIdOf(ctx))?.id
        if (profils.none { it.id == profilRegle }) {
            // Profil réglé supprimé depuis : celui que suit l'écran conducteur.
            profilRegle = enCours
            if (visible) FloatingProfiles.editingId = profilRegle
        }
        racine.findViewById<View>(R.id.row_floating_profiles).visibility = if (profils.isEmpty()) View.GONE else View.VISIBLE
        racine.findViewById<View>(R.id.btn_floating_copy).visibility = if (profils.size >= 2) View.VISIBLE else View.GONE
        puces = ProfileChips.construire(racine.findViewById(R.id.grid_floating_profiles), profils,
            texte = { p -> textePuce(ctx, p, FloatingProfiles.buttonsOf(p).size) }) { id -> choisirProfil(id) }
        puces.forEach { (id, btn) -> ProfileChips.peindre(btn, id == profilRegle) }
    }

    /** Le nom, « en cours » pour le profil que suit l'écran conducteur, et le nombre de bulles (2/6). */
    private fun textePuce(ctx: Context, p: DrivingProfile, nombre: Int): CharSequence = ProfileChips.texte(p.name,
        listOfNotNull(ctx.getString(R.string.settings_cluster_profile_current).takeIf { p.id == enCours },
            "$nombre/${FloatingButtons.MAX}").joinToString(" · "))

    private fun choisirProfil(id: String) {
        val ctx = fragment?.context ?: return
        if (id == profilRegle) return
        FloatingProfiles.flush(ctx)
        profilRegle = id
        if (visible) FloatingProfiles.editingId = id
        fermerCopie()
        fermerEdition()
        relibeller(ctx)
        puces.forEach { (pid, btn) -> ProfileChips.peindre(btn, pid == profilRegle) }
        peindreProfil(ctx)
        FloatingButtonsOverlay.suivreProfil(ctx)
    }

    /** L'interrupteur et la liste du profil réglé. */
    private fun peindreProfil(ctx: Context) {
        majInterrupteur = true
        racine.findViewById<Switch>(R.id.switch_floating).isChecked = FloatingButtonSettings.isEnabled(ctx)
        majInterrupteur = false
        peindreListe()
    }

    // ── Copie vers d'autres profils ──────────────────────────────────────────

    private fun ouvrirCopie() {
        val ctx = fragment?.context ?: return
        val source = profils.firstOrNull { it.id == profilRegle } ?: return
        if (FloatingButtonSettings.buttons(ctx).isEmpty()) {
            Toast.makeText(ctx, R.string.floating_copy_empty, Toast.LENGTH_SHORT).show()
            return
        }
        FloatingProfiles.flush(ctx)
        copieVers = emptySet()
        racine.findViewById<TextView>(R.id.tv_floating_copy_info).text = ctx.getString(R.string.floating_copy_info, source.name)
        pucesCopie = ProfileChips.construire(racine.findViewById(R.id.grid_floating_copy), profils.filter { it.id != source.id },
            texte = { p -> ProfileChips.texte(p.name, null) }) { id ->
            copieVers = if (id in copieVers) copieVers - id else copieVers + id
            pucesCopie.forEach { (pid, btn) -> ProfileChips.peindre(btn, pid in copieVers) }
        }
        pucesCopie.forEach { (_, btn) -> ProfileChips.peindre(btn, false) }
        racine.findViewById<View>(R.id.floating_copy_panel).visibility = View.VISIBLE
    }

    private fun fermerCopie() {
        copieVers = emptySet()
        racine.findViewById<View>(R.id.floating_copy_panel).visibility = View.GONE
    }

    private fun copier() {
        val ctx = fragment?.context ?: return
        val source = profilRegle ?: return
        if (copieVers.isEmpty()) {
            Toast.makeText(ctx, R.string.floating_copy_choose, Toast.LENGTH_SHORT).show()
            return
        }
        val noms = profils.filter { it.id in copieVers }.joinToString { it.name }
        FloatingProfiles.copyTo(ctx, source, copieVers)
        Toast.makeText(ctx, ctx.getString(R.string.floating_copy_done, noms), Toast.LENGTH_SHORT).show()
        fermerCopie()
        // Les puces disent combien de bulles a chaque profil.
        construirePuces()
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
        profils.firstOrNull { it.id == profilRegle }?.let { p ->
            puces.firstOrNull { it.first == p.id }?.second?.text = textePuce(ctx, p, liste.size)
        }
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

    /** Enregistre [b], repeint l'édition (les champs, sauf pendant la frappe), puis les bulles. */
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
