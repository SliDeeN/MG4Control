package com.mg4.control.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.mg4.control.R
import com.mg4.control.model.BubbleInfo
import com.mg4.control.model.BubbleLook
import com.mg4.control.model.ClusterConfigs
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.FloatingButtonsOverlay
import com.mg4.control.service.OverlayKit
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.CustomShortcutSettings
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
    /** Couleur de la bulle modifiée, dans son panneau (2026-11-04). */
    private var couleurBulle: CouleurRangee? = null
    /** Le panneau d'édition, déplacé dans la carte de la bulle modifiée (issue #213), et sa place d'origine. */
    private var panneau: View? = null
    private var panneauParent: ViewGroup? = null
    private var panneauRang = 0
    /** Lignes de la liste, par bulle : repeintes sur place pendant l'édition. */
    private val lignes = HashMap<String, View>()
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
        panneau = view.findViewById<View>(R.id.floating_edit).also { p ->
            panneauParent = p.parent as? ViewGroup
            panneauRang = panneauParent?.indexOfChild(p) ?: 0
        }
        // « Fermer » et « Bouton n » : remplacés par la ligne de la bulle elle-même (issue #213).
        view.findViewById<View>(R.id.btn_floating_close).visibility = View.GONE
        view.findViewById<View>(R.id.floating_edit_heading).visibility = View.GONE

        view.findViewById<Switch>(R.id.switch_floating).apply {
            isChecked = FloatingButtonSettings.isEnabled(ctx)
            setOnCheckedChangeListener { _, on ->
                peindreEtat(on)
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
        view.findViewById<MaterialButton>(R.id.btn_floating_reset).setOnClickListener {
            FloatingButtonSettings.resetPositions(ctx)
            FloatingButtonsOverlay.refresh(ctx)
            Toast.makeText(ctx, R.string.floating_reset_done, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_add).setOnClickListener { ajouter() }
        // Couleur générale des bulles (2026-11-04) : pastilles, personnalisée, code, par défaut.
        CouleurRangee(ctx, view.findViewById(R.id.floating_color_row), defaut = { com.mg4.control.model.BubbleColors.DEFAULT }) { c ->
            FloatingButtonSettings.setDefaultColor(ctx, c)
            FloatingButtonsOverlay.refresh(ctx)
            majLignes()
        }.peindre(if (FloatingButtonSettings.defaultColor(ctx) == com.mg4.control.model.BubbleColors.DEFAULT) null
            else FloatingButtonSettings.defaultColor(ctx))
        couleurBulle = CouleurRangee(ctx, view.findViewById(R.id.floating_edit_color),
            defaut = { FloatingButtonSettings.defaultColor(ctx) }) { c ->
            bouton(choisi)?.let { modifier(it.copy(color = c), repeindreChamps = false) }
        }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy).setOnClickListener { ouvrirCopie() }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy_cancel).setOnClickListener { fermerCopie() }
        view.findViewById<MaterialButton>(R.id.btn_floating_copy_go).setOnClickListener { copier() }
        brancherEdition()
        construirePuces()
        peindreEtat(FloatingButtonSettings.isEnabled(ctx))
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
        // Un raccourci perso garde son nom à lui (renommé depuis : le nouveau).
        val relibelles = listeLue.map { b ->
            val nom = when (b.action) {
                ShortcutAction.CUSTOM.name -> CustomShortcutSettings.get(ctx, b.customId)?.name
                ShortcutAction.RUN_RULE.name -> RuleChooser.nom(ctx, b.customId)
                else -> libelle(b.action)
            }
            nom?.let { b.copy(actionLabel = it) } ?: b
        }
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
        val on = FloatingButtonSettings.isEnabled(ctx)
        racine.findViewById<Switch>(R.id.switch_floating).isChecked = on
        majInterrupteur = false
        peindreEtat(on)
        peindreListe()
    }

    /**
     * Titre et phrase de l'interrupteur (maquette C du 2026-11-03) : nommé avec le profil réglé
     * — l'interrupteur est le sien —, et ce que fait l'état en cours.
     */
    private fun peindreEtat(on: Boolean) {
        val ctx = fragment?.context ?: return
        val profil = profils.firstOrNull { it.id == profilRegle }
        racine.findViewById<TextView>(R.id.floating_switch_title).text =
            profil?.let { ctx.getString(R.string.floating_switch_title_profile, it.name) } ?: ctx.getString(R.string.floating_switch_title)
        racine.findViewById<TextView>(R.id.floating_switch_desc).setText(if (on) R.string.floating_state_on else R.string.floating_state_off)
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
        choisirAction(null) { action, nom, perso ->
            val b = FloatingButtonSettings.add(ctx, action.name) ?: return@choisirAction
            FloatingButtonSettings.update(ctx, b.copy(actionLabel = nom, customId = perso))
            choisi = b.id
            FloatingButtonsOverlay.refresh(ctx)
            peindreListe()
            ouvrirEdition()
        }
    }

    /**
     * Le raccourci d'une bulle. Raccourci perso (issue #172) : on choisit ensuite lequel — ou on le
     * crée — et son nom devient celui de la bulle ([surChoix] reçoit son identifiant).
     */
    private fun choisirAction(actuelle: String?, dejaPerso: String? = null, surChoix: (ShortcutAction, String, String?) -> Unit) {
        val ctx = fragment?.context ?: return
        AppDialog.choisir(ctx, ctx.getString(R.string.floating_choose_action),
            actions.map { AppDialog.Choix(it.first) },
            annuler = ctx.getString(android.R.string.cancel),
            choisi = actions.indexOfFirst { it.second.name == actuelle }) { rang ->
            val (nom, action) = actions[rang]
            if (action == ShortcutAction.CUSTOM) {
                CustomShortcutDialog.choisir(ctx, dejaPerso) { c -> surChoix(action, c.name, c.id) }
            } else if (action == ShortcutAction.RUN_RULE) {
                RuleChooser.choisir(ctx, dejaPerso) { r -> surChoix(action, r.name, r.id) }
            } else {
                surChoix(action, nom, null)
            }
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
        rangerPanneau()
        lignes.clear()
        val gonfleur = LayoutInflater.from(ctx)
        liste.forEach { b ->
            val ligne = gonfleur.inflate(R.layout.item_floating_button, boite, false)
            lignes[b.id] = ligne
            peindreLigne(ctx, ligne, b)
            val ouverte = b.id == choisi
            ligne.findViewById<MaterialButton>(R.id.btn_floating_row_edit).apply {
                setText(if (ouverte) R.string.floating_close else R.string.floating_edit)
                setOnClickListener { if (ouverte) fermerEdition() else { choisi = b.id; ouvrirEdition() } }
            }
            ligne.findViewById<View>(R.id.btn_floating_row_delete).apply {
                visibility = if (ouverte) View.GONE else View.VISIBLE
                setOnClickListener {
                    FloatingButtonSettings.remove(ctx, b.id)
                    FloatingButtonsOverlay.refresh(ctx)
                    peindreListe()
                }
            }
            if (!ouverte) { boite.addView(ligne); return@forEach }
            // Bulle modifiée (issue #213) : sa ligne et le panneau d'édition dans une même carte,
            // à sa place dans la liste — et non plus tout en bas de la page.
            val carte = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val marge = OverlayKit.dp(ctx, 6f)
                setPadding(marge, marge, marge, marge)
                OverlayKit.fond(this, ctx.getColor(R.color.dash_accent_dim), ctx.getColor(R.color.dash_accent),
                    OverlayKit.dp(ctx, 2f), OverlayKit.dp(ctx, 16f).toFloat())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = OverlayKit.dp(ctx, 8f) }
            }
            (ligne.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
            carte.addView(ligne)
            panneau?.let { p ->
                (p.parent as? ViewGroup)?.removeView(p)
                carte.addView(p)
                p.visibility = View.VISIBLE
            }
            boite.addView(carte)
        }
    }

    /** L'aperçu de la bulle et sa ligne : le résumé, ou « Modifier « titre » » pour la bulle ouverte. */
    private fun peindreLigne(ctx: Context, ligne: View, b: FloatingButton) {
        val info = BubbleInfo.isInfo(b)
        val nom = b.actionLabel.ifEmpty { ctx.getString(R.string.floating_unknown_action) }
        val titre = FloatingButtons.title(b, if (info) nomInfo(b.info) else nom)
        val puce = b.copy(size = 0, textSp = 14)
        FloatingButtonsOverlay.peindre(ligne.findViewById(R.id.floating_row_chip), puce,
            if (info) FloatingButtonsOverlay.texteInfo(ctx, puce, BubbleInfo.UNKNOWN) else titre, deplacement = false)
        // Bulle d'information : « Information · action (ou affichage seul) » à la place du nom de l'action.
        val quoi = if (!info) nom else listOf(ctx.getString(R.string.floating_content_info) + " : " + nomInfo(b.info),
            if (BubbleInfo.tapRuns(b)) nom else ctx.getString(R.string.floating_tap_none_short)).joinToString(" · ")
        ligne.findViewById<TextView>(R.id.floating_row_info).text =
            if (b.id == choisi) ctx.getString(R.string.floating_edit_heading_name, titre)
            else ctx.getString(R.string.floating_summary, quoi,
                if (FloatingButtons.inTopBar(b)) ctx.getString(R.string.floating_loc_bar) else nomTaille(b.size),
                FloatingButtons.textFor(b)) + (resumeApparence(ctx, b)?.let { " · $it" } ?: "")
    }

    /** Pendant l'édition, les lignes sont repeintes sur place : reconstruire la liste ferait perdre la saisie. */
    private fun majLignes() {
        val ctx = fragment?.context ?: return
        FloatingButtonSettings.buttons(ctx).forEach { b -> lignes[b.id]?.let { peindreLigne(ctx, it, b) } }
    }

    /** Le panneau d'édition retourne, caché, à sa place d'origine (hors de la liste). */
    private fun rangerPanneau() {
        val p = panneau ?: return
        val origine = panneauParent ?: return
        if (p.parent !== origine) {
            (p.parent as? ViewGroup)?.removeView(p)
            origine.addView(p, panneauRang.coerceAtMost(origine.childCount))
        }
        p.visibility = View.GONE
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
                // Bulle d'information : le titre est le libellé, par défaut le nom de la valeur.
                val base = if (BubbleInfo.isInfo(b)) nomInfo(b.info) else b.actionLabel
                modifier(b.copy(customTitle = FloatingButtons.customTitleFor(s?.toString().orEmpty(), base)),
                    repeindreChamps = false)
            }
        })
        racine.findViewById<View>(R.id.btn_floating_title_reset).setOnClickListener {
            val b = bouton(choisi) ?: return@setOnClickListener
            modifier(b.copy(customTitle = null))
        }
        racine.findViewById<View>(R.id.btn_floating_action).setOnClickListener {
            val b = bouton(choisi) ?: return@setOnClickListener
            choisirAction(b.action, b.customId) { action, nom, perso ->
                modifier(b.copy(action = action.name, actionLabel = nom, customId = perso))
            }
        }
        // Emplacement (2026-10-24) : dans la barre du haut, la place se choisit à la pose — la
        // première libre — puis au glissé ; de retour sur l'écran, la place d'origine de son rang.
        racine.findViewById<View>(R.id.btn_floating_loc_screen).setOnClickListener {
            val b = bouton(choisi)?.takeIf { FloatingButtons.inTopBar(it) } ?: return@setOnClickListener
            val ctx = fragment?.context ?: return@setOnClickListener
            val (x, y) = FloatingButtons.defaultPosition(FloatingButtonSettings.buttons(ctx).indexOfFirst { it.id == b.id }.coerceAtLeast(0))
            modifier(b.copy(topBar = null, x = x, y = y))
        }
        racine.findViewById<View>(R.id.btn_floating_loc_bar).setOnClickListener {
            val b = bouton(choisi)?.takeIf { !FloatingButtons.inTopBar(it) } ?: return@setOnClickListener
            modifier(b.copy(topBar = true, x = FloatingButtons.TOP_AUTO, textSp = FloatingButtons.textFor(b.copy(topBar = true))))
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
                bouton(choisi)?.let { modifier(it.copy(textSp = FloatingButtons.textFor(it.copy(textSp = valeur.toInt())))) }
            }
        }
        // Texte en gras (2026-10-24) : l'interrupteur est remis à l'état du bouton par le code.
        racine.findViewById<Switch>(R.id.switch_floating_bold).setOnCheckedChangeListener { _, on ->
            if (majTitre) return@setOnCheckedChangeListener
            bouton(choisi)?.let { modifier(it.copy(bold = if (on) true else null), repeindreChamps = false) }
        }
        racine.findViewById<View>(R.id.btn_floating_close).setOnClickListener {
            fermerEdition()
            peindreListe()
        }
        brancherApparence()
        brancherInfo()
    }

    // ── Bulle d'information (issue #236, 2026-11-10) ───────────────────────────

    /** Nom d'une information : « Heure », ou le nom de la valeur dans les automatisations. */
    private fun nomInfo(cle: String?): String {
        val ctx = fragment?.context ?: return cle.orEmpty()
        return BubbleInfo.signal(cle)?.let { AndroidRuleWords(ctx).signal(it) } ?: ctx.getString(R.string.floating_info_time)
    }

    /** Ce que la voiture sait lire : mêmes filtres que la liste des valeurs des automatisations. */
    private fun infoLisible(s: com.mg4.control.model.RuleSignal): Boolean = InfoIcons.readable(s)

    private fun choisirInfo(b: FloatingButton) {
        val ctx = fragment?.context ?: return
        val cles = BubbleInfo.choices(::infoLisible)
        AppDialog.choisir(ctx, titre = ctx.getString(R.string.floating_info_pick_title),
            choix = cles.map { AppDialog.Choix(nomInfo(it)) }, annuler = ctx.getString(R.string.profile_cancel),
            choisi = cles.indexOf(b.info)) { rang ->
            bouton(choisi)?.let { modifier(it.copy(info = cles[rang])) }
        }
    }

    private fun brancherInfo() {
        racine.findViewById<View>(R.id.btn_floating_content_title).setOnClickListener {
            bouton(choisi)?.takeIf { BubbleInfo.isInfo(it) }?.let { modifier(it.copy(info = null, infoStyle = null)) }
        }
        // « Information » : la valeur se choisit tout de suite ; sans choix, la bulle reste un titre.
        racine.findViewById<View>(R.id.btn_floating_content_info).setOnClickListener {
            bouton(choisi)?.takeIf { !BubbleInfo.isInfo(it) }?.let { choisirInfo(it) }
        }
        racine.findViewById<View>(R.id.btn_floating_info).setOnClickListener { bouton(choisi)?.let { choisirInfo(it) } }
        listOf(R.id.btn_floating_style_value to BubbleInfo.STYLE_VALUE, R.id.btn_floating_style_label to BubbleInfo.STYLE_LABEL,
            R.id.btn_floating_style_icon to BubbleInfo.STYLE_ICON).forEach { (id, style) ->
            racine.findViewById<View>(id).setOnClickListener {
                bouton(choisi)?.let { modifier(it.copy(infoStyle = style.takeIf { s -> s != BubbleInfo.STYLE_LABEL })) }
            }
        }
        racine.findViewById<View>(R.id.btn_floating_tap_action).setOnClickListener {
            bouton(choisi)?.let { modifier(it.copy(tapAction = null)) }
        }
        racine.findViewById<View>(R.id.btn_floating_tap_none).setOnClickListener {
            bouton(choisi)?.let { modifier(it.copy(tapAction = false)) }
        }
    }

    private fun peindreInfo(b: FloatingButton) {
        val info = BubbleInfo.isInfo(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_content_title), !info)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_content_info), info)
        racine.findViewById<View>(R.id.row_floating_info).visibility = if (info) View.VISIBLE else View.GONE
        racine.findViewById<View>(R.id.row_floating_info_style).visibility = if (info) View.VISIBLE else View.GONE
        if (info) racine.findViewById<MaterialButton>(R.id.btn_floating_info).text = nomInfo(b.info)
        val style = BubbleInfo.style(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_style_value), style == BubbleInfo.STYLE_VALUE)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_style_label), style == BubbleInfo.STYLE_LABEL)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_style_icon), style == BubbleInfo.STYLE_ICON)
        val agit = BubbleInfo.tapRuns(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_tap_action), agit)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_tap_none), !agit)
        // Au toucher « Rien » : l'action ne sert à rien, sa ligne disparaît (elle est gardée).
        racine.findViewById<View>(R.id.row_floating_action).visibility = if (agit) View.VISIBLE else View.GONE
    }

    // ── Apparence (issue #230, 2026-11-09) ──────────────────────────────────────

    private fun brancherApparence() {
        racine.findViewById<Slider>(R.id.slider_floating_opacity).addOnChangeListener { _, valeur, parUtilisateur ->
            if (!parUtilisateur) return@addOnChangeListener
            bouton(choisi)?.let { modifier(it.copy(opacity = valeur.toInt()), repeindreChamps = false) }
        }
        listOf(R.id.btn_floating_fill_dark to BubbleLook.FILL_DARK, R.id.btn_floating_fill_tint to BubbleLook.FILL_TINT,
            R.id.btn_floating_fill_light to BubbleLook.FILL_LIGHT, R.id.btn_floating_fill_black to BubbleLook.FILL_BLACK).forEach { (id, fond) ->
            racine.findViewById<View>(id).setOnClickListener {
                bouton(choisi)?.let { modifier(it.copy(fill = fond.takeIf { f -> f != BubbleLook.FILL_DARK }), repeindreChamps = false) }
            }
        }
        // Largeur (issue #222) : automatique, ou fixe au curseur.
        racine.findViewById<View>(R.id.btn_floating_width_auto).setOnClickListener {
            bouton(choisi)?.let { modifier(it.copy(widthDp = null)) }
        }
        racine.findViewById<View>(R.id.btn_floating_width_fixed).setOnClickListener {
            bouton(choisi)?.let { if (it.widthDp == null) modifier(it.copy(widthDp = BubbleLook.WIDTH_DEFAULT)) }
        }
        racine.findViewById<Slider>(R.id.slider_floating_width).addOnChangeListener { _, valeur, parUtilisateur ->
            if (!parUtilisateur) return@addOnChangeListener
            bouton(choisi)?.let { modifier(it.copy(widthDp = valeur.toInt()), repeindreChamps = false) }
        }
        racine.findViewById<View>(R.id.btn_floating_border_on).setOnClickListener {
            bouton(choisi)?.let { modifier(it.copy(border = null), repeindreChamps = false) }
        }
        racine.findViewById<View>(R.id.btn_floating_border_off).setOnClickListener {
            bouton(choisi)?.let { modifier(it.copy(border = false), repeindreChamps = false) }
        }
        racine.findViewById<Switch>(R.id.switch_floating_dim).setOnCheckedChangeListener { _, on ->
            if (majTitre) return@setOnCheckedChangeListener
            bouton(choisi)?.let { modifier(it.copy(dimIdle = if (on) true else null), repeindreChamps = false) }
        }
        racine.findViewById<View>(R.id.btn_floating_look_copy).setOnClickListener { copierApparence() }
        racine.findViewById<View>(R.id.btn_floating_look_all).setOnClickListener { appliquerATous() }
    }

    /** Les autres bulles, de ce profil d'abord puis des autres (nommés) : leurs quatre réglages recopiés. */
    private fun copierApparence() {
        val ctx = fragment?.context ?: return
        val b = bouton(choisi) ?: return
        val inconnue = ctx.getString(R.string.floating_unknown_action)
        fun nom(o: FloatingButton) = FloatingButtons.title(o, o.actionLabel.ifEmpty { inconnue })
        val ici = FloatingButtonSettings.buttons(ctx).filter { it.id != b.id }.map { nom(it) to it }
        val ailleurs = profils.filter { it.id != profilRegle }.flatMap { p ->
            FloatingProfiles.buttonsOf(p).map { ctx.getString(R.string.floating_look_from_profile_fmt, nom(it), p.name) to it }
        }
        val choix = ici + ailleurs
        if (choix.isEmpty()) {
            Toast.makeText(ctx, R.string.floating_look_copy_none, Toast.LENGTH_SHORT).show()
            return
        }
        AppDialog.choisir(ctx, titre = ctx.getString(R.string.floating_look_copy_title),
            choix = choix.map { AppDialog.Choix(it.first) }, annuler = ctx.getString(R.string.profile_cancel)) { rang ->
            val source = choix[rang].second
            modifier(BubbleLook.copy(source, b))
            Toast.makeText(ctx, ctx.getString(R.string.floating_look_copied, nom(source)), Toast.LENGTH_SHORT).show()
        }
    }

    /** L'apparence de la bulle ouverte, donnée à toutes les bulles du profil réglé. */
    private fun appliquerATous() {
        val ctx = fragment?.context ?: return
        val b = bouton(choisi) ?: return
        val liste = FloatingButtonSettings.buttons(ctx)
        FloatingButtonSettings.save(ctx, liste.map { if (it.id == b.id) it else BubbleLook.copy(b, it) })
        majLignes()
        racine.removeCallbacks(rafraichir)
        racine.postDelayed(rafraichir, RAFRAICHIR_MS)
        Toast.makeText(ctx, ctx.getString(R.string.floating_look_applied, liste.size), Toast.LENGTH_SHORT).show()
    }

    private fun peindreApparence(ctx: Context, b: FloatingButton, repeindreChamps: Boolean) {
        if (repeindreChamps) {
            majTitre = true
            racine.findViewById<Switch>(R.id.switch_floating_dim).isChecked = BubbleLook.dims(b)
            majTitre = false
            racine.findViewById<Slider>(R.id.slider_floating_opacity).value =
                (BubbleLook.opacity(b) / BubbleLook.OPACITY_STEP * BubbleLook.OPACITY_STEP).toFloat()
        }
        racine.findViewById<TextView>(R.id.tv_floating_opacity).text = ctx.getString(R.string.floating_opacity_value, BubbleLook.opacity(b))
        val fond = BubbleLook.fill(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_fill_dark), fond == BubbleLook.FILL_DARK)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_fill_tint), fond == BubbleLook.FILL_TINT)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_fill_light), fond == BubbleLook.FILL_LIGHT)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_fill_black), fond == BubbleLook.FILL_BLACK)
        val largeur = BubbleLook.width(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_width_auto), largeur == null)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_width_fixed), largeur != null)
        racine.findViewById<View>(R.id.row_floating_width_value).visibility = if (largeur != null) View.VISIBLE else View.GONE
        if (largeur != null) {
            if (repeindreChamps) racine.findViewById<Slider>(R.id.slider_floating_width).value = largeur.toFloat()
            racine.findViewById<TextView>(R.id.tv_floating_width).text = ctx.getString(R.string.floating_width_value, largeur)
        }
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_border_on), BubbleLook.border(b))
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_border_off), !BubbleLook.border(b))
    }

    /** « 35 % · teinté · sans bordure · atténuée », seulement si la bulle n'a plus l'allure d'avant. */
    private fun resumeApparence(ctx: Context, b: FloatingButton): String? {
        if (b.opacity == null && b.fill == null && b.border == null && b.dimIdle == null && b.widthDp == null) return null
        return BubbleLook.summary(b, { f ->
            ctx.getString(when (f) {
                BubbleLook.FILL_TINT -> R.string.floating_fill_tint
                BubbleLook.FILL_LIGHT -> R.string.floating_fill_light
                BubbleLook.FILL_BLACK -> R.string.floating_fill_black
                else -> R.string.floating_fill_dark
            }).lowercase()
        }, ctx.getString(R.string.floating_look_no_border), ctx.getString(R.string.floating_look_dimmed))
    }

    /** Enregistre [b], repeint l'édition (les champs, sauf pendant la frappe), puis les bulles. */
    private fun modifier(b: FloatingButton, repeindreChamps: Boolean = true) {
        val ctx = fragment?.context ?: return
        FloatingButtonSettings.update(ctx, b)
        peindreEdition(b, repeindreChamps)
        majLignes()
        racine.removeCallbacks(rafraichir)
        racine.postDelayed(rafraichir, RAFRAICHIR_MS)
    }

    private fun ouvrirEdition() {
        val b = bouton(choisi) ?: return
        peindreListe()
        peindreEdition(b, true)
        // La carte ouverte peut sortir de l'écran (bas de la liste, bulle ajoutée) : on l'y amène.
        val carte = panneau?.parent as? View ?: return
        carte.post { carte.requestRectangleOnScreen(android.graphics.Rect(0, 0, carte.width, carte.height), false) }
    }

    private fun fermerEdition() {
        choisi = null
        rangerPanneau()
        racine.removeCallbacks(rafraichir)
        fragment?.context?.let { FloatingButtonsOverlay.refresh(it) }
        peindreListe()
    }

    private fun peindreEdition(b: FloatingButton, repeindreChamps: Boolean) {
        val ctx = fragment?.context ?: return
        val nom = b.actionLabel.ifEmpty { ctx.getString(R.string.floating_unknown_action) }
        racine.findViewById<MaterialButton>(R.id.btn_floating_action).text = nom
        if (repeindreChamps) {
            majTitre = true
            racine.findViewById<EditText>(R.id.edit_floating_title).setText(
                FloatingButtons.title(b, if (BubbleInfo.isInfo(b)) nomInfo(b.info) else nom))
            racine.findViewById<Switch>(R.id.switch_floating_bold).isChecked = b.bold == true
            couleurBulle?.peindre(b.color)
            majTitre = false
            racine.findViewById<Slider>(R.id.slider_floating_size).value = b.size.toFloat()
            // Dans la barre, 14 sp au plus : la valeur d'abord ramenée sous la nouvelle borne, sinon
            // le curseur refuse sa borne.
            racine.findViewById<Slider>(R.id.slider_floating_text).apply {
                val borne = (if (FloatingButtons.inTopBar(b)) FloatingButtons.TOP_TEXT_MAX else FloatingButtons.TEXT_MAX).toFloat()
                value = value.coerceAtMost(borne)
                valueTo = borne
                value = FloatingButtons.textFor(b).toFloat()
            }
        }
        racine.findViewById<TextView>(R.id.tv_floating_size).text = nomTaille(b.size)
        racine.findViewById<TextView>(R.id.tv_floating_text).text = ctx.getString(R.string.floating_text_value, FloatingButtons.textFor(b))
        // Emplacement : la taille de la bulle est celle de la barre du haut, plus de curseur.
        val enHaut = FloatingButtons.inTopBar(b)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_loc_screen), !enHaut)
        ProfileChips.peindre(racine.findViewById(R.id.btn_floating_loc_bar), enHaut)
        racine.findViewById<View>(R.id.row_floating_size).visibility = if (enHaut) View.GONE else View.VISIBLE
        racine.findViewById<View>(R.id.row_floating_ticks).visibility = if (enHaut) View.GONE else View.VISIBLE
        racine.findViewById<View>(R.id.row_floating_size_bar).visibility = if (enHaut) View.VISIBLE else View.GONE
        // Le titre suit-il le raccourci ? ↺ n'a de sens que s'il a été modifié.
        racine.findViewById<View>(R.id.btn_floating_title_reset).alpha = if (b.customTitle == null) 0.4f else 1f
        val repere = FloatingButtons.presetIndex(b.size)
        listOf(R.id.tick_floating_small, R.id.tick_floating_medium, R.id.tick_floating_large).forEachIndexed { i, id ->
            racine.findViewById<TextView>(id).setTextColor(ctx.getColor(if (i == repere) R.color.dash_accent else R.color.text_secondary))
        }
        racine.findViewById<MaterialButton>(R.id.btn_floating_action).strokeColor =
            ColorStateList.valueOf(ctx.getColor(R.color.dash_border))
        peindreApparence(ctx, b, repeindreChamps)
        peindreInfo(b)
    }

    private companion object {
        /** Délai avant de reposer les bulles à l'écran après un changement. */
        const val RAFRAICHIR_MS = 400L
    }
}
