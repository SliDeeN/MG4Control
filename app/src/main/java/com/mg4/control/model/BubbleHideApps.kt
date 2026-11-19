package com.mg4.control.model

/**
 * Boutons flottants masqués dans des applications choisies (issue #261, 2026-11-19) : quand une de
 * ces applications est au premier plan, toutes les bulles disparaissent, et reviennent quand on la
 * quitte. Réglage commun à tous les profils. Les règles, sans Android.
 */
object BubbleHideApps {

    /** Relecture de l'application au premier plan, comme la zone clim de la projection. */
    const val SONDE_MS = 1_000L

    /** Les bulles se cachent : option active et [premierPlan] dans la liste. */
    fun masquer(active: Boolean, choisies: Set<String>, premierPlan: String?): Boolean =
        active && premierPlan != null && premierPlan in choisies

    /** Rien à surveiller : option coupée ou liste vide. */
    fun surveiller(active: Boolean, choisies: Set<String>): Boolean = active && choisies.isNotEmpty()

    /**
     * La liste proposée : les projections installées d'abord (Android Auto, CarPlay — elles ne sont
     * pas « lançables »), puis les applications lançables par ordre alphabétique, sans MG4Control
     * [soi] ni doublon.
     */
    fun candidats(projections: List<Pair<String, String>>, lancables: List<Pair<String, String>>, soi: String): List<Pair<String, String>> {
        val tete = projections.filter { it.first != soi }
        val dejaVus = tete.map { it.first }.toSet()
        val reste = lancables.filter { (p, _) -> p != soi && p !in dejaVus }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        return tete + reste
    }
}
