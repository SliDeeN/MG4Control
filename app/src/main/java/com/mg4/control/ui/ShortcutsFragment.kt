package com.mg4.control.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.ui.widget.setCategoryTabActive
import com.mg4.control.util.CustomShortcutSettings
import com.mg4.control.accessibility.AdvancedShortcuts
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateScreenMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileManager
import com.mg4.control.service.ClusterModuleOverlay
import com.mg4.control.service.OverlayKit
import com.mg4.control.service.PreClimate
import com.mg4.control.shortcut.AudioSource
import com.mg4.control.shortcut.AudioSourceCycle
import com.mg4.control.shortcut.DriveModeCycle
import com.mg4.control.model.DriveMode
import com.mg4.control.shortcut.ProfileCycle
import com.mg4.control.shortcut.RegenCycle
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.PreClimateSettings

class ShortcutsFragment : Fragment() {

    private val PREFS = "mg4_shortcuts"

    private lateinit var prefs: SharedPreferences
    private var accentColor = 0
    private var defColor    = 0

    private var switchEnabled:   Switch? = null
    /** Vrai pendant que [restoreState] pose l'interrupteur : ce n'est pas un choix à enregistrer. */
    private var restauration = false

    /** Éléments disponibles dans les Spinners — calculés une fois selon le firmware. */
    private data class ActionItem(val label: String, val action: ShortcutAction)

    /** Liste de base (sans label custom) — partagée pour tous les spinners. */
    private var baseActionItems: List<ActionItem> = emptyList()

    /** Page « Boutons flottants » (2026-10-23). */
    private val floatingPage = FloatingButtonsPage()
    /** Raccourcis perso (issue #172) : leur page, dans le rail. */
    private val customPage = CustomShortcutsPage()
    /** Raccourcis › Réglages rapides (issue #197). */
    companion object {
        /** Page à ouvrir au prochain affichage, demandée par un pop-up (voir MainActivity). */
        var pageDemandee: String? = null
    }

    private val quickPage = QuickSettingsPage()
    /** La page Réglages rapides a été demandée par son pop-up : elle reste dans la rangée. */
    private var quickForcee = false

    /** Clés identifiant chaque ligne slot × type de pression. */
    private val slotPressList = listOf(
        "btn1_single", "btn1_long",
        "btn2_single", "btn2_long"
    )

    /**
     * Séquence du cycle de régénération en cours d'édition — miroir de ce qui est enregistré.
     *
     * L'ordre EST l'information : c'est celui des appuis de l'utilisateur, pas celui des boutons
     * à l'écran. Une simple liste de niveaux suffit donc, la position valant rang.
     */
    private val regenCycleSel = mutableListOf<RegenLevel>()

    /** Rejoue l'affichage de la page du cycle (état des modes, du résumé et du bouton). */
    private var majCycleRegen: (() -> Unit)? = null

    /** Repart de la séquence ENREGISTRÉE, en jetant une composition non sauvegardée. */
    private var rechargerCycleRegen: (() -> Unit)? = null

    /** Cycle des modes de conduite (issue #207) : mêmes trois pièces que celui de la régénération. */
    private val driveCycleSel = mutableListOf<DriveMode>()
    private var majCycleModes: (() -> Unit)? = null
    private var rechargerCycleModes: (() -> Unit)? = null

    /**
     * Cycle de profils en cours d'édition : des IDENTIFIANTS de profil, dans l'ordre des appuis.
     * Même principe que [regenCycleSel], la position vaut rang.
     */
    private val profileCycleSel = mutableListOf<String>()

    /** Pendants de [majCycleRegen] et [rechargerCycleRegen] pour la page du cycle de profils. */
    private var majCycleProfils: (() -> Unit)? = null
    private var rechargerCycleProfils: (() -> Unit)? = null

    /** Cycle de sources audio en cours de composition ; la position vaut rang, comme les autres. */
    private val sourceCycleSel = mutableListOf<AudioSource>()
    private var rechargerCycleSources: (() -> Unit)? = null

    /** Page « Boutons du volant » : les anciens onglets Raccourcis et Avancés, fusionnés (2026-11-02). */
    private val wheelPage = WheelButtonsPage(object : WheelButtonsPage.Host {
        override val ctx: Context get() = requireContext()
        override fun actions() = baseActionItems.map { it.label to it.action }
        override fun choisirCible(slot: String, action: ShortcutAction, apres: () -> Unit) = when (action) {
            ShortcutAction.OPEN_CUSTOM_APP -> choisirAppAvancee(slot, apres)
            ShortcutAction.APPLY_PROFILE   -> choisirProfilAvance(slot, apres)
            ShortcutAction.CUSTOM          -> choisirPersoAvance(slot, apres)
            ShortcutAction.RUN_RULE        -> RuleChooser.choisir(requireContext(),
                prefs.getString("shortcut_${slot}_rule_id", null)) { r ->
                prefs.edit().putString("shortcut_${slot}_rule_id", r.id).apply()
                apres()
            }
            else -> apres()
        }
        override fun libelle(slot: String, action: ShortcutAction) = libelleCible(slot, action)
        override fun changement() { reselectTabs?.invoke() }
    })

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_shortcuts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs       = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accentColor = requireContext().getColor(R.color.accent_eco)
        defColor    = requireContext().getColor(R.color.bg_button)

        switchEnabled    = view.findViewById(R.id.switch_shortcuts_enabled)

        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN

        // ── Construction des items de base selon firmware ─────────────────
        baseActionItems = ShortcutCatalog.items(requireContext()).map { ActionItem(it.label, it.action) }

        // ── Affichage des sections de config selon firmware ───────────────
        // Tous les firmwares connus utilisent la config 5 modes (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA).
        adasSupported = isKnown
        view.findViewById<View>(R.id.config_adas_swi133)?.visibility  = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi68)?.visibility   = View.GONE

        wheelPage.bind(view)
        setupConfigListeners(view)
        setupRegenCycle(view)
        setupDriveCycle(view)
        setupProfileCycle(view)
        setupSourceCycle(view)
        setupPreClimatePage(view)
        // Boutons flottants : les actions du volant, sans celles qui demandent un réglage à part
        // (profil ou appli à choisir) — la bulle n'a que son raccourci.
        view.findViewById<android.widget.LinearLayout>(R.id.page_sc_quick)?.let { quickPage.bind(it) }
        floatingPage.bind(this, view, baseActionItems
            .filter { it.action !in setOf(ShortcutAction.NONE, ShortcutAction.APPLY_PROFILE, ShortcutAction.OPEN_CUSTOM_APP) }
            .map { it.label to it.action })
        // Raccourcis perso : un changement repeint les emplacements qui en lancent un.
        customPage.bind(this, view) { wheelPage.refresh() }
        restoreState()

        // En dernier : le rail décide quelles pages existent, il lui faut l'état final.
        rootView = view
        bindCategoryRail(view)
    }

    // ── Onglets « Boutons » / « Actions » ────────────────────────────────

    /** Vrai si le firmware expose le cycle ADAS (sinon la section reste masquée en permanence). */
    private var adasSupported = false
    private var rootView: View? = null
    /** Rejoue la sélection d'onglet après un changement de visibilité (le rail peut apparaître
     *  ou disparaître quand l'utilisateur attribue ou retire une action). */
    private var reselectTabs: (() -> Unit)? = null


    override fun onResume() {
        super.onResume()
        // « Choisir les tuiles » du pop-up Réglages rapides : directement sur sa page.
        if (pageDemandee == com.mg4.control.MainActivity.PAGE_QUICK_SETTINGS) {
            pageDemandee = null
            quickForcee = true
            reselectTabs?.invoke()
            rootView?.findViewById<View>(R.id.btn_sc_sub_quick)?.performClick()
        }
        // L utilisateur revient peut-etre des reglages d accessibilite : l etat affiche doit
        // suivre, sinon il resterait sur "inactif" apres avoir active le service.
        wheelPage.refresh()
        floatingPage.surPageVisible(rootView?.findViewById<View>(R.id.page_sc_floating)?.visibility == View.VISIBLE)
    }

    override fun onPause() {
        // Hors de l'écran, les bulles redeviennent celles du profil en cours.
        floatingPage.surPageVisible(false)
        super.onPause()
    }

    override fun onDestroyView() {
        // Le listener est statique : ne pas le liberer retiendrait ce Fragment detruit.
        KeyCaptureService.listener = null
        wheelPage.detacher()
        // Même raison : cette lambda capture les vues du rail. Elle a désormais plusieurs
        // appelants (spinner avancé, création, suppression), donc plusieurs occasions d'être
        // invoquée après la destruction de la vue si on la laissait en place.
        reselectTabs = null
        majCycleRegen = null
        rechargerCycleRegen = null
        majCycleModes = null
        rechargerCycleModes = null
        majCycleProfils = null
        rechargerCycleProfils = null
        rechargerCycleSources = null
        rootView = null
        super.onDestroyView()
    }

    /**
     * Sélecteurs propres aux raccourcis avancés.
     *
     * Volontairement distincts de [showAppPickerDialog] / [showProfilePickerDialog] : ceux-là
     * rafraîchissent les spinners de l'écran classique et remettraient un emplacement à NONE en
     * cas d'annulation. Les réutiliser ici aurait modifié les raccourcis classiques au passage.
     */
    /** Service AllGo du raccourci Android Auto / CarPlay (`com.allgo.rui`), sur les six firmwares connus. */
    private fun choisirAppAvancee(slot: String, apres: () -> Unit) {
        val cle = "shortcut_${slot}_custom_app"
        choisirUneApp(dejaChoisie = prefs.getString(cle, null)) { paquet, _ ->
            prefs.edit().putString(cle, paquet).apply()
            apres()
        }
    }

    private fun choisirProfilAvance(slot: String, apres: () -> Unit) {
        val profils = ProfileManager(requireContext()).getAll()
        if (profils.isEmpty()) {
            Toast.makeText(requireContext(), R.string.shortcuts_no_profiles, Toast.LENGTH_SHORT).show()
            return
        }
        val cle = "shortcut_${slot}_profile_id"
        choisirUnProfil(profils, dejaChoisi = prefs.getString(cle, null)) { profil ->
            prefs.edit().putString(cle, profil.id).apply()
            apres()
        }
    }

    /**
     * La grille des applications lançables, triées par nom, chacune avec son icône.
     *
     * @param dejaChoisie paquet de l'application en cours : sa tuile est marquée
     * @param onAnnule    la grille fermée sans choisir
     */
    private fun choisirUneApp(
        dejaChoisie: String?,
        onAnnule: () -> Unit = {},
        onChoix: (paquet: String, nom: String) -> Unit,
    ) {
        val pm = requireContext().packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it to it.loadLabel(pm).toString() }.sortedBy { it.second.lowercase() }

        AppDialog.choisir(
            requireContext(),
            titre = getString(R.string.shortcuts_pick_app_title),
            sousTitre = getString(R.string.choice_count_apps, apps.size),
            // Une icône illisible ne doit pas empêcher de choisir l'application : sa tuile reste,
            // avec son seul nom.
            choix = apps.map { (info, nom) ->
                AppDialog.Choix(nom, icone = runCatching { info.loadIcon(pm) }.getOrNull())
            },
            annuler = getString(R.string.profile_cancel),
            choisi = apps.indexOfFirst { it.first.activityInfo.packageName == dejaChoisie },
            onAnnule = onAnnule,
        ) { rang -> onChoix(apps[rang].first.activityInfo.packageName, apps[rang].second) }
    }

    /** La grille des profils, chacun avec la pastille de son mode de conduite. */
    private fun choisirUnProfil(
        profils: List<DrivingProfile>,
        dejaChoisi: String?,
        onAnnule: () -> Unit = {},
        onChoix: (DrivingProfile) -> Unit,
    ) {
        val ctx = requireContext()
        AppDialog.choisir(
            ctx,
            titre = getString(R.string.shortcuts_pick_profile_title),
            sousTitre = getString(R.string.choice_count_profiles, profils.size),
            choix = profils.map { profil ->
                // Rien sur un réglage que le profil n'écrira pas : sans la catégorie Conduite, pas
                // de mode — la pastille garde sa place, vide, pour que les noms restent alignés.
                if (!profil.appliesDrive) AppDialog.Choix(profil.name, pastille = "") else {
                    val (fond, texte) = OverlayKit.pastilleMode(ctx, profil.driveMode)
                    AppDialog.Choix(profil.name, pastille = profil.driveMode.label,
                        fondPastille = fond, textePastille = texte)
                }
            },
            annuler = getString(R.string.profile_cancel),
            choisi = profils.indexOfFirst { it.id == dejaChoisi },
            onAnnule = onAnnule,
        ) { rang -> onChoix(profils[rang]) }
    }

    /**
     * Libellé d'une ligne de liste. Pour les deux actions à cible, on affiche la CIBLE et non
     * l'action générique : « Waze » plutôt que « Ouvrir une app », sans quoi deux raccourcis
     * différents seraient indiscernables.
     */
    private fun libelleCible(slot: String, action: ShortcutAction): String {
        val generique = baseActionItems.firstOrNull { it.action == action }?.label ?: action.name
        return when (action) {
            ShortcutAction.OPEN_CUSTOM_APP ->
                prefs.getString("shortcut_${slot}_custom_app", null)
                    ?.let { resolveAppLabel(it) } ?: generique
            ShortcutAction.APPLY_PROFILE ->
                prefs.getString("shortcut_${slot}_profile_id", null)
                    ?.let { id -> ProfileManager(requireContext()).getById(id)?.name }
                    ?.let { getString(R.string.shortcuts_profile_prefix) + " " + it } ?: generique
            ShortcutAction.CUSTOM -> libellePerso(slot)
            ShortcutAction.RUN_RULE -> RuleChooser.nom(requireContext(), prefs.getString("shortcut_${slot}_rule_id", null))
                ?.let { "$generique : $it" } ?: generique
            else -> generique
        }
    }

    /**
     * Les entrées du spinner de profil : « Tous les profils » d'abord, puis les profils
     * existants. La valeur portée est l'identifiant, `null` pour la première.
     */
    /**
     * Nom du profil d'un raccourci.
     *
     * Un identifiant qui ne correspond plus à rien est DIT, pas masqué : c'est ce qui arrive
     * quand on supprime un profil, et le raccourci resté derrière ne se déclenchera jamais.
     * Mieux vaut le voir dans la liste et pouvoir l'effacer.
     */
    /**
     * Identité d'un bouton : son nom quand on le connaît, et TOUJOURS son code.
     *
     * Le code n'est pas décoratif — c'est lui qu'on demande dans les remontées de bug, et le
     * seul repère pour une touche que [AdvancedShortcuts.nomTouche] ne connaît pas encore.
     */
    /**
     * Vocabulaire d'appui propre aux raccourcis avancés — « appui court / long / double »,
     * comme les trois boutons du formulaire. L'écran classique garde le sien (Simple / Long /
     * Double), plus compact parce qu'il tient dans une colonne de tableau.
     */
    /**
     * Change la FONCTION et le PROFIL d'un raccourci existant, sans retoucher ni la touche ni le
     * type d'appui — refaire les étapes pour corriger l'un des deux n'avait pas de raison d'être,
     * et obligeait à réappuyer sur un bouton que le service consomme.
     *
     * Les deux réglages sont dans la MÊME boîte. Deux dialogues enchaînés auraient été plus
     * simples, mais on ne peut alors plus revenir sur le premier choix après avoir vu le second,
     * et annuler à mi-chemin laisse un raccourci à moitié modifié.
     */
    /**
     * Reconstruit la liste des raccourcis avancés, GROUPÉE par touche + type d'appui.
     *
     * Le regroupement porte l'information : à l'intérieur d'un groupe, les lignes sont dans
     * l'ordre où elles sont résolues à l'appui — « tous les profils » en tête, variantes en
     * dessous. Une liste plate laisserait deviner laquelle l'emporte.
     */
    /**
     * Une fonction est-elle attribuée à un bouton, et donc son réglage utile ?
     *
     * Trois sources, et il en faut trois :
     *  • les emplacements classiques,
     *  • les raccourcis avancés déjà créés — c'est celle qui manquait, et sans elle un réglage
     *    attribué depuis l'onglet avancé n'apparaissait JAMAIS,
     *  • la fonction en cours de sélection dans le formulaire avancé, parce que c'est LÀ que
     *    l'utilisateur veut la régler : c'est le moment où il y pense.
     *
     * Ces réglages sont globaux : peu importe laquelle des trois sources répond, le réglage vaut
     * pour tous les boutons qui déclenchent la fonction.
     */
    private fun actionEnJeu(action: ShortcutAction): Boolean {
        if (slotPressList.any {
                ShortcutAction.fromId(prefs.getInt("shortcut_$it", 0)) == action
            }) return true
        val ctx = requireContext()
        if (AdvancedShortcuts.all(ctx).any { it.action == action }) return true
        // Les boutons flottants aussi, de tous les profils (2026-11-04) : une bulle « Cycle de
        // régénération » ne faisait pas apparaître la page qui règle ce cycle.
        val bulles = ProfileManager(ctx).getAll()
            .flatMap { com.mg4.control.util.FloatingProfiles.buttonsOf(it) } +
            com.mg4.control.util.FloatingButtonSettings.legacyButtons(ctx)
        if (bulles.any { it.action == action.name }) return true
        // Et Mes automatisations : une action « raccourci » qui lance cette fonction.
        fun lance(l: List<com.mg4.control.model.RuleAction>?): Boolean = l.orEmpty().any {
            (it.type == com.mg4.control.model.RuleAction.Type.SHORTCUT && it.shortcut == action.name) || lance(it.then) || lance(it.otherwise)
        }
        return com.mg4.control.automation.CustomRuleStore.all(ctx).any { lance(it.actions) }
    }

    /**
     * Composition de la séquence parcourue par le raccourci « Cycle Régénération Personnalisé ».
     *
     * Le geste tient en un principe : l'ordre des appuis EST l'ordre du cycle. Toucher un mode
     * éteint l'ajoute en fin de séquence, toucher un mode allumé le retire. Pas de flèches, pas
     * de glisser-déposer — ni l'un ni l'autre ne se manient au volant.
     *
     * La composition est LIBRE, y compris vide, et rien n'est enregistré avant « Sauvegarder ».
     * Le minimum de deux modes s'imposait auparavant à chaque geste : il fallait défaire pour
     * refaire, ce qui rendait le choix des deux PREMIERS modes du cycle presque impraticable.
     */
    private fun setupRegenCycle(view: View) {
        val boutons = listOf(
            R.id.sc_regen_cycle_low       to RegenLevel.LOW,
            R.id.sc_regen_cycle_medium    to RegenLevel.MEDIUM,
            R.id.sc_regen_cycle_high      to RegenLevel.HIGH,
            R.id.sc_regen_cycle_adaptive  to RegenLevel.ADAPTIVE,
            R.id.sc_regen_cycle_one_pedal to RegenLevel.ONE_PEDAL
        ).mapNotNull { (id, niveau) ->
            view.findViewById<MaterialButton>(id)?.let { it to niveau }
        }
        val resume = view.findViewById<TextView>(R.id.tv_regen_cycle_summary)
        val save   = view.findViewById<MaterialButton>(R.id.btn_regen_cycle_save)

        val texteActif   = requireContext().getColor(R.color.text_active)
        val texteInactif = requireContext().getColor(R.color.text_secondary)

        fun maj() {
            boutons.forEach { (btn, niveau) ->
                val rang = regenCycleSel.indexOf(niveau)
                val on   = rang >= 0
                // Le rang est PORTÉ par le bouton, sur une seconde ligne : cinq boutons allumés
                // ne diraient pas dans quel ordre ils sont parcourus, qui est tout l'objet de
                // l'écran. L'espace insécable garde la même hauteur quand il n'y a pas de rang.
                btn.text = libelleRegen(niveau) + "\n" + (if (on) "${rang + 1}" else "\u00A0")
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accentColor else defColor)
                btn.setTextColor(if (on) texteActif else texteInactif)
            }
            val assez = regenCycleSel.size >= RegenCycle.MIN_MODES
            // À la place du résumé, la RAISON du refus : un bouton grisé sans explication se lit
            // comme une panne de l'écran.
            resume?.text = if (assez) getString(
                R.string.shortcuts_cfg_regen_summary,
                regenCycleSel.joinToString(" → ") { libelleRegen(it) }
            ) else getString(R.string.shortcuts_cfg_regen_min)
            save?.isEnabled = assez
            save?.alpha     = if (assez) 1f else 0.4f
        }

        boutons.forEach { (btn, niveau) ->
            btn.setOnClickListener {
                // Aucun minimum PENDANT la composition : le verrou ne s'applique qu'à
                // l'enregistrement. `remove` rend faux quand l'élément n'y était pas, ce qui
                // donne la bascule ajouter/retirer en une ligne.
                if (!regenCycleSel.remove(niveau)) regenCycleSel.add(niveau)
                maj()
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_regen_cycle_clear)?.setOnClickListener {
            regenCycleSel.clear()
            maj()
        }

        save?.setOnClickListener {
            if (regenCycleSel.size < RegenCycle.MIN_MODES) return@setOnClickListener
            RegenCycle.save(requireContext(), regenCycleSel)
            Toast.makeText(requireContext(), R.string.shortcuts_cfg_regen_saved,
                Toast.LENGTH_SHORT).show()
        }

        majCycleRegen = { maj() }
        rechargerCycleRegen = {
            regenCycleSel.clear()
            regenCycleSel.addAll(RegenCycle.order(requireContext()))
            maj()
        }
        rechargerCycleRegen?.invoke()
    }

    /**
     * Composition du cycle des modes de conduite (issue #207) : le même geste que [setupRegenCycle]
     * — l'ordre des appuis est l'ordre du cycle, rien n'est gardé avant « Sauvegarder », deux modes
     * au moins.
     */
    private fun setupDriveCycle(view: View) {
        val boutons = listOf(
            R.id.sc_drive_cycle_eco    to DriveMode.ECO,
            R.id.sc_drive_cycle_normal to DriveMode.NORMAL,
            R.id.sc_drive_cycle_sport  to DriveMode.SPORT,
            R.id.sc_drive_cycle_snow   to DriveMode.SNOW,
            R.id.sc_drive_cycle_custom to DriveMode.CUSTOM,
        ).mapNotNull { (id, mode) -> view.findViewById<MaterialButton>(id)?.let { it to mode } }
        val resume = view.findViewById<TextView>(R.id.tv_drive_cycle_summary)
        val save   = view.findViewById<MaterialButton>(R.id.btn_drive_cycle_save)
        val texteActif   = requireContext().getColor(R.color.text_active)
        val texteInactif = requireContext().getColor(R.color.text_secondary)

        fun maj() {
            boutons.forEach { (btn, mode) ->
                val rang = driveCycleSel.indexOf(mode)
                val on   = rang >= 0
                btn.text = libelleMode(mode) + "\n" + (if (on) "${rang + 1}" else "\u00A0")
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accentColor else defColor)
                btn.setTextColor(if (on) texteActif else texteInactif)
            }
            val assez = driveCycleSel.size >= DriveModeCycle.MIN_MODES
            resume?.text = if (assez) getString(R.string.shortcuts_cfg_regen_summary,
                driveCycleSel.joinToString(" → ") { libelleMode(it) })
            else getString(R.string.shortcuts_cfg_drive_min)
            save?.isEnabled = assez
            save?.alpha     = if (assez) 1f else 0.4f
        }

        boutons.forEach { (btn, mode) ->
            btn.setOnClickListener {
                if (!driveCycleSel.remove(mode)) driveCycleSel.add(mode)
                maj()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_drive_cycle_clear)?.setOnClickListener {
            driveCycleSel.clear()
            maj()
        }
        save?.setOnClickListener {
            if (driveCycleSel.size < DriveModeCycle.MIN_MODES) return@setOnClickListener
            DriveModeCycle.save(requireContext(), driveCycleSel)
            Toast.makeText(requireContext(), R.string.shortcuts_cfg_regen_saved, Toast.LENGTH_SHORT).show()
        }
        majCycleModes = { maj() }
        rechargerCycleModes = {
            driveCycleSel.clear()
            driveCycleSel.addAll(DriveModeCycle.order(requireContext()))
            maj()
        }
        rechargerCycleModes?.invoke()
    }

    /** Nom d'un mode, le même que sur l'écran Conduite. */
    private fun libelleMode(m: DriveMode): String = getString(when (m) {
        DriveMode.ECO    -> R.string.drive_eco
        DriveMode.NORMAL -> R.string.drive_normal
        DriveMode.SPORT  -> R.string.drive_sport
        DriveMode.SNOW   -> R.string.drive_snow
        DriveMode.CUSTOM -> R.string.drive_custom
    })

    /** Libellés courts des niveaux — les mêmes que la rangée « Regen retour », pour que le même
     *  mode ne porte pas deux noms d'un écran à l'autre. */
    private fun libelleRegen(niveau: RegenLevel): String = getString(when (niveau) {
        RegenLevel.LOW       -> R.string.regen_low
        RegenLevel.MEDIUM    -> R.string.regen_medium
        RegenLevel.HIGH      -> R.string.regen_high
        RegenLevel.ADAPTIVE  -> R.string.regen_adaptive_short
        RegenLevel.ONE_PEDAL -> R.string.regen_one_pedal_short
        RegenLevel.OFF       -> R.string.regen_off
    })

    /**
     * Composition du cycle parcouru par le raccourci « Cycle de profils ».
     *
     * Même écran et même geste que [setupRegenCycle] — l'ordre des appuis EST l'ordre du cycle,
     * rien n'est enregistré avant « Sauvegarder » — à une différence près : les boutons ne sont
     * pas fixes. Les profils se créent, se renomment et se suppriment dans un autre onglet ; ils
     * sont donc RELUS à chaque arrivée sur la page, et le cycle affiché est celui que le
     * raccourci parcourrait à cet instant (profils supprimés déjà retirés).
     */
    private fun setupProfileCycle(view: View) {
        // Dix boutons pour dix profils au plus (ProfileManager.MAX_PROFILES), en deux rangées
        // de cinq : la seconde ne se montre qu'à partir du sixième profil.
        val boutons = listOf(
            R.id.sc_profile_cycle_1, R.id.sc_profile_cycle_2, R.id.sc_profile_cycle_3,
            R.id.sc_profile_cycle_4, R.id.sc_profile_cycle_5, R.id.sc_profile_cycle_6,
            R.id.sc_profile_cycle_7, R.id.sc_profile_cycle_8, R.id.sc_profile_cycle_9,
            R.id.sc_profile_cycle_10
        ).mapNotNull { view.findViewById<MaterialButton>(it) }
        val rangee  = view.findViewById<View>(R.id.row_profile_cycle)
        val rangee2 = view.findViewById<View>(R.id.row_profile_cycle_2)
        val vide   = view.findViewById<TextView>(R.id.tv_profile_cycle_empty)
        val resume = view.findViewById<TextView>(R.id.tv_profile_cycle_summary)
        val save   = view.findViewById<MaterialButton>(R.id.btn_profile_cycle_save)

        val texteActif   = requireContext().getColor(R.color.text_active)
        val texteInactif = requireContext().getColor(R.color.text_secondary)

        var profils = emptyList<DrivingProfile>()

        fun maj() {
            boutons.forEachIndexed { i, btn ->
                val profil = profils.getOrNull(i)
                // INVISIBLE et non GONE : avec deux profils, deux boutons de la largeur de la
                // page se liraient comme deux bandeaux, plus comme des choix.
                btn.visibility = if (profil != null) View.VISIBLE else View.INVISIBLE
                if (profil == null) return@forEachIndexed
                val rang = profileCycleSel.indexOf(profil.id)
                val on   = rang >= 0
                btn.text = ProfileCycle.label(profil.name) + "\n" + (if (on) "${rang + 1}" else "\u00A0")
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accentColor else defColor)
                btn.setTextColor(if (on) texteActif else texteInactif)
            }
            val aucun = profils.isEmpty()
            vide?.visibility    = if (aucun) View.VISIBLE else View.GONE
            rangee?.visibility  = if (aucun) View.GONE else View.VISIBLE
            rangee2?.visibility = if (ProfileCycle.rows(profils.size) > 1) View.VISIBLE else View.GONE

            val assez = profileCycleSel.size >= ProfileCycle.MIN_PROFILES
            // Le résumé porte les noms ENTIERS : ceux des boutons sont raccourcis.
            resume?.text = when {
                aucun -> ""
                assez -> getString(
                    R.string.shortcuts_cfg_regen_summary,
                    profileCycleSel.mapNotNull { id -> profils.firstOrNull { it.id == id }?.name }
                        .joinToString(" → "))
                else  -> getString(R.string.shortcuts_cfg_profile_min)
            }
            save?.isEnabled = assez
            save?.alpha     = if (assez) 1f else 0.4f
        }

        boutons.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                val id = profils.getOrNull(i)?.id ?: return@setOnClickListener
                if (!profileCycleSel.remove(id)) profileCycleSel.add(id)
                maj()
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_profile_cycle_clear)?.setOnClickListener {
            profileCycleSel.clear()
            maj()
        }

        save?.setOnClickListener {
            if (profileCycleSel.size < ProfileCycle.MIN_PROFILES) return@setOnClickListener
            ProfileCycle.save(requireContext(), profileCycleSel)
            Toast.makeText(requireContext(), R.string.shortcuts_cfg_regen_saved,
                Toast.LENGTH_SHORT).show()
        }

        majCycleProfils = { maj() }
        rechargerCycleProfils = {
            profils = ProfileManager(requireContext()).getAll().take(boutons.size)
            profileCycleSel.clear()
            profileCycleSel.addAll(ProfileCycle.order(requireContext(), profils.map { it.id }))
            maj()
        }
        rechargerCycleProfils?.invoke()
    }

    /**
     * Composition du cycle de sources audio (2026-10-15) — même geste que les cycles de
     * régénération et de profils : l'ordre des appuis EST l'ordre du cycle, rien n'est enregistré
     * avant « Sauvegarder ». Présentation en tuiles (maquette B) : pictogramme, nom, et le rang en
     * pastille. Leur disponibilité n'est pas vérifiée, par choix ; seule la musique en ligne est
     * retirée des A9, où elle n'existe pas ([AudioSourceCycle.offertes]).
     */
    private fun setupSourceCycle(view: View) {
        val offertes = AudioSourceCycle.offertes(FirmwareInfo.isA9())
        val tuiles = listOf(
            Triple(AudioSource.RADIO,      R.id.sc_audio_radio,      R.id.sc_audio_radio_rank),
            Triple(AudioSource.BLUETOOTH,  R.id.sc_audio_bluetooth,  R.id.sc_audio_bluetooth_rank),
            Triple(AudioSource.USB,        R.id.sc_audio_usb,        R.id.sc_audio_usb_rank),
            Triple(AudioSource.ONLINE,     R.id.sc_audio_online,     R.id.sc_audio_online_rank),
            Triple(AudioSource.PROJECTION, R.id.sc_audio_projection, R.id.sc_audio_projection_rank),
        ).mapNotNull { (source, bouton, rang) ->
            val btn = view.findViewById<MaterialButton>(bouton) ?: return@mapNotNull null
            if (source !in offertes) {
                // La tuile ET son cadre : les autres se partagent la rangée.
                (btn.parent as? View)?.visibility = View.GONE
                return@mapNotNull null
            }
            Triple(source, btn, view.findViewById<TextView>(rang))
        }
        val resume = view.findViewById<TextView>(R.id.tv_audio_cycle_summary)
        val save   = view.findViewById<MaterialButton>(R.id.btn_audio_cycle_save)

        val texteActif   = requireContext().getColor(R.color.text_active)
        val texteInactif = requireContext().getColor(R.color.text_secondary)

        fun maj() {
            tuiles.forEach { (source, btn, pastille) ->
                val rang = sourceCycleSel.indexOf(source)
                val on   = rang >= 0
                btn.backgroundTintList = ColorStateList.valueOf(if (on) accentColor else defColor)
                btn.setTextColor(if (on) texteActif else texteInactif)
                btn.iconTint = ColorStateList.valueOf(if (on) texteActif else texteInactif)
                pastille?.visibility = if (on) View.VISIBLE else View.GONE
                pastille?.text = "${rang + 1}"
            }
            val assez = sourceCycleSel.size >= AudioSourceCycle.MIN_SOURCES
            resume?.text = if (assez) getString(R.string.shortcuts_cfg_regen_summary,
                sourceCycleSel.joinToString(" → ") { getString(it.label) })
            else getString(R.string.shortcuts_cfg_audio_min)
            save?.isEnabled = assez
            save?.alpha     = if (assez) 1f else 0.4f
        }

        tuiles.forEach { (source, btn, _) ->
            btn.setOnClickListener {
                if (!sourceCycleSel.remove(source)) sourceCycleSel.add(source)
                maj()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_audio_cycle_clear)?.setOnClickListener {
            sourceCycleSel.clear()
            maj()
        }
        save?.setOnClickListener {
            if (sourceCycleSel.size < AudioSourceCycle.MIN_SOURCES) return@setOnClickListener
            AudioSourceCycle.save(requireContext(), sourceCycleSel)
            AppLogger.i("MG4_SHORTCUTS", "Cycle de sources enregistré : ${sourceCycleSel.joinToString(" → ") { it.name }}")
            Toast.makeText(requireContext(), R.string.shortcuts_cfg_regen_saved, Toast.LENGTH_SHORT).show()
        }

        rechargerCycleSources = {
            sourceCycleSel.clear()
            sourceCycleSel.addAll(AudioSourceCycle.order(requireContext()))
            maj()
        }
        rechargerCycleSources?.invoke()
    }

    /**
     * Sous-onglets en pastilles — même motif que l'éditeur de profil et les Réglages, à ceci près que
     * plusieurs entrées vont et viennent selon ce que l'utilisateur a attribué.
     *
     * Deux familles d'entrées, deux règles :
     *  • les onglets « Raccourcis » et « Avancés » existent tant que leur page a du contenu ;
     *  • les quatre pages de RÉGLAGE (1 Pédale, ADAS, cycles de régénération et de profils) existent quand leur
     *    fonction est attribuée à un bouton, quelle que soit la voie — c'est [actionEnJeu] qui
     *    tranche, et rien d'autre.
     */
    private fun bindCategoryRail(view: View) {
        // La page « Raccourcis » ne contient plus que les emplacements : les réglages d'action
        // y étaient des sections, ce qui les rendait dépendants d'un onglet où l'utilisateur ne
        // passe pas forcément. Chacun a désormais sa page.
        val tabs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_sc_cat_classic)  to view.findViewById<ViewGroup>(R.id.page_sc_classic),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_onepedal) to view.findViewById<ViewGroup>(R.id.page_sc_onepedal),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_adas)     to view.findViewById<ViewGroup>(R.id.page_sc_adas),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_regen)    to view.findViewById<ViewGroup>(R.id.page_sc_regen),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_drivemode) to view.findViewById<ViewGroup>(R.id.page_sc_drivemode),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_profiles) to view.findViewById<ViewGroup>(R.id.page_sc_profiles),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_sources)  to view.findViewById<ViewGroup>(R.id.page_sc_sources),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_preclim)  to view.findViewById<ViewGroup>(R.id.page_sc_preclim),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_quick)    to view.findViewById<ViewGroup>(R.id.page_sc_quick),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_floating) to view.findViewById<ViewGroup>(R.id.page_sc_floating),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_custom)   to view.findViewById<ViewGroup>(R.id.page_sc_custom)
        )
        fun page(id: Int) = tabs.first { it.second.id == id }.second
        val pageCycle   = page(R.id.page_sc_regen)
        val pageProfils = page(R.id.page_sc_profiles)
        val pageSources = page(R.id.page_sc_sources)

        // Chaque page de réglage est adossée à une fonction : elle n'existe que si un bouton la
        // déclenche. C'est la règle qui manquait aux deux premières, dont les réglages ne
        // regardaient que les emplacements classiques.
        val pagesReglage = mapOf(
            page(R.id.page_sc_onepedal) to ShortcutAction.ONE_PEDAL,
            page(R.id.page_sc_adas)     to ShortcutAction.ADAS_CYCLE,
            pageCycle                   to ShortcutAction.REGEN_CYCLE,
            page(R.id.page_sc_drivemode) to ShortcutAction.DRIVE_MODE_CYCLE,
            pageProfils                 to ShortcutAction.PROFILE_CYCLE,
            pageSources                 to ShortcutAction.AUDIO_SOURCE_CYCLE,
            page(R.id.page_sc_preclim)  to ShortcutAction.PRE_CLIMATE,
            page(R.id.page_sc_quick)    to ShortcutAction.QUICK_SETTINGS
        )
        val scroll = view.findViewById<ScrollView>(R.id.scroll_shortcuts)
        val enabledRow = view.findViewById<View?>(R.id.sc_enabled_row)

        fun hasVisibleContent(page: ViewGroup): Boolean =
            (0 until page.childCount).any { page.getChildAt(it).visibility == View.VISIBLE }

        // Les pages de réglage ont TOUJOURS du contenu : ce n'est pas lui qui décide de leur
        // existence, mais le fait que leur fonction soit en jeu.
        fun utilisable(page: ViewGroup): Boolean {
            val action = pagesReglage[page] ?: return hasVisibleContent(page)
            // L'ADAS ne se règle pas là où le firmware ne l'expose pas : la page offrirait des
            // crans dont aucun ne serait écrit.
            if (action == ShortcutAction.ADAS_CYCLE && !adasSupported) return false
            // Garde-voiture : toujours là sur un firmware reconnu — la tuile du sélecteur de
            // profils le lance sans aucun bouton attribué, et ses réglages doivent rester
            // atteignables (retour utilisateur du 2026-11-04).
            if (action == ShortcutAction.PRE_CLIMATE) return FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN
            if (action == ShortcutAction.QUICK_SETTINGS && quickForcee) return true
            return actionEnJeu(action)
        }

        fun apply() {
            val usable = tabs.filter { (_, page) -> utilisable(page) }
            tabs.forEach { (btn, page) ->
                val ok = usable.any { it.second === page }
                btn.visibility = if (ok) View.VISIBLE else View.GONE
                // Une page devenue inutilisable ne doit pas RESTER affichée : la fonction vient
                // peut-être d'être retirée depuis un autre onglet, et deux pages se
                // superposeraient dans le défilement.
                if (!ok) page.visibility = View.GONE
            }
            // L'onglet courant vient d'être masqué (action retirée) → retomber sur le premier.
            if (usable.none { it.second.visibility == View.VISIBLE }) {
                usable.firstOrNull()?.let { (_, page) -> page.visibility = View.VISIBLE }
            }
            tabs.forEach { (btn, page) ->
                btn.setCategoryTabActive(page.visibility == View.VISIBLE)
            }
            // L'interrupteur « Activer » n'a sa place que sur le premier sous-onglet, « Boutons
            // physiques », la seule page qu'il commande.
            enabledRow?.visibility = if (tabs.first().second.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            // Page des boutons flottants à l'écran : les bulles montrées sont celles du profil qu'elle règle.
            floatingPage.surPageVisible(isResumed && tabs.first { it.second.id == R.id.page_sc_floating }.second.visibility == View.VISIBLE)
            // Aucune entrée n'est masquée par le CONTEXTE : la liste reste atteignable depuis
            // n'importe quel onglet, et les pages de réglage ne dépendent que de l'attribution
            // de leur fonction. La règle de contexte qui cachait la liste hors de l'onglet
            // avancé obligeait à un détour pour la consulter.
        }

        tabs.forEach { (btn, page) ->
            btn.setOnClickListener {
                tabs.forEach { (_, p) -> p.visibility = if (p === page) View.VISIBLE else View.GONE }
                // Revenir sur la page du cycle repart de ce qui est ENREGISTRÉ : une composition
                // abandonnée sans « Sauvegarder » ne doit pas se faire passer pour le réglage
                // en vigueur.
                if (page === pageCycle) rechargerCycleRegen?.invoke()
                if (page.id == R.id.page_sc_drivemode) rechargerCycleModes?.invoke()
                // Idem pour le cycle de profils, qui relit en plus la liste des profils.
                if (page === pageProfils) rechargerCycleProfils?.invoke()
                if (page === pageSources) rechargerCycleSources?.invoke()
                if (page.id == R.id.page_sc_custom) customPage.peindre()
                if (page.id == R.id.page_sc_quick) quickPage.refresh()
                scroll?.scrollTo(0, 0)
                apply()
            }
        }
        reselectTabs = { apply() }
        tabs.first().second.visibility = View.VISIBLE
        tabs.drop(1).forEach { (_, p) -> p.visibility = View.GONE }
        apply()
    }

    /**
     * Construit la liste de labels pour un slot.
     * - OPEN_CUSTOM_APP : affiche "Ouvrir [AppName]" si une app est sauvegardée.
     * - APPLY_PROFILE   : affiche "▶ [NomProfil]" si un profil est sauvegardé.
     */
    // ── Raccourcis perso (issue #172) ────────────────────────────────────────

    /** « Perso : Dashcam : enregistrer », ou le nom générique s'il n'y en a pas (ou plus). */
    private fun libellePerso(slotKey: String): String =
        CustomShortcutSettings.get(requireContext(), prefs.getString("shortcut_${slotKey}_custom_id", null))
            ?.let { getString(R.string.shortcuts_custom_prefix) + " " + it.name }
            ?: getString(R.string.shortcuts_action_custom)

    /**
     * Choisir le raccourci perso d'un emplacement du volant — « Créer… » au bout de la liste l'ouvre
     * et l'attribue aussitôt. Rien de choisi alors que rien ne l'était : retour à « aucune action ».
     */
    private fun choisirPersoAvance(slot: String, apres: () -> Unit) {
        val cle = "shortcut_${slot}_custom_id"
        CustomShortcutDialog.choisir(requireContext(), prefs.getString(cle, null)) { c ->
            prefs.edit().putString(cle, c.id).apply()
            customPage.peindre()
            apres()
        }
    }

    /** Retourne le label de l'application (packageName) ou null si introuvable. */
    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(info).toString()
            getString(R.string.shortcuts_open_custom_prefix) + " " + appName
        } catch (_: Exception) { null }
    }

    // ── Config buttons (1 Pédale / AEB / ADAS) ───────────────────────────

    private fun setupConfigListeners(view: View) {
        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            // Seul un geste de l'utilisateur enregistre : la restauration pose l'état sans passer
            // ici ([restauration]). L'ancien filtre `isPressed` ne voyait jamais le SegmentSwitch
            // de la refonte (il ne passe pas par l'état « appuyé ») : Oui n'était jamais
            // enregistré et revenait à Non au retour sur l'écran (testeur SWI173, 2026-11-03).
            if (restauration) return@setOnCheckedChangeListener
            saveBoolean("shortcut_enabled", checked)
            applyEnabledUI(checked)
        }

        // One Pedal — regen de retour
        setupConfigRow("shortcut_one_pedal_fallback", RegenLevel.HIGH.value, view,
            R.id.sc_fallback_off      to RegenLevel.OFF.value,
            R.id.sc_fallback_low      to RegenLevel.LOW.value,
            R.id.sc_fallback_medium   to RegenLevel.MEDIUM.value,
            R.id.sc_fallback_high     to RegenLevel.HIGH.value,
            R.id.sc_fallback_adaptive to RegenLevel.ADAPTIVE.value
        )

        // ADAS — modes A et B : tous les firmwares connus utilisent les indices 0-4
        // (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA). La conversion index→hardware est faite dans le service.
        setupConfigRow("shortcut_adas_mode_a", 0, view,
            R.id.sc_adas_a_0 to 0, R.id.sc_adas_a_1 to 1, R.id.sc_adas_a_2 to 2,
            R.id.sc_adas_a_3 to 3, R.id.sc_adas_a_4 to 4
        )
        setupConfigRow("shortcut_adas_mode_b", 3, view,
            R.id.sc_adas_b_0 to 0, R.id.sc_adas_b_1 to 1, R.id.sc_adas_b_2 to 2,
            R.id.sc_adas_b_3 to 3, R.id.sc_adas_b_4 to 4
        )
    }

    private fun setupConfigRow(
        prefKey: String,
        defaultValue: Int,
        view: View,
        vararg pairs: Pair<Int, Int>
    ) {
        val buttons = pairs.associate { (resId, value) ->
            value to view.findViewById<MaterialButton>(resId)
        }
        buttons.forEach { (value, btn) ->
            btn?.setOnClickListener {
                saveInt(prefKey, value)
                highlightConfig(buttons, value)
            }
        }
        highlightConfig(buttons, prefs.getInt(prefKey, defaultValue))
    }

    // ── Restauration de l'état ────────────────────────────────────────────

    private fun restoreState() {
        val enabled = prefs.getBoolean("shortcut_enabled", false)
        restauration = true
        switchEnabled?.isChecked = enabled
        restauration = false
        applyEnabledUI(enabled)
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    /**
     * L'interrupteur « Activer » ne commande que les boutons physiques (2026-11-03) : seule leur
     * page est grisée. Les autres sous-onglets — boutons flottants (qui ont leur propre
     * interrupteur), cycles, garde-voiture, raccourcis perso — restent utilisables.
     */
    private fun applyEnabledUI(enabled: Boolean) {
        view?.findViewById<View>(R.id.page_sc_classic)?.let {
            it.alpha = if (enabled) 1f else 0.35f
            setChildrenEnabled(it, enabled)
        }
    }

    private fun setChildrenEnabled(v: View?, enabled: Boolean) {
        if (v == null) return
        v.isEnabled = enabled
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setChildrenEnabled(v.getChildAt(i), enabled)
        }
    }

    /**
     * Page « Pré-climatisation » (issue #130) : décompte avant verrouillage, seuil de batterie et
     * écran pendant l'attente. Enregistré à chaque appui — rien à valider.
     */
    private fun setupPreClimatePage(view: View) {
        val ctx = requireContext()
        val tvLock = view.findViewById<TextView>(R.id.tv_preclim_lock)
        val tvSoc = view.findViewById<TextView>(R.id.tv_preclim_soc)
        val choix = mapOf(
            PreClimateScreenMode.NORMAL to view.findViewById<MaterialButton>(R.id.btn_preclim_screen_normal),
            PreClimateScreenMode.DIM to view.findViewById(R.id.btn_preclim_screen_dim),
            PreClimateScreenMode.OFF to view.findViewById(R.id.btn_preclim_screen_off),
        )
        fun peindre() {
            tvLock.text = getString(R.string.preclim_value_seconds, PreClimateSettings.lockSeconds(ctx))
            tvSoc.text = getString(R.string.preclim_value_percent, PreClimateSettings.minSoc(ctx))
            highlightConfig(choix.mapKeys { it.key.ordinal }, PreClimateSettings.screenMode(ctx).ordinal)
        }
        AppDialog.repeter(view.findViewById(R.id.btn_preclim_lock_minus)) {
            PreClimateSettings.setLockSeconds(ctx, PreClimateSettings.lockSeconds(ctx) - PreClimatePlan.LOCK_STEP_S); peindre()
        }
        AppDialog.repeter(view.findViewById(R.id.btn_preclim_lock_plus)) {
            PreClimateSettings.setLockSeconds(ctx, PreClimateSettings.lockSeconds(ctx) + PreClimatePlan.LOCK_STEP_S); peindre()
        }
        AppDialog.repeter(view.findViewById(R.id.btn_preclim_soc_minus)) {
            PreClimateSettings.setMinSoc(ctx, PreClimateSettings.minSoc(ctx) - PreClimatePlan.SOC_STEP); peindre()
        }
        AppDialog.repeter(view.findViewById(R.id.btn_preclim_soc_plus)) {
            PreClimateSettings.setMinSoc(ctx, PreClimateSettings.minSoc(ctx) + PreClimatePlan.SOC_STEP); peindre()
        }
        choix.forEach { (mode, bouton) -> bouton.setOnClickListener { PreClimateSettings.setScreenMode(ctx, mode); peindre() } }
        // Fiche sur l'écran conducteur : réservée aux firmwares où le combiné montre l'écran central.
        view.findViewById<View>(R.id.row_preclim_cluster).visibility =
            if (ClusterModuleSettings.isSupported()) View.VISIBLE else View.GONE
        view.findViewById<Switch>(R.id.switch_preclim_cluster).apply {
            isChecked = PreClimateSettings.showOnCluster(ctx)
            setOnCheckedChangeListener { _, coche ->
                PreClimateSettings.setShowOnCluster(ctx, coche)
                AppLogger.i(PreClimate.TAG, "fiche sur l'écran conducteur ${if (coche) "activée" else "désactivée"}")
                // Programme en cours : la fiche apparaît ou part tout de suite.
                ClusterModuleOverlay.refresh(ctx, "réglage de la pré-climatisation")
            }
        }
        view.findViewById<Switch>(R.id.switch_preclim_unlock).apply {
            isChecked = PreClimateSettings.unlockReminder(ctx)
            setOnCheckedChangeListener { _, coche ->
                PreClimateSettings.setUnlockReminder(ctx, coche)
                AppLogger.i(PreClimate.TAG, "rappel avant le départ ${if (coche) "activé" else "désactivé"}")
            }
        }
        peindre()
    }

    private fun highlightConfig(map: Map<Int, MaterialButton?>, active: Int) {
        val activeTextColor   = requireContext().getColor(R.color.text_active)
        val inactiveTextColor = requireContext().getColor(R.color.text_secondary)
        map.forEach { (value, btn) ->
            val isActive = value == active
            btn?.backgroundTintList = ColorStateList.valueOf(if (isActive) accentColor else defColor)
            btn?.setTextColor(if (isActive) activeTextColor else inactiveTextColor)
        }
    }

    // ── Prefs helpers ────────────────────────────────────────────────────

    private fun saveInt(key: String, value: Int)          = prefs.edit().putInt(key, value).apply()
    private fun saveBoolean(key: String, value: Boolean)  = prefs.edit().putBoolean(key, value).apply()
}
