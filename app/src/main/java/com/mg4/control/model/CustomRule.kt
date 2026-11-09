package com.mg4.control.model

import java.util.UUID

/**
 * Mes automatisations (2026-11-02, issue #201) — le modèle, sans Android.
 *
 * Une automatisation suit le schéma de Home Assistant : **Quand** (un déclencheur suffit),
 * **Et si** (un arbre de conditions ET / OU / NON), **Alors** (des actions dans l'ordre, avec des
 * blocs Si / Sinon et des attentes). Le moteur qui lit la voiture et exécute est
 * [com.mg4.control.automation.CustomRules] ; ici, seulement les données et les règles pures.
 *
 * Enregistré en JSON (Gson) : toutes les propriétés ont une valeur par défaut, donc un champ ajouté
 * plus tard se relit sur les anciennes sauvegardes. Une valeur d'énumération inconnue (sauvegarde
 * d'une version plus récente) revient `null` : la condition qui la porte est « inconnue », la
 * règle ne part pas, l'action est sautée — jamais d'écriture sur une donnée mal comprise.
 */
data class CustomRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val triggers: List<RuleTrigger> = emptyList(),
    /** Racine des conditions : un groupe « toutes » (vide = aucune contrainte). */
    val condition: RuleCondition = RuleCondition(RuleCondition.Type.ALL),
    val actions: List<RuleAction> = emptyList(),
    val ifRunning: IfRunning = IfRunning.IGNORE,
    /** Au plus une exécution toutes les N minutes ; 0 = pas de limite. */
    val cooldownMinutes: Int = 0,
    /** Ne part que voiture en READY. */
    val onlyWhenReady: Boolean = false,
) {
    /** Déclenchée alors qu'elle tourne encore (une attente en cours, par exemple). */
    enum class IfRunning { IGNORE, RESTART }
}

/**
 * Ce que le moteur sait lire. [kind] dit comment on le compare :
 * - BOOL : vrai / faux ;
 * - NUMBER : au-dessus / en dessous d'un nombre ;
 * - TEXT : égal / différent d'un texte (rapport `P`, `D` ; mode de conduite ; id de profil) ;
 * - TEXT_SET : contient / ne contient pas (adresses des téléphones Bluetooth connectés).
 */
enum class RuleSignal(val kind: Kind) {
    READY(Kind.BOOL),
    /** `P`, `D`, ou [GEAR_OTHER] : seules les valeurs P et D sont relevées à coup sûr. */
    GEAR(Kind.TEXT),
    SPEED(Kind.NUMBER),
    SOC(Kind.NUMBER),
    RANGE(Kind.NUMBER),
    CHARGING(Kind.BOOL),
    OUTSIDE_TEMP(Kind.NUMBER),
    HVAC_ON(Kind.BOOL),
    BT_DEVICE(Kind.TEXT_SET),
    PROFILE(Kind.TEXT),
    DRIVE_MODE(Kind.TEXT),

    // ── Lot 1 (étape 3, 2026-11-02) : ce que MG4Control lisait déjà ailleurs ──
    /** Niveau de récupération, nom de [RegenLevel]. */
    REGEN(Kind.TEXT),
    ONE_PEDAL(Kind.BOOL),
    ENERGY_SAVING(Kind.BOOL),
    AEB(Kind.BOOL),
    TSR(Kind.BOOL),
    ESC(Kind.BOOL),
    DROWSINESS(Kind.BOOL),
    OVERSPEED_ALARM(Kind.BOOL),
    AC_ON(Kind.BOOL),
    HVAC_AUTO(Kind.BOOL),
    HVAC_ECO(Kind.BOOL),
    HVAC_TEMP(Kind.NUMBER),
    FAN(Kind.NUMBER),
    /** `INNER`, `OUTSIDE` ou `AUTO`. */
    RECIRC(Kind.TEXT),
    DEFROST_FRONT(Kind.BOOL),
    DEFROST_REAR(Kind.BOOL),
    SEAT_HEAT_LEFT(Kind.NUMBER),
    SEAT_HEAT_RIGHT(Kind.NUMBER),
    STEERING_HEAT(Kind.BOOL),
    /** Chauffage intelligent de la batterie allumé (2026-11-06), là où la voiture l'a. */
    BATTERY_HEAT(Kind.BOOL),
    /** Une porte avant est ouverte (2026-11-06) : seulement là où la voiture la signale (SWI132, SWI133). */
    FRONT_DOOR(Kind.BOOL),
    LOCKED(Kind.BOOL),
    /** Clé qui s'éloigne (chargeur sans fil 0x2140f445 = 8, relevé SWI133 seulement). */
    KEY_AWAY(Kind.BOOL),
    BRIGHTNESS(Kind.NUMBER),
    VOLUME(Kind.NUMBER),
    /** Feux de position automatiques allumés : la nuit des écrans d'origine. */
    NIGHT(Kind.BOOL),
    /** Minutes depuis le dernier passage en READY (0 hors READY). */
    DRIVE_MINUTES(Kind.NUMBER),
    DATA_TODAY_MB(Kind.NUMBER),
    DATA_MONTH_MB(Kind.NUMBER),
    CHARGE_LIMIT(Kind.NUMBER),
    PLUGGED(Kind.BOOL),
    CHARGE_DC(Kind.BOOL),
    /** Nom de la génération de firmware (`SWI133`…). */
    FIRMWARE(Kind.TEXT),
    PROJECTION(Kind.BOOL),

    // ── Lot 2 : Android ──
    WIFI_ON(Kind.BOOL),
    WIFI_SSID(Kind.TEXT),
    BT_ON(Kind.BOOL),
    HOTSPOT_ON(Kind.BOOL),
    MEDIA_PLAYING(Kind.BOOL),
    IN_CALL(Kind.BOOL),
    /** Soleil au-dessus de l'horizon, à la position de la voiture. */
    SUN_UP(Kind.BOOL);

    enum class Kind { BOOL, NUMBER, TEXT, TEXT_SET }

    companion object {
        const val GEAR_PARK = "P"
        const val GEAR_DRIVE = "D"
        const val GEAR_OTHER = "OTHER"

        /** Rapport SAIC brut → texte comparable. P = 1, D = 4 (voir [SoundAutomationRules]). */
        fun gearText(raw: Int?): String? = when (raw) {
            null -> null
            SoundAutomationRules.GEAR_PARK -> GEAR_PARK
            SoundAutomationRules.GEAR_DRIVE -> GEAR_DRIVE
            else -> GEAR_OTHER
        }
    }
}

enum class RuleOp { IS_TRUE, IS_FALSE, ABOVE, BELOW, EQUALS, NOT_EQUALS }

/** Lever ou coucher du soleil, pour un déclencheur [RuleTrigger.Type.SUN]. */
enum class SunEvent { SUNRISE, SUNSET }

/** Événements ponctuels, sans valeur à comparer. */
enum class RuleEvent {
    /** Démarrage du service, donc de l'écran de la voiture. */
    APP_START,
    /** Une porte avant s'ouvre : seulement là où la voiture la signale (SWI132, SWI133). */
    FRONT_DOOR_OPEN,
    /** Les portes avant sont toutes refermées (2026-11-06) : mêmes firmwares que l'ouverture. */
    FRONT_DOOR_CLOSE,
}

/**
 * Un nœud de l'arbre des conditions.
 * - ALL / ANY / NOT : groupe de [children] (NOT = aucune n'est vraie) ;
 * - COMPARE : [signal] [op] [number] ou [text] ;
 * - TIME_RANGE : de [from] à [to], en minutes depuis minuit (peut passer minuit : 22:00 → 6:00) ;
 * - WEEKDAYS : jour dans [days], 1 = lundi … 7 = dimanche ;
 * - RANDOM : vrai [number] fois sur 100, tiré à chaque évaluation ;
 * - SEASON : période de l'année de [from] à [to], en mois × 100 + jour (1201 → 0228 passe l'an) ;
 * - VARIABLE : la variable [text] comparée par [op] à [number] (ou vraie / fausse) ;
 * - PLACE : la voiture est ([op] IS_TRUE) ou n'est pas (IS_FALSE) dans le lieu [text] (lot 2).
 */
data class RuleCondition(
    val type: Type? = null,
    val signal: RuleSignal? = null,
    val op: RuleOp? = null,
    val number: Double? = null,
    val text: String? = null,
    val from: Int? = null,
    val to: Int? = null,
    val days: List<Int>? = null,
    val children: List<RuleCondition>? = null,
    /** Valeur texte comparée à une variable (VARIABLE, EQUALS / NOT_EQUALS sans nombre). */
    val value: String? = null,
) {
    enum class Type { ALL, ANY, NOT, COMPARE, TIME_RANGE, WEEKDAYS, RANDOM, SEASON, VARIABLE, PLACE }

    val isGroup: Boolean get() = type == Type.ALL || type == Type.ANY || type == Type.NOT
}

/**
 * Un déclencheur.
 * - EVENT : [event] arrive ;
 * - BECOMES : [condition] PASSE de fausse à vraie (et le reste [forSeconds]) ;
 * - TIME : l'horloge arrive à [at] (minutes depuis minuit) ;
 * - PERIODIC : toutes les [every] minutes (étape 3) ;
 * - CHANGES : la valeur du signal de [condition] change (d'une valeur lue à une autre) ;
 * - MANUAL : lancée par un bouton du volant, une bulle ou une autre automatisation ;
 * - SUN : au lever ou au coucher du soleil [sun], décalé de [offset] minutes (lot 2).
 */
data class RuleTrigger(
    val type: Type? = null,
    val event: RuleEvent? = null,
    val condition: RuleCondition? = null,
    val at: Int? = null,
    val forSeconds: Int = 0,
    val every: Int? = null,
    val sun: SunEvent? = null,
    val offset: Int = 0,
) {
    enum class Type { EVENT, BECOMES, TIME, PERIODIC, CHANGES, MANUAL, SUN }
}

/**
 * Une action.
 * - SET : écriture [key] = [value], mêmes clés que l'API externe (`ExternalApi.SET_*`) ;
 * - SHORTCUT : un raccourci de l'appli, par le nom de son `ShortcutAction` ;
 * - PROFILE : appliquer le profil [profileId] ;
 * - WAIT : attendre [seconds] ;
 * - IF : si [condition], alors [then], sinon [otherwise] ;
 * - NOTIFY : un message [text] à l'écran ;
 * - STOP : arrêter l'automatisation ici ;
 * - REPEAT : [then] répété [count] fois (étape 2, plafonné par [RuleRun.MAX_REPEAT]) ;
 * - WAIT_UNTIL : attendre que [condition] soit vraie, [seconds] au plus, puis continuer ;
 * - CONFIRM : poser la question [text] (Oui / Non), [seconds] pour répondre ; Non arrête,
 *   sans réponse vaut Oui si [value] = "yes", sinon arrête ;
 * - PLAY_SOUND : le son n° [value] des sons d'automatisation ([text] = son nom) ;
 * - OPEN_APP : ouvrir l'appli [text] (nom de paquet) ;
 * - CUSTOM_SHORTCUT : lancer le raccourci perso [text] (identifiant) ;
 * - RULE_ENABLE : activer ([value] = "1") ou couper ("0") l'automatisation [text] ;
 * - RUN_RULE : lancer l'automatisation [text] (ses conditions s'appliquent) ;
 * - SET_VARIABLE : la variable [key] prend la valeur [value] ;
 * - SPEAK : lire [text] à voix haute (lot 2) ;
 * - NAVIGATE : ouvrir la navigation vers le lieu [text] (identifiant ou « lat,lng », lot 2) ;
 * - ABRP_START : envoi continu des données à ABRP vers le compte [text] (issue #188) ;
 * - ABRP_STOP : arrêter l'envoi à ABRP ;
 * - SAVE_VALUE : la variable [key] prend la valeur LUE du signal [text] (2026-11-06) : vrai/faux
 *   en « 1 » / « 0 », nombre, texte. Un SET dont la valeur est « $NOM » écrit celle de la variable.
 *
 * - VARIABLE_ADD : la variable [key] (nombre, 0 si absente) augmente de [value] (négatif = retire) ;
 * - RULE_STOP : arrêter l'automatisation [text] si elle est en cours (elle reste active) ;
 * - MEDIA : [value] = « play » (reprendre si rien ne joue) ou « pause » (mettre en pause si ça joue).
 *
 * PLAY_SOUND (2026-11-06) : [key] = « beep » (bip intégré, [count] = volume en %) ou
 * « rule:<id> » (son importé dans l'éditeur, [count] = volume média, absent = inchangé) ;
 * sinon [value] = n° d'un son de la page Sons, comme avant.
 */
data class RuleAction(
    val type: Type? = null,
    val key: String? = null,
    val value: String? = null,
    val shortcut: String? = null,
    val profileId: String? = null,
    val seconds: Int? = null,
    val text: String? = null,
    val condition: RuleCondition? = null,
    val then: List<RuleAction>? = null,
    val otherwise: List<RuleAction>? = null,
    val count: Int? = null,
) {
    enum class Type {
        SET, SHORTCUT, PROFILE, WAIT, IF, NOTIFY, STOP, REPEAT,
        WAIT_UNTIL, CONFIRM, PLAY_SOUND, OPEN_APP, CUSTOM_SHORTCUT, RULE_ENABLE, RUN_RULE, SET_VARIABLE,
        SPEAK, NAVIGATE,
        ABRP_START, ABRP_STOP,
        SAVE_VALUE,
        VARIABLE_ADD, RULE_STOP, MEDIA,
        // 2026-11-09 : actions Android (AndroidActions) — [value] = home / back ; [text] = paquet ; [value] = page.
        ANDROID_KEY, CLOSE_APP, OPEN_SETTINGS,
    }
}

/**
 * Dernière exécution d'une automatisation. [atMs] : heure murale, `null` si l'horloge n'était pas
 * encore plausible (réveil sur l'horloge d'usine) ; [done] / [failed] : actions réussies, refusées ;
 * [journal] : une ligne par action, dans la langue de l'appli (le Journal de l'éditeur).
 * Dans `model` comme le reste : enregistré par Gson, il doit garder ses noms une fois minifié.
 */
/**
 * Une ligne de l'historique (les 20 dernières, toutes automatisations) : une exécution ([run]), ou
 * depuis le 2026-11-08 un déclenchement refusé ([skip] non nul, [run] alors sans objet).
 */
data class RuleHistoryEntry(
    val id: String = "",
    val name: String = "",
    val run: RuleLastRun = RuleLastRun(),
    val skip: RuleSkip? = null,
)

data class RuleLastRun(
    val atMs: Long? = null,
    val done: Int = 0,
    val failed: Int = 0,
    val journal: List<String>? = null,
    /** Ce qui l'a déclenchée, dans la langue de l'appli (« Le profil actif change »). */
    val by: String? = null,
)

/**
 * Déclenchement refusé (2026-11-08) : la règle est partie, mais ne s'est pas exécutée.
 * [detail] : pour des conditions fausses ou illisibles, la première en cause, en texte ;
 * [value] : ce qui a été lu pour elle ; [minutes] : l'anti-rebond. [count] : refus identiques
 * regroupés en une ligne (×N) — le texte de [reason] est écrit à l'affichage.
 */
data class RuleSkip(
    val atMs: Long? = null,
    val by: String? = null,
    val reason: Reason? = null,
    val detail: String? = null,
    val value: String? = null,
    val minutes: Int? = null,
    val count: Int = 1,
) {
    enum class Reason { CONDITIONS, UNKNOWN, NOT_READY, BUSY, COOLDOWN, GARAGE }

    /** Même refus, à la valeur lue près : il se regroupe avec le précédent. */
    fun sameAs(o: RuleSkip): Boolean = reason == o.reason && detail == o.detail && minutes == o.minutes
}

/**
 * Compteurs d'une règle (variante 3, 2026-11-08) : exécutions lancées par un déclencheur ou à la
 * main (pas « Tester ») et déclenchements ignorés, chaque refus compté même regroupé (×N) dans
 * l'historique. Depuis la création ; une modification les garde, supprimer ou importer repart de zéro.
 */
data class RuleCounts(val runs: Int = 0, val skips: Int = 0) {
    fun plusRun() = copy(runs = runs + 1)
    fun plusSkip() = copy(skips = skips + 1)
}

/** Historique : ajout d'une exécution, ou d'un refus regroupé avec le dernier refus identique. */
object RuleHistory {

    fun addRun(list: List<RuleHistoryEntry>, e: RuleHistoryEntry, max: Int): List<RuleHistoryEntry> =
        (listOf(e) + list).take(max)

    /**
     * Un refus identique au DERNIER enregistré pour cette règle (rien d'autre d'elle entre-temps)
     * prend sa place : compteur + 1, heure et valeur lue du nouveau, et remonte en tête. Sinon il
     * s'ajoute. Une règle « toutes les 5 min » aux conditions fausses tient ainsi sur une ligne.
     */
    fun addSkip(list: List<RuleHistoryEntry>, e: RuleHistoryEntry, max: Int): List<RuleHistoryEntry> {
        val s = e.skip ?: return addRun(list, e, max)
        val i = list.indexOfFirst { it.id == e.id }
        val prev = list.getOrNull(i)?.skip
        if (prev == null || !prev.sameAs(s)) return addRun(list, e, max)
        val fusion = e.copy(skip = s.copy(count = prev.count.coerceAtLeast(1) + 1))
        return (listOf(fusion) + list.filterIndexed { j, _ -> j != i }).take(max)
    }

    enum class Filter { ALL, RUNS, SKIPPED }

    fun filter(list: List<RuleHistoryEntry>, f: Filter): List<RuleHistoryEntry> = when (f) {
        Filter.ALL -> list
        Filter.RUNS -> list.filter { it.skip == null }
        Filter.SKIPPED -> list.filter { it.skip != null }
    }

    /** Le refus est-il plus récent que la dernière exécution ? (la carte ne montre alors que lui en plus) */
    fun skipIsNewer(run: RuleLastRun?, skip: RuleSkip?): Boolean {
        val s = skip?.atMs ?: return skip != null && run == null
        val r = run?.atMs ?: return true
        return s > r
    }
}

/**
 * Ce que le moteur a lu au moment d'évaluer. Seuls les signaux utiles aux règles actives sont lus
 * (lectures binder) ; un signal absent ou illisible vaut `null`, c'est-à-dire « inconnu ».
 * Valeurs : Boolean, Double, String ou Set<String> selon [RuleSignal.kind].
 */
data class RuleState(
    val values: Map<RuleSignal, Any?> = emptyMap(),
    /** Minutes depuis minuit ; `null` tant que l'horloge n'est pas plausible (réveil à ~2019). */
    val minuteOfDay: Int? = null,
    /** 1 = lundi … 7 = dimanche ; `null` avec l'horloge. */
    val weekday: Int? = null,
    /** Mois × 100 + jour (1225 = 25 décembre) ; `null` avec l'horloge. */
    val monthDay: Int? = null,
    /** Variables de l'utilisateur, en texte (« 12 », « true »). */
    val variables: Map<String, String> = emptyMap(),
    /** Tirage 0 ≤ x < 100 pour les conditions au hasard ; `null` = pas tiré (inconnu). */
    val chance: Double? = null,
    /** Dans chaque lieu ? par identifiant ; absent ou `null` = position inconnue. */
    val places: Map<String, Boolean?> = emptyMap(),
)
