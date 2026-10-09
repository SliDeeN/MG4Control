package com.mg4.control.model

/**
 * Sièges chauffants — atteindre un niveau sur une voiture qui ne connaît qu'une commande : « un
 * cran ».
 *
 * L'écran clim d'origine envoie 1 à chaque clic et c'est la voiture qui choisit le niveau
 * suivant ; le sens n'est écrit nulle part dans le firmware. Il a été établi sur véhicule
 * (SWI133, 2026-10-09) : **chaque cran fait DESCENDRE** — éteint → 3 → 2 → 1 → éteint. Sept
 * essais sur les boutons de niveau, faits alors que le compte supposait à tort que le cran
 * montait, l'ont montré sans ambiguïté (demander 1 depuis éteint donnait 3, demander 2 depuis 3
 * éteignait le siège…) ; ils sont rejoués dans les tests.
 *
 * Conséquence : descendre d'un niveau coûte un cran, MONTER d'un niveau en coûte trois, en
 * passant par « éteint ». Aucun compte ne peut y échapper.
 *
 * La voiture annonce aussi son nouveau niveau avec retard : on lit donc UNE fois, on compte, on
 * envoie, puis on vérifie sans jamais recliquer. Sans vue ni véhicule : la lecture, le cran et
 * l'attente sont fournis par l'appelant.
 */
object SeatHeat {

    const val MAX_LEVEL = 3
    private const val CYCLE = MAX_LEVEL + 1

    /** Écart entre deux crans : trop rapprochés, la voiture pourrait en ignorer un. */
    const val CLICK_INTERVAL_MS = 600L

    /**
     * Délai après lequel le niveau LU reflète un cran qui vient de partir. Valeur prudente, non
     * mesurée : compter d'après une lecture en retard enverrait un cran de trop ou de moins.
     */
    const val READBACK_MS = 2_000L

    private const val VERIFY_POLL_MS = 250L
    private const val VERIFY_TIMEOUT_MS = 3_000L

    /**
     * Niveau où un cran amène la voiture : éteint → 3 → 2 → 1 → éteint. C'est ce qu'il faut
     * annoncer après un appui (pop-up HVAC, raccourci) ; null si le niveau de départ est illisible.
     */
    fun next(actuel: Int?): Int? =
        actuel?.let { (it.coerceIn(0, MAX_LEVEL) + CYCLE - 1) % CYCLE }

    /**
     * Nombre de crans à envoyer pour passer de [actuel] à [cible] ; null si l'un des deux n'est
     * pas un niveau (lecture en échec, cible inconnue).
     */
    fun steps(actuel: Int, cible: Int): Int? {
        if (actuel !in 0..MAX_LEVEL || cible !in 0..MAX_LEVEL) return null
        return ((actuel - cible) % CYCLE + CYCLE) % CYCLE
    }

    /**
     * Amène le siège à [cible] : une lecture, le nombre de crans qui convient, puis une
     * vérification qui ne fait que LIRE.
     *
     * Rend false sans rien envoyer si le niveau est illisible — envoyer des crans à l'aveugle
     * ferait tourner le siège sans fin — et false aussi si la voiture n'a pas annoncé [cible] au
     * bout de la vérification. Dans ce cas on n'insiste pas : avec une lecture en retard, un cran
     * de rattrapage partirait d'un niveau faux.
     *
     * La vérification ne peut pas être trompée par un niveau traversé en route : une série compte
     * au plus trois crans, donc quatre niveaux distincts, et [cible] est le dernier.
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
