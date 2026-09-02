package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.accessibility.AdvancedShortcuts
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.RegenCycle
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.util.FirmwareInfo

class ShortcutsFragment : Fragment() {

    /** Interrupteur des raccourcis avances. Defaut false : la voie classique reste la norme. */
    private val PREF_ADV_SHORTCUTS = "advanced_shortcuts_enabled"

    private val PREFS = "mg4_shortcuts"

    private lateinit var prefs: SharedPreferences
    private var accentColor = 0
    private var defColor    = 0

    private var switchEnabled:   Switch? = null
    private var shortcutsContent: View?  = null

    /** Éléments disponibles dans les Spinners — calculés une fois selon le firmware. */
    private data class ActionItem(val label: String, val action: ShortcutAction)

    /** Liste de base (sans label custom) — partagée pour tous les spinners. */
    private var baseActionItems: List<ActionItem> = emptyList()

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

    /**
     * Fonction sélectionnée dans le formulaire des raccourcis avancés, avant création.
     *
     * Champ et non variable locale : le rail doit pouvoir la consulter pour révéler le réglage
     * d'une fonction PENDANT qu'on compose le raccourci — voir [actionEnJeu].
     */
    private var actionChoisie: ShortcutAction? = null

    /** Rejoue l'affichage de la page du cycle (état des modes, du résumé et du bouton). */
    private var majCycleRegen: (() -> Unit)? = null

    /** Repart de la séquence ENREGISTRÉE, en jetant une composition non sauvegardée. */
    private var rechargerCycleRegen: (() -> Unit)? = null

    // ── Par-spinner : label list mutable + adapter + vue ─────────────────
    private val spinnerLabelLists = mutableMapOf<String, MutableList<String>>()
    private val spinnerAdapters   = mutableMapOf<String, ArrayAdapter<String>>()
    private val spinnerViews      = mutableMapOf<String, Spinner>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_shortcuts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs       = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        accentColor = requireContext().getColor(R.color.accent_eco)
        defColor    = requireContext().getColor(R.color.bg_button)

        switchEnabled    = view.findViewById(R.id.switch_shortcuts_enabled)
        shortcutsContent = view.findViewById(R.id.shortcuts_content)

        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132

        // ── Construction des items de base selon firmware ─────────────────
        baseActionItems = buildList {
            add(ActionItem(getString(R.string.shortcuts_action_none),           ShortcutAction.NONE))
            add(ActionItem(getString(R.string.shortcuts_action_one_pedal),      ShortcutAction.ONE_PEDAL))
            add(ActionItem(getString(R.string.shortcuts_action_regen_cycle),    ShortcutAction.REGEN_CYCLE))
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_aeb),        ShortcutAction.AEB_CYCLE))
            }
            // SWI68/69/131/165 : une seule alerte sonore VSM
            if (isVsmBased && !isSWI132) {
                add(ActionItem(getString(R.string.shortcuts_action_sound),      ShortcutAction.SOUND_WARNING))
            }
            // SWI133 + SWI132 : deux alertes indépendantes (survitesse + ton limite)
            if ((!isVsmBased || isSWI132) && isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_overspeed),  ShortcutAction.OVERSPEED_ALARM))
                add(ActionItem(getString(R.string.shortcuts_action_speed_limit),ShortcutAction.SPEED_LIMIT_TONE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_adas),       ShortcutAction.ADAS_CYCLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_energy_saving), ShortcutAction.ENERGY_SAVING_TOGGLE))
            }
            if (isKnown) {
                add(ActionItem(getString(R.string.shortcuts_action_tsr), ShortcutAction.TSR_TOGGLE))
            }
            // ESC + somnolence : même condition que la carte du Dashboard, sinon on
            // proposerait un raccourci qui échouerait en silence sur les firmwares non câblés.
            if (MG4Hardware.hasDrowsinessAndEsc()) {
                add(ActionItem(getString(R.string.shortcuts_action_esc),             ShortcutAction.ESC_TOGGLE))
                add(ActionItem(getString(R.string.shortcuts_action_drowsiness),      ShortcutAction.DROWSINESS_TOGGLE))
                add(ActionItem(getString(R.string.shortcuts_action_drowsiness_sen),  ShortcutAction.DROWSINESS_SEN_CYCLE))
            }
            // Chauffages : aucun filtre firmware, exactement comme la carte du Dashboard qui les
            // affiche partout. Ils passent par des propriétés véhicule dont la lecture nullable
            // tranche avant d'agir — au pire le raccourci reste sans effet, jamais une écriture
            // à l'aveugle.
            add(ActionItem(getString(R.string.shortcuts_action_seat_heat_left),  ShortcutAction.SEAT_HEAT_LEFT_CYCLE))
            add(ActionItem(getString(R.string.shortcuts_action_seat_heat_right), ShortcutAction.SEAT_HEAT_RIGHT_CYCLE))
            add(ActionItem(getString(R.string.shortcuts_action_steering_heat),   ShortcutAction.STEERING_HEAT_TOGGLE))
            if (MG4Hardware.hasClimateControl()) {
                add(ActionItem(getString(R.string.shortcuts_action_hvac_toggle),     ShortcutAction.HVAC_TOGGLE))
                add(ActionItem(getString(R.string.shortcuts_action_hvac_temp_up),    ShortcutAction.HVAC_TEMP_UP))
                add(ActionItem(getString(R.string.shortcuts_action_hvac_temp_down),  ShortcutAction.HVAC_TEMP_DOWN))
                add(ActionItem(getString(R.string.shortcuts_action_hvac_fan_up),     ShortcutAction.HVAC_FAN_UP))
                add(ActionItem(getString(R.string.shortcuts_action_hvac_fan_down),   ShortcutAction.HVAC_FAN_DOWN))
                add(ActionItem(getString(R.string.shortcuts_action_defrost_front),   ShortcutAction.DEFROST_FRONT_TOGGLE))
                add(ActionItem(getString(R.string.shortcuts_action_defrost_rear),    ShortcutAction.DEFROST_REAR_TOGGLE))
                add(ActionItem(getString(R.string.shortcuts_action_hvac_recirc),     ShortcutAction.HVAC_RECIRC_CYCLE))
            }
            if (MG4Hardware.hasBrightnessControl()) {
                add(ActionItem(getString(R.string.shortcuts_action_brightness_up),   ShortcutAction.BRIGHTNESS_UP))
                add(ActionItem(getString(R.string.shortcuts_action_brightness_down), ShortcutAction.BRIGHTNESS_DOWN))
            }
            // Média : aucun filtre firmware — ça ne passe pas par le véhicule mais par la
            // session média d'Android, identique sur les six firmwares.
            add(ActionItem(getString(R.string.shortcuts_action_media_next),      ShortcutAction.MEDIA_NEXT))
            add(ActionItem(getString(R.string.shortcuts_action_media_prev),      ShortcutAction.MEDIA_PREVIOUS))
            add(ActionItem(getString(R.string.shortcuts_action_media_play_pause), ShortcutAction.MEDIA_PLAY_PAUSE))
            // Volume : pas de filtre non plus — la voie SAIC est tentée d'abord, AudioManager
            // prend le relais ailleurs, donc il y a toujours un chemin.
            add(ActionItem(getString(R.string.shortcuts_action_volume_up),       ShortcutAction.VOLUME_UP))
            add(ActionItem(getString(R.string.shortcuts_action_volume_down),     ShortcutAction.VOLUME_DOWN))
            add(ActionItem(getString(R.string.shortcuts_action_apply_profile),   ShortcutAction.APPLY_PROFILE))
            add(ActionItem(getString(R.string.shortcuts_action_profile_picker), ShortcutAction.PROFILE_PICKER))
            add(ActionItem(getString(R.string.shortcuts_action_open_app),       ShortcutAction.OPEN_APP))
            add(ActionItem(getString(R.string.shortcuts_action_open_custom_app),ShortcutAction.OPEN_CUSTOM_APP))
            if (MG4Hardware.hasVehiclePowerOff()) {
                add(ActionItem(getString(R.string.shortcuts_action_vehicle_power_off), ShortcutAction.VEHICLE_POWER_OFF))
            }
        }

        // ── Affichage des sections de config selon firmware ───────────────
        // Tous les firmwares connus utilisent la config 5 modes (Off/Lim.Manuel/Lim.Auto/ACC/ICA|TJA).
        adasSupported = isKnown
        view.findViewById<View>(R.id.config_adas_swi133)?.visibility  = if (isKnown) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.config_adas_swi68)?.visibility   = View.GONE

        // ── Bouton Fermer ─────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_shortcuts_close)?.setOnClickListener {
            findNavController().popBackStack(R.id.dashboardFragment, false)
        }

        setupSpinners(view)
        setupConfigListeners(view)
        setupRegenCycle(view)
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

    // ═════════════════════════════════════════════════════════════════════════
    //  Raccourcis avancés — interception avant le launcher
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Étape 1 : le service OBSERVE les touches sans en consommer aucune.
     *
     * Le toggle n'active pas le service d'accessibilité — Android l'interdit, seul l'utilisateur
     * peut le faire depuis les réglages système. Le toggle enregistre donc une INTENTION, et
     * l'état réel du service est affiché à côté, relu à chaque retour à l'écran : sans ça
     * l'utilisateur croirait avoir activé la fonction alors que rien n'écoute.
     */
    private var advancedRefresh: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        // L utilisateur revient peut-etre des reglages d accessibilite : l etat affiche doit
        // suivre, sinon il resterait sur "inactif" apres avoir active le service.
        advancedRefresh?.invoke()
    }

    override fun onDestroyView() {
        // Le listener est statique : ne pas le liberer retiendrait ce Fragment detruit.
        KeyCaptureService.listener = null
        advancedRefresh = null
        // Même raison : cette lambda capture les vues du rail. Elle a désormais plusieurs
        // appelants (spinner avancé, création, suppression), donc plusieurs occasions d'être
        // invoquée après la destruction de la vue si on la laissait en place.
        reselectTabs = null
        majCycleRegen = null
        rechargerCycleRegen = null
        rootView = null
        super.onDestroyView()
    }

    private fun setupAdvancedShortcuts(view: View) {
        val sw      = view.findViewById<Switch>(R.id.switch_adv_shortcuts)
        val status  = view.findViewById<TextView>(R.id.tv_adv_status)
        val btnAcc  = view.findViewById<MaterialButton>(R.id.btn_adv_accessibility)
        val cardRec = view.findViewById<View>(R.id.card_adv_record)
        val btnRec  = view.findViewById<MaterialButton>(R.id.btn_adv_record)
        val tvKey   = view.findViewById<TextView>(R.id.tv_adv_last_key)
        val btnSimple = view.findViewById<MaterialButton>(R.id.btn_adv_press_single)
        val btnLong   = view.findViewById<MaterialButton>(R.id.btn_adv_press_long)
        val btnDouble = view.findViewById<MaterialButton>(R.id.btn_adv_press_double)
        val spinner = view.findViewById<Spinner>(R.id.spinner_adv_action)
        val spinnerProfil = view.findViewById<Spinner>(R.id.spinner_adv_profile)

        // Toutes les actions sont proposées. « Ouvrir une app » et « Appliquer un profil »
        // réclament un choix supplémentaire : il est demandé juste après, et le raccourci n'est
        // enregistré QUE si ce choix aboutit — sinon on créerait un raccourci qui ne fait rien.
        val actionsAvancees = baseActionItems
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            actionsAvancees.map { it.label }
        )

        // La liste est construite UNE FOIS : créer un profil oblige à quitter cet écran, dont
        // la vue est alors détruite puis reconstruite — le spinner repart donc à jour.
        val itemsProfils = itemsProfils()
        spinnerProfil.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            itemsProfils.map { it.first }
        )

        var toucheChoisie: Int? = null
        var typeAppui = PressType.SINGLE
        // `null` = tous les profils, première entrée du spinner et valeur par défaut : sans
        // toucher à cette étape, on retrouve exactement le comportement d'avant.
        var profilChoisi: String? = null

        val actif   = requireContext().getColor(R.color.dash_accent_dim)
        val inactif = requireContext().getColor(R.color.dash_btn)
        val txtOn   = requireContext().getColor(R.color.dash_accent)
        val txtOff  = requireContext().getColor(R.color.text_secondary)

        fun majAppui() {
            listOf(btnSimple to PressType.SINGLE, btnLong to PressType.LONG,
                   btnDouble to PressType.DOUBLE).forEach { (b, t) ->
                val on = t == typeAppui
                b.backgroundTintList = ColorStateList.valueOf(if (on) actif else inactif)
                b.setTextColor(if (on) txtOn else txtOff)
            }
        }

        fun majEtat() {
            val serviceOn = KeyCaptureService.isEnabled(requireContext())
            status.setText(if (serviceOn) R.string.adv_sc_status_on else R.string.adv_sc_status_off)
            // Le bouton disparait une fois le service accorde : il n'a plus rien a demander.
            // Desactiver la fonctionnalite passe par l'interrupteur ci-dessus, pas par les
            // reglages Android — au repos le service ne consomme aucune touche.
            btnAcc.visibility = if (serviceOn) View.GONE else View.VISIBLE
            // Enregistrer n'a aucun sens tant que le service ne tourne pas : rien n'arriverait,
            // et l'utilisateur croirait que sa touche n'est pas reconnue.
            val utilisable = serviceOn && sw.isChecked
            cardRec.alpha = if (utilisable) 1f else 0.35f
            listOf<View>(btnRec, btnSimple, btnLong, btnDouble, spinner, spinnerProfil,
                view.findViewById(R.id.btn_adv_create)).forEach { it.isEnabled = utilisable }
            refreshAdvancedList(view)
        }

        // ⚠️ L'état est posé AVANT l'écouteur : sinon l'avertissement d'activation ci-dessous
        // s'afficherait à chaque ouverture de l'écran, sans que l'utilisateur ait rien demandé.
        sw.isChecked = AdvancedShortcuts.isEnabled(requireContext())
        sw.setOnCheckedChangeListener { _, checked ->
            // Désactivation : rien à expliquer, les touches sont rendues au système.
            if (!checked) {
                AdvancedShortcuts.setEnabled(requireContext(), false)
                majEtat()
                return@setOnCheckedChangeListener
            }

            // Activation : on prévient AVANT d'armer quoi que ce soit. Qu'un bouton du volant
            // perde sa fonction d'origine ne se devine pas depuis un interrupteur, et se
            // découvrirait au volant — c'est exactement ce qui est arrivé à un testeur avec la
            // touche Accueil, qui a cru à un plantage.
            //
            // Rien n'est enregistré tant que l'utilisateur n'a pas confirmé : annuler, ou fermer
            // la boîte de dialogue, remet l'interrupteur sur off — ce qui repasse par ce même
            // écouteur avec `checked = false`, donc par la désactivation propre ci-dessus.
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.adv_sc_enable_warn_title)
                .setMessage(R.string.adv_sc_enable_warn_msg)
                .setPositiveButton(R.string.adv_sc_enable_warn_ok) { _, _ ->
                    AdvancedShortcuts.setEnabled(requireContext(), true)
                    majEtat()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> sw.isChecked = false }
                .setOnCancelListener { _ -> sw.isChecked = false }
                .show()
        }

        btnAcc.setOnClickListener {
            // La liste des services d'accessibilité, pas notre entrée : le lien direct n'est pas
            // une API publique et varie d'un constructeur à l'autre.
            val ouvert = runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }.getOrDefault(false)
            if (!ouvert) {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                AppLogger.w("MG4_KEYCAP", "réglages d'accessibilité inaccessibles — repli Réglages")
            }
        }

        // L'enregistrement consomme la touche : il doit donc pouvoir être interrompu autrement
        // qu'en appuyant sur une touche. D'où le second appui qui annule, et l'expiration
        // automatique si l'utilisateur passe à autre chose.
        var enregistrementEnCours = false
        val finEnregistrement = Runnable {
            enregistrementEnCours = false
            KeyCaptureService.listener = null
            if (isAdded) {
                btnRec.setText(R.string.adv_sc_record)
                Toast.makeText(requireContext(), R.string.adv_sc_record_cancelled,
                    Toast.LENGTH_SHORT).show()
            }
        }

        btnRec.setOnClickListener {
            if (enregistrementEnCours) {
                view.removeCallbacks(finEnregistrement)
                finEnregistrement.run()
                return@setOnClickListener
            }
            enregistrementEnCours = true
            view.postDelayed(finEnregistrement, 15_000)
            btnRec.setText(R.string.adv_sc_recording)
            KeyCaptureService.listener = { keyCode ->
                // Le service tourne sur son propre thread : revenir à l'UI avant de toucher aux
                // vues, et se débrancher aussitôt pour ne capter qu'une seule touche.
                view.post {
                    enregistrementEnCours = false
                    view.removeCallbacks(finEnregistrement)
                    if (isAdded) {
                        toucheChoisie = keyCode
                        tvKey.text = libelleTouche(keyCode)
                        btnRec.setText(R.string.adv_sc_record)
                    }
                }
                KeyCaptureService.listener = null
            }
        }

        btnSimple.setOnClickListener { typeAppui = PressType.SINGLE; majAppui() }
        btnLong.setOnClickListener   { typeAppui = PressType.LONG;   majAppui() }
        btnDouble.setOnClickListener { typeAppui = PressType.DOUBLE; majAppui() }

        // La sélection ne fait plus qu'ENREGISTRER le choix. Valider ici imposait un ordre
        // (touche puis fonction) : choisir la fonction en premier ne produisait rien du tout,
        // pas même le sélecteur d'app ou de profil, à cause du retour anticipé.
        actionChoisie = null
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                actionChoisie = actionsAvancees.getOrNull(pos)?.action?.takeIf { it != ShortcutAction.NONE }
                // Certaines fonctions ont un réglage à elles : le rail doit le révéler dès la
                // sélection. Attendre la création obligerait à revenir sur ses pas pour régler
                // un raccourci qu'on vient tout juste de poser.
                reselectTabs?.invoke()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {
                actionChoisie = null
                reselectTabs?.invoke()
            }
        }

        spinnerProfil.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                profilChoisi = itemsProfils.getOrNull(pos)?.second
            }
            override fun onNothingSelected(p: AdapterView<*>?) { profilChoisi = null }
        }

        view.findViewById<MaterialButton>(R.id.btn_adv_create).setOnClickListener {
            val touche = toucheChoisie
            val action = actionChoisie
            // Dire CE QUI MANQUE plutôt que de ne rien faire : c'est le silence qui rendait
            // l'écran incompréhensible quand on s'y prenait dans l'autre sens.
            if (touche == null) {
                Toast.makeText(requireContext(), R.string.adv_sc_need_key, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (action == null) {
                Toast.makeText(requireContext(), R.string.adv_sc_need_action, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profil = profilChoisi
            val enregistrer = {
                AdvancedShortcuts.set(requireContext(), touche, typeAppui, action, profil)
                AppLogger.i("MG4_KEYCAP", "raccourci avancé enregistré : touche=$touche " +
                    "${typeAppui.key} → ${action.name} (profil ${profil ?: "tous"})")
                Toast.makeText(requireContext(), R.string.adv_sc_saved, Toast.LENGTH_SHORT).show()
                toucheChoisie = null
                actionChoisie = null
                tvKey.setText(R.string.adv_sc_none)
                spinner.setSelection(0, false)
                spinnerProfil.setSelection(0, false)
                refreshAdvancedList(view)
            }

            // Les deux actions à cible réclament un choix supplémentaire ; annuler laisse le
            // formulaire en l'état plutôt que de créer un raccourci sans destination.
            val poursuivre = {
                val slot = AdvancedShortcuts.slotKey(touche, typeAppui, profil)
                when (action) {
                    ShortcutAction.OPEN_CUSTOM_APP -> choisirAppAvancee(slot, enregistrer)
                    ShortcutAction.APPLY_PROFILE   -> choisirProfilAvance(slot, enregistrer)
                    else -> enregistrer()
                }
            }

            // Un couple (touche, type d'appui) ne peut pas exister en double : c'est la clé de
            // stockage elle-même. La deuxième attribution écrasait donc la première EN SILENCE
            // — l'utilisateur croyait ajouter un raccourci, il en remplaçait un. On le dit, et
            // on nomme la fonction perdue : sans elle, impossible de décider en connaissance.
            // Le couple qui doit rester unique est désormais un TRIPLET : la même touche et le
            // même appui peuvent porter plusieurs raccourcis, un par profil. C'est tout l'objet
            // de la fonctionnalité — le garde-fou descend simplement d'un cran.
            val existante = AdvancedShortcuts.actionFor(requireContext(), touche, typeAppui, profil)
            if (existante == null) {
                poursuivre()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.adv_sc_replace_title)
                .setMessage(getString(R.string.adv_sc_replace_msg,
                    libelleTouche(touche),
                    // Le profil est accolé au type d'appui plutôt que de multiplier les
                    // variantes de message : « Appui court · Sport » se lit sans explication.
                    getString(libellePress(typeAppui)) + " · " + libelleProfil(profil),
                    libelleAction(AdvancedShortcuts.Mapping(touche, typeAppui, existante, profil))))
                .setPositiveButton(R.string.adv_sc_replace_ok) { _, _ -> poursuivre() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        majAppui()
        majEtat()
        advancedRefresh = ::majEtat
    }

    /**
     * Sélecteurs propres aux raccourcis avancés.
     *
     * Volontairement distincts de [showAppPickerDialog] / [showProfilePickerDialog] : ceux-là
     * rafraîchissent les spinners de l'écran classique et remettraient un emplacement à NONE en
     * cas d'annulation. Les réutiliser ici aurait modifié les raccourcis classiques au passage.
     */
    private fun choisirAppAvancee(slot: String, apres: () -> Unit) {
        val pm = requireContext().packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }
        val libelles = apps.map { it.loadLabel(pm).toString() }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_app_title)
            .setItems(libelles) { _, i ->
                prefs.edit()
                    .putString("shortcut_${slot}_custom_app", apps[i].activityInfo.packageName)
                    .apply()
                apres()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choisirProfilAvance(slot: String, apres: () -> Unit) {
        val profils = ProfileManager(requireContext()).getAll()
        if (profils.isEmpty()) {
            Toast.makeText(requireContext(), R.string.shortcuts_no_profiles, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_profile_title)
            .setItems(profils.map { it.name }.toTypedArray()) { _, i ->
                prefs.edit().putString("shortcut_${slot}_profile_id", profils[i].id).apply()
                apres()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Libellé d'une ligne de liste. Pour les deux actions à cible, on affiche la CIBLE et non
     * l'action générique : « Waze » plutôt que « Ouvrir une app », sans quoi deux raccourcis
     * différents seraient indiscernables.
     */
    private fun libelleAction(m: AdvancedShortcuts.Mapping): String {
        val slot = AdvancedShortcuts.slotKey(m.keyCode, m.press, m.profileId)
        val generique = baseActionItems.firstOrNull { it.action == m.action }?.label ?: m.action.name
        return when (m.action) {
            ShortcutAction.OPEN_CUSTOM_APP ->
                prefs.getString("shortcut_${slot}_custom_app", null)
                    ?.let { resolveAppLabel(it) } ?: generique
            ShortcutAction.APPLY_PROFILE ->
                prefs.getString("shortcut_${slot}_profile_id", null)
                    ?.let { id -> ProfileManager(requireContext()).getById(id)?.name } ?: generique
            else -> generique
        }
    }

    /**
     * Les entrées du spinner de profil : « Tous les profils » d'abord, puis les profils
     * existants. La valeur portée est l'identifiant, `null` pour la première.
     */
    private fun itemsProfils(): List<Pair<String, String?>> =
        listOf(getString(R.string.adv_sc_all_profiles) to null) +
        ProfileManager(requireContext()).getAll().map { it.name to it.id }

    /**
     * Nom du profil d'un raccourci.
     *
     * Un identifiant qui ne correspond plus à rien est DIT, pas masqué : c'est ce qui arrive
     * quand on supprime un profil, et le raccourci resté derrière ne se déclenchera jamais.
     * Mieux vaut le voir dans la liste et pouvoir l'effacer.
     */
    private fun libelleProfil(id: String?): String {
        if (id == null) return getString(R.string.adv_sc_all_profiles)
        return ProfileManager(requireContext()).getById(id)?.name
            ?: getString(R.string.adv_sc_profile_missing)
    }

    /**
     * Identité d'un bouton : son nom quand on le connaît, et TOUJOURS son code.
     *
     * Le code n'est pas décoratif — c'est lui qu'on demande dans les remontées de bug, et le
     * seul repère pour une touche que [AdvancedShortcuts.nomTouche] ne connaît pas encore.
     */
    private fun libelleTouche(code: Int): String =
        AdvancedShortcuts.nomTouche(code)?.let { "$it ($code)" }
            ?: getString(R.string.adv_sc_key_unknown, code)

    /**
     * Vocabulaire d'appui propre aux raccourcis avancés — « appui court / long / double »,
     * comme les trois boutons du formulaire. L'écran classique garde le sien (Simple / Long /
     * Double), plus compact parce qu'il tient dans une colonne de tableau.
     */
    private fun libellePress(press: PressType): Int = when (press) {
        PressType.SINGLE -> R.string.adv_sc_press_short
        PressType.LONG   -> R.string.adv_sc_press_long_lbl
        PressType.DOUBLE -> R.string.adv_sc_press_double
    }

    /**
     * Change la FONCTION et le PROFIL d'un raccourci existant, sans retoucher ni la touche ni le
     * type d'appui — refaire les étapes pour corriger l'un des deux n'avait pas de raison d'être,
     * et obligeait à réappuyer sur un bouton que le service consomme.
     *
     * Les deux réglages sont dans la MÊME boîte. Deux dialogues enchaînés auraient été plus
     * simples, mais on ne peut alors plus revenir sur le premier choix après avoir vu le second,
     * et annuler à mi-chemin laisse un raccourci à moitié modifié.
     */
    private fun modifierRaccourci(m: AdvancedShortcuts.Mapping, vue: View) {
        // Même liste que le formulaire, filtres firmware compris : proposer ici une fonction
        // absente du firmware fabriquerait un raccourci sans effet.
        val choixActions = baseActionItems.filter { it.action != ShortcutAction.NONE }
        val choixProfils = itemsProfils()

        val contenu = layoutInflater.inflate(R.layout.dialog_edit_shortcut, null)
        val spAction = contenu.findViewById<Spinner>(R.id.spinner_edit_action)
        val spProfil = contenu.findViewById<Spinner>(R.id.spinner_edit_profile)
        spAction.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, choixActions.map { it.label })
        spProfil.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, choixProfils.map { it.first })
        choixActions.indexOfFirst { it.action == m.action }
            .takeIf { it >= 0 }?.let { spAction.setSelection(it) }
        choixProfils.indexOfFirst { it.second == m.profileId }
            .takeIf { it >= 0 }?.let { spProfil.setSelection(it) }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.adv_sc_edit_title)
            .setView(contenu)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val action = choixActions.getOrNull(spAction.selectedItemPosition)?.action
                    ?: return@setPositiveButton
                val profil = choixProfils.getOrNull(spProfil.selectedItemPosition)?.second

                // Changer de profil DÉPLACE le raccourci, sa clé de stockage en dépendant. Si la
                // destination est déjà occupée on refuse : écraser en silence ferait disparaître
                // un raccourci que l'utilisateur ne regardait même pas.
                if (profil != m.profileId &&
                    AdvancedShortcuts.actionFor(requireContext(), m.keyCode, m.press, profil) != null) {
                    Toast.makeText(requireContext(), R.string.adv_sc_edit_conflict,
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val enregistrer = {
                    // L'ancienne entrée d'abord : sans ça un changement de profil laisserait
                    // deux raccourcis là où l'utilisateur croit n'en avoir déplacé qu'un.
                    if (profil != m.profileId) {
                        AdvancedShortcuts.remove(requireContext(), m.keyCode, m.press, m.profileId)
                    }
                    AdvancedShortcuts.set(requireContext(), m.keyCode, m.press, action, profil)
                    AppLogger.i("MG4_KEYCAP", "raccourci avancé modifié : touche=${m.keyCode} " +
                        "${m.press.key} → ${action.name} (profil ${profil ?: "tous"})")
                    Toast.makeText(requireContext(), R.string.adv_sc_updated, Toast.LENGTH_SHORT).show()
                    refreshAdvancedList(vue)
                }
                // Mêmes cibles à choisir que dans le formulaire : basculer sur « ouvrir une
                // app » sans désigner laquelle donnerait un raccourci qui n'ouvre rien.
                val slot = AdvancedShortcuts.slotKey(m.keyCode, m.press, profil)
                when (action) {
                    ShortcutAction.OPEN_CUSTOM_APP -> choisirAppAvancee(slot, enregistrer)
                    ShortcutAction.APPLY_PROFILE   -> choisirProfilAvance(slot, enregistrer)
                    else -> enregistrer()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reconstruit la liste des raccourcis avancés, GROUPÉE par touche + type d'appui.
     *
     * Le regroupement porte l'information : à l'intérieur d'un groupe, les lignes sont dans
     * l'ordre où elles sont résolues à l'appui — « tous les profils » en tête, variantes en
     * dessous. Une liste plate laisserait deviner laquelle l'emporte.
     */
    private fun refreshAdvancedList(view: View) {
        val conteneur = view.findViewById<ViewGroup>(R.id.container_adv_list) ?: return
        val vide      = view.findViewById<View>(R.id.tv_adv_empty)
        conteneur.removeAllViews()
        val items = AdvancedShortcuts.all(requireContext())
        vide?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        // `all` rend déjà la liste triée, et groupBy conserve l'ordre de rencontre : les groupes
        // comme leurs lignes sortent donc dans le bon ordre sans second tri.
        items.groupBy { it.keyCode to it.press }.forEach { (cle, lignes) ->
            val (code, press) = cle
            val entete = layoutInflater.inflate(R.layout.item_advanced_group, conteneur, false)
            entete.findViewById<TextView>(R.id.adv_group_title).text = getString(
                R.string.adv_sc_group_title, libelleTouche(code), getString(libellePress(press)))
            // Sans ligne « tous les profils », la touche reste réclamée sous les autres profils
            // mais n'y fait rien — conséquence directe du fait qu'une touche se réclame en bloc.
            entete.findViewById<View>(R.id.adv_group_warning).visibility =
                if (lignes.any { it.profileId == null }) View.GONE else View.VISIBLE
            conteneur.addView(entete)

            lignes.forEach { m ->
                val ligne = layoutInflater.inflate(R.layout.item_advanced_shortcut, conteneur, false)
                ligne.findViewById<TextView>(R.id.adv_item_profile).text =
                    if (m.profileId == null) getString(R.string.adv_sc_all_profiles)
                    else "\u2514 " + libelleProfil(m.profileId)
                ligne.findViewById<TextView>(R.id.adv_item_action).text = libelleAction(m)
                ligne.findViewById<View>(R.id.adv_item_edit).setOnClickListener {
                    modifierRaccourci(m, view)
                }
                ligne.findViewById<View>(R.id.adv_item_delete).setOnClickListener {
                    AdvancedShortcuts.remove(requireContext(), m.keyCode, m.press, m.profileId)
                    refreshAdvancedList(view)
                }
                conteneur.addView(ligne)
            }
        }

        // Créer, modifier ou supprimer un raccourci peut faire apparaître ou disparaître le
        // réglage d'une fonction. Ce point de passage est commun aux trois, d'où l'appel ici
        // plutôt que recopié à chaque endroit.
        reselectTabs?.invoke()
    }

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
        if (actionChoisie == action) return true
        if (slotPressList.any {
                ShortcutAction.fromId(prefs.getInt("shortcut_$it", 0)) == action
            }) return true
        return AdvancedShortcuts.all(requireContext()).any { it.action == action }
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
     * Rail de gauche — même motif que l'éditeur de profil et les Réglages, à ceci près que
     * plusieurs entrées vont et viennent selon ce que l'utilisateur a attribué.
     *
     * Deux familles d'entrées, deux règles :
     *  • les onglets « Raccourcis » et « Avancés » existent tant que leur page a du contenu ;
     *  • les trois pages de RÉGLAGE (1 Pédale, ADAS, cycle de régénération) existent quand leur
     *    fonction est attribuée à un bouton, quelle que soit la voie — c'est [actionEnJeu] qui
     *    tranche, et rien d'autre.
     */
    private fun bindCategoryRail(view: View) {
        // La page « Raccourcis » ne contient plus que les emplacements : les réglages d'action
        // y étaient des sections, ce qui les rendait dépendants d'un onglet où l'utilisateur ne
        // passe pas forcément. Chacun a désormais sa page.
        val tabs = listOf(
            view.findViewById<MaterialButton>(R.id.btn_sc_cat_classic)  to view.findViewById<ViewGroup>(R.id.page_sc_classic),
            view.findViewById<MaterialButton>(R.id.btn_sc_cat_advanced) to view.findViewById<ViewGroup>(R.id.page_sc_advanced),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_onepedal) to view.findViewById<ViewGroup>(R.id.page_sc_onepedal),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_adas)     to view.findViewById<ViewGroup>(R.id.page_sc_adas),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_regen)    to view.findViewById<ViewGroup>(R.id.page_sc_regen),
            view.findViewById<MaterialButton>(R.id.btn_sc_sub_list)     to view.findViewById<ViewGroup>(R.id.page_sc_list)
        )
        val pageCycle = tabs[4].second

        // Chaque page de réglage est adossée à une fonction : elle n'existe que si un bouton la
        // déclenche. C'est la règle qui manquait aux deux premières, dont les réglages ne
        // regardaient que les emplacements classiques.
        val pagesReglage = mapOf(
            tabs[2].second to ShortcutAction.ONE_PEDAL,
            tabs[3].second to ShortcutAction.ADAS_CYCLE,
            tabs[4].second to ShortcutAction.REGEN_CYCLE
        )
        setupAdvancedShortcuts(view)
        val scroll = view.findViewById<ScrollView>(R.id.scroll_shortcuts)
        // Le rail reprend l'accent des deux autres écrans refondus (dash_accent), pas l'accent vert
        // propre aux boutons de cet écran : c'est le même composant de navigation partout.
        val dimColor = requireContext().getColor(R.color.dash_accent_dim)
        val railOn   = requireContext().getColor(R.color.dash_accent)
        val railOff  = requireContext().getColor(R.color.dash_btn)
        val border   = requireContext().getColor(R.color.dash_border)
        val textOff  = requireContext().getColor(R.color.text_secondary)

        fun hasVisibleContent(page: ViewGroup): Boolean =
            (0 until page.childCount).any { page.getChildAt(it).visibility == View.VISIBLE }

        // Les pages de réglage ont TOUJOURS du contenu : ce n'est pas lui qui décide de leur
        // existence, mais le fait que leur fonction soit en jeu.
        fun utilisable(page: ViewGroup): Boolean {
            val action = pagesReglage[page] ?: return hasVisibleContent(page)
            // L'ADAS ne se règle pas là où le firmware ne l'expose pas : la page offrirait des
            // crans dont aucun ne serait écrit.
            if (action == ShortcutAction.ADAS_CYCLE && !adasSupported) return false
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
                val on = page.visibility == View.VISIBLE
                btn.backgroundTintList = ColorStateList.valueOf(if (on) dimColor else railOff)
                btn.setTextColor(if (on) railOn else textOff)
                btn.strokeColor = ColorStateList.valueOf(if (on) railOn else border)
            }
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
                scroll?.scrollTo(0, 0)
                apply()
            }
        }
        reselectTabs = { apply() }
        tabs.first().second.visibility = View.VISIBLE
        tabs.drop(1).forEach { (_, p) -> p.visibility = View.GONE }
        apply()
    }

    // ── Spinners (un adapter par spinner) ────────────────────────────────

    private fun setupSpinners(view: View) {
        for (slotKey in slotPressList) {
            val spinnerId = resources.getIdentifier("spinner_$slotKey", "id", requireContext().packageName)
            val spinner   = view.findViewById<Spinner>(spinnerId) ?: continue

            // Construire la liste de labels pour ce slot (OPEN_CUSTOM_APP peut avoir un label custom)
            val labels = buildLabelsFor(slotKey)
            spinnerLabelLists[slotKey] = labels
            spinnerViews[slotKey]      = spinner

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAdapters[slotKey] = adapter
            spinner.adapter = adapter

            // Sélection initiale
            val savedAction = ShortcutAction.fromId(prefs.getInt("shortcut_$slotKey", 0))
            val position    = baseActionItems.indexOfFirst { it.action == savedAction }.coerceAtLeast(0)
            spinner.setSelection(position)

            // Listener positionné APRÈS pour ignorer le callback auto de setSelection.
            // Le flag `initialized` absorbe le premier onItemSelected automatique (sélection initiale).
            spinner.post {
                var initialized = false
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        val action = baseActionItems[pos].action
                        saveInt("shortcut_$slotKey", action.id)
                        // Le réglage lié à l'action doit apparaître (ou disparaître) tout de suite.
                        reselectTabs?.invoke()
                        if (initialized) {
                            when (action) {
                                ShortcutAction.OPEN_CUSTOM_APP -> showAppPickerDialog(slotKey)
                                ShortcutAction.APPLY_PROFILE   -> showProfilePickerDialog(slotKey)
                                else -> {}
                            }
                        }
                        initialized = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
        }
    }

    /**
     * Construit la liste de labels pour un slot.
     * - OPEN_CUSTOM_APP : affiche "Ouvrir [AppName]" si une app est sauvegardée.
     * - APPLY_PROFILE   : affiche "▶ [NomProfil]" si un profil est sauvegardé.
     */
    private fun buildLabelsFor(slotKey: String): MutableList<String> {
        val savedPkg = prefs.getString("shortcut_${slotKey}_custom_app", null)
        val customAppLabel = if (savedPkg != null) {
            resolveAppLabel(savedPkg) ?: getString(R.string.shortcuts_action_open_custom_app)
        } else {
            getString(R.string.shortcuts_action_open_custom_app)
        }

        val savedProfileId = prefs.getString("shortcut_${slotKey}_profile_id", null)
        val profileLabel = if (savedProfileId != null) {
            val profile = ProfileManager(requireContext()).getById(savedProfileId)
            if (profile != null) getString(R.string.shortcuts_profile_prefix) + " " + profile.name
            else getString(R.string.shortcuts_action_apply_profile)
        } else {
            getString(R.string.shortcuts_action_apply_profile)
        }

        return baseActionItems.map { item ->
            when (item.action) {
                ShortcutAction.OPEN_CUSTOM_APP -> customAppLabel
                ShortcutAction.APPLY_PROFILE   -> profileLabel
                else                           -> item.label
            }
        }.toMutableList()
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

    // ── Dialog de sélection d'application ────────────────────────────────

    private fun showAppPickerDialog(slotKey: String) {
        val pm = requireContext().packageManager

        // Récupérer toutes les apps launchables, triées par label
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launchIntent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val labels   = resolveList.map { it.loadLabel(pm).toString() }.toTypedArray()
        val packages = resolveList.map { it.activityInfo.packageName }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_app_title)
            .setItems(labels) { _, which ->
                val pkg      = packages[which]
                val appName  = labels[which]
                val newLabel = getString(R.string.shortcuts_open_custom_prefix) + " " + appName

                prefs.edit().putString("shortcut_${slotKey}_custom_app", pkg).apply()
                updateCustomAppLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Si aucune app n'était sauvegardée → revenir à NONE
                if (prefs.getString("shortcut_${slotKey}_custom_app", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    // ── Dialog de sélection de profil ────────────────────────────────────────

    private fun showProfilePickerDialog(slotKey: String) {
        val profiles = ProfileManager(requireContext()).getAll()

        if (profiles.isEmpty()) {
            // Aucun profil créé → revenir à NONE
            val spinner = spinnerViews[slotKey] ?: return
            spinner.setSelection(0)
            saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.shortcuts_no_profiles)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = profiles.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_pick_profile_title)
            .setItems(labels) { _, which ->
                val profile  = profiles[which]
                val newLabel = getString(R.string.shortcuts_profile_prefix) + " " + profile.name
                prefs.edit().putString("shortcut_${slotKey}_profile_id", profile.id).apply()
                updateProfileLabel(slotKey, newLabel)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Annulation sans profil préalablement sauvegardé → revenir à NONE
                if (prefs.getString("shortcut_${slotKey}_profile_id", null) == null) {
                    val spinner = spinnerViews[slotKey] ?: return@setNegativeButton
                    spinner.setSelection(0)
                    saveInt("shortcut_$slotKey", ShortcutAction.NONE.id)
                }
            }
            .show()
    }

    /** Met à jour le label APPLY_PROFILE dans l'adapter du spinner concerné. */
    private fun updateProfileLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.APPLY_PROFILE }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        spinner.setSelection(idx)
    }

    /** Met à jour le label OPEN_CUSTOM_APP dans l'adapter du spinner concerné. */
    private fun updateCustomAppLabel(slotKey: String, newLabel: String) {
        val labels  = spinnerLabelLists[slotKey] ?: return
        val spinner = spinnerViews[slotKey]      ?: return
        val adapter = spinnerAdapters[slotKey]   ?: return

        val idx = baseActionItems.indexOfFirst { it.action == ShortcutAction.OPEN_CUSTOM_APP }
        if (idx < 0) return

        labels[idx] = newLabel
        adapter.notifyDataSetChanged()
        // S'assurer que le spinner affiche le bon item sélectionné
        spinner.setSelection(idx)
    }

    // ── Config buttons (1 Pédale / AEB / ADAS) ───────────────────────────

    private fun setupConfigListeners(view: View) {
        switchEnabled?.setOnCheckedChangeListener { _, checked ->
            if (switchEnabled?.isPressed == true) {
                saveBoolean("shortcut_enabled", checked)
                applyEnabledUI(checked)
                if (checked) showShortcutWarning()
            }
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
        switchEnabled?.isChecked = enabled
        applyEnabledUI(enabled)
    }

    // ── Helpers UI ───────────────────────────────────────────────────────

    private fun applyEnabledUI(enabled: Boolean) {
        shortcutsContent?.alpha = if (enabled) 1f else 0.35f
        setChildrenEnabled(shortcutsContent, enabled)
        // setChildrenEnabled réactive TOUT, « Sauvegarder » compris. Or son état ne dépend pas
        // de l'interrupteur global mais du nombre de modes choisis : il faut le lui rendre.
        if (enabled) majCycleRegen?.invoke()
    }

    private fun setChildrenEnabled(v: View?, enabled: Boolean) {
        if (v == null) return
        v.isEnabled = enabled
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setChildrenEnabled(v.getChildAt(i), enabled)
        }
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

    private fun showShortcutWarning() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shortcuts_warning_title)
            .setMessage(R.string.shortcuts_warning_message)
            .setPositiveButton(R.string.shortcuts_warning_ok, null)
            .show()
    }

    // ── Prefs helpers ────────────────────────────────────────────────────

    private fun saveInt(key: String, value: Int)          = prefs.edit().putInt(key, value).apply()
    private fun saveBoolean(key: String, value: Boolean)  = prefs.edit().putBoolean(key, value).apply()
}
