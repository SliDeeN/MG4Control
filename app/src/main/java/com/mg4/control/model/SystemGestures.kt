package com.mg4.control.model

/**
 * Glissements depuis le bord de l'écran (2026-11-20).
 *
 * SAIC a ajouté un verrou devant les quatre glissements d'Android 9 (`PhoneWindowManager$8`
 * `onSwipeFromTop/Left/Right/Bottom` testent d'abord `mSystemGesturesEnable`) : ce champ est lu
 * UNE fois, au démarrage, dans la propriété [PROP], fausse par défaut. Aucun firmware ne la règle
 * (SWI69/131/132/133/173) : sans elle, une barre cachée par le plein écran ne revient jamais au
 * glissement. Une application système peut l'écrire (`system_app` → `system_prop`, SELinux des
 * 5 firmwares), mais la voiture ne la relit qu'au démarrage suivant de l'écran central.
 *
 * Kotlin pur : décide quoi écrire et quand prévenir que le redémarrage manque.
 */
object SystemGestures {

    const val PROP = "persist.sys.gestures.enable"

    enum class Action { RIEN, ACTIVER, RESTAURER }

    /**
     * [voulu] : le plein écran par application est actif. [lu] : la valeur actuelle (null = absente).
     * [parNous] : c'est MG4Control qui l'a mise à vrai. On ne remet à faux que ce qu'on a écrit.
     */
    fun action(voulu: Boolean, lu: Boolean?, parNous: Boolean): Action = when {
        voulu && lu != true -> Action.ACTIVER
        !voulu && parNous && lu == true -> Action.RESTAURER
        else -> Action.RIEN
    }

    /**
     * Le redémarrage manque : la propriété est vraie, c'est nous qui l'avons écrite, et l'écran
     * n'a pas redémarré depuis (même numéro de démarrage). Numéro illisible : on ne prévient pas.
     */
    fun enAttente(lu: Boolean?, demarrageEcriture: Int?, demarrageActuel: Int?): Boolean =
        lu == true && demarrageEcriture != null && demarrageActuel != null && demarrageEcriture == demarrageActuel

    /** Même lecture que `SystemProperties.getBoolean` d'Android ; null si absente ou illisible. */
    fun lire(texte: String?): Boolean? = when (texte?.trim()?.lowercase()) {
        "1", "y", "yes", "on", "true" -> true
        "0", "n", "no", "off", "false" -> false
        else -> null
    }
}
