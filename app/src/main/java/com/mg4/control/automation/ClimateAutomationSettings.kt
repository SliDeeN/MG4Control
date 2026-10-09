package com.mg4.control.automation

import android.content.Context

/**
 * Réglages de l'automatisation « Déclenchement A/C via la température ».
 *
 * Deux règles INDÉPENDANTES : une quand il fait chaud (temp ≥ seuil), une quand il fait froid
 * (temp ≤ seuil). Chacune porte ses propres réglages clim — les mêmes valeurs n'auraient aucun
 * sens à 35 °C et à −5 °C.
 *
 * Volontairement séparée d'[AutomationSettings] : ce n'est pas une application de profil, elle
 * n'est donc PAS soumise au toggle « application auto du profil » et a son propre interrupteur.
 */
object ClimateAutomationSettings {

    const val PREFS = "mg4_settings"

    const val KEY_ENABLED = "ac_auto_enabled"
    /** Mode de déclenchement : le nom d'un [Trigger]. */
    const val KEY_TRIGGER = "ac_auto_trigger"
    /**
     * Ancienne case « une seule fois par démarrage », d'avant le choix à trois positions. Plus
     * jamais écrite ; lue seulement pour reprendre le réglage de qui l'avait touchée.
     */
    private const val KEY_ONCE_LEGACY = "ac_auto_once"

    // Une règle = un préfixe ; les clés sont dérivées pour éviter douze constantes quasi jumelles.
    private const val HOT  = "ac_auto_hot_"
    private const val COLD = "ac_auto_cold_"

    const val DEFAULT_HOT_THRESHOLD  = 28
    const val DEFAULT_COLD_THRESHOLD = 5
    const val DEFAULT_HOT_TARGET     = 20
    const val DEFAULT_COLD_TARGET    = 24
    const val DEFAULT_FAN            = 4
    /** Recyclage par defaut quand l'utilisateur force : AUTO, le plus neutre des trois. */
    const val DEFAULT_LOOP           = 2

    /** Bornes de saisie — larges à dessein, les vraies limites du véhicule sont lues au moment
     *  d'appliquer (getClimateState) et la consigne y est clampée. */
    const val MIN_TEMP = -20
    const val MAX_TEMP = 60
    const val MIN_TARGET = 15
    const val MAX_TARGET = 33
    const val MIN_FAN = 1
    const val MAX_FAN = 10

    /** Une règle : sa condition de déclenchement et les réglages clim à appliquer. */
    data class Rule(
        val active: Boolean,
        val threshold: Int,
        val targetTemp: Int,
        val fanLevel: Int,
        val defrostFront: Boolean,
        val defrostRear: Boolean,
        /**
         * Mode automatique de la ventilation. Quand il est actif, [fanLevel] n'est PAS appliqué :
         * choisir une vitesse manuelle fait sortir du mode auto sur ce véhicule, donc appliquer
         * les deux donnerait un résultat dépendant du seul ordre des appels.
         */
        val autoMode: Boolean,
        /**
         * Recyclage d'air : `null` = ne pas y toucher, sinon 0=Intérieur, 1=Extérieur, 2=Auto.
         *
         * Le `null` par défaut est délibéré : les automatisations déjà configurées par les
         * utilisateurs ne doivent pas se mettre à piloter un réglage qu'elles ne pilotaient pas.
         */
        val loopMode: Int?
    )

    /**
     * Quand l'automatisation a le droit de se déclencher. « Démarrage » s'entend ici comme le
     * démarrage de l'application, donc de l'écran de bord — pas un simple retour en READY.
     *
     * Le besoin vient de l'issue #121 : couper le compresseur avant d'arriver pour sécher
     * l'évaporateur, descendre ouvrir un portail, et ne pas le voir se rallumer en remontant.
     */
    enum class Trigger {
        /**
         * Une seule décision, à la première évaluation où la température est lisible : que le
         * seuil soit atteint ou non, plus rien ensuite. Le défaut.
         */
        START_ONLY,
        /**
         * Reste armée tant qu'aucune règle n'a été appliquée : un seuil atteint plus tard, après
         * un arrêt en cours de trajet, déclenche encore — une fois.
         */
        ONCE_PER_START,
        /** À chaque passage en READY, comme à l'origine. */
        EVERY_READY;

        companion object {
            /**
             * Mode à retenir d'après ce qui est enregistré. Le choix à trois positions l'emporte ;
             * à défaut, l'ancienne case si elle a été touchée (cochée = une fois par démarrage,
             * décochée exprès = à chaque READY) ; sinon le défaut.
             */
            fun resolve(stored: String?, legacyOnce: Boolean?): Trigger =
                entries.firstOrNull { it.name == stored }
                    ?: when (legacyOnce) {
                        true -> ONCE_PER_START
                        false -> EVERY_READY
                        null -> START_ONLY
                    }
        }
    }

    data class Config(
        val enabled: Boolean,
        val hot: Rule,
        val cold: Rule,
        val trigger: Trigger = Trigger.START_ONLY
    )

    fun read(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun rule(prefix: String, defThreshold: Int, defTarget: Int) = Rule(
            active       = p.getBoolean(prefix + "on", false),
            threshold    = p.getInt(prefix + "threshold", defThreshold),
            targetTemp   = p.getInt(prefix + "target", defTarget),
            fanLevel     = p.getInt(prefix + "fan", DEFAULT_FAN),
            defrostFront = p.getBoolean(prefix + "def_front", false),
            defrostRear  = p.getBoolean(prefix + "def_rear", false),
            autoMode     = p.getBoolean(prefix + "auto", false),
            loopMode     = if (p.getBoolean(prefix + "recirc_force", false))
                               p.getInt(prefix + "recirc", DEFAULT_LOOP).coerceIn(0, 2)
                           else null
        )
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            hot     = rule(HOT,  DEFAULT_HOT_THRESHOLD,  DEFAULT_HOT_TARGET),
            cold    = rule(COLD, DEFAULT_COLD_THRESHOLD, DEFAULT_COLD_TARGET),
            trigger = Trigger.resolve(
                stored = p.getString(KEY_TRIGGER, null),
                legacyOnce = if (p.contains(KEY_ONCE_LEGACY)) p.getBoolean(KEY_ONCE_LEGACY, false) else null
            )
        )
    }

    // ── Écriture (utilisée par l'UI) ─────────────────────────────────────────
    fun keyOn(hot: Boolean)        = (if (hot) HOT else COLD) + "on"
    fun keyThreshold(hot: Boolean) = (if (hot) HOT else COLD) + "threshold"
    fun keyTarget(hot: Boolean)    = (if (hot) HOT else COLD) + "target"
    fun keyFan(hot: Boolean)       = (if (hot) HOT else COLD) + "fan"
    fun keyDefFront(hot: Boolean)  = (if (hot) HOT else COLD) + "def_front"
    fun keyDefRear(hot: Boolean)   = (if (hot) HOT else COLD) + "def_rear"
    fun keyAuto(hot: Boolean)      = (if (hot) HOT else COLD) + "auto"
    fun keyRecircForce(hot: Boolean) = (if (hot) HOT else COLD) + "recirc_force"
    fun keyRecirc(hot: Boolean)    = (if (hot) HOT else COLD) + "recirc"

    fun clampThreshold(raw: Int?, hot: Boolean): Int =
        (raw ?: if (hot) DEFAULT_HOT_THRESHOLD else DEFAULT_COLD_THRESHOLD)
            .coerceIn(MIN_TEMP, MAX_TEMP)

    fun clampTarget(raw: Int?, hot: Boolean): Int =
        (raw ?: if (hot) DEFAULT_HOT_TARGET else DEFAULT_COLD_TARGET)
            .coerceIn(MIN_TARGET, MAX_TARGET)

    fun clampFan(raw: Int?): Int = (raw ?: DEFAULT_FAN).coerceIn(MIN_FAN, MAX_FAN)
}
