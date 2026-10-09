package com.mg4.control.model

/**
 * Sièges chauffants — atteindre un niveau sur une voiture qui ne sait qu'AVANCER d'un cran.
 *
 * La commande du véhicule est un appui : éteint → 1 → 2 → 3 → éteint, toujours dans ce sens
 * (l'écran clim d'origine envoie 1 à chaque clic). Et la voiture annonce son nouveau niveau avec
 * retard. Lire, cliquer, relire, recliquer ne marche donc pas : un cran part en trop dès que la
 * lecture tarde, et il faut refaire tout le tour — « 1 → 2 » donnait 2, 3, éteint, 1, 2
 * (constaté sur SWI133 le 2026-10-09).
 *
 * D'où la règle : on lit UNE fois, on compte, on envoie, et on ne reclique jamais d'après une
 * relecture. Sans vue ni véhicule : la lecture, le cran et l'attente sont fournis par l'appelant.
 */
object SeatHeat {

    const val MAX_LEVEL = 3

    /** Écart entre deux crans : trop rapprochés, la voiture pourrait en ignorer un. */
    const val CLICK_INTERVAL_MS = 600L

    /** Délai après lequel le niveau LU reflète un cran qui vient de partir. */
    const val READBACK_MS = 1_200L

    private const val VERIFY_POLL_MS = 250L
    private const val VERIFY_TIMEOUT_MS = 3_000L

    /**
     * Nombre de crans à envoyer pour passer de [actuel] à [cible] ; null si l'un des deux n'est
     * pas un niveau (lecture en échec, cible inconnue). Descendre d'un niveau en coûte trois :
     * la voiture ne recule pas.
     */
    fun steps(actuel: Int, cible: Int): Int? {
        if (actuel !in 0..MAX_LEVEL || cible !in 0..MAX_LEVEL) return null
        val cycle = MAX_LEVEL + 1
        return ((cible - actuel) % cycle + cycle) % cycle
    }

    /**
     * Amène le siège à [cible] : une lecture, le nombre de crans qui convient, puis une
     * vérification qui ne fait que LIRE.
     *
     * Rend false sans rien envoyer si le niveau est illisible — envoyer des crans à l'aveugle
     * ferait tourner le siège sans fin — et false aussi si la voiture n'a pas annoncé [cible] au
     * bout de la vérification. Dans ce cas on n'insiste pas : avec une lecture en retard, un cran
     * de rattrapage serait justement le cran de trop.
     *
     * @param lire     niveau annoncé par la voiture (négatif = illisible)
     * @param cran     envoie UN cran
     * @param attendre patiente le nombre de millisecondes donné
     */
    fun reach(cible: Int, lire: () -> Int, cran: () -> Unit, attendre: (Long) -> Unit): Boolean {
        val crans = steps(lire(), cible) ?: return false
        if (crans == 0) return true
        repeat(crans) { rang ->
            if (rang > 0) attendre(CLICK_INTERVAL_MS)
            cran()
        }
        var patience = 0L
        while (patience < VERIFY_TIMEOUT_MS) {
            attendre(VERIFY_POLL_MS)
            patience += VERIFY_POLL_MS
            if (lire() == cible) return true
        }
        return false
    }
}
