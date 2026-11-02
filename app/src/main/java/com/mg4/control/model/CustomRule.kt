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
    DRIVE_MODE(Kind.TEXT);

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

/** Événements ponctuels, sans valeur à comparer. */
enum class RuleEvent {
    /** Démarrage du service, donc de l'écran de la voiture. */
    APP_START,
    /** Une porte avant s'ouvre : seulement là où la voiture la signale (SWI132, SWI133). */
    FRONT_DOOR_OPEN,
}

/**
 * Un nœud de l'arbre des conditions.
 * - ALL / ANY / NOT : groupe de [children] (NOT = aucune n'est vraie) ;
 * - COMPARE : [signal] [op] [number] ou [text] ;
 * - TIME_RANGE : de [from] à [to], en minutes depuis minuit (peut passer minuit : 22:00 → 6:00) ;
 * - WEEKDAYS : jour dans [days], 1 = lundi … 7 = dimanche.
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
) {
    enum class Type { ALL, ANY, NOT, COMPARE, TIME_RANGE, WEEKDAYS }

    val isGroup: Boolean get() = type == Type.ALL || type == Type.ANY || type == Type.NOT
}

/**
 * Un déclencheur.
 * - EVENT : [event] arrive ;
 * - BECOMES : [condition] PASSE de fausse à vraie (et le reste [forSeconds]) ;
 * - TIME : l'horloge arrive à [at] (minutes depuis minuit).
 */
data class RuleTrigger(
    val type: Type? = null,
    val event: RuleEvent? = null,
    val condition: RuleCondition? = null,
    val at: Int? = null,
    val forSeconds: Int = 0,
) {
    enum class Type { EVENT, BECOMES, TIME }
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
 * - REPEAT : [then] répété [count] fois (étape 2, plafonné par [RuleRun.MAX_REPEAT]).
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
    enum class Type { SET, SHORTCUT, PROFILE, WAIT, IF, NOTIFY, STOP, REPEAT }
}

/**
 * Dernière exécution d'une automatisation. [atMs] : heure murale, `null` si l'horloge n'était pas
 * encore plausible (réveil sur l'horloge d'usine) ; [done] / [failed] : actions réussies, refusées ;
 * [journal] : une ligne par action, dans la langue de l'appli (le Journal de l'éditeur).
 * Dans `model` comme le reste : enregistré par Gson, il doit garder ses noms une fois minifié.
 */
data class RuleLastRun(
    val atMs: Long? = null,
    val done: Int = 0,
    val failed: Int = 0,
    val journal: List<String>? = null,
)

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
)
