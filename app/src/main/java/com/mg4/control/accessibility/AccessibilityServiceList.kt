package com.mg4.control.accessibility

/**
 * Lecture de `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` : des composants séparés par « : »,
 * chacun sous la forme LONGUE `paquet/paquet.Classe` (ce qu'écrivent les Réglages,
 * `flattenToString`) ou COURTE `paquet/.Classe` (ce qu'écrit le système quand il réécrit toute la
 * liste, `flattenToShortString`). Android lit les deux (`ComponentName.unflattenFromString`).
 *
 * Né d'un faux « service inactif » (2026-10-17) : après une MAJ de l'appli ou un redémarrage de
 * la voiture, la liste revenait en forme courte ; comparée à la seule forme longue, elle disait
 * le service coupé alors que le joystick naviguait dans les pop-ups. Le désactiver puis le
 * réactiver dans les Réglages « réparait » : les Réglages réécrivent toute la liste en forme longue.
 *
 * Logique pure, sans Android : testée sur la JVM.
 */
object AccessibilityServiceList {

    /** Comment un composant figure dans la liste. */
    enum class Forme { LONGUE, COURTE, ABSENTE }

    /**
     * Forme sous laquelle [paquet]/[classe] (nom complet de la classe) figure dans [liste], la
     * valeur brute du réglage (`null` : réglage vide). Une entrée longue l'emporte sur une courte.
     */
    fun forme(liste: String?, paquet: String, classe: String): Forme {
        var trouvee = Forme.ABSENTE
        for (brute in liste.orEmpty().split(':')) {
            val entree = brute.trim()
            // Même découpage que ComponentName.unflattenFromString : paquet avant le « / »,
            // classe après ; une classe qui commence par « . » se lit dans le paquet.
            val sep = entree.indexOf('/')
            if (sep <= 0 || sep == entree.length - 1) continue
            val p = entree.substring(0, sep)
            val c = entree.substring(sep + 1)
            val courte = c.startsWith(".")
            if (!p.equals(paquet, ignoreCase = true)) continue
            if (!(if (courte) p + c else c).equals(classe, ignoreCase = true)) continue
            if (!courte) return Forme.LONGUE
            trouvee = Forme.COURTE
        }
        return trouvee
    }

    /** Vrai si [paquet]/[classe] figure dans [liste], sous l'une ou l'autre forme. */
    fun contient(liste: String?, paquet: String, classe: String): Boolean =
        forme(liste, paquet, classe) != Forme.ABSENTE
}
